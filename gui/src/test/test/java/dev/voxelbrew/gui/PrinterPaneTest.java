package dev.voxelbrew.gui;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.model.printing.PrintArtifact;
import dev.voxelbrew.api.printer.Printer;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PrinterPaneTest {
    private static dev.voxelbrew.api.printer.Profile profile() {
        var p = dev.voxelbrew.api.model.printing.PrintProfile.resolve(
                        dev.voxelbrew.api.model.printing.PrintSetup.empty())
                .printer();
        return new dev.voxelbrew.api.printer.Profile(
                p.id(),
                p.name(),
                "Generic",
                p.bed(),
                p.excludedZones(),
                p.usableHeight(),
                p.nozzleMm(),
                1.75,
                1,
                p.firmware(),
                p.outputFormat(),
                true,
                null);
    }

    @Test
    void artifactSavingPreservesNativeBytes(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory)
            throws Exception {
        var artifact = PrintArtifact.of("gcode.3mf", new byte[] {0, 1, 2, 3}, Map.of("target_kind", "profile"));
        var path = directory.resolve("job.gcode.3mf");
        dev.voxelbrew.slicer.SliceService.save(artifact, path, false);
        assertThat(java.nio.file.Files.readAllBytes(path)).isEqualTo(artifact.bytes());
    }

    @Test
    void slicedJobDeterminesTheOnlyPrinterDevice() {
        var other = new Printer(
                "other",
                "Other",
                "custom",
                new dev.voxelbrew.api.printer.driver.DriverConfiguration(Map.of()),
                profile());
        var target = new Printer(
                "target",
                "Target",
                "custom",
                new dev.voxelbrew.api.printer.driver.DriverConfiguration(Map.of()),
                profile());
        var devices = List.of(other, target);
        var sliced =
                PrintArtifact.of("gcode", new byte[] {1}, Map.of("target_kind", "printer", "target_id", target.id()));
        var missing =
                PrintArtifact.of("gcode", new byte[] {1}, Map.of("target_kind", "printer", "target_id", "deleted"));
        var profileOnly = PrintArtifact.of("gcode", new byte[] {1}, Map.of("target_kind", "profile"));

        assertThat(PrinterPane.deviceForJob(sliced, devices)).isSameAs(target);
        assertThat(PrinterPane.deviceForJob(missing, devices)).isNull();
        assertThat(PrinterPane.deviceForJob(profileOnly, devices)).isNull();
        assertThat(PrinterPane.deviceForJob(null, devices)).isNull();
    }
}
