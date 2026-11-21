package dev.voxelbrew.gui;

import dev.voxelbrew.api.i18n.TranslationService;
import dev.voxelbrew.api.printer.PrinterProfileCatalog;
import java.util.*;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

final class PrinterPickerDialog {
    static Optional<PrinterProfileCatalog.Entry> choose(
            Window owner,
            TranslationService translations,
            List<PrinterProfileCatalog.Entry> entries,
            String currentId) {
        var search = new TextField();
        search.setId("print-printer-search");
        search.setPromptText(translations.translate("print.searchPrinters"));
        search.setAccessibleText(translations.translate("print.searchPrinters"));
        var results = new ListView<PrinterProfileCatalog.Entry>();
        results.setId("print-printer-results");
        results.setPrefHeight(300);
        results.setPlaceholder(new Label(translations.translate("print.noPrinters")));
        results.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(PrinterProfileCatalog.Entry item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    return;
                }
                String suffix = item.bundled() ? " (" + translations.translate("print.builtInPrinter") + ")" : "";
                setText(item.name() + suffix);
            }
        });
        results.getItems().setAll(entries);
        results.getSelectionModel().selectFirst();
        entries.stream()
                .filter(entry -> entry.definition().printer().id().equals(currentId))
                .findFirst()
                .ifPresent(entry -> results.getSelectionModel().select(entry));
        results.scrollTo(results.getSelectionModel().getSelectedIndex());
        search.textProperty().addListener((o, a, query) -> {
            String filter = query.strip().toLowerCase(Locale.ROOT);
            results.getItems()
                    .setAll(entries.stream()
                            .filter(entry ->
                                    entry.name().toLowerCase(Locale.ROOT).contains(filter))
                            .toList());
            results.getSelectionModel().selectFirst();
            results.scrollTo(0);
        });
        var dialog = new Dialog<PrinterProfileCatalog.Entry>();
        dialog.setTitle(translations.translate("print.printerSelector"));
        var content = new VBox(8, search, results);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        var confirm = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
        confirm.setId("print-printer-confirm");
        confirm.disableProperty()
                .bind(results.getSelectionModel().selectedItemProperty().isNull());
        search.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.DOWN || event.getCode() == KeyCode.UP) {
                if (event.getCode() == KeyCode.DOWN) results.getSelectionModel().selectNext();
                else {
                    results.getSelectionModel().selectPrevious();
                }
                results.scrollTo(results.getSelectionModel().getSelectedIndex());
                event.consume();
            }
        });
        content.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ENTER) {
                confirm.fire();
                event.consume();
            }
        });
        results.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) confirm.fire();
        });
        dialog.setResultConverter(
                type -> type == ButtonType.OK ? results.getSelectionModel().getSelectedItem() : null);
        Platform.runLater(() -> Platform.runLater(search::requestFocus));
        return Dialogs.show(dialog, owner, translations);
    }

    private PrinterPickerDialog() {}
}
