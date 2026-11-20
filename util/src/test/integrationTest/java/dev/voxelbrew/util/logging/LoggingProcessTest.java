package dev.voxelbrew.util.logging;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class LoggingProcessTest {
    @TempDir
    Path directory;

    @lombok.extern.slf4j.Slf4j
    public static final class Writer {
        public static void main(String[] args) throws Exception {
            Logging.initialize(args[0]);
            Logging.initialize(args[0]);
            try {
                Files.writeString(
                        dev.voxelbrew.util.services.Services.load(dev.voxelbrew.util.config.ApplicationPaths.class)
                                .home()
                                .resolve(args[0] + ".ready"),
                        "ready");
                if (System.in.read() < 0) throw new IllegalStateException("Missing start signal");
                for (int i = 0; i < 100; i++) log.debug("event-{}-{}; Zażółć ☕", args[0], i);
                var failed = Thread.ofPlatform().start(() -> {
                    throw new IllegalStateException("uncaught-worker-" + args[0] + " Zażółć ☕");
                });
                failed.join();
            } finally {
                Logging.close();
            }
        }
    }

    @Test
    @Timeout(60)
    void liveApplicationsShareTheActiveFileAcrossStartupAndSizeRotation() throws Exception {
        Path home = directory.resolve("user home with % spaces");
        new dev.voxelbrew.util.config.FileUserConfiguration(home.resolve("config.properties"))
                .update(
                        Map.of(
                                "logging.level",
                                "DEBUG",
                                "logging.console.level",
                                "OFF",
                                "logging.file.limit",
                                "4096",
                                "logging.file.count",
                                "20"),
                        Set.of());
        var processes = new ArrayList<Process>();
        try {
            processes.add(start(home, "gui"));
            awaitReady(home, "gui", processes.getFirst());
            assertThat(Files.exists(home.resolve("log/app.log"))).isTrue();
            try (var files = Files.list(home.resolve("log"))) {
                assertThat(files.filter(p -> p.getFileName().toString().startsWith("app-"))
                                .count())
                        .as("initialize twice must not rotate twice")
                        .isEqualTo(0);
            }
            processes.add(start(home, "cli"));
            awaitReady(home, "cli", processes.getLast());
            try (var files = Files.list(home.resolve("log"))) {
                assertThat(files.filter(p -> p.getFileName().toString().startsWith("app-"))
                                .count())
                        .isEqualTo(1);
            }
            for (var process : processes) {
                process.getOutputStream().write('\n');
                process.getOutputStream().flush();
            }
            for (var process : processes) {
                assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
                assertThat(process.exitValue()).isEqualTo(0);
            }
            var content = new StringBuilder();
            try (var files = Files.list(home.resolve("log"))) {
                var logs = files.filter(p -> p.toString().endsWith(".log")).toList();
                assertThat(logs.size() > 2)
                        .as("Concurrent writes must exercise size rotation")
                        .isTrue();
                for (var path : logs) {
                    assertThat(Files.size(path) <= 4096).isTrue();
                    content.append(Files.readString(path));
                }
            }
            String text = content.toString();
            for (String component : List.of("gui", "cli")) {
                assertThat(Files.readString(directory.resolve(component + ".out")))
                        .isEqualTo("");
                assertThat(Files.readString(directory.resolve(component + ".err")))
                        .isEqualTo("");
                assertThat(text.contains("[" + component + ":")).isTrue();
                assertThat(text.contains("uncaught-worker-" + component)).isTrue();
                for (int i = 0; i < 100; i++) {
                    String marker = "event-" + component + "-" + i + ";";
                    assertThat(text.split(java.util.regex.Pattern.quote(marker), -1).length - 1)
                            .as(marker)
                            .isEqualTo(1);
                }
            }
        } finally {
            for (var process : processes) {
                if (process.isAlive()) process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    @Timeout(45)
    void unavailableLogDirectoryFallsBackToConsoleWithoutPreventingStartup() throws Exception {
        Path home = Files.createDirectory(directory.resolve("blocked home"));
        Files.writeString(home.resolve("log"), "existing regular file");
        var process = start(home, "cli");
        try {
            awaitReady(home, "cli", process);
            process.getOutputStream().write('\n');
            process.getOutputStream().flush();
            assertThat(process.waitFor(20, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).isEqualTo(0);
            assertThat(Files.readString(directory.resolve("cli.out"))).isEqualTo("");
            String errors = Files.readString(directory.resolve("cli.err"));
            assertThat(errors.contains("Cannot configure file logging")).isTrue();
            assertThat(errors.contains("uncaught-worker-cli Zażółć ☕")).isTrue();
            assertThat(errors.contains("Starting cli version")).isFalse();
            assertThat(Files.readString(home.resolve("log"))).isEqualTo("existing regular file");
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
    }

    private Process start(Path home, String component) throws Exception {
        var locations = new ArrayList<String>();
        for (var type : List.of(
                Logging.class,
                Writer.class,
                org.slf4j.LoggerFactory.class,
                Class.forName("org.slf4j.jul.JULServiceProvider"))) {
            locations.add(Path.of(type.getProtectionDomain()
                            .getCodeSource()
                            .getLocation()
                            .toURI())
                    .toString());
        }
        return new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-Dvoxelbrew.home=" + home,
                        "-cp",
                        String.join(java.io.File.pathSeparator, locations),
                        Writer.class.getName(),
                        component)
                .redirectOutput(directory.resolve(component + ".out").toFile())
                .redirectError(directory.resolve(component + ".err").toFile())
                .start();
    }

    private void awaitReady(Path home, String component, Process process) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!Files.exists(home.resolve(component + ".ready"))) {
            assertThat(process.isAlive())
                    .as(Files.readString(directory.resolve(component + ".err")))
                    .isTrue();
            assertThat(System.nanoTime() < deadline)
                    .as("Logging process did not initialize")
                    .isTrue();
            Thread.sleep(20);
        }
    }
}
