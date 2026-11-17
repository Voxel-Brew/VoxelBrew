package dev.voxelbrew.slicer;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.model.printing.*;
import dev.voxelbrew.engine.printing.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BackendConfigurationTest {
    @TempDir
    Path directory;

    @Test
    void registryReadsSeparateFilesAndMigratesLegacySettings() throws Exception {
        var configuration = new dev.voxelbrew.util.config.UserConfiguration(directory.resolve("config.properties"));
        configuration.set("slicer.prusa-slicer.executable", "legacy-prusa");
        var cura = new dev.voxelbrew.util.config.UserConfiguration(directory.resolve("slicers/cura.properties"));
        cura.set("executable", "separate-cura");
        try (var registry = new SlicerRegistry(directory, configuration)) {
            assertThat(registry.require("prusa-slicer").executable()).isEqualTo(Path.of("legacy-prusa"));
            assertThat(registry.require("cura").executable()).isEqualTo(Path.of("separate-cura"));
            assertThat(configuration.read().isEmpty()).isTrue();
        }
        try (var registry = new SlicerRegistry(directory, configuration)) {
            assertThat(registry.require("prusa-slicer").executable()).isEqualTo(Path.of("legacy-prusa"));
        }
    }

    @Test
    void registryKeepsProviderSettingsSeparateAndReportsInvalidOptions() throws Exception {
        try (var registry = new SlicerRegistry(
                directory,
                Map.of(
                        "slicer.prusa-slicer.executable", "prusa custom.exe",
                        "slicer.cura.executable", "cura custom.exe",
                        "slicer.cura.definition", "machine.json"))) {
            assertThat(registry.require("prusa-slicer").executable()).isEqualTo(Path.of("prusa custom.exe"));
            var cura = registry.require("cura");
            assertThat(cura.executable()).isEqualTo(Path.of("cura custom.exe"));
            assertThat(cura.configure(Map.of("settings", "process.properties")).executable())
                    .isEqualTo(Path.of("cura custom.exe"));
            assertThat(cura.configure(Map.of("executable", "override")).executable())
                    .isEqualTo(Path.of("override"));
            assertThat(cura.executable()).isEqualTo(Path.of("cura custom.exe"));
            assertThat(registry.diagnostics().isEmpty()).isTrue();
        }
        try (var registry = new SlicerRegistry(directory, Map.of("slicer.cura.typo", "value"))) {
            assertThat(registry.require("prusa-slicer")).isNotNull();
            assertThatThrownBy(() -> registry.require("cura")).isInstanceOf(IllegalArgumentException.class);
            assertThat(registry.diagnostics().isEmpty()).isFalse();
        }
    }
}
