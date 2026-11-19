package dev.voxelbrew.gui;

import dev.voxelbrew.api.model.printing.Cancellation;
import dev.voxelbrew.api.model.vui.Layout;
import dev.voxelbrew.api.model.vui.VerticalLayout;
import dev.voxelbrew.api.slicer.*;
import dev.voxelbrew.slicer.*;
import dev.voxelbrew.util.config.UserConfiguration;
import dev.voxelbrew.util.i18n.Translations;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.StringConverter;

/** Manages named slicer installations and runs provider checks off the JavaFX thread. */
final class SlicerSettings {
    private static final String NAME = "voxelbrew.installation.name";

    private record Check(String version, String failure) {}

    private final UserConfiguration configuration;
    private final SlicerInstallationStore store;
    private final ExecutorService worker;

    SlicerSettings(UserConfiguration configuration, ExecutorService worker) {
        this.configuration = configuration;
        this.store = new SlicerInstallationStore(configuration.path());
        this.worker = worker;
    }

    List<SlicerInstallation> installations() {
        try {
            return store.load();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    void configure(SlicerProviderRegistry registry, Window owner, Translations text, Runnable refresh) {
        var manager = new Dialog<ButtonType>();
        manager.setTitle(text.text("slicer.settings.title"));
        manager.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        var installations = new ListView<SlicerInstallation>();
        installations.setId("slicer-installations");
        installations.setPlaceholder(new Label(text.text("slicer.installation.empty")));
        installations.setPrefHeight(170);
        installations.setCellFactory(ignored -> new ListCell<>() {
            @Override
            protected void updateItem(SlicerInstallation item, boolean empty) {
                super.updateItem(item, empty);
                setText(
                        empty || item == null
                                ? null
                                : item.name() + " · "
                                        + (item.operational()
                                                ? item.version()
                                                : text.text("slicer.installation.unavailable")));
            }
        });
        installations.getItems().setAll(installations());
        var status = new Label();
        status.setId("slicer-installation-status");
        status.setWrapText(true);
        installations
                .getSelectionModel()
                .selectedItemProperty()
                .addListener((observable, old, selected) -> status.setText(
                        selected == null
                                ? ""
                                : selected.bundled()
                                        ? text.text("slicer.installation.bundled", selected.version())
                                        : selected.operational()
                                                ? text.text("slicer.installation.operational", selected.version())
                                                : text.text("slicer.installation.failed", selected.failure())));
        installations.getSelectionModel().selectFirst();

        var types = new ComboBox<SlicerDescriptor>();
        types.setId("slicer-installation-provider");
        types.setAccessibleText(text.text("slicer.installation.provider"));
        types.getItems().setAll(registry.descriptors());
        types.setConverter(new StringConverter<>() {
            @Override
            public String toString(SlicerDescriptor descriptor) {
                return descriptor == null ? "" : descriptor.name();
            }

            @Override
            public SlicerDescriptor fromString(String name) {
                throw new UnsupportedOperationException();
            }
        });
        types.getSelectionModel().selectFirst();
        var add = new Button(text.text("slicer.installation.add"));
        add.setId("slicer-installation-add");
        add.disableProperty().bind(types.valueProperty().isNull());
        var edit = new Button(text.text("slicer.installation.edit"));
        edit.setId("slicer-installation-edit");
        edit.disableProperty()
                .bind(installations
                        .getSelectionModel()
                        .selectedItemProperty()
                        .map(SlicerInstallation::bundled)
                        .orElse(true));
        java.util.function.Consumer<SlicerInstallation> update = saved -> {
            installations.getItems().setAll(installations());
            installations.getSelectionModel().select(saved);
            refresh.run();
        };
        add.setOnAction(event -> editSafely(registry, types.getValue().id(), null, owner, text, update));
        edit.setOnAction(event -> {
            var selected = installations.getSelectionModel().getSelectedItem();
            editSafely(registry, selected.providerId(), selected, owner, text, update);
        });
        var content = new VBox(10, installations, status, new HBox(8, types, add, edit));
        content.setPrefWidth(520);
        manager.getDialogPane().setContent(content);
        Dialogs.show(manager, owner, text);
    }

    private void editSafely(
            SlicerProviderRegistry registry,
            String providerId,
            SlicerInstallation existing,
            Window owner,
            Translations text,
            java.util.function.Consumer<SlicerInstallation> update) {
        try {
            edit(registry.require(providerId), existing, owner, text).ifPresent(update);
        } catch (IOException | IllegalArgumentException | UncheckedIOException failure) {
            Dialogs.show(
                    new Alert(Alert.AlertType.ERROR, text.text("slicer.settings.failed", failure.getMessage())),
                    owner,
                    text);
        }
    }

    private Optional<SlicerInstallation> edit(
            SlicerProvider provider, SlicerInstallation existing, Window owner, Translations text) throws IOException {
        var descriptor = provider.descriptor();
        var dialog = provider.settingsDialog().orElse(null);
        var name = new dev.voxelbrew.api.model.vui.TextField(
                NAME, text.text("slicer.installation.name"), descriptor.name());
        Layout layout = dialog == null
                ? new VerticalLayout(name)
                : new VerticalLayout(name, new dev.voxelbrew.api.model.vui.Separator(), dialog.layout());
        var initial = new HashMap<>(
                existing == null
                        ? configuration.section("slicer", descriptor.id()).read()
                        : existing.settings());
        if (existing != null) initial.put(NAME, existing.name());
        var others = installations().stream()
                .filter(entry -> existing == null || !entry.id().equals(existing.id()))
                .toList();
        return new DesktopExportWindow(owner, null, text)
                .interaction(text.text("slicer.installation.testSave"), "slicer-input-")
                .sourceCaption(id -> text.textOrDefault("slicer.input." + id, text.text("import.source")))
                .show(
                        text.text("slicer.installation.configure", descriptor.name()),
                        layout,
                        values -> {
                            var errors = new LinkedHashMap<String, String>();
                            String proposed = values.getOrDefault(NAME, "").strip();
                            if (proposed.isEmpty()) errors.put(NAME, text.text("slicer.installation.nameRequired"));
                            else if (others.stream()
                                    .anyMatch(entry -> entry.name().equalsIgnoreCase(proposed)))
                                errors.put(NAME, text.text("slicer.installation.duplicate"));
                            if (dialog != null) errors.putAll(dialog.validate(providerValues(values)));
                            return errors;
                        },
                        values -> saveCheckedInstallation(provider, existing, values, owner, text),
                        initial,
                        false);
    }

    private SlicerInstallation saveCheckedInstallation(
            SlicerProvider provider,
            SlicerInstallation existing,
            Map<String, String> inputs,
            Window owner,
            Translations text) {
        var values = providerValues(inputs);
        var result = check(provider, values, owner, text)
                .orElseThrow(() -> new IllegalArgumentException(text.text("slicer.installation.cancelled")));
        if (!result.failure().isEmpty())
            throw new IllegalArgumentException(text.text("slicer.installation.failed", result.failure()));
        var installation = new SlicerInstallation(
                existing == null ? UUID.randomUUID() : existing.id(),
                provider.descriptor().id(),
                inputs.get(NAME).strip(),
                result.version(),
                "",
                values);
        try {
            store.save(installation);
        } catch (IOException failure) {
            throw new UncheckedIOException(text.text("slicer.settings.failed", failure.getMessage()), failure);
        }
        return installation;
    }

    private Optional<Check> check(
            SlicerProvider provider, Map<String, String> settings, Window owner, Translations text) {
        var progress = new Dialog<Check>();
        progress.setTitle(text.text("slicer.installation.checking"));
        var done = new ButtonType("", ButtonBar.ButtonData.OTHER);
        progress.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, done);
        var finish = (ButtonBase) progress.getDialogPane().lookupButton(done);
        finish.setVisible(false);
        finish.setManaged(false);
        var result = new java.util.concurrent.atomic.AtomicReference<Check>();
        progress.setResultConverter(button -> button == done ? result.get() : null);
        var indicator = new ProgressIndicator();
        indicator.setPrefSize(24, 24);
        indicator.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        progress.getDialogPane().setContent(indicator);
        var cancellation = new Cancellation();
        worker.execute(() -> {
            Check checked;
            try {
                String version = provider.checkConfiguration(SlicerInputs.window(settings, Map.of()), cancellation);
                if (version == null || version.isBlank())
                    throw new IllegalArgumentException("Slicer did not report a version");
                checked = new Check(version, "");
            } catch (CancellationException cancelled) {
                checked = new Check("", text.text("slicer.installation.cancelled"));
            } catch (Exception | LinkageError failure) {
                String message = Objects.toString(failure.getMessage(), "").strip();
                checked = new Check("", message.isEmpty() ? failure.toString() : message);
            }
            var outcome = checked;
            Platform.runLater(() -> {
                if (cancellation.cancelled()) return;
                result.set(outcome);
                finish.fire();
            });
        });
        try {
            return Dialogs.show(progress, owner, text);
        } finally {
            cancellation.cancel();
        }
    }

    private static Map<String, String> providerValues(Map<String, String> values) {
        var result = new LinkedHashMap<>(values);
        result.remove(NAME);
        return result;
    }
}
