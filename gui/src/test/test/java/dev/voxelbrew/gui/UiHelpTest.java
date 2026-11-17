package dev.voxelbrew.gui;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.util.i18n.Translations;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class UiHelpTest {
    @Test
    void finishingAndFeatureNameFieldsHaveLocalizedHelpAtStartup() {
        for (var locale : List.of(Locale.ENGLISH, Locale.forLanguageTag("pl"))) {
            var translations = new Translations(locale);
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
