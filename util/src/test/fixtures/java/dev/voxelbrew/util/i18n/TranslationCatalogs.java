package dev.voxelbrew.util.i18n;

import dev.voxelbrew.api.i18n.TranslationService;
import java.nio.file.Path;
import java.util.Locale;

/** Creates isolated translation catalogs for frontend tests. */
public final class TranslationCatalogs {
    public static TranslationService forLocale(Locale locale, Path directory) {
        return new TranslationServiceImpl(locale, directory);
    }

    private TranslationCatalogs() {}
}
