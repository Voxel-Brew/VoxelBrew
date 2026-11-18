package dev.voxelbrew.util.services;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;

class UtilityServiceJarTest {
    @Test
    void packagedUtilityJarRegistersAllApplicationServices() throws Exception {
        var providers = Map.of(
                "dev.voxelbrew.util.config.ApplicationPaths", "dev.voxelbrew.util.config.SystemApplicationPaths",
                "dev.voxelbrew.util.config.UserConfiguration", "dev.voxelbrew.util.config.FileUserConfiguration",
                "dev.voxelbrew.util.i18n.Translations", "dev.voxelbrew.util.i18n.Translations",
                "dev.voxelbrew.util.update.ReleaseSource", "dev.voxelbrew.util.update.GitHubReleaseSource");
        try (var jar = new JarFile(System.getProperty("voxelbrew.test.utilJar"))) {
            for (var provider : providers.entrySet()) {
                var descriptor = jar.getJarEntry("META-INF/services/" + provider.getKey());
                assertThat(descriptor)
                        .as("Service registration for %s", provider.getKey())
                        .isNotNull();
                try (var input = jar.getInputStream(descriptor)) {
                    assertThat(new String(input.readAllBytes(), StandardCharsets.UTF_8).strip())
                            .isEqualTo(provider.getValue());
                }
                assertThat(jar.getJarEntry(provider.getValue().replace('.', '/') + ".class"))
                        .isNotNull();
            }
        }
    }
}
