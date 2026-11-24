package dev.voxelbrew.engine.model;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.model.BodyStyle;
import dev.voxelbrew.api.model.CadDocument;
import dev.voxelbrew.api.model.Feature;
import dev.voxelbrew.api.model.ModelException;
import dev.voxelbrew.engine.persistence.DocumentCodec;
import dev.voxelbrew.engine.persistence.DocumentFormatException;
import dev.voxelbrew.engine.persistence.proto.DocumentProto;
import dev.voxelbrew.exporter.DocumentWriter;
import dev.voxelbrew.importer.DocumentReader;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@SuppressWarnings("deprecation")
class EditableEngineTest {
    private static final Feature.Plane XY = new Feature.Plane("origin-xy", 0);

    @TempDir
    Path directory;

    static ModelSession reference() {
        var session = new ModelSession(CadDocument.empty());
        session.add(new Feature.Sketch("plate", "plate.profile", XY, new Feature.Rectangle(0, 0, 60, 40)));
        session.add(new Feature.Extrude("thickness", new Feature.ProfileRef("plate", "plate.profile"), 5));
        session.add(new Feature.Sketch("hole", "hole.profile", XY, new Feature.Circle(10, 10, 4)));
        session.add(new Feature.ThroughCut(
                "cut", new Feature.ProfileRef("hole", "hole.profile"), new Feature.BodyRef("thickness", "body")));
        return session;
    }

    private static void assertReference(ModelSession session, double thickness, double diameter) {
        try (var result = session.evaluate()) {
            assertThat(result.valid()).as(result.diagnostics().toString()).isTrue();
            assertThat(result.requireCompleteDocument().size()).isEqualTo(1);
            var body = result.bodies().getFirst();
            assertThat(body.id()).isEqualTo("thickness.body");
            assertThat(body.solid().volume())
                    .isCloseTo(
                            (2400 - Math.PI * diameter * diameter / 4) * thickness,
                            org.assertj.core.data.Offset.offset(0.01));
            assertThat(body.solid().contains(10, 10, thickness / 2)).isFalse();
            assertThat(body.solid().contains(15, 10, thickness / 2)).isTrue();
            assertThat(body.solid().bounds().maxX()).isCloseTo(60, org.assertj.core.data.Offset.offset(0.001));
            assertThat(body.solid().bounds().maxY()).isCloseTo(40, org.assertj.core.data.Offset.offset(0.001));
            assertThat(body.solid().bounds().maxZ()).isCloseTo(thickness, org.assertj.core.data.Offset.offset(0.001));
        }
    }

    @Test
    void solidModificationsSurviveDocumentRoundTrip() throws Exception {
        var session = new ModelSession(CadDocument.empty());
        session.add(new Feature.Sketch(
                "profile", "rectangle", Feature.Plane.origin("XY"), new Feature.Rectangle(0, 0, 20, 20)));
        session.add(new Feature.Extrude("block", new Feature.ProfileRef("profile", "rectangle"), 10));
        session.add(new Feature.EdgeFinish(
                "round",
                new Feature.BodyRef("block", "body"),
                Feature.EdgeFinish.Kind.FILLET,
                1,
                java.util.List.of(1)));
        session.add(new Feature.Shell("hollow", new Feature.BodyRef("round", "body"), 1, 1));
        var document = DocumentCodec.decode(DocumentCodec.encode(session.document()));
        org.assertj.core.api.Assertions.assertThat(document.features())
                .isEqualTo(session.document().features());
        try (var evaluation = new ModelSession(document).evaluate()) {
            evaluation.requireCompleteDocument();
        }
    }

    @Test
    void bodyStylesFollowStableIdsThroughCutSplitSaveAndGeometryEdits() throws Exception {
        var session = reference();
        var red = new BodyStyle("PETG", "#FF0000");
        session.setBodyStyle("thickness.body", red);
        session.editDimension("thickness", "distance", 8);
        try (var evaluation = session.evaluate()) {
            assertThat(evaluation.valid()).isTrue();
            assertThat(evaluation.bodies().getFirst().id()).isEqualTo("thickness.body");
            assertThat(evaluation.bodies().getFirst().solid().volume())
                    .isCloseTo(19099.469035085127, org.assertj.core.data.Offset.offset(.01));
        }
        session.add(new Feature.SplitBody(
                "split", new Feature.BodyRef("cut", "body"), new Feature.SplitPlane(1, 0, 0, 30)));
        session.setBodyStyle("split.positive", new BodyStyle("PLA", "#0000FF"));
        Path file = directory.resolve("colored split bodies.vxb");
        DocumentWriter.save(session.document(), file, false);
        var reopened = new ModelSession(DocumentReader.load(file));
        try (var evaluation = reopened.evaluate()) {
            assertThat(evaluation.valid()).isTrue();
            assertThat(evaluation.bodies().size()).isEqualTo(2);
            assertThat(evaluation.bodies().stream()
                            .mapToDouble(b -> b.solid().volume())
                            .sum())
                    .isCloseTo(19099.469035085127, org.assertj.core.data.Offset.offset(.01));
            assertThat(reopened.document().bodyStyle("split.negative")).isEqualTo(red);
            assertThat(reopened.document().bodyStyle("split.positive").material())
                    .isEqualTo("PLA");
        }
        reopened.setCursor(3);
        try (var evaluation = reopened.evaluate()) {
            assertThat(reopened.document()
                            .bodyStyle(evaluation.bodies().getFirst().id()))
                    .isEqualTo(red);
            assertThat(evaluation.bodies().getFirst().solid().volume())
                    .isCloseTo(19200, org.assertj.core.data.Offset.offset(.01));
        }
        reopened.setCursor(5);
        reopened.editDimension("thickness", "distance", 10);
        assertThat(reopened.document().bodyStyles())
                .isEqualTo(session.document().bodyStyles());
    }

    @Test
    void reopenEditAndRollbackPreserveStableIdsAndThroughHole() throws Exception {
        var session = reference();
        Path file = directory.resolve("reference.vxb");
        DocumentWriter.save(session.document(), file, false);
        var reopened = new ModelSession(DocumentReader.load(file));
        var ids = reopened.document().features().stream().map(Feature::id).toList();
        reopened.editDimension("thickness", "distance", 8);
        DocumentWriter.save(reopened.document(), file, true);
        var edited = DocumentReader.load(file);
        assertThat(edited.features().stream().map(Feature::id).toList()).isEqualTo(ids);
        assertThat(edited.id()).isEqualTo(session.document().id());
        assertReference(reopened, 8, 4);
        reopened.setCursor(3);
        try (var result = reopened.evaluate()) {
            assertThat(result.bodies().getFirst().solid().volume())
                    .isCloseTo(19200, org.assertj.core.data.Offset.offset(0.01));
            assertThat(result.bodies().getFirst().solid().contains(10, 10, 4)).isTrue();
            assertThatThrownBy(result::requireCompleteDocument).isInstanceOf(IllegalStateException.class);
        }
        reopened.setCursor(4);
        assertReference(reopened, 8, 4);
    }

    @Test
    void rollbackIsIndependentOfUndoRedoAndRejectedEditsAreNotTransactions() {
        var session = reference();
        session.editDimension("thickness", "distance", 8);
        session.setCursor(2);
        assertThat(session.undo()).isTrue();
        assertThat(session.document().cursor()).isEqualTo(2);
        assertThat(((Feature.Extrude) session.document().features().get(1)).distance())
                .isEqualTo(5);
        assertThat(session.redo()).isTrue();
        assertThat(session.document().cursor()).isEqualTo(2);
        session.setCursor(4);
        assertReference(session, 8, 4);
        var valid = session.document();
        for (double bad : new double[] {0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
            var failure = org.assertj.core.api.Assertions.catchThrowableOfType(
                    () -> session.editDimension("hole", "diameter", bad), ModelException.class);
            assertThat(failure.getMessage().contains("hole")).isTrue();
            assertThat(failure.getMessage().contains("diameter")).isTrue();
            assertThat(session.document()).isEqualTo(valid);
        }
        session.editDimension("hole", "diameter", 6);
        var circle =
                (Feature.Circle) ((Feature.Sketch) session.document().features().get(2)).profile();
        assertThat(circle.centerX()).isEqualTo(10);
        assertThat(circle.centerY()).isEqualTo(10);
        assertReference(session, 8, 6);
        assertThat(session.undo()).isTrue();
        assertReference(session, 8, 4);
    }

    @Test
    void missingReferenceReopensAsFailedAndBlockedAndCanBeRepaired() throws Exception {
        var session = reference();
        session.replace(new Feature.Extrude("thickness", new Feature.ProfileRef("missing", "missing.profile"), 5));
        Path file = directory.resolve("broken.vxb");
        DocumentWriter.save(session.document(), file, false);
        var reopened = new ModelSession(DocumentReader.load(file));
        try (var result = reopened.evaluate()) {
            assertThat(result.diagnostics().get(1).state()).isEqualTo(Evaluation.State.FAILED);
            assertThat(result.diagnostics().get(1).message().contains("missing"))
                    .isTrue();
            assertThat(result.diagnostics().get(3).state()).isEqualTo(Evaluation.State.BLOCKED);
            assertThatThrownBy(result::bodies).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(result::requireCompleteDocument).isInstanceOf(IllegalStateException.class);
        }
        reopened.replace(new Feature.Extrude("thickness", new Feature.ProfileRef("plate", "plate.profile"), 5));
        assertThat(reopened.document().features().size()).isEqualTo(4);
        assertReference(reopened, 5, 4);
    }

    @Test
    void forwardReferencesAndWrongNamedOutputsFailAtTheirFeature() {
        var session = reference();
        session.replace(new Feature.Extrude("thickness", new Feature.ProfileRef("hole", "hole.profile"), 5));
        try (var result = session.evaluate()) {
            assertThat(result.diagnostics().get(1).state()).isEqualTo(Evaluation.State.FAILED);
            assertThat(result.diagnostics().get(1).message().contains("non-earlier"))
                    .isTrue();
        }
        session.undo();
        session.replace(new Feature.ThroughCut(
                "cut", new Feature.ProfileRef("hole", "hole.profile"), new Feature.BodyRef("thickness", "wrong")));
        try (var result = session.evaluate()) {
            assertThat(result.diagnostics().get(3).message().contains("missing body output"))
                    .isTrue();
        }
    }

    @Test
    void offsetPlanesAndSignedCircularExtrusionsRoundTrip() throws Exception {
        for (double z : new double[] {0, 12}) {
            var session = new ModelSession(CadDocument.empty());
            session.add(new Feature.Sketch(
                    "rectangle", "profile", new Feature.Plane("plane", z), new Feature.Rectangle(0, 0, 60, 40)));
            session.add(new Feature.Extrude("extrude", new Feature.ProfileRef("rectangle", "profile"), 5));
            Path file = directory.resolve("offset-" + z + ".vxb");
            DocumentWriter.save(session.document(), file, false);
            var reopened = DocumentReader.load(file);
            assertThat(reopened).isEqualTo(session.document());
            try (var result = Evaluator.evaluate(reopened)) {
                var bounds = result.bodies().getFirst().solid().bounds();
                assertThat(bounds.minZ()).isCloseTo(z, org.assertj.core.data.Offset.offset(0.001));
                assertThat(bounds.maxZ()).isCloseTo(z + 5, org.assertj.core.data.Offset.offset(0.001));
            }
        }
        var circle = new ModelSession(CadDocument.empty());
        circle.add(new Feature.Sketch(
                "circle", "profile", new Feature.Plane("offset", 12), new Feature.Circle(10, 10, 6)));
        circle.add(new Feature.Extrude("extrude", new Feature.ProfileRef("circle", "profile"), -3));
        try (var result = circle.evaluate()) {
            var solid = result.bodies().getFirst().solid();
            assertThat(solid.volume()).isCloseTo(27 * Math.PI, org.assertj.core.data.Offset.offset(0.01));
            assertThat(solid.bounds().minZ()).isCloseTo(9, org.assertj.core.data.Offset.offset(0.001));
            assertThat(solid.bounds().maxZ()).isCloseTo(12, org.assertj.core.data.Offset.offset(0.001));
        }
    }

    @Test
    void failedGeometryKeepsDefinitionsAndCannotExposeStaleSolid() {
        var session = reference();
        session.editDimension("hole", "diameter", 1000);
        try (var result = session.evaluate()) {
            assertThat(result.valid()).isFalse();
            assertThatThrownBy(result::requireCompleteDocument).isInstanceOf(IllegalStateException.class);
        }
        assertThat(((Feature.Circle)
                                ((Feature.Sketch) session.document().features().get(2)).profile())
                        .diameter())
                .isEqualTo(1000);
        assertThat(session.undo()).isTrue();
        assertReference(session, 5, 4);
    }

    @Test
    void truncatedAndCorruptFilesHaveUsefulErrorsAndPreserveOriginalOnFailedSave() throws Exception {
        Path file = directory.resolve("part.vxb");
        var document = reference().document();
        DocumentWriter.save(document, file, false);
        byte[] valid = Files.readAllBytes(file);
        assertThatThrownBy(() -> DocumentWriter.save(CadDocument.empty(), file, false))
                .isInstanceOf(java.io.IOException.class);
        assertThat(Files.readAllBytes(file)).containsExactly(valid);
        for (int length : new int[] {0, 10, 43, valid.length - 1}) {
            Files.write(file, Arrays.copyOf(valid, length));
            var failure = org.assertj.core.api.Assertions.catchThrowableOfType(
                    () -> DocumentReader.load(file), DocumentFormatException.class);
            assertThat(failure.getMessage().toLowerCase().contains("truncated")).isTrue();
        }
        valid[valid.length - 1] ^= 1;
        Files.write(file, valid);
        assertThat(org.assertj.core.api.Assertions.catchThrowableOfType(
                                () -> DocumentReader.load(file), DocumentFormatException.class)
                        .getMessage()
                        .contains("checksum"))
                .isTrue();
        assertThatThrownBy(() -> DocumentWriter.save(document, file, true)).isInstanceOf(DocumentFormatException.class);
        assertThat(Files.readAllBytes(file)).containsExactly(valid);
        try (var files = Files.list(directory)) {
            assertThat(files.count()).isEqualTo(1);
        }
    }

    @Test
    void newerSchemasAndUnknownFieldsCannotBeDestructivelyWritten() throws Exception {
        for (boolean newerVersion : new boolean[] {true, false}) {
            Path file = directory.resolve("future-" + newerVersion + ".vxb");
            DocumentWriter.save(reference().document(), file, false);
            byte[] bytes = Files.readAllBytes(file);
            var message = DocumentProto.Document.parseFrom(Arrays.copyOfRange(bytes, 44, bytes.length)).toBuilder();
            if (newerVersion) {
                message.setSchemaVersion(DocumentCodec.LATEST_SCHEMA_VERSION + 1);
            } else {
                message.setUnknownFields(com.google.protobuf.UnknownFieldSet.newBuilder()
                        .addField(
                                100,
                                com.google.protobuf.UnknownFieldSet.Field.newBuilder()
                                        .addVarint(42)
                                        .build())
                        .build());
            }
            byte[] payload = message.build().toByteArray();
            byte[] future = ByteBuffer.allocate(44 + payload.length)
                    .put(bytes, 0, 8)
                    .putInt(payload.length)
                    .put(MessageDigest.getInstance("SHA-256").digest(payload))
                    .put(payload)
                    .array();
            Files.write(file, future);
            assertThat(org.assertj.core.api.Assertions.catchThrowableOfType(
                                    () -> DocumentReader.load(file), DocumentFormatException.class)
                            .getMessage()
                            .contains("read-only"))
                    .isTrue();
            assertThatThrownBy(() -> DocumentWriter.save(CadDocument.empty(), file, true))
                    .isInstanceOf(DocumentFormatException.class);
            assertThat(Files.readAllBytes(file)).containsExactly(future);
        }
    }

    @Test
    void olderSchemasAreRejectedWithoutRewritingTheSource() throws Exception {
        var original = reference().document();
        for (int version : new int[] {1, 2, 3, 4, 5}) {
            Path file = directory.resolve("legacy-" + version + ".vxb");
            DocumentWriter.save(original, file, false);
            byte[] bytes = Files.readAllBytes(file);
            var message = DocumentProto.Document.parseFrom(Arrays.copyOfRange(bytes, 44, bytes.length)).toBuilder()
                    .setSchemaVersion(version);
            if (version == 1) message.clearPrintSetup();
            byte[] payload = message.build().toByteArray();
            Files.write(
                    file,
                    ByteBuffer.allocate(44 + payload.length)
                            .put(bytes, 0, 8)
                            .putInt(payload.length)
                            .put(MessageDigest.getInstance("SHA-256").digest(payload))
                            .put(payload)
                            .array());
            byte[] originalBytes = Files.readAllBytes(file);
            assertThatThrownBy(() -> DocumentReader.load(file)).isInstanceOf(DocumentFormatException.class);
            assertThatThrownBy(() -> DocumentWriter.save(original, file, true))
                    .isInstanceOf(DocumentFormatException.class);
            assertThat(Files.readAllBytes(file)).containsExactly(originalBytes);
        }
    }

    @Test
    void concurrentCreateNeverOverwritesAnotherDocument() throws Exception {
        Path file = directory.resolve("race.vxb");
        var first = CadDocument.empty();
        var second = CadDocument.empty();
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var results = new ArrayList<java.util.concurrent.Future<Boolean>>();
            for (var document : List.of(first, second)) {
                results.add(executor.submit(() -> {
                    start.await();
                    try {
                        DocumentWriter.save(document, file, false);
                        return true;
                    } catch (java.io.IOException expectedConflict) {
                        return false;
                    }
                }));
            }
            start.countDown();
            int successes = 0;
            for (var result : results) {
                if (result.get()) {
                    successes++;
                }
            }
            assertThat(successes).isEqualTo(1);
        }
        assertThat(List.of(first.id(), second.id())
                        .contains(DocumentReader.load(file).id()))
                .isTrue();
        try (var files = Files.list(directory)) {
            assertThat(files.count()).isEqualTo(1);
        }
    }

    @Test
    void insertsBodyOperationBeforeExistingCutAndRestoresItsInputOnUndo() throws Exception {
        var session = reference();
        var original = session.document().features();
        session.setCursor(3);
        var joined = new BodyExtrusion(
                        new Feature.Extrude("extend", new Feature.ProfileRef("plate", "plate.profile"), 8),
                        BodyExtrusion.Operation.JOIN,
                        new Feature.BodyRef("thickness", "body"))
                .feature();
        session.add(joined);
        assertThat(session.document().cursor()).isEqualTo(4);
        assertThat(((Feature.ThroughCut) session.document().features().getLast()).body())
                .isEqualTo(new Feature.BodyRef("extend", "body"));
        assertThat(session.undo()).isTrue();
        assertThat(session.document().features()).isEqualTo(original);
        assertThat(session.document().cursor()).isEqualTo(3);
        assertThat(session.redo()).isTrue();
        assertThat(session.document().cursor()).isEqualTo(4);
        var file = directory.resolve("inserted.vxb");
        DocumentWriter.save(session.document(), file, false);
        var reopened = new ModelSession(DocumentReader.load(file));
        reopened.setCursor(5);
        assertReference(reopened, 8, 4);
    }

    @Test
    void undoRedoFeatureCreationKeepsCursorAtEnd() {
        var session = reference();
        assertThat(session.undo()).isTrue();
        assertThat(session.document().cursor()).isEqualTo(3);
        assertThat(session.redo()).isTrue();
        assertThat(session.document().cursor()).isEqualTo(4);
        assertReference(session, 5, 4);
    }
}
