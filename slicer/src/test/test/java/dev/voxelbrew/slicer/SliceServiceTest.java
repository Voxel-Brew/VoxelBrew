package dev.voxelbrew.slicer;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.model.printing.*;
import dev.voxelbrew.api.slicer.SlicerException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SliceServiceTest {
    @TempDir
    Path directory;

    @Test
    void physicalBambuTargetRequiresAPackageWithInspectableGcode() throws Exception {
        var original = model();
        var hardware = original.profile().printer();
        var bambu = new PrintProfile.Printer(
                "bambu-lab-a1",
                "Bambu Lab A1",
                hardware.nozzleMm(),
                hardware.bed(),
                hardware.excludedZones(),
                hardware.usableHeight(),
                "vendor",
                "gcode");
        var setup = original.setup()
                .withProfile("orca", bambu.id(), "PLA", original.setup().process())
                .withPrintTarget("saved-device");
        var model = new ManufacturingModel(
                "doc",
                "revision",
                setup,
                new PrintProfile(
                        bambu, original.profile().material(), original.profile().process()),
                Map.of());
        var metadata = new HashMap<>(SliceService.provenance(model, "orca"));
        metadata.put("backend_version", "fixture");
        byte[] gcode = ";TYPE:Perimeter\nG90\nM82\nG1 Z0.2\nG1 X10 E1\n".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> SliceService.inspect(model, "orca", PrintArtifact.of("gcode", gcode, metadata)))
                .isInstanceOf(SlicerException.InvalidArtifact.class)
                .hasMessageContaining("gcode.3mf");
        assertThatThrownBy(() -> SliceService.inspect(model, "orca", PrintArtifact.of("gcode.3mf", gcode, metadata)))
                .isInstanceOf(SlicerException.InvalidArtifact.class);
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("Metadata/plate_1.gcode"));
            zip.write(gcode);
            zip.closeEntry();
        }
        var artifact = PrintArtifact.of("gcode.3mf", output.toByteArray(), metadata);
        var result = SliceService.inspect(model, "orca", artifact);
        assertThat(result.job().modelLayers()).containsExactly(0.2);
        assertThat(result.artifact()).isSameAs(artifact);
    }

    private static PrintArtifact artifact(ManufacturingModel model) {
        var values = new HashMap<>(SliceService.provenance(model, "prusa-slicer"));
        values.put("backend_version", "process-double");
        var gcode = ";TYPE:Perimeter\nG90\nM82\nG1 Z0.2\nG1 X10 E1\n";
        return PrintArtifact.of("gcode", gcode.getBytes(StandardCharsets.UTF_8), values);
    }

    private static ManufacturingModel model() {
        var setup = dev.voxelbrew.api.model.CadDocument.empty().printSetup();
        return new ManufacturingModel("doc", "revision", setup, PrintProfile.resolve(setup), Map.of());
    }

    @Test
    void cancelledAndStalePublicationsLeaveExistingOutputUntouched() throws Exception {
        var existing = Files.writeString(directory.resolve("existing.gcode"), "original");
        var artifacts = Map.of(existing, artifact(model()));
        var cancelled = new Cancellation();
        cancelled.cancel();
        assertThatThrownBy(() -> SliceService.publish(artifacts, true, cancelled, () -> {}, () -> fail("attached")))
                .isInstanceOf(java.util.concurrent.CancellationException.class);
        assertThatThrownBy(() -> SliceService.publish(
                        artifacts,
                        true,
                        new Cancellation(),
                        () -> {
                            throw new SliceService.OutdatedException();
                        },
                        () -> fail("attached")))
                .isInstanceOf(SliceService.OutdatedException.class);
        assertThat(existing).hasContent("original");
    }

    @Test
    void inspectsDepositionAndRejectsWrongTargetAndBackend() throws Exception {
        var model = model();
        var artifact = artifact(model);
        assertThat(SliceService.inspect(model, "prusa-slicer", artifact).job().modelLayers())
                .containsExactly(0.2);
        assertThatThrownBy(() -> SliceService.inspect(model, "cura", artifact))
                .isInstanceOf(SlicerException.InvalidArtifact.class)
                .hasMessageContaining("backend");
        var changed = new ManufacturingModel(
                model.documentId(),
                model.revision(),
                model.setup().withPrintTarget("another-device"),
                model.profile(),
                Map.of());
        assertThatThrownBy(() -> SliceService.inspect(changed, "prusa-slicer", artifact))
                .isInstanceOf(SlicerException.InvalidArtifact.class)
                .hasMessageContaining("target");
    }

    @Test
    void failedAttachmentRestoresOverwrittenFilesAndRemovesNewOutputs() throws Exception {
        var existing = Files.writeString(directory.resolve("existing.gcode"), "original");
        var fresh = directory.resolve("fresh.gcode");
        var artifact = artifact(model());
        assertThatThrownBy(() -> SliceService.save(artifact, existing, false))
                .isInstanceOf(FileAlreadyExistsException.class);
        assertThatThrownBy(() -> SliceService.publish(Map.of(existing, artifact, fresh, artifact), true, () -> {
                    throw new IOException("attachment failed");
                }))
                .isInstanceOf(IOException.class)
                .hasMessage("attachment failed");
        assertThat(existing).hasContent("original");
        assertThat(fresh).doesNotExist();
        try (var paths = Files.list(directory)) {
            assertThat(paths.toList()).containsExactly(existing);
        }
    }
}
