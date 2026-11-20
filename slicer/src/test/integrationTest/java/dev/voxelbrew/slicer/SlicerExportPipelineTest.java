package dev.voxelbrew.slicer;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.model.mesh.MeshOptions;
import dev.voxelbrew.api.model.printing.*;
import dev.voxelbrew.engine.printing.ManufacturingModels;
import dev.voxelbrew.slicer.internal.ProcessSlicer;
import dev.voxelbrew.slicer.internal.ProcessSlicerProvider;
import dev.voxelbrew.slicer.prusa.PrusaSlicerBackend;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SlicerExportPipelineTest {
    private static ProcessSlicer process(String format, AtomicReference<Path> work) {
        return new ProcessSlicer() {
            @Override
            public Descriptor descriptor() {
                return new PrusaSlicerBackend().descriptor();
            }

            @Override
            public Path executable() {
                return Path.of("unused");
            }

            @Override
            public Probe probe(Path executable, Cancellation cancellation) {
                return new Probe("process double", descriptor().capabilities());
            }

            @Override
            public InputFormat inputFormat() {
                return new InputFormat(format, format.equals("stl") ? "stl" : "3mf");
            }

            @Override
            public RunResult slice(Request request) throws IOException {
                work.set(request.workingDirectory());
                assertThat(request.input()).isRegularFile().hasParent(request.workingDirectory());
                assertThat(Files.size(request.input())).isGreaterThan(100);
                Files.writeString(request.output(), "partial");
                throw new IOException("forced process failure");
            }
        };
    }

    @Test
    void simultaneousOperationsOwnSeparateScratchAndCancellation() throws Exception {
        var model = ManufacturingModels.evaluate(SlicerAcceptanceTest.cube().document(), MeshOptions.DEFAULT);
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var directories = ConcurrentHashMap.<Path>newKeySet();
        var provider = new ProcessSlicerProvider(() -> new ProcessSlicer() {
            @Override
            public Descriptor descriptor() {
                return new PrusaSlicerBackend().descriptor();
            }

            @Override
            public Path executable() {
                return Path.of("unused");
            }

            @Override
            public Probe probe(Path executable, Cancellation cancellation) {
                return new Probe("concurrent process double", descriptor().capabilities());
            }

            @Override
            public RunResult slice(Request request) throws IOException {
                directories.add(request.workingDirectory());
                entered.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("Process double timed out");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(e);
                }
                request.cancellation().check();
                Files.writeString(request.output(), ";TYPE:Perimeter\nG90\nM82\nG1 Z0.2\nG1 X10 E1\n");
                return new RunResult(Map.of());
            }
        }) {};
        var cancelled = new Cancellation();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() ->
                    SliceService.slice(provider, model, SlicerTestSupport.window(Map.of()), cancelled, ignored -> {}));
            var second = executor.submit(() -> SliceService.slice(
                    provider, model, SlicerTestSupport.window(Map.of()), new Cancellation(), ignored -> {}));
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(directories).hasSize(2);
                cancelled.cancel();
            } finally {
                release.countDown();
            }
            assertThatThrownBy(() -> first.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(CancellationException.class);
            assertThat(second.get(10, TimeUnit.SECONDS)).isPresent();
        }
        for (var directory : directories) assertThat(directory).doesNotExist();
    }

    @Test
    void writesEvaluatedInputAndCleansScratchWhenProcessFails() throws Exception {
        var model = ManufacturingModels.evaluate(SlicerAcceptanceTest.cube().document(), MeshOptions.DEFAULT);
        for (String format : List.of("stl", "3mf", "prusa-project")) {
            var work = new AtomicReference<Path>();
            var provider = new ProcessSlicerProvider(() -> process(format, work)) {};
            assertThatThrownBy(() -> SliceService.slice(
                            provider, model, SlicerTestSupport.window(Map.of()), new Cancellation(), ignored -> {}))
                    .isInstanceOf(IOException.class)
                    .hasMessage("forced process failure");
            assertThat(work.get()).isNotNull().doesNotExist();
        }
    }
}
