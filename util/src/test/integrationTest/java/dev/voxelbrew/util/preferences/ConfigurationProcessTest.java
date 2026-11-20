package dev.voxelbrew.util.preferences;

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
            Path file = Path.of(args[0]);
            var config = new UserConfigServiceImpl(
                    file.getParent(), file.getFileName().toString());
            for (int index = 0; index < 20; index++) config.saveUserConfig(args[1] + "." + index, "value." + index);
        }
    }

    @Test
    void independentApplicationsMergeEditsInTheSameConfiguration() throws Exception {
        Path file = directory.resolve("shared settings/config.properties");
        var config = new dev.voxelbrew.util.preferences.UserConfigServiceImpl(
                file.getParent(), file.getFileName().toString());
        config.saveUserConfig("unrelated", "preserved");
        String classpath = Path.of(UserConfigServiceImpl.class
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .toURI())
                + java.io.File.pathSeparator
                + Path.of(dev.voxelbrew.api.userconfig.UserConfigService.class
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
            assertThat(config.readUserConfig().size()).isEqualTo(81);
            assertThat(config.readUserConfig("unrelated").orElse("")).isEqualTo("preserved");
            for (int process = 0; process < 4; process++) {
                for (int index = 0; index < 20; index++) {
                    assertThat(config.readUserConfig("process." + process + "." + index)
                                    .orElse(""))
                            .isEqualTo("value." + index);
                }
            }
        } finally {
            for (var process : processes) {
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
            }
        }
    }
}
