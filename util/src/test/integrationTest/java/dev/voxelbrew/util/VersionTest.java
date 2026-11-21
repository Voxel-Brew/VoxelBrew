package dev.voxelbrew.util;

import static org.assertj.core.api.Assertions.*;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class VersionTest {
    @Test
    void ideClassesReturnDevelopmentVersionEvenWithProcessedResources() {
        assertThat(Version.class.getResource("/voxelbrew-util-version.properties"))
                .isNotNull();
        assertThat(Version.version()).isEqualTo("0.0.0");
    }

    @Test
    void builtJarReturnsGradleVersion() throws Exception {
        var artifact = Path.of(System.getProperty("voxelbrew.test.utilJar"));
        try (var loader =
                new URLClassLoader(new URL[] {artifact.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            var version = loader.loadClass("dev.voxelbrew.util.Version");
            var expected = System.getProperty("voxelbrew.test.buildVersion");
            assertThat(version.getMethod("version").invoke(null)).isEqualTo(expected);
            try (var input = loader.getResourceAsStream("META-INF/voxelbrew/application.properties")) {
                assertThat(input).isNotNull();
                var properties = new Properties();
                properties.load(input);
                assertThat(properties.getProperty("version")).isEqualTo(expected);
            }
        }
    }
}
