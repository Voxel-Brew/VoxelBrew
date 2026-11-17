package dev.voxelbrew.cli;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.engine.model.CadDocument;
import dev.voxelbrew.exporter.DocumentWriter;
import dev.voxelbrew.importer.DocumentReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentExportTest {
    @TempDir
    Path directory;

    @Test
    void noPromptReportsMissingDestinationOnStderr() throws Exception {
        Path source = directory.resolve("source.vxb");
        DocumentWriter.save(CadDocument.empty(), source, false);
        var cli = VoxelBrew.commandLine();
        var out = new StringWriter();
        var err = new StringWriter();
        cli.setOut(new PrintWriter(out));
        cli.setErr(new PrintWriter(err));

        assertThat(cli.execute("document", "export", source.toString(), "--format", "json", "--export-no-prompt"))
                .isNotEqualTo(0);

        assertThat(out.toString()).isEqualTo("");
        assertThat(err.toString().contains("--export.json.destination"))
                .as(err::toString)
                .isTrue();
    }

    @Test
    void namespacedComponentInputsSupplyDestinationAndOverwrite() throws Exception {
        var document = CadDocument.empty();
        Path source = directory.resolve("source.vxb");
        Path target = directory.resolve("report.json");
        DocumentWriter.save(document, source, false);
        var cli = VoxelBrew.commandLine();
        assertThat(cli.execute(
                        "document",
                        "export",
                        source.toString(),
                        "--format",
                        "json",
                        "--export.json.destination",
                        target.toString()))
                .isEqualTo(0);
        byte[] original = Files.readAllBytes(target);
        assertThat(cli.execute(
                        "document",
                        "export",
                        source.toString(),
                        "--format",
                        "json",
                        "--export.json.destination=" + target))
                .isNotEqualTo(0);
        assertThat(Files.readAllBytes(target)).containsExactly(original);
        assertThat(cli.execute(
                        "document",
                        "export",
                        source.toString(),
                        "--format",
                        "json",
                        "--export.json.destination=" + target,
                        "--export.json.overwrite=true"))
                .isEqualTo(0);
    }

    @Test
    @org.junit.jupiter.api.parallel.ResourceLock(org.junit.jupiter.api.parallel.Resources.SYSTEM_PROPERTIES)
    void defaultUserPluginsAreDiscoveredAndCanExportWithoutPluginArguments() throws Exception {
        String home = System.getProperty("user.home");
        String override = System.getProperty("voxelbrew.home");
        try {
            System.setProperty("user.home", directory.toString());
            System.clearProperty("voxelbrew.home");
            Path plugins = Files.createDirectories(directory.resolve(".voxelbrew/plugins"));
            Files.copy(Path.of(System.getProperty("voxelbrew.test.plugin")), plugins.resolve("provider.jar"));
            Files.writeString(plugins.resolve("README.txt"), "Ignored non-JAR file");
            for (var listing : java.util.Map.of(
                            "document exporters",
                            "document-probe",
                            "document importers",
                            "plate",
                            "designer feature types",
                            "example.spacer")
                    .entrySet()) {
                var cli = VoxelBrew.commandLine();
                var output = new StringWriter();
                cli.setOut(new PrintWriter(output));
                cli.setErr(new PrintWriter(new StringWriter()));
                assertThat(cli.execute(listing.getKey().split(" "))).isEqualTo(0);
                assertThat(output.toString().contains(listing.getValue()))
                        .as(output::toString)
                        .isTrue();
            }
            var document = CadDocument.empty();
            Path source = directory.resolve("source.vxb");
            Path target = directory.resolve("report.txt");
            DocumentWriter.save(document, source, false);
            assertThat(VoxelBrew.commandLine()
                            .execute(
                                    "document",
                                    "export",
                                    source.toString(),
                                    "--format",
                                    "document-probe",
                                    "--output",
                                    target.toString()))
                    .isEqualTo(0);
            assertThat(Files.readString(target)).isEqualTo("document=" + document.id());
        } finally {
            System.setProperty("user.home", home);
            if (override == null) System.clearProperty("voxelbrew.home");
            else System.setProperty("voxelbrew.home", override);
        }
    }

    @Test
    void daggerRegistryDiscoversAndInvokesAnIndependentDocumentProvider() throws Exception {
        Path plugins = Files.createDirectory(directory.resolve("plugins"));
        Files.copy(Path.of(System.getProperty("voxelbrew.test.plugin")), plugins.resolve("provider.jar"));
        Path source = directory.resolve("source.vxb");
        var document = CadDocument.empty();
        DocumentWriter.save(document, source, false);
        var cli = VoxelBrew.commandLine();
        var output = new StringWriter();
        cli.setOut(new PrintWriter(output));
        cli.setErr(new PrintWriter(new StringWriter()));
        assertThat(cli.execute("document", "exporters", "--plugin-dir", plugins.toString()))
                .isEqualTo(0);
        assertThat(output.toString().contains("document-probe")).isTrue();
        assertThat(output.toString().contains("Document probe (1.0)")).isTrue();
        assertThat(output.toString().lines().anyMatch(line -> line.equals("json\tJSON")))
                .isTrue();
        Path target = directory.resolve("report.txt");
        assertThat(cli.execute(
                        "document",
                        "export",
                        source.toString(),
                        "--format",
                        "document-probe",
                        "--plugin-dir",
                        plugins.toString(),
                        "--output",
                        target.toString()))
                .isEqualTo(0);
        assertThat(Files.readString(target)).isEqualTo("document=" + document.id());
    }

    @Test
    void documentFormatsWorkWithoutGeometryAndRequireExplicitOverwrite() throws Exception {
        var document = CadDocument.empty();
        Path source = directory.resolve("source.vxb");
        DocumentWriter.save(document, source, false);
        for (String format : java.util.List.of("vxb", "json")) {
            Path target = directory.resolve("copy." + format);
            var cli = VoxelBrew.commandLine();
            cli.setOut(new PrintWriter(new StringWriter()));
            cli.setErr(new PrintWriter(new StringWriter()));
            String[] args = {"document", "export", source.toString(), "--format", format, "--output", target.toString()
            };
            assertThat(cli.execute(args)).isEqualTo(0);
            byte[] original = Files.readAllBytes(target);
            assertThat(cli.execute(args)).isNotEqualTo(0);
            assertThat(Files.readAllBytes(target)).containsExactly(original);
            assertThat(cli.execute(
                            "document",
                            "export",
                            source.toString(),
                            "--format",
                            format,
                            "--output",
                            target.toString(),
                            "--overwrite"))
                    .isEqualTo(0);
            if (format.equals("vxb"))
                assertThat(DocumentReader.load(target).id()).isEqualTo(document.id());
            else assertThat(Files.readString(target).contains("\"documentId\"")).isTrue();
        }
    }
}
