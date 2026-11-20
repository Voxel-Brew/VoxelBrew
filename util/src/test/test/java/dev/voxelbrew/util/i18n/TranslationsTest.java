package dev.voxelbrew.util.i18n;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import java.util.logging.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class TranslationsTest {
    @TempDir
    Path directory;

    private Locale previousDefault, previousDisplay, previousFormat;

    private static Set<String> placeholders(String pattern) {
        var result = new TreeSet<String>();
        var matcher = java.util.regex.Pattern.compile("\\{(\\d+)(?:[,}])").matcher(pattern);
        while (matcher.find()) result.add(matcher.group(1));
        return result;
    }

    @Test
    void loadsUserCatalogAndFallsBackToEnglishForMissingKeys() throws Exception {
        var catalogs = Files.createDirectory(directory.resolve("i18n"));
        Files.writeString(
                catalogs.resolve("messages_de-DE.properties"),
                "language.name=Deutsch\nlanguage.i18n=de-DE\naction.save=Speichern\n");
        var german = new Translations(Locale.forLanguageTag("de-DE"), catalogs);
        assertThat(german.text("action.save")).isEqualTo("Speichern");
        assertThat(german.text("language.title")).isEqualTo("Application language");
        assertThat(german.languages().get("de-DE")).isEqualTo("Deutsch");
        assertThat(german.languages().get("en-EN")).isEqualTo("English");
        assertThat(german.languages().get("pl-PL")).isEqualTo("Polski");
    }

    @BeforeEach
    void rememberLocales() {
        previousDefault = Locale.getDefault();
        previousDisplay = Locale.getDefault(Locale.Category.DISPLAY);
        previousFormat = Locale.getDefault(Locale.Category.FORMAT);
    }

    @AfterEach
    void restoreLocales() {
        Locale.setDefault(previousDefault);
        Locale.setDefault(Locale.Category.DISPLAY, previousDisplay);
        Locale.setDefault(Locale.Category.FORMAT, previousFormat);
    }

    @Test
    void firstLaunchUsesDisplayLanguageWithoutCreatingOrChangingPreferences() throws Exception {
        var config = new dev.voxelbrew.util.config.FileUserConfiguration(directory.resolve("config.properties"));
        Locale.setDefault(Locale.ENGLISH);
        Locale.setDefault(Locale.Category.DISPLAY, Locale.forLanguageTag("pl-PL"));
        assertThat(Translations.configured(config).text("action.save")).isEqualTo("Zapisz");
        assertThat(Files.exists(config.path())).isFalse();
        assertThat(Locale.getDefault(Locale.Category.FORMAT)).isEqualTo(Locale.ENGLISH);

        config.set("external.setting", "preserve me");
        var before = config.read();
        assertThat(Translations.configured(config).text("action.save")).isEqualTo("Zapisz");
        assertThat(config.read()).isEqualTo(before);
        config.set("language", "  ");
        assertThat(Translations.configured(config).text("action.save")).isEqualTo("Zapisz");

        Locale.setDefault(Locale.Category.DISPLAY, Locale.UK);
        Locale.setDefault(Locale.Category.FORMAT, Locale.forLanguageTag("pl-PL"));
        assertThat(Translations.configured(config).text("action.save")).isEqualTo("Save");
    }

    @Test
    void missingUnsupportedAndInvalidLanguagesFallBackWhileSavedChoicesOverrideSystem() throws Exception {
        var config = new dev.voxelbrew.util.config.FileUserConfiguration(directory.resolve("config.properties"));
        Locale.setDefault(Locale.forLanguageTag("pl-PL"));
        for (var unsupported : List.of(Locale.ROOT, Locale.GERMAN, Locale.JAPANESE)) {
            Locale.setDefault(Locale.Category.DISPLAY, unsupported);
            assertThat(Translations.configured(config).locale()).isEqualTo(Locale.ENGLISH);
        }
        config.set("language", "pl-PL");
        assertThat(Translations.configured(config).text("action.save")).isEqualTo("Zapisz");
        Locale.setDefault(Locale.Category.DISPLAY, Locale.forLanguageTag("pl-PL"));
        for (var saved : List.of("en", "en-GB", "de-DE", "not_a_tag")) {
            config.set("language", saved);
            assertThat(Translations.configured(config).locale()).isEqualTo(Locale.ENGLISH);
        }
        config.set("language", " SYSTEM ");
        assertThat(Translations.configured(config).text("action.save")).isEqualTo("Zapisz");
        Files.writeString(config.path(), "language=\\uXXXX");
        assertThat(Translations.configured(config).locale()).isEqualTo(Locale.ENGLISH);
        assertThat(Files.readString(config.path())).isEqualTo("language=\\uXXXX");
    }

    @Test
    void startupLogsSelectionSourceAndEnglishFallbackAtInfoLevel() throws Exception {
        var records = new ArrayList<LogRecord>();
        var logger = Logger.getLogger(Translations.class.getName());
        var previousLevel = logger.getLevel();
        boolean previousParents = logger.getUseParentHandlers();
        var capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        logger.setLevel(Level.INFO);
        logger.setUseParentHandlers(false);
        logger.addHandler(capture);
        try {
            var config = new dev.voxelbrew.util.config.FileUserConfiguration(directory.resolve("config.properties"));
            Locale.setDefault(Locale.Category.DISPLAY, Locale.forLanguageTag("pl-PL"));
            Translations.configured(config);
            config.set("language", "en");
            Translations.configured(config);
            config.set("language", "system");
            Locale.setDefault(Locale.Category.DISPLAY, Locale.ROOT);
            Translations.configured(config);
            Locale.setDefault(Locale.Category.DISPLAY, Locale.GERMAN);
            Translations.configured(config);
            config.set("language", "not_a_tag");
            Translations.configured(config);
            var messages = records.stream()
                    .filter(r -> r.getLevel() == Level.INFO)
                    .map(LogRecord::getMessage)
                    .toList();
            assertThat(messages.size()).isEqualTo(5);
            assertThat(messages.get(0).contains("language pl from system display locale (pl-PL)"))
                    .isTrue();
            assertThat(messages.get(1).contains("language en from saved language preference (en)"))
                    .isTrue();
            assertThat(messages.get(2).contains("English fallback")).isTrue();
            assertThat(messages.get(2).contains("system display locale (und)")).isTrue();
            assertThat(messages.get(3).contains("system display locale (de)")).isTrue();
            assertThat(messages.get(4).contains("English fallback for unreadable or invalid"))
                    .isTrue();
        } finally {
            logger.removeHandler(capture);
            logger.setLevel(previousLevel);
            logger.setUseParentHandlers(previousParents);
        }
    }

    @Test
    void catalogUsesUtf8AndFallsBackToEnglishWithoutHostLocaleLeakage() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("pl-PL"));
            var polish = new Translations(Locale.forLanguageTag("pl-PL"));
            assertThat(polish.bundle().getLocale()).isEqualTo(polish.locale());
            assertThat(polish.text("action.open")).isEqualTo("Otwórz…");
            assertThat(polish.text("update.installed", "1.2.3")).isEqualTo("Zainstalowano: 1.2.3");
            assertThat(new Translations(Locale.JAPANESE).text("action.open")).isEqualTo("Open…");
            assertThat(new Translations(Locale.JAPANESE).locale()).isEqualTo(Locale.ENGLISH);
            assertThat(polish.text("view.accessible", "Góra")).isEqualTo("Widok: Góra");
            assertThat(polish.textOrDefault("external.caption", "Provider-owned caption"))
                    .isEqualTo("Provider-owned caption");
            assertThat(polish.textOrDefault("action.open", "ignored")).isEqualTo("Otwórz…");
            assertThatThrownBy(() -> polish.text("missing.key")).isInstanceOf(MissingResourceException.class);
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void configurationSelectsLanguageAndEveryCatalogHasTheSameKeysAndValidFormats() throws Exception {
        var config = new dev.voxelbrew.util.config.FileUserConfiguration(directory.resolve("config.properties"));
        config.set("language", "en");
        assertThat(Translations.configured(config).text("action.save")).isEqualTo("Save");
        config.set("language", "pl");
        assertThat(Translations.configured(config).text("action.save")).isEqualTo("Zapisz");
        config.set("language", "not_a_tag");
        assertThat(Translations.configured(config).text("action.save")).isEqualTo("Save");
        var english = new Translations(Locale.ENGLISH);
        var polish = new Translations(Locale.forLanguageTag("pl"));
        // Read the files directly so ResourceBundle's inherited keys cannot hide an omission.
        var keys = new Properties();
        var translated = new Properties();
        try (var base = Translations.class.getResourceAsStream("messages_en.properties");
                var local = Translations.class.getResourceAsStream("messages_pl.properties")) {
            keys.load(new java.io.InputStreamReader(
                    Objects.requireNonNull(base), java.nio.charset.StandardCharsets.UTF_8));
            translated.load(new java.io.InputStreamReader(
                    Objects.requireNonNull(local), java.nio.charset.StandardCharsets.UTF_8));
        }
        assertThat(translated.keySet()).isEqualTo(keys.keySet());
        for (String key : keys.stringPropertyNames()) {
            assertThat(english.text(key, "test").isBlank()).isFalse();
            assertThat(polish.text(key, "test").isBlank()).isFalse();
            assertThat(placeholders(translated.getProperty(key)))
                    .as(key)
                    .isEqualTo(placeholders(keys.getProperty(key)));
        }
    }

    @Test
    void configuredCatalogsAreIndependentAndOnlyNewInstancesReadPreferenceChanges() throws Exception {
        var config = new dev.voxelbrew.util.config.FileUserConfiguration(directory.resolve("config.properties"));
        config.set("language", "en");
        var english = Translations.configured(config);
        config.set("language", "pl-PL");
        var polish = Translations.configured(config);
        assertThat(english.text("language.label")).isEqualTo("Language…");
        assertThat(polish.text("language.label")).isEqualTo("Język…");
        assertThat(english.text("formula.result", "1.5", " mm")).isEqualTo("Result: 1.5 mm");
        assertThat(polish.text("formula.result", "1.5", " mm")).isEqualTo("Wynik: 1.5 mm");
        Locale previous = Locale.getDefault();
        try {
            config.set("language", "system");
            Locale.setDefault(Locale.forLanguageTag("pl-PL"));
            assertThat(Translations.configured(config).text("action.save")).isEqualTo("Zapisz");
            Locale.setDefault(Locale.GERMAN);
            assertThat(Translations.configured(config).text("action.save")).isEqualTo("Save");
        } finally {
            Locale.setDefault(previous);
        }
    }
}
