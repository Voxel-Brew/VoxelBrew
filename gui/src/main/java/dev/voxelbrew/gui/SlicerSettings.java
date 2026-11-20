package dev.voxelbrew.gui;

import dev.voxelbrew.api.i18n.TranslationService;
import dev.voxelbrew.api.model.printing.Cancellation;
import dev.voxelbrew.api.model.vui.Layout;
import dev.voxelbrew.api.model.vui.VerticalLayout;
import dev.voxelbrew.api.slicer.*;
import dev.voxelbrew.api.userconfig.UserConfigException;
import dev.voxelbrew.api.userconfig.UserConfigService;
import dev.voxelbrew.slicer.*;
import dev.voxelbrew.util.update.BuildVersion;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.regex.Pattern;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.StringConverter;

/** Manages named slicer installations and runs provider checks off the JavaFX thread. */
final class SlicerSettings {
    private static final String NAME = "voxelbrew.installation.name";

    private static final Pattern VERSION = Pattern.compile("(?<![\\w.])v?(\\d+(?:\\.\\d+)+)(?![\\d.])");

    private static final int INSTALLATION_ROW_HEIGHT = 54;

    private final SlicerInstallationStore store;

    private final ExecutorService worker;

    private record Check(String version, String failure) {}

    private static ListCell<SlicerInstallation> installationCell(
            Map<String, SlicerDescriptor> providers, String appVersion, TranslationService text) {
        var name = new Label();
        name.getStyleClass().add("slicer-installation-name");
        var version = new Label();
        version.getStyleClass().add("slicer-installation-version");
        var state = new Label();
        state.setMinWidth(Region.USE_PREF_SIZE);
        var identity = new VBox(2, name, version);
        identity.setMinWidth(0);
        identity.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(identity, Priority.ALWAYS);
        var row = new HBox(12, identity, state);
        row.setAlignment(Pos.CENTER_LEFT);
        return new ListCell<>() {
            {
                setPrefWidth(0);
                row.prefWidthProperty()
                        .bind(Bindings.createDoubleBinding(
                                () -> Math.max(
                                        0,
                                        getWidth()
                                                - getInsets().getLeft()
                                                - getInsets().getRight()),
                                widthProperty(),
                                insetsProperty()));
            }

            @Override
            protected void updateItem(SlicerInstallation item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    setTooltip(null);
                    setAccessibleText(null);
                    return;
                }
                var descriptor = providers.get(item.providerId());
                String provider = descriptor == null ? item.providerId() : descriptor.name();
                String displayName = descriptor == null || descriptor.version().equals(appVersion)
                        ? item.name()
                        : item.name() + " (" + descriptor.version() + ")";
                var match = VERSION.matcher(item.version());
                String release = match.find() ? match.group(1) : item.version();
                String subtitle;
                if (item.name().equalsIgnoreCase(provider)) {
                    subtitle = release;
                } else if (release.isBlank()) {
                    subtitle = provider;
                } else {
                    subtitle = provider + " · " + release;
                }
                name.setText(displayName);
                version.setText(subtitle);
                String status;
                if (item.bundled()) {
                    status = "bundled";
                } else if (item.operational()) {
                    status = "ready";
                } else {
                    status = "unavailable";
                }
                state.setText(
                        text.translate("slicer.installation.state." + status).orElseThrow());
                state.getStyleClass().setAll("label", "slicer-installation-state", "slicer-state-" + status);
                setTooltip(item.version().isBlank() ? null : new Tooltip(item.version()));
                setAccessibleText(displayName + " · " + subtitle + " · " + state.getText());
                setGraphic(row);
            }
        };
    }

    private static void showInstallationDetail(Label status, SlicerInstallation selected, TranslationService text) {
        String detail;
        if (selected == null) {
            detail = "";
        } else if (selected.bundled()) {
            detail = text.translate("slicer.installation.bundled").orElseThrow();
        } else if (selected.operational()) {
            detail = selected.settings().getOrDefault("executable", selected.version());
        } else {
            detail = text.translate("slicer.installation.failed", selected.failure())
                    .orElseThrow();
        }
        status.setText(detail);
        status.setTooltip(detail.isBlank() ? null : new Tooltip(detail));
    }

    private static Map<String, String> providerValues(Map<String, String> values) {
        var result = new LinkedHashMap<>(values);
        result.remove(NAME);
        return result;
    }

    SlicerSettings(UserConfigService configuration, ExecutorService worker) {
        this.store = new SlicerInstallationStore(configuration);
        this.worker = worker;
    }

    List<SlicerInstallation> installations() {
        try {
            return store.load();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    void configure(SlicerProviderRegistry registry, Window owner, TranslationService text, Runnable refresh) {
        var manager = new Dialog<ButtonType>();
        manager.setTitle(text.translate("slicer.settings.heading").orElseThrow());
        manager.getDialogPane().setPrefWidth(570);
        manager.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        manager.getDialogPane().lookupButton(ButtonType.CLOSE).getStyleClass().add("slicer-manager-action");
        var descriptors = registry.descriptors();
        var providers = new HashMap<String, SlicerDescriptor>();
        descriptors.forEach(descriptor -> providers.put(descriptor.id(), descriptor));
        String appVersion = BuildVersion.current();
        var installations = new ListView<SlicerInstallation>();
        installations.setId("slicer-installations");
        installations.getStyleClass().add("slicer-installation-list");
        var empty = new Label(text.translate("slicer.installation.empty").orElseThrow());
        empty.getStyleClass().add("slicer-installation-detail");
        installations.setPlaceholder(empty);
        installations.setFixedCellSize(INSTALLATION_ROW_HEIGHT);
        var items = installations.getItems();
        installations
                .prefHeightProperty()
                .bind(Bindings.createDoubleBinding(
                        () -> 10.0 + INSTALLATION_ROW_HEIGHT * Math.max(1, Math.min(4, items.size())), items));
        installations.setCellFactory(ignored -> installationCell(providers, appVersion, text));
        installations.getItems().setAll(installations());
        var status = new Label();
        status.setId("slicer-installation-status");
        status.getStyleClass().add("slicer-installation-detail");
        status.setTextOverrun(OverrunStyle.CENTER_ELLIPSIS);
        status.setMinWidth(0);
        status.setPrefWidth(0);
        status.setMaxWidth(Double.MAX_VALUE);
        installations
                .getSelectionModel()
                .selectedItemProperty()
                .addListener((observable, old, selected) -> showInstallationDetail(status, selected, text));
        installations.getSelectionModel().selectFirst();

        var types = new ComboBox<SlicerDescriptor>();
        types.setId("slicer-installation-provider");
        types.setAccessibleText(text.translate("slicer.installation.provider").orElseThrow());
        types.getItems().setAll(descriptors);
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
        var add = new Button(text.translate("slicer.installation.add").orElseThrow());
        add.setId("slicer-installation-add");
        add.getStyleClass().addAll("slicer-manager-action", "slicer-manager-primary");
        add.setMinWidth(Region.USE_PREF_SIZE);
        add.disableProperty().bind(types.valueProperty().isNull());
        var edit = new Button(text.translate("slicer.installation.edit").orElseThrow());
        edit.setId("slicer-installation-edit");
        edit.getStyleClass().add("slicer-manager-action");
        edit.setMinWidth(Region.USE_PREF_SIZE);
        var remove = new Button(text.translate("slicer.installation.remove").orElseThrow());
        remove.setId("slicer-installation-remove");
        remove.getStyleClass().addAll("slicer-manager-action", "slicer-manager-remove");
        remove.setMinWidth(Region.USE_PREF_SIZE);
        var readOnly = installations
                .getSelectionModel()
                .selectedItemProperty()
                .map(SlicerInstallation::bundled)
                .orElse(true);
        edit.disableProperty().bind(readOnly);
        remove.disableProperty().bind(readOnly);
        java.util.function.Consumer<SlicerInstallation> update = saved -> {
            installations.getItems().setAll(installations());
            if (saved == null) installations.getSelectionModel().selectFirst();
            else {
                installations.getSelectionModel().select(saved);
            }
            refresh.run();
        };
        add.setOnAction(event -> editSafely(registry, types.getValue().id(), null, owner, text, update));
        edit.setOnAction(event -> {
            var selected = installations.getSelectionModel().getSelectedItem();
            editSafely(registry, selected.providerId(), selected, owner, text, update);
        });
        remove.setOnAction(event -> removeSafely(
                installations.getSelectionModel().getSelectedItem(), owner, text, () -> update.accept(null)));
        types.setMaxWidth(Double.MAX_VALUE);
        types.setMinWidth(0);
        HBox.setHgrow(types, Priority.ALWAYS);
        var actions = new HBox(8, status, edit, remove);
        actions.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(status, Priority.ALWAYS);
        var typeLabel = new Label(text.translate("slicer.installation.provider").orElseThrow());
        typeLabel.setLabelFor(types);
        typeLabel.setMinWidth(Region.USE_PREF_SIZE);
        var creation = new HBox(10, typeLabel, types, add);
        creation.setAlignment(Pos.CENTER_LEFT);
        creation.getStyleClass().add("slicer-manager-create");
        var content = new VBox(12, installations, actions, creation);
        manager.getDialogPane().setContent(content);
        Dialogs.show(manager, owner, text);
    }

    private void removeSafely(SlicerInstallation installation, Window owner, TranslationService text, Runnable update) {
        var confirmation = new Alert(
                Alert.AlertType.CONFIRMATION,
                text.translate("slicer.installation.removePrompt", installation.name())
                        .orElseThrow());
        confirmation.setTitle(text.translate("slicer.installation.remove").orElseThrow());
        var remove = new ButtonType(
                text.translate("slicer.installation.remove").orElseThrow(), ButtonBar.ButtonData.OK_DONE);
        confirmation.getButtonTypes().setAll(remove, ButtonType.CANCEL);
        confirmation.getDialogPane().lookupButton(remove).setId("slicer-installation-remove-confirm");
        if (Dialogs.show(confirmation, owner, text).orElse(ButtonType.CANCEL) != remove) return;
        try {
            store.remove(installation.id());
            update.run();
        } catch (IOException | IllegalArgumentException | UncheckedIOException | UserConfigException failure) {
            Dialogs.show(
                    new Alert(
                            Alert.AlertType.ERROR,
                            text.translate("slicer.installation.removeFailed", failure.getMessage())
                                    .orElseThrow()),
                    owner,
                    text);
        }
    }

    private void editSafely(
            SlicerProviderRegistry registry,
            String providerId,
            SlicerInstallation existing,
            Window owner,
            TranslationService text,
            java.util.function.Consumer<SlicerInstallation> update) {
        try {
            edit(registry.require(providerId), existing, owner, text).ifPresent(update);
        } catch (IOException | IllegalArgumentException | UncheckedIOException | UserConfigException failure) {
            Dialogs.show(
                    new Alert(
                            Alert.AlertType.ERROR,
                            text.translate("slicer.settings.failed", failure.getMessage())
                                    .orElseThrow()),
                    owner,
                    text);
        }
    }

    private Optional<SlicerInstallation> edit(
            SlicerProvider provider, SlicerInstallation existing, Window owner, TranslationService text)
            throws IOException {
        var descriptor = provider.descriptor();
        var dialog = provider.settingsDialog().orElse(null);
        var name = new dev.voxelbrew.api.model.vui.TextField(
                NAME, text.translate("slicer.installation.name").orElseThrow(), descriptor.name());
        Layout layout = dialog == null
                ? new VerticalLayout(name)
                : new VerticalLayout(name, new dev.voxelbrew.api.model.vui.Separator(), dialog.layout());
        var initial = new HashMap<>(existing == null ? store.providerDefaults(descriptor.id()) : existing.settings());
        if (existing != null) initial.put(NAME, existing.name());
        var others = installations().stream()
                .filter(entry -> existing == null || !entry.id().equals(existing.id()))
                .toList();
        java.util.function.Function<Map<String, String>, Map<String, String>> validateProvider =
                values -> dialog == null ? Map.of() : dialog.validate(providerValues(values));
        return new DesktopExportWindow(owner, null, text)
                .interaction(text.translate("action.save").orElseThrow(), "slicer-input-")
                .checkAction(
                        text.translate("slicer.installation.test").orElseThrow(),
                        validateProvider,
                        values -> text.translate(
                                        "slicer.installation.testPassed",
                                        checkedVersion(provider, providerValues(values), owner, text))
                                .orElseThrow())
                .sourceCaption(id -> text.translate("slicer.input." + id)
                        .orElseGet(() -> text.translate("import.source").orElseThrow()))
                .pathAction(
                        "executable",
                        text.translate("slicer.discovery.action").orElseThrow(),
                        () -> discover(provider, owner, text))
                .show(
                        text.translate("slicer.installation.configure", descriptor.name())
                                .orElseThrow(),
                        layout,
                        values -> {
                            var errors = new LinkedHashMap<String, String>();
                            String proposed = values.getOrDefault(NAME, "").strip();
                            if (proposed.isEmpty()) {
                                errors.put(
                                        NAME,
                                        text.translate("slicer.installation.nameRequired")
                                                .orElseThrow());
                            } else if (others.stream()
                                    .anyMatch(entry -> entry.name().equalsIgnoreCase(proposed))) {
                                errors.put(
                                        NAME,
                                        text.translate("slicer.installation.duplicate")
                                                .orElseThrow());
                            }
                            errors.putAll(validateProvider.apply(values));
                            return errors;
                        },
                        values -> saveCheckedInstallation(provider, existing, values, owner, text),
                        initial,
                        false);
    }

    private Optional<Path> discover(SlicerProvider provider, Window owner, TranslationService text) {
        var dialog = new Dialog<Path>();
        dialog.setTitle(
                text.translate("slicer.discovery.title", provider.descriptor().name())
                        .orElseThrow());
        dialog.getDialogPane().setPrefWidth(640);
        var use = new ButtonType(text.translate("slicer.discovery.select").orElseThrow(), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(use, ButtonType.CANCEL);
        var candidates = new ListView<Path>();
        candidates.setId("slicer-discovered-executables");
        candidates.setPrefHeight(175);
        var status = new Label(text.translate("slicer.discovery.searching").orElseThrow());
        status.setWrapText(true);
        candidates.setPlaceholder(status);
        var choose = dialog.getDialogPane().lookupButton(use);
        choose.setId("slicer-use-executable");
        choose.disableProperty()
                .bind(candidates.getSelectionModel().selectedItemProperty().isNull());
        dialog.setResultConverter(
                button -> button == use ? candidates.getSelectionModel().getSelectedItem() : null);
        dialog.getDialogPane().setContent(candidates);
        var cancellation = new Cancellation();
        worker.execute(() -> {
            try {
                var found = List.copyOf(provider.discoverExecutables(cancellation));
                Platform.runLater(() -> {
                    if (cancellation.cancelled()) return;
                    status.setText(text.translate("slicer.discovery.empty").orElseThrow());
                    candidates.getItems().setAll(found);
                    candidates.getSelectionModel().selectFirst();
                });
            } catch (Exception | LinkageError failure) {
                Platform.runLater(() -> {
                    if (!cancellation.cancelled()) {
                        status.setText(text.translate(
                                        "slicer.discovery.failed",
                                        Objects.toString(failure.getMessage(), failure.toString()))
                                .orElseThrow());
                    }
                });
            }
        });
        try {
            return Dialogs.show(dialog, owner, text);
        } finally {
            cancellation.cancel();
        }
    }

    private SlicerInstallation saveCheckedInstallation(
            SlicerProvider provider,
            SlicerInstallation existing,
            Map<String, String> inputs,
            Window owner,
            TranslationService text) {
        var values = providerValues(inputs);
        String version = checkedVersion(provider, values, owner, text);
        var installation = new SlicerInstallation(
                existing == null ? UUID.randomUUID() : existing.id(),
                provider.descriptor().id(),
                inputs.get(NAME).strip(),
                version,
                "",
                values);
        store.save(installation);
        return installation;
    }

    private String checkedVersion(
            SlicerProvider provider, Map<String, String> settings, Window owner, TranslationService text) {
        var result = check(provider, settings, owner, text)
                .orElseThrow(() -> new IllegalArgumentException(
                        text.translate("slicer.installation.cancelled").orElseThrow()));
        if (!result.failure().isEmpty()) {
            throw new IllegalArgumentException(text.translate("slicer.installation.failed", result.failure())
                    .orElseThrow());
        }
        return result.version();
    }

    private Optional<Check> check(
            SlicerProvider provider, Map<String, String> settings, Window owner, TranslationService text) {
        var progress = new Dialog<Check>();
        progress.setTitle(text.translate("slicer.installation.checking").orElseThrow());
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
                if (version == null || version.isBlank()) {
                    throw new IllegalArgumentException("Slicer did not report a version");
                }
                checked = new Check(version, "");
            } catch (CancellationException cancelled) {
                checked = new Check(
                        "", text.translate("slicer.installation.cancelled").orElseThrow());
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
}
