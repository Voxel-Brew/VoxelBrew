package dev.voxelbrew.util.config;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigurationSectionTest {
    @Test
    void migratesPreviousFileWithDestinationTakingPrecedence() throws Exception {
        var main = new dev.voxelbrew.util.config.FileUserConfiguration(directory.resolve("config.properties"));
        main.set("slicer.cura.executable", "oldest");
        var previous =
                new dev.voxelbrew.util.config.FileUserConfiguration(directory.resolve("config-slicer-cura.properties"));
        previous.update(Map.of("executable", "previous", "definition", "machine.json"), java.util.Set.of());
        var target = new dev.voxelbrew.util.config.FileUserConfiguration(directory.resolve("slicers/cura.properties"));
        target.set("executable", "current");
        assertThat(main.section("slicer", "cura").read())
                .isEqualTo(Map.of("executable", "current", "definition", "machine.json"));
        assertThat(Files.exists(previous.path())).isFalse();
        assertThat(main.read().isEmpty()).isTrue();
        assertThat(main.section("slicer", "cura").read()).isEqualTo(target.read());
    }

    @Test
    void failedFileMigrationKeepsPreviousFile() throws Exception {
        var main = new dev.voxelbrew.util.config.FileUserConfiguration(directory.resolve("config.properties"));
        var previous =
                new dev.voxelbrew.util.config.FileUserConfiguration(directory.resolve("config-slicer-cura.properties"));
        previous.set("executable", "kept");
        Files.createDirectories(directory.resolve("slicers/cura.properties"));
        assertThatThrownBy(() -> main.section("slicer", "cura")).isInstanceOf(IOException.class);
        assertThat(previous.get("executable", "")).isEqualTo("kept");
    }

    @TempDir
    Path directory;

    @Test
    void migratesOnlyThisProviderAndKeepsDestinationValuesAndUnknownKeys() throws Exception {
        var main = new dev.voxelbrew.util.config.FileUserConfiguration(directory.resolve("custom.properties"));
        main.update(
                Map.of(
                        "slicer.prusa-slicer.executable",
                        "old",
                        "slicer.prusa-slicer.custom",
                        "kept",
                        "slicer.cura.executable",
                        "cura",
                        "appearance",
                        "dark"),
                java.util.Set.of());
        var separate = new dev.voxelbrew.util.config.FileUserConfiguration(
                directory.resolve("slicers/prusa-slicer.properties"));
        separate.set("executable", "new");
        assertThat(main.section("slicer", "prusa-slicer").read())
                .isEqualTo(Map.of("executable", "new", "custom", "kept"));
        assertThat(main.read()).isEqualTo(Map.of("slicer.cura.executable", "cura", "appearance", "dark"));
        assertThat(main.section("slicer", "prusa-slicer").read()).isEqualTo(separate.read());
    }

    @Test
    void failedMigrationPreservesLegacyValues() throws Exception {
        var main = new dev.voxelbrew.util.config.FileUserConfiguration(directory.resolve("config.properties"));
        main.set("slicer.cura.executable", "kept");
        Files.createDirectories(directory.resolve("slicers/cura.properties"));
        assertThatThrownBy(() -> main.section("slicer", "cura")).isInstanceOf(IOException.class);
        assertThat(main.get("slicer.cura.executable", "")).isEqualTo("kept");
    }

    @Test
    void rejectsTraversalAndDoesNotCreateUnusedFiles() throws Exception {
        var main = new dev.voxelbrew.util.config.FileUserConfiguration(directory.resolve("config.properties"));
        assertThatThrownBy(() -> main.section("slicer", "../outside")).isInstanceOf(IllegalArgumentException.class);
        assertThat(main.section("slicer", "cura").read().isEmpty()).isTrue();
        assertThat(Files.exists(directory.resolve("slicers/cura.properties"))).isFalse();
        assertThat(Files.exists(main.path())).isFalse();
    }
}
