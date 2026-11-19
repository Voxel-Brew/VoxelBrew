package dev.voxelbrew.gui;

import dev.voxelbrew.api.slicer.*;
import dev.voxelbrew.slicer.SlicerInputs;
import dev.voxelbrew.util.config.UserConfiguration;
import dev.voxelbrew.util.i18n.Translations;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javafx.scene.control.Alert;
import javafx.stage.Window;
import lombok.RequiredArgsConstructor;

/** Edits saved provider settings and supplies a snapshot to slicing operations. */
@RequiredArgsConstructor
final class SlicerSettings {
    private final UserConfiguration configuration;

    void configure(SlicerProvider provider, Window owner, Translations translations) {
        var request = provider.settingsDialog();
        if (request.isEmpty()) {
            Dialogs.show(
                    new Alert(Alert.AlertType.INFORMATION, translations.text("slicer.settings.noOptions")),
                    owner,
                    translations);
            return;
        }
        var dialog = request.get();
        try {
            var saved = configuration.section("slicer", provider.descriptor().id());
            new DesktopExportWindow(owner, null, translations)
                    .interaction(translations.text("action.save"), "slicer-input-")
                    .show(
                            dialog.title(),
                            dialog.layout(),
                            dialog::validate,
                            values -> {
                                var settings = SlicerInputs.build(dialog, values);
                                try {
                                    saved.update(values, Set.of());
                                } catch (IOException failure) {
                                    throw new UncheckedIOException(failure);
                                }
                                return settings;
                            },
                            saved.read(),
                            false);
        } catch (IOException failure) {
            throw new UncheckedIOException(translations.text("slicer.settings.failed", failure.getMessage()), failure);
        }
    }

    SlicerWindow window(String providerId, Translations translations) {
        Map<String, String> saved;
        try {
            saved = configuration.section("slicer", providerId).read();
        } catch (IOException failure) {
            throw new UncheckedIOException(translations.text("slicer.settings.failed", failure.getMessage()), failure);
        }
        return new SlicerWindow() {
            @Override
            public <T> Optional<T> show(SlicerDialog<T> dialog) {
                try {
                    var values = SlicerInputs.resolve(dialog.layout(), saved, Map.of());
                    return Optional.of(SlicerInputs.build(dialog, values));
                } catch (IllegalArgumentException invalid) {
                    throw new IllegalArgumentException(
                            translations.text("slicer.settings.required", dialog.title(), invalid.getMessage()),
                            invalid);
                }
            }
        };
    }
}
