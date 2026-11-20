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
                "dev.voxelbrew.api.userconfig.UserConfigService",
                "dev.voxelbrew.util.preferences.UserConfigServiceImpl",
                "dev.voxelbrew.api.i18n.TranslationService",
                "dev.voxelbrew.util.i18n.TranslationServiceImpl",
                "dev.voxelbrew.api.multithread.MultithreadService",
                "dev.voxelbrew.util.concurrent.MultithreadServiceImpl",
                "dev.voxelbrew.util.update.ReleaseSource",
                "dev.voxelbrew.util.update.GitHubReleaseSource");
        try (var jar = new JarFile(System.getProperty("voxelbrew.test.utilJar"))) {
            assertThat(jar.stream().map(java.util.jar.JarEntry::getName))
                    .noneMatch(name -> name.startsWith("dev/voxelbrew/util/config/")
                            || name.startsWith("META-INF/services/dev.voxelbrew.util.config.")
                            || name.contains("util/i18n/Translations")
                            || name.endsWith("util.i18n.Translations")
                            || name.contains("util/concurrent/CpuWork"));
            assertThat(jar.getJarEntry("META-INF/voxelbrew/translations")).isNotNull();
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
