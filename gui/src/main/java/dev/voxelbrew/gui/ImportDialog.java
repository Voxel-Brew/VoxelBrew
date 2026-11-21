package dev.voxelbrew.gui;

import dev.voxelbrew.api.i18n.TranslationService;
import dev.voxelbrew.api.importer.*;
import dev.voxelbrew.api.model.parameters.*;
import dev.voxelbrew.engine.parameters.*;
import java.nio.file.*;
import java.util.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.*;

/** Descriptor-driven inputs; provider code runs later on the document controller's file worker. */
final class ImportDialog {
    static Optional<ImporterDescriptor> choose(
            Window owner, ImporterProviderRegistry registry, TranslationService translations) {
        var dialog = new Dialog<ImporterDescriptor>();
        dialog.setTitle(translations.translate("import.title"));
        var format = new ComboBox<ImporterDescriptor>();
        format.setId("import-format");
        format.getItems().setAll(registry.descriptors().stream().toList());
        UiText.choices(format, ImporterDescriptor::name);
        format.getSelectionModel().selectFirst();
        dialog.getDialogPane().setContent(new VBox(8, new Label(translations.translate("gui.format")), format));
        var confirm = new ButtonType(translations.translate("action.import"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(confirm, ButtonType.CANCEL);
        var button = dialog.getDialogPane().lookupButton(confirm);
        button.setId("import-confirm");
        button.disableProperty().bind(format.valueProperty().isNull());
        dialog.setResultConverter(selected -> selected == confirm ? format.getValue() : null);
        return Dialogs.show(dialog, owner, translations);
    }

    private ImportDialog() {}
}
