package dev.voxelbrew.gui;

import dev.voxelbrew.api.exporter.ExportDialog;
import dev.voxelbrew.api.exporter.ExportWindow;
import dev.voxelbrew.api.model.vui.Component;
import dev.voxelbrew.api.model.vui.FileDestination;
import dev.voxelbrew.api.model.vui.HorizontalLayout;
import dev.voxelbrew.api.model.vui.VerticalLayout;
import dev.voxelbrew.util.i18n.Translations;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
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

/** Renders exporter-owned component trees on the JavaFX application thread. */
@RequiredArgsConstructor
public final class DesktopExportWindow implements ExportWindow {
    private record InputBinding(Supplier<String> value, Node control, Label caption, Label message) {
        void display(String error) {
            if (error == null) {
                control.getStyleClass().remove("export-dialog-invalid");
                if (caption != null) caption.getStyleClass().remove("export-dialog-invalid-label");
                control.setAccessibleHelp(null);
            } else {
                if (!control.getStyleClass().contains("export-dialog-invalid"))
                    control.getStyleClass().add("export-dialog-invalid");
                if (caption != null && !caption.getStyleClass().contains("export-dialog-invalid-label"))
                    caption.getStyleClass().add("export-dialog-invalid-label");
                control.setAccessibleHelp(error);
            }
            message.setText(error == null ? "" : error);
            message.setVisible(error != null);
        }
    }

    private final Window owner;
    private final Path current;
    private final Translations translations;
    private Path suggestedDestination;
    private String exporterVersion;
    private String confirmLabel;
    private String inputPrefix = "export-";
    private Function<String, String> sourceCaption;

    public DesktopExportWindow interaction(String label, String prefix) {
        confirmLabel = label;
        inputPrefix = prefix;
        return this;
    }

    DesktopExportWindow sourceCaption(Function<String, String> caption) {
        sourceCaption = caption;
        return this;
    }

    public DesktopExportWindow exporterVersion(String version) {
        exporterVersion =
                Objects.requireNonNull(version, "version").equals(dev.voxelbrew.util.update.BuildVersion.current())
                        ? null
                        : version;
        return this;
    }

    public DesktopExportWindow suggestDestination(Path destination) {
        suggestedDestination = destination;
        return this;
    }

    record Embedded(Node node, Supplier<Map<String, String>> values) {}

    Embedded embed(dev.voxelbrew.api.printer.driver.DriverGui gui, Map<String, String> initial, Runnable changed) {
        var inputs = new LinkedHashMap<String, InputBinding>();
        var node = render(gui.layout(), inputs, changed);
        applyInitial(inputs, initial);
        return new Embedded(node, () -> readValues(inputs));
    }

    private static void applyInitial(Map<String, InputBinding> inputs, Map<String, String> initial) {
        initial.forEach((id, value) -> {
            var binding = inputs.get(id);
            if (binding == null) return;
            switch (binding.control()) {
                case TextInputControl input -> input.setText(value);
                case CheckBox input -> input.setSelected(Boolean.parseBoolean(value));
                case ComboBox<?> input ->
                    input.getSelectionModel().select(input.getItems().indexOf(value));
                case Slider input -> input.setValue(Double.parseDouble(value));
                case VBox group ->
                    group.getChildren().stream()
                            .filter(RadioButton.class::isInstance)
                            .map(RadioButton.class::cast)
                            .filter(button -> Objects.equals(button.getUserData(), value))
                            .findFirst()
                            .ifPresent(button -> button.setSelected(true));
                default -> {}
            }
        });
    }

    @Override
    public <R> Optional<R> show(ExportDialog<R> request) {
        Objects.requireNonNull(request, "request");
        return show(request.title(), request.layout(), request::validate, request::buildSettings, Map.of(), true);
    }

    <R> Optional<R> show(
            String title,
            dev.voxelbrew.api.model.vui.Layout layout,
            Function<Map<String, String>, Map<String, String>> validate,
            Function<Map<String, String>, R> buildSettings,
            Map<String, String> initial,
            boolean confirmOverwrite) {
        if (!javafx.application.Platform.isFxApplicationThread())
            throw new IllegalStateException("Export dialogs must be shown on the JavaFX application thread");
        var inputs = new LinkedHashMap<String, InputBinding>();
        var dialog = new Dialog<R>();
        dialog.setTitle(exporterVersion == null ? title : title + " (" + exporterVersion + ")");
        dialog.setResizable(true);
        dialog.getDialogPane().setPrefWidth(540);
        var error = new Label();
        error.setId("export-error");
        error.getStyleClass().add("error");
        error.setWrapText(true);
        error.setVisible(false);
        error.managedProperty().bind(error.visibleProperty());
        var attempted = new boolean[1];
        Runnable revalidate = () -> {
            if (attempted[0]) displayErrors(inputs, validate.apply(readValues(inputs)), error);
        };
        var content = new VBox(14, render(layout, inputs, revalidate));
        applyInitial(inputs, initial);
        content.getStyleClass().add("export-dialog-content");
        content.getChildren().add(error);
        double maxBodyHeight = Math.max(160, owner.getScene().getHeight() - 180);
        if (content.prefHeight(500) > maxBodyHeight) {
            var scroll = new ScrollPane(content);
            scroll.setFitToWidth(true);
            scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
            scroll.setPrefViewportHeight(maxBodyHeight);
            scroll.getStyleClass().add("export-dialog-scroll");
            dialog.getDialogPane().setContent(scroll);
        } else {
            dialog.getDialogPane().setContent(content);
        }
        var confirm = new ButtonType(
                confirmLabel == null ? translations.text("gui.export") : confirmLabel, ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(confirm, ButtonType.CANCEL);
        var button = dialog.getDialogPane().lookupButton(confirm);
        button.setId("export-confirm");
        var accepted = new java.util.concurrent.atomic.AtomicReference<R>();
        button.addEventFilter(ActionEvent.ACTION, event -> {
            try {
                attempted[0] = true;
                var selected = readValues(inputs);
                var errors = validate.apply(selected);
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
                if (confirmOverwrite
                        && selected.containsKey("destination")
                        && selected.containsKey("overwrite")
                        && !Boolean.parseBoolean(selected.get("overwrite"))
                        && Files.exists(Path.of(selected.get("destination")))) {
                    var confirmation = new Alert(
                            Alert.AlertType.CONFIRMATION,
                            translations.text("file.replace", Path.of(selected.get("destination"))),
                            ButtonType.YES,
                            ButtonType.NO);
                    if (Dialogs.show(confirmation, owner, translations).orElse(ButtonType.NO) != ButtonType.YES) {
                        event.consume();
                        return;
                    }
                    selected.put("overwrite", "true");
                }
                accepted.set(Objects.requireNonNull(buildSettings.apply(selected), "export settings"));
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
        if (component instanceof dev.voxelbrew.api.model.vui.Label label) return new Label(label.text());
        if (component instanceof dev.voxelbrew.api.model.vui.Separator) return new Separator();
        if (component instanceof dev.voxelbrew.api.model.vui.FileSource source) {
            var path = new javafx.scene.control.TextField(
                    source.defaultPath() == null ? "" : source.defaultPath().toString());
            path.setId(inputPrefix + source.id());
            var caption = sourceCaption == null ? translations.text("import.source") : sourceCaption.apply(source.id());
            var browse = new Button(translations.text("gui.browse"));
            browse.setId(inputPrefix + source.id() + "-browse");
            browse.setOnAction(event -> {
                var chooser = new FileChooser();
                chooser.setTitle(caption);
                if (!source.extension().equals("*")) {
                    String pattern = "*." + source.extension();
                    chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(pattern, pattern));
                }
                chooser.getExtensionFilters()
                        .add(new FileChooser.ExtensionFilter(translations.text("gui.allFiles"), "*"));
                try {
                    Path initial = Path.of(path.getText());
                    if (initial.getParent() != null && Files.isDirectory(initial.getParent()))
                        chooser.setInitialDirectory(
                                initial.toAbsolutePath().getParent().toFile());
                } catch (InvalidPathException ignored) {
                    // A file can still be chosen to replace invalid input.
                }
                var chosen = chooser.showOpenDialog(owner);
                if (chosen != null) path.setText(chosen.toString());
            });
            return bind(
                    inputs,
                    source.id(),
                    path::getText,
                    path,
                    field(caption, Dialogs.pathRow(path, browse)),
                    path.textProperty(),
                    onChange);
        }
        if (component instanceof FileDestination destination) {
            var path = new javafx.scene.control.TextField();
            path.setId("export-path");
            Path suggestion = destination.defaultPath() != null
                    ? destination.defaultPath()
                    : suggestedDestination == null ? current : suggestedDestination;
            if (suggestion != null)
                path.setText(dev.voxelbrew.gui.ExportDialog.destinationForFormat(suggestion, destination.extension())
                        .toString());
            var browse = new Button(translations.text("gui.browse"));
            browse.setId("file-browse");
            UiHelp.install(browse, translations);
            browse.setOnAction(event -> {
                var chooser = new FileChooser();
                chooser.setTitle(translations.text("gui.destination"));
                chooser.getExtensionFilters()
                        .add(new FileChooser.ExtensionFilter(
                                "*." + destination.extension(), "*." + destination.extension()));
                if (!path.getText().isBlank()) {
                    Path initial = Path.of(path.getText()).toAbsolutePath().normalize();
                    if (initial.getParent() != null && Files.isDirectory(initial.getParent()))
                        chooser.setInitialDirectory(initial.getParent().toFile());
                    chooser.setInitialFileName(initial.getFileName().toString());
                }
                var chosen = chooser.showSaveDialog(owner);
                if (chosen != null) path.setText(chosen.toString());
            });
            return bind(
                    inputs,
                    destination.id(),
                    path::getText,
                    path,
                    field(translations.text("gui.destination"), Dialogs.pathRow(path, browse)),
                    path.textProperty(),
                    onChange);
        }
        if (component instanceof dev.voxelbrew.api.model.vui.PasswordField field) {
            var input = new javafx.scene.control.PasswordField();
            input.setText(field.value());
            input.setId(inputPrefix + field.id().replace('.', '-'));
            return bind(
                    inputs,
                    field.id(),
                    input::getText,
                    input,
                    field(field.label(), input),
                    input.textProperty(),
                    onChange);
        }
        if (component instanceof dev.voxelbrew.api.model.vui.TextField field) {
            var input = new javafx.scene.control.TextField(field.value());
            input.setId(inputPrefix + field.id().replace('.', '-'));
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
        if (component instanceof dev.voxelbrew.api.model.vui.TextArea area) {
            var input = new javafx.scene.control.TextArea(area.value());
            input.setId(inputPrefix + area.id());
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
        if (component instanceof dev.voxelbrew.api.model.vui.CheckBox box) {
            var input = new javafx.scene.control.CheckBox(box.label());
            input.setId(inputPrefix + box.id());
            UiHelp.describe(input, translations.textOrDefault("help.export-" + box.id(), box.label()));
            input.setSelected(box.selected());
            input.getStyleClass().add("export-dialog-choice");
            return bind(
                    inputs,
                    box.id(),
                    () -> Boolean.toString(input.isSelected()),
                    input,
                    new VBox(input),
                    input.selectedProperty(),
                    onChange);
        }
        if (component instanceof dev.voxelbrew.api.model.vui.ComboBox box) {
            var input = new javafx.scene.control.ComboBox<String>();
            input.setId(inputPrefix + box.id());
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
        if (component instanceof dev.voxelbrew.api.model.vui.RadioButtonGroup group) {
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
        if (component instanceof dev.voxelbrew.api.model.vui.Slider slider) {
            var input = new javafx.scene.control.Slider(slider.minimum(), slider.maximum(), slider.value());
            input.setId(inputPrefix + slider.id());
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
                "Unsupported export component: " + component.getClass().getName());
    }

    private static VBox field(String label, Node input) {
        var caption = new Label(label);
        caption.getStyleClass().add("export-dialog-field-label");
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
        message.getStyleClass().add("export-dialog-inline-error");
        message.setWrapText(true);
        message.setVisible(false);
        message.managedProperty().bind(message.visibleProperty());
        container.getChildren().add(message);
        Label caption = container.getChildren().getFirst() instanceof Label label ? label : null;
        if (caption != null) caption.setLabelFor(control);
        if (inputs.putIfAbsent(id, new InputBinding(value, control, caption, message)) != null)
            throw new IllegalArgumentException("Duplicate export input: " + id);
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
