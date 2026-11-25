package dev.voxelbrew.gui;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.importer.ImportDialog;
import dev.voxelbrew.api.importer.ImportWindow;
import dev.voxelbrew.api.importer.ServiceLoaderImporterProviderRegistry;
import dev.voxelbrew.api.model.*;
import dev.voxelbrew.engine.model.*;
import dev.voxelbrew.exporter.DocumentWriter;
import dev.voxelbrew.importer.DocumentReader;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentControllerTest {
    private final ConcurrentLinkedQueue<Runnable> callbacks = new ConcurrentLinkedQueue<>();

    @TempDir
    Path directory;

    private static byte[] versionedDocument(
            dev.voxelbrew.engine.persistence.proto.DocumentProto.Document document, String version) throws Exception {
        byte[] payload =
                document.toBuilder().setApplicationVersion(version).build().toByteArray();
        return java.nio.ByteBuffer.allocate(44 + payload.length)
                .put(new byte[] {'J', 'C', 'A', 'D', 13, 37, 13, 37})
                .putInt(payload.length)
                .put(java.security.MessageDigest.getInstance("SHA-256").digest(payload))
                .put(payload)
                .array();
    }

    private static Feature.Sketch sketch(String id, double z) {
        return new Feature.Sketch(
                id,
                id + ".profile",
                new Feature.Plane(z == 0 ? "origin-xy" : "offset", z),
                new Feature.Rectangle(0, 0, 60, 40));
    }

    @Test
    void editingAnExtrusionReusesIndependentMeshesOnEitherSideInHistory() {
        var first = sketch("first", 0);
        var second = sketch("second", 10);
        var a = new Feature.Extrude("a", new Feature.ProfileRef("first", first.profileId()), 2);
        var b = new Feature.Extrude("b", new Feature.ProfileRef("second", second.profileId()), 3);
        var session = new ModelSession(new CadDocument("model", List.of(first, a, second, b), 4));
        var evaluator = new DocumentController.DisplayEvaluator(directory);
        var before = evaluator.apply(session.document()).bodies();
        session.replace(new Feature.Extrude(b.id(), b.profile(), 4));
        var after = evaluator.apply(session.document()).bodies();
        assertThat(after.getFirst()).isSameAs(before.getFirst());
        assertThat(after.getLast().volume()).isCloseTo(9600, within(.001));
        session.replace(new Feature.Extrude(a.id(), a.profile(), 1));
        var upstream = evaluator.apply(session.document()).bodies();
        assertThat(upstream.getFirst().volume()).isCloseTo(2400, within(.001));
        assertThat(upstream.getLast()).isSameAs(after.getLast());
    }

    @Test
    void editPropagatesThroughOnlyTheAffectedTargetOfAMultiBodyCut() {
        var first = sketch("first", 0);
        var second = sketch("second", 10);
        var a = new Feature.Extrude("a", new Feature.ProfileRef(first.id(), first.profileId()), 2);
        var b = new Feature.Extrude("b", new Feature.ProfileRef(second.id(), second.profileId()), 3);
        var hole = new Feature.Sketch("hole", "circle", first.plane(), new Feature.Circle(10, 10, 4));
        var cut = new CutTargets(List.of(new Feature.BodyRef("a", "body"), new Feature.BodyRef("b", "body")))
                .throughFeature("cut", new Feature.ProfileRef(hole.id(), hole.profileId()));
        var pattern = new BodyPattern(
                        BodyPattern.Kind.RECTANGULAR,
                        new Feature.BodyRef("cut", "body-2"),
                        BodyPattern.Axis.X,
                        2,
                        1,
                        80,
                        0)
                .feature("pattern");
        var session = new ModelSession(new CadDocument("multi", List.of(first, a, second, b, hole, cut, pattern), 7));
        var evaluator = new DocumentController.DisplayEvaluator(directory);
        var before = evaluator.apply(session.document()).bodies();
        session.editDimension("a", "distance", 4);
        var after = evaluator.apply(session.document()).bodies();
        assertThat(after).hasSize(3);
        assertThat(after.getFirst().volume()).isCloseTo(2 * before.getFirst().volume(), within(.001));
        assertThat(after.get(1)).isSameAs(before.get(1));
        assertThat(after.get(2)).isSameAs(before.get(2));
        session.editDimension("b", "distance", 6);
        var changed = evaluator.apply(session.document()).bodies();
        assertThat(changed.getFirst()).isSameAs(after.getFirst());
        assertThat(changed.get(2).volume()).isCloseTo(2 * after.get(2).volume(), within(.001));
    }

    @Test
    void changingNozzleRebuildsAnExtrusionBoundToThePrintProfile() throws Exception {
        try (var controller = new DocumentController(callbacks::add)) {
            var profile = sketch("base", 0);
            controller.add(profile);
            await(() -> !controller.rebuilding());
            controller.previewExtrusion(profile, "$vb.slicer.nozzle_diameter");
            await(() -> !controller.rebuilding());
            controller.commitPreview();
            await(() -> !controller.rebuilding());
            assertThat(controller.canExport()).isTrue();
            assertThat(controller.build().bodies().getFirst().volume()).isCloseTo(960, within(.001));

            var setup = controller.snapshot().document().printSetup();
            controller.printSetup(setup.withNozzle(.6));
            assertThat(controller.canExport()).isFalse();
            await(() -> !controller.rebuilding());
            assertThat(controller.canExport()).isTrue();
            assertThat(controller.build().bodies().getFirst().volume()).isCloseTo(1440, within(.001));
        }
    }

    @Test
    void changingPlacementReusesTheEvaluatedCadBody() throws Exception {
        try (var controller = new DocumentController(callbacks::add)) {
            var profile = sketch("base", 0);
            controller.add(profile);
            await(() -> !controller.rebuilding());
            controller.previewExtrusion(profile, "5");
            await(() -> !controller.rebuilding());
            controller.commitPreview();
            await(() -> !controller.rebuilding());
            var build = controller.build();
            var setup = controller.snapshot().document().printSetup();
            var instance = new BodyInstance("placed", build.bodies().getFirst().id(), Transform.translation(12, 7, 0));
            controller.printSetup(setup.withInstances(List.of(instance)));
            assertThat(controller.rebuilding()).isFalse();
            assertThat(controller.build()).isSameAs(build);
            assertThat(controller.snapshot().document().printSetup().instances())
                    .containsExactly(instance);
        }
    }

    @Test
    void selectedRegionPreviewCommitsAndReopensWithoutChangingSketch() throws Exception {
        var g = dev.voxelbrew.engine.sketch.Sketches.rectangle("r", 0, 0, 20, 10, false);
        var entities = new java.util.ArrayList<>(g.entities());
        entities.add(dev.voxelbrew.engine.sketch.Sketches.entity(
                "roof-left", dev.voxelbrew.api.model.sketch.SketchGeometry.Kind.LINE, false, 0, 10, 10, 20));
        entities.add(dev.voxelbrew.engine.sketch.Sketches.entity(
                "roof-right", dev.voxelbrew.api.model.sketch.SketchGeometry.Kind.LINE, false, 10, 20, 20, 10));
        g = new dev.voxelbrew.api.model.sketch.SketchGeometry(
                entities,
                g.constraints(),
                entities.stream()
                        .map(dev.voxelbrew.api.model.sketch.SketchGeometry.Entity::id)
                        .toList());
        var sketch =
                new Feature.Sketch("sketch", "profile", new Feature.Plane("origin-xy", 0), new Feature.Constrained(g));
        var region =
                dev.voxelbrew.engine.sketch.SketchProfiles.selectableRegions(g).getFirst();
        String profileId = dev.voxelbrew.engine.sketch.SketchProfiles.regionProfileId("profile", region);
        try (var controller = new DocumentController(callbacks::add)) {
            controller.add(sketch);
            await(() -> !controller.rebuilding());
            controller.previewExtrusion(sketch, profileId, "5");
            await(() -> !controller.rebuilding());
            assertThat(controller.build().bodies().getFirst().volume())
                    .isCloseTo(1000, org.assertj.core.data.Offset.offset(.001));
            controller.cancelPreview();
            await(() -> !controller.rebuilding());
            assertThat(controller.snapshot().document().features()).isEqualTo(List.of(sketch));
            controller.previewExtrusion(sketch, profileId, "8");
            await(() -> !controller.rebuilding());
            controller.commitPreview();
            await(() -> !controller.rebuilding());
            assertThat(controller.build().bodies().getFirst().volume())
                    .isCloseTo(1600, org.assertj.core.data.Offset.offset(.001));
            var saving = controller.save(directory.resolve("region.vxb"), false);
            await(saving::isDone);
            saving.join();
            controller.undo();
            await(() -> !controller.rebuilding());
            assertThat(controller.snapshot().document().features()).isEqualTo(List.of(sketch));
            var opening = controller.open(directory.resolve("region.vxb"));
            await(() -> opening.isDone() && !controller.rebuilding());
            opening.join();
            assertThat(controller.build().bodies().getFirst().volume())
                    .isCloseTo(1600, org.assertj.core.data.Offset.offset(.001));
            assertThat(controller.snapshot().document().features().getFirst()).isEqualTo(sketch);
        }
    }

    @Test
    void olderFilesUpgradeAndFutureFilesPreserveCleanAndDirtyDocuments() throws Exception {
        var original = new CadDocument("versioned-design", List.of(sketch("plate", 0)), 1);
        Path file = directory.resolve("older version design.vxb");
        DocumentWriter.save(original, file, false);
        byte[] saved = Files.readAllBytes(file);
        var message = dev.voxelbrew.engine.persistence.proto.DocumentProto.Document.parseFrom(
                java.util.Arrays.copyOfRange(saved, 44, saved.length));
        assertThat(message.getApplicationVersion()).isEqualTo(dev.voxelbrew.util.Version.version());
        byte[] older = versionedDocument(message, "0.0.0");
        Files.write(file, older);
        Path futureFile = directory.resolve("future version design.vxb");
        byte[] future = versionedDocument(message, "65534.65534.65534");
        Files.write(futureFile, future);
        try (var controller = new DocumentController(callbacks::add)) {
            var opened = controller.open(file);
            await(() -> opened.isDone() && !controller.rebuilding());
            opened.join();
            assertThat(controller.snapshot().document()).isEqualTo(original);
            assertThat(controller.dirty()).isFalse();
            assertThat(Files.readAllBytes(file)).containsExactly(older);
            var saving = controller.save(file, true);
            await(saving::isDone);
            saving.join();
            byte[] upgraded = Files.readAllBytes(file);
            assertThat(dev.voxelbrew.engine.persistence.proto.DocumentProto.Document.parseFrom(
                                    java.util.Arrays.copyOfRange(upgraded, 44, upgraded.length))
                            .getApplicationVersion())
                    .isEqualTo(dev.voxelbrew.util.Version.version());
            assertThat(DocumentReader.load(file)).isEqualTo(original);
            for (boolean dirty : List.of(false, true)) {
                if (dirty) {
                    controller.edit("plate", "width", 61);
                    await(() -> !controller.rebuilding());
                }
                var before = controller.snapshot();
                var failed = controller.open(futureFile);
                await(failed::isDone);
                assertThat(failed.isCompletedExceptionally()).isTrue();
                assertThat(controller.ioBusy()).isFalse();
                assertThat(controller.snapshot()).isEqualTo(before);
                assertThat(controller.path()).isEqualTo(file.toAbsolutePath());
                assertThat(controller.dirty()).isEqualTo(dirty);
                assertThat(controller.error().contains("VoxelBrew 65534.65534.65534"))
                        .isTrue();
                assertThat(controller.error().contains("supported version " + dev.voxelbrew.util.Version.version()))
                        .isTrue();
                assertThat(controller.error().contains("Update VoxelBrew")).isTrue();
                assertThat(Files.readAllBytes(futureFile)).containsExactly(future);
                assertThat(Files.readAllBytes(file)).containsExactly(upgraded);
            }
        }
    }

    @Test
    void bodyStylesDirtySaveUndoAndRejectStaleOrInactiveSelections() throws Exception {
        try (var controller = new DocumentController(callbacks::add)) {
            controller.add(sketch("plate", 0));
            controller.add(new Feature.Extrude("solid", new Feature.ProfileRef("plate", "plate.profile"), 5));
            await(() -> !controller.rebuilding());
            Path file = directory.resolve("body style.vxb");
            controller.save(file, false);
            await(() -> !controller.ioBusy());
            assertThat(controller.dirty()).isFalse();
            var red = new BodyStyle("PETG", "#FF0000");
            controller.bodyStyle("solid.body", red);
            assertThat(controller.dirty()).isTrue();
            assertThatThrownBy(() -> controller.bodyStyle("solid.body", red)).isInstanceOf(IllegalStateException.class);
            await(() -> !controller.rebuilding());
            assertThat(controller.build().bodies().getFirst().volume())
                    .isCloseTo(12000, org.assertj.core.data.Offset.offset(.01));
            assertThatThrownBy(() -> controller.bodyStyle("missing.body", red))
                    .isInstanceOf(IllegalStateException.class);
            controller.undo();
            await(() -> !controller.rebuilding());
            assertThat(controller.dirty()).isFalse();
            controller.redo();
            await(() -> !controller.rebuilding());
            assertThat(controller.snapshot().document().bodyStyle("solid.body")).isEqualTo(red);
            controller.save(file, true);
            await(() -> !controller.ioBusy());
            assertThat(DocumentReader.load(file).bodyStyle("solid.body")).isEqualTo(red);
            controller.cursor(1);
            await(() -> !controller.rebuilding());
            assertThatThrownBy(() -> controller.bodyStyle("solid.body", red)).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void importedDocumentsAreUnsavedAndFailuresPreserveTheCurrentDocument() throws Exception {
        Path plugins = Files.createDirectories(directory.resolve("import providers"));
        Files.copy(Path.of(System.getProperty("voxelbrew.test.plugin")), plugins.resolve("provider.jar"));
        Path source = directory.resolve("plate.vbplate");
        Files.writeString(source, "60,40,4");
        ImportWindow window = new ImportWindow() {
            @Override
            public <R> java.util.Optional<R> show(ImportDialog<R> dialog) {
                return java.util.Optional.of(
                        dialog.buildSettings(java.util.Map.of("source", source.toString(), "scale", "2")));
            }
        };
        try (var registry = new ServiceLoaderImporterProviderRegistry(plugins);
                var controller = new DocumentController(callbacks::add, plugins)) {
            var imported = controller.importDocument(registry.require("plate"), window);
            assertThat(controller.ioBusy()).isTrue();
            await(() -> imported.isDone() && !controller.rebuilding());
            imported.join();
            assertThat(controller.dirty()).isTrue();
            assertThat(controller.path()).isNull();
            assertThat(controller.build().bodies().getFirst().volume())
                    .isCloseTo(76800, org.assertj.core.data.Offset.offset(.01));
            Path saved = directory.resolve("saved.vxb");
            controller.save(saved, false);
            await(() -> !controller.ioBusy());
            assertThat(controller.dirty()).isFalse();
            controller.edit("thickness", "distance", 9);
            await(() -> !controller.rebuilding());
            var before = controller.snapshot();
            var failed = controller.importDocument(registry.require("failing"), window);
            await(failed::isDone);
            assertThat(failed.isCompletedExceptionally()).isTrue();
            assertThat(controller.snapshot()).isEqualTo(before);
            assertThat(controller.path()).isEqualTo(saved.toAbsolutePath());
            assertThat(controller.dirty()).isTrue();
            assertThat(controller.error().contains("Deliberate importer failure"))
                    .isTrue();
            assertThat(Files.readString(source)).isEqualTo("60,40,4");
        }
    }

    private void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            for (Runnable callback; (callback = callbacks.poll()) != null; ) callback.run();
            if (condition.getAsBoolean()) return;
            Thread.sleep(10);
        }
        fail("Timed out waiting for controller");
    }

    @Test
    void configurationsInvalidateGeometryAndUndoPreservesFormulaExtrusions() throws Exception {
        try (var controller = new DocumentController(callbacks::add)) {
            var sketch = sketch("plate", 0);
            controller.add(sketch);
            await(() -> !controller.rebuilding());
            var p = dev.voxelbrew.api.model.parameters.ParameterSet.empty()
                    .put("thickness", dev.voxelbrew.api.model.parameters.ParameterUnit.MM, "5", "")
                    .addConfiguration("Thick", false)
                    .override("thickness", "Thick", "8")
                    .addConfiguration("Invalid", false)
                    .override("thickness", "Invalid", "0");
            controller.parameters(p);
            await(() -> !controller.rebuilding());
            controller.previewExtrusion(sketch, "thickness");
            await(() -> !controller.rebuilding());
            controller.cancelPreview();
            await(() -> !controller.rebuilding());
            assertThat(controller.snapshot().document().parameters().bindings().isEmpty())
                    .isTrue();
            controller.previewExtrusion(sketch, "thickness");
            controller.commitPreview();
            await(() -> !controller.rebuilding());
            assertThat(controller.build().bodies().getFirst().volume())
                    .isCloseTo(12000, org.assertj.core.data.Offset.offset(.01));
            controller.undo();
            await(() -> !controller.rebuilding());
            assertThat(controller.snapshot().document().features().size()).isEqualTo(1);
            controller.redo();
            await(() -> !controller.rebuilding());
            assertThat(controller.snapshot().document().parameters().bindings().size())
                    .isEqualTo(1);
            controller.configuration("Thick");
            assertThat(controller.canExport()).isFalse();
            await(() -> !controller.rebuilding());
            assertThat(controller.build().bodies().getFirst().volume())
                    .isCloseTo(19200, org.assertj.core.data.Offset.offset(.01));
            assertThat(((Feature.Extrude) controller.effectiveFeatures().getLast()).distance())
                    .isEqualTo(8);
            controller.configuration("Invalid");
            await(() -> !controller.rebuilding());
            assertThat(controller.canExport()).isFalse();
            assertThat(controller.build().bodies().isEmpty()).isTrue();
            controller.undo();
            await(() -> !controller.rebuilding());
            assertThat(controller.canExport()).isTrue();
        }
    }

    @Test
    void customFeaturesRebuildOffThreadAndMissingPluginsNeverExposeStaleGeometry() throws Exception {
        Path plugins = Files.createDirectory(directory.resolve("custom plugins"));
        Path jar = plugins.resolve("provider.jar");
        Files.copy(Path.of(System.getProperty("voxelbrew.test.plugin")), jar);
        Feature.Custom custom;
        try (var registry = new dev.voxelbrew.engine.feature.FeatureRegistry(plugins)) {
            custom = registry.create("spacer", "example.spacer", "spacer", java.util.Map.of());
        }
        try (var controller = new DocumentController(callbacks::add, plugins)) {
            controller.add(custom);
            await(() -> !controller.rebuilding());
            assertThat(controller.canExport()).isTrue();
            assertThat(controller.build().bodies().getFirst().volume())
                    .isCloseTo(Math.PI * 96 * 8, org.assertj.core.data.Offset.offset(0.01));
            controller.edit("spacer", "height", 12);
            await(() -> !controller.rebuilding());
            assertThat(controller.build().bodies().getFirst().volume())
                    .isCloseTo(Math.PI * 96 * 12, org.assertj.core.data.Offset.offset(0.01));
            var before = controller.snapshot();
            Files.delete(jar);
            controller.rebuild();
            assertThat(controller.canExport()).isFalse();
            await(() -> !controller.rebuilding());
            assertThat(controller.build().valid()).isFalse();
            assertThat(controller.build().bodies().isEmpty()).isTrue();
            assertThat(controller.snapshot()).isEqualTo(before);
            Path file = directory.resolve("missing plugin.vxb");
            controller.save(file, false);
            await(() -> !controller.ioBusy());
            assertThat(DocumentReader.load(file)).isEqualTo(before.document());
            Files.copy(Path.of(System.getProperty("voxelbrew.test.plugin")), jar);
            controller.rebuild();
            await(() -> !controller.rebuilding());
            assertThat(controller.canExport()).isTrue();
            assertThat(controller.snapshot()).isEqualTo(before);
            controller.undo();
            await(() -> !controller.rebuilding());
            assertThat(controller.build().bodies().getFirst().volume())
                    .isCloseTo(Math.PI * 96 * 8, org.assertj.core.data.Offset.offset(0.01));
        }
    }

    @Test
    void previewsCancelWithoutTransactionsAndCommitAsOneUndoStep() throws Exception {
        try (var controller = new DocumentController(callbacks::add)) {
            var sketch = sketch("plate", 12);
            controller.add(sketch);
            await(() -> !controller.rebuilding());
            var saved = controller.snapshot();
            controller.previewExtrusion(sketch, -3);
            await(() -> !controller.rebuilding());
            assertThat(controller.snapshot().document().features().size()).isEqualTo(1);
            assertThat(controller.canExport()).isFalse();
            assertThat(controller.build().bodies().getFirst().volume())
                    .isCloseTo(7200, org.assertj.core.data.Offset.offset(0.01));
            controller.cancelPreview();
            await(() -> !controller.rebuilding());
            assertThat(controller.snapshot()).isEqualTo(saved);
            assertThat(controller.build().bodies().isEmpty()).isTrue();
            controller.previewExtrusion(sketch, 5);
            controller.previewExtrusion(sketch, 8);
            controller.commitPreview();
            await(() -> !controller.rebuilding());
            assertThat(controller.snapshot().document().features().size()).isEqualTo(2);
            assertThat(controller.snapshot().canUndo()).isTrue();
            assertThat(controller.canExport()).isTrue();
            controller.undo();
            await(() -> !controller.rebuilding());
            assertThat(controller.snapshot().document().features().size()).isEqualTo(1);
            assertThat(controller.snapshot().canRedo()).isTrue();
            controller.redo();
            await(() -> !controller.rebuilding());
            assertThat(controller.build().bodies().getFirst().volume())
                    .isCloseTo(19200, org.assertj.core.data.Offset.offset(0.01));
            long revision = controller.snapshot().revision();
            controller.cursor(1);
            await(() -> !controller.rebuilding());
            assertThat(controller.snapshot().revision() > revision).isTrue();
            assertThat(controller.canExport()).isFalse();
            controller.cursor(2);
            controller.undo();
            await(() -> !controller.rebuilding());
            assertThat(controller.snapshot().document().features().size()).isEqualTo(1);
        }
    }

    @Test
    void staleRebuildsCannotReplaceANewDocumentAndQueueCoalesces() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        try (var controller = new DocumentController(callbacks::add, document -> {
            if (calls.incrementAndGet() == 1) {
                entered.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Test latch timeout");
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
            }
            return DocumentController.evaluate(document);
        })) {
            controller.add(sketch("old", 0));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var oldDocument = controller.snapshot().document();
            controller.newDocument();
            controller.add(sketch("new", 0));
            controller.add(new Feature.Extrude("new-solid", new Feature.ProfileRef("new", "new.profile"), 8));
            release.countDown();
            await(() -> !controller.rebuilding());
            assertThat(controller.snapshot().document()).isNotSameAs(oldDocument);
            // Document-local counters can reuse IDs; stale work must still be discarded.
            assertThat(controller.snapshot().document().id()).isEqualTo(oldDocument.id());
            assertThat(calls.get()).isEqualTo(2);
            assertThat(controller.build().bodies().getFirst().id()).isEqualTo("new-solid.body");
            assertThat(controller.build().diagnostics().stream()
                            .noneMatch(d -> d.featureId().equals("old")))
                    .isTrue();
        } finally {
            release.countDown();
        }
    }

    @Test
    void savesReopenWithStableIdsAndFailedOpenPreservesCurrentDocument() throws Exception {
        try (var controller = new DocumentController(callbacks::add)) {
            var s = sketch("plate", 0);
            controller.add(s);
            controller.previewExtrusion(s, 8);
            controller.commitPreview();
            await(() -> !controller.rebuilding());
            assertThat(controller.dirty()).isTrue();
            Path file = directory.resolve("saved design.vxb");
            var saving = controller.save(file, false);
            await(saving::isDone);
            assertThat(controller.dirty()).isFalse();
            var original = controller.snapshot().document();
            controller.edit(original.features().getLast().id(), "distance", 5);
            await(() -> !controller.rebuilding());
            assertThat(controller.dirty()).isTrue();
            var opening = controller.open(file);
            await(() -> opening.isDone() && !controller.rebuilding());
            assertThat(controller.snapshot().document()).isEqualTo(original);
            assertThat(controller.dirty()).isFalse();
            Files.write(directory.resolve("bad.vxb"), new byte[] {1, 2, 3});
            var failed = controller.open(directory.resolve("bad.vxb"));
            await(failed::isDone);
            assertThat(failed.isCompletedExceptionally()).isTrue();
            assertThat(controller.snapshot().document()).isEqualTo(original);
            assertThat(DocumentReader.load(file)).isEqualTo(original);
        }
    }

    @Test
    void brokenDefinitionsClearGeometryAndRemainRepairable() throws Exception {
        var s = sketch("plate", 0);
        var invalid = new Feature.Extrude("solid", new Feature.ProfileRef("missing", "missing.profile"), 8);
        Path file = directory.resolve("broken.vxb");
        DocumentWriter.save(new CadDocument("broken", List.of(s, invalid), 2), file, false);
        try (var controller = new DocumentController(callbacks::add)) {
            var opening = controller.open(file);
            await(() -> opening.isDone() && !controller.rebuilding());
            assertThat(controller.build().valid()).isFalse();
            assertThat(controller.build().bodies().isEmpty()).isTrue();
            assertThat(controller.canExport()).isFalse();
            controller.replaceFeature(new Feature.Extrude("solid", new Feature.ProfileRef(s.id(), s.profileId()), 8));
            await(() -> !controller.rebuilding());
            assertThat(controller.canExport()).isTrue();
            assertThat(controller.build().bodies().getFirst().volume())
                    .isCloseTo(19200, org.assertj.core.data.Offset.offset(0.01));
        }
    }

    @Test
    void editsDuringSaveRemainDirtyAndDoNotChangeTheCapturedFile() throws Exception {
        try (var controller = new DocumentController(callbacks::add)) {
            controller.add(sketch("plate", 0));
            await(() -> !controller.rebuilding());
            var captured = controller.snapshot().document();
            var file = directory.resolve("save snapshot.vxb");
            var saving = controller.save(file, false);
            controller.edit("plate", "width", 61);
            await(() -> saving.isDone() && !controller.rebuilding());
            assertThat(DocumentReader.load(file)).isEqualTo(captured);
            assertThat(controller.dirty()).isTrue();
            assertThat(((Feature.Rectangle) ((Feature.Sketch) controller
                                            .snapshot()
                                            .document()
                                            .features()
                                            .getFirst())
                                    .profile())
                            .width())
                    .isEqualTo(61);
        }
    }
}
