package dev.voxelbrew.gui;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.util.i18n.TranslationCatalogs;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UiHelpTest {
    @TempDir
    Path directory;

    @Test
    void finishingAndFeatureNameFieldsHaveLocalizedHelpAtStartup() {
        for (var locale : List.of(Locale.ENGLISH, Locale.forLanguageTag("pl"))) {
            var translations = TranslationCatalogs.forLocale(locale, directory);
            for (var id : List.of(
                    "fillet",
                    "chamfer",
                    "shell",
                    "feature-rename-field",
                    "parameters-show-builtins",
                    "print-view-2d",
                    "print-view-3d")) {
                assertThat(UiHelp.description(translations, id).isBlank())
                        .as(locale + ": " + id)
                        .isFalse();
            }
        }
    }
}
