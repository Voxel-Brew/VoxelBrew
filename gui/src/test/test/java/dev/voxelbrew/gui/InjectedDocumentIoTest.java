package dev.voxelbrew.gui;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.engine.model.CadDocument;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InjectedDocumentIoTest {
    @TempDir
    Path directory;

    private final Queue<Runnable> callbacks = new ConcurrentLinkedQueue<>();

    @Test
    void importRunsOnFileWorkerShowsDialogOnOwnerThreadAndKeepsDocumentOnCancelOrFailure() throws Exception {
        var owner = Thread.currentThread();
        var importThread = new AtomicReference<Thread>();
        var dialogThread = new AtomicReference<Thread>();
        var controller =
                new DocumentController(callbacks::add, doc -> new DocumentController.Build(List.of(), List.of(), true));
        try {
            var before = controller.snapshot().document();
            dev.voxelbrew.api.importer.ImporterProvider provider = new dev.voxelbrew.api.importer.ImporterProvider() {
                @Override
                public dev.voxelbrew.api.importer.ImporterDescriptor descriptor() {
                    return new dev.voxelbrew.api.importer.ImporterDescriptor("thread-probe", "Thread probe", "1.0");
                }

                @Override
                public dev.voxelbrew.api.importer.Importer create() {
                    return window -> {
                        importThread.set(Thread.currentThread());
                        return window.show(new dev.voxelbrew.api.importer.ImportDialog<CadDocument>() {
                            @Override
                            public String title() {
                                return "Thread probe";
                            }

                            @Override
                            public dev.voxelbrew.api.importer.component.ImportDialogLayout layout() {
                                return new dev.voxelbrew.api.importer.component.VerticalLayout();
                            }

                            @Override
                            public Map<String, String> validate(Map<String, String> values) {
                                return Map.of();
                            }

                            @Override
                            public CadDocument buildSettings(Map<String, String> values) {
                                return CadDocument.empty();
                            }
                        });
                    };
                }
            };
            var cancelled = controller.importDocument(provider, new dev.voxelbrew.api.importer.ImportWindow() {
                @Override
                public <R> Optional<R> show(dev.voxelbrew.api.importer.ImportDialog<R> dialog) {
                    dialogThread.set(Thread.currentThread());
                    return Optional.empty();
                }
            });
            await(cancelled::isDone);
            org.assertj.core.api.Assertions.assertThat(cancelled).isCancelled();
            org.assertj.core.api.Assertions.assertThat(importThread.get()).isNotSameAs(owner);
            org.assertj.core.api.Assertions.assertThat(dialogThread.get()).isSameAs(owner);
            org.assertj.core.api.Assertions.assertThat(controller.snapshot().document())
                    .isEqualTo(before);
            org.assertj.core.api.Assertions.assertThat(controller.ioBusy()).isFalse();

            var failed = controller.importDocument(
                    new dev.voxelbrew.api.importer.ImporterProvider() {
                        @Override
                        public dev.voxelbrew.api.importer.ImporterDescriptor descriptor() {
                            return new dev.voxelbrew.api.importer.ImporterDescriptor("failure", "Failure", "1.0");
                        }

                        @Override
                        public dev.voxelbrew.api.importer.Importer create() {
                            return window -> {
                                throw new IOException("Injected import failure");
                            };
                        }
                    },
                    new dev.voxelbrew.api.importer.ImportWindow() {
                        @Override
                        public <R> Optional<R> show(dev.voxelbrew.api.importer.ImportDialog<R> dialog) {
                            return Optional.empty();
                        }
                    });
            await(failed::isDone);
            org.assertj.core.api.Assertions.assertThat(failed).isCompletedExceptionally();
            org.assertj.core.api.Assertions.assertThat(controller.snapshot().document())
                    .isEqualTo(before);
            org.assertj.core.api.Assertions.assertThat(controller.ioBusy()).isFalse();
        } finally {
            controller.close();
        }
    }

    @Test
    void exportRunsOnFileWorkerAndShowsDialogOnOwnerThread() throws Exception {
        var owner = Thread.currentThread();
        var exportThread = new AtomicReference<Thread>();
        var dialogThread = new AtomicReference<Thread>();
        var controller =
                new DocumentController(callbacks::add, doc -> new DocumentController.Build(List.of(), List.of(), true));
        try {
            dev.voxelbrew.api.exporter.ExporterProvider<String> provider =
                    new dev.voxelbrew.api.exporter.ExporterProvider<>() {
                        @Override
                        public dev.voxelbrew.api.exporter.ExporterDescriptor descriptor() {
                            return new dev.voxelbrew.api.exporter.ExporterDescriptor(
                                    "thread-probe", "Thread probe", "1.0");
                        }

                        @Override
                        public dev.voxelbrew.api.exporter.Exporter<String> create() {
                            return (document, window) -> {
                                exportThread.set(Thread.currentThread());
                                return window.show(new dev.voxelbrew.api.exporter.ExportDialog<>() {
                                    @Override
                                    public String title() {
                                        return "Thread probe";
                                    }

                                    @Override
                                    public dev.voxelbrew.api.exporter.component.ExportDialogLayout layout() {
                                        return new dev.voxelbrew.api.exporter.component.VerticalLayout();
                                    }

                                    @Override
                                    public Map<String, String> validate(Map<String, String> values) {
                                        return Map.of();
                                    }

                                    @Override
                                    public String buildSettings(Map<String, String> values) {
                                        return "accepted";
                                    }
                                });
                            };
                        }
                    };
            var export = controller.exportDocument(provider, new dev.voxelbrew.api.exporter.ExportWindow() {
                @Override
                public <R> Optional<R> show(dev.voxelbrew.api.exporter.ExportDialog<R> dialog) {
                    dialogThread.set(Thread.currentThread());
                    return Optional.of(dialog.buildSettings(Map.of()));
                }
            });
            await(export::isDone);
            export.join();
            assertThat(exportThread.get()).isNotSameAs(owner);
            assertThat(dialogThread.get()).isSameAs(owner);
        } finally {
            controller.close();
        }
    }

    @Test
    void saveAndJsonExportCapturePendingSlicerFields() throws Exception {
        var controller =
                new DocumentController(callbacks::add, doc -> new DocumentController.Build(List.of(), List.of(), true));
        try {
            var settings = Map.of("print-output", "custom.gcode", "print-walls", "4", "print-overwrite", "true");
            controller.printWorkspace(() -> settings);
            assertThat(controller.dirty()).isTrue();
            var vxb = directory.resolve("settings.vxb");
            var save = controller.save(vxb, false);
            await(save::isDone);
            save.join();
            assertThat(dev.voxelbrew.engine.persistence.DocumentCodec.decode(Files.readAllBytes(vxb))
                            .printSetup()
                            .workspaceSettings())
                    .isEqualTo(settings);
            assertThat(controller.dirty()).isFalse();
            var json = directory.resolve("settings.json");
            var export = controller.exportDocument(
                    new dev.voxelbrew.exporter.JsonExporterProvider(),
                    dev.voxelbrew.util.testing.FileExportWindows.destination(json, false));
            await(export::isDone);
            export.join();
            assertThat(dev.voxelbrew.engine.persistence.DocumentCodec.decodeJson(Files.readString(json))
                            .printSetup()
                            .workspaceSettings())
                    .isEqualTo(settings);
        } finally {
            controller.close();
        }
    }

    @Test
    void exportersKeepCancellationAndJsonSeparateFromSavingTheEditableDocument() throws Exception {
        var controller =
                new DocumentController(callbacks::add, doc -> new DocumentController.Build(List.of(), List.of(), true));
        var used = new ArrayList<Path>();
        controller.onFileUsed(used::add);
        try {
            var cancelled = controller.saveAs(new dev.voxelbrew.api.exporter.ExportWindow() {
                @Override
                public <R> Optional<R> show(dev.voxelbrew.api.exporter.ExportDialog<R> dialog) {
                    return Optional.empty();
                }
            });
            await(cancelled::isDone);
            assertThat(cancelled.isCancelled()).isTrue();
            assertThat(controller.ioBusy()).isFalse();
            assertThat(controller.path()).isNull();
            var json = controller.exportDocument(
                    new dev.voxelbrew.exporter.JsonExporterProvider(),
                    dev.voxelbrew.util.testing.FileExportWindows.destination(directory.resolve("copy.json"), false));
            await(json::isDone);
            json.join();
            assertThat(controller.path()).isNull();
            assertThat(Files.exists(directory.resolve("copy.json"))).isTrue();
            assertThat(used.isEmpty()).isTrue();
            Path saved = directory.resolve("saved.vxb");
            var vxb = controller.saveAs(dev.voxelbrew.util.testing.FileExportWindows.destination(saved, false));
            await(vxb::isDone);
            vxb.join();
            assertThat(controller.path()).isEqualTo(saved);
            assertThat(controller.ioBusy()).isFalse();
            assertThat(used).isEqualTo(List.of(saved));
            Path second = directory.resolve("second.vxb");
            var save = controller.save(second, false);
            await(save::isDone);
            save.join();
            var open = controller.open(saved);
            await(open::isDone);
            open.join();
            var failed = controller.open(directory.resolve("missing.vxb"));
            await(failed::isDone);
            assertThat(failed.isCompletedExceptionally()).isTrue();
            assertThat(used).isEqualTo(List.of(saved, second, saved));
        } finally {
            controller.close();
        }
    }

    @Test
    void injectedFileFailuresPreserveTheDocumentAndShutdownDrainsCapturedWrites() throws Exception {
        var rebuild = WindowResources.worker("test-rebuild");
        var files = WindowResources.worker("test-files");
        var loaded = CadDocument.empty();
        var written = new AtomicReference<CadDocument>();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var controller = new DocumentController(
                callbacks::add,
                doc -> new DocumentController.Build(List.of(), List.of(), true),
                directory,
                rebuild,
                files,
                path -> {
                    if (path.getFileName().toString().equals("bad.vxb")) throw new IOException("Injected read failure");
                    return loaded;
                },
                (doc, path, overwrite) -> {
                    entered.countDown();
                    try {
                        if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("Test write timed out");
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new IOException(failure);
                    }
                    written.set(doc);
                });
        try {
            var open = controller.open(directory.resolve("virtual.vxb"));
            await(() -> open.isDone() && !controller.rebuilding());
            open.join();
            assertThat(controller.snapshot().document()).isEqualTo(loaded);
            var failed = controller.open(directory.resolve("bad.vxb"));
            await(failed::isDone);
            assertThat(failed.isCompletedExceptionally()).isTrue();
            assertThat(controller.snapshot().document()).isEqualTo(loaded);
            var save = controller.save(directory.resolve("saved.vxb"), false);
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            controller.close();
            release.countDown();
            assertThat(files.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            await(save::isDone);
            assertThat(written.get()).isEqualTo(loaded);
            assertThat(save.isCancelled())
                    .as("Closed window must not adopt the captured write as current state")
                    .isTrue();
            assertThat(rebuild.isShutdown()).isTrue();
            assertThat(Files.exists(directory.resolve("saved.vxb"))).isFalse();
        } finally {
            release.countDown();
            controller.close();
        }
    }

    private void await(BooleanSupplier done) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            for (Runnable callback; (callback = callbacks.poll()) != null; ) callback.run();
            if (done.getAsBoolean()) return;
            Thread.sleep(5);
        }
        fail("Timed out waiting for owner-thread callbacks");
    }
}
