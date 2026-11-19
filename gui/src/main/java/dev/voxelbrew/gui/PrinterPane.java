package dev.voxelbrew.gui;

import dev.voxelbrew.api.model.CadDocument;
import dev.voxelbrew.api.model.printing.PrintArtifact;
import dev.voxelbrew.api.printer.*;
import dev.voxelbrew.api.printer.driver.*;
import dev.voxelbrew.slicer.SliceService;
import dev.voxelbrew.util.i18n.Translations;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import javafx.animation.*;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.util.Duration;

/** Device workspace. All transport work is serialized away from JavaFX. */
final class PrinterPane extends VBox implements AutoCloseable {
    private static final Object[] OPERATION_LOCKS = new Object[64];

    static {
        Arrays.setAll(OPERATION_LOCKS, index -> new Object());
    }

    private static Object operationLock(Printer printer) {
        var endpoints = printer.driverConfiguration().properties().values().stream()
                .filter(java.net.URI.class::isInstance)
                .map(Object::toString)
                .sorted()
                .toList();
        return OPERATION_LOCKS[
                Math.floorMod(
                        Objects.hash(printer.driverId(), endpoints.isEmpty() ? printer.id() : endpoints),
                        OPERATION_LOCKS.length)];
    }

    static DriverStatus readStatus(Driver driver, Printer printer) throws Exception {
        synchronized (operationLock(printer)) {
            return driver.readPrinterStatus(printer.driverConfiguration());
        }
    }

    static Driver.PrintResult submitArtifact(Driver driver, Printer printer, PrintArtifact artifact) throws Exception {
        var errors = printer.validateArtifact(artifact);
        if (!errors.isEmpty()) return new Driver.PrintResult.Failure(errors);
        synchronized (operationLock(printer)) {
            return driver.print(printer.driverConfiguration(), artifact);
        }
    }

    private record Plate(String id, String name) {}

    private final PrinterStore runtime;
    private final dev.voxelbrew.api.printer.PrinterProfileCatalog profiles;
    private final PrinterVisibility printerVisibility;
    private final Translations text;
    private final ExecutorService worker;
    private final ComboBox<Plate> plates = new ComboBox<>();
    private final Label deviceName = new Label();
    private List<Printer> availableDevices = List.of();
    private Printer selectedDevice;
    private final Label status = new Label(), details = new Label(), message = new Label();
    private final Label connectionDetail = new Label();
    private final Label progressValue = new Label(),
            nozzleValue = new Label(),
            bedValue = new Label(),
            remainingValue = new Label();
    private final ProgressBar transfer = new ProgressBar(0);
    private final VBox monitor = new VBox(16);
    private final Button print, exportGcode;
    private final DriverRegistry providers;
    private final Timeline polling;
    private CadDocument document;
    private Driver session;
    // Accessed only by the serialized device worker, including final cleanup.

    private DriverStatus current = DriverStatus.unknown("");
    private final AtomicLong generation = new AtomicLong();
    private volatile boolean busy;
    private volatile boolean closed;
    private volatile boolean startPending;
    private final AtomicLong jobGeneration = new AtomicLong();

    PrinterPane(
            PrinterStore runtime,
            DriverRegistry providers,
            dev.voxelbrew.api.printer.PrinterProfileCatalog profiles,
            PrinterVisibility printerVisibility,
            Translations text,
            ExecutorService worker) {
        this.runtime = runtime;
        this.providers = providers;
        this.profiles = profiles;
        this.printerVisibility = printerVisibility;
        this.text = text;
        this.worker = worker;
        setId("printer-workspace");
        getStyleClass().add("printer-workspace");
        plates.setId("printer-plate");
        plates.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(Plate plate) {
                return plate == null ? "" : plate.name();
            }

            @Override
            public Plate fromString(String value) {
                return plates.getValue();
            }
        });
        deviceName.setId("printer-device");
        plates.setMaxWidth(Double.MAX_VALUE);
        deviceName.setMaxWidth(Double.MAX_VALUE);
        deviceName.setWrapText(true);
        deviceName.setAlignment(Pos.CENTER_LEFT);
        deviceName.getStyleClass().add("printer-device-name");
        deviceName.setText(t("noDevice"));
        plates.setPromptText(t("platePrompt"));
        plates.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(Plate item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.name());
            }
        });
        var configure = button("configure", this::configure);
        configure.getStyleClass().add("printer-configure");
        exportGcode = button("exportGcode", this::exportGcode);
        print = button("print", this::submit);
        print.getStyleClass().add("printer-primary");
        status.setId("printer-status");
        details.setId("printer-details");
        message.setId("printer-message");
        message.setWrapText(true);
        details.setWrapText(true);
        message.getStyleClass().add("printer-message");
        details.getStyleClass().add("printer-guidance");
        var heading = new Label(t("workspaceTitle"));
        heading.setId("printer-heading");
        heading.getStyleClass().add("printer-title");
        var subtitle = new Label(t("workspaceSubtitle"));
        subtitle.getStyleClass().add("printer-subtitle");
        var introduction = new VBox(5, heading, subtitle);
        var plateStep = step("01", t("plate"), t("plateHint"), plates);
        var deviceStep = step("02", t("device"), t("deviceHint"), deviceName);
        var configureRow = new HBox(configure);
        configureRow.setAlignment(Pos.CENTER_RIGHT);
        var setup = new VBox(22, plateStep, deviceStep, configureRow);
        setup.getStyleClass().add("printer-card");
        var sendTitle = new Label(t("sendTitle"));
        sendTitle.getStyleClass().add("printer-section-title");
        var actions = new HBox(10, exportGcode, print);
        actions.setAlignment(Pos.CENTER_LEFT);
        var send = new VBox(12, sendTitle, details, actions, transfer, message);
        send.getStyleClass().add("printer-card");
        var monitorTitle = new Label(t("monitorTitle"));
        monitorTitle.getStyleClass().add("printer-section-title");
        status.getStyleClass().add("printer-state");
        var statusRow = new HBox(12, monitorTitle, status);
        statusRow.setAlignment(Pos.CENTER_LEFT);
        connectionDetail.getStyleClass().add("printer-guidance");
        connectionDetail.setWrapText(true);
        var metrics = new HBox(
                10,
                metric(t("progress"), progressValue),
                metric(t("nozzle"), nozzleValue),
                metric(t("bed"), bedValue),
                metric(t("remaining"), remainingValue));
        metrics.getStyleClass().add("printer-metrics");
        monitor.getChildren().addAll(statusRow, connectionDetail, metrics);
        monitor.getStyleClass().add("printer-card");
        var content = new VBox(18, introduction, setup, send, monitor);
        content.getStyleClass().add("printer-content");
        content.setMaxWidth(820);
        var canvas = new StackPane(content);
        canvas.setAlignment(Pos.TOP_CENTER);
        canvas.setPadding(new Insets(32, 28, 48, 28));
        var scroll = new ScrollPane(canvas);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("printer-scroll");
        getChildren().add(scroll);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        transfer.setId("printer-transfer");
        transfer.setMaxWidth(Double.MAX_VALUE);
        transfer.setVisible(false);
        transfer.managedProperty().bind(transfer.visibleProperty());
        message.setVisible(false);
        message.managedProperty().bind(message.visibleProperty());
        message.textProperty().addListener((o, old, value) -> message.setVisible(value != null && !value.isBlank()));
        plates.valueProperty().addListener((o, a, b) -> syncDevice(false));
        polling = new Timeline(new KeyFrame(Duration.seconds(2), event -> poll()));
        polling.setCycleCount(Animation.INDEFINITE);
        polling.play();
        reload();
        update();
    }

    QuickMenu quickMenu(
            javafx.scene.Parent theme, java.util.function.Supplier<java.util.List<QuickMenu.Action>> workspaces) {
        return new QuickMenu(
                this,
                theme,
                text,
                () -> {
                    var commands = QuickMenu.controls(this, "printer-configure", "printer-exportGcode");
                    if (print.isVisible()) commands.addAll(QuickMenu.controls(this, "printer-print"));
                    return new QuickMenu.Content(commands, List.of());
                },
                workspaces,
                (dx, dy) -> {},
                target -> {
                    for (var node = target; node != null && node != this; node = node.getParent()) {
                        if (node instanceof javafx.scene.control.Control
                                && !(node instanceof Label)
                                && !(node instanceof ScrollPane)) return false;
                    }
                    return true;
                });
    }

    private String t(String key) {
        return text.text("device." + key);
    }

    private Button button(String key, Runnable action) {
        var button = new Button(t(key));
        button.setId("printer-" + key);
        button.setAccessibleHelp(t(key));
        button.setTooltip(new Tooltip(t(key)));
        button.setOnAction(e -> action.run());
        return button;
    }

    private VBox step(String number, String title, String hint, javafx.scene.Node selector) {
        var index = new Label(number);
        index.getStyleClass().add("printer-step-number");
        var label = new Label(title);
        label.getStyleClass().add("printer-field-title");
        var description = new Label(hint);
        description.getStyleClass().add("printer-field-hint");
        var heading = new HBox(12, index, new VBox(2, label, description));
        heading.setAlignment(Pos.CENTER_LEFT);
        return new VBox(10, heading, selector);
    }

    private VBox metric(String name, Label value) {
        var label = new Label(name);
        label.getStyleClass().add("printer-metric-label");
        value.getStyleClass().add("printer-metric-value");
        var box = new VBox(5, label, value);
        box.getStyleClass().add("printer-metric");
        HBox.setHgrow(box, Priority.ALWAYS);
        box.setMaxWidth(Double.MAX_VALUE);
        return box;
    }

    void documentChanged(CadDocument value) {
        if (document == null
                || !document.gcode().equals(value.gcode())
                || !document.printArtifacts().equals(value.printArtifacts())) jobGeneration.incrementAndGet();
        document = value;
        String selected = plates.getValue() == null
                ? value.printSetup().activePlateId()
                : plates.getValue().id();
        var allPlates = value.printSetup().plates();
        var choices = java.util.stream.IntStream.range(0, allPlates.size())
                .mapToObj(i -> {
                    var plate = allPlates.get(i);
                    return new Plate(
                            plate.id(), plate.name().isBlank() ? t("unnamedPlate") + " " + (i + 1) : plate.name());
                })
                .filter(p -> !value.gcode().getOrDefault(p.id(), List.of()).isEmpty())
                .toList();
        if (!plates.getItems().equals(choices)) {
            plates.getItems().setAll(choices);
        }
        var choice = choices.stream()
                .filter(p -> p.id().equals(selected))
                .findFirst()
                .orElse(choices.isEmpty() ? null : choices.getFirst());
        if (!Objects.equals(plates.getSelectionModel().getSelectedItem(), choice))
            plates.getSelectionModel().select(choice);
        syncDevice(false);
    }

    boolean hasJobs() {
        return !plates.getItems().isEmpty();
    }

    private void syncDevice(boolean reconnect) {
        PrintArtifact artifact = document == null || plates.getValue() == null
                ? null
                : document.printArtifacts().get(plates.getValue().id());
        String target = artifact != null && "printer".equals(artifact.metadata().get("target_kind"))
                ? artifact.metadata().getOrDefault("target_id", "")
                : null;
        var resolved = deviceForJob(artifact, availableDevices);
        deviceName.setText(
                target == null ? t("noDevice") : resolved == null ? t("unavailableDevice") : resolved.name());
        if (reconnect || !Objects.equals(selectedDevice, resolved)) {
            selectedDevice = resolved;
            connect();
        } else update();
    }

    static Printer deviceForJob(PrintArtifact artifact, List<Printer> available) {
        if (artifact == null || !"printer".equals(artifact.metadata().get("target_kind"))) return null;
        String target = artifact.metadata().get("target_id");
        return available.stream()
                .filter(device -> device.id().equals(target))
                .findFirst()
                .orElse(null);
    }

    private void update() {
        exportGcode.setDisable(busy || plates.getValue() == null);
        boolean selected = plates.getValue() != null && selectedDevice != null;
        boolean compatible = selected;
        print.setDisable(busy || !selected || session == null || !current.canStart() || startPending);
        plates.setDisable(busy);
        if (plates.getValue() == null) details.setText(t("choose"));
        else if (selectedDevice == null) {
            var artifact = document.printArtifacts().get(plates.getValue().id());
            details.setText(
                    artifact != null && "printer".equals(artifact.metadata().get("target_kind"))
                            ? t("missingDevice")
                            : t("profileOnly"));
        } else details.setText(compatible ? t("ready") : t("choose"));
        print.setVisible(selectedDevice != null);
        print.setManaged(print.isVisible());
        monitor.setVisible(selectedDevice != null);
        monitor.setManaged(monitor.isVisible());
        status.setText(t("state." + current.state().name().toLowerCase(Locale.ROOT)));
        connectionDetail.setText(!current.filename().isBlank() ? current.filename() : current.detail());
        progressValue.setText(number(current.progress(), 100, "%"));
        nozzleValue.setText(number(current.nozzle(), 1, " °C"));
        bedValue.setText(number(current.bed(), 1, " °C"));
        remainingValue.setText(number(current.remainingSeconds(), 1.0 / 60, " min"));
    }

    private String number(OptionalDouble value, double scale, String unit) {
        return value.isPresent()
                ? String.format(Locale.ROOT, "%.1f%s", value.getAsDouble() * scale, unit)
                : t("unknown");
    }

    private void reload() {
        worker.submit(() -> {
            try {
                var snapshot = runtime.load();
                var loaded = snapshot.devices();
                Platform.runLater(() -> {
                    if (closed) return;
                    availableDevices = List.copyOf(loaded);
                    message.setText(String.join("\n", snapshot.diagnostics()));
                    syncDevice(true);
                });
            } catch (Exception failure) {
                error(failure);
            }
        });
    }

    private void connect() {
        busy = false;
        startPending = false;
        long token = generation.incrementAndGet();
        var device = selectedDevice;
        session = null;
        current = DriverStatus.unknown(t("connecting"));
        update();
        if (device == null) return;
        worker.submit(() -> {
            try {
                var driver = providers
                        .find(device.driverId())
                        .orElseThrow(() -> new IllegalStateException(
                                "Printer '" + device.name() + "' uses missing driver '" + device.driverId()
                                        + "'. Install its plugin or edit this printer configuration."));
                var initial = readStatus(driver, device);
                Platform.runLater(() -> {
                    if (closed || token != generation.get()) return;
                    session = driver;
                    current = initial;
                    update();
                });
            } catch (Exception failure) {
                Platform.runLater(() -> {
                    if (closed || token != generation.get()) return;
                    current = DriverStatus.unknown(t("offline"));
                    message.setText(failure.getMessage());
                    update();
                });
            }
        });
    }

    private void poll() {
        if (closed || busy || session == null) return;
        var connected = session;
        var configuredPrinter = selectedDevice;
        long token = generation.get();
        busy = true;
        worker.submit(() -> {
            DriverStatus next;
            try {
                next = readStatus(connected, configuredPrinter);
            } catch (Exception failure) {
                next = DriverStatus.unknown(t("offline"));
            }
            var result = next;
            Platform.runLater(() -> {
                if (!closed && token == generation.get()) {
                    busy = false;
                    current = result;
                    if (current.state() == DriverStatus.State.PRINTING
                            || current.state() == DriverStatus.State.PAUSED
                            || current.state() == DriverStatus.State.COMPLETE) startPending = false;
                    update();
                }
            });
        });
    }

    private void exportGcode() {
        if (document == null || plates.getValue() == null) return;
        long revision = jobGeneration.get();
        var lines = document.gcode().getOrDefault(plates.getValue().id(), List.of());
        if (lines.isEmpty()) return;
        var artifact = document.printArtifacts().get(plates.getValue().id());
        String format = artifact == null ? "gcode" : artifact.format();
        var chooser = new FileChooser();
        chooser.setTitle(t("exportGcode"));
        chooser.setInitialFileName(plates.getValue().name() + "." + format);
        chooser.getExtensionFilters()
                .add(new FileChooser.ExtensionFilter("Print artifact (*." + format + ")", "*." + format));
        var chosen = chooser.showSaveDialog(getScene().getWindow());
        if (chosen == null) return;
        var path = chosen.toPath();
        boolean overwrite = Files.exists(path);
        if (overwrite) {
            var confirmation = new Alert(
                    Alert.AlertType.CONFIRMATION, text.text("file.replace", path), ButtonType.YES, ButtonType.NO);
            if (Dialogs.show(confirmation, getScene().getWindow(), text).orElse(ButtonType.NO) != ButtonType.YES)
                return;
        }
        try {
            var payload = artifact == null
                    ? PrintArtifact.of(
                            "gcode",
                            (String.join("\n", lines) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8),
                            java.util.Map.of())
                    : artifact;
            SliceService.publish(
                    java.util.Map.of(path, payload),
                    overwrite,
                    new dev.voxelbrew.api.model.printing.Cancellation(),
                    () -> {
                        if (revision != jobGeneration.get()) throw new SliceService.OutdatedException();
                    },
                    () -> {});
        } catch (java.io.IOException | IllegalArgumentException | SliceService.OutdatedException failure) {
            error(failure);
        }
    }

    private void submit() {
        if (busy
                || session == null
                || document == null
                || plates.getValue() == null
                || startPending
                || !current.canStart()) return;
        var job = document;
        var plate = plates.getValue().id();
        var connected = session;
        var configuredPrinter = selectedDevice;
        long token = generation.get();
        busy = true;
        startPending = true;
        transfer.setProgress(-1);
        transfer.setVisible(true);
        update();
        worker.submit(() -> {
            try {
                var result = submitArtifact(
                        connected, configuredPrinter, job.printArtifacts().get(plate));
                Platform.runLater(() -> {
                    if (closed || token != generation.get()) return;
                    if (result instanceof Driver.PrintResult.Failure failure) {
                        startPending = failure.submissionUncertain();
                        message.setText(String.join("\n", failure.messages()));
                    } else message.setText(t("submitted"));
                });
            } catch (Exception failure) {
                // Submission may have reached the printer. Keep start disabled until status reconciles it.
                error(failure);
            } finally {
                Platform.runLater(() -> {
                    if (closed || token != generation.get()) return;
                    busy = false;
                    transfer.setVisible(false);
                    update();
                    poll();
                });
            }
        });
    }

    private void error(Exception failure) {
        Platform.runLater(() -> {
            if (!closed) message.setText(failure.getMessage());
        });
    }

    void configure() {
        var dialog = new Dialog<ButtonType>();
        dialog.setTitle(t("configure"));
        dialog.getDialogPane().setId("device-config-dialog");
        var list = new ComboBox<Printer>();
        list.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(Printer printer) {
                return printer == null ? "" : printer.name();
            }

            @Override
            public Printer fromString(String value) {
                return list.getValue();
            }
        });
        list.getItems().setAll(availableDevices);
        list.setPromptText(t("new"));
        var name = new TextField();
        name.setId("device-config-name");
        var driver = new ComboBox<Driver>();
        driver.setId("device-config-driver");
        driver.getItems().setAll(providers.drivers());
        driver.setPromptText("Choose a driver");
        driver.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(Driver d) {
                return d == null ? "" : d.name();
            }

            @Override
            public Driver fromString(String value) {
                return driver.getValue();
            }
        });
        var profile = new ComboBox<Profile>();
        profile.setId("device-config-profile");
        profile.getItems()
                .setAll(profiles.load().entries().stream()
                        .map(PrinterProfileCatalog.Entry::definition)
                        .toList());
        profile.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(Profile p) {
                return p == null
                        ? ""
                        : p.manufacturer() + " " + p.name()
                                + (list.getValue() != null
                                                && p == list.getValue().profile()
                                        ? " (saved snapshot)"
                                        : "");
            }

            @Override
            public Profile fromString(String value) {
                return profile.getValue();
            }
        });
        var inline = new VBox();
        inline.setId("device-config-fields");
        var error = new Label();
        error.setWrapText(true);
        var form = new DesktopExportWindow.Embedded[1];
        var save = new ButtonType(t("save"), ButtonBar.ButtonData.OK_DONE);
        var remove = new ButtonType(t("remove"), ButtonBar.ButtonData.OTHER);
        dialog.getDialogPane().getButtonTypes().addAll(remove, ButtonType.CANCEL, save);
        var cancelButton = (Button) dialog.getDialogPane().lookupButton(ButtonType.CANCEL);
        var saveButton = (Button) dialog.getDialogPane().lookupButton(save);
        saveButton.setId("device-config-save");
        dialog.getDialogPane().lookupButton(ButtonType.CANCEL).setId("device-config-cancel");
        Runnable validate = () -> {
            boolean valid = driver.getValue() != null
                    && profile.getValue() != null
                    && !name.getText().isBlank()
                    && form[0] != null;
            var errors =
                    valid ? driver.getValue().gui().validate(form[0].values().get()) : Map.<String, String>of();
            saveButton.setDisable(!valid || !errors.isEmpty());
            error.setText(String.join("; ", errors.values()));
        };
        driver.valueProperty().addListener((o, old, value) -> {
            form[0] = null;
            inline.getChildren().clear();
            if (value != null) {
                var existing = list.getValue();
                var initial = existing != null && existing.driverId().equals(value.id())
                        ? existing.driverConfiguration().toInput()
                        : Map.<String, String>of();
                form[0] = new DesktopExportWindow(getScene().getWindow(), null, text)
                        .interaction(t("save"), "device-config-")
                        .embed(value.gui(), initial, validate);
                inline.getChildren().add(form[0].node());
            }
            validate.run();
        });
        name.textProperty().addListener((o, a, b) -> validate.run());
        profile.valueProperty().addListener((o, a, b) -> validate.run());
        list.valueProperty().addListener((o, a, value) -> {
            if (value == null) return;
            name.setText(value.name());
            profile.getItems()
                    .setAll(profiles.load().entries().stream()
                            .map(PrinterProfileCatalog.Entry::definition)
                            .toList());
            if (!profile.getItems().contains(value.profile()))
                profile.getItems().add(value.profile());
            profile.setValue(value.profile());
            driver.setValue(null);
            driver.setValue(providers.find(value.driverId()).orElse(null));
            if (driver.getValue() == null)
                error.setText("Missing driver " + value.driverId() + ". Install its plugin or choose a replacement.");
        });
        var add = new Button(t("new"));
        add.setOnAction(e -> {
            list.setValue(null);
            name.clear();
            profile.setValue(null);
            driver.setValue(null);
        });
        var newProfile = new Button("New profile");
        newProfile.setId("device-profile-new");
        newProfile.setOnAction(e -> ProfileEditor.show(getScene().getWindow(), text, profiles, null)
                .ifPresent(p -> {
                    profile.getItems().add(p);
                    profile.setValue(p);
                }));
        var copyProfile = new Button("Copy / edit profile");
        copyProfile.setId("device-profile-edit");
        copyProfile.setOnAction(e -> ProfileEditor.show(getScene().getWindow(), text, profiles, profile.getValue())
                .ifPresent(p -> {
                    profile.getItems().removeIf(existing -> existing.id().equals(p.id()));
                    profile.getItems().add(p);
                    profile.setValue(p);
                }));
        var content = new VBox(
                10,
                new HBox(8, list, add),
                deviceField(new Label(t("name")), name),
                deviceField(new Label(t("profile")), profile),
                new HBox(8, newProfile, copyProfile),
                deviceField(new Label("Driver"), driver),
                inline,
                error);
        content.setId("device-config-content");
        var scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(580);
        dialog.getDialogPane().setContent(scroll);
        dialog.getDialogPane().setPrefWidth(600);
        saveButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            event.consume();
            try {
                var old = list.getValue();
                var next = new Printer(
                        old == null ? UUID.randomUUID().toString() : old.id(),
                        name.getText(),
                        driver.getValue().id(),
                        driver.getValue()
                                .gui()
                                .buildConfiguration(form[0].values().get()),
                        profile.getValue());
                saveButton.setDisable(true);
                content.setDisable(true);
                cancelButton.setDisable(true);
                worker.submit(() -> {
                    try {
                        runtime.save(next);
                        Platform.runLater(() -> {
                            reload();
                            cancelButton.setDisable(false);
                            cancelButton.fire();
                        });
                    } catch (Exception failure) {
                        Platform.runLater(() -> {
                            content.setDisable(false);
                            cancelButton.setDisable(false);
                            error.setText(failure.getMessage());
                            validate.run();
                        });
                    }
                });
            } catch (RuntimeException failure) {
                error.setText(failure.getMessage());
            }
        });
        dialog.getDialogPane().lookupButton(remove).addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            event.consume();
            if (list.getValue() == null) return;
            var selected = list.getValue();
            worker.submit(() -> {
                try {
                    runtime.remove(selected.id());
                    Platform.runLater(() -> {
                        reload();
                        cancelButton.fire();
                    });
                } catch (Exception failure) {
                    error(failure);
                }
            });
        });
        validate.run();
        Dialogs.show(dialog, getScene().getWindow(), text);
    }

    private static VBox deviceField(Label label, Control input) {
        label.getStyleClass().add("device-config-label");
        input.setMaxWidth(Double.MAX_VALUE);
        var field = new VBox(6, label, input);
        field.getStyleClass().add("device-config-field");
        field.setMaxWidth(Double.MAX_VALUE);
        return field;
    }

    @Override
    public void close() {
        closed = true;
        generation.incrementAndGet();
        jobGeneration.incrementAndGet();
        polling.stop();
    }
}
