package dev.voxelbrew.util.services;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.util.config.*;
import dev.voxelbrew.util.i18n.Translations;
import dev.voxelbrew.util.update.*;
import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.Enumeration;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UtilityServicesTest {
    @TempDir
    Path directory;

    public static final class AlternativePaths implements ApplicationPaths {
        public AlternativePaths() {}

        @Override
        public Path home() {
            return Path.of("alternative-service-home").toAbsolutePath();
        }

        @Override
        public Path configuration() {
            return home().resolve("custom.properties");
        }
    }

    public static final class AlternativeReleaseSource implements ReleaseSource {
        public AlternativeReleaseSource() {}

        @Override
        public Release fetch(ReleaseVersion requested, UpdateCancellation cancellation) throws IOException {
            throw new IOException("Alternative release service reached");
        }

        @Override
        public void download(Release release, String asset, Path destination, UpdateCancellation cancellation) {
            throw new AssertionError("No download expected");
        }
    }

    private static void restore(String key, String previous) {
        if (previous == null) System.clearProperty(key);
        else System.setProperty(key, previous);
    }

    @Test
    void defaultServicesAreDiscoverableWithoutCreatingFilesOrFetchingReleases() {
        assertThat(Services.load(ApplicationPaths.class)).isInstanceOf(SystemApplicationPaths.class);
        assertThat(Services.load(UserConfiguration.class)).isInstanceOf(FileUserConfiguration.class);
        assertThat(Services.load(Translations.class)).isNotNull();
        assertThat(Services.load(ReleaseSource.class)).isInstanceOf(GitHubReleaseSource.class);
    }

    @Test
    void explicitPathsKeepConfigurationAndTranslationsIsolated() throws Exception {
        var englishPaths = new SystemApplicationPaths(directory.resolve("english"), directory.resolve("en.properties"));
        var polishPaths = new SystemApplicationPaths(directory.resolve("polish"), directory.resolve("pl.properties"));
        var english = new FileUserConfiguration(englishPaths);
        var polish = new FileUserConfiguration(polishPaths);
        assertThat(Files.exists(english.path())).isFalse();
        english.set("language", "en");
        polish.set("language", "pl");
        assertThat(new Translations(english, englishPaths).locale()).isEqualTo(Locale.ENGLISH);
        assertThat(new Translations(polish, polishPaths).text("action.save")).isEqualTo("Zapisz");
        assertThat(english.read()).containsEntry("language", "en");
        assertThat(englishPaths.plugins()).isEqualTo(englishPaths.home().resolve("plugins"));
        assertThat(polishPaths.printers()).isEqualTo(polishPaths.home().resolve("printers"));
        assertThat(polishPaths.logs()).isEqualTo(polishPaths.home().resolve("log"));
    }

    @Test
    void systemOverridesAreCapturedPerInstanceAndNewInstancesSeeChanges() {
        var previousHome = System.getProperty("voxelbrew.home");
        var previousConfig = System.getProperty("voxelbrew.config");
        try {
            System.setProperty("voxelbrew.home", directory.resolve("first").toString());
            System.clearProperty("voxelbrew.config");
            var first = Services.load(ApplicationPaths.class);
            assertThat(first.configuration()).isEqualTo(first.home().resolve("config.properties"));
            System.setProperty("voxelbrew.home", directory.resolve("second").toString());
            System.setProperty(
                    "voxelbrew.config",
                    directory.resolve("separate/settings.properties").toString());
            var second = Services.load(ApplicationPaths.class);
            assertThat(first.home()).isEqualTo(directory.resolve("first"));
            assertThat(second.home()).isEqualTo(directory.resolve("second"));
            assertThat(second.configuration()).isEqualTo(directory.resolve("separate/settings.properties"));
            assertThat(UserConfiguration.user().path()).isEqualTo(second.configuration());
            assertThat(Files.exists(second.home())).isFalse();
        } finally {
            restore("voxelbrew.home", previousHome);
            restore("voxelbrew.config", previousConfig);
        }
    }

    @Test
    void configurationNoArgumentConstructorResolvesAnAlternativePathsProvider() throws Exception {
        var descriptorName = "META-INF/services/" + ApplicationPaths.class.getName();
        var descriptor = directory.resolve(descriptorName);
        Files.createDirectories(descriptor.getParent());
        Files.writeString(descriptor, AlternativePaths.class.getName());
        var thread = Thread.currentThread();
        var previous = thread.getContextClassLoader();
        try (var loader =
                new URLClassLoader(
                        new java.net.URL[] {directory.toUri().toURL()},
                        getClass().getClassLoader()) {
                    @Override
                    public Enumeration<java.net.URL> getResources(String name) throws IOException {
                        return name.equals(descriptorName) ? findResources(name) : super.getResources(name);
                    }
                }) {
            thread.setContextClassLoader(loader);
            assertThat(Services.load(ApplicationPaths.class)).isInstanceOf(AlternativePaths.class);
            assertThat(Services.load(UserConfiguration.class).path()).isEqualTo(new AlternativePaths().configuration());
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    @Test
    void systemUpdaterUsesTheDiscoveredReleaseTransport() throws Exception {
        var descriptorName = "META-INF/services/" + ReleaseSource.class.getName();
        var descriptor = directory.resolve(descriptorName);
        Files.createDirectories(descriptor.getParent());
        Files.writeString(descriptor, AlternativeReleaseSource.class.getName());
        var thread = Thread.currentThread();
        var previous = thread.getContextClassLoader();
        try (var loader =
                new URLClassLoader(
                        new java.net.URL[] {directory.toUri().toURL()},
                        getClass().getClassLoader()) {
                    @Override
                    public Enumeration<java.net.URL> getResources(String name) throws IOException {
                        return name.equals(descriptorName) ? findResources(name) : super.getResources(name);
                    }
                }) {
            thread.setContextClassLoader(loader);
            var updater = UpdateService.system(ApplicationImage.Component.CLI);
            assertThatThrownBy(() -> updater.check(new UpdateService.Request(null, false), new UpdateCancellation()))
                    .isInstanceOf(IOException.class)
                    .hasMessage("Alternative release service reached");
        } finally {
            thread.setContextClassLoader(previous);
        }
    }
}
