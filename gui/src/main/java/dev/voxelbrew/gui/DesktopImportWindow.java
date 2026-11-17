package dev.voxelbrew.gui;

import dev.voxelbrew.api.importer.ImportDialog;
import dev.voxelbrew.api.importer.ImportWindow;
import dev.voxelbrew.api.vui.Component;
import dev.voxelbrew.api.vui.FileSource;
import dev.voxelbrew.api.vui.HorizontalLayout;
import dev.voxelbrew.api.vui.VerticalLayout;
import dev.voxelbrew.util.i18n.Translations;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import javafx.beans.value.ObservableValue;
import javafx.event.ActionEvent;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import lombok.RequiredArgsConstructor;

/** Renders importer-owned component trees on the JavaFX application thread. */
@RequiredArgsConstructor
public final class DesktopImportWindow implements ImportWindow {
    private record InputBinding(Supplier<String> value, Node control, Label caption, Label message) {
        void display(String error) {
            if (error == null) {
                control.getStyleClass().remove("import-dialog-invalid");
                if (caption != null) caption.getStyleClass().remove("import-dialog-invalid-label");
                control.setAccessibleHelp(null);
            } else {
                if (!control.getStyleClass().contains("import-dialog-invalid"))
                    control.getStyleClass().add("import-dialog-invalid");
                if (caption != null && !caption.getStyleClass().contains("import-dialog-invalid-label"))
                    caption.getStyleClass().add("import-dialog-invalid-label");
                control.setAccessibleHelp(error);
            }
            message.setText(error == null ? "" : error);
            message.setVisible(error != null);
        }
    }

    private final Window owner;
    private final Path current;
    private final Translations translations;
    private Path suggestedSource;
    private String importerVersion;

    public DesktopImportWindow importerVersion(String version) {
        importerVersion =
                Objects.requireNonNull(version, "version").equals(dev.voxelbrew.util.update.BuildVersion.current())
                        ? null
                        : version;
        return this;
    }

    public DesktopImportWindow suggestSource(Path source) {
        suggestedSource = source;
        return this;
    }

    @Override
    public <R> Optional<R> show(ImportDialog<R> request) {
        Objects.requireNonNull(request, "request");
        if (!javafx.application.Platform.isFxApplicationThread())
            throw new IllegalStateException("Import dialogs must be shown on the JavaFX application thread");
        var inputs = new LinkedHashMap<String, InputBinding>();
        var dialog = new Dialog<R>();
        dialog.setTitle(importerVersion == null ? request.title() : request.title() + " (" + importerVersion + ")");
        dialog.setResizable(true);
        dialog.getDialogPane().setPrefWidth(540);
        var error = new Label();
        error.setId("import-error");
        error.getStyleClass().add("error");
        error.setWrapText(true);
        error.setVisible(false);
        error.managedProperty().bind(error.visibleProperty());
        var attempted = new boolean[1];
        Runnable revalidate = () -> {
            if (attempted[0]) displayErrors(inputs, request.validate(readValues(inputs)), error);
        };
        var content = new VBox(14, render(request.layout(), inputs, revalidate));
        content.getStyleClass().add("import-dialog-content");
        content.getChildren().add(error);
        double maxBodyHeight = Math.max(160, owner.getScene().getHeight() - 180);
        if (content.prefHeight(500) > maxBodyHeight) {
            var scroll = new ScrollPane(content);
            scroll.setFitToWidth(true);
            scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
            scroll.setPrefViewportHeight(maxBodyHeight);
            scroll.getStyleClass().add("import-dialog-scroll");
            dialog.getDialogPane().setContent(scroll);
        } else {
            dialog.getDialogPane().setContent(content);
        }
        var confirm = new ButtonType(translations.text("action.import"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(confirm, ButtonType.CANCEL);
        var button = dialog.getDialogPane().lookupButton(confirm);
        button.setId("import-confirm");
        var accepted = new java.util.concurrent.atomic.AtomicReference<R>();
        button.addEventFilter(ActionEvent.ACTION, event -> {
            try {
                attempted[0] = true;
                var selected = readValues(inputs);
                var errors = request.validate(selected);
                displayErrors(inputs, errors, error);
                if (!errors.isEmpty()) {
                    for (var entry : inputs.entrySet()) {
                        if (errors.containsKey(entry.getKey())) {
                            entry.getValue().control().requestFocus();
                            break;
                        }
                    }
                    event.consume();
                    return;
                }
                accepted.set(Objects.requireNonNull(request.buildSettings(selected), "import settings"));
            } catch (RuntimeException failure) {
                error.setText(Objects.toString(failure.getMessage(), failure.toString()));
                error.setVisible(true);
                event.consume();
            }
        });
        dialog.setResultConverter(choice -> choice == confirm ? accepted.get() : null);
        return Dialogs.show(dialog, owner, translations);
    }

    private Node render(Component component, Map<String, InputBinding> inputs, Runnable onChange) {
        if (component instanceof HorizontalLayout layout) {
            var row = new HBox(12);
            row.setAlignment(Pos.CENTER_LEFT);
            layout.children().forEach(child -> {
                var node = render(child, inputs, onChange);
                row.getChildren().add(node);
                HBox.setHgrow(node, Priority.ALWAYS);
            });
            return row;
        }
        if (component instanceof VerticalLayout layout) {
            var column = new VBox(10);
            layout.children().forEach(child -> column.getChildren().add(render(child, inputs, onChange)));
            return column;
        }
        if (component instanceof dev.voxelbrew.api.vui.Label label) return new Label(label.text());
        if (component instanceof dev.voxelbrew.api.vui.Separator) return new Separator();
        if (component instanceof FileSource source) {
            var path = new javafx.scene.control.TextField();
            path.setId("import-path");
            Path suggestion = source.defaultPath() != null
                    ? source.defaultPath()
                    : suggestedSource == null ? current : suggestedSource;
            if (suggestion != null) path.setText(suggestion.toString());
            var browse = new Button(translations.text("gui.browse"));
            browse.setId("file-browse");
            UiHelp.install(browse, translations);
            browse.setOnAction(event -> {
                var chooser = new FileChooser();
                chooser.setTitle(translations.text("import.source"));
                chooser.getExtensionFilters()
                        .add(new FileChooser.ExtensionFilter("*." + source.extension(), "*." + source.extension()));
                if (!path.getText().isBlank()) {
                    Path initial = Path.of(path.getText()).toAbsolutePath().normalize();
                    if (initial.getParent() != null && Files.isDirectory(initial.getParent()))
                        chooser.setInitialDirectory(initial.getParent().toFile());
                    chooser.setInitialFileName(initial.getFileName().toString());
                }
                var chosen = chooser.showOpenDialog(owner);
                if (chosen != null) path.setText(chosen.toString());
            });
            return bind(
                    inputs,
                    source.id(),
                    path::getText,
                    path,
                    field(translations.text("import.source"), Dialogs.pathRow(path, browse)),
                    path.textProperty(),
                    onChange);
        }
        if (component instanceof dev.voxelbrew.api.vui.TextField field) {
            var input = new javafx.scene.control.TextField(field.value());
            input.setId("import-" + field.id());
            input.setMaxWidth(Double.MAX_VALUE);
            return bind(
                    inputs,
                    field.id(),
                    input::getText,
                    input,
                    field(field.label(), input),
                    input.textProperty(),
                    onChange);
        }
        if (component instanceof dev.voxelbrew.api.vui.TextArea area) {
            var input = new javafx.scene.control.TextArea(area.value());
            input.setId("import-" + area.id());
            input.setMaxWidth(Double.MAX_VALUE);
            return bind(
                    inputs,
                    area.id(),
                    input::getText,
                    input,
                    field(area.label(), input),
                    input.textProperty(),
                    onChange);
        }
        if (component instanceof dev.voxelbrew.api.vui.CheckBox box) {
            var input = new javafx.scene.control.CheckBox(box.label());
            input.setId("import-" + box.id());
            UiHelp.describe(input, translations.textOrDefault("help.import-" + box.id(), box.label()));
            input.setSelected(box.selected());
            input.getStyleClass().add("import-dialog-choice");
            return bind(
                    inputs,
                    box.id(),
                    () -> Boolean.toString(input.isSelected()),
                    input,
                    new VBox(input),
                    input.selectedProperty(),
                    onChange);
        }
        if (component instanceof dev.voxelbrew.api.vui.ComboBox box) {
            var input = new javafx.scene.control.ComboBox<String>();
            input.setId("import-" + box.id());
            input.getItems().setAll(box.choices());
            input.setValue(box.selected());
            input.setMaxWidth(Double.MAX_VALUE);
            return bind(
                    inputs,
                    box.id(),
                    input::getValue,
                    input,
                    field(box.label(), input),
                    input.valueProperty(),
                    onChange);
        }
        if (component instanceof dev.voxelbrew.api.vui.RadioButtonGroup group) {
            var buttons = new ToggleGroup();
            var container = new VBox(4, new Label(group.label()));
            for (String choice : group.choices()) {
                var button = new RadioButton(choice);
                button.setToggleGroup(buttons);
                button.setUserData(choice);
                button.setSelected(choice.equals(group.selected()));
                container.getChildren().add(button);
            }
            container.setFocusTraversable(true);
            return bind(
                    inputs,
                    group.id(),
                    () -> buttons.getSelectedToggle() == null
                            ? null
                            : Objects.toString(buttons.getSelectedToggle().getUserData()),
                    container,
                    container,
                    buttons.selectedToggleProperty(),
                    onChange);
        }
        if (component instanceof dev.voxelbrew.api.vui.Slider slider) {
            var input = new javafx.scene.control.Slider(slider.minimum(), slider.maximum(), slider.value());
            input.setId("import-" + slider.id());
            return bind(
                    inputs,
                    slider.id(),
                    () -> Double.toString(input.getValue()),
                    input,
                    field(slider.label(), input),
                    input.valueProperty(),
                    onChange);
        }
        throw new UnsupportedOperationException(
                "Unsupported import component: " + component.getClass().getName());
    }

    private static VBox field(String label, Node input) {
        var caption = new Label(label);
        caption.getStyleClass().add("import-dialog-field-label");
        var column = new VBox(6, caption, input);
        column.setMaxWidth(Double.MAX_VALUE);
        return column;
    }

    private static VBox bind(
            Map<String, InputBinding> inputs,
            String id,
            Supplier<String> value,
            Node control,
            VBox container,
            ObservableValue<?> changed,
            Runnable onChange) {
        var message = new Label();
        message.getStyleClass().add("import-dialog-inline-error");
        message.setWrapText(true);
        message.setVisible(false);
        message.managedProperty().bind(message.visibleProperty());
        container.getChildren().add(message);
        Label caption = container.getChildren().getFirst() instanceof Label label ? label : null;
        if (inputs.putIfAbsent(id, new InputBinding(value, control, caption, message)) != null)
            throw new IllegalArgumentException("Duplicate import input: " + id);
        changed.addListener((observable, before, after) -> onChange.run());
        return container;
    }

    private static Map<String, String> readValues(Map<String, InputBinding> inputs) {
        var values = new LinkedHashMap<String, String>();
        inputs.forEach((id, input) -> values.put(id, input.value().get()));
        return values;
    }

    private static void displayErrors(Map<String, InputBinding> inputs, Map<String, String> errors, Label general) {
        inputs.forEach((id, input) -> input.display(errors.get(id)));
        var unbound = new java.util.ArrayList<String>();
        errors.forEach((id, message) -> {
            if (!inputs.containsKey(id)) unbound.add(message);
        });
        general.setText(String.join("\n", unbound));
        general.setVisible(!unbound.isEmpty());
    }
}
