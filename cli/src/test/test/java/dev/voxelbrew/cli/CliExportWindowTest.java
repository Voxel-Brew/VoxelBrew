package dev.voxelbrew.cli;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.exporter.ExportDialog;
import dev.voxelbrew.api.exporter.FileExportDialog;
import dev.voxelbrew.api.model.vui.FileDestination;
import dev.voxelbrew.api.model.vui.Layout;
import dev.voxelbrew.api.model.vui.VerticalLayout;
import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

@SuppressWarnings("deprecation")
class CliExportWindowTest {
    @Test
    void promptsForMissingInputsUsingLabelAndId() {
        var output = new StringWriter();
        var window = new CliExportWindow(
                "json",
                Path.of("plugins"),
                Map.of(),
                new String[0],
                new PrintWriter(output),
                new PrintWriter(new StringWriter()),
                new BufferedReader(new StringReader("report.json\n\n")),
                true,
                false,
                false);

        var settings = window.show(new FileExportDialog("JSON", "json")).orElseThrow();

        assertThat(settings.path()).isEqualTo(Path.of("report.json"));
        assertThat(settings.overwrite()).isFalse();
        assertThat(output.toString()).isEqualTo("Destination[destination]: Overwrite[overwrite]: ");
    }

    @Test
    void namespacedArgumentsSkipPromptsAndOverrideLegacyValues() {
        var output = new StringWriter();
        var window = new CliExportWindow(
                "json",
                Path.of("plugins"),
                Map.of("destination", "old.json"),
                new String[] {"--export.json.destination=new.json", "--export.json.overwrite=true"},
                new PrintWriter(output),
                new PrintWriter(new StringWriter()),
                new BufferedReader(new StringReader("")),
                true,
                false,
                false);

        var settings = window.show(new FileExportDialog("JSON", "json")).orElseThrow();

        assertThat(settings.path()).isEqualTo(Path.of("new.json"));
        assertThat(settings.overwrite()).isTrue();
        assertThat(output.toString()).isEqualTo("");
    }

    @Test
    void endOfInputCancelsPrompt() {
        var window = new CliExportWindow(
                "json",
                Path.of("plugins"),
                Map.of(),
                new String[0],
                new PrintWriter(new StringWriter()),
                new PrintWriter(new StringWriter()),
                new BufferedReader(new StringReader("")),
                true,
                false,
                false);

        assertThat(window.show(new FileExportDialog("JSON", "json")).isEmpty()).isTrue();
    }

    @Test
    void noPromptReportsEveryMissingInputWithoutReading() {
        var output = new StringWriter();
        var window = new CliExportWindow(
                "bundle",
                Path.of("plugins"),
                Map.of(),
                new String[0],
                new PrintWriter(output),
                new PrintWriter(new StringWriter()),
                new BufferedReader(new StringReader("ignored")),
                false,
                true,
                false);
        ExportDialog<Map<String, String>> dialog = new ExportDialog<>() {
            @Override
            public String title() {
                return "Bundle";
            }

            @Override
            public Layout layout() {
                return new VerticalLayout(new FileDestination("model", "vxb"), new FileDestination("preview", "png"));
            }

            @Override
            public Map<String, String> buildSettings(Map<String, String> values) {
                return values;
            }

            @Override
            public Map<String, String> validate(Map<String, String> values) {
                return Map.of();
            }
        };

        var failure = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> window.show(dialog), IllegalArgumentException.class);

        assertThat(failure.getMessage())
                .isEqualTo(
                        "Missing export inputs: --export.bundle.model (Destination), --export.bundle.preview (Destination)");
        assertThat(output.toString()).isEqualTo("");
    }

    @Test
    void explicitFileDestinationDefaultWorksWithoutPrompt() {
        var window = new CliExportWindow(
                "txt",
                Path.of("plugins"),
                Map.of(),
                new String[0],
                new PrintWriter(new StringWriter()),
                new PrintWriter(new StringWriter()),
                new BufferedReader(new StringReader("")),
                false,
                true,
                false);
        ExportDialog<Map<String, String>> dialog = new ExportDialog<>() {
            @Override
            public String title() {
                return "Text";
            }

            @Override
            public Layout layout() {
                return new VerticalLayout(new FileDestination("destination", "txt", Path.of("report.txt")));
            }

            @Override
            public Map<String, String> buildSettings(Map<String, String> values) {
                return values;
            }

            @Override
            public Map<String, String> validate(Map<String, String> values) {
                return Map.of();
            }
        };

        assertThat(window.show(dialog).orElseThrow().get("destination")).isEqualTo("report.txt");
    }

    @Test
    void invalidTypedInputReportsErrorAndRetries() {
        var output = new StringWriter();
        var errors = new StringWriter();
        var window = new CliExportWindow(
                "json",
                Path.of("plugins"),
                Map.of(),
                new String[0],
                new PrintWriter(output),
                new PrintWriter(errors, true),
                new BufferedReader(new StringReader("\0\nreport.json\n\n")),
                true,
                false,
                false);

        assertThat(window.show(new FileExportDialog("JSON", "json"))
                        .orElseThrow()
                        .path())
                .isEqualTo(Path.of("report.json"));
        assertThat(errors.toString().contains("Destination[destination]: Invalid destination path"))
                .isTrue();
        assertThat(output.toString())
                .isEqualTo("Destination[destination]: Destination[destination]: Overwrite[overwrite]: ");
    }

    @Test
    void invalidSuppliedInputsAreReportedTogetherWithoutPrompt() {
        var output = new StringWriter();
        var window = new CliExportWindow(
                "json",
                Path.of("plugins"),
                Map.of("destination", "", "overwrite", "maybe"),
                new String[0],
                new PrintWriter(output),
                new PrintWriter(new StringWriter()),
                new BufferedReader(new StringReader("ignored")),
                true,
                false,
                false);

        var failure = catchThrowable(() -> window.show(new FileExportDialog("JSON", "json")));
        assertThat(failure).isInstanceOf(IllegalArgumentException.class);
        assertThat(failure.getMessage().contains("--export.json.destination (Destination): Choose a destination"))
                .isTrue();
        assertThat(failure.getMessage()
                        .contains("--export.json.overwrite (Overwrite): Overwrite must be true or false"))
                .isTrue();
        assertThat(output.toString()).isEqualTo("");
    }

    @Test
    void noPromptCombinesInvalidAndMissingInputs() {
        var window = new CliExportWindow(
                "json",
                Path.of("plugins"),
                Map.of("overwrite", "maybe"),
                new String[0],
                new PrintWriter(new StringWriter()),
                new PrintWriter(new StringWriter()),
                new BufferedReader(new StringReader("ignored")),
                false,
                true,
                false);

        var failure = catchThrowable(() -> window.show(new FileExportDialog("JSON", "json")));
        assertThat(failure).isInstanceOf(IllegalArgumentException.class);
        assertThat(failure.getMessage().contains("--export.json.destination (Destination): Choose a destination"))
                .isTrue();
        assertThat(failure.getMessage()
                        .contains("--export.json.overwrite (Overwrite): Overwrite must be true or false"))
                .isTrue();
    }
}
