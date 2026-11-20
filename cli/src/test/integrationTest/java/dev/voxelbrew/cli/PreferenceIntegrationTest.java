package dev.voxelbrew.cli;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.printer.PrinterProfileCatalog;
import dev.voxelbrew.api.slicer.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** Real configuration files and runtime catalogs; no executable invocation or network. */
class PreferenceIntegrationTest {
    @TempDir
    Path directory;

    private String output;

    private int run(String... args) {
        var root = new CommandLine(CommandLine.Model.CommandSpec.create().name("test"));
        new PreferenceCommands(
                        new dev.voxelbrew.util.preferences.UserConfigServiceImpl(directory, "config.properties"),
                        new dev.voxelbrew.printer.DefaultPrinterProfileCatalog(directory.resolve("printers")),
                        () -> ServiceLoader.load(SlicerProviderRegistry.class)
                                .findFirst()
                                .orElseThrow())
                .install(root);
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
    void preferencesSurviveFreshCommandInstancesAndResetPreservesOtherKeys() throws Exception {
        assertThat(run("settings", "set", "--key", "language", "--value", "pl")).isEqualTo(0);
        assertThat(run("settings", "set", "--key", "appearance", "--value", "dark"))
                .isEqualTo(0);
        assertThat(run("settings", "reset", "--key", "language")).isEqualTo(0);
        var saved = new dev.voxelbrew.util.preferences.UserConfigServiceImpl(directory, "config.properties")
                .readUserConfig();
        assertThat(saved.containsKey("language")).isFalse();
        assertThat(saved.get("appearance")).isEqualTo("dark");
        assertThat(run("settings", "show")).isEqualTo(0);
        assertThat(output.contains("appearance=dark")).isTrue();
    }

    @Test
    void visibilityOperationsApplyToAllDefaultsAndRejectCustomOrUnknownIdsWithoutWriting() throws Exception {
        var config = new dev.voxelbrew.util.preferences.UserConfigServiceImpl(directory, "config.properties");
        var catalog = new dev.voxelbrew.printer.DefaultPrinterProfileCatalog(directory.resolve("printers")).load();
        var ids = catalog.entries().stream()
                .filter(PrinterProfileCatalog.Entry::bundled)
                .map(e -> e.definition().printer().id())
                .collect(java.util.stream.Collectors.toSet());
        assertThat(ids.isEmpty()).isFalse();
        assertThat(run("slicer", "profile", "visibility", "--operation", "deselect-all"))
                .isEqualTo(0);
        assertThat(Set.of(config.readUserConfig().get("printers.hiddenDefaults").split(",")))
                .isEqualTo(ids);
        assertThat(run("slicer", "profile", "visibility", "--operation", "invert"))
                .isEqualTo(0);
        assertThat(config.readUserConfig()).containsEntry("printers.hiddenDefaults", "");
        assertThat(run(
                        "slicer",
                        "profile",
                        "visibility",
                        "--operation",
                        "hide",
                        "--id",
                        ids.iterator().next()))
                .isEqualTo(0);
        Path file = config.homeFolder().resolve("config.properties");
        byte[] before = Files.readAllBytes(file);
        assertThat(run("slicer", "profile", "visibility", "--operation", "hide", "--id", "missing-profile"))
                .isEqualTo(1);
        assertThat(Files.readAllBytes(file)).containsExactly(before);
        assertThat(run("slicer", "profile", "visibility", "--operation", "select-all"))
                .isEqualTo(0);
        assertThat(config.readUserConfig()).containsEntry("printers.hiddenDefaults", "");
    }

    @Test
    void providerOptionsPersistAndUnknownOptionsCannotOverwriteThem() throws Exception {
        String executable = directory.resolve("slicer executable").toString();
        assertThat(run(
                        "slicer",
                        "backend",
                        "configure",
                        "--id",
                        "prusa-slicer",
                        "--option",
                        "executable=" + executable))
                .as(output)
                .isEqualTo(0);
        Path defaults = directory.resolve("slicers/prusa-slicer.properties");
        var store = new dev.voxelbrew.slicer.SlicerInstallationStore(
                new dev.voxelbrew.util.preferences.UserConfigServiceImpl(directory, "config.properties"));
        assertThat(store.providerDefaults("prusa-slicer")).containsEntry("executable", executable);
        byte[] before = Files.readAllBytes(defaults);
        assertThat(run("slicer", "backend", "configure", "--id", "prusa-slicer", "--option", "invalid"))
                .isEqualTo(1);
        assertThat(Files.readAllBytes(defaults)).containsExactly(before);
        assertThat(run("slicer", "backend", "describe", "--id", "prusa-slicer")).isEqualTo(0);
        assertThat(output.contains("executable=" + executable)).isTrue();
    }

    @Test
    void exportingDefaultProfilesPublishesConsumerReadableCopiesAndNeverOverwrites() throws Exception {
        var catalog = new dev.voxelbrew.printer.DefaultPrinterProfileCatalog(directory.resolve("printers"));
        String id = catalog.load().entries().stream()
                .filter(PrinterProfileCatalog.Entry::bundled)
                .findFirst()
                .orElseThrow()
                .definition()
                .printer()
                .id();
        assertThat(run("slicer", "profile", "export", "--id", id)).isEqualTo(0);
        assertThat(output.contains("Exported 1 profiles")).isTrue();
        var copy = catalog.load().entries().stream()
                .filter(e -> !e.bundled())
                .findFirst()
                .orElseThrow();
        assertThat(copy.definition().printer().id()).isEqualTo("custom-" + id);
        Path json;
        try (var files = Files.list(catalog.directory())) {
            json = files.filter(p -> p.toString().endsWith(".json")).findFirst().orElseThrow();
        }
        byte[] before = Files.readAllBytes(json);
        assertThat(run("slicer", "profile", "export", "--id", id)).isEqualTo(0);
        assertThat(output.contains("Exported 0 profiles")).isTrue();
        assertThat(Files.readAllBytes(json)).containsExactly(before);
    }
}
