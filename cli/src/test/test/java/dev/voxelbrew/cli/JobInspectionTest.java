package dev.voxelbrew.cli;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.model.*;
import dev.voxelbrew.api.model.printing.*;
import dev.voxelbrew.engine.model.*;
import dev.voxelbrew.engine.printing.*;
import dev.voxelbrew.importer.GcodeSource;
import java.io.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class JobInspectionTest {
    private String output;
    private final List<String> reads = new ArrayList<>();

    private GcodeJob.Segment segment(double z, GcodeJob.Role role) {
        return new GcodeJob.Segment(0, 0, z, 10, 0, z, 1, .4, .2, role);
    }

    private int run(CadDocument document, String... args) {
        var job = new GcodeJob(
                List.of(
                        segment(.2, GcodeJob.Role.PERIMETER),
                        segment(.2, GcodeJob.Role.INFILL),
                        segment(.4, GcodeJob.Role.SUPPORT),
                        segment(.4, GcodeJob.Role.TRAVEL)),
                List.of(.2, .4),
                OptionalDouble.of(12),
                Optional.of("1 minute"),
                Map.of("backend", "fixture"));
        var reader = new GcodeSource() {
            public GcodeJob read(Path path) {
                reads.add("file:" + path);
                return job;
            }

            public GcodeJob readLines(List<String> lines) {
                reads.addAll(lines);
                return job;
            }
        };
        var root = new CommandLine(CommandLine.Model.CommandSpec.create().name("test"));
        root.getCommandSpec()
                .addOption(CommandLine.Model.OptionSpec.builder("--document")
                        .type(Path.class)
                        .scopeType(CommandLine.ScopeType.INHERIT)
                        .build());
        root.addSubcommand("inspect", new PrintingCommands.InspectJob(reader, path -> document));
        var text = new StringWriter();
        root.setOut(new PrintWriter(text));
        root.setErr(new PrintWriter(text));
        root.setExecutionExceptionHandler((failure, command, parsed) -> {
            command.getErr().println(failure.getMessage());
            return 1;
        });
        int result = root.execute(args);
        output = text.toString();
        return result;
    }

    @Test
    void layerAndRoleFiltersSelectActualSegmentsAndRetainEstimates() {
        assertThat(run(
                        CadDocument.empty(),
                        "inspect",
                        "job.gcode",
                        "--from-layer",
                        "2",
                        "--to-layer",
                        "2",
                        "--role",
                        "SUPPORT",
                        "--paths"))
                .isEqualTo(0);
        assertThat(reads).isEqualTo(List.of("file:job.gcode"));
        assertThat(output.contains("filtered_segments=1")).isTrue();
        assertThat(output.contains("role=SUPPORT")).isTrue();
        assertThat(output.contains("role=PERIMETER")).isFalse();
        assertThat(output.contains("role=TRAVEL")).isFalse();
        assertThat(output.contains("estimated_time=1 minute")).isTrue();
        assertThat(output.contains("filament_mm=12.0")).isTrue();
    }

    @Test
    void selectedStoredPlateUsesEmbeddedReaderInsteadOfOpeningAFile() {
        var initial = CadDocument.empty();
        var setup = initial.printSetup().addPlate("second");
        var document = new CadDocument(initial.id(), List.of(), 0, setup)
                .withGcode(Map.of("plate-1", List.of("first"), "second", List.of("second")));
        assertThat(run(document, "--document", "design.vxb", "inspect")).isEqualTo(0);
        assertThat(reads).isEqualTo(List.of("second"));
        reads.clear();
        assertThat(run(document, "--document", "design.vxb", "inspect", "--plate", "plate-1"))
                .isEqualTo(0);
        assertThat(reads).isEqualTo(List.of("first"));
    }

    @Test
    void invalidRangesFailRatherThanReturningAnEmptySuccessfulPreview() {
        for (String[] range : new String[][] {{"0", "1"}, {"2", "1"}, {"1", "3"}}) {
            assertThat(run(
                            CadDocument.empty(),
                            "inspect",
                            "job.gcode",
                            "--from-layer",
                            range[0],
                            "--to-layer",
                            range[1]))
                    .isEqualTo(1);
            assertThat(output.contains("Invalid layer range")).isTrue();
        }
    }

    @Test
    void ambiguousInputAndMissingStoredJobAreRejectedBeforeReadingToolpaths() {
        assertThat(run(CadDocument.empty(), "--document", "design.vxb", "inspect", "job.gcode"))
                .isEqualTo(1);
        assertThat(reads.isEmpty()).isTrue();
        assertThat(run(CadDocument.empty(), "--document", "design.vxb", "inspect"))
                .isEqualTo(1);
        assertThat(reads.isEmpty()).isTrue();
    }
}
