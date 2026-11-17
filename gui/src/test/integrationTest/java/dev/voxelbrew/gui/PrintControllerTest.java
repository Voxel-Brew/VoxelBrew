package dev.voxelbrew.gui;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.model.*;
import dev.voxelbrew.api.model.printing.*;
import dev.voxelbrew.api.slicer.SlicerBackend;
import dev.voxelbrew.engine.model.*;
import dev.voxelbrew.engine.printing.*;
import dev.voxelbrew.slicer.prusa.PrusaSlicerBackend;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PrintControllerTest {
    @TempDir
    Path directory;

    private final Queue<Runnable> callbacks = new ConcurrentLinkedQueue<>();

    private void await(BooleanSupplier done) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            for (Runnable r; (r = callbacks.poll()) != null; ) r.run();
            if (done.getAsBoolean()) return;
            Thread.sleep(10);
        }
        fail("Controller timed out");
    }

    private static ModelSession document() {
        var s = new ModelSession(CadDocument.empty());
        s.add(new Feature.Sketch("plate", "p", new Feature.Plane("xy", 0), new Feature.Rectangle(0, 0, 20, 20)));
        s.add(new Feature.Extrude("height", new Feature.ProfileRef("plate", "p"), 8));
        s.setPrintSetup(s.document()
                .printSetup()
                .withInstances(List.of(new BodyInstance("part", "height.body", Transform.translation(10, 10, 0)))));
        return s;
    }

    @Test
    void embeddedRealSliceSurvivesReopenWithoutPublishingDestination() throws Exception {
        var session = document();
        try (var controller = new PrintController(callbacks::add, session::document)) {
            var future = controller.startEmbedded(session.document(), new PrusaSlicerBackend(), false);
            await(future::isDone);
            var sliced = future.get();
            assertThat(Files.exists(sliced.output())).isFalse();
            assertThat(sliced.job().lines().isEmpty()).isFalse();
            session.setGcode(controller.completedGcode());
        }
        var reopened = dev.voxelbrew.engine.persistence.DocumentCodec.decode(
                dev.voxelbrew.engine.persistence.DocumentCodec.encode(session.document()));
        try (var controller = new PrintController(callbacks::add, () -> reopened)) {
            controller.restoreEmbedded(reopened);
            await(() -> controller.state() == PrintController.State.SUCCEEDED);
            assertThat(controller.result().job().lines()).isEqualTo(reopened.activeGcode());
            assertThat(controller.result().job().modelLayers().size()).isEqualTo(40);
        }
    }

    private static final class HeldBackend implements SlicerBackend {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);

        public Descriptor descriptor() {
            return new PrusaSlicerBackend().descriptor();
        }

        public Probe probe(Path exe, Cancellation cancellation) {
            return new Probe("failure-lifecycle-fixture", Set.of());
        }

        public RunResult slice(Request request) throws IOException {
            request.diagnostics().accept("Fixture process entered");
            entered.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("Latch timeout");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
            request.cancellation().check();
            Files.writeString(request.output(), "partial output");
            throw new IOException("forced failure");
        }
    }

    private Map<String, Path> destinations(CadDocument document, String stem) {
        var result = new LinkedHashMap<String, Path>();
        for (int i = 0; i < document.printSetup().plates().size(); i++)
            result.put(
                    document.printSetup().plates().get(i).id(),
                    directory.resolve(stem + "-plate-" + (i + 1) + ".gcode"));
        return result;
    }

    @Test
    void batchSlicesNonEmptyPlatesAndKeepsSelectedPreview() throws Exception {
        var session = document();
        session.setPrintSetup(session.document()
                .printSetup()
                .addPlate("second")
                .withInstances(List.of(new BodyInstance("other", "height.body", Transform.translation(80, 80, 0))))
                .addPlate("empty")
                .selectPlate("plate-1"));
        var original = session.document();
        try (var controller = new PrintController(callbacks::add, session::document)) {
            controller.startAll(
                    original,
                    new PrusaSlicerBackend(),
                    PrusaSlicerBackend.defaultExecutable(),
                    destinations(session.document(), "batch"),
                    false);
            await(() -> !controller.running());
            assertThat(controller.state()).as(controller.message()).isEqualTo(PrintController.State.SUCCEEDED);
            assertThat(Files.exists(directory.resolve("batch-plate-1.gcode"))).isTrue();
            assertThat(Files.exists(directory.resolve("batch-plate-2.gcode"))).isTrue();
            assertThat(Files.exists(directory.resolve("batch-plate-3.gcode"))).isFalse();
            assertThat(controller.result().output()).isEqualTo(directory.resolve("batch-plate-1.gcode"));
            assertThat(session.document()).isEqualTo(original);
        }
    }

    @Test
    void batchPreflightsLaterPlatesBeforePublishingAnyFile() throws Exception {
        var session = document();
        session.setPrintSetup(session.document()
                .printSetup()
                .addPlate("invalid")
                .withInstances(List.of(new BodyInstance("other", "height.body", Transform.translation(500, 500, 0))))
                .selectPlate("plate-1"));
        try (var controller = new PrintController(callbacks::add, session::document)) {
            controller.startAll(
                    session.document(),
                    new PrusaSlicerBackend(),
                    PrusaSlicerBackend.defaultExecutable(),
                    destinations(session.document(), "bad"),
                    false);
            await(() -> !controller.running());
            assertThat(controller.state()).isEqualTo(PrintController.State.FAILED);
            assertThat(Files.exists(directory.resolve("bad-plate-1.gcode"))).isFalse();
        }
    }

    @Test
    void batchCanCancelAfterFirstPlateWasPublished() throws Exception {
        var session = document();
        session.setPrintSetup(session.document()
                .printSetup()
                .addPlate("second")
                .withInstances(List.of(new BodyInstance("other", "height.body", Transform.translation(80, 80, 0))))
                .selectPlate("plate-1"));
        var held = new HeldBackend();
        var real = new PrusaSlicerBackend();
        var backend = new SlicerBackend() {
            int calls;

            public Descriptor descriptor() {
                return real.descriptor();
            }

            public Probe probe(Path path, Cancellation token) throws IOException {
                return real.probe(path, token);
            }

            public RunResult slice(Request request) throws IOException {
                return ++calls == 1 ? real.slice(request) : held.slice(request);
            }
        };
        try (var controller = new PrintController(callbacks::add, session::document)) {
            controller.startAll(
                    session.document(),
                    backend,
                    PrusaSlicerBackend.defaultExecutable(),
                    destinations(session.document(), "cancel"),
                    false);
            await(() -> held.entered.getCount() == 0);
            assertThat(Files.exists(directory.resolve("cancel-plate-1.gcode"))).isTrue();
            controller.cancel();
            held.release.countDown();
            await(() -> !controller.running());
            assertThat(controller.state()).isEqualTo(PrintController.State.CANCELLED);
            assertThat(Files.exists(directory.resolve("cancel-plate-2.gcode"))).isFalse();
        }
    }

    @Test
    void realSliceUsesOnlySelectedPlateAndSwitchingInvalidatesPreview() throws Exception {
        var session = document();
        session.setPrintSetup(session.document()
                .printSetup()
                .addPlate("off-bed")
                .withInstances(List.of(new BodyInstance("outside", "height.body", Transform.translation(500, 500, 0))))
                .selectPlate("plate-1"));
        try (var controller = new PrintController(callbacks::add, session::document)) {
            var future = controller.start(
                    session.document(),
                    new PrusaSlicerBackend(),
                    PrusaSlicerBackend.defaultExecutable(),
                    directory.resolve("selected-plate.gcode"),
                    false);
            await(() -> !controller.running());
            assertThat(controller.state()).as(controller.message()).isEqualTo(PrintController.State.SUCCEEDED);
            assertThat(future.get().job().segments().isEmpty()).isFalse();
            assertThat(future.get().job().segments().stream()
                            .filter(GcodeJob.Segment::deposits)
                            .allMatch(segment -> segment.x1() < 200 && segment.y1() < 200))
                    .isTrue();
            session.setPrintSetup(session.document().printSetup().selectPlate("off-bed"));
            controller.documentChanged(session.document());
            assertThat(controller.state()).isEqualTo(PrintController.State.OUTDATED);
            assertThat(controller.result()).isNull();
        }
    }

    @Test
    void editProfileOrCancelInvalidatesRunningJobAndFailureRemainsDistinct() throws Exception {
        var session = document();
        Path output = directory.resolve("existing.gcode");
        Files.writeString(output, "original");
        try (var controller = new PrintController(callbacks::add, session::document)) {
            for (var expected : List.of(
                    PrintController.State.CANCELLED, PrintController.State.OUTDATED, PrintController.State.FAILED)) {
                var backend = new HeldBackend();
                var future = controller.start(session.document(), backend, Path.of("unused"), output, true);
                assertThat(backend.entered.await(5, TimeUnit.SECONDS)).isTrue();
                if (expected == PrintController.State.CANCELLED) controller.cancel();
                if (expected == PrintController.State.OUTDATED) {
                    session.setPrintSetup(session.document()
                            .printSetup()
                            .withProfile("prusa-slicer", "generic-marlin-200-0.4", "PETG", ProcessSettings.DEFAULT));
                    controller.documentChanged(session.document());
                }
                backend.release.countDown();
                await(() -> !controller.running());
                assertThat(controller.state()).isEqualTo(expected);
                assertThat(controller.result()).isNull();
                assertThat(future.isCompletedExceptionally()).isTrue();
                assertThat(Files.readString(output)).isEqualTo("original");
            }
        }
    }

    @Test
    void delayedPublicationCannotBecomeCurrentAfterDocumentChange() throws Exception {
        var session = document();
        Path output = directory.resolve("existing.gcode");
        Files.writeString(output, "original");
        try (var controller = new PrintController(callbacks::add, session::document)) {
            var future = controller.start(
                    session.document(), new PrusaSlicerBackend(), PrusaSlicerBackend.defaultExecutable(), output, true);
            // Hold owner-thread callbacks while the real slicer works; edit before publication.
            session.editDimension("height", "distance", 5);
            controller.documentChanged(session.document());
            await(() -> !controller.running());
            assertThat(controller.state()).isEqualTo(PrintController.State.OUTDATED);
            assertThat(controller.result()).isNull();
            assertThat(Files.readString(output)).isEqualTo("original");
            assertThat(future.isCompletedExceptionally()).isTrue();
        }
    }

    @Test
    void rebuildingUnchangedDefinitionsInvalidatesCompletedAndRunningJobs() throws Exception {
        var session = document();
        var unchanged = session.document();
        Path output = directory.resolve("reload.gcode");
        try (var controller = new PrintController(callbacks::add, session::document)) {
            var completed = controller.start(
                    unchanged, new PrusaSlicerBackend(), PrusaSlicerBackend.defaultExecutable(), output, false);
            await(() -> !controller.running());
            assertThat(controller.state()).isEqualTo(PrintController.State.SUCCEEDED);
            assertThat(completed.join()).isNotNull();
            byte[] published = Files.readAllBytes(output);
            controller.invalidateGeometry();
            assertThat(controller.state()).isEqualTo(PrintController.State.OUTDATED);
            assertThat(controller.result()).isNull();
            assertThat(session.document()).isEqualTo(unchanged);
            var held = new HeldBackend();
            var future = controller.start(unchanged, held, Path.of("unused"), output, true);
            assertThat(held.entered.await(5, TimeUnit.SECONDS)).isTrue();
            controller.invalidateGeometry();
            held.release.countDown();
            await(() -> !controller.running());
            assertThat(controller.state()).isEqualTo(PrintController.State.OUTDATED);
            assertThat(controller.result()).isNull();
            assertThat(future.isCompletedExceptionally()).isTrue();
            assertThat(Files.readAllBytes(output)).containsExactly(published);
        }
    }
}
