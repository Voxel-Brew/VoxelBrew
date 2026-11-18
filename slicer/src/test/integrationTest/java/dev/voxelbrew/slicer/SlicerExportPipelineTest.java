package dev.voxelbrew.slicer;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.exporter.*;
import dev.voxelbrew.api.model.mesh.MeshOptions;
import dev.voxelbrew.api.model.printing.*;
import dev.voxelbrew.api.slicer.SlicerBackend;
import dev.voxelbrew.engine.printing.*;
import dev.voxelbrew.exporter.*;
import dev.voxelbrew.exporter.printing.*;
import dev.voxelbrew.slicer.prusa.PrusaSlicerBackend;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@SuppressWarnings("deprecation")
class SlicerExportPipelineTest {
    @TempDir
    Path directory;

    @Test
    void selectedExporterCreatesInputBeforeBackendRuns() throws Exception {
        var document = PrintingTest.reference().document();
        var model = ManufacturingModels.evaluate(document, MeshOptions.DEFAULT);
        Path output = directory.resolve("preserved.gcode");
        Files.writeString(output, "original");
        try (var builtins = new dev.voxelbrew.api.exporter.ServiceLoaderExporterProviderRegistry(directory)) {
            for (String format : List.of("stl", "3mf", "prusa-project")) {
                var exports = new AtomicInteger();
                var runs = new AtomicInteger();
                var original = builtins.require(format);
                if (!(original instanceof GeometryExporterProvider geometry))
                    throw new AssertionError("Expected geometry exporter");
                ExporterProvider<?> tracked =
                        new GeometryExporterProvider(original.descriptor(), plugins -> (doc, window) -> {
                            assertThat(doc).isSameAs(document);
                            exports.incrementAndGet();
                            return geometry.create().export(doc, window);
                        });
                try (var registry = new dev.voxelbrew.api.exporter.ServiceLoaderExporterProviderRegistry(
                        directory, List.of(tracked))) {
                    var backend = backend(format, request -> {
                        assertThat(exports.get()).isEqualTo(1);
                        assertThat(Files.size(request.input()) > 100).isTrue();
                        assertThat(request.input().getParent()).isEqualTo(request.workingDirectory());
                        assertThat(request.model()).isSameAs(model);
                        runs.incrementAndGet();
                        throw new IOException("Backend reached after export");
                    });
                    var failure = org.assertj.core.api.Assertions.catchThrowableOfType(
                            () -> SliceService.slice(
                                    backend,
                                    document,
                                    model,
                                    registry,
                                    directory,
                                    Path.of("unused"),
                                    output,
                                    true,
                                    new Cancellation(),
                                    line -> {},
                                    dev.voxelbrew.api.slicer.Slicing.DIRECT),
                            IOException.class);
                    assertThat(failure.getMessage()).isEqualTo("Backend reached after export");
                    assertThat(runs.get()).isEqualTo(1);
                    assertThat(Files.readString(output)).isEqualTo("original");
                    try (var files = Files.list(directory)) {
                        assertThat(files.toList()).isEqualTo(List.of(output));
                    }
                }
            }
        }
    }

    @Test
    void failedOrCancelledExportNeverStartsBackendOrPublishesOutput() throws Exception {
        var document = PrintingTest.reference().document();
        var model = ManufacturingModels.evaluate(document, MeshOptions.DEFAULT);
        Path output = directory.resolve("preserved.gcode");
        Files.writeString(output, "original");
        for (boolean cancel : List.of(false, true)) {
            ExporterProvider<?> provider = new GeometryExporterProvider(
                    new ExporterDescriptor("input", "Failing input", "1.0"), plugins -> (doc, window) -> {
                        if (window.show(new MeshExportDialog(
                                        new MeshFormat("input", "Input", List.of("stl"), false, List.of())))
                                .isEmpty()) return Optional.empty();
                        if (cancel) return Optional.empty();
                        throw new IOException("Export failed");
                    });
            try (var registry = new dev.voxelbrew.api.exporter.ServiceLoaderExporterProviderRegistry(
                    directory, List.of(provider))) {
                var backend = backend("input", request -> {
                    fail("Backend started before a successful export");
                    return null;
                });
                var failure = org.assertj.core.api.Assertions.catchThrowableOfType(
                        () -> SliceService.slice(
                                backend,
                                document,
                                model,
                                registry,
                                directory,
                                Path.of("unused"),
                                output,
                                true,
                                new Cancellation(),
                                line -> {},
                                dev.voxelbrew.api.slicer.Slicing.DIRECT),
                        Exception.class);
                if (cancel) assertThat(failure).isInstanceOf(CancellationException.class);
                else assertThat(failure).isInstanceOf(IOException.class);
                assertThat(Files.readString(output)).isEqualTo("original");
                try (var files = Files.list(directory)) {
                    assertThat(files.toList()).isEqualTo(List.of(output));
                }
            }
        }
    }

    @FunctionalInterface
    interface Run {
        SlicerBackend.RunResult run(SlicerBackend.Request request) throws IOException;
    }

    private static SlicerBackend backend(String format, Run run) {
        return new SlicerBackend() {
            @Override
            public Descriptor descriptor() {
                return new PrusaSlicerBackend().descriptor();
            }

            @Override
            public Probe probe(Path executable, Cancellation cancellation) {
                return new Probe("fixture", descriptor().capabilities());
            }

            @Override
            public InputFormat inputFormat() {
                return new InputFormat(format, format.equals("stl") ? "stl" : "3mf");
            }

            @Override
            public RunResult slice(Request request) throws IOException {
                return run.run(request);
            }
        };
    }
}
