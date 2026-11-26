package dev.voxelbrew.gui;

import dev.voxelbrew.api.exporter.ExporterDescriptor;
import dev.voxelbrew.api.exporter.ExporterProviderRegistry;
import dev.voxelbrew.api.i18n.TranslationService;
import java.nio.file.Files;
import java.nio.file.Path;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.StringConverter;

final class ExportDialog {
    static void choose(
            Window owner,
            ExporterProviderRegistry registry,
            TranslationService translations,
            java.util.function.Consumer<String> documentExporter) {
        var dialog = new Dialog<ExporterDescriptor>();
        dialog.setTitle(translations.translate("action.export"));
        var format = new ComboBox<ExporterDescriptor>();
        format.setId("export-format");
        format.getItems().setAll(registry.descriptors());
        format.getSelectionModel().selectFirst();
        format.setMaxWidth(Double.MAX_VALUE);
        dialog.getDialogPane().setContent(new VBox(8, new Label(translations.translate("gui.format")), format));
        var confirm = new ButtonType(translations.translate("gui.export"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(confirm, ButtonType.CANCEL);
        var button = dialog.getDialogPane().lookupButton(confirm);
        button.setId("export-confirm");
        String appVersion = dev.voxelbrew.util.update.BuildVersion.current();
        format.setConverter(new StringConverter<>() {
            @Override
            public String toString(ExporterDescriptor value) {
                if (value == null) return "";
                return value.name() + (value.version().equals(appVersion) ? "" : " (" + value.version() + ")");
            }

            @Override
            public ExporterDescriptor fromString(String value) {
                throw new UnsupportedOperationException();
            }
        });
        button.disableProperty().bind(format.valueProperty().isNull());
        dialog.setResultConverter(selected -> selected == confirm ? format.getValue() : null);
        Dialogs.show(dialog, owner, translations).ifPresent(choice -> documentExporter.accept(choice.id()));
    }

    static Path destinationForFormat(Path source, String extension) {
        Path filename = source.getFileName();
        if (filename == null) throw new IllegalArgumentException("Expected a project file: " + source);
        String name = filename.toString();
        if (hasSuffix(name, "." + extension)) return source;
        int dot = name.lastIndexOf('.');
        return source.resolveSibling((dot > 0 ? name.substring(0, dot) : name) + "." + extension);
    }

    static Path withExtension(Path destination, String extension) {
        Path filename = destination.getFileName();
        if (filename == null) return destination;
        String name = filename.toString();
        String suffix = "." + extension;
        if (hasSuffix(name, suffix) || Files.isDirectory(destination)) return destination;
        return destination.resolveSibling(name + suffix);
    }

    private static boolean hasSuffix(String name, String suffix) {
        return name.regionMatches(true, name.length() - suffix.length(), suffix, 0, suffix.length());
    }

    private ExportDialog() {}
}
