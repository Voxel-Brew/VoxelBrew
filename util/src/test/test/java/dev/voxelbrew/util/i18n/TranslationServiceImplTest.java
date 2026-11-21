package dev.voxelbrew.util.i18n;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import java.util.logging.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class TranslationServiceImplTest {
    @TempDir
    Path directory;

    private Locale previousDefault, previousDisplay, previousFormat;
    private String previousHome, previousConfig;

    private static void restoreProperty(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }

    private static Properties readCatalog(String language) throws java.io.IOException {
        var properties = new Properties();
        try (var stream = TranslationServiceImpl.class.getResourceAsStream("messages_" + language + ".properties")) {
            properties.load(new java.io.InputStreamReader(
                    Objects.requireNonNull(stream), java.nio.charset.StandardCharsets.UTF_8));
        }
        return properties;
    }

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
                catalogs.resolve("messages_fi-FI.properties"),
                "language.name=Suomi\nlanguage.i18n=fi-FI\naction.save=Tallenna\n");
        var finnish = new TranslationServiceImpl(Locale.forLanguageTag("fi-FI"), catalogs);
        assertThat(finnish.supportedLocales())
                .contains(Locale.ENGLISH, Locale.forLanguageTag("pl"), Locale.forLanguageTag("fi-FI"));
        assertThat(finnish.translate("action.save").orElseThrow()).isEqualTo("Tallenna");
        assertThat(finnish.translate("language.title").orElseThrow()).isEqualTo("Application language");
        assertThat(finnish.languages().get("fi-FI")).isEqualTo("Suomi");
        assertThat(finnish.languages().get("en-EN")).isEqualTo("English");
        assertThat(finnish.languages().get("pl-PL")).isEqualTo("Polski");
    }

    @Test
    void supportedLocalesReturnsAnImmutableSnapshotOfRegisteredLocales() {
        var service = new TranslationServiceImpl(Locale.ENGLISH, directory);
        var snapshot = service.supportedLocales();

        service.registerTranslation(Locale.KOREAN, "test.message", "Example");

        assertThat(service.supportedLocales()).contains(Locale.KOREAN);
        assertThat(snapshot).doesNotContain(Locale.KOREAN);
        assertThatThrownBy(() -> snapshot.add(Locale.KOREAN)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void regionalPreferencesSelectBundledCadCatalogs() {
        var examples = Map.of(
                "fr-CA", "Congé",
                "it-IT", "Raccordo",
                "es-MX", "Redondeo",
                "pt-PT", "Arredondamento",
                "de-AT", "Verrundung",
                "sv-SE", "Avrundning",
                "no-NO", "Avrunding",
                "zh-CN", "圆角",
                "ja-JP", "フィレット");
        examples.forEach((tag, expected) -> {
            var service = new TranslationServiceImpl(Locale.forLanguageTag(tag), directory);
            assertThat(service.translate("action.fillet")).as(tag).contains(expected);
            assertThat(service.languages()).containsKey(service.currentLocale().toLanguageTag());
        });
    }

    @Test
    void addsSupportedLocalesBeforeTheyHaveTranslations() {
        var service = new TranslationServiceImpl(Locale.ENGLISH, directory);

        service.addSupportedLocale(Locale.CANADA_FRENCH);
        assertThat(service.supportedLocales()).contains(Locale.CANADA_FRENCH);
        assertThat(service.currentLocale()).isEqualTo(Locale.ENGLISH);
        assertThat(service.registerTranslation(Locale.CANADA_FRENCH, "test.message", "Example"))
                .isNull();

        service.addSupportedLocale(Locale.CANADA_FRENCH);
        assertThat(service.registerTranslation(Locale.CANADA_FRENCH, "test.message", "Replacement"))
                .isEqualTo("Example");
    }

    @Test
    void listsLocalesRegisteredAfterStartup() {
        var service = new TranslationServiceImpl(Locale.ENGLISH, directory);
        var snapshot = service.languages();
        var testLocale = Locale.forLanguageTag("qaa");

        service.addSupportedLocale(Locale.CANADA_FRENCH);
        service.registerTranslation(testLocale, "language.name", "Test language");

        assertThat(service.languages())
                .containsEntry("fr-CA", Locale.CANADA_FRENCH.getDisplayName(Locale.CANADA_FRENCH))
                .containsEntry("qaa", "Test language");
        assertThat(snapshot).doesNotContainKeys("fr-CA", "qaa");
        assertThatThrownBy(() -> snapshot.put("qaa", "Replacement")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void keepsBothLocalesWhenARegisteredTagMatchesABundledAlias() {
        var service = new TranslationServiceImpl(Locale.ENGLISH, directory);
        var polish = Locale.forLanguageTag("pl-PL");

        service.registerTranslation(polish, "language.name", "Polski (Polska)");

        assertThat(service.languages()).containsEntry("pl", "Polski").containsEntry("pl-PL", "Polski (Polska)");
    }

    @BeforeEach
    void rememberStartupEnvironment() {
        previousHome = System.setProperty("voxelbrew.home", directory.toString());
        previousConfig = System.setProperty(
                "voxelbrew.config", directory.resolve("config.properties").toString());
        previousDefault = Locale.getDefault();
        previousDisplay = Locale.getDefault(Locale.Category.DISPLAY);
        previousFormat = Locale.getDefault(Locale.Category.FORMAT);
    }

    @AfterEach
    void restoreStartupEnvironment() {
        restoreProperty("voxelbrew.home", previousHome);
        restoreProperty("voxelbrew.config", previousConfig);
        Locale.setDefault(previousDefault);
        Locale.setDefault(Locale.Category.DISPLAY, previousDisplay);
        Locale.setDefault(Locale.Category.FORMAT, previousFormat);
    }

    @Test
    void firstLaunchUsesDisplayLanguageWithoutCreatingOrChangingPreferences() throws Exception {
        var config = new dev.voxelbrew.util.preferences.UserConfigServiceImpl(directory, "config.properties");
        Locale.setDefault(Locale.ENGLISH);
        Locale.setDefault(Locale.Category.DISPLAY, Locale.forLanguageTag("pl-PL"));
        assertThat(new TranslationServiceImpl().translate("action.save").orElseThrow())
                .isEqualTo("Zapisz");
        assertThat(config.homeFolder().resolve("config.properties")).doesNotExist();
        assertThat(Locale.getDefault(Locale.Category.FORMAT)).isEqualTo(Locale.ENGLISH);

        config.saveUserConfig("external.setting", "preserve me");
        var before = config.readUserConfig();
        assertThat(new TranslationServiceImpl().translate("action.save").orElseThrow())
                .isEqualTo("Zapisz");
        assertThat(config.readUserConfig()).isEqualTo(before);
        config.saveUserConfig("language", "  ");
        assertThat(new TranslationServiceImpl().translate("action.save").orElseThrow())
                .isEqualTo("Zapisz");

        Locale.setDefault(Locale.Category.DISPLAY, Locale.UK);
        Locale.setDefault(Locale.Category.FORMAT, Locale.forLanguageTag("pl-PL"));
        assertThat(new TranslationServiceImpl().translate("action.save").orElseThrow())
                .isEqualTo("Save");
    }

    @Test
    void missingUnsupportedAndInvalidLanguagesFallBackWhileSavedChoicesOverrideSystem() throws Exception {
        var config = new dev.voxelbrew.util.preferences.UserConfigServiceImpl(directory, "config.properties");
        Locale.setDefault(Locale.forLanguageTag("pl-PL"));
        for (var unsupported : List.of(Locale.ROOT, Locale.forLanguageTag("fi"), Locale.KOREAN)) {
            Locale.setDefault(Locale.Category.DISPLAY, unsupported);
            assertThat(new TranslationServiceImpl().currentLocale()).isEqualTo(Locale.ENGLISH);
        }
        config.saveUserConfig("language", "pl-PL");
        assertThat(new TranslationServiceImpl().translate("action.save").orElseThrow())
                .isEqualTo("Zapisz");
        Locale.setDefault(Locale.Category.DISPLAY, Locale.forLanguageTag("pl-PL"));
        for (var saved : List.of("en", "en-GB", "fi-FI", "not_a_tag")) {
            config.saveUserConfig("language", saved);
            assertThat(new TranslationServiceImpl().currentLocale()).isEqualTo(Locale.ENGLISH);
        }
        config.saveUserConfig("language", " SYSTEM ");
        assertThat(new TranslationServiceImpl().translate("action.save").orElseThrow())
                .isEqualTo("Zapisz");
        var file = config.homeFolder().resolve("config.properties");
        Files.writeString(file, "language=\\uXXXX");
        assertThat(new TranslationServiceImpl().currentLocale()).isEqualTo(Locale.ENGLISH);
        assertThat(Files.readString(file)).isEqualTo("language=\\uXXXX");
    }

    @Test
    void startupLogsSelectionSourceAndEnglishFallbackAtInfoLevel() throws Exception {
        var records = new ArrayList<LogRecord>();
        var logger = Logger.getLogger(TranslationServiceImpl.class.getName());
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
            var config = new dev.voxelbrew.util.preferences.UserConfigServiceImpl(directory, "config.properties");
            Locale.setDefault(Locale.Category.DISPLAY, Locale.forLanguageTag("pl-PL"));
            new TranslationServiceImpl();
            config.saveUserConfig("language", "en");
            new TranslationServiceImpl();
            config.saveUserConfig("language", "system");
            Locale.setDefault(Locale.Category.DISPLAY, Locale.ROOT);
            new TranslationServiceImpl();
            Locale.setDefault(Locale.Category.DISPLAY, Locale.forLanguageTag("fi"));
            new TranslationServiceImpl();
            config.saveUserConfig("language", "not_a_tag");
            new TranslationServiceImpl();
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
            assertThat(messages.get(3).contains("system display locale (fi)")).isTrue();
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
            var polish = new TranslationServiceImpl(Locale.forLanguageTag("pl-PL"), directory);
            assertThat(polish.currentLocale()).isEqualTo(Locale.forLanguageTag("pl"));
            assertThat(polish.translate("action.open").orElseThrow()).isEqualTo("Otwórz…");
            assertThat(polish.translate("update.installed", "1.2.3").orElseThrow())
                    .isEqualTo("Zainstalowano: 1.2.3");
            assertThat(new TranslationServiceImpl(Locale.KOREAN, directory)
                            .translate("action.open")
                            .orElseThrow())
                    .isEqualTo("Open…");
            assertThat(new TranslationServiceImpl(Locale.KOREAN, directory).currentLocale())
                    .isEqualTo(Locale.ENGLISH);
            assertThat(polish.translate("view.accessible", "Góra").orElseThrow())
                    .isEqualTo("Widok: Góra");
            assertThat(polish.translate("external.caption").orElse("Provider-owned caption"))
                    .isEqualTo("Provider-owned caption");
            assertThat(polish.translate("action.open").orElse("ignored")).isEqualTo("Otwórz…");
            assertThat(polish.translate("missing.key")).isEmpty();
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void configurationSelectsLanguageAndEveryCatalogHasTheSameKeysAndValidFormats() throws Exception {
        var config = new dev.voxelbrew.util.preferences.UserConfigServiceImpl(directory, "config.properties");
        config.saveUserConfig("language", "en");
        assertThat(new TranslationServiceImpl().translate("action.save").orElseThrow())
                .isEqualTo("Save");
        config.saveUserConfig("language", "pl");
        assertThat(new TranslationServiceImpl().translate("action.save").orElseThrow())
                .isEqualTo("Zapisz");
        config.saveUserConfig("language", "not_a_tag");
        assertThat(new TranslationServiceImpl().translate("action.save").orElseThrow())
                .isEqualTo("Save");
        var english = new TranslationServiceImpl(Locale.ENGLISH, directory);
        var keys = readCatalog("en");
        for (var locale : english.supportedLocales()) {
            var service = new TranslationServiceImpl(locale, directory);
            var translated = readCatalog(locale.getLanguage());
            assertThat(service.currentLocale()).isEqualTo(locale);
            assertThat(translated.keySet()).as(locale.toString()).isEqualTo(keys.keySet());
            for (String key : keys.stringPropertyNames()) {
                assertThat(service.translate(key, "test").orElseThrow()).as(key).isNotBlank();
                assertThat(placeholders(translated.getProperty(key)))
                        .as("%s %s", locale, key)
                        .isEqualTo(placeholders(keys.getProperty(key)));
            }
        }
    }

    @Test
    void configuredCatalogsAreIndependentAndOnlyNewInstancesReadPreferenceChanges() throws Exception {
        var config = new dev.voxelbrew.util.preferences.UserConfigServiceImpl(directory, "config.properties");
        config.saveUserConfig("language", "en");
        var english = new TranslationServiceImpl();
        config.saveUserConfig("language", "pl-PL");
        var polish = new TranslationServiceImpl();
        assertThat(english.translate("language.label").orElseThrow()).isEqualTo("Language…");
        assertThat(polish.translate("language.label").orElseThrow()).isEqualTo("Język…");
        assertThat(english.translate("formula.result", "1.5", " mm").orElseThrow())
                .isEqualTo("Result: 1.5 mm");
        assertThat(polish.translate("formula.result", "1.5", " mm").orElseThrow())
                .isEqualTo("Wynik: 1.5 mm");
        Locale previous = Locale.getDefault();
        try {
            config.saveUserConfig("language", "system");
            Locale.setDefault(Locale.forLanguageTag("pl-PL"));
            assertThat(new TranslationServiceImpl().translate("action.save").orElseThrow())
                    .isEqualTo("Zapisz");
            Locale.setDefault(Locale.forLanguageTag("fi"));
            assertThat(new TranslationServiceImpl().translate("action.save").orElseThrow())
                    .isEqualTo("Save");
        } finally {
            Locale.setDefault(previous);
        }
    }
}
