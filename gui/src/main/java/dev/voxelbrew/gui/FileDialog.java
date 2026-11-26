package dev.voxelbrew.gui;

import dev.voxelbrew.api.i18n.TranslationService;
import dev.voxelbrew.api.model.parameters.ParameterSet;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import javafx.event.ActionEvent;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Window;

/** Editable path plus native Browse keeps file operations accessible and keyboard friendly. */
final class FileDialog {
    static Optional<Path> choose(
            Window owner,
            boolean save,
            Path current,
            ParameterSet parameters,
            TranslationService translations,
            dev.voxelbrew.api.userconfig.UserConfigService configuration) {
        return choose(owner, save, current, parameters, translations, configuration, "vxb", null);
    }

    static Optional<Path> choose(
            Window owner,
            Path current,
            ParameterSet parameters,
            TranslationService translations,
            dev.voxelbrew.api.userconfig.UserConfigService configuration,
            List<Path> recentFiles) {
        return choose(owner, false, current, parameters, translations, configuration, "vxb", null, recentFiles);
    }

    static Optional<Path> choose(
            Window owner,
            boolean save,
            Path current,
            ParameterSet parameters,
            TranslationService translations,
            dev.voxelbrew.api.userconfig.UserConfigService configuration,
            String extension,
            String title) {
        return choose(owner, save, current, parameters, translations, configuration, extension, title, List.of());
    }

    private static Optional<Path> choose(
            Window owner,
            boolean save,
            Path current,
            ParameterSet parameters,
            TranslationService translations,
            dev.voxelbrew.api.userconfig.UserConfigService configuration,
            String extension,
            String title,
            List<Path> recentFiles) {
        var dialog = new Dialog<Path>();
        dialog.setTitle(
                save
                        ? translations.translate("gui.save_editable_design")
                        : translations.translate("gui.open_editable_design"));
        if (title != null && !extension.equals("vxb")) dialog.setTitle(title);
        Path initial = current;
        if (initial == null) {
            try {
                initial = save
                        ? projectsFolder().resolve("Untitled." + extension)
                        : Path.of(System.getProperty("user.home"), "Untitled.vxb");
            } catch (IllegalArgumentException failure) {
                Dialogs.show(
                        new Alert(Alert.AlertType.ERROR, failure.getMessage(), ButtonType.OK), owner, translations);
                return Optional.empty();
            }
        }
        var path = FormulaField.text(initial.toString(), () -> parameters, translations);
        path.setId("file-path");
        path.setPrefColumnCount(48);
        var browse = new Button(translations.translate("gui.browse"));
        browse.setId("file-browse");
        UiHelp.install(browse, translations);
        browse.setOnAction(e -> {
            browse(owner, save, dialog.getTitle(), extension, path, translations);
        });
        var error = new Label();
        error.getStyleClass().add("error");
        var box = new VBox(
                10,
                new Label(translations.translate("gui.editable_design_location")),
                Dialogs.pathRow(path, browse),
                error);
        dialog.getDialogPane().setContent(box);
        var confirm = new ButtonType(
                save ? translations.translate("action.save") : translations.translate("gui.open"),
                ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(confirm, ButtonType.CANCEL);
        var button = dialog.getDialogPane().lookupButton(confirm);
        button.setId("file-confirm");
        if (!save && !recentFiles.isEmpty()) {
            var recent = new VBox(4, new Label(translations.translate("menu-open-recent")));
            recent.setId("file-recent-projects");
            for (int i = 0; i < Math.min(3, recentFiles.size()); i++) {
                Path project = recentFiles.get(i);
                var link = new Hyperlink(project.toString());
                link.setId("file-recent-" + i);
                link.setMnemonicParsing(false);
                link.setTextOverrun(OverrunStyle.CENTER_ELLIPSIS);
                link.setEllipsisString("...");
                link.setMinWidth(0);
                link.setMaxWidth(Double.MAX_VALUE);
                UiHelp.describe(link, project.toString());
                link.setOnAction(event -> {
                    path.setText(project.toString());
                    ((Button) button).fire();
                });
                recent.getChildren().add(link);
            }
            box.getChildren().add(2, recent);
        }
        button.addEventFilter(ActionEvent.ACTION, e -> {
            validatePath(e, save, path, error, translations);
        });
        dialog.setResultConverter(b -> {
            if (b != confirm) return null;
            Path selected = Path.of(path.evaluatedText()).toAbsolutePath();
            return save ? ExportDialog.withExtension(selected, extension) : selected;
        });
        return Dialogs.show(dialog, owner, translations);
    }

    private static void browse(
            Window owner,
            boolean save,
            String title,
            String extension,
            FormulaField path,
            dev.voxelbrew.api.i18n.TranslationService translations) {
        var chooser = new FileChooser();
        chooser.setTitle(title);
        chooser.getExtensionFilters()
                .add(new FileChooser.ExtensionFilter("VoxelBrew (*." + extension + ")", "*." + extension));
        try {
            Path selected = Path.of(path.evaluatedText()).toAbsolutePath();
            Path parent = selected.getParent();
            Path filename = selected.getFileName();
            if (parent != null && filename != null) {
                if (save) java.nio.file.Files.createDirectories(parent);
                if (java.nio.file.Files.isDirectory(parent)) chooser.setInitialDirectory(parent.toFile());
                chooser.setInitialFileName(filename.toString());
            }
        } catch (java.io.IOException | IllegalArgumentException failure) {
            Dialogs.show(new Alert(Alert.AlertType.ERROR, failure.getMessage(), ButtonType.OK), owner, translations);
            return;
        }
        var file = save ? chooser.showSaveDialog(owner) : chooser.showOpenDialog(owner);
        if (file != null) path.setText(file.toString());
    }

    private static void validatePath(
            ActionEvent e,
            boolean save,
            FormulaField path,
            Label error,
            dev.voxelbrew.api.i18n.TranslationService translations) {
        try {
            if (path.evaluatedText().isBlank()) {
                throw new IllegalArgumentException(translations.translate("gui.choose_a_file_path"));
            }
            Path selected = Path.of(path.evaluatedText()).toAbsolutePath();
            Path parent = selected.getParent();
            if (parent == null) throw new IllegalArgumentException("Expected a file path: " + selected);
            if (save) java.nio.file.Files.createDirectories(parent);
        } catch (java.io.IOException | IllegalArgumentException failure) {
            error.setText(failure.getMessage());
            e.consume();
        }
    }

    static Path projectsFolder() {
        return Path.of(System.getProperty("user.home"), "VoxelBrew");
    }

    private FileDialog() {}
}
