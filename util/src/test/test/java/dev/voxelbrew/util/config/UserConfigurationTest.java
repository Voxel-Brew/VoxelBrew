package dev.voxelbrew.util.config;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UserConfigurationTest {
    @TempDir
    Path directory;

    @Test
    void defaultSaveFolderUsesHomeAndExpandsConfiguredPaths() throws Exception {
        var config = new dev.voxelbrew.util.config.FileUserConfiguration(directory.resolve("save.properties"));
        Path home = Path.of(System.getProperty("user.home")).toAbsolutePath();
        assertThat(config.defaultSaveFolder()).isEqualTo(home.resolve("VoxelBrew"));
        assertThat(Files.exists(config.path())).isFalse();
        config.set("save.folder", " ");
        assertThat(config.defaultSaveFolder()).isEqualTo(home.resolve("VoxelBrew"));
        config.set("save.folder", directory.resolve("my designs").toString());
        assertThat(new dev.voxelbrew.util.config.FileUserConfiguration(config.path()).defaultSaveFolder())
                .isEqualTo(directory.resolve("my designs"));
        config.set("save.folder", "~/Designs");
        assertThat(config.defaultSaveFolder()).isEqualTo(home.resolve("Designs"));
    }

    @Test
    void utf8RoundTripMergesUnknownKeysAndSupportsRemovalAndMigration() throws Exception {
        var config = new dev.voxelbrew.util.config.FileUserConfiguration(
                directory.resolve("settings with spaces/config.properties"));
        assertThat(config.read()).isEqualTo(Map.of());
        assertThat(Files.exists(config.path().getParent())).isFalse();
        config.update(Map.of("language", "pl", "plugin.vendor", "Zażółć gęślą jaźń ☕\nnext=line"), Set.of());
        new dev.voxelbrew.util.config.FileUserConfiguration(config.path()).set("appearance", "DARK");
        config.setIfAbsent("appearance", "LIGHT");
        assertThat(config.get("appearance", "SYSTEM")).isEqualTo("DARK");
        assertThat(config.get("plugin.vendor", "")).isEqualTo("Zażółć gęślą jaźń ☕\nnext=line");
        assertThatThrownBy(() -> config.read().put("key", "value")).isInstanceOf(UnsupportedOperationException.class);
        config.remove("language");
        assertThat(config.get("language", "en")).isEqualTo("en");
        assertThat(config.read().size()).isEqualTo(2);
    }

    @Test
    void malformedConfigurationAndInvalidEditsPreserveExistingBytes() throws Exception {
        Path file = directory.resolve("config.properties");
        Files.writeString(file, "bad=\\uZZZZ\n");
        byte[] before = Files.readAllBytes(file);
        var config = new dev.voxelbrew.util.config.FileUserConfiguration(file);
        assertThatThrownBy(config::read).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> config.set("appearance", "DARK")).isInstanceOf(IOException.class);
        assertThat(Files.readAllBytes(file)).containsExactly(before);
        Files.writeString(file, "unknown=keep\n");
        assertThatThrownBy(() -> config.set(" ", "value")).isInstanceOf(IllegalArgumentException.class);
        assertThat(Files.readString(file)).isEqualTo("unknown=keep\n");
        try (var entries = Files.list(directory)) {
            assertThat(entries.noneMatch(path -> path.toString().endsWith(".tmp")))
                    .isTrue();
        }
    }

    @Test
    void separateInstancesMergeConcurrentEditsWithoutLostKeys() throws Exception {
        Path file = directory.resolve("config.properties");
        try (var workers = Executors.newFixedThreadPool(4)) {
            var futures = new ArrayList<Future<?>>();
            for (int index = 0; index < 40; index++) {
                String key = "key." + index;
                futures.add(workers.submit(() -> {
                    new dev.voxelbrew.util.config.FileUserConfiguration(file).set(key, key);
                    return null;
                }));
            }
            for (var future : futures) future.get(20, TimeUnit.SECONDS);
        }
        var values = new dev.voxelbrew.util.config.FileUserConfiguration(file).read();
        assertThat(values.size()).isEqualTo(40);
        values.forEach((key, value) -> assertThat(value).isEqualTo(key));
    }
}
