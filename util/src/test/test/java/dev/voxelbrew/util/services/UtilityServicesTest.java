package dev.voxelbrew.util.services;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.i18n.TranslationService;
import dev.voxelbrew.api.multithread.MultithreadService;
import dev.voxelbrew.api.userconfig.UserConfigService;
import dev.voxelbrew.util.i18n.TranslationCatalogs;
import dev.voxelbrew.util.i18n.TranslationServiceImpl;
import dev.voxelbrew.util.preferences.UserConfigServiceImpl;
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
        else {
            System.setProperty(key, previous);
        }
    }

    @Test
    void defaultServicesAreDiscoverableWithoutCreatingFilesOrFetchingReleases() {
        assertThat(Services.load(UserConfigService.class)).isInstanceOf(UserConfigServiceImpl.class);
        assertThat(Services.load(TranslationService.class)).isInstanceOf(TranslationServiceImpl.class);
        var executor = Services.load(MultithreadService.class).commonExecutorService();
        assertThat(Services.load(MultithreadService.class).commonExecutorService())
                .isSameAs(executor);
        assertThat(Services.load(ReleaseSource.class)).isInstanceOf(GitHubReleaseSource.class);
    }

    @Test
    void explicitPathsKeepConfigurationAndTranslationsIsolated() throws Exception {
        var english = new UserConfigServiceImpl(directory.resolve("english"), "en.properties");
        var polish = new UserConfigServiceImpl(directory.resolve("polish"), "pl.properties");
        assertThat(english.homeFolder().resolve("en.properties")).doesNotExist();
        english.saveUserConfig("language", "en");
        polish.saveUserConfig("language", "pl");
        assertThat(TranslationCatalogs.forLocale(
                                Locale.ENGLISH, english.homeFolder().resolve("i18n"))
                        .currentLocale())
                .isEqualTo(Locale.ENGLISH);
        assertThat(TranslationCatalogs.forLocale(
                                Locale.forLanguageTag("pl"), polish.homeFolder().resolve("i18n"))
                        .translate("action.save")
                        .orElseThrow())
                .isEqualTo("Zapisz");
        assertThat(english.readUserConfig()).containsEntry("language", "en");
        assertThat(english.pluginsFolder()).isEqualTo(english.homeFolder().resolve("plugins"));
        assertThat(polish.printersFolder()).isEqualTo(polish.homeFolder().resolve("printers"));
        assertThat(polish.logsFolder()).isEqualTo(polish.homeFolder().resolve("log"));
    }

    @Test
    void systemOverridesAreCapturedPerInstanceAndNewInstancesSeeChanges() {
        var previousHome = System.getProperty("voxelbrew.home");
        var previousConfig = System.getProperty("voxelbrew.config");
        try {
            System.setProperty("voxelbrew.home", directory.resolve("first").toString());
            System.clearProperty("voxelbrew.config");
            var first = Services.load(UserConfigService.class);
            System.setProperty("voxelbrew.home", directory.resolve("second").toString());
            System.setProperty("voxelbrew.config", "settings.properties");
            var second = Services.load(UserConfigService.class);
            assertThat(first.homeFolder()).isEqualTo(directory.resolve("first"));
            assertThat(second.homeFolder()).isEqualTo(directory.resolve("second"));
            assertThat(second.homeFolder()).doesNotExist();
            first.saveUserConfig("language", "en");
            second.saveUserConfig("language", "pl");
            assertThat(first.homeFolder().resolve("config.properties")).isRegularFile();
            assertThat(second.homeFolder().resolve("settings.properties")).isRegularFile();
            System.setProperty(
                    "voxelbrew.config", directory.resolve("outside.properties").toString());
            assertThatIllegalArgumentException().isThrownBy(UserConfigServiceImpl::new);
        } finally {
            restore("voxelbrew.home", previousHome);
            restore("voxelbrew.config", previousConfig);
        }
    }

    @Test
    void publicConfigContractMergesPreferencesAndRemovesIndividualKeys() {
        UserConfigService config = new UserConfigServiceImpl(directory, "config.properties");
        config.saveUserConfig("language", "en");
        config.saveUserConfig(java.util.Map.of("theme", "dark"));
        assertThat(config.readUserConfig()).containsEntry("language", "en").containsEntry("theme", "dark");
        config.clearUserConfig("language");
        assertThat(config.readUserConfig("language")).isEmpty();
        assertThat(config.readUserConfig("theme")).contains("dark");
    }

    @Test
    void translationProviderLoadsApiCatalogsAndAcceptsNewRegistrations() {
        var polish = TranslationCatalogs.forLocale(Locale.forLanguageTag("pl"), directory);
        assertThat(polish.translate("api.export.destination.choose")).contains("Wybierz miejsce docelowe");
        assertThat(polish.translate("api.import.source.choose")).contains("Wybierz plik źródłowy");
        assertThat(polish.translate("test.translation")).isEmpty();
        assertThat(polish.registerTranslation(Locale.ENGLISH, "test.translation", "Fallback {0}"))
                .isNull();
        assertThat(polish.translate("test.translation", "message")).contains("Fallback message");
        assertThat(polish.registerTranslation(Locale.ENGLISH, "test.translation", "Replacement"))
                .isEqualTo("Fallback {0}");
    }

    @Test
    void serviceLoaderInstancesOwnTheirRegistrations() {
        var service = Services.load(TranslationService.class);
        service.registerTranslation(service.currentLocale(), "test.local-catalog", "Registered by a provider");

        assertThat(service.translate("test.local-catalog")).contains("Registered by a provider");
        var other = Services.load(TranslationService.class);
        assertThat(other.translate("action.save"))
                .hasValueSatisfying(value -> assertThat(value).isNotBlank());
        assertThat(other.translate("test.local-catalog")).isEmpty();
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
