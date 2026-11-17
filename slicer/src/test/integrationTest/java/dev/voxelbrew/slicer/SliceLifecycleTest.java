package dev.voxelbrew.slicer;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.model.mesh.MeshOptions;
import dev.voxelbrew.api.model.printing.*;
import dev.voxelbrew.api.slicer.SlicerBackend;
import dev.voxelbrew.engine.printing.*;
import dev.voxelbrew.exporter.printing.*;
import dev.voxelbrew.slicer.prusa.PrusaSlicerBackend;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@SuppressWarnings("deprecation")
class SliceLifecycleTest {
    @TempDir
    Path directory;

    @Test
    void cancellationKillsRealSlicerAndPreservesDestination() throws Exception {
        var session = PrintingTest.reference();
        var model = dev.voxelbrew.engine.printing.ManufacturingModels.evaluate(session.document(), MeshOptions.DEFAULT);
        Path output = directory.resolve("preserved job.gcode");
        Files.writeString(output, "original");
        var cancellation = new Cancellation();
        var log = new ArrayList<String>();
        assertThatThrownBy(() -> SliceService.slice(
                        new PrusaSlicerBackend(),
                        session.document(),
                        model,
                        PrusaSlicerBackend.defaultExecutable(),
                        output,
                        true,
                        cancellation,
                        line -> {
                            synchronized (log) {
                                log.add(line);
                            }
                            if (line.contains("Processing triangulated mesh")) cancellation.cancel();
                        },
                        dev.voxelbrew.api.slicer.Slicing.DIRECT))
                .isInstanceOf(CancellationException.class);
        assertThat(Files.readString(output)).isEqualTo("original");
        assertThat(cancellation.cancelled()).isTrue();
        try (var paths = Files.list(directory)) {
            assertThat(paths.toList()).isEqualTo(List.of(output));
        }
    }

    @Test
    void backendFailureWrongFormatAndMissingProfilesNeverPublish() throws Exception {
        var session = PrintingTest.reference();
        var model = dev.voxelbrew.engine.printing.ManufacturingModels.evaluate(session.document(), MeshOptions.DEFAULT);
        Path output = directory.resolve("failed.gcode");
        Files.writeString(output, "original");
        var backend = new SlicerBackend() {
            public Descriptor descriptor() {
                return new PrusaSlicerBackend().descriptor();
            }

            public Probe probe(Path exe, Cancellation cancellation) {
                return new Probe("failure-fixture", Set.of());
            }

            public RunResult slice(Request request) throws IOException {
                Files.writeString(request.output(), "partial");
                throw new IOException("forced backend failure");
            }
        };
        var failure = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> SliceService.slice(
                        backend,
                        session.document(),
                        model,
                        Path.of("unused"),
                        output,
                        true,
                        new Cancellation(),
                        line -> {},
                        dev.voxelbrew.api.slicer.Slicing.DIRECT),
                IOException.class);
        assertThat(failure.getMessage().contains("forced backend failure")).isTrue();
        assertThat(Files.readString(output)).isEqualTo("original");
        var p = model.profile().printer();
        var wrong = new PrintProfile(
                new PrintProfile.Printer(
                        p.id(),
                        p.name(),
                        p.nozzleMm(),
                        p.bed(),
                        p.excludedZones(),
                        p.usableHeight(),
                        p.firmware(),
                        "bgcode"),
                model.profile().material(),
                model.profile().process());
        assertThatThrownBy(() -> SliceService.slice(
                        backend,
                        session.document(),
                        new ManufacturingModel(
                                model.documentId(), model.revision(), model.setup(), wrong, model.meshes()),
                        Path.of("unused"),
                        output,
                        true,
                        new Cancellation(),
                        line -> {},
                        dev.voxelbrew.api.slicer.Slicing.DIRECT))
                .isInstanceOf(IOException.class);
        session.setPrintSetup(
                session.document().printSetup().withProfile("prusa-slicer", "missing", "PLA", ProcessSettings.DEFAULT));
        assertThat(org.assertj.core.api.Assertions.catchThrowableOfType(
                                () -> dev.voxelbrew.engine.printing.ManufacturingModels.evaluate(
                                        session.document(), MeshOptions.DEFAULT),
                                IllegalArgumentException.class)
                        .getMessage()
                        .contains("Missing printer profile"))
                .isTrue();
        assertThat(Files.readString(output)).isEqualTo("original");
    }
}
