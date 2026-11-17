package dev.voxelbrew.util.logging;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.util.config.UserConfiguration;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;
import java.util.Set;
import java.util.logging.*;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Slf4j
class LoggingTest {
    @TempDir
    Path directory;

    @Test
    void configurationWritesUtf8RotatingLogsWithoutChangingHostHandlers() throws Exception {
        var config = new UserConfiguration(directory.resolve("config.properties"));
        config.update(
                Map.of(
                        "logging.level",
                        "FINE",
                        "logging.console.level",
                        "OFF",
                        "logging.file.limit",
                        "1024",
                        "logging.file.count",
                        "2"),
                Set.of());
        Handler[] rootHandlers = Logger.getLogger("").getHandlers();
        Path logs = directory.resolve("logs with % spaces");
        try {
            Logging.configure(config, logs, "cli");
            for (int i = 0; i < 100; i++) log.debug("entry {} {}", i, " ".repeat(20));
            log.info("Zażółć ☕ final marker");
        } finally {
            Logging.close();
        }
        assertThat(Logger.getLogger("").getHandlers()).containsExactly(rootHandlers);
        try (var files = Files.list(logs)) {
            var paths = files.filter(path -> path.toString().endsWith(".log")).toList();
            assertThat(paths.size()).isEqualTo(3);
            boolean found = false;
            for (Path path : paths) found |= Files.readString(path).contains("Zażółć ☕ final marker");
            assertThat(found).isTrue();
        }
    }

    @Test
    void slf4jRespectsConfiguredLevelsAndRoutesExceptionsOnlyToStderrAndFiles() throws Exception {
        var config = new UserConfiguration(directory.resolve("config.properties"));
        config.update(Map.of("logging.level", "DEBUG", "logging.console.level", "WARN"), Set.of());
        Path logs = directory.resolve("logs");
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        PrintStream originalOut = System.out, originalErr = System.err;
        Handler[] rootHandlers = Logger.getLogger("").getHandlers();
        try (var out = new PrintStream(stdout, true, StandardCharsets.UTF_8);
                var err = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
            System.setOut(out);
            System.setErr(err);
            try {
                Logging.configure(config, logs, "gui");
                assertThat(log.isDebugEnabled()).isTrue();
                assertThat(log.isTraceEnabled()).isFalse();
                log.trace("hidden trace");
                log.debug("revision {}", 42);
                log.warn("Cannot read {}", "plate.vxb", new IOException("file unavailable"));
                log.warn("Console UTF-8: Zażółć ☕");
            } finally {
                Logging.close();
                System.setOut(originalOut);
                System.setErr(originalErr);
            }
        }
        assertThat(stdout.toString(StandardCharsets.UTF_8)).isEqualTo("");
        String console = stderr.toString(StandardCharsets.UTF_8);
        assertThat(console.contains("revision")).isFalse();
        assertThat(console.contains("hidden trace")).isFalse();
        assertThat(console.split("Cannot read plate.vxb", -1).length - 1).isEqualTo(1);
        assertThat(console.contains("java.io.IOException: file unavailable")).isTrue();
        assertThat(console.contains("Console UTF-8: Zażółć ☕")).isTrue();
        assertThat(Logger.getLogger("").getHandlers()).containsExactly(rootHandlers);
        try (var files = Files.list(logs)) {
            var paths = files.filter(path -> path.toString().endsWith(".log")).toList();
            assertThat(paths.size()).isEqualTo(1);
            String content = Files.readString(paths.getFirst());
            assertThat(content.contains("revision 42")).isTrue();
            assertThat(content.contains("hidden trace")).isFalse();
            assertThat(content.split("Cannot read plate.vxb", -1).length - 1).isEqualTo(1);
            assertThat(content.contains("java.io.IOException: file unavailable"))
                    .isTrue();
        }
    }

    @Test
    void rejectsInvalidConfigurationBeforeOpeningLogFiles() throws Exception {
        var config = new UserConfiguration(directory.resolve("config.properties"));
        config.set("logging.file.count", "0");
        assertThatThrownBy(() -> Logging.configure(config, directory.resolve("logs"), "cli"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(Files.exists(directory.resolve("logs"))).isFalse();
    }

    @Test
    void restartArchivesThePreviousSessionAndUsesTheExactActiveFilename() throws Exception {
        var config = new UserConfiguration(directory.resolve("config.properties"));
        config.set("logging.console.level", "OFF");
        Path logs = directory.resolve("log");
        try {
            Logging.configure(config, logs, "gui");
            log.info("first-session-marker");
            Logging.close();
            Logging.configure(config, logs, "cli");
            log.info("second-session-marker");
        } finally {
            Logging.close();
        }
        String active = Files.readString(logs.resolve("app.log"));
        assertThat(active.contains("second-session-marker")).isTrue();
        assertThat(active.contains("INFO [cli:")).isTrue();
        assertThat(active.contains("first-session-marker")).isFalse();
        try (var files = Files.list(logs)) {
            var archives = files.filter(p -> p.getFileName().toString().startsWith("app-"))
                    .toList();
            assertThat(archives.size()).isEqualTo(1);
            assertThat(Files.readString(archives.getFirst()).contains("first-session-marker"))
                    .isTrue();
        }
    }

    @Test
    void defaultsRotateAtTwentyMebibytesWithoutSplittingRecords() throws Exception {
        var config = new UserConfiguration(directory.resolve("config.properties"));
        config.set("logging.console.level", "OFF");
        Path logs = directory.resolve("log");
        String payload = ".".repeat(1024 * 1024);
        try {
            Logging.configure(config, logs, "gui");
            for (int i = 0; i < 21; i++) log.info("record-{} {}", i, payload);
        } finally {
            Logging.close();
        }
        try (var files = Files.list(logs)) {
            var paths = files.filter(p -> p.toString().endsWith(".log")).toList();
            assertThat(paths.size()).isEqualTo(2);
            for (var path : paths)
                assertThat(Files.size(path) <= 20L * 1024 * 1024).isTrue();
            long archived = paths.stream()
                    .filter(p -> !p.getFileName().toString().equals("app.log"))
                    .mapToLong(p -> p.toFile().length())
                    .sum();
            assertThat(archived >= 19L * 1024 * 1024).isTrue();
            String active = Files.readString(logs.resolve("app.log"));
            assertThat(active.contains("record-20 ")).isTrue();
            assertThat(active.contains("record-0 ")).isFalse();
        }
    }

    @Test
    void consoleCanBeMoreVerboseThanFileAndUnrelatedArchivesArePreserved() throws Exception {
        var config = new UserConfiguration(directory.resolve("config.properties"));
        config.update(
                Map.of("logging.level", "ERROR", "logging.console.level", "DEBUG", "logging.file.count", "1"),
                Set.of());
        Path logs = Files.createDirectory(directory.resolve("log"));
        Path unrelated = Files.writeString(logs.resolve("app-personal.log"), "keep");
        var stderr = new ByteArrayOutputStream();
        PrintStream original = System.err;
        try (var capture = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
            System.setErr(capture);
            try {
                for (int i = 0; i < 3; i++) {
                    Logging.configure(config, logs, "cli");
                    log.debug("console-only-marker");
                    log.error("error-marker");
                    Logging.close();
                }
            } finally {
                Logging.close();
                System.setErr(original);
            }
        }
        assertThat(stderr.toString(StandardCharsets.UTF_8).contains("console-only-marker"))
                .isTrue();
        assertThat(Files.readString(logs.resolve("app.log")).contains("console-only-marker"))
                .isFalse();
        assertThat(Files.readString(logs.resolve("app.log")).contains("ERROR")).isTrue();
        assertThat(Files.readString(unrelated)).isEqualTo("keep");
        try (var files = Files.list(logs)) {
            assertThat(files.filter(p -> p.getFileName().toString().matches("app-\\d.*\\.log"))
                            .count())
                    .isEqualTo(1);
        }
    }
}
