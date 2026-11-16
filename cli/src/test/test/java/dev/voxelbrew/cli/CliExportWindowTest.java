package dev.voxelbrew.cli;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.api.exporter.ExportDialog;
import dev.voxelbrew.api.exporter.FileExportDialog;
import dev.voxelbrew.api.exporter.component.ExportDialogLayout;
import dev.voxelbrew.api.exporter.component.FileDestination;
import dev.voxelbrew.api.exporter.component.VerticalLayout;
import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

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

        assertEquals(Path.of("report.json"), settings.path());
        assertFalse(settings.overwrite());
        assertEquals("Destination[destination]: Overwrite[overwrite]: ", output.toString());
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

        assertEquals(Path.of("new.json"), settings.path());
        assertTrue(settings.overwrite());
        assertEquals("", output.toString());
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

        assertTrue(window.show(new FileExportDialog("JSON", "json")).isEmpty());
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
            public ExportDialogLayout layout() {
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

        var failure = assertThrows(IllegalArgumentException.class, () -> window.show(dialog));

        assertEquals(
                "Missing export inputs: --export.bundle.model (Destination), --export.bundle.preview (Destination)",
                failure.getMessage());
        assertEquals("", output.toString());
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
            public ExportDialogLayout layout() {
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

        assertEquals("report.txt", window.show(dialog).orElseThrow().get("destination"));
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

        assertEquals(
                Path.of("report.json"),
                window.show(new FileExportDialog("JSON", "json")).orElseThrow().path());
        assertTrue(errors.toString().contains("Destination[destination]: Invalid destination path"));
        assertEquals("Destination[destination]: Destination[destination]: Overwrite[overwrite]: ", output.toString());
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

        var failure =
                assertThrows(IllegalArgumentException.class, () -> window.show(new FileExportDialog("JSON", "json")));
        assertTrue(failure.getMessage().contains("--export.json.destination (Destination): Choose a destination"));
        assertTrue(
                failure.getMessage().contains("--export.json.overwrite (Overwrite): Overwrite must be true or false"));
        assertEquals("", output.toString());
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

        var failure =
                assertThrows(IllegalArgumentException.class, () -> window.show(new FileExportDialog("JSON", "json")));
        assertTrue(failure.getMessage().contains("--export.json.destination (Destination): Choose a destination"));
        assertTrue(
                failure.getMessage().contains("--export.json.overwrite (Overwrite): Overwrite must be true or false"));
    }
}
