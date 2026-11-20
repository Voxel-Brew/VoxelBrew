package dev.voxelbrew.util.preferences;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.userconfig.UserConfigException;
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
    void readsCurrentSettingsAfterExternalChangesAndWritesFromOtherInstances() throws IOException {
        Path file = Files.writeString(directory.resolve("config.properties"), "language=pl\n");
        var config =
                new UserConfigServiceImpl(file.getParent(), file.getFileName().toString());

        assertThat(config.readUserConfig("language")).contains("pl");
        Files.writeString(file, "language=en\nexternal=keep\n");
        assertThat(config.readUserConfig("language")).contains("en");
        assertThat(config.readUserConfig("external")).contains("keep");
        assertThat(config.readUserConfig()).containsEntry("language", "en");

        var peer =
                new UserConfigServiceImpl(file.getParent(), file.getFileName().toString());
        config.saveUserConfig("language", "pl");
        assertThat(peer.readUserConfig("language")).contains("pl");
        config.saveUserConfig("theme", "dark");
        peer.clearUserConfig("language");
        assertThat(config.readUserConfig("language")).isEmpty();
        assertThat(config.readUserConfig()).doesNotContainKey("language");
        assertThat(peer.readUserConfig("theme")).contains("dark");
        assertThat(new UserConfigServiceImpl(
                                file.getParent(), file.getFileName().toString())
                        .readUserConfig())
                .isEqualTo(Map.of("external", "keep", "theme", "dark"));
        Files.delete(file);
        assertThat(config.readUserConfig("theme")).isEmpty();
        assertThat(config.readUserConfig()).isEmpty();
    }

    @Test
    void rejectsFileNamesOutsideHomeAndExistingDirectoriesAsFiles() throws IOException {
        Path existingFile = Files.createFile(directory.resolve("regular-file"));
        Path existingDirectory = Files.createDirectory(directory.resolve("settings"));

        for (String fileName : List.of(
                "",
                ".",
                "..",
                "nested/config.properties",
                "..\\config.properties",
                existingFile.toString(),
                existingDirectory.getFileName().toString())) {
            assertThatIllegalArgumentException().isThrownBy(() -> new UserConfigServiceImpl(directory, fileName));
        }
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new UserConfigServiceImpl(existingFile.resolve("child"), "config.properties"));
    }

    @Test
    void failedWritePreservesFileAndSubsequentReadsReportItsCurrentState() throws IOException {
        Path file = Files.writeString(directory.resolve("config.properties"), "language=pl\n");
        var config =
                new UserConfigServiceImpl(file.getParent(), file.getFileName().toString());
        assertThat(config.readUserConfig("language")).contains("pl");
        Files.writeString(file, "bad=\\uZZZZ\n");

        assertThatThrownBy(() -> config.saveUserConfig("language", "en"))
                .isInstanceOf(UserConfigException.class)
                .hasCauseInstanceOf(IOException.class);
        assertThatThrownBy(() -> config.readUserConfig("language")).isInstanceOf(UserConfigException.class);
        assertThat(Files.readString(file)).isEqualTo("bad=\\uZZZZ\n");
        Files.writeString(file, "language=en\n");
        assertThat(config.readUserConfig("language")).contains("en");
    }

    @Test
    void homeFolderReturnsTheConfiguredDirectoryWithoutCreatingIt() {
        Path home = directory.resolve("app-home");
        var config = new UserConfigServiceImpl(home, "config.properties");

        assertThat(config.homeFolder()).isEqualTo(home);
        assertThat(home).doesNotExist();
    }

    @Test
    void utf8RoundTripMergesUnknownKeysAndSupportsRemoval() throws Exception {
        var config = new dev.voxelbrew.util.preferences.UserConfigServiceImpl(
                directory.resolve("settings with spaces"), "config.properties");
        assertThat(config.readUserConfig()).isEqualTo(Map.of());
        assertThat(config.homeFolder()).doesNotExist();
        config.update(Map.of("language", "pl", "plugin.vendor", "Zażółć gęślą jaźń ☕\nnext=line"), Set.of());
        new dev.voxelbrew.util.preferences.UserConfigServiceImpl(config.homeFolder(), "config.properties")
                .saveUserConfig("appearance", "DARK");
        assertThat(config.readUserConfig()).containsEntry("appearance", "DARK");
        assertThat(config.readUserConfig("plugin.vendor").orElse("")).isEqualTo("Zażółć gęślą jaźń ☕\nnext=line");
        assertThatThrownBy(() -> config.readUserConfig().put("key", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
        config.remove("language");
        assertThat(config.readUserConfig("language").orElse("en")).isEqualTo("en");
        assertThat(config.readUserConfig().size()).isEqualTo(2);
    }

    @Test
    void malformedConfigurationAndInvalidEditsPreserveExistingBytes() throws Exception {
        Path file = directory.resolve("config.properties");
        Files.writeString(file, "bad=\\uZZZZ\n");
        byte[] before = Files.readAllBytes(file);
        var config = new dev.voxelbrew.util.preferences.UserConfigServiceImpl(
                file.getParent(), file.getFileName().toString());
        assertThatThrownBy(() -> config.readUserConfig("appearance")).isInstanceOf(UserConfigException.class);
        assertThatThrownBy(config::readUserConfig)
                .isInstanceOf(UserConfigException.class)
                .hasCauseInstanceOf(IOException.class);
        assertThatThrownBy(() -> config.saveUserConfig("appearance", "DARK"))
                .isInstanceOf(UserConfigException.class)
                .hasCauseInstanceOf(IOException.class);
        assertThat(Files.readAllBytes(file)).containsExactly(before);
        Files.writeString(file, "unknown=keep\n");
        assertThatThrownBy(() -> config.saveUserConfig(" ", "value")).isInstanceOf(IllegalArgumentException.class);
        assertThat(Files.readString(file)).isEqualTo("unknown=keep\n");
        assertThat(config.readUserConfig()).containsEntry("unknown", "keep");
        assertThat(config.readUserConfig("unknown")).contains("keep");
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
                    new dev.voxelbrew.util.preferences.UserConfigServiceImpl(
                                    file.getParent(), file.getFileName().toString())
                            .saveUserConfig(key, key);
                    return null;
                }));
            }
            for (var future : futures) future.get(20, TimeUnit.SECONDS);
        }
        var values = new dev.voxelbrew.util.preferences.UserConfigServiceImpl(
                        file.getParent(), file.getFileName().toString())
                .readUserConfig();
        assertThat(values.size()).isEqualTo(40);
        values.forEach((key, value) -> assertThat(value).isEqualTo(key));
    }
}
