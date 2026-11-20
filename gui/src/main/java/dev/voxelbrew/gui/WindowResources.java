package dev.voxelbrew.gui;

import dev.voxelbrew.api.exporter.ExporterProviderRegistry;
import dev.voxelbrew.api.multithread.MultithreadService;
import dev.voxelbrew.api.slicer.SlicerProviderRegistry;
import dev.voxelbrew.util.concurrent.SerialExecutor;
import dev.voxelbrew.util.services.Services;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;
import lombok.extern.slf4j.Slf4j;

/** Window-owned resources. The window scope constructs and explicitly closes them. */
@Slf4j
final class WindowResources implements AutoCloseable {
    final ExporterProviderRegistry exporters;

    final dev.voxelbrew.api.printer.driver.DriverRegistry printers;

    final SlicerProviderRegistry slicers;

    final SerialExecutor rebuild, files, slicing, validation, appearance, printer;

    private final CompletableFuture<Void> disposal = new CompletableFuture<>();

    private boolean closed;

    static ExecutorService commonExecutor() {
        return Services.load(MultithreadService.class).commonExecutorService();
    }

    static SerialExecutor worker() {
        return new SerialExecutor(commonExecutor());
    }

    WindowResources(
            Path plugins,
            java.util.function.Supplier<ExporterProviderRegistry> exporterSupplier,
            java.util.function.Supplier<SlicerProviderRegistry> slicerSupplier) {
        ExporterProviderRegistry opened = null;
        SlicerProviderRegistry openedSlicers = null;
        dev.voxelbrew.api.printer.driver.DriverRegistry openedPrinters = null;
        try {
            opened = exporterSupplier.get().withDirectory(plugins);
            openedSlicers = slicerSupplier.get();
            openedSlicers.withDirectory(plugins);
            slicers = openedSlicers;
            exporters = opened;
            openedPrinters = java.util.ServiceLoader.load(dev.voxelbrew.api.printer.driver.DriverRegistry.class)
                    .findFirst()
                    .orElseThrow(
                            () -> new IllegalStateException("No DriverRegistry service found; install printer-impl"));
            printers = openedPrinters.withDirectory(plugins);
        } catch (IOException | RuntimeException | Error failure) {
            if (openedPrinters != null)
                try {
                    openedPrinters.close();
                } catch (IOException close) {
                    failure.addSuppressed(close);
                }
            if (openedSlicers != null)
                try {
                    openedSlicers.close();
                } catch (IOException close) {
                    failure.addSuppressed(close);
                }
            if (opened != null)
                try {
                    opened.close();
                } catch (IOException close) {
                    failure.addSuppressed(close);
                }
            if (failure instanceof IOException io) throw new UncheckedIOException(io);
            if (failure instanceof RuntimeException runtime) throw runtime;
            throw (Error) failure;
        }
        rebuild = worker();
        files = worker();
        slicing = worker();
        validation = worker();
        appearance = worker();
        printer = worker();
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        // Drain captured file writes and cancelled native/slicer work on their owning threads.
        var workers = List.of(rebuild, files, slicing, validation, appearance, printer);
        for (var worker : workers) worker.shutdown();
        CompletableFuture.allOf(workers.stream()
                        .map(worker -> worker.whenTerminated().toCompletableFuture())
                        .toArray(CompletableFuture<?>[]::new))
                .thenRun(this::closeLoaders);
    }

    CompletionStage<Void> whenClosed() {
        return disposal.minimalCompletionStage();
    }

    private void closeLoaders() {
        IOException failed = null;
        try {
            exporters.close();
        } catch (IOException failure) {
            failed = failure;
        }
        try {
            slicers.close();
        } catch (IOException failure) {
            if (failed == null) failed = failure;
            else failed.addSuppressed(failure);
        }
        try {
            printers.close();
        } catch (IOException failure) {
            if (failed == null) failed = failure;
            else failed.addSuppressed(failure);
        }
        if (failed == null) disposal.complete(null);
        else {
            log.warn("Cannot close plugin loaders cleanly", failed);
            disposal.completeExceptionally(failed);
        }
    }
}
