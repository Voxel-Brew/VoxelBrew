package dev.voxelbrew.gui;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.model.CadDocument;
import dev.voxelbrew.api.model.printing.GcodeJob;
import dev.voxelbrew.importer.GcodeSource;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class EmbeddedPreviewTest {
    private final Queue<Runnable> work = new ArrayDeque<>(), ui = new ArrayDeque<>();
    private final AtomicReference<CadDocument> current =
            new AtomicReference<>(CadDocument.empty().withGcode(Map.of("plate-1", List.of("G90"))));
    private final ExecutorService worker = new AbstractExecutorService() {
        @Override
        public void execute(Runnable task) {
            work.add(task);
        }

        @Override
        public void shutdown() {}

        @Override
        public List<Runnable> shutdownNow() {
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }
    };
    private boolean failRead;

    private PrintController controller() {
        return new PrintController(ui::add, current::get, Path.of("."), worker, new GcodeSource() {
            @Override
            public GcodeJob read(Path path) {
                throw new AssertionError("Embedded jobs require no files");
            }

            @Override
            public GcodeJob readLines(List<String> lines) throws IOException {
                if (failRead) throw new IOException("broken job");
                return new GcodeJob(List.of(), List.of(), OptionalDouble.empty(), Optional.empty(), Map.of(), lines);
            }
        });
    }

    private void finish() {
        work.remove().run();
        ui.remove().run();
    }

    @Test
    void cleanAndGeometryInvalidationDiscardQueuedRestores() {
        try (var controller = controller()) {
            controller.restoreEmbedded(current.get());
            work.remove().run();
            controller.clear();
            ui.remove().run();
            assertThat(controller.state()).isEqualTo(PrintController.State.IDLE);
            assertThat(controller.result()).isNull();
            controller.restoreEmbedded(current.get());
            finish();
            controller.clear();
            controller.restoreEmbedded(current.get());
            work.remove().run();
            controller.invalidateGeometry();
            ui.remove().run();
            assertThat(controller.state()).isEqualTo(PrintController.State.OUTDATED);
            assertThat(controller.result()).isNull();
        }
    }

    @Test
    void identicalJobsOnDifferentPlatesRestoreAfterSelectionChanges() {
        var setup = current.get().printSetup().addPlate("second").selectPlate("plate-1");
        var document = current.get();
        current.set(new CadDocument(document.id(), List.of(), 0, setup)
                .withGcode(Map.of("plate-1", List.of("G90"), "second", List.of("G90"))));
        try (var controller = controller()) {
            controller.restoreEmbedded(current.get());
            finish();
            document = current.get();
            current.set(new CadDocument(document.id(), List.of(), 0, setup.selectPlate("second"))
                    .withGcode(document.gcode()));
            controller.documentChanged(current.get());
            controller.restoreEmbedded(current.get());
            finish();
            assertThat(controller.state()).isEqualTo(PrintController.State.SUCCEEDED);
            assertThat(controller.result()).isNotNull();
        }
    }

    @Test
    void replacingEmbeddedLinesAtTheSameRevisionRefreshesPreview() {
        try (var controller = controller()) {
            controller.restoreEmbedded(current.get());
            finish();
            current.set(current.get().withGcode(Map.of("plate-1", List.of("G21", "G90"))));
            controller.restoreEmbedded(current.get());
            finish();
            assertThat(controller.result().job().lines()).isEqualTo(List.of("G21", "G90"));
        }
    }

    @Test
    void failedReadsCanRetryAndOldFailuresCannotOverwriteCleanState() {
        try (var controller = controller()) {
            failRead = true;
            controller.restoreEmbedded(current.get());
            finish();
            assertThat(controller.message().contains("broken job")).isTrue();
            failRead = false;
            controller.restoreEmbedded(current.get());
            finish();
            assertThat(controller.state()).isEqualTo(PrintController.State.SUCCEEDED);
            controller.clear();
            failRead = true;
            controller.restoreEmbedded(current.get());
            work.remove().run();
            controller.clear();
            ui.remove().run();
            assertThat(controller.message()).isEqualTo("Ready to slice");
        }
    }
}
