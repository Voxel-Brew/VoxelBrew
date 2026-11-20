package dev.voxelbrew.gui;

import dev.voxelbrew.api.i18n.TranslationService;
import dev.voxelbrew.api.userconfig.UserConfigException;
import dev.voxelbrew.api.userconfig.UserConfigService;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/** Persists display language independently of CAD documents; each window keeps its own catalog. */
final class LanguageDialog {
    static void show(
            Window owner, TranslationService translations, UserConfigService configuration, ExecutorService worker) {
        var dialog = new Dialog<Void>();
        dialog.setTitle(translations.translate("language.title").orElseThrow());
        var choice = new ComboBox<String>();
        choice.setId("language-choice");
        choice.getItems().add("system");
        choice.getItems().addAll(translations.languages().keySet());
        UiText.choices(
                choice,
                value -> value.equals("system")
                        ? translations.translate("language.system").orElseThrow()
                        : translations.languages().get(value));
        var message = new Label(translations.translate("language.restart").orElseThrow());
        message.setWrapText(true);
        message.setId("language-status");
        try {
            String saved =
                    configuration.readUserConfig("language").orElse("system").strip();
            String selected = translations.languages().keySet().stream()
                    .filter(tag -> tag.equalsIgnoreCase(saved)
                            || Locale.forLanguageTag(tag)
                                    .getLanguage()
                                    .equalsIgnoreCase(
                                            Locale.forLanguageTag(saved).getLanguage()))
                    .findFirst()
                    .orElse("system");
            choice.setValue(saved.isEmpty() || saved.equalsIgnoreCase("system") ? "system" : selected);
        } catch (UserConfigException failure) {
            choice.setValue("system");
            message.setText(translations
                    .translate("language.readFailed", failure.getCause().getMessage())
                    .orElseThrow());
        }
        var save = new Button(translations.translate("action.save").orElseThrow());
        save.setId("language-save");
        UiHelp.install(save, translations);
        save.setOnAction(event -> {
            String language = choice.getValue();
            save.setDisable(true);
            choice.setDisable(true);
            worker.execute(() -> {
                String result;
                try {
                    configuration.saveUserConfig("language", language);
                    result = translations.translate("language.saved").orElseThrow();
                } catch (UserConfigException failure) {
                    result = translations
                            .translate("language.saveFailed", failure.getCause().getMessage())
                            .orElseThrow();
                }
                String status = result;
                Platform.runLater(() -> {
                    message.setText(status);
                    save.setDisable(false);
                    choice.setDisable(false);
                });
            });
        });
        dialog.getDialogPane().setContent(new VBox(10, choice, message, save));
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.CLOSE);
        dialog.getDialogPane().lookupButton(ButtonType.CLOSE).setId("language-close");
        Dialogs.show(dialog, owner, translations);
    }

    private LanguageDialog() {}
}
