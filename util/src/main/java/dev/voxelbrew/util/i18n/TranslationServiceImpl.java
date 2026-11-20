package dev.voxelbrew.util.i18n;

import dev.voxelbrew.api.i18n.TranslationService;
import dev.voxelbrew.api.userconfig.UserConfigException;
import dev.voxelbrew.api.userconfig.UserConfigService;
import dev.voxelbrew.util.services.Services;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.MessageFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

/** Thread-safe translation catalog. Missing languages fall back to English. */
@Slf4j
public final class TranslationServiceImpl implements TranslationService {
    private static final Locale POLISH = Locale.forLanguageTag("pl");

    private final Locale locale;

    private final Map<Locale, Map<String, String>> translations = new ConcurrentHashMap<>();

    private final Map<String, String> languages;

    private record Startup(Locale locale, Path directory, String source) {}

    private static Map<String, Properties> readExternalCatalogs(Path directory) {
        try (var files = Files.list(directory)) {
            return files.filter(path -> Objects.toString(path.getFileName(), "")
                            .matches("messages_[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*\\.properties"))
                    .map(TranslationServiceImpl::readExternalCatalog)
                    .flatMap(Optional::stream)
                    .collect(Collectors.toMap(
                            Map.Entry::getKey, Map.Entry::getValue, (previous, next) -> next, TreeMap::new));
        } catch (NoSuchFileException ignored) {
            return Map.of();
        } catch (IOException failure) {
            log.warn("Cannot list translation catalogs in {}", directory, failure);
            return Map.of();
        }
    }

    private static Optional<Map.Entry<String, Properties>> readExternalCatalog(Path path) {
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            var properties = new Properties();
            properties.load(reader);
            String tag = properties.getProperty("language.i18n", "").strip();
            String name = properties.getProperty("language.name", "").strip();
            String filename = Objects.toString(path.getFileName(), "");
            String fileTag = filename.substring(9, filename.length() - 11);
            if (!name.isEmpty()
                    && tag.equalsIgnoreCase(fileTag)
                    && !Locale.forLanguageTag(tag).getLanguage().isEmpty()) {
                return Optional.of(Map.entry(tag.toLowerCase(Locale.ROOT), properties));
            }
            log.warn("Ignoring translation catalog with invalid language metadata: {}", path);
        } catch (IOException | IllegalArgumentException failure) {
            log.warn("Cannot load translation catalog {}", path, failure);
        }
        return Optional.empty();
    }

    private static Locale selectLocale(Locale locale, Map<String, Properties> catalogs) {
        String requested = locale.toLanguageTag().toLowerCase(Locale.ROOT);
        Properties external = catalogs.get(requested);
        if (external == null && !requested.contains("-"))
            external = catalogs.entrySet().stream()
                    .filter(entry -> entry.getKey().startsWith(requested + "-"))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse(null);
        return external != null
                ? Locale.forLanguageTag(external.getProperty("language.i18n"))
                : locale.getLanguage().equals("pl") ? POLISH : Locale.ENGLISH;
    }

    private static Startup startup(UserConfigService configuration) {
        Path directory = configuration.homeFolder().resolve("i18n");
        try {
            String language =
                    configuration.readUserConfig("language").orElse("system").strip();
            boolean system = language.isEmpty() || language.equalsIgnoreCase("system");
            Locale selected = system
                    ? Locale.getDefault(Locale.Category.DISPLAY)
                    : new Locale.Builder().setLanguageTag(language).build();
            return new Startup(selected, directory, system ? "system display locale" : "saved language preference");
        } catch (UserConfigException | IllformedLocaleException failure) {
            log.warn("Cannot read language preference; using English", failure);
            log.info(
                    "Selected application language en: English fallback for unreadable or invalid language preference");
            return new Startup(Locale.ENGLISH, directory, null);
        }
    }

    public TranslationServiceImpl() {
        this(startup(Services.load(UserConfigService.class)));
    }

    private TranslationServiceImpl(Startup startup) {
        this(startup.locale(), startup.directory());
        logLanguageSelection(startup);
    }

    TranslationServiceImpl(@NonNull Locale locale, Path directory) {
        var catalogs = loadCatalogs(directory);
        languages = registerExternalCatalogs(catalogs);
        this.locale = selectLocale(locale, catalogs);
    }

    private Map<String, Properties> loadCatalogs(Path directory) {
        loadBundledTranslations();
        return readExternalCatalogs(directory);
    }

    private void logLanguageSelection(Startup startup) {
        if (startup.source() == null) return;
        if (startup.locale().getLanguage().equals(currentLocale().getLanguage()))
            log.info(
                    "Selected application language {} from {} ({})",
                    currentLocale().toLanguageTag(),
                    startup.source(),
                    startup.locale().toLanguageTag());
        else
            log.info(
                    "Selected application language en: English fallback for unset or unsupported {} ({})",
                    startup.source(),
                    startup.locale().toLanguageTag());
    }

    private Map<String, String> registerExternalCatalogs(Map<String, Properties> catalogs) {
        var names = new TreeMap<String, String>();
        names.put("en-EN", lookup(Locale.ENGLISH, "language.name"));
        names.put("pl-PL", lookup(POLISH, "language.name"));
        catalogs.forEach((tag, properties) -> registerExternalCatalog(tag, properties, names));
        return Collections.unmodifiableMap(names);
    }

    private void registerExternalCatalog(String tag, Properties properties, Map<String, String> names) {
        names.put(properties.getProperty("language.i18n"), properties.getProperty("language.name"));
        Locale language = Locale.forLanguageTag(tag);
        properties
                .stringPropertyNames()
                .forEach(key -> registerTranslation(language, key, properties.getProperty(key)));
    }

    private void loadBundledTranslations() {
        var loader = TranslationServiceImpl.class.getClassLoader();
        var control = ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES);
        try {
            var indexes = loader.getResources("META-INF/voxelbrew/translations");
            while (indexes.hasMoreElements()) {
                loadBundledIndex(indexes.nextElement(), loader, control);
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot read bundled translation indexes", failure);
        }
    }

    private void loadBundledIndex(URL index, ClassLoader loader, ResourceBundle.Control control) throws IOException {
        try (var reader = new BufferedReader(new InputStreamReader(index.openStream(), StandardCharsets.UTF_8))) {
            reader.lines()
                    .map(String::strip)
                    .filter(catalog -> !catalog.isEmpty() && !catalog.startsWith("#"))
                    .forEachOrdered(catalog -> registerBundledCatalog(catalog, loader, control));
        }
    }

    private void registerBundledCatalog(String catalog, ClassLoader loader, ResourceBundle.Control control) {
        for (Locale language : List.of(defaultLocale(), POLISH)) {
            var bundle = ResourceBundle.getBundle(catalog, language, loader, control);
            bundle.keySet().forEach(key -> registerTranslation(language, key, bundle.getString(key)));
        }
    }

    private String lookup(Locale language, String key) {
        return translations.getOrDefault(language, Map.of()).get(key);
    }

    @Override
    public String registerTranslation(@NonNull Locale locale, @NonNull String key, @NonNull String text) {
        return translations
                .computeIfAbsent(locale, ignored -> new ConcurrentHashMap<>())
                .put(key, text);
    }

    @Override
    public Locale currentLocale() {
        return locale;
    }

    @Override
    public Set<Locale> supportedLocales() {
        return Set.copyOf(translations.keySet());
    }

    @Override
    public void addSupportedLocale(@NonNull Locale locale) {
        translations.computeIfAbsent(locale, ignored -> new ConcurrentHashMap<>());
    }

    private String pattern(String key) {
        String value = lookup(locale, key);
        if (value == null) value = lookup(Locale.forLanguageTag(locale.getLanguage()), key);
        return value != null ? value : lookup(defaultLocale(), key);
    }

    @Override
    public Map<String, String> languages() {
        return Collections.unmodifiableMap(languages);
    }

    @Override
    public Optional<String> translate(String key, Object... arguments) {
        return Optional.ofNullable(pattern(key))
                .map(pattern -> arguments.length == 0 ? pattern : new MessageFormat(pattern, locale).format(arguments));
    }
}
