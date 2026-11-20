package dev.voxelbrew.gui;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.model.*;
import dev.voxelbrew.api.model.printing.*;
import dev.voxelbrew.api.slicer.*;
import dev.voxelbrew.engine.model.ModelSession;
import dev.voxelbrew.slicer.SliceService;
import dev.voxelbrew.slicer.prusa.PrusaSlicerProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class PrintControllerTest {
    private static final SlicerWindow DEFAULTS = new SlicerWindow() {
        @Override
        public <T> Optional<T> show(SlicerDialog<T> dialog) {
            return Optional.of(dialog.buildSettings(Map.of()));
        }
    };
    private final Queue<Runnable> callbacks = new ConcurrentLinkedQueue<>();

    private static ModelSession document() {
        var session = new ModelSession(CadDocument.empty());
        session.add(new Feature.Sketch("plate", "p", new Feature.Plane("xy", 0), new Feature.Rectangle(0, 0, 20, 20)));
        session.add(new Feature.Extrude("height", new Feature.ProfileRef("plate", "p"), 8));
        session.setPrintSetup(session.document()
                .printSetup()
                .withInstances(List.of(new BodyInstance("part", "height.body", Transform.translation(10, 10, 0)))));
        return session;
    }

    private static SlicerProvider provider(java.util.function.Supplier<Slicer> operations) {
        return new SlicerProvider() {
            @Override
            public SlicerDescriptor descriptor() {
                return new PrusaSlicerProvider().descriptor();
            }

            @Override
            public Slicer create() {
                return operations.get();
            }
        };
    }

    private static Optional<PrintArtifact> slice(
            ManufacturingModel model,
            SlicerWindow window,
            Cancellation cancellation,
            java.util.function.Consumer<String> diagnostics)
            throws IOException {
        cancellation.check();
        var metadata = new HashMap<>(SliceService.provenance(model, "prusa-slicer"));
        metadata.put("backend_version", "fixture");
        String gcode = ";TYPE:Perimeter\nG90\nM82\nG1 Z0.2\nG1 X10 E1\n";
        return Optional.of(PrintArtifact.of("gcode", gcode.getBytes(StandardCharsets.UTF_8), metadata));
    }

    private void await(BooleanSupplier done) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            for (Runnable r; (r = callbacks.poll()) != null; ) r.run();
            if (done.getAsBoolean()) return;
            Thread.sleep(10);
        }
        fail("Controller timed out");
    }

    @Test
    void embeddedRealSliceSurvivesReopen() throws Exception {
        var session = document();
        try (var controller = new PrintController(callbacks::add, session::document)) {
            var future = controller.startEmbedded(session.document(), new PrusaSlicerProvider(), DEFAULTS, false);
            await(future::isDone);
            assertThat(future.get().job().modelLayers()).hasSize(40);
            session.setGcode(controller.completedGcode());
            session.setPrintArtifacts(controller.completedArtifacts());
        }
        var codec = dev.voxelbrew.engine.persistence.DocumentCodec.encode(session.document());
        var reopened = dev.voxelbrew.engine.persistence.DocumentCodec.decode(codec);
        try (var controller = new PrintController(callbacks::add, () -> reopened)) {
            controller.restoreEmbedded(reopened);
            await(() -> controller.state() == PrintController.State.SUCCEEDED);
            assertThat(controller.result().job().lines()).isEqualTo(reopened.activeGcode());
            assertThat(reopened.printArtifacts()).containsKey("plate-1");
        }
    }

    @Test
    void batchSkipsEmptyPlatesAndKeepsSelectedPreview() throws Exception {
        var session = document();
        session.setPrintSetup(session.document()
                .printSetup()
                .addPlate("second")
                .withInstances(List.of(new BodyInstance("other", "height.body", Transform.translation(80, 80, 0))))
                .addPlate("empty")
                .selectPlate("plate-1"));
        var original = session.document();
        var created = new java.util.concurrent.atomic.AtomicInteger();
        try (var controller = new PrintController(callbacks::add, session::document)) {
            var future = controller.startEmbedded(
                    original,
                    provider(() -> {
                        created.incrementAndGet();
                        return PrintControllerTest::slice;
                    }),
                    DEFAULTS,
                    true);
            await(future::isDone);
            assertThat(future.get()).isNotNull();
            assertThat(created.get()).isEqualTo(2);
            assertThat(controller.completedArtifacts()).hasSize(2).containsKey("plate-1");
            assertThat(controller.result().artifact())
                    .isEqualTo(controller.completedArtifacts().get("plate-1"));
            assertThat(session.document()).isEqualTo(original);
        }
    }

    @Test
    void failedCancelledAndOutdatedOperationsDoNotPublishResults() throws Exception {
        for (var expected : List.of(
                PrintController.State.CANCELLED, PrintController.State.OUTDATED, PrintController.State.FAILED)) {
            var session = document();
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var provider = provider(() -> (model, window, cancellation, diagnostics) -> {
                entered.countDown();
                if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("Fixture timed out");
                if (expected == PrintController.State.FAILED) throw new IOException("Forced failure");
                return slice(model, window, cancellation, diagnostics);
            });
            try (var controller = new PrintController(callbacks::add, session::document)) {
                var future = controller.startEmbedded(session.document(), provider, DEFAULTS, false);
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                if (expected == PrintController.State.CANCELLED) controller.cancel();
                if (expected == PrintController.State.OUTDATED) {
                    session.editDimension("height", "distance", 5);
                    controller.documentChanged(session.document());
                }
                release.countDown();
                await(future::isDone);
                assertThat(controller.state()).isEqualTo(expected);
                assertThat(controller.result()).isNull();
                assertThat(controller.completedArtifacts()).isEmpty();
                assertThat(future.isCompletedExceptionally()).isTrue();
            } finally {
                release.countDown();
            }
        }
    }
}
