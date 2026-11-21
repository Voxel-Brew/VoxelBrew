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
        dialog.setTitle(translations.translate("language.title"));
        var choice = new ComboBox<String>();
        choice.setId("language-choice");
        var languages = translations.languages();
        choice.getItems().add("system");
        choice.getItems().addAll(languages.keySet());
        UiText.choices(
                choice,
                value -> value.equals("system") ? translations.translate("language.system") : languages.get(value));
        var message = new Label(translations.translate("language.restart"));
        message.setWrapText(true);
        message.setId("language-status");
        try {
            String saved =
                    configuration.readUserConfig("language").orElse("system").strip();
            String savedLanguage = Locale.forLanguageTag(saved).getLanguage();
            String selected = languages.keySet().stream()
                    .filter(tag -> tag.equalsIgnoreCase(saved))
                    .findFirst()
                    .or(() -> languages.keySet().stream()
                            .filter(tag ->
                                    Locale.forLanguageTag(tag).getLanguage().equals(savedLanguage))
                            .findFirst())
                    .orElse("system");
            choice.setValue(saved.isEmpty() || saved.equalsIgnoreCase("system") ? "system" : selected);
        } catch (UserConfigException failure) {
            choice.setValue("system");
            message.setText(translations.translate(
                    "language.readFailed", failure.getCause().getMessage()));
        }
        var save = new Button(translations.translate("action.save"));
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
                    result = translations.translate("language.saved");
                } catch (UserConfigException failure) {
                    result = translations.translate(
                            "language.saveFailed", failure.getCause().getMessage());
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
