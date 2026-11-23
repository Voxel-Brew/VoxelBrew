package dev.voxelbrew.importer;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.model.printing.GcodeJob;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GcodeReaderTest {
    @TempDir
    Path directory;

    @Test
    void filamentSummariesCombineToolsAndPreferReportedTotals() {
        var prusa = FilamentUsage.fromLines(List.of(
                "; filament_colour = #CC0033;#000000;#AABBCC",
                "; filament used [mm] = 1200, 0, 45.5",
                "; total filament used [g] = 4.7",
                "; filament used [g] = 4.2, 0, 0.4",
                "; total filament used for wipe tower [g] = 0.1"));
        assertThat(prusa.millimeters()).hasValue(1245.5);
        assertThat(prusa.grams()).hasValue(4.7);
        assertThat(prusa.filaments()).extracting(FilamentUsage.Filament::slot).containsExactly(1, 3);
        var third = prusa.filaments().getLast();
        assertThat(third.color()).isEqualTo("#AABBCC");
        assertThat(third.grams()).hasValue(0.4);
        assertThat(third.millimeters()).hasValue(45.5);
        assertThat(FilamentUsage.fromLines(List.of("; total filament length [mm] : 123"))
                        .millimeters())
                .hasValue(123);
        assertThat(FilamentUsage.fromLines(List.of(";Filament used: 1.2m, 0.05m"))
                        .millimeters())
                .hasValue(1250);
        var missing =
                FilamentUsage.fromLines(List.of("G28", "; filament used [mm] = NaN", "; total filament used [g] = -1"));
        assertThat(missing.millimeters()).isEmpty();
        assertThat(missing.grams()).isEmpty();
    }

    @Test
    void orcaFeatureMarkersAndQuarterCircleRetainExtrusionAndLayer() throws Exception {
        var input =
                new java.io.ByteArrayInputStream(("G90\nM83\nG1 Z0.2\n; FEATURE: Outer wall\n" + "G2 X1 Y1 I1 J0 E2\n")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var job = GcodeReader.read(input);
        assertThat(job.modelLayers()).isEqualTo(List.of(0.2));
        assertThat(job.segments().stream()
                        .mapToDouble(GcodeJob.Segment::extrusion)
                        .sum())
                .isCloseTo(2, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(job.segments().stream().mapToDouble(GcodeJob.Segment::length).sum())
                .isCloseTo(Math.PI / 2, org.assertj.core.data.Offset.offset(0.01));
        assertThat(job.segments().stream().allMatch(s -> s.role() == GcodeJob.Role.EXTERNAL_PERIMETER))
                .isTrue();
        assertThat(job.segments().getLast().x1()).isEqualTo(1);
        assertThat(job.segments().getLast().y1()).isEqualTo(1);
    }

    @Test
    void curaRolesSeparateModelFromSupportAndTravel() throws Exception {
        Path file = directory.resolve("cura.gcode");
        Files.writeString(
                file,
                "G21\nG90\nM82\nG1 Z0.2\n;TYPE:WALL-OUTER\nG1 X10 E1\n;TYPE:WALL-INNER\nG1 X20 E2\n;TYPE:FILL\nG1 X30 E3\nG1 Z0.4\n;TYPE:SUPPORT\nG1 X40 E4\n;TYPE:SUPPORT-INTERFACE\nG1 X50 E5\nG1 X60\n");
        var job = GcodeReader.read(file);
        assertThat(job.modelLayers()).isEqualTo(List.of(.2));
        assertThat(job.segments().stream().map(GcodeJob.Segment::role).toList())
                .isEqualTo(List.of(
                        GcodeJob.Role.EXTERNAL_PERIMETER,
                        GcodeJob.Role.PERIMETER,
                        GcodeJob.Role.INFILL,
                        GcodeJob.Role.SUPPORT,
                        GcodeJob.Role.INTERFACE,
                        GcodeJob.Role.TRAVEL));
    }

    @Test
    void parserCountsDepositedMotionRatherThanLayerCommentsAndRejectsUnsupportedDialects() throws Exception {
        Path file = directory.resolve("motion.gcode");
        Files.writeString(
                file,
                "G21\nG90\nM82\nG1 Z0.2\n;TYPE:Perimeter\nG1 X10 E1\nG92 E0\nG1 X20 E1\nG1 Z8\nG1 X30\nG1 Z0.4\nM83\nG91\nG1 X1 E0.1\n;LAYER_CHANGE\n;LAYER_CHANGE\n");
        var job = GcodeReader.read(file);
        assertThat(job.modelLayers()).isEqualTo(List.of(.2, .4));
        assertThat(job.segments().stream().filter(GcodeJob.Segment::deposits).count())
                .isEqualTo(3);
        Files.writeString(file, "G20\nG1 X1 E1\n");
        assertThatThrownBy(() -> GcodeReader.read(file)).isInstanceOf(java.io.IOException.class);
        Files.writeString(file, "G3 X1 Y1 R0 E1\n");
        assertThatThrownBy(() -> GcodeReader.read(file)).isInstanceOf(java.io.IOException.class);
    }
}
