package dev.voxelbrew.gui;

import dev.voxelbrew.api.exporter.ExporterProviderRegistry;
import dev.voxelbrew.api.i18n.TranslationService;
import dev.voxelbrew.api.model.*;
import dev.voxelbrew.api.model.mesh.MeshData;
import dev.voxelbrew.api.model.parameters.*;
import dev.voxelbrew.api.model.sketch.SketchGeometry.Entity;
import dev.voxelbrew.api.userconfig.UserConfigException;
import dev.voxelbrew.engine.feature.FeatureRegistry;
import dev.voxelbrew.engine.model.*;
import dev.voxelbrew.engine.parameters.*;
import dev.voxelbrew.engine.sketch.FaceProjection;
import dev.voxelbrew.engine.sketch.SketchProfiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.scene.shape.SVGPath;
import javafx.stage.Popup;
import javafx.stage.Stage;
import javafx.util.Duration;
import lombok.Getter;
import lombok.experimental.Accessors;

/** CAD workbench: model state stays in engine; scene graph and tool previews stay here. */
@lombok.extern.slf4j.Slf4j
public final class Workbench extends BorderPane implements AutoCloseable {
    private static final String EXPORT_NOTIFICATION_DURATION_KEY = "export.notification.duration-ms";

    private static final int DEFAULT_EXPORT_NOTIFICATION_DURATION_MS = 3000;

    private static final List<String> STANDARD_VIEWS =
            List.of("Isometric", "Top", "Front", "Right", "Back", "Left", "Bottom");

    private final Stage stage;

    private final WindowFrame window;

    private final javafx.beans.value.ChangeListener<Boolean> windowFocus;

    @Getter
    @Accessors(fluent = true)
    private final DocumentController controller;

    private final Path pluginDirectory;

    private final ProjectBackups backups;
    private final javafx.animation.Timeline backupTimer;

    private final ExporterProviderRegistry registry;

    private final java.util.function.Supplier<dev.voxelbrew.api.importer.ImporterProviderRegistry> importers;

    @Getter
    @Accessors(fluent = true)
    private final Viewport viewport;

    private final PrintingPane printing;

    private final PrinterPane printer;

    private final Tab printerTab;

    private final Tab designTab;

    private final Tab printTab;

    private final TabPane workspaces;

    private final TreeView<Entry> tree = new TreeView<>();
    private final TreeItem<Entry> operationRows = new TreeItem<>();
    private final TreeItem<Entry> sketchRows = new TreeItem<>();
    private final TreeItem<Entry> bodyRows = new TreeItem<>();

    private final VBox inspector = new VBox(12), form = new VBox(9);

    private final VBox featureDiagnostics = new VBox(6);

    private final FlowPane toolActions = new FlowPane(8, 5);

    private final Label status, error, selectionLabel;

    private final FeatureTimeline timeline;

    private final Button undo, redo;

    private final MenuItem export;

    private final javafx.beans.property.BooleanProperty fileBusy = new javafx.beans.property.SimpleBooleanProperty();

    private final Label documentTitle = new Label();

    private final Button customFeature;

    private final Button parameters;

    private final ComboBox<String> configuration = new ComboBox<>();

    private final Button constrainedSketch;

    private final Button extrude;

    private final Button cut;

    private final Button splitBody;

    private final Button fillet;

    private final Button chamfer;

    private final Button shell;

    private final ProgressIndicator progress = new ProgressIndicator();

    private final Appearance appearance;

    private final ExecutorService preferencesWorker;

    private final TranslationService translations;

    private final dev.voxelbrew.api.userconfig.UserConfigService configurationStore;

    private final RecentFiles recentFiles;

    private final WindowResources resources;

    private final FeatureRegistry.Factory featureProviders;

    private final SketchController.Factory sketches;

    private final McpServerHost mcpServer;

    private final java.util.Set<Integer> modificationFaces = new java.util.TreeSet<>();

    private final java.util.Set<Integer> modificationEdges = new java.util.TreeSet<>();

    private final Map<String, TextField> fields = new LinkedHashMap<>();

    private final Map<String, Boolean> validSketchProfiles = new HashMap<>();

    private final List<QuickMenu> quickMenus = new ArrayList<>();

    private final UpdateDialog.Factory updates;

    private final java.util.function.Supplier<AboutDialog> about;

    private ConstructionEditor constructionEditor;

    private dev.voxelbrew.api.model.parameters.ParameterSet lastParameters =
            dev.voxelbrew.api.model.parameters.ParameterSet.empty();

    private Appearance.Choice appearanceChoice;

    private long appearanceRequest;

    private boolean closed, refreshing;

    private Popup exportNotification;

    private int exportNotificationDurationMillis = DEFAULT_EXPORT_NOTIFICATION_DURATION_MS;

    private Tool tool = Tool.NONE;

    private String modification;

    private String modificationBody;

    private Label modificationSelection;

    private String selectedFeature, selectedBody;

    private BodyStyleDraft bodyStyleDraft;

    private List<Feature> lastFeatures = List.of();

    private Map<String, BodyStyle> lastBodyStyles = Map.of();

    private Map<String, String> lastBodyNames = Map.of();

    private Map<String, String> lastFeatureNames = Map.of();

    private boolean lastRebuilding;

    private boolean lastIoBusy;

    private DocumentController.Build lastBuild;

    private String lastDocument = "";

    private boolean fitNext = true;

    private Feature.Sketch extrusionSketch;

    private ModelSession.Snapshot planeSnapshot;
    private long draftRevision;
    private long draftGeneration;

    private Feature.Sketch planeRepair;

    private Button finish;

    private record BodyStyleDraft(String bodyId, BodyStyle initial, TextField material, ColorPicker color) {
        boolean changed() {
            return !Objects.equals(initial.material(), material.getText())
                    || !javafx.scene.paint.Color.web(initial.color()).equals(color.getValue());
        }

        BodyStyle value() {
            var c = color.getValue();
            String rgb = String.format(
                    Locale.ROOT,
                    "#%02X%02X%02X",
                    Math.round(c.getRed() * 255),
                    Math.round(c.getGreen() * 255),
                    Math.round(c.getBlue() * 255));
            return new BodyStyle(material.getText(), rgb);
        }
    }

    private record Entry(String id, String label, boolean body) {
        @Override
        public String toString() {
            return label;
        }
    }

    private enum Tool {
        NONE,
        PLANE,
        EXTRUDE,
        EDIT_PROFILE,
        MODIFY,
        OPERATION,
        CONSTRUCTION
    }

    private record ProfileChoices(CheckBox whole, Map<CheckBox, String> regions) {
        List<String> selectedIds() {
            return regions.entrySet().stream()
                    .filter(e -> e.getKey().isSelected())
                    .map(Map.Entry::getValue)
                    .toList();
        }

        String profileId(Feature.Sketch sketch) {
            return whole.isSelected()
                    ? sketch.profileId()
                    : SketchProfiles.selectedProfileId(sketch.profileId(), selectedIds());
        }

        void onChange(Runnable update) {
            whole.setOnAction(event -> {
                if (whole.isSelected()) regions.keySet().forEach(box -> box.setSelected(false));
                update.run();
            });
            regions.keySet()
                    .forEach(box -> box.setOnAction(event -> {
                        whole.setSelected(false);
                        update.run();
                    }));
        }
    }

    private final class BodyTreeCell extends TreeCell<Entry> {
        private final ContextMenu menu = new ContextMenu();
        private final ContextMenu featureMenu = new ContextMenu();
        private TextField editor;
        private Label validation;
        private ModelSession.Snapshot editingSnapshot;
        private Entry editingEntry;
        private boolean renameRequested;

        BodyTreeCell() {
            var rename = new MenuItem(translations.translate("body.rename"));
            rename.setId("body-rename");
            UiHelp.menuItem(rename, translations.translate("help.body-rename"));
            rename.setOnAction(event -> {
                var item = getTreeItem();
                Platform.runLater(() -> {
                    if (item == getTreeItem()) requestRename();
                });
            });
            var fit = new MenuItem(translations.translate("body.fit"));
            fit.setId("body-fit");
            UiHelp.menuItem(fit, translations.translate("help.body-fit"));
            fit.setOnAction(event -> {
                var entry = getItem();
                if (entry == null || entry.id() == null) return;
                var mesh = meshes().get(entry.id());
                if (mesh != null) viewport.fit(Map.of(entry.id(), mesh));
            });
            menu.getItems().addAll(rename, fit);
            UiHelp.menu(menu);
            var featureRename = new MenuItem(translations.translate("body.rename"));
            featureRename.setId("feature-rename");
            featureRename.setOnAction(event -> {
                var item = getTreeItem();
                Platform.runLater(() -> {
                    if (item == getTreeItem()) requestRename();
                });
            });
            var featureFit = new MenuItem(translations.translate("body.fit"));
            featureFit.setId("feature-fit");
            featureFit.setOnAction(event -> {
                var entry = getItem();
                if (entry == null) return;
                var matching = new LinkedHashMap<String, MeshData>();
                controller.build().bodies().stream()
                        .filter(body -> body.output().featureId().equals(entry.id()))
                        .forEach(body -> matching.put(body.id(), body.mesh()));
                viewport.fit(matching.isEmpty() ? meshes() : matching);
            });
            featureMenu.getItems().addAll(featureRename, featureFit);
            UiHelp.menu(featureMenu);
            featureMenu.setOnShowing(event -> {
                tree.getSelectionModel().select(getTreeItem());
                featureRename.setDisable(!canRename());
                featureFit.setDisable(controller.rebuilding()
                        || !controller.build().valid()
                        || controller.build().bodies().isEmpty());
            });
            menu.setOnShowing(event -> {
                tree.getSelectionModel().select(getTreeItem());
                rename.setDisable(!canRename());
                fit.setDisable(tool != Tool.NONE
                        || controller.rebuilding()
                        || !controller.build().valid());
            });
            setOnMouseClicked(this::activateRow);
        }

        private void activateRow(MouseEvent event) {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2 && canRename()) {
                if (getTreeItem().getParent() == sketchRows) editSelectedFeature();
                else requestRename();
                event.consume();
            }
        }

        private boolean canRename() {
            var entry = getItem();
            return entry != null
                    && entry.id() != null
                    && tool == Tool.NONE
                    && (entry.body()
                            ? controller.canExport()
                                    && controller.build().bodies().stream()
                                            .anyMatch(body -> body.id().equals(entry.id()))
                            : !controller.ioBusy()
                                    && !controller.rebuilding()
                                    && !controller.hasPreview()
                                    && controller.snapshot().document().features().stream()
                                            .anyMatch(feature -> feature.id().equals(entry.id())));
        }

        private void requestRename() {
            renameRequested = true;
            try {
                tree.edit(getTreeItem());
            } finally {
                renameRequested = false;
            }
        }

        @Override
        protected void updateItem(Entry entry, boolean empty) {
            super.updateItem(entry, empty);
            if (!isEditing()) {
                setText(empty || entry == null ? null : entry.label());
                setGraphic(
                        empty || entry == null || getTreeItem() == null
                                ? null
                                : getTreeItem().getGraphic());
            }
            if (empty || entry == null || entry.id() == null) setContextMenu(null);
            else setContextMenu(entry.body() ? menu : featureMenu);
            if (empty) {
                menu.hide();
                featureMenu.hide();
            }
        }

        @Override
        public void startEdit() {
            if (isEditing()) return;
            // TreeView's default single-click editing can resize/scroll a selected row
            // between the two clicks, moving the second click onto a different item.
            if (!renameRequested || !canRename()) {
                if (tree.getEditingItem() == getTreeItem()) tree.edit(null);
                return;
            }
            super.startEdit();
            if (!isEditing()) return;
            editingEntry = getItem();
            editingSnapshot = controller.snapshot();
            editor = new TextField(
                    editingEntry.body()
                            ? editingEntry.label()
                            : controller
                                    .snapshot()
                                    .document()
                                    .featureName(editingEntry.id(), featureBaseName(editingEntry.id())));
            editor.setId(editingEntry.body() ? "body-rename-field" : "feature-rename-field");
            editor.setMinWidth(0);
            editor.getStyleClass().add("body-rename-field");
            editor.setAccessibleText(translations.translate("body.name"));
            UiHelp.install(editor, translations);
            validation = new Label();
            validation.setId("body-rename-error");
            validation.setWrapText(true);
            validation.getStyleClass().add("error");
            validation.managedProperty().bind(validation.textProperty().isNotEmpty());
            validation.visibleProperty().bind(validation.managedProperty());
            var content = new VBox(3, editor, validation);
            content.prefWidthProperty()
                    .bind(javafx.beans.binding.Bindings.max(50, widthProperty().subtract(44)));
            setText(null);
            setGraphic(content);
            editor.setOnAction(event -> {
                applyName();
                event.consume();
            });
            editor.textProperty().addListener((observable, before, after) -> {
                validation.setText("");
                editor.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("invalid"), false);
            });
            var input = editor;
            input.focusedProperty().addListener((observable, before, focused) -> {
                if (!focused) {
                    Platform.runLater(() -> {
                        if (isEditing() && editor == input && !input.isFocused()) tree.edit(null);
                    });
                }
            });
            focusEditor(true);
        }

        private void focusEditor(boolean selectAll) {
            Platform.runLater(() -> {
                if (isEditing()) {
                    tree.applyCss();
                    tree.layout();
                    tree.scrollTo(getIndex());
                    editor.requestFocus();
                    if (selectAll) editor.selectAll();
                }
            });
        }

        private void applyName() {
            if (!isEditing()) return;
            String name = editor.getText();
            try {
                CadDocument.requireBodyName(name);
                name = name.strip();
            } catch (IllegalArgumentException invalid) {
                validation.setText(translations.translate("body.rename.invalid"));
                editor.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("invalid"), true);
                focusEditor(false);
                return;
            }
            var current = controller.snapshot();
            boolean currentBody = canRename()
                    && current.revision() == editingSnapshot.revision()
                    && current.document().id().equals(editingSnapshot.document().id());
            var original = editingEntry;
            String previousName = original.body()
                    ? original.label()
                    : editingSnapshot.document().featureName(original.id(), featureBaseName(original.id()));
            tree.edit(null);
            if (currentBody && !name.equals(previousName)) {
                String accepted = name;
                attempt(() -> {
                    if (original.body()) controller.renameBody(original.id(), accepted);
                    else {
                        controller.renameFeature(original.id(), accepted);
                    }
                });
            }
        }

        @Override
        public void cancelEdit() {
            super.cancelEdit();
            editingEntry = null;
            editingSnapshot = null;
            setText(getItem() == null ? null : getItem().label());
            setGraphic(getTreeItem() == null ? null : getTreeItem().getGraphic());
        }
    }

    public static Workbench create(Stage stage, Path plugins) throws IOException {
        return create(
                stage,
                plugins,
                dev.voxelbrew.util.di.ApplicationModule.system(
                        dev.voxelbrew.util.update.ApplicationImage.Component.GUI),
                new dev.voxelbrew.gui.FileIoModule());
    }

    static Workbench create(Stage stage, Path plugins, dev.voxelbrew.util.update.UpdateServiceFactory updates)
            throws IOException {
        return create(
                stage,
                plugins,
                dev.voxelbrew.util.di.ApplicationModule.system(updates),
                new dev.voxelbrew.gui.FileIoModule());
    }

    static Workbench create(
            Stage stage,
            Path plugins,
            dev.voxelbrew.util.di.ApplicationModule application,
            dev.voxelbrew.gui.FileIoModule files)
            throws IOException {
        var graph = GuiComponent.factory().create(stage, plugins, application, files);
        WindowResources resources;
        try {
            resources = graph.resources();
        } catch (java.io.UncheckedIOException failure) {
            throw failure.getCause();
        }
        try {
            return graph.workbench();
        } catch (RuntimeException | Error failure) {
            resources.close();
            throw failure;
        }
    }

    private static Label heading(String text) {
        var label = new Label(text);
        label.getStyleClass().add("eyebrow");
        return label;
    }

    private static void fire(MenuItem item) {
        if (!item.isDisable()) item.fire();
    }

    private static double number(TextField field, String name) {
        try {
            double value = field instanceof FormulaField formula
                    ? formula.value()
                    : Double.parseDouble(field.getText().trim());
            if (!Double.isFinite(value)) throw new NumberFormatException();
            field.getStyleClass().remove("invalid");
            return value;
        } catch (IllegalArgumentException failure) {
            if (!field.getStyleClass().contains("invalid")) {
                field.getStyleClass().add("invalid");
            }
            throw new IllegalArgumentException(name + ": " + failure.getMessage(), failure);
        }
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static String numericText(double value) {
        var format = java.text.NumberFormat.getNumberInstance();
        format.setMaximumFractionDigits(15);
        return format.format(value);
    }

    static int parseNotificationDuration(String value) {
        int millis = Integer.parseInt(value.strip());
        if (millis <= 0) throw new IllegalArgumentException("Notification duration must be positive");
        return millis;
    }

    static int loadNotificationDuration(dev.voxelbrew.api.userconfig.UserConfigService configuration) {
        try {
            return parseNotificationDuration(configuration
                    .readUserConfig(EXPORT_NOTIFICATION_DURATION_KEY)
                    .orElse(Integer.toString(DEFAULT_EXPORT_NOTIFICATION_DURATION_MS)));
        } catch (UserConfigException | IllegalArgumentException failure) {
            log.warn("Could not load export notification duration; using 3000 ms", failure);
            return DEFAULT_EXPORT_NOTIFICATION_DURATION_MS;
        }
    }

    static Path exportSuggestion(Path saveFolder, Path projectPath) {
        return projectPath == null ? saveFolder.resolve("Untitled.vxb") : projectPath;
    }

    private static boolean canCut(Feature.Sketch sketch) {
        return sketch.profile() instanceof Feature.Circle || sketch.profile() instanceof Feature.Constrained;
    }

    Workbench(
            Stage stage,
            Path pluginDirectory,
            DocumentController controller,
            McpServerHost mcpServer,
            ExporterProviderRegistry registry,
            java.util.function.Supplier<dev.voxelbrew.api.importer.ImporterProviderRegistry> importers,
            PrintingPane printing,
            PrinterPane printer,
            SlicerSettings slicerSettings,
            Appearance appearance,
            Rendering rendering,
            ExecutorService preferencesWorker,
            TranslationService translations,
            dev.voxelbrew.api.userconfig.UserConfigService configurationStore,
            RecentFiles recentFiles,
            UpdateDialog.Factory updates,
            java.util.function.Supplier<AboutDialog> about,
            WindowResources resources,
            FeatureRegistry.Factory featureProviders,
            SketchController.Factory sketches) {
        this.stage = stage;
        ApplicationIcon.install(stage);
        this.pluginDirectory = pluginDirectory;
        this.controller = controller;
        this.mcpServer = mcpServer;
        mcpServer.busy(() -> closed || tool != Tool.NONE || isDisabled());
        this.registry = registry;
        this.importers = importers;
        this.printing = printing;
        printing.onExport3mf(() -> exportDocument("3mf"));
        this.printer = printer;
        printer.setOnExported(this::showExportSuccess);
        printing.onConfigureDevices(printer::configure);
        this.appearance = appearance;
        this.preferencesWorker = preferencesWorker;
        this.translations = translations;
        this.configurationStore = configurationStore;
        ProjectBackups.Settings backupSettings;
        try {
            backupSettings = ProjectBackups.Settings.load(configurationStore);
        } catch (UserConfigException failure) {
            log.warn("Could not read autosave preferences", failure);
            backupSettings = new ProjectBackups.Settings(true, 5, 10);
        }
        backups = new ProjectBackups(controller, configurationStore.backupsFolder(), backupSettings);
        controller.projectOpener(this::openWithRecovery);
        backupTimer = new javafx.animation.Timeline(
                new javafx.animation.KeyFrame(Duration.seconds(1), event -> backups.check(System.nanoTime())));
        backupTimer.setCycleCount(javafx.animation.Animation.INDEFINITE);
        exportNotificationDurationMillis = loadNotificationDuration(configurationStore);
        this.recentFiles = recentFiles;
        this.viewport = new Viewport(translations);
        this.viewport.setRendering(rendering.load());
        this.updates = updates;
        this.about = about;
        this.resources = resources;
        this.featureProviders = featureProviders;
        this.sketches = sketches;
        this.status = new Label(translations.translate("status.ready"));
        this.error = new Label();
        this.selectionLabel = new Label(translations.translate("status.noSelection"));
        this.undo = button(translations.translate("action.undo"), "undo", () -> {
            cancelTool();
            controller().undo();
        });
        this.redo = button(translations.translate("action.redo"), "redo", () -> {
            cancelTool();
            controller().redo();
        });
        this.export = menuItem(
                translations.translate("action.export"),
                "export",
                true,
                () -> ExportDialog.choose(stage(), registry(), translations, this::exportDocument));
        this.customFeature = button(
                translations.tryTranslate("action.features").orElseThrow(), "custom-feature", this::customFeature);
        this.parameters =
                button(translations.tryTranslate("action.parameters").orElseThrow(), "parameters", this::parameters);
        this.constrainedSketch = button(
                translations.tryTranslate("action.sketch").orElseThrow(),
                "constrained-sketch",
                () -> beginPlaneSelection(null));
        this.extrude =
                button(translations.tryTranslate("action.extrude").orElseThrow(), "extrude", this::beginExtrusion);
        this.cut = button(translations.tryTranslate("action.cut").orElseThrow(), "cut", this::beginCut);
        this.splitBody =
                button(translations.tryTranslate("action.split").orElseThrow(), "split-body", this::beginSplit);
        this.fillet =
                button(translations.tryTranslate("action.fillet").orElseThrow(), "fillet", () -> beginFinish(true));
        this.chamfer =
                button(translations.tryTranslate("action.chamfer").orElseThrow(), "chamfer", () -> beginFinish(false));
        this.shell = button(translations.tryTranslate("action.shell").orElseThrow(), "shell", this::beginShell);
        appearanceChoice = appearance.load();
        setId("workbench");
        disabledProperty().addListener((observable, previous, disabled) -> {
            if (disabled && exportNotification != null) exportNotification.hide();
        });
        featureDiagnostics.setId("feature-diagnostics");
        getStylesheets()
                .add(Objects.requireNonNull(getClass().getResource("workbench.css"))
                        .toExternalForm());
        UiText.choices(
                configuration, name -> name.equals("Default") ? translations.translate("configuration.default") : name);
        var fileMenu = menu("menu-file");
        var newItem = menuItem(translations.translate("action.new"), "new", true, this::newDocument);
        var openItem = menuItem(translations.translate("action.open"), "open", true, this::open);
        var recentMenu = recentMenu();
        var saveItem = menuItem(translations.translate("action.save"), "save", true, () -> save(false));
        var importItem = menuItem(
                translations.translate("action.import"), "import", true, () -> requestChange(this::importFile));
        var saveAsItem = menuItem(translations.translate("action.saveAs"), "save-as", true, () -> save(true));
        for (var item : List.of(newItem, openItem, importItem, saveItem, saveAsItem)) {
            item.disableProperty().bind(disabledProperty().or(fileBusy));
        }
        export.disableProperty().bind(disabledProperty().or(fileBusy));
        fileMenu.getItems()
                .addAll(
                        newItem,
                        openItem,
                        recentMenu,
                        importItem,
                        new SeparatorMenuItem(),
                        saveItem,
                        saveAsItem,
                        new SeparatorMenuItem(),
                        export,
                        new SeparatorMenuItem(),
                        menuItem(translations.translate("action.exit"), "exit", true, this::exitApplication));
        var editMenu = menu("menu-edit");
        editMenu.getItems()
                .addAll(mirror(undo, true), mirror(redo, true), new SeparatorMenuItem(), mirror(parameters, true));
        var viewMenu = viewMenu(rendering);
        var settingsMenu = menu("menu-settings");
        {
            try {
                mcpServer.credentials(
                        dev.voxelbrew.util.services.Services.load(dev.voxelbrew.api.userconfig.UserConfigService.class)
                                .homeFolder()
                                .resolve("mcp-token"));
            } catch (java.io.IOException failure) {
                log.warn("Could not load persistent MCP credentials; using a window-only token", failure);
            }
        }
        settingsMenu
                .getItems()
                .add(menuItem(
                        translations.translate("mcp.title"),
                        "mcp-server",
                        false,
                        () -> McpDialog.show(stage, mcpServer, configurationStore, translations)));
        settingsMenu
                .getItems()
                .addAll(
                        menuItem(
                                translations.translate("language.label"),
                                "language",
                                false,
                                () -> LanguageDialog.show(stage, translations, configurationStore, preferencesWorker)),
                        menuItem(
                                translations.translate("exportNotification.duration.title"),
                                "export-notification-duration",
                                false,
                                this::configureExportNotificationDuration),
                        menuItem(translations.translate("autosave.title"), "autosave", false, this::configureBackups),
                        menuItem(translations.translate("action.updates"), "updates", false, this::updates),
                        menuItem(
                                translations.translate("action.report-bug"),
                                "report-bug",
                                false,
                                dev.voxelbrew.util.BugReport::open),
                        menuItem(
                                translations.translate("support.title"),
                                "support",
                                false,
                                () -> SupportDialog.show(stage, translations)),
                        menuItem(translations.translate("about.title"), "about", false, () -> {
                            if (about.get().show(stage)) updates();
                        }),
                        new SeparatorMenuItem(),
                        menuItem(
                                translations.translate("shortcuts.title"),
                                "keyboard-shortcuts",
                                true,
                                () -> KeyboardShortcuts.show(stage, translations)));
        var slicersMenu = menu("menu-slicer");
        slicersMenu
                .getItems()
                .add(menuItem(
                        translations.translate("slicer.settings.title"),
                        "slicer-settings",
                        false,
                        () -> slicerSettings.configure(
                                resources.slicers, stage, translations, printing::reloadSlicers)));
        var printersMenu = menu("menu-printers");
        printersMenu
                .getItems()
                .add(menuItem(
                        translations.translate("device.configure"),
                        "printers-configure-devices",
                        false,
                        printing::configureDevices));
        printersMenu
                .getItems()
                .addAll(
                        menuItem(
                                translations.translate("print.exportPrinters"),
                                "printers-export-defaults",
                                false,
                                printing::exportDefaultPrinters),
                        menuItem(
                                translations.translate("print.openPrinters"),
                                "printers-open-folder",
                                false,
                                printing::openPrintersFolder),
                        menuItem(
                                translations.translate("print.reloadPrinters"),
                                "printers-reload",
                                false,
                                printing::reloadPrinters),
                        menuItem(
                                translations.translate("print.visiblePrinters"),
                                "printers-visibility",
                                false,
                                printing::manageVisiblePrinters));
        var menuBar = new MenuBar(fileMenu, editMenu, viewMenu, slicersMenu, printersMenu, settingsMenu);
        menuBar.setId("main-menu");
        menuBar.disableProperty().bind(disabledProperty());
        menuBar.setMinWidth(Region.USE_PREF_SIZE);
        UiHelp.menuBar(menuBar);
        var brand = new Label("VoxelBrew");
        brand.getStyleClass().add("brand");
        brand.setMinWidth(Region.USE_PREF_SIZE);
        documentTitle.setId("document-title");
        documentTitle.getStyleClass().add("document-title");
        var documentIcon = new javafx.scene.shape.SVGPath();
        documentIcon.setContent("M 3 1 L 11 1 L 15 5 L 15 17 L 3 17 Z M 11 1 L 11 5 L 15 5");
        documentIcon.getStyleClass().add("document-icon");
        documentTitle.setGraphic(documentIcon);
        documentTitle.setGraphicTextGap(10);
        documentTitle.setTextOverrun(OverrunStyle.CENTER_ELLIPSIS);
        documentTitle.setMinWidth(0);
        documentTitle.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(documentTitle, Priority.ALWAYS);
        var title = new HBox(12, brand, documentTitle, menuBar);
        title.getStyleClass().add("title-strip");
        window = new WindowFrame(stage, title, this, translations);
        var sketchIcon = OperationIcons.icon("constrained-sketch", javafx.scene.paint.Color.WHITE);
        if (constrainedSketch.getGraphic() == null) {
            constrainedSketch.setGraphic(sketchIcon);
        } else {
            var iconAndCaption = new HBox(sketchIcon, constrainedSketch.getGraphic());
            iconAndCaption.setAlignment(Pos.CENTER_LEFT);
            iconAndCaption.spacingProperty().bind(constrainedSketch.graphicTextGapProperty());
            iconAndCaption.setMouseTransparent(true);
            constrainedSketch.setGraphic(iconAndCaption);
        }
        var sketchCommands = new HBox(2, constrainedSketch);
        var solidCommands = new MenuButton(translations.translate("action.operations"));
        solidCommands.setId("operations-menu");
        var operationsIcon = new javafx.scene.shape.SVGPath();
        operationsIcon.setContent("M2 6L10 2L18 6V15L10 19L2 15Z M2 6L10 10L18 6 M10 10V19");
        operationsIcon.setFill(javafx.scene.paint.Color.TRANSPARENT);
        operationsIcon.setStroke(javafx.scene.paint.Color.web("#658dcc"));
        operationsIcon.setStrokeWidth(1.5);
        operationsIcon.setMouseTransparent(true);
        solidCommands.setGraphic(operationsIcon);
        UiHelp.menu(solidCommands, translations);
        populateSolidCommands(solidCommands);
        sketchCommands.getStyleClass().add("command-group");
        var constructionMenu = constructionMenu();
        var toolbar = new FlowPane(10, 6, sketchCommands, solidCommands, constructionMenu);
        toolbar.setId("modeling-toolbar");
        toolbar.getStyleClass().add("command-strip");
        constrainedSketch.getStyleClass().add("primary");
        var left = modelPanel();
        inspector.getStyleClass().addAll("side-panel", "properties-panel");
        inspector.setPrefWidth(270);
        inspector.setMinWidth(240);
        error.setWrapText(true);
        error.setId("tool-error");
        error.getStyleClass().add("error");
        selectionLabel.getStyleClass().add("selection-title");
        selectionLabel.setWrapText(true);
        var header = new VBox(5, heading(translations.translate("gui.properties")), selectionLabel);
        header.getStyleClass().add("inspector-header");
        form.getStyleClass().add("inspector-form");
        toolActions.getStyleClass().add("inspector-actions");
        toolActions.managedProperty().bind(javafx.beans.binding.Bindings.isNotEmpty(toolActions.getChildren()));
        var scroll = new ScrollPane(form);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        error.managedProperty().bind(error.textProperty().isNotEmpty());
        error.visibleProperty().bind(error.managedProperty());
        inspector.getChildren().addAll(header, scroll, error, toolActions);
        var center = new BorderPane(viewport);
        center.getStyleClass().add("viewport-panel");
        var split = new SplitPane(left, center, inspector);
        split.setDividerPositions(0.18, 0.78);
        var design = new BorderPane(split);
        design.setId("design-workspace");
        design.setTop(toolbar);
        designTab = new Tab(translations.translate("tab.Designer"), design);
        designTab.setId("workspace-design");
        designTab.setClosable(false);
        printTab = new Tab(translations.translate("tab.Slicer"), printing);
        printTab.setId("workspace-print");
        printTab.setClosable(false);
        printerTab = new Tab(translations.translate("tab.Printer"), printer);
        printerTab.setId("workspace-printer");
        printerTab.setClosable(false);
        printerTab.setDisable(true);
        workspaces = new TabPane(designTab, printTab, printerTab);
        configureWorkspaceTabs();
        setCenter(workspaces);
        quickMenus.add(viewport.quickMenu(this, this::designQuickMenu, this::quickWorkspaces));
        quickMenus.add(printing.quickMenu(this, this::quickWorkspaces));
        quickMenus.add(printer.quickMenu(this, this::quickWorkspaces));
        timeline = new FeatureTimeline(
                translations,
                position -> attempt(() -> controller.cursor(position)),
                this::selectFeature,
                this::editSelectedFeature,
                this::shortName,
                (from, to) -> attempt(() -> controller.deleteFeatures(from, to)));
        progress.setPrefSize(16, 16);
        progress.setMaxSize(16, 16);
        status.setId("status");
        status.setWrapText(true);
        var statusSpacer = new Region();
        HBox.setHgrow(statusSpacer, Priority.ALWAYS);
        var statusbar = new HBox(
                10, progress, status, statusSpacer, new Label(translations.translate("gui.mm_occt_7_9_grid_10_mm")));
        statusbar.getStyleClass().add("status-strip");
        design.setBottom(new VBox(timeline, statusbar));
        installViewportSelection();
        controller.onChanged(() -> {
            refresh();
            if (!closed) backups.check(System.nanoTime());
        });
        backupTimer.play();
        windowFocus = (o, old, focused) -> {
            if (!closed && focused && appearanceChoice == Appearance.Choice.SYSTEM) applyAppearance();
        };
        stage.focusedProperty().addListener(windowFocus);
        installShortcuts(menuBar, newItem, openItem, importItem, saveItem, saveAsItem, constructionMenu, solidCommands);
        stage.setOnCloseRequest(e -> {
            e.consume();
            requestChange(this::closeWindow);
        });
        stage.getProperties().put(Workbench.class, this);
        applyAppearance();
        refresh();
    }

    private Stage stage() {
        return stage;
    }

    private void closeWindow() {
        close();
        stage.hide();
    }

    private void exitApplication() {
        stage.toFront();
        if (isDisabled()) return;
        requestChange(() -> {
            closeWindow();
            javafx.stage.Window.getWindows().stream()
                    .map(open -> open.getProperties().get(Workbench.class))
                    .filter(Workbench.class::isInstance)
                    .map(Workbench.class::cast)
                    .findFirst()
                    .ifPresent(Workbench::exitApplication);
        });
    }

    javafx.scene.Scene createScene(double width, double height) {
        return new javafx.scene.Scene(window, width, height);
    }

    private ExporterProviderRegistry registry() {
        return registry;
    }

    private Button button(String text, String id, Runnable action) {
        var button = new Button(text);
        button.setId(id);
        UiHelp.install(button, translations);
        button.setOnAction(e -> attempt(action));
        return button;
    }

    private Menu menu(String id) {
        var menu = new Menu(translations.translate(id));
        menu.setId(id);
        UiHelp.menu(menu, translations);
        return menu;
    }

    private MenuItem menuItem(String text, String id, boolean shortcut, Runnable action) {
        var item = new MenuItem(text);
        item.setId(id);
        item.disableProperty().bind(disabledProperty());
        if (shortcut) KeyboardShortcuts.menuAccelerator(id).ifPresent(item::setAccelerator);
        UiHelp.menuItem(item, UiHelp.description(translations, id));
        item.setOnAction(e -> attempt(action));
        return item;
    }

    private MenuItem mirror(Button button, boolean shortcut) {
        var item = menuItem(button.getText(), "menu-" + button.getId(), shortcut, button::fire);
        item.disableProperty().bind(disabledProperty().or(button.disabledProperty()));
        return item;
    }

    private List<QuickMenu.Action> quickWorkspaces() {
        return workspaces.getTabs().stream()
                .map(tab -> new QuickMenu.Action(
                        tab.getId(),
                        tab.getText(),
                        "▣",
                        tab.disableProperty(),
                        workspaces.getSelectionModel().selectedItemProperty().isEqualTo(tab),
                        () -> {
                            if (!tab.isDisable()) workspaces.getSelectionModel().select(tab);
                        }))
                .toList();
    }

    private QuickMenu.Content designQuickMenu() {
        var radial = new ArrayList<>(List.of(
                QuickMenu.Action.button(constrainedSketch, "✎"),
                QuickMenu.Action.button(extrude, "⇧"),
                QuickMenu.Action.button(cut, "⊖"),
                QuickMenu.Action.button(fillet, "◜"),
                QuickMenu.Action.button(chamfer, "◩")));
        radial.add(new QuickMenu.Action(
                "fit",
                translations.translate("action.fit"),
                "⛶",
                disabledProperty(),
                null,
                () -> viewport.fit(meshes())));
        radial.add(QuickMenu.Action.button(undo, "↶"));
        radial.add(QuickMenu.Action.button(redo, "↷"));
        var additional = new ArrayList<>(List.of(
                QuickMenu.Action.button(parameters, "ƒ"),
                QuickMenu.Action.button(shell, "□"),
                QuickMenu.Action.button(splitBody, "◫"),
                QuickMenu.Action.menu(export, "⇥")));
        for (var node : toolActions.getChildren()) {
            if (node instanceof ButtonBase button) additional.add(QuickMenu.Action.button(button, "✓"));
        }
        return new QuickMenu.Content(radial, additional);
    }

    private void newDocument() {
        chooseProjectWindow(null, () -> {
            cancelTool();
            selectedFeature = null;
            selectedBody = null;
            fitNext = true;
            controller.newDocument();
        });
    }

    private void importFile() {
        try {
            var providers = importers.get().withDirectory(pluginDirectory);
            boolean started = false;
            try {
                var choice = ImportDialog.choose(stage, providers, translations);
                if (choice.isPresent()) {
                    cancelTool();
                    controller
                            .importDocument(
                                    providers.require(choice.get().id()),
                                    new DesktopImportWindow(stage, null, translations)
                                            .importerVersion(choice.get().version()))
                            .whenComplete((value, failure) -> {
                                try {
                                    providers.close();
                                } catch (IOException closeFailure) {
                                    log.warn("Closing importer registry", closeFailure);
                                }
                            })
                            .exceptionally(failure -> null);
                    started = true;
                }
            } finally {
                if (!started) providers.close();
            }
        } catch (IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
    }

    private void attempt(Runnable action) {
        try {
            error.setText("");
            action.run();
        } catch (RuntimeException failure) {
            error.setText(Objects.toString(failure.getMessage(), failure.toString()));
        }
    }

    private Map<String, MeshData> meshes() {
        var result = new LinkedHashMap<String, MeshData>();
        controller.build().bodies().forEach(b -> result.put(b.id(), b.mesh()));
        return result;
    }

    private void moveWorkspace(int direction) {
        var tabs = workspaces.getTabs();
        for (int index = workspaces.getSelectionModel().getSelectedIndex() + direction;
                index >= 0 && index < tabs.size();
                index += direction) {
            if (tabs.get(index).isDisable()) continue;
            workspaces.getSelectionModel().select(index);
            return;
        }
    }

    private void selectWorkspaceNumber(KeyEvent event) {
        int index = event.getCode().getCode() - KeyCode.DIGIT1.getCode();
        if (index < 0 || index >= workspaces.getTabs().size()) return;
        if (!workspaces.getTabs().get(index).isDisable()) {
            workspaces.getSelectionModel().select(index);
        }
    }

    private void refresh() {
        if (closed) return;
        refreshing = true;
        try {
            var snapshot = controller.snapshot();
            var document = snapshot.document();
            refreshWorkspaceTabs(document, snapshot.revision());
            var effective = controller.effectiveFeatures();
            boolean buildStateChanged = lastBuild != controller.build()
                    || lastRebuilding != controller.rebuilding()
                    || lastIoBusy != controller.ioBusy();
            boolean changed = featuresChanged(document, effective);
            if (changed) validSketchProfiles.clear();
            refreshBodies(document, changed);
            var styles = new LinkedHashMap<String, BodyStyle>();
            controller.build().bodies().forEach(b -> styles.put(b.id(), document.bodyStyle(b.id())));
            viewport.setBodyStyles(styles);
            synchronizeTreeSelection();
            if (tool != Tool.EDIT_PROFILE) {
                viewport.showSketches(effective.subList(0, document.cursor()), selectedFeature);
            }
            if (tool != Tool.NONE) {
                selectionLabel.setGraphic(null);
                selectionLabel.setTooltip(null);
            }
            if (tool == Tool.NONE && (changed || selectedBody != null && buildStateChanged)) inspect();
            lastRebuilding = controller.rebuilding();
            lastIoBusy = controller.ioBusy();
            lastFeatures = effective;
            lastBodyStyles = document.bodyStyles();
            lastBodyNames = document.bodyNames();
            lastFeatureNames = document.featureNames();
            lastParameters = document.parameters();
            lastDocument = document.id();
            refreshCommands(document, snapshot);
            refreshSolidCommands();
            refreshSketchCommands(document);
            refreshStatus();
        } finally {
            refreshing = false;
        }
    }

    private String shortName(Feature feature) {
        String customName = controller.snapshot().document().featureNames().get(feature.id());
        if (customName != null) return customName;
        return defaultFeatureName(feature);
    }

    private String featureBaseName(String id) {
        return controller.snapshot().document().features().stream()
                .filter(feature -> feature.id().equals(id))
                .findFirst()
                .map(this::defaultFeatureName)
                .orElse(id);
    }

    private String defaultFeatureName(Feature feature) {
        if (CutTargets.through(feature)) return translations.translate("action.cut");
        if (BodyPattern.matches(feature)) {
            return patternName(BodyPattern.from(feature).kind());
        }
        if (BodyExtrusion.modifiesBody(feature)) {
            return translations.translate("gui.extrude") + " · "
                    + translations.translate(
                            "extrude." + BodyExtrusion.from(feature).operation());
        }
        if (feature instanceof ConstructionFeature construction) {
            return translations.translate("construction." + construction.tool().name());
        }
        if (feature instanceof Feature.Custom custom) {
            return translations.translate("feature.custom", custom.type());
        }
        if (feature instanceof Feature.EdgeFinish finish) {
            return translations.translate(
                    finish.kind() == Feature.EdgeFinish.Kind.FILLET ? "action.fillet" : "action.chamfer");
        }
        if (feature instanceof Feature.Shell) {
            return translations.translate("action.shell");
        }
        if (feature instanceof Feature.Sketch s) {
            if (s.profile() instanceof Feature.Circle) {
                return translations.translate("gui.circle");
            }
            return translations.translate(s.profile() instanceof Feature.Constrained ? "gui.sketch" : "gui.rectangle");
        }
        if (feature instanceof Feature.Extrude) {
            return translations.translate("gui.extrude");
        }
        return translations.translate(feature instanceof Feature.SplitBody ? "gui.split" : "action.cut");
    }

    private void rebuildTree(CadDocument document) {
        tree.edit(null);
        var selectedRow = tree.getSelectionModel().getSelectedItem();
        boolean keepSketchRow = selectedRow != null && selectedRow.getParent() == sketchRows;
        var operations = new ArrayList<TreeItem<Entry>>();
        var sketches = new ArrayList<TreeItem<Entry>>();
        var bodies = new ArrayList<TreeItem<Entry>>();
        var diagnostics = new HashMap<String, String>();
        for (var result : controller.build().diagnostics()) {
            if (result.state() != Evaluation.State.OK) {
                diagnostics.putIfAbsent(result.featureId(), "  [" + result.state() + "]");
            }
        }
        for (int i = 0; i < document.features().size(); i++) {
            var feature = document.features().get(i);
            String diagnostic = diagnostics.getOrDefault(feature.id(), "");
            String label = document.featureName(feature.id(), defaultFeatureName(feature));
            var item = new TreeItem<>(new Entry(feature.id(), (i + 1) + "  " + label + diagnostic, false));
            item.setGraphic(FeatureTimeline.featureIcon(feature));
            operations.add(item);
            if (feature instanceof Feature.Sketch) {
                var sketchRow = new TreeItem<>(
                        new Entry(feature.id(), document.featureName(feature.id(), feature.id()) + diagnostic, false),
                        FeatureTimeline.featureIcon(feature));
                sketches.add(sketchRow);
            }
        }
        for (var body : controller.build().bodies()) {
            bodies.add(new TreeItem<>(
                    new Entry(
                            body.id(),
                            controller
                                    .build()
                                    .labels()
                                    .label(body.id(), document, i -> translations.translate("feature.body", i)),
                            true),
                    OperationIcons.icon("new-body")));
        }
        operationRows.getChildren().setAll(operations);
        sketchRows.getChildren().setAll(sketches);
        bodyRows.getChildren().setAll(bodies);
        if (keepSketchRow && sketchRows.isExpanded()) {
            sketches.stream()
                    .filter(row -> row.getValue().id().equals(selectedFeature))
                    .findFirst()
                    .ifPresent(tree.getSelectionModel()::select);
        }
    }

    private void synchronizeTreeSelection() {
        if (tree.getRoot() == null) return;
        String id = selectedBody == null ? selectedFeature : selectedBody;
        var current = tree.getSelectionModel().getSelectedItem();
        if (id != null
                && current != null
                && current.getValue().body() == (selectedBody != null)
                && id.equals(current.getValue().id())) {
            return;
        }
        for (var group : tree.getRoot().getChildren()) {
            if (!group.isExpanded()) continue;
            for (var item : group.getChildren()) {
                if (item.getValue().body() == (selectedBody != null)
                        && item.getValue().id().equals(id)) {
                    tree.getSelectionModel().select(item);
                    return;
                }
            }
        }
        tree.getSelectionModel().clearSelection();
    }

    private String bodyLabel(String id) {
        return controller
                .build()
                .labels()
                .label(id, controller.snapshot().document(), i -> translations.translate("feature.body", i));
    }

    private Feature selected() {
        return controller.effectiveFeatures().stream()
                .filter(f -> f.id().equals(selectedFeature))
                .findFirst()
                .orElse(null);
    }

    private void selectFeature(String id) {
        if (tool != Tool.NONE) return;
        timeline.stop();
        selectedFeature = id;
        selectedBody = null;
        viewport.select(null);
        viewport.showSketches(
                controller
                        .effectiveFeatures()
                        .subList(0, controller.snapshot().document().cursor()),
                id);
        inspect();
        var snapshot = controller.snapshot();
        var document = snapshot.document();
        boolean wasRefreshing = refreshing;
        refreshing = true;
        try {
            synchronizeTreeSelection();
            refreshCommands(document, snapshot);
            refreshSketchCommands(document);
        } finally {
            refreshing = wasRefreshing;
        }
    }

    private void selectBody(String id, int face) {
        timeline.stop();
        selectedBody = id;
        controller.build().bodies().stream()
                .filter(b -> b.id().equals(id))
                .findFirst()
                .ifPresent(b -> selectedFeature = b.output().featureId());
        viewport.select(id);
        printing.selectedFace(id, face);
        inspect();
        if (face >= 0) {
            selectionLabel.setText(translations.translate("feature.face", face) + " · " + bodyLabel(id));
        }
        refresh();
    }

    private void inspect() {
        if (tool != Tool.NONE) return;
        bodyStyleDraft = null;
        form.getChildren().clear();
        toolActions.getChildren().clear();
        fields.clear();
        error.setText("");
        var feature = selected();
        selectionLabel.setGraphic(feature == null ? null : FeatureTimeline.featureIcon(feature));
        selectionLabel.setTooltip(feature == null ? null : new Tooltip(feature.id()));
        if (feature == null) {
            selectionLabel.setText(translations.translate("gui.start_a_design"));
            var hint = new Label(
                    translations.translate(
                            "gui.draw_a_rectangle_or_circle_on_xy_then_extrude_its_closed_profile_middle_drag_to_orbit_shift_mid"));
            hint.setWrapText(true);
            form.getChildren().add(hint);
            return;
        }
        selectionLabel.setText(selectedBody == null ? shortName(feature) : bodyLabel(selectedBody));
        if (selectedBody != null) bodyStyleEditor(selectedBody);
        inspectFeature(feature);
    }

    private void inspectSketch(Feature.Sketch s) {
        sketchNameEditor(s);
        if (s.profile() instanceof Feature.Rectangle r) {
            dimension(s, "x", r.x());
            dimension(s, "y", r.y());
            dimension(s, "width", r.width());
            dimension(s, "height", r.height());
        } else if (s.profile() instanceof Feature.Circle c) {
            dimension(s, "centerX", c.centerX());
            dimension(s, "centerY", c.centerY());
            dimension(s, "diameter", c.diameter());
        } else if (s.profile() instanceof Feature.Constrained c) {
            var info = new Label(translations.translate(
                    "feature.sketchSummary",
                    c.geometry().entities().size(),
                    c.geometry().constraints().size()));
            info.setWrapText(true);
            form.getChildren().add(info);
            var hint = new Label(translations.translate(
                    "gui.edit_sketch_to_change_driving_dimensions_constraints_or_the_profile_boundary"));
            hint.setWrapText(true);
            form.getChildren().add(hint);
        }
        form.getChildren()
                .add(button(
                        translations.translate("gui.edit_sketch_constraints"),
                        "edit-sketch-constraints",
                        () -> editSketch(s)));

        var plane = sketchPlaneLabel(s);
        var changePlane =
                button(translations.translate("plane.change"), "change-sketch-plane", () -> beginPlaneSelection(s));
        form.getChildren().add(new FlowPane(6, 4, plane, changePlane));
    }

    private void inspectFeature(Feature feature) {
        if (feature instanceof ConstructionFeature construction) {
            var details = new InspectorDetails();
            for (var input : construction.inputs()) {
                details.row(
                        translations.translate("construction.input"),
                        ConstructionEditor.inputLabel(input, controller, translations));
            }
            var valueKey = ConstructionEditor.valueKey(construction.tool());
            if (valueKey != null) details.row(translations.translate(valueKey), numericText(construction.value()));
            form.getChildren().add(details);
            var edit = new Button(translations.translate("construction.edit"));
            edit.setId("construction-edit");
            edit.setOnAction(event -> beginConstruction(construction.tool(), construction));
            form.getChildren().add(edit);
        } else if (feature instanceof Feature.Sketch s) {
            inspectSketch(s);
        } else if (feature instanceof Feature.Extrude || BodyExtrusion.modifiesBody(feature)) {
            var definition = BodyExtrusion.from(feature);
            dimension(feature, "distance", definition.extrusion().distance());
            referenceEditor(feature, definition.extrusion().profile(), definition.target());
        } else if (feature instanceof Feature.EdgeFinish f) {
            dimension(feature, "size", f.size());
            referenceEditor(feature, null, f.body());
        } else if (feature instanceof Feature.Shell s) {
            dimension(feature, "thickness", s.thickness());
            referenceEditor(feature, null, s.body());
        } else if (CutTargets.through(feature)) {
            inspectThroughCut(feature);
        } else if (feature instanceof Feature.SplitBody s) {
            dimension(feature, "offset", s.plane().offset());
            referenceEditor(feature, null, s.body());
        } else if (BodyPattern.matches(feature)) {
            var pattern = BodyPattern.from(feature);
            var details = new InspectorDetails();
            details.row(translations.translate("gui.input_body"), targetLabel(pattern.source()));
            boolean circular = pattern.kind() == BodyPattern.Kind.CIRCULAR;
            details.row(
                    translations.translate(circular ? "pattern.axis" : "pattern.plane"),
                    BodyPatternEditor.axisName(pattern.axis(), circular));
            for (var dim : FeatureDimensions.list(feature)) {
                var caption =
                        BodyPatternEditor.caption(dim.target().field(), pattern.kind(), pattern.axis(), translations);
                details.row(caption, numericText(dim.value()));
            }
            form.getChildren().add(details);
            form.getChildren()
                    .add(button(
                            translations.translate("parameter.edit"),
                            "edit-body-pattern",
                            () -> editBodyPattern(pattern.kind(), feature)));
        } else if (feature instanceof Feature.Custom custom) {
            inspectPlugin(custom);
        }
        form.getChildren().add(featureDiagnostics);
        updateFeatureDiagnostics();
    }

    private void inspectPlugin(Feature.Custom custom) {
        var metadata = new Label(custom.providerId() + "/" + custom.type() + " · payload v" + custom.payloadVersion());
        metadata.setWrapText(true);
        form.getChildren().add(metadata);
        try (var features = featureProviders.open(pluginDirectory)) {
            var values = features.parameters(custom);
            for (var parameter : features.descriptor(custom).parameters()) {
                editableDimension(
                        parameter.key(),
                        parameter.label(),
                        values.get(parameter.key()),
                        FormulaField.unit(parameter.unit()),
                        input -> controller.edit(custom.id(), parameter.key(), input.declaredValue()));
            }
        } catch (IOException | RuntimeException failure) {
            var message = new Label(failure.getMessage());
            message.setWrapText(true);
            message.getStyleClass().add("error");
            form.getChildren().add(message);
        }
        form.getChildren().add(button(translations.translate("gui.reload_plugin_and_rebuild"), "custom-rebuild", () -> {
            inspect();
            controller.rebuild();
        }));
    }

    private void bodyStyleEditor(String bodyId) {
        var document = controller.snapshot().document();
        var style = document.bodyStyle(bodyId);
        var material = new ComboBox<String>();
        material.setId("body-material");
        material.getItems().setAll("PLA", "PETG", "ABS", "ASA", "TPU");
        material.setEditable(true);
        material.getEditor().setId("body-material-name");
        material.setValue(style.material());
        material.setPromptText(translations.translate("body.unassigned"));
        material.getEditor().setPromptText(translations.translate("body.unassigned"));
        material.setMaxWidth(Double.MAX_VALUE);
        UiHelp.install(material, translations);
        UiHelp.describe(material.getEditor(), translations.translate("help.body-material"));
        var color = new ColorPicker(javafx.scene.paint.Color.web(style.color()));
        color.setId("body-color");
        color.setMaxWidth(Double.MAX_VALUE);
        UiHelp.install(color, translations);
        var draft = new BodyStyleDraft(bodyId, style, material.getEditor(), color);
        bodyStyleDraft = draft;
        var apply = button(
                translations.translate("gui.apply"),
                "body-style-apply",
                () -> controller.bodyStyle(bodyId, draft.value()));
        var reset = button(
                translations.translate("body.reset"), "body-style-reset", () -> controller.bodyStyle(bodyId, null));
        reset.setDisable(!document.bodyStyles().containsKey(bodyId));
        material.getEditor().setOnAction(e -> apply.fire());
        var materialLabel = new Label(translations.translate("body.material"));
        materialLabel.setLabelFor(material);
        var colorLabel = new Label(translations.translate("body.color"));
        colorLabel.setLabelFor(color);
        var hint = new Label(translations.translate("body.cadHint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("muted");
        var content = new VBox(
                9,
                InspectorDetails.field(materialLabel, material),
                InspectorDetails.field(colorLabel, color),
                new HBox(6, apply, reset),
                hint);
        // Properties remain readable while rebuilding; editing requires a current body.
        content.setDisable(
                !controller.canExport() || controller.build().bodies().stream().noneMatch(b -> b.id().equals(bodyId)));
        var section = new TitledPane(translations.translate("body.appearance"), content);
        section.setCollapsible(false);
        form.getChildren().add(section);
    }

    private void updateFeatureDiagnostics() {
        featureDiagnostics.getChildren().clear();
        controller.build().diagnostics().stream()
                .filter(d -> d.featureId().equals(selectedFeature) && d.state() != Evaluation.State.OK)
                .forEach(d -> {
                    var label = new Label(d.state() + ": " + d.message());
                    label.setWrapText(true);
                    label.getStyleClass().add("error");
                    featureDiagnostics.getChildren().add(label);
                });
    }

    private void dimension(Feature feature, String name, double value) {
        String expression = controller
                .snapshot()
                .document()
                .parameters()
                .binding(new dev.voxelbrew.api.model.parameters.ParameterSet.Target(feature.id(), name));
        var field = editableDimension(
                name, translations.tryTranslate("dimension." + name).orElse(name), value, ParameterUnit.MM, input -> {
                    String text = input.expression().strip();
                    if (text.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")) {
                        controller.edit(feature.id(), name, number(input, name));
                    } else {
                        controller.expression(feature.id(), name, text);
                    }
                });
        if (!expression.isEmpty()) field.setExpression(expression);
    }

    private FormulaField editableDimension(
            String key,
            String caption,
            double initial,
            ParameterUnit unit,
            java.util.function.Consumer<FormulaField> commit) {
        var input = numericField("dimension-" + key, initial, unit);
        var apply = button(translations.translate("gui.apply"), "apply-" + key, () -> commit.accept(input));
        input.setOnAction(e -> apply.fire());
        input.setMinWidth(0);
        HBox.setHgrow(input, Priority.ALWAYS);
        apply.setMinWidth(Region.USE_PREF_SIZE);
        var label = new Label(caption);
        label.setLabelFor(input);
        var row = new HBox(6, input, apply);
        row.setAlignment(Pos.CENTER_LEFT);
        form.getChildren().add(InspectorDetails.field(label, row));
        return input;
    }

    private void customFeature() {
        cancelTool();
        try {
            var editor = new CustomFeatureEditor(pluginDirectory, featureProviders, formulaParameters(), translations);
            form.getChildren().setAll(editor);
            var apply = operationPanel(translations.translate("gui.add_feature"), "custom-add", () -> {
                var feature = editor.feature();
                controller.add(feature);
                selectedFeature = feature.id();
                selectedBody = feature.id() + ".body";
                fitNext = true;
            });
            apply.setDisable(!editor.available());
            toolActions.getChildren().getLast().setId("custom-cancel");
        } catch (IOException failure) {
            error.setText(failure.getMessage());
        }
    }

    private void parameters() {
        cancelTool();
        ParameterDialog.show(
                        stage,
                        controller.effectiveFeatures(),
                        controller.snapshot().document().parameters(),
                        translations)
                .ifPresent(controller::parameters);
    }

    private String sketchReferenceName(String id) {
        return controller.snapshot().document().featureName(id, id);
    }

    private String planeName(Feature.Plane plane) {
        var document = controller.snapshot().document();
        if (!plane.constructionSupport().isEmpty()) {
            String id = plane.constructionSupport();
            return document.featureName(id, featureBaseName(id));
        }
        if (plane.support() != null) {
            var source = plane.support().body();
            String bodyId =
                    controller.build().bodyIds().getOrDefault(source, source.featureId() + "." + source.outputName());
            String featureName = document.featureNames().get(source.featureId());
            if (controller.build().labels().isCopy(bodyId)) return bodyLabel(bodyId);
            return document.bodyName(bodyId, featureName == null ? bodyLabel(bodyId) : featureName);
        }
        return plane.isOrigin() ? plane.id().substring("origin-".length()).toUpperCase(Locale.ROOT) : plane.id();
    }

    private Label sketchPlaneLabel(Feature.Sketch sketch) {
        var label = new Label(translations.translate("feature.plane", planeName(sketch.plane())));
        label.setId("sketch-plane-name");
        label.setWrapText(true);
        return label;
    }

    private void sketchNameEditor(Feature.Sketch sketch) {
        var name = new TextField(sketchReferenceName(sketch.id()));
        name.setId("sketch-name");
        var label = new Label(translations.translate("gui.name"));
        label.setLabelFor(name);
        var rename = button(translations.translate("body.rename"), "rename-sketch", () -> {
            if (!name.getText().strip().equals(sketchReferenceName(sketch.id()))) {
                controller.renameFeature(sketch.id(), name.getText());
            }
        });
        name.setOnAction(event -> rename.fire());
        HBox.setHgrow(name, Priority.ALWAYS);
        form.getChildren().add(InspectorDetails.field(label, new HBox(6, name, rename)));
    }

    private ComboBox<String> sketchInput(List<Feature> earlier, Feature.ProfileRef profile, VBox container) {
        var sketches = new ComboBox<String>();
        sketches.setId("repair-sketch");
        UiText.choices(sketches, this::sketchReferenceName);
        earlier.stream()
                .filter(f -> f instanceof Feature.Sketch)
                .forEach(f -> sketches.getItems().add(f.id()));
        if (profile != null) {
            container
                    .getChildren()
                    .add(InspectorDetails.field(new Label(translations.translate("gui.input_sketch")), sketches));
            var plane = new Label();
            plane.setId("sketch-plane-name");
            plane.setWrapText(true);
            sketches.valueProperty().addListener((o, previous, id) -> {
                plane.setText(earlier.stream()
                        .filter(Feature.Sketch.class::isInstance)
                        .map(Feature.Sketch.class::cast)
                        .filter(s -> s.id().equals(id))
                        .map(s -> translations.translate("feature.plane", planeName(s.plane())))
                        .findFirst()
                        .orElse(""));
            });
            sketches.setValue(profile.sketchId());
            container.getChildren().add(plane);
        }
        return sketches;
    }

    private void inspectThroughCut(Feature feature) {
        var sketch = CutTargets.firstThroughCut(feature).profile().sketchId();
        var details = new InspectorDetails();
        details.row(translations.translate("gui.input_sketch"), sketchReferenceName(sketch));
        var targets = new VBox(4);
        targets.setId("cut-target-list");
        for (var target : CutTargets.throughTargets(feature).bodies()) {
            var name = new Label(targetLabel(target));
            name.setWrapText(true);
            targets.getChildren().add(name);
        }
        details.row(translations.translate("gui.bodies"), targets);
        form.getChildren()
                .addAll(
                        details,
                        button(
                                translations.translate("parameter.edit"),
                                "edit-cut-profile",
                                () -> beginProfileEdit(feature, sketch)));
    }

    private String targetLabel(Feature.BodyRef target) {
        return bodyLabel(
                controller.build().bodyIds().getOrDefault(target, target.featureId() + "." + target.outputName()));
    }

    private void referenceEditor(Feature feature, Feature.ProfileRef profile, Feature.BodyRef body) {
        var earlier = controller.snapshot().document().features().stream()
                .takeWhile(f -> !f.id().equals(feature.id()))
                .toList();
        referenceSummary(feature, profile, body, earlier);
        var inputs = new VBox(9);
        var sketches = sketchInput(earlier, profile, inputs);
        referenceControls(feature, profile, body, earlier, inputs, sketches);
    }

    private void referenceSummary(
            Feature feature, Feature.ProfileRef profile, Feature.BodyRef body, List<Feature> earlier) {
        var details = new InspectorDetails();
        if (profile != null) {
            details.row(translations.translate("gui.input_sketch"), sketchReferenceName(profile.sketchId()));
            earlier.stream()
                    .filter(f -> f instanceof Feature.Sketch && f.id().equals(profile.sketchId()))
                    .map(Feature.Sketch.class::cast)
                    .findFirst()
                    .ifPresent(s -> details.row(translations.translate("pattern.plane"), planeName(s.plane())));
        }
        if (body != null) {
            var targets = BodyExtrusion.modifiesBody(feature)
                    ? BodyExtrusion.from(feature).targets()
                    : List.of(body);
            details.row(
                    translations.translate("gui.bodies"),
                    targets.stream().map(this::targetLabel).collect(java.util.stream.Collectors.joining("\n")));
        }
        form.getChildren().add(details);
    }

    private void referenceControls(
            Feature feature,
            Feature.ProfileRef profile,
            Feature.BodyRef body,
            List<Feature> earlier,
            VBox inputs,
            ComboBox<String> sketches) {
        var bodies = new ComboBox<String>();
        bodies.setId("repair-body");
        var output =
                FormulaField.text(body == null ? "body" : body.outputName(), this::formulaParameters, translations);
        output.setId("repair-output");
        boolean extrusion = feature instanceof Feature.Extrude || BodyExtrusion.modifiesBody(feature);
        var operation =
                extrusion ? extrusionOperation(BodyExtrusion.from(feature).operation()) : null;
        if (extrusion) {
            form.getChildren()
                    .add(button(
                            translations.translate("extrude.editProfile"),
                            "edit-extrusion-profile",
                            () -> beginProfileEdit(feature, sketches.getValue())));
            inputs.getChildren()
                    .add(InspectorDetails.field(new Label(translations.translate("extrude.operation")), operation));
        }
        earlier.stream()
                .filter(f -> !(f instanceof Feature.Sketch || f instanceof ConstructionFeature))
                .forEach(f -> bodies.getItems().add(f.id()));
        if (body != null) bodies.setValue(body.featureId());
        else bodies.getSelectionModel().selectLast();
        if (extrusion) {
            bodies.disableProperty().bind(operation.valueProperty().isEqualTo(BodyExtrusion.Operation.NEW_BODY));
            output.disableProperty().bind(bodies.disableProperty());
        }
        var targetFields = new VBox(
                8,
                InspectorDetails.field(new Label(translations.translate("gui.input_body_feature")), bodies),
                InspectorDetails.field(new Label(translations.translate("inspector.output")), output));
        if (extrusion) {
            targetFields.visibleProperty().bind(bodies.disableProperty().not());
            targetFields.managedProperty().bind(targetFields.visibleProperty());
        }
        inputs.getChildren().add(targetFields);
        var additional = historicalCutTargets(feature, earlier, bodies, output, operation);
        if (additional != null) inputs.getChildren().add(additional);
        var disclosure = new TitledPane(translations.translate("inspector.editInputs"), inputs);
        disclosure.setId("operation-references");
        disclosure.setAnimated(false);
        disclosure.setExpanded(!controller.build().valid());
        form.getChildren().add(disclosure);
        inputs.getChildren()
                .add(button(translations.translate(extrusion ? "gui.apply" : "gui.repair_inputs"), "repair", () -> {
                    boolean useTarget =
                            extrusion ? operation.getValue() != BodyExtrusion.Operation.NEW_BODY : body != null;
                    var target = useTarget
                            ? new Feature.BodyRef(
                                    Objects.requireNonNull(
                                            bodies.getValue(), translations.translate("gui.choose_an_earlier_body")),
                                    output.evaluatedText())
                            : null;
                    replaceInputs(
                            feature,
                            earlier,
                            profile,
                            sketches.getValue(),
                            target,
                            extrusion ? operation.getValue() : null,
                            additional == null ? List.of() : additional.selected());
                }));
    }

    private AdditionalCutTargets historicalCutTargets(
            Feature feature,
            List<Feature> earlier,
            ComboBox<String> bodies,
            FormulaField output,
            ComboBox<BodyExtrusion.Operation> operation) {
        if (operation == null) return null;
        var choices = historicalBodies(earlier);
        var selected = BodyExtrusion.from(feature).additionalTargets();
        var picker = new AdditionalCutTargets(
                choices,
                () -> bodies.getValue() == null ? null : new Feature.BodyRef(bodies.getValue(), output.evaluatedText()),
                selected,
                translations);
        picker.visibleProperty().bind(operation.valueProperty().isEqualTo(BodyExtrusion.Operation.CUT));
        return picker;
    }

    private Map<Feature.BodyRef, String> historicalBodies(List<Feature> earlier) {
        var ids = controller.build().bodyIds();
        var byFeature = ids.entrySet().stream()
                .collect(java.util.stream.Collectors.groupingBy(e -> e.getKey().featureId()));
        var active = new LinkedHashMap<String, Feature.BodyRef>();
        for (var step : earlier) {
            if (step instanceof Feature.SplitBody split) active.remove(ids.get(split.body()));
            byFeature.getOrDefault(step.id(), List.of()).stream()
                    .sorted(Map.Entry.comparingByKey(Comparator.comparing(Feature.BodyRef::outputName)))
                    .forEach(e -> active.put(e.getValue(), e.getKey()));
        }
        var choices = new LinkedHashMap<Feature.BodyRef, String>();
        active.forEach((id, ref) -> choices.put(ref, bodyLabel(id)));
        return choices;
    }

    private void replaceInputs(
            Feature feature,
            List<Feature> earlier,
            Feature.ProfileRef original,
            String sketchId,
            Feature.BodyRef target,
            BodyExtrusion.Operation operation,
            List<Feature.BodyRef> additional) {
        if (feature instanceof Feature.EdgeFinish finish) {
            controller.replaceFeature(
                    new Feature.EdgeFinish(finish.id(), target, finish.kind(), finish.size(), finish.edges()));
        } else if (feature instanceof Feature.Shell shell) {
            controller.replaceFeature(new Feature.Shell(shell.id(), target, shell.thickness(), shell.removedFace()));
        } else if (feature instanceof Feature.SplitBody split) {
            controller.replaceFeature(new Feature.SplitBody(split.id(), target, split.plane()));
        } else {
            var sketch = earlier.stream()
                    .filter(Feature.Sketch.class::isInstance)
                    .map(Feature.Sketch.class::cast)
                    .filter(s -> s.id().equals(sketchId))
                    .findFirst()
                    .orElseThrow(
                            () -> new IllegalArgumentException(translations.translate("gui.choose_an_earlier_sketch")));
            var profile = original.sketchId().equals(sketch.id())
                    ? original
                    : new Feature.ProfileRef(sketch.id(), sketch.profileId());
            Feature replacement = new BodyExtrusion(
                            new Feature.Extrude(
                                    feature.id(),
                                    profile,
                                    BodyExtrusion.from(feature).extrusion().distance()),
                            operation,
                            target,
                            additional)
                    .feature();
            controller.replaceFeature(replacement);
        }
    }

    private ParameterSet formulaParameters() {
        return controller.snapshot().document().parameters();
    }

    private FormulaField field(String id, String label, double initial) {
        var input = numericField(id, initial, ParameterUnit.MM);
        var caption = new Label(label);
        caption.setLabelFor(input);
        form.getChildren().add(InspectorDetails.field(caption, input));
        return input;
    }

    private FormulaField numericField(String id, double initial, ParameterUnit unit) {
        var text = FormulaField.number(Double.toString(initial), this::formulaParameters, unit, translations);
        text.setId(id);
        fields.put(id, text);
        return text;
    }

    private double value(String key) {
        return number(fields.get(key), key);
    }

    private void editSelectedFeature() {
        var feature = selected();
        if (feature instanceof Feature.Sketch sketch) attempt(() -> editSketch(sketch));
        else if (CutTargets.through(feature)) {
            attempt(() -> beginProfileEdit(
                    feature, CutTargets.firstThroughCut(feature).profile().sketchId()));
        } else if (feature instanceof Feature.Extrude || BodyExtrusion.modifiesBody(feature)) {
            attempt(() -> beginProfileEdit(
                    feature, BodyExtrusion.from(feature).extrusion().profile().sketchId()));
        }
    }

    private void editSketch(Feature.Sketch sketch) {
        openSketch(sketch, sketch.plane());
    }

    private void beginPlaneSelection(Feature.Sketch repair) {
        cancelTool();
        if (controller.ioBusy() || controller.rebuilding()) return;
        planeSnapshot = controller.snapshot();
        planeRepair = repair;
        tool = Tool.PLANE;
        selectionLabel.setText(translations.translate("plane.choose"));
        form.getChildren().clear();
        fields.clear();
        var hint = new Label(translations.translate("plane.hint"));
        hint.setWrapText(true);
        hint.setId("sketch-plane-hint");
        form.getChildren().add(hint);
        var origins = new HBox(6);
        for (var name : List.of("XY", "XZ", "YZ")) {
            origins.getChildren()
                    .add(button(
                            name,
                            "choose-plane-" + name.toLowerCase(Locale.ROOT),
                            () -> choosePlane(Feature.Plane.origin(name))));
        }
        form.getChildren().add(origins);
        controller.build().construction().forEach((id, datum) -> {
            if (datum instanceof dev.voxelbrew.api.model.construction.ConstructionGeometry.Plane plane) {
                var choose = new Button(controller.snapshot().document().featureName(id, featureBaseName(id)));
                choose.setId("choose-construction-" + id);
                choose.setOnAction(
                        event -> choosePlane(new Feature.Plane("support-" + id, 0, plane.frame(), null, id)));
                form.getChildren().add(choose);
            }
        });
        toolActions
                .getChildren()
                .setAll(button(translations.translate("action.cancel"), "cancel-tool", this::cancelTool));
        viewport.pickSketchPlane(this::choosePlane, (id, face) -> {
            boolean flat = controller.build().bodies().stream()
                    .filter(b -> b.id().equals(id))
                    .flatMap(b -> b.planarFaces().stream())
                    .anyMatch(p -> p.faceIndex() == face);
            viewport.highlightFace(id, flat ? face : -1);
        });
        log.info("Selecting a sketch plane{}", repair == null ? "" : " for " + repair.id());
        refresh();
    }

    private boolean planeSelectionCurrent() {
        var current = controller.snapshot();
        return planeSnapshot != null
                && !controller.rebuilding()
                && !controller.ioBusy()
                && current.revision() == planeSnapshot.revision()
                && current.document().id().equals(planeSnapshot.document().id());
    }

    private void chooseBodyPlane(String id, int face) {
        if (!planeSelectionCurrent()) {
            cancelTool();
            return;
        }
        var body = controller.build().bodies().stream()
                .filter(b -> b.id().equals(id))
                .findFirst();
        if (body.isEmpty() || body.get().planarFaces().stream().noneMatch(p -> p.faceIndex() == face)) {
            error.setText(translations.translate("plane.flat_only"));
            return;
        }
        if (planeRepair != null) {
            var features = planeSnapshot.document().features();
            int sketchIndex = java.util.stream.IntStream.range(0, features.size())
                    .filter(i -> features.get(i).id().equals(planeRepair.id()))
                    .findFirst()
                    .orElse(-1);
            int bodyIndex = java.util.stream.IntStream.range(0, features.size())
                    .filter(i -> features.get(i).id().equals(body.get().output().featureId()))
                    .findFirst()
                    .orElse(Integer.MAX_VALUE);
            if (bodyIndex >= sketchIndex) {
                error.setText(translations.translate("plane.upstream_only"));
                return;
            }
        }
        choosePlane(FaceSupport.attach(
                body.get().output(),
                body.get().planarFaces(),
                face,
                controller.snapshot().document().uuidGenerator()));
    }

    private void choosePlane(Feature.Plane plane) {
        if (tool != Tool.PLANE || !planeSelectionCurrent()) {
            cancelTool();
            return;
        }
        var repair = planeRepair;
        log.info(
                "Selected sketch plane {}{}",
                plane.id(),
                plane.support() == null ? "" : " on " + plane.support().body());
        cancelTool();
        if (repair == null) {
            openSketch(null, plane);
        } else {
            // Keep identities, geometry and parameter bindings; reattachment is one undoable edit.
            var placement = plane.withOffset(repair.plane().offsetZ());
            if (!Objects.equals(placement.support(), repair.plane().support())
                    && repair.profile() instanceof Feature.Constrained c
                    && c.geometry().entities().stream().anyMatch(e -> FaceProjection.reference(e.id()))) {
                error.setText(translations.translate("sketch.projection.changePlane"));
                return;
            }
            boolean boundOffset = controller.snapshot().document().parameters().bindings().stream()
                    .anyMatch(b -> b.target().featureId().equals(repair.id())
                            && b.target().field().equals("offsetZ"));
            if (placement.isOrigin() && (placement.offsetZ() != 0 || boundOffset)) {
                placement = new Feature.Plane(
                        "plane-"
                                + controller
                                        .snapshot()
                                        .document()
                                        .uuidGenerator()
                                        .next(),
                        placement.offsetZ(),
                        placement.frame(),
                        null);
            }
            controller.sketch(
                    new Feature.Sketch(repair.id(), repair.profileId(), placement, repair.profile()),
                    controller.snapshot().document().parameters(),
                    false);
            selectedFeature = repair.id();
            selectedBody = null;
            refresh();
        }
    }

    private void openSketch(Feature.Sketch sketch, Feature.Plane plane) {
        cancelTool();
        var before = controller.snapshot();
        if (controller.ioBusy() || controller.rebuilding()) {
            throw new IllegalArgumentException(
                    translations.translate("gui.wait_for_the_current_rebuild_or_file_operation"));
        }
        SketchEditor.show(
                stage,
                sketches,
                sketch,
                plane,
                projectedEdges(sketch, plane),
                controller.effectiveFeatures(),
                before.document().parameters(),
                translations,
                (result, parameters) -> {
                    var current = controller.snapshot();
                    if (!before.document().id().equals(current.document().id())
                            || before.revision() != current.revision()) {
                        throw new IllegalStateException(translations.translate(
                                "gui.the_document_changed_while_editing_cancel_and_reopen_the_sketch"));
                    }
                    controller.sketch(result, parameters, sketch == null);
                    selectedFeature = result.id();
                    selectedBody = null;
                    inspect();
                    refresh();
                });
    }

    private List<Entity> projectedEdges(Feature.Sketch sketch, Feature.Plane plane) {
        if (plane.support() == null) return List.of();
        if (sketch != null && controller.build().projections().containsKey(sketch.id())) {
            return controller.build().projections().get(sketch.id());
        }
        return controller.build().bodies().stream()
                .filter(b -> b.output().equals(plane.support().body()))
                .findFirst()
                .map(b -> FaceProjection.edges(plane, b.planarFaces(), b.faceEdges(), b.edgeSamples()))
                .orElse(List.of());
    }

    private ComboBox<BodyExtrusion.Operation> extrusionOperation(BodyExtrusion.Operation initial) {
        var choice = new ComboBox<BodyExtrusion.Operation>();
        choice.setId("extrude-operation");
        choice.getItems().setAll(BodyExtrusion.Operation.values());
        UiText.enums(choice, translations, "extrude.");
        choice.setCellFactory(list -> extrusionOperationCell(choice));
        choice.setButtonCell(extrusionOperationCell(choice));
        choice.setValue(initial);
        return choice;
    }

    private ListCell<BodyExtrusion.Operation> extrusionOperationCell(ComboBox<BodyExtrusion.Operation> choice) {
        return new ListCell<>() {
            @Override
            protected void updateItem(BodyExtrusion.Operation operation, boolean empty) {
                super.updateItem(operation, empty);
                if (empty || operation == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                setText(choice.getConverter().toString(operation));
                setGraphic(OperationIcons.extrusion(operation));
            }
        };
    }

    private ComboBox<DocumentController.Body> extrusionTarget(Feature.Sketch sketch) {
        var target = new ComboBox<DocumentController.Body>();
        target.setId("extrude-target");
        target.getItems().setAll(controller.build().bodies());
        UiText.choices(target, b -> bodyLabel(b.id()));
        var preferred = target.getItems().stream()
                .filter(b -> b.id().equals(selectedBody)
                        || sketch.plane().support() != null
                                && b.output().equals(sketch.plane().support().body()))
                .findFirst();
        target.getSelectionModel()
                .select(preferred.orElse(
                        target.getItems().isEmpty() ? null : target.getItems().getFirst()));
        return target;
    }

    private ProfileChoices extrusionRegions(Feature.Sketch sketch, VBox container) {
        var whole = new CheckBox(translations.translate("gui.extrude_all_regions"));
        whole.setId("extrude-whole");
        whole.setSelected(true);
        var regions = new LinkedHashMap<CheckBox, String>();
        if (sketch.profile() instanceof Feature.Constrained constrained) {
            boolean editing = tool == Tool.EDIT_PROFILE;
            var contours = editing
                    ? SketchProfiles.regionContours(constrained.geometry())
                    : Map.<String, List<List<dev.voxelbrew.api.model.sketch.SketchGeometry.Vec>>>of();
            var faces = editing
                    ? List.copyOf(contours.keySet())
                    : SketchProfiles.selectableRegions(constrained.geometry()).stream()
                            .map(SketchProfiles.Region::id)
                            .toList();
            if (faces.size() > 1 || tool == Tool.EDIT_PROFILE) {
                container.getChildren().add(whole);
                var choices = new VBox(5);
                for (var face : faces) {
                    int number = regions.size() + 1;
                    var box = new CheckBox(translations.translate("gui.extrude_region") + " " + number);
                    box.setId("extrude-region-" + number);
                    regions.put(box, face);
                    if (editing) {
                        var highlight = viewport.editingHighlight(sketch.plane(), contours.get(face));
                        highlight.visibleProperty().bind(box.selectedProperty());
                    }
                    choices.getChildren().add(box);
                }
                if (faces.size() > 4) {
                    var scroll = new ScrollPane(choices);
                    scroll.setId("extrude-region-list");
                    scroll.setFitToWidth(true);
                    scroll.setPrefViewportHeight(96);
                    container.getChildren().add(scroll);
                } else {
                    container.getChildren().add(choices);
                }
                var include = new Button(translations.translate("extrude.includeEnclosed"));
                include.setId("extrude-include-enclosed");
                include.disableProperty().bind(whole.selectedProperty());
                include.setOnAction(event -> {
                    var selection = new ProfileChoices(whole, regions);
                    var filled =
                            SketchProfiles.includingEnclosedRegions(constrained.geometry(), selection.selectedIds());
                    var additional = regions.entrySet().stream()
                            .filter(e -> !e.getKey().isSelected() && filled.contains(e.getValue()))
                            .map(Map.Entry::getKey)
                            .toList();
                    if (additional.isEmpty()) return;
                    additional.subList(1, additional.size()).forEach(box -> box.setSelected(true));
                    additional.getFirst().fire();
                });
                container.getChildren().add(include);
                viewport.pickExtrusionRegion(
                        sketch.plane(),
                        point -> SketchProfiles.regionAt(
                                        constrained.geometry(),
                                        new dev.voxelbrew.api.model.sketch.SketchGeometry.Vec(
                                                point.getX(), point.getY()))
                                .ifPresent(id -> regions.forEach((box, region) -> {
                                    if (region.equals(id)) box.fire();
                                })));
                if (editing) viewport.editingWholeHighlight(sketch, constrained.geometry(), whole.selectedProperty());
            }
        }
        return new ProfileChoices(whole, regions);
    }

    private void beginProfileEdit(Feature feature, String initialSketch) {
        var document = controller.snapshot().document();
        var definition = document.features().stream()
                .filter(f -> f.id().equals(feature.id()))
                .findFirst()
                .orElseThrow();
        boolean cut = CutTargets.through(definition);
        var original = cut
                ? CutTargets.firstThroughCut(definition).profile()
                : BodyExtrusion.from(definition).extrusion().profile();
        var earlier = controller.effectiveFeatures().stream()
                .takeWhile(f -> !f.id().equals(feature.id()))
                .toList();
        tool = Tool.EDIT_PROFILE;
        draftRevision = controller.snapshot().revision();
        draftGeneration = controller.generation();
        form.getChildren().clear();
        fields.clear();
        selectionLabel.setText(translations.translate(cut ? "gui.through_all_cut" : "extrude.editProfile"));
        var sketchCandidates = cut
                ? earlier.stream()
                        .filter(f -> f instanceof Feature.Sketch s && canCut(s))
                        .toList()
                : earlier;
        var sketches = sketchInput(sketchCandidates, original, form);
        sketches.setValue(sketches.getItems().contains(initialSketch) ? initialSketch : original.sketchId());
        var replaceProfile = profileReplacement(definition, earlier);
        var choices = new VBox(5);
        form.getChildren().add(choices);
        var apply = new Button(translations.translate("gui.apply"));
        apply.setId(cut ? "apply-cut-profile" : "apply-extrusion-profile");
        apply.getStyleClass().add("primary");
        toolActions
                .getChildren()
                .setAll(apply, button(translations.translate("action.cancel"), "cancel-tool", this::cancelTool));
        viewport.isolateProfile(true);
        Runnable update = () -> {
            choices.getChildren().clear();
            viewport.clearHandle();
            apply.setDisable(true);
            error.setText("");
            var sketch = sketchCandidates.stream()
                    .filter(Feature.Sketch.class::isInstance)
                    .map(Feature.Sketch.class::cast)
                    .filter(s -> s.id().equals(sketches.getValue()))
                    .findFirst();
            viewport.showSketches(sketch.stream().map(Feature.class::cast).toList(), null);
            sketch.ifPresent(s -> attempt(() -> {
                viewport.frameProfile(s);
                editProfileRegions(original, s, choices, apply, replaceProfile);
            }));
        };
        sketches.setOnAction(event -> update.run());
        update.run();
        refresh();
    }

    private java.util.function.Consumer<Feature.ProfileRef> profileReplacement(
            Feature definition, List<Feature> earlier) {
        if (CutTargets.through(definition)) {
            var saved = CutTargets.throughTargets(definition).bodies();
            var choices = historicalBodies(earlier);
            saved.forEach(ref -> choices.putIfAbsent(ref, ref.featureId() + "." + ref.outputName()));
            form.getChildren().add(new Label(translations.translate("gui.choose_the_target_body")));
            var first = primaryCutTarget(earlier, choices, saved.getFirst());
            var additional = new AdditionalCutTargets(choices, first, saved.subList(1, saved.size()), translations);
            form.getChildren().add(additional);
            return profile -> {
                var targets = new ArrayList<>(List.of(first.get()));
                targets.addAll(additional.selected());
                controller.replaceFeature(new CutTargets(targets).throughFeature(definition.id(), profile));
            };
        }
        var extrusion = BodyExtrusion.from(definition);
        return profile -> controller.replaceFeature(new BodyExtrusion(
                        new Feature.Extrude(
                                definition.id(), profile, extrusion.extrusion().distance()),
                        extrusion.operation(),
                        extrusion.target(),
                        extrusion.additionalTargets())
                .feature());
    }

    private java.util.function.Supplier<Feature.BodyRef> primaryCutTarget(
            List<Feature> earlier, Map<Feature.BodyRef, String> choices, Feature.BodyRef saved) {
        if (!controller.build().valid()
                || earlier.size() > controller.snapshot().document().cursor()) {
            // A failed or rolled-back build may have no index of the earlier outputs.
            var sources = new ComboBox<String>();
            sources.setId("repair-body");
            earlier.stream()
                    .filter(f -> !(f instanceof Feature.Sketch || f instanceof ConstructionFeature))
                    .forEach(f -> sources.getItems().add(f.id()));
            sources.setValue(saved.featureId());
            var output = FormulaField.text(saved.outputName(), this::formulaParameters, translations);
            output.setId("repair-output");
            form.getChildren()
                    .addAll(
                            sources,
                            new Label(translations.translate("gui.named_output_body_negative_or_positive")),
                            output);
            return () -> new Feature.BodyRef(sources.getValue(), output.evaluatedText());
        }
        var first = new ComboBox<Feature.BodyRef>();
        first.setId("cut-target");
        first.getItems().setAll(choices.keySet());
        UiText.choices(first, choices::get);
        first.setValue(saved);
        form.getChildren().add(first);
        return first::getValue;
    }

    private void editProfileRegions(
            Feature.ProfileRef original,
            Feature.Sketch sketch,
            VBox choices,
            Button apply,
            java.util.function.Consumer<Feature.ProfileRef> replaceProfile) {
        var selection = extrusionRegions(sketch, choices);
        boolean all =
                !original.sketchId().equals(sketch.id()) || original.profileId().equals(sketch.profileId());
        selection.whole().setSelected(all);
        var saved = original.profileId();
        String prefix = sketch.profileId() + "/regions/";
        var ids = saved.startsWith(prefix)
                ? Arrays.asList(saved.substring(prefix.length()).split("\\."))
                : List.<String>of();
        selection
                .regions()
                .forEach((box, id) -> box.setSelected(
                        !all && (saved.equals(sketch.profileId() + "/region/" + id) || ids.contains(id))));
        selection.onChange(() -> apply.setDisable(
                !selection.whole().isSelected() && selection.selectedIds().isEmpty()));
        apply.setDisable(!all && (selection.selectedIds().isEmpty() || !saved.equals(selection.profileId(sketch))));
        apply.setOnAction(event -> attempt(() -> {
            String profileId = selection.profileId(sketch);
            replaceProfile.accept(new Feature.ProfileRef(sketch.id(), profileId));
            cancelTool();
        }));
    }

    private void beginExtrusion() {
        var feature = selected();
        if (!(feature instanceof Feature.Sketch sketch)) {
            throw new IllegalArgumentException(translations.translate("gui.select_a_closed_sketch_profile_first"));
        }
        cancelTool();
        extrusionSketch = sketch;
        tool = Tool.EXTRUDE;
        selectionLabel.setText(
                translations.translate("gui.extrude_profile") + " · " + sketchReferenceName(sketch.id()));
        form.getChildren().clear();
        fields.clear();
        form.getChildren().add(sketchPlaneLabel(sketch));
        var regions = extrusionRegions(sketch, form);
        var operation = extrusionOperation(BodyExtrusion.Operation.NEW_BODY);
        var target = extrusionTarget(sketch);
        var additional = additionalCutTargets(target);
        additional.visibleProperty().bind(operation.valueProperty().isEqualTo(BodyExtrusion.Operation.CUT));
        operation.setDisable(target.getItems().isEmpty());
        target.disableProperty().bind(operation.valueProperty().isEqualTo(BodyExtrusion.Operation.NEW_BODY));
        var targetField = InspectorDetails.field(new Label(translations.translate("gui.input_body")), target);
        targetField.visibleProperty().bind(target.disableProperty().not());
        targetField.managedProperty().bind(targetField.visibleProperty());
        form.getChildren()
                .addAll(
                        InspectorDetails.field(new Label(translations.translate("extrude.operation")), operation),
                        targetField,
                        additional);
        var distance = field("extrude-distance", translations.translate("gui.signed_distance_mm"), 5);
        finish = button(translations.translate("gui.commit"), "commit-extrusion", this::commitExtrusion);
        finish.getStyleClass().add("primary");
        toolActions
                .getChildren()
                .setAll(finish, button(translations.translate("action.cancel"), "cancel-tool", this::cancelTool));
        var hint = new Label(
                translations.translate("gui.drag_the_gold_handle_or_type_a_distance_enter_commits_escape_cancels"));
        hint.setWrapText(true);
        form.getChildren().add(hint);
        Runnable updatePreview = () -> {
            try {
                double value = distance.value();
                if (!regions.whole().isSelected() && regions.selectedIds().isEmpty()) {
                    controller.cancelPreview();
                    finish.setDisable(true);
                    error.setText(translations.translate("gui.select_a_closed_sketch_profile"));
                    return;
                }
                controller.previewExtrusion(
                        sketch,
                        regions.profileId(sketch),
                        distance.expression().strip(),
                        operation.getValue(),
                        target.isDisabled() || target.getValue() == null
                                ? null
                                : target.getValue().output(),
                        operation.getValue() == BodyExtrusion.Operation.CUT ? additional.selected() : List.of());
                viewport.updateExtrusionDistance(value);
                error.setText("");
                finish.setDisable(!controller.canCommitExtrusion());
            } catch (RuntimeException failure) {
                controller.cancelPreview();
                finish.setDisable(true);
                error.setText(failure.getMessage());
            }
        };
        distance.textProperty().addListener((o, a, b) -> updatePreview.run());
        regions.onChange(updatePreview);
        additional.onChange(updatePreview);
        target.setOnAction(event -> updatePreview.run());
        operation.setOnAction(event -> {
            if (operation.getValue() == BodyExtrusion.Operation.CUT
                    && sketch.plane().support() != null
                    && distance.expression().equals("5.0")) {
                distance.setExpression("-5");
            } else {
                updatePreview.run();
            }
        });
        distance.setOnAction(e -> {
            if (!finish.isDisabled()) finish.fire();
        });
        UiText.choices(
                configuration, name -> name.equals("Default") ? translations.translate("configuration.default") : name);
        viewport.standardView("Isometric");
        controller.previewExtrusion(sketch, 5);
        viewport.extrusionHandle(sketch, 5, v -> distance.setExpression(format(v)), () -> {
            if (!finish.isDisabled()) commitExtrusion();
        });
        distance.requestFocus();
        distance.selectAll();
        refresh();
    }

    private void commitExtrusion() {
        if (!controller.canCommitExtrusion()) return;
        controller.commitPreview();
        tool = Tool.NONE;
        planeSnapshot = null;
        planeRepair = null;
        viewport.stopPlaneSelection();
        viewport.clearHandle();
        var document = controller.snapshot().document();
        selectedFeature = document.features().get(document.cursor() - 1).id();
        inspect();
        refresh();
    }

    private AdditionalCutTargets additionalCutTargets(ComboBox<DocumentController.Body> first) {
        var choices = new LinkedHashMap<Feature.BodyRef, String>();
        first.getItems().forEach(b -> choices.put(b.output(), bodyLabel(b.id())));
        return new AdditionalCutTargets(choices, () -> first.getValue().output(), List.of(), translations);
    }

    private void beginCut() {
        if (!(selected() instanceof Feature.Sketch sketch) || !canCut(sketch)) {
            throw new IllegalArgumentException(translations.translate("gui.select_a_closed_sketch_profile"));
        }
        cancelTool();
        var target = extrusionTarget(sketch);
        if (target.getValue() == null) {
            throw new IllegalArgumentException(translations.translate("gui.a_target_body_is_required"));
        }
        target.setId("cut-target");
        var additional = additionalCutTargets(target);
        form.getChildren()
                .setAll(
                        InspectorDetails.field(new Label(translations.translate("gui.input_body")), target),
                        additional);
        operationPanel(translations.translate("gui.through_all_cut"), "cut-confirm", () -> {
            String id =
                    "cut-" + controller.snapshot().document().uuidGenerator().next();
            var inputs = new ArrayList<>(List.of(target.getValue().output()));
            inputs.addAll(additional.selected());
            controller.add(
                    new CutTargets(inputs).throughFeature(id, new Feature.ProfileRef(sketch.id(), sketch.profileId())));
            selectedFeature = id;
        });
    }

    private Button operationPanel(String title, String applyId, Runnable commit) {
        tool = Tool.OPERATION;
        draftGeneration = controller.generation();
        draftRevision = controller.snapshot().revision();
        selectionLabel.setText(title);
        var apply = button(translations.translate("gui.apply"), applyId, () -> {
            commit.run();
            cancelTool();
        });
        apply.getStyleClass().add("primary");
        toolActions
                .getChildren()
                .setAll(apply, button(translations.translate("action.cancel"), "cancel-tool", this::cancelTool));
        refresh();
        return apply;
    }

    private void beginFinish(boolean rounded) {
        beginSolidModification(rounded ? "action.fillet" : "action.chamfer");
    }

    private void beginShell() {
        beginSolidModification("action.shell");
    }

    private void beginSolidModification(String title) {
        cancelTool();
        var bodies = controller.build().bodies();
        if (bodies.isEmpty()) return;
        tool = Tool.MODIFY;
        modification = title;
        modificationBody = selectedBody != null && bodies.stream().anyMatch(b -> b.id().equals(selectedBody))
                ? selectedBody
                : bodies.getFirst().id();
        modificationFaces.clear();
        modificationEdges.clear();
        viewport.pickEdges(!title.equals("action.shell"));
        form.getChildren().clear();
        fields.clear();
        error.setText("");
        selectionLabel.setText(translations.translate(title));
        modificationSelection = new Label(translations.translate("gui.choose_faces_in_viewport"));
        modificationSelection.setWrapText(true);
        form.getChildren().add(modificationSelection);
        var size = field(
                "modify-size",
                translations.translate(title.equals("action.shell") ? "gui.thickness_mm" : "gui.size_mm"),
                1);
        finish = button(translations.translate("gui.commit"), "commit-modification", () -> {
            try {
                if (modificationFaces.isEmpty() && modificationEdges.isEmpty()) {
                    throw new IllegalArgumentException(translations.translate("gui.choose_faces_in_viewport"));
                }
                var body = controller.build().bodies().stream()
                        .filter(b -> b.id().equals(modificationBody))
                        .findFirst()
                        .orElseThrow();
                double amount = number(size, "size");
                String id = "modify-"
                        + controller.snapshot().document().uuidGenerator().next();
                Feature feature;
                if (modification.equals("action.shell")) {
                    if (modificationFaces.size() != 1) {
                        throw new IllegalArgumentException(translations.translate("gui.choose_one_face"));
                    }
                    feature = new Feature.Shell(
                            id,
                            body.output(),
                            amount,
                            modificationFaces.iterator().next() + 1);
                } else {
                    var edges = new java.util.TreeSet<Integer>();
                    edges.addAll(modificationEdges);
                    modificationFaces.forEach(face -> edges.addAll(body.edgesOnFace(face)));
                    if (edges.isEmpty()) {
                        throw new IllegalArgumentException(translations.translate("gui.choose_faces_in_viewport"));
                    }
                    feature = new Feature.EdgeFinish(
                            id,
                            body.output(),
                            modification.equals("action.fillet")
                                    ? Feature.EdgeFinish.Kind.FILLET
                                    : Feature.EdgeFinish.Kind.CHAMFER,
                            amount,
                            java.util.List.copyOf(edges));
                }
                var parameters = formulaParameters();
                if (!Expressions.isNumber(size.expression())) {
                    String key = feature instanceof Feature.Shell ? "thickness" : "size";
                    parameters = parameters.bind(new ParameterSet.Target(id, key), size.expression());
                }
                controller.add(feature, parameters);
                selectedFeature = id;
                tool = Tool.NONE;
                modificationFaces.clear();
                modificationEdges.clear();
                viewport.pickEdges(false);
                viewport.highlightSelectedEdges(null, java.util.Set.of());
                inspect();
                refresh();
            } catch (RuntimeException failure) {
                error.setText(failure.getMessage());
            }
        });
        finish.getStyleClass().add("primary");
        toolActions
                .getChildren()
                .setAll(finish, button(translations.translate("action.cancel"), "cancel-tool", this::cancelTool));
        size.setOnAction(e -> finish.fire());
        viewport.select(modificationBody);
        refresh();
    }

    private void selectModificationFace(String id, int face) {
        if (face < 0) return;
        if (!id.equals(modificationBody)) {
            modificationBody = id;
            modificationFaces.clear();
            modificationEdges.clear();
        }
        if (!modificationFaces.add(face)) modificationFaces.remove(face);
        viewport.select(id);
        viewport.highlightFace(id, face);
        updateModificationSelection();
    }

    private void selectModificationEdge(String id, int edge) {
        if (tool != Tool.MODIFY) return;
        if (!id.equals(modificationBody)) {
            modificationBody = id;
            modificationFaces.clear();
            modificationEdges.clear();
        }
        if (!modificationEdges.add(edge)) modificationEdges.remove(edge);
        viewport.select(id);
        updateModificationSelection();
    }

    private void updateModificationSelection() {
        var body = controller.build().bodies().stream()
                .filter(b -> b.id().equals(modificationBody))
                .findFirst()
                .orElse(null);
        var edges = new java.util.TreeSet<Integer>(modificationEdges);
        if (body != null) modificationFaces.forEach(face -> edges.addAll(body.edgesOnFace(face)));
        viewport.highlightSelectedEdges(modificationBody, edges);
        modificationSelection.setText(
                translations.translate("gui.selected_geometry", modificationEdges.size(), modificationFaces.size()));
    }

    private String patternName(BodyPattern.Kind kind) {
        return translations.translate("sketch.copy." + kind.name().toLowerCase(Locale.ROOT));
    }

    private void editBodyPattern(BodyPattern.Kind kind, Feature existing) {
        var sources = new LinkedHashMap<Feature.BodyRef, String>();
        BodyPattern initial;
        if (existing == null) {
            cancelTool();
            var bodies = controller.build().bodies();
            if (bodies.isEmpty()) return;
            for (var body : bodies) sources.put(body.output(), bodyLabel(body.id()));
            var source = bodies.stream()
                    .filter(b -> b.id().equals(selectedBody))
                    .findFirst()
                    .orElse(bodies.getFirst())
                    .output();
            initial = new BodyPattern(
                    kind,
                    source,
                    BodyPattern.Axis.Z,
                    kind == BodyPattern.Kind.CIRCULAR ? 4 : 2,
                    1,
                    switch (kind) {
                        case RECTANGULAR -> 20;
                        case CIRCULAR -> 360;
                        case MIRROR -> 0;
                    },
                    kind == BodyPattern.Kind.RECTANGULAR ? 20 : 0);
        } else {
            initial = BodyPattern.from(existing);
            var ref = initial.source();
            var bodyId = controller.build().bodyIds().get(ref);
            sources.put(ref, bodyId == null ? ref.featureId() + "." + ref.outputName() : bodyLabel(bodyId));
        }
        String id = existing == null
                ? "pattern-" + controller.snapshot().document().uuidGenerator().next()
                : existing.id();
        var editor = new BodyPatternEditor(id, initial, sources, formulaParameters(), translations);
        form.getChildren().setAll(editor);
        operationPanel(patternName(kind), "pattern-apply", () -> {
            if (existing == null) controller.add(editor.feature(), editor.bindings());
            else controller.replaceFeature(editor.feature(), editor.bindings());
            selectedFeature = id;
            selectedBody = null;
        });
    }

    private void beginSplit() {
        cancelTool();
        var body = new ComboBox<DocumentController.Body>();
        body.setId("split-input");
        body.getItems().setAll(controller.build().bodies());
        UiText.choices(body, b -> bodyLabel(b.id()));
        body.setValue(body.getItems().stream()
                .filter(b -> b.id().equals(selectedBody))
                .findFirst()
                .orElse(body.getItems().getFirst()));
        var axis = new ComboBox<String>();
        axis.setId("split-axis");
        axis.getItems().setAll("X", "Y", "Z");
        axis.setValue("X");
        var offset = FormulaField.number("30", this::formulaParameters, ParameterUnit.MM, translations);
        offset.setId("split-offset");
        form.getChildren()
                .setAll(
                        InspectorDetails.field(new Label(translations.translate("gui.input_body")), body),
                        InspectorDetails.field(new Label(translations.translate("gui.plane_normal")), axis),
                        InspectorDetails.field(new Label(translations.translate("gui.offset_mm")), offset));
        operationPanel(translations.translate("action.split"), "split-confirm", () -> {
            String id =
                    "split-" + controller.snapshot().document().uuidGenerator().next();
            var feature = new Feature.SplitBody(
                    id,
                    Objects.requireNonNull(body.getValue(), translations.translate("gui.choose_a_body"))
                            .output(),
                    new Feature.SplitPlane(
                            axis.getValue().equals("X") ? 1 : 0,
                            axis.getValue().equals("Y") ? 1 : 0,
                            axis.getValue().equals("Z") ? 1 : 0,
                            number(offset, "offset")));
            var parameters = formulaParameters();
            if (!Expressions.isNumber(offset.expression())) {
                parameters = parameters.bind(new ParameterSet.Target(id, "offset"), offset.expression());
            }
            controller.add(feature, parameters);
            selectedFeature = id;
        });
    }

    private void beginConstruction(
            dev.voxelbrew.api.model.construction.ConstructionTools.Tool command, ConstructionFeature original) {
        if (controller.rebuilding()
                || controller.ioBusy()
                || (original == null && !controller.build().valid())) {
            return;
        }
        cancelTool();
        tool = Tool.CONSTRUCTION;
        selectionLabel.setText(translations.translate("construction." + command.name()));
        viewport.pickEdges(true);
        constructionEditor = new ConstructionEditor(
                command, original, controller, viewport, translations, toolActions, this::cancelTool);
        form.getChildren().setAll(constructionEditor);
        refresh();
    }

    private void cancelTool() {
        if (tool == Tool.EDIT_PROFILE) viewport.isolateProfile(false);
        if (constructionEditor != null) {
            constructionEditor.close();
            constructionEditor = null;
        }
        if (timeline != null) timeline.stop();
        tool = Tool.NONE;
        modificationFaces.clear();
        modificationEdges.clear();
        viewport.pickEdges(false);
        viewport.highlightSelectedEdges(null, java.util.Set.of());
        modification = null;
        modificationBody = null;
        planeSnapshot = null;
        planeRepair = null;
        viewport.stopPlaneSelection();
        viewport.stopDrawing();
        viewport.clearHandle();
        controller.cancelPreview();
        extrusionSketch = null;
        inspect();
        refresh();
    }

    private void open() {
        FileDialog.choose(
                        stage,
                        controller.path(),
                        formulaParameters(),
                        translations,
                        configurationStore,
                        recentFiles.paths())
                .ifPresent(this::openProject);
    }

    private void openProject(Path path) {
        chooseProjectWindow(path, () -> {
            cancelTool();
            controller.open(path).exceptionally(failure -> null);
        });
    }

    private void chooseProjectWindow(Path path, Runnable currentWindow) {
        var document = controller.snapshot().document();
        if (path == null
                && !controller.dirty()
                && document.features().isEmpty()
                && document.gcode().isEmpty()
                && document.parameters().equals(dev.voxelbrew.api.model.parameters.ParameterSet.empty())) {
            requestChange(currentWindow);
            return;
        }
        var current = new ButtonType(translations.translate("projectWindow.current"), ButtonBar.ButtonData.OK_DONE);
        var separate = new ButtonType(translations.translate("projectWindow.new"));
        var dialog = new Alert(
                Alert.AlertType.CONFIRMATION,
                translations.translate("projectWindow.question"),
                current,
                separate,
                ButtonType.CANCEL);
        dialog.setHeaderText(translations.translate("projectWindow.title"));
        var currentButton = dialog.getDialogPane().lookupButton(current);
        currentButton.setId("project-window-current");
        currentButton.getStyleClass().add("primary");
        dialog.getDialogPane().lookupButton(separate).setId("project-window-new");
        Dialogs.show(dialog, stage, translations).ifPresent(choice -> {
            if (choice == current) requestChange(currentWindow);
            else if (choice == separate) {
                try {
                    WorkbenchApplication.openWindow(new Stage(), pluginDirectory, path);
                } catch (IOException failure) {
                    Dialogs.show(
                            new Alert(Alert.AlertType.ERROR, failure.getMessage(), ButtonType.OK), stage, translations);
                }
            }
        });
    }

    CompletableFuture<Void> openWithRecovery(Path project) {
        BackupSession candidate = null;
        try {
            candidate = backups.latest(project);
            if (candidate != null && candidate.projectSavedAfterClose(project)) {
                var older = candidate;
                return controller.openOriginal(project).thenRun(older::close).exceptionallyCompose(failure -> {
                    if (closed) {
                        older.close();
                        return CompletableFuture.failedFuture(failure);
                    }
                    return chooseRecovery(project, older, false);
                });
            }
        } catch (IOException failure) {
            log.warn("Could not check for backups of {}", project, failure);
        } catch (RuntimeException failure) {
            if (candidate != null) candidate.close();
            throw failure;
        }
        return candidate == null ? openOriginalOrBlank(project) : chooseRecovery(project, candidate, true);
    }

    private CompletableFuture<Void> chooseRecovery(Path project, BackupSession candidate, boolean saveRecovered) {
        try {
            String key = candidate.interrupted ? "autosave.recoverInterrupted" : "autosave.recover";
            var prompt = new Alert(
                    Alert.AlertType.CONFIRMATION,
                    translations.translate(key, candidate.backup.getFileName()),
                    ButtonType.YES,
                    ButtonType.NO,
                    ButtonType.CANCEL);
            prompt.setHeaderText(translations.translate("autosave.title"));
            prompt.getDialogPane().lookupButton(ButtonType.YES).setId("autosave-recover");
            prompt.getDialogPane().lookupButton(ButtonType.NO).setId("autosave-open-original");
            var choice = Dialogs.show(prompt, stage, translations).orElse(ButtonType.CANCEL);
            if (choice == ButtonType.YES) {
                var recovered = backups.recover(candidate, project);
                candidate = null;
                return saveRecovered ? recovered.thenCompose(unused -> save(false)) : recovered;
            }
            if (choice != ButtonType.NO) {
                return CompletableFuture.failedFuture(new java.util.concurrent.CancellationException());
            }
            var declined = candidate;
            var opened = openOriginalOrBlank(project).whenComplete((unused, failure) -> {
                try (declined) {
                    if (failure == null) declined.dismiss(project);
                } catch (IOException writeFailure) {
                    log.warn("Could not remember recovery choice for {}", declined.backup, writeFailure);
                }
            });
            candidate = null;
            return opened;
        } finally {
            if (candidate != null) candidate.close();
        }
    }

    private CompletableFuture<Void> openOriginalOrBlank(Path project) {
        return project == null ? CompletableFuture.completedFuture(null) : controller.openOriginal(project);
    }

    private boolean hasBodyStyleDraft() {
        return bodyStyleDraft != null
                && tool == Tool.NONE
                && bodyStyleDraft.color().getScene() != null
                && !bodyStyleDraft.color().isDisabled()
                && bodyStyleDraft.changed();
    }

    private void applyBodyStyleDraft() {
        if (hasBodyStyleDraft()) controller.bodyStyle(bodyStyleDraft.bodyId(), bodyStyleDraft.value());
    }

    private CompletableFuture<Void> save(boolean saveAs) {
        if (controller.ioBusy()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException(translations.translate("gui.file_operation_in_progress")));
        }
        applyBodyStyleDraft();
        if (!saveAs && controller.path() != null) {
            Path destination = controller.path();
            return controller.save(destination, Files.exists(destination)).thenRun(() -> showSaveSuccess(destination));
        }
        var window = exportWindow("vxb");
        return controller.saveAs(window).thenRun(() -> showSaveSuccess(window.acceptedDestination()));
    }

    private CompletableFuture<Void> exportDocument(String format) {
        if (format.equals("vxb")) applyBodyStyleDraft();
        var window = exportWindow(format);
        var export = controller.exportDocument(registry.require(format), window);
        export.whenComplete((ignored, failure) -> {
            if (export.isCancelled() || failure instanceof java.util.concurrent.CancellationException) return;
            Platform.runLater(() -> {
                if (closed || stage.getScene() == null || !stage.isShowing()) return;
                if (failure == null) {
                    showExportSuccess(format, window.acceptedDestination());
                } else {
                    var cause = failure instanceof java.util.concurrent.CompletionException completion
                                    && completion.getCause() != null
                            ? completion.getCause()
                            : failure;
                    String message = cause.getMessage() == null ? cause.toString() : cause.getMessage();
                    showExportNotification(
                            translations.translate("gui.export_failed_title"),
                            translations.translate("gui.export_failed", message),
                            false,
                            null);
                }
            });
        });
        return export;
    }

    private void showSaveSuccess(Path output) {
        if (output != null && !closed && stage.isShowing()) {
            showExportNotification(translations.translate("gui.save_success_title"), output.toString(), true, output);
        }
    }

    private void showExportSuccess(String format, Path output) {
        showExportNotification(
                translations.translate("gui.export_success_title"),
                translations.translate("gui.export_success", format),
                true,
                output);
    }

    private Hyperlink openFolderLink(Path folder) {
        var link = new Hyperlink(translations.translate("gui.open_folder"));
        link.setId("output-open-folder");
        link.setTooltip(new Tooltip(folder.toString()));
        link.setOnAction(event -> {
            try {
                java.awt.Desktop.getDesktop().open(folder.toFile());
            } catch (IOException
                    | UnsupportedOperationException
                    | SecurityException
                    | IllegalArgumentException failure) {
                Dialogs.show(
                        new Alert(Alert.AlertType.ERROR, failure.getLocalizedMessage(), ButtonType.OK),
                        stage,
                        translations);
            }
        });
        return link;
    }

    private void showExportNotification(String title, String message, boolean success, Path output) {
        if (exportNotification != null) exportNotification.hide();
        var symbol = new SVGPath();
        symbol.setContent(success ? "M3 9 L7 13 L15 4" : "M4 4 L14 14 M14 4 L4 14");
        symbol.getStyleClass().add("export-toast-symbol");
        var icon = new StackPane(symbol);
        icon.getStyleClass().add("export-toast-icon");
        var heading = new Label(title);
        heading.getStyleClass().add("export-toast-title");
        var detail = new Label(message);
        detail.setId("export-notification-message");
        detail.getStyleClass().add("export-toast-detail");
        detail.setWrapText(true);
        detail.setMaxHeight(80);
        if (!success) detail.setTooltip(new Tooltip(message));
        var copy = new VBox(4, heading, detail);
        if (output != null) {
            Path folder = output.toAbsolutePath().normalize().getParent();
            if (folder != null) copy.getChildren().add(openFolderLink(folder));
        }
        copy.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(copy, Priority.ALWAYS);
        var closeIcon = new SVGPath();
        closeIcon.setContent("M2 2 L12 12 M12 2 L2 12");
        closeIcon.getStyleClass().add("export-toast-close-icon");
        closeIcon.setMouseTransparent(true);
        var close = new Button(null, closeIcon);
        close.setId("export-notification-close");
        close.getStyleClass().add("export-toast-close");
        close.setFocusTraversable(false);
        var closeLabel = translations.tryTranslate("dialog.close").orElseThrow();
        close.setAccessibleText(closeLabel);
        close.setTooltip(UiHelp.tooltip(closeLabel));
        var card = new HBox(12, icon, copy, close);
        card.setAlignment(Pos.CENTER_LEFT);
        card.setPrefWidth(Math.min(380, Math.max(280, stage.getWidth() - 32)));
        card.getStyleClass()
                .addAll(
                        "root",
                        "export-toast",
                        getStyleClass().contains("dark") ? "dark" : "light",
                        success ? "success" : "failure");
        card.getStylesheets()
                .add(Objects.requireNonNull(Workbench.class.getResource("workbench.css"))
                        .toExternalForm());
        var popup = new Popup();
        popup.setAutoHide(true);
        popup.setConsumeAutoHidingEvents(false);
        popup.setHideOnEscape(true);
        popup.getContent().add(card);
        close.setOnAction(event -> popup.hide());
        ChangeListener<Number> followWindow = (observable, before, after) -> positionExportNotification(popup, card);
        stage.xProperty().addListener(followWindow);
        stage.yProperty().addListener(followWindow);
        stage.widthProperty().addListener(followWindow);
        stage.heightProperty().addListener(followWindow);
        popup.setOnHidden(event -> {
            stage.xProperty().removeListener(followWindow);
            stage.yProperty().removeListener(followWindow);
            stage.widthProperty().removeListener(followWindow);
            stage.heightProperty().removeListener(followWindow);
            if (exportNotification == popup) exportNotification = null;
        });
        card.setOpacity(0);
        popup.show(stage, stage.getX() + stage.getWidth() - card.getPrefWidth() - 24, stage.getY() + 70);
        positionExportNotification(popup, card);
        exportNotification = popup;
        long fadeMillis = Math.min(180, exportNotificationDurationMillis / 4);
        var enter = new FadeTransition(Duration.millis(fadeMillis), card);
        enter.setToValue(1);
        enter.play();
        var dismiss = new PauseTransition(Duration.millis(exportNotificationDurationMillis - fadeMillis));
        dismiss.setOnFinished(event -> {
            var exit = new FadeTransition(Duration.millis(fadeMillis), card);
            exit.setToValue(0);
            exit.setOnFinished(done -> popup.hide());
            exit.play();
        });
        dismiss.play();
    }

    private void positionExportNotification(Popup popup, Region card) {
        popup.setX(stage.getX() + stage.getWidth() - card.getPrefWidth() - 24);
        popup.setY(stage.getY() + 70);
    }

    private void configureExportNotificationDuration() {
        var dialog = new Dialog<Integer>();
        dialog.setTitle(translations.translate("exportNotification.duration.title"));
        var input = new TextField(Integer.toString(exportNotificationDurationMillis));
        input.setId("export-notification-duration-ms");
        var hint = new Label(translations.translate("exportNotification.duration.hint"));
        hint.setWrapText(true);
        var error = new Label();
        error.setId("export-notification-duration-error");
        error.getStyleClass().add("error");
        error.setVisible(false);
        error.managedProperty().bind(error.visibleProperty());
        dialog.getDialogPane().setContent(new VBox(10, hint, input, error));
        var save = new ButtonType(translations.translate("action.save"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().setAll(save, ButtonType.CANCEL);
        var selected = new int[1];
        dialog.getDialogPane().lookupButton(save).addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            try {
                selected[0] = parseNotificationDuration(input.getText());
            } catch (IllegalArgumentException failure) {
                error.setText(translations.translate("exportNotification.duration.invalid"));
                error.setVisible(true);
                event.consume();
            }
        });
        dialog.setResultConverter(button -> button == save ? selected[0] : null);
        Dialogs.show(dialog, stage, translations)
                .ifPresent(millis -> preferencesWorker.execute(() -> {
                    try {
                        configurationStore.saveUserConfig(EXPORT_NOTIFICATION_DURATION_KEY, Integer.toString(millis));
                        Platform.runLater(() -> exportNotificationDurationMillis = millis);
                    } catch (UserConfigException failure) {
                        log.error("Could not save export notification duration", failure);
                        Platform.runLater(() -> Dialogs.show(
                                new Alert(Alert.AlertType.ERROR, failure.getMessage(), ButtonType.OK),
                                stage,
                                translations));
                    }
                }));
    }

    private void configureBackups() {
        var dialog = new Dialog<ProjectBackups.Settings>();
        dialog.setTitle(translations.translate("autosave.title"));
        var current = backups.settings();
        var enabled = new CheckBox(translations.translate("autosave.enabled"));
        enabled.setId("autosave-enabled");
        enabled.setSelected(current.enabled());
        var minutes = new Spinner<Integer>(1, Integer.MAX_VALUE, current.minutes());
        var operations = new Spinner<Integer>(1, Integer.MAX_VALUE, current.operations());
        minutes.setId("autosave-minutes");
        operations.setId("autosave-operations");
        minutes.disableProperty().bind(enabled.selectedProperty().not());
        operations.disableProperty().bind(enabled.selectedProperty().not());
        var fields = new GridPane();
        fields.setHgap(12);
        fields.setVgap(8);
        fields.add(enabled, 0, 0, 2, 1);
        fields.addRow(1, new Label(translations.translate("autosave.minutes")), minutes);
        fields.addRow(2, new Label(translations.translate("autosave.operations")), operations);
        dialog.getDialogPane().setContent(fields);
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.getDialogPane().lookupButton(ButtonType.OK).setId("autosave-apply");
        dialog.setResultConverter(button -> button == ButtonType.OK
                ? new ProjectBackups.Settings(enabled.isSelected(), minutes.getValue(), operations.getValue())
                : null);
        Dialogs.show(dialog, stage, translations).ifPresent(next -> {
            try {
                next.save(configurationStore);
                backups.configure(next);
            } catch (UserConfigException failure) {
                Dialogs.show(
                        new Alert(Alert.AlertType.ERROR, failure.getMessage(), ButtonType.OK), stage, translations);
            }
        });
    }

    private DesktopExportWindow exportWindow(String format) {
        if (controller.path() == null) {
            try {
                java.nio.file.Files.createDirectories(FileDialog.projectsFolder());
            } catch (java.io.IOException failure) {
                log.warn("Could not create default project folder", failure);
            }
        }
        return new DesktopExportWindow(stage, format.equals("vxb") ? controller.path() : null, translations)
                .exporterVersion(registry.require(format).descriptor().version())
                .suggestDestination(exportSuggestion(FileDialog.projectsFolder(), controller.path()));
    }

    void openUpdates() {
        updates();
    }

    private void updates() {
        if (UpdateDialog.show(stage, updates, this::requestChange)) {
            close();
            stage.hide();
            Platform.exit();
        }
    }

    private void requestChange(Runnable action) {
        if (controller.ioBusy()) {
            error.setText(translations.translate("gui.wait_for_the_current_file_operation"));
            return;
        }
        if (!controller.dirty() && !hasBodyStyleDraft()) {
            action.run();
            return;
        }
        var save = new ButtonType(translations.translate("action.save"), ButtonBar.ButtonData.YES);
        var discard = new ButtonType(translations.translate("gui.discard"), ButtonBar.ButtonData.NO);
        var alert = new Alert(
                Alert.AlertType.CONFIRMATION,
                translations.translate("gui.save_changes_to_this_design"),
                save,
                discard,
                ButtonType.CANCEL);
        alert.setHeaderText(translations.translate("gui.unsaved_changes"));
        alert.getDialogPane().lookupButton(save).setId("unsaved-save");
        alert.getDialogPane().lookupButton(discard).setId("unsaved-discard");
        alert.getDialogPane().lookupButton(ButtonType.CANCEL).setId("unsaved-cancel");
        var choice = Dialogs.show(alert, stage, translations).orElse(ButtonType.CANCEL);
        if (choice == discard) action.run();
        else if (choice == save) {
            save(false).thenRun(() -> requestChange(action)).exceptionally(failure -> null);
        }
    }

    private void applyAppearance() {
        long request = ++appearanceRequest;
        var choice = appearanceChoice;
        if (choice != Appearance.Choice.SYSTEM) {
            setDark(choice == Appearance.Choice.DARK);
            return;
        }
        CompletableFuture.supplyAsync(Appearance::systemDark, preferencesWorker)
                .thenAccept(dark -> Platform.runLater(() -> {
                    if (!closed && request == appearanceRequest) setDark(dark);
                }));
    }

    private void setDark(boolean dark) {
        getStyleClass().removeAll("light", "dark");
        getStyleClass().add(dark ? "dark" : "light");
        window.getStyleClass().removeAll("light", "dark");
        window.getStyleClass().add(dark ? "dark" : "light");
        if (exportNotification != null && exportNotification.isShowing()) {
            var toast = exportNotification.getContent().getFirst();
            toast.getStyleClass().removeAll("light", "dark");
            toast.getStyleClass().add(dark ? "dark" : "light");
        }
        viewport.setDark(dark);
        printing.setDark(dark);
    }

    /**
     * Completes after all window workers drain and plugin JAR handles are released. Wait off the
     * JavaFX thread before replacing/removing plugin files.
     */
    public java.util.concurrent.CompletionStage<Void> whenClosed() {
        return resources.whenClosed();
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        stage.getProperties().remove(Workbench.class);
        backupTimer.stop();
        if (exportNotification != null) exportNotification.hide();
        quickMenus.forEach(QuickMenu::close);
        if (mcpServer != null) mcpServer.close();
        if (constructionEditor != null) constructionEditor.close();
        timeline.close();
        window.close();
        stage.focusedProperty().removeListener(windowFocus);
        printing.close();
        printer.close();
        controller.close();
        preferencesWorker.shutdown();
        resources.close();
    }

    void startMcpIfConfigured() {
        try {
            var settings = McpSettings.load(configurationStore);
            if (settings.autoStart()) mcpServer.start(settings);
        } catch (Exception failure) {
            log.warn("MCP listener was not started", failure);
            error.setText("MCP: " + failure.getMessage());
        }
    }

    private Menu recentMenu() {
        var recentMenu = menu("menu-open-recent");
        Runnable refreshRecent = () -> {
            recentMenu.getItems().clear();
            for (Path path : recentFiles.paths()) {
                var item = new MenuItem(path.toString());
                item.setMnemonicParsing(false);
                item.setOnAction(event -> openProject(path));
                recentMenu.getItems().add(item);
            }
            if (recentMenu.getItems().isEmpty()) {
                var empty = new MenuItem(translations.translate("recent.empty"));
                empty.setDisable(true);
                recentMenu.getItems().add(empty);
            }
        };
        refreshRecent.run();
        recentMenu.setOnShowing(event -> refreshRecent.run());
        recentMenu.disableProperty().bind(disabledProperty().or(fileBusy));
        var titleRecentMenu = new ContextMenu();
        UiHelp.menu(titleRecentMenu);
        documentTitle.setOnMouseClicked(event -> {
            if (event.getButton() != MouseButton.PRIMARY || !event.isStillSincePress()) return;
            titleRecentMenu.getItems().clear();
            for (Path path : recentFiles.paths()) {
                var item = new MenuItem(path.toString());
                item.setMnemonicParsing(false);
                item.setOnAction(action -> openProject(path));
                titleRecentMenu.getItems().add(item);
            }
            if (titleRecentMenu.getItems().isEmpty()) {
                var empty = new MenuItem(translations.translate("recent.empty"));
                empty.setDisable(true);
                titleRecentMenu.getItems().add(empty);
            }
            if (!isDisabled() && !fileBusy.get()) {
                titleRecentMenu.show(documentTitle, javafx.geometry.Side.BOTTOM, 0, 0);
            }
            event.consume();
        });
        controller.onFileUsed(path -> {
            recentFiles.remember(path);
            preferencesWorker.execute(recentFiles::save);
        });
        return recentMenu;
    }

    private Menu viewMenu(Rendering rendering) {
        var viewMenu = menu("menu-view");
        viewMenu.getItems().add(menuItem(translations.translate("action.fit"), "menu-fit", false, () -> {
            viewport.fit(meshes());
            Platform.runLater(viewport::requestFocus);
        }));
        for (var name : STANDARD_VIEWS) {
            var item = menuItem(
                    UiText.view(translations, name),
                    "view-" + name.toLowerCase(Locale.ROOT),
                    false,
                    () -> viewport.standardView(name));
            viewMenu.getItems().add(item);
        }
        var appearanceMenu = new Menu(translations.translate("appearance.label"));
        appearanceMenu.setId("menu-appearance");
        UiHelp.menu(appearanceMenu, translations);
        var themeGroup = new ToggleGroup();
        for (var choice : Appearance.Choice.values()) {
            String name = choice.name().toLowerCase(Locale.ROOT);
            var item = new RadioMenuItem(translations.translate("appearance." + name));
            item.setId("appearance-" + name);
            item.setToggleGroup(themeGroup);
            item.setSelected(choice == appearanceChoice);
            item.disableProperty().bind(disabledProperty());
            UiHelp.menuItem(item, translations.translate("help.appearance"));
            item.setOnAction(event -> {
                appearanceChoice = choice;
                appearance.save(choice);
                applyAppearance();
            });
            appearanceMenu.getItems().add(item);
        }
        viewMenu.getItems().addAll(new SeparatorMenuItem(), appearanceMenu);
        var renderingMenu = new Menu(translations.translate("rendering.label"));
        renderingMenu.setId("menu-rendering");
        UiHelp.menu(renderingMenu, translations);
        var renderingGroup = new ToggleGroup();
        for (var style : Rendering.Style.values()) {
            String name = style.name().toLowerCase(Locale.ROOT);
            var item = new RadioMenuItem(translations.translate("rendering." + name));
            item.setId("rendering-" + name);
            item.setToggleGroup(renderingGroup);
            item.setSelected(style == viewport.rendering());
            item.disableProperty().bind(disabledProperty());
            UiHelp.menuItem(item, translations.translate("help.rendering-" + name));
            item.setOnAction(event -> {
                viewport.setRendering(style);
                rendering.save(style);
            });
            renderingMenu.getItems().add(item);
        }
        viewMenu.getItems().add(renderingMenu);
        return viewMenu;
    }

    private void populateSolidCommands(MenuButton solidCommands) {
        for (var command : List.of(extrude, cut, fillet, chamfer, shell, splitBody)) {
            var item = menuItem(command.getText(), command.getId(), true, command::fire);
            item.setGraphic(OperationIcons.icon(command.getId()));
            item.disableProperty().bind(disabledProperty().or(command.disabledProperty()));
            solidCommands.getItems().add(item);
        }
        for (var kind : BodyPattern.Kind.values()) {
            var key = kind.name().toLowerCase(Locale.ROOT);
            var item = menuItem(patternName(kind), "body-" + key, false, () -> editBodyPattern(kind, null));
            item.setGraphic(OperationIcons.icon(key));
            item.disableProperty().bind(disabledProperty().or(splitBody.disabledProperty()));
            solidCommands.getItems().add(item);
        }
    }

    private MenuButton constructionMenu() {
        var constructionMenu = new MenuButton(translations.translate("construction.menu"));
        constructionMenu.setId("construction-menu");
        constructionMenu.setGraphic(
                ConstructionIcons.icon(dev.voxelbrew.api.model.construction.ConstructionTools.Tool.OFFSET_PLANE));
        UiHelp.menu(constructionMenu, translations);
        for (var command : dev.voxelbrew.api.model.construction.ConstructionTools.Tool.values()) {
            if (command == dev.voxelbrew.api.model.construction.ConstructionTools.Tool.OFFSET_PLANE
                    || command == dev.voxelbrew.api.model.construction.ConstructionTools.Tool.AXIS_SURFACE
                    || command == dev.voxelbrew.api.model.construction.ConstructionTools.Tool.POINT_VERTEX) {
                constructionMenu.getItems().add(new SeparatorMenuItem());
            }
            var item = new MenuItem(translations.translate("construction." + command.name()));
            item.setGraphic(ConstructionIcons.icon(command));
            UiHelp.menuItem(
                    item,
                    translations.translate("construction." + command.name()) + ". "
                            + translations.translate("construction.select"));
            item.setId("construct-" + command.name().toLowerCase(Locale.ROOT).replace('_', '-'));
            item.setOnAction(event -> attempt(() -> beginConstruction(command, null)));
            constructionMenu.getItems().add(item);
        }
        return constructionMenu;
    }

    private VBox modelPanel() {
        tree.setId("model-tree");
        var root = new TreeItem<>(new Entry(null, translations.translate("gui.document"), false));
        operationRows.setValue(new Entry(null, translations.translate("gui.features"), false));
        operationRows.setGraphic(OperationIcons.icon("features"));
        sketchRows.setValue(new Entry(null, translations.translate("gui.sketches"), false));
        sketchRows.setGraphic(OperationIcons.icon("constrained-sketch"));
        bodyRows.setValue(new Entry(null, translations.translate("gui.bodies"), true));
        bodyRows.setGraphic(OperationIcons.icon("bodies"));
        for (var group : List.of(operationRows, bodyRows, sketchRows)) {
            group.setExpanded(true);
            root.getChildren().add(group);
        }
        tree.setRoot(root);
        tree.setShowRoot(false);
        tree.setEditable(true);
        tree.setCellFactory(view -> new BodyTreeCell());
        tree.getSelectionModel().selectedItemProperty().addListener((o, old, item) -> {
            if (refreshing || item == null || item.getValue().id() == null || tool != Tool.NONE) return;
            var entry = item.getValue();
            if (entry.body()) selectBody(entry.id(), -1);
            else {
                selectFeature(entry.id());
            }
        });
        configuration.setId("active-configuration");
        UiHelp.install(configuration, translations);
        configuration.setMaxWidth(Double.MAX_VALUE);
        configuration.setOnAction(e -> {
            if (!refreshing && configuration.getValue() != null) {
                attempt(() -> controller.configuration(configuration.getValue()));
            }
        });
        var configurationActions = new GridPane();
        configurationActions.setHgap(6);
        for (int column = 0; column < 2; column++) {
            var half = new ColumnConstraints();
            half.setPercentWidth(50);
            configurationActions.getColumnConstraints().add(half);
        }
        parameters.setMaxWidth(Double.MAX_VALUE);
        customFeature.setMaxWidth(Double.MAX_VALUE);
        configurationActions.addRow(0, parameters, customFeature);
        var left = new VBox(
                10,
                heading(translations.translate("gui.model")),
                tree,
                new Separator(),
                heading(translations.translate("workbench.configuration")),
                configuration,
                configurationActions);
        left.getStyleClass().add("side-panel");
        tree.setPrefWidth(200);
        left.setMinWidth(Region.USE_PREF_SIZE);
        VBox.setVgrow(tree, Priority.ALWAYS);
        return left;
    }

    private void configureWorkspaceTabs() {
        for (int index = 0; index < workspaces.getTabs().size(); index++) {
            var tab = workspaces.getTabs().get(index);
            var key = KeyCombination.keyCombination("Shortcut+" + (index + 1)).getDisplayText();
            tab.setTooltip(UiHelp.tooltip(UiHelp.baseDescription(translations, tab.getId())
                    + "\n"
                    + translations.translate("shortcuts.hint", key)));
        }
        workspaces.setId("workspaces");
        workspaces.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            quickMenus.forEach(QuickMenu::hide);
            if (b == printTab && printTab.isDisable()) {
                workspaces.getSelectionModel().select(designTab);
                return;
            }
            if (b == printTab && (controller.path() == null || controller.dirty())) {
                javafx.application.Platform.runLater(() -> {
                    workspaces.getSelectionModel().select(designTab);
                    save(false)
                            .thenRun(() -> {
                                if (controller.path() != null && !controller.dirty() && !printTab.isDisable()) {
                                    workspaces.getSelectionModel().select(printTab);
                                }
                            })
                            .exceptionally(failure -> null);
                });
                return;
            }
            cancelTool();
            printing.documentChanged();
        });
    }

    private void installViewportSelection() {
        viewport.onSelection((id, face) -> {
            if (tool == Tool.CONSTRUCTION && constructionEditor != null) {
                constructionEditor.pick(id, face);
                return;
            }
            if (tool == Tool.PLANE) {
                chooseBodyPlane(id, face);
                return;
            }
            if (tool == Tool.MODIFY) {
                selectModificationFace(id, face);
                return;
            }
            if (tool != Tool.NONE || controller.rebuilding()) return;
            if (controller.snapshot().document().features().stream().anyMatch(f -> f.id().equals(id))) {
                selectFeature(id);
            } else {
                selectBody(id, face);
            }
        });
        viewport.onEdgeSelection((id, edge) -> {
            if (constructionEditor != null) constructionEditor.pickEdge(id, edge);
            else {
                selectModificationEdge(id, edge);
            }
        });
    }

    private void escape() {
        if (printing.cancelPlateRename() || printing.cancelDrag()) return;
        if (tree.getEditingItem() != null) {
            tree.edit(null);
        } else if (designTab.isSelected() && tool == Tool.NONE && selectedBody != null) {
            selectFeature(null);
        } else {
            cancelTool();
        }
    }

    private void installShortcuts(
            MenuBar menuBar,
            MenuItem newItem,
            MenuItem openItem,
            MenuItem importItem,
            MenuItem saveItem,
            MenuItem saveAsItem,
            MenuButton constructionMenu,
            MenuButton solidCommands) {
        window.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (isDisabled() || menuBar.getMenus().stream().anyMatch(Menu::isShowing)) return;
            var focus = getScene().getFocusOwner();
            if (KeyboardShortcuts.textUndo(e, focus)) return;
            if (e.getCode() == KeyCode.ESCAPE) {
                escape();
                e.consume();
                return;
            }
            String action = KeyboardShortcuts.match(KeyboardShortcuts.Scope.DOCUMENT, e, focus);
            if (action != null) {
                // Consume before opening a modal nested event loop, and use the same
                // enabled-state gates as the visible controls.
                e.consume();
                switch (action) {
                    case "new" -> fire(newItem);
                    case "open" -> fire(openItem);
                    case "import" -> fire(importItem);
                    case "save" -> fire(saveItem);
                    case "save-as" -> fire(saveAsItem);
                    case "export" -> fire(export);
                    case "exit" -> exitApplication();
                    case "undo" -> undo.fire();
                    case "redo" -> redo.fire();
                    case "parameters" -> parameters.fire();
                    case "workspace-number" -> selectWorkspaceNumber(e);
                    case "workspace-previous" -> moveWorkspace(-1);
                    case "workspace-next" -> moveWorkspace(1);
                    case "context-help" -> {
                        showWorkspaceHelp();
                    }
                    default -> throw new IllegalStateException(action);
                }
                return;
            }
            if (!designTab.isSelected()) return;
            action = KeyboardShortcuts.match(KeyboardShortcuts.Scope.DESIGN, e, focus);
            if (action == null) return;
            KeyboardShortcuts.consume(e, getScene());
            String viewAction = action;
            switch (action) {
                case "constrained-sketch" -> constrainedSketch.fire();
                case "operations-menu" -> {
                    constructionMenu.hide();
                    solidCommands.requestFocus();
                    solidCommands.show();
                }
                case "construction-menu" -> {
                    solidCommands.hide();
                    constructionMenu.requestFocus();
                    constructionMenu.show();
                }
                case "extrude" -> extrude.fire();
                case "cut" -> cut.fire();
                case "split-body" -> splitBody.fire();
                case "fit" -> viewport.fit(meshes());
                default ->
                    STANDARD_VIEWS.stream()
                            .filter(name -> ("view-" + name.toLowerCase(Locale.ROOT)).equals(viewAction))
                            .findFirst()
                            .ifPresent(viewport::standardView);
            }
        });
    }

    private void showWorkspaceHelp() {
        var help = new Alert(Alert.AlertType.INFORMATION);
        help.initOwner(stage);
        String workspace = "designer";
        if (printerTab.isSelected()) {
            workspace = "printer";
        } else if (printTab.isSelected()) {
            workspace = "slicer";
        }
        help.setTitle(translations.translate("help." + workspace + ".title"));
        help.setHeaderText(null);
        help.setContentText(translations.translate("help." + workspace + ".content"));
        help.getDialogPane().setPrefWidth(600);
        help.getDialogPane().getButtonTypes().setAll(ButtonType.CLOSE);
        help.getDialogPane().lookupButton(ButtonType.CLOSE).setId("context-help-close");
        var content = new Label(help.getContentText());
        content.setId("context-help-content");
        content.setWrapText(true);
        help.getDialogPane().setContent(content);
        Dialogs.show(help, stage, translations);
    }

    private void refreshWorkspaceTabs(CadDocument document, long revision) {
        if ((tool == Tool.EDIT_PROFILE || tool == Tool.OPERATION)
                && (revision != draftRevision || controller.generation() != draftGeneration)) {
            tool = Tool.NONE;
            viewport.clearHandle();
            viewport.isolateProfile(false);
            inspect();
        }
        printTab.setDisable(controller.build().bodies().isEmpty());
        if (printTab.isDisable() && printTab.isSelected()) {
            workspaces.getSelectionModel().select(designTab);
        }
        if (tool == Tool.PLANE && !planeSelectionCurrent()) {
            tool = Tool.NONE;
            planeSnapshot = null;
            planeRepair = null;
            viewport.stopPlaneSelection();
            inspect();
        }
        printer.documentChanged(document, controller.path());
        printerTab.setDisable(!printer.hasJobs());
        if (printerTab.isDisable() && printerTab.isSelected()) {
            workspaces.getSelectionModel().select(printTab.isDisable() ? designTab : printTab);
        }
    }

    private boolean featuresChanged(CadDocument document, List<Feature> effective) {
        return !lastFeatures.equals(effective)
                || !lastBodyStyles.equals(document.bodyStyles())
                || !lastBodyNames.equals(document.bodyNames())
                || !lastFeatureNames.equals(document.featureNames())
                || !lastParameters.equals(document.parameters())
                || !lastDocument.equals(document.id());
    }

    private void refreshBodies(CadDocument document, boolean changed) {
        if (!lastDocument.equals(document.id())) {
            selectedFeature = null;
            selectedBody = null;
            viewport.select(null);
            fitNext = true;
        }
        if (changed || lastBuild != controller.build()) rebuildTree(document);
        if (lastBuild != controller.build()) {
            viewport.showBodies(controller.build().bodies());
            viewport.showConstruction(controller.build().construction(), id -> {
                if (constructionEditor != null) constructionEditor.pickDatum(id);
                else {
                    selectFeature(id);
                }
            });
            if (fitNext && !controller.build().bodies().isEmpty()) {
                viewport.fit(meshes());
                fitNext = false;
            }
            lastBuild = controller.build();
            if (tool == Tool.NONE) {
                updateFeatureDiagnostics();
                var feature = selected();
                if (feature != null) selectionLabel.setText(shortName(feature));
            }
        }
    }

    private void refreshCommands(CadDocument document, ModelSession.Snapshot snapshot) {
        timeline.update(
                document,
                selectedFeature,
                controller.build().diagnostics(),
                controller.ioBusy() || tool != Tool.NONE || controller.hasPreview(),
                controller.rebuilding(),
                controller.build().valid());
        undo.setDisable(!snapshot.canUndo() || controller.ioBusy());
        redo.setDisable(!snapshot.canRedo() || controller.ioBusy());
        parameters.setDisable(controller.ioBusy() || tool != Tool.NONE || controller.rebuilding());
        var names = new ArrayList<String>();
        names.add("Default");
        document.parameters().configurations().forEach(c -> names.add(c.name()));
        if (!configuration.getItems().equals(names)) {
            configuration.getItems().setAll(names);
        }
        configuration.setValue(document.parameters().activeName());
        configuration.setDisable(controller.ioBusy() || tool != Tool.NONE);
        fileBusy.set(controller.ioBusy());
    }

    private void refreshSolidCommands() {
        if (tool == Tool.EXTRUDE && finish != null) {
            finish.setDisable(
                    !controller.canCommitExtrusion() || !error.getText().isEmpty());
        }
        splitBody.setDisable(tool != Tool.NONE || !controller.hasEditableBodies());
        fillet.setDisable(splitBody.isDisabled());
        chamfer.setDisable(splitBody.isDisabled());
        shell.setDisable(splitBody.isDisabled());
        printing.documentChanged();
        customFeature.setDisable(controller.ioBusy() || tool != Tool.NONE || controller.rebuilding());
        constrainedSketch.setDisable(customFeature.isDisabled());
    }

    private void refreshSketchCommands(CadDocument document) {
        if (controller.ioBusy()
                || tool != Tool.NONE
                || !(selected() instanceof Feature.Sketch sketch)
                || document.features().subList(0, document.cursor()).stream()
                        .noneMatch(f -> f.id().equals(sketch.id()))) {
            extrude.setDisable(true);
            cut.setDisable(true);
            return;
        }
        boolean available = !(sketch.profile() instanceof Feature.Constrained c)
                || validSketchProfiles.computeIfAbsent(
                        sketch.id(), id -> dev.voxelbrew.engine.sketch.SketchProfiles.hasProfile(c.geometry()));
        extrude.setDisable(!available);
        cut.setDisable(!available
                || sketch.profile() instanceof Feature.Rectangle
                || controller.rebuilding()
                || controller.build().bodies().isEmpty());
    }

    private void refreshStatus() {
        progress.setVisible(controller.rebuilding() || controller.ioBusy());
        String diagnostics = controller.build().diagnostics().stream()
                .filter(d -> d.state() == Evaluation.State.FAILED || d.state() == Evaluation.State.BLOCKED)
                .map(d -> d.featureId() + ": " + d.message())
                .reduce((a, b) -> a + " | " + b)
                .orElse("");
        status.setText(buildStatus(diagnostics));
        documentTitle.setText((controller.dirty() ? "● " : "")
                + (controller.path() == null
                        ? translations.translate("gui.untitled")
                        : controller.path().toAbsolutePath().normalize()));
        UiHelp.describe(
                documentTitle,
                controller.path() == null
                        ? translations.translate("help.unsaved-document")
                        : controller.path().toString());
        stage.setTitle((controller.dirty() ? "● " : "")
                + (controller.path() == null
                        ? translations.translate("gui.untitled")
                        : controller.path().toAbsolutePath().normalize())
                + " — VoxelBrew CAD");
    }

    private String buildStatus(String diagnostics) {
        if (!controller.error().isBlank()) return controller.error();
        if (controller.ioBusy()) {
            return translations.translate("gui.working_with_file");
        }
        if (controller.rebuilding()) {
            return translations.translate("gui.rebuilding");
        }
        if (!diagnostics.isBlank()) return diagnostics;
        if (controller.hasPreview()) {
            return translations.translate("gui.preview_release_or_enter_to_commit_escape_to_cancel");
        }
        return translations.translate(
                "status.bodies",
                controller.build().bodies().size(),
                String.format(
                        Locale.ROOT,
                        "%.3f mm³",
                        controller.build().bodies().stream()
                                .mapToDouble(DocumentController.Body::volume)
                                .sum()));
    }
}
