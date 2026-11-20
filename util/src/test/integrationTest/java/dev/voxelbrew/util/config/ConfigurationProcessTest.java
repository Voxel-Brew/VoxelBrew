package dev.voxelbrew.util.config;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigurationProcessTest {
    @TempDir
    Path directory;

    public static final class Writer {
        public static void main(String[] args) throws Exception {
            var config = new dev.voxelbrew.util.config.FileUserConfiguration(Path.of(args[0]));
            for (int index = 0; index < 20; index++) config.set(args[1] + "." + index, "value." + index);
        }
    }

    @Test
    void independentApplicationsMergeEditsInTheSameConfiguration() throws Exception {
        Path file = directory.resolve("shared settings/config.properties");
        var config = new dev.voxelbrew.util.config.FileUserConfiguration(file);
        config.set("unrelated", "preserved");
        String classpath = Path.of(UserConfiguration.class
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .toURI())
                + java.io.File.pathSeparator
                + Path.of(Writer.class
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .toURI())
                + java.io.File.pathSeparator
                + Path.of(org.slf4j.LoggerFactory.class
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .toURI())
                + java.io.File.pathSeparator
                + Path.of(Class.forName("org.slf4j.jul.JULServiceProvider")
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .toURI());
        var processes = new ArrayList<Process>();
        try {
            for (int index = 0; index < 4; index++) {
                processes.add(new ProcessBuilder(
                                Path.of(System.getProperty("java.home"), "bin", "java")
                                        .toString(),
                                "-cp",
                                classpath,
                                Writer.class.getName(),
                                file.toString(),
                                "process." + index)
                        .redirectErrorStream(true)
                        .redirectOutput(
                                directory.resolve("writer-" + index + ".log").toFile())
                        .start());
            }
            for (int index = 0; index < processes.size(); index++) {
                var process = processes.get(index);
                assertThat(process.waitFor(45, TimeUnit.SECONDS)).isTrue();
                assertThat(process.exitValue())
                        .as(Files.readString(directory.resolve("writer-" + index + ".log")))
                        .isEqualTo(0);
            }
            assertThat(config.read().size()).isEqualTo(81);
            assertThat(config.get("unrelated", "")).isEqualTo("preserved");
            for (int process = 0; process < 4; process++) {
                for (int index = 0; index < 20; index++)
                    assertThat(config.get("process." + process + "." + index, ""))
                            .isEqualTo("value." + index);
            }
        } finally {
            for (var process : processes) if (process.isAlive()) process.destroyForcibly();
        }
    }
}
