package dev.voxelbrew.util;

import static org.assertj.core.api.Assertions.*;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
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
            assertThat(version.getMethod("version").invoke(null))
                    .isEqualTo(System.getProperty("voxelbrew.test.buildVersion"));
        }
    }
}
