package dev.voxelbrew.util.i18n;

import dev.voxelbrew.util.config.ApplicationPaths;
import dev.voxelbrew.util.config.UserConfiguration;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.MessageFormat;
import java.util.*;
import lombok.Getter;
import lombok.NonNull;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

/** Immutable, thread-safe translation catalog. Missing languages fall back to English. */
@Slf4j
public final class Translations {
    @Getter
    @Accessors(fluent = true)
    private final Locale locale;

    private final ResourceBundle messages;
    private final Map<String, String> languages;

    public Translations(@NonNull Locale locale) {
        this(
                locale,
                dev.voxelbrew.util.services.Services.load(ApplicationPaths.class)
                        .home()
                        .resolve("i18n"));
    }

    Translations(@NonNull Locale locale, Path directory) {
        var control = ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES);
        var english = ResourceBundle.getBundle("dev.voxelbrew.util.i18n.messages", Locale.ENGLISH, control);
        var catalogs = new TreeMap<String, Properties>();
        try (var files = Files.list(directory)) {
            files.filter(path -> path.getFileName()
                            .toString()
                            .matches("messages_[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*\\.properties"))
                    .forEach(path -> {
                        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                            var properties = new Properties();
                            properties.load(reader);
                            String tag =
                                    properties.getProperty("language.i18n", "").strip();
                            String name =
                                    properties.getProperty("language.name", "").strip();
                            String fileTag = path.getFileName()
                                    .toString()
                                    .substring(9, path.getFileName().toString().length() - 11);
                            if (!name.isEmpty()
                                    && tag.equalsIgnoreCase(fileTag)
                                    && !Locale.forLanguageTag(tag).getLanguage().isEmpty())
                                catalogs.put(tag.toLowerCase(Locale.ROOT), properties);
                            else log.warn("Ignoring translation catalog with invalid language metadata: {}", path);
                        } catch (IOException failure) {
                            log.warn("Cannot load translation catalog {}", path, failure);
                        }
                    });
        } catch (NoSuchFileException ignored) {
            // The user translation directory is optional.
        } catch (IOException failure) {
            log.warn("Cannot list translation catalogs in {}", directory, failure);
        }
        var names = new TreeMap<String, String>();
        names.put("en-EN", english.getString("language.name"));
        var polish = ResourceBundle.getBundle("dev.voxelbrew.util.i18n.messages", Locale.forLanguageTag("pl"), control);
        names.put("pl-PL", polish.getString("language.name"));
        catalogs.forEach((tag, properties) ->
                names.put(properties.getProperty("language.i18n"), properties.getProperty("language.name")));
        languages = Collections.unmodifiableMap(names);
        String requested = locale.toLanguageTag().toLowerCase(Locale.ROOT);
        Properties external = catalogs.get(requested);
        if (external == null && !requested.contains("-"))
            external = catalogs.entrySet().stream()
                    .filter(entry -> entry.getKey().startsWith(requested + "-"))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse(null);
        this.locale = external != null
                ? Locale.forLanguageTag(external.getProperty("language.i18n"))
                : locale.getLanguage().equals("pl") ? Locale.forLanguageTag("pl") : Locale.ENGLISH;
        var selected = external != null
                ? external
                : ResourceBundle.getBundle("dev.voxelbrew.util.i18n.messages", this.locale, control);
        messages = new ResourceBundle() {
            @Override
            public Locale getLocale() {
                return Translations.this.locale;
            }

            @Override
            protected Object handleGetObject(String key) {
                return selected instanceof Properties properties && properties.containsKey(key)
                        ? properties.getProperty(key)
                        : selected instanceof ResourceBundle bundle && bundle.containsKey(key)
                                ? bundle.getObject(key)
                                : english.getObject(key);
            }

            @Override
            public Enumeration<String> getKeys() {
                var keys = new HashSet<>(english.keySet());
                if (selected instanceof Properties properties) keys.addAll(properties.stringPropertyNames());
                if (selected instanceof ResourceBundle bundle) keys.addAll(bundle.keySet());
                return Collections.enumeration(keys);
            }
        };
    }

    public ResourceBundle bundle() {
        return messages;
    }

    public Map<String, String> languages() {
        return languages;
    }

    public String text(String key, Object... arguments) {
        String pattern = messages.getString(key);
        return arguments.length == 0 ? pattern : new MessageFormat(pattern, locale).format(arguments);
    }

    /** Provider descriptions without a bundled translation retain their original text. */
    public String textOrDefault(String key, String fallback) {
        return messages.containsKey(key) ? text(key) : fallback;
    }

    public Translations() {
        this(
                dev.voxelbrew.util.services.Services.load(UserConfiguration.class),
                dev.voxelbrew.util.services.Services.load(ApplicationPaths.class));
    }

    public Translations(@NonNull UserConfiguration configuration, @NonNull ApplicationPaths paths) {
        this(language(configuration), paths.home().resolve("i18n"));
    }

    private record Language(Locale locale, String source) {}

    private Translations(Language language, Path directory) {
        this(language.locale(), directory);
        if (language.source() == null) return;
        if (language.locale().getLanguage().equals(locale().getLanguage()))
            log.info(
                    "Selected application language {} from {} ({})",
                    locale().toLanguageTag(),
                    language.source(),
                    language.locale().toLanguageTag());
        else
            log.info(
                    "Selected application language en: English fallback for unset or unsupported {} ({})",
                    language.source(),
                    language.locale().toLanguageTag());
    }

    private static Language language(UserConfiguration configuration) {
        try {
            String language = configuration.get("language", "system").strip();
            boolean system = language.isEmpty() || language.equalsIgnoreCase("system");
            Locale selected = system
                    ? Locale.getDefault(Locale.Category.DISPLAY)
                    : new Locale.Builder().setLanguageTag(language).build();
            return new Language(selected, system ? "system display locale" : "saved language preference");
        } catch (IOException | IllformedLocaleException failure) {
            log.warn("Cannot read language preference; using English", failure);
            log.info(
                    "Selected application language en: English fallback for unreadable or invalid language preference");
            return new Language(Locale.ENGLISH, null);
        }
    }

    public static Translations configured(UserConfiguration configuration) {
        return new Translations(configuration, dev.voxelbrew.util.services.Services.load(ApplicationPaths.class));
    }

    private static final class Current {
        static final Translations INSTANCE = dev.voxelbrew.util.services.Services.load(Translations.class);
    }

    /** Reads the language preference once per application process; restart after changing it. */
    public static Translations current() {
        return Current.INSTANCE;
    }
}
