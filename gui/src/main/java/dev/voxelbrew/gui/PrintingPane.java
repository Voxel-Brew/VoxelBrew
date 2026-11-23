package dev.voxelbrew.gui;

import dev.voxelbrew.api.exporter.ExporterProviderRegistry;
import dev.voxelbrew.api.i18n.TranslationService;
import dev.voxelbrew.api.model.BodyInstance;
import dev.voxelbrew.api.model.Transform;
import dev.voxelbrew.api.model.mesh.*;
import dev.voxelbrew.api.model.parameters.*;
import dev.voxelbrew.api.model.printing.*;
import dev.voxelbrew.api.printer.PrinterProfileCatalog;
import dev.voxelbrew.api.slicer.*;
import dev.voxelbrew.api.userconfig.UserConfigException;
import dev.voxelbrew.engine.parameters.*;
import dev.voxelbrew.engine.persistence.DocumentCodec;
import dev.voxelbrew.engine.printing.*;
import dev.voxelbrew.printer.ProfileHardware;
import dev.voxelbrew.slicer.SliceService;
import dev.voxelbrew.slicer.SlicerInstallation;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.util.StringConverter;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.StyleClassedTextArea;
import org.fxmisc.richtext.util.UndoUtils;

/** Build placement and actual toolpath preview, sharing all validation and slicing with CLI. */
final class PrintingPane extends BorderPane implements AutoCloseable {
    private final SlicerWorkspace workspace = new SlicerWorkspace();

    private final Map<String, ButtonBase> quickActions = new LinkedHashMap<>();

    private final PrinterProfileCatalog printers;

    private final PrinterVisibility printerVisibility;

    private final Button printerChoice = new Button();

    private final Label printerProfileLabel = new Label();

    private final ComboBox<String> printTarget = new ComboBox<>();

    private final dev.voxelbrew.api.printer.PrinterStore printerRuntime;

    private final Label printerDetails = new Label();

    private final FormulaField nozzle;

    private final javafx.scene.image.ImageView printerImage = new javafx.scene.image.ImageView();

    private final DocumentController document;

    private final PrintController jobs;

    private final SlicerProviderRegistry slicers;

    private final SlicerSettings slicerSettings;

    private final ExecutorService validator;

    private final TranslationService translations;

    private final Label plateSize = new Label();

    private final ToggleButton toolpathPreview = new ToggleButton();

    private final Canvas canvas = new Canvas();
    private final Canvas dragOverlay = new Canvas();

    private final PrintNavigationView navigationView =
            Platform.isSupported(javafx.application.ConditionalFeature.SCENE3D) ? new PrintNavigationView() : null;
    private final javafx.animation.PauseTransition navigationIdle =
            new javafx.animation.PauseTransition(javafx.util.Duration.millis(160));
    private final javafx.animation.AnimationTimer navigationFrame = new javafx.animation.AnimationTimer() {
        @Override
        public void handle(long now) {
            stop();
            if (dragSource != null && rotationDrag == null && navigationView != null) {
                drawTranslation();
            } else {
                draw(true);
            }
        }
    };

    private final HBox plateStrip = new HBox(6);

    private final VBox previewControls = new VBox(10);

    private final Label placementHint = new Label();

    private final PrintCamera topCamera = new PrintCamera(false), spatialCamera = new PrintCamera(true);

    private final ComboBox<PrintRotationGizmo.Space> rotationSpace = new ComboBox<>();

    private final CheckBox localGizmoAxes = new CheckBox();

    private final CheckBox showRotationGizmos = new CheckBox();

    private final ComboBox<String> targetPlate = new ComboBox<>();

    private final ComboBox<String> bodies = new ComboBox<>(),
            instances = new ComboBox<>(),
            materials = new ComboBox<>(),
            supportIds = new ComboBox<>(),
            supportOwners = new ComboBox<>();

    private final ComboBox<SlicerInstallation> backends = new ComboBox<>();

    private final ComboBox<SupportVolume.Role> supportRole = new ComboBox<>();

    private final Map<String, FormulaField> fields = new LinkedHashMap<>();

    private final CheckBox supportsEnabled = new CheckBox();

    private final CheckBox autoSupports = new CheckBox(""), bedOnly = new CheckBox("");

    private final Slider layer = new Slider(0, 0, 0);

    private final Label layerLabel = new Label(""),
            validationLabel = new Label(""),
            status = new Label(""),
            summary = new Label();

    private final StyleClassedTextArea diagnostics = new StyleClassedTextArea();

    private final Hyperlink jobDetails = new Hyperlink();

    private final EnumMap<GcodeJob.Role, CheckBox> roles = new EnumMap<>(GcodeJob.Role.class);

    private final Button slice;

    private final Button sliceAll;

    private final Button cleanSlicing;

    private final Button cancel;

    private final ProgressIndicator progress = new ProgressIndicator();

    private final Tooltip selectedObjectTooltip = UiHelp.tooltip("");

    private final Label selectedObjectName = new Label();

    private final TabPane tabs = new TabPane();

    private Runnable export3mf = () -> {};

    private int failureStart;

    private Set<String> hiddenPrinters = Set.of();

    private PrinterProfileCatalog.Result printerCatalog;

    private Map<String, String> printTargetNames = Map.of();

    private Map<String, dev.voxelbrew.api.printer.Printer> printTargetDevices = Map.of();

    private Runnable configureDevicesAction = () -> {};

    private Future<?> validationTask;

    private long validationSequence;

    private boolean threeDimensional;

    private Button autoArrange;

    private Button orientAll;

    private boolean orienting;

    private double plateWidth = 200, plateDepth = 200, plateMinX, plateMinY;

    private ViewCube viewCube;

    private TextField plateNameEditor;

    private double viewScale, viewOx, viewOy;

    private PrintDepthBuffer placementRaster;

    private double placementRasterScale = 1;

    private boolean navigating, primaryNavigation;
    private double navigationX, navigationY;
    private double dragX, dragY;
    private BodyInstance dragSource, dragPreview;
    private Point2D detailedDragOffset = Point2D.ZERO;

    private String dragRevision;

    private List<PrintRotationGizmo.Ring> rotationRings = List.of();

    private PrintRotationGizmo.Drag rotationDrag;

    private PrintRotationGizmo.Axis hoveredRotation;

    private double rotationDegrees;

    private Button export3mfButton;

    private ManufacturingModel model;

    private PrintValidation.Result validation;

    private PrintSetup lastSetup;

    private DocumentController.Build lastBuild;

    private String validatedRevision = "";

    private int selectedFaceIndex;

    private String selectedFaceBody;

    private boolean updating, dark, closed;

    private List<Double> previewLayers = List.of();

    private SliceService.Result displayed;

    private static Color rotationColor(PrintRotationGizmo.Axis axis) {
        return switch (axis) {
            case X -> Color.web("#e46969");
            case Y -> Color.web("#57ad74");
            case Z -> Color.web("#728ef5");
        };
    }

    private static HBox equalRow(Button... buttons) {
        var row = new HBox(6);
        for (var button : buttons) {
            button.setMinWidth(0);
            button.setPrefWidth(0);
            button.setMaxWidth(Double.MAX_VALUE);
            button.setWrapText(true);
            HBox.setHgrow(button, Priority.ALWAYS);
            row.getChildren().add(button);
        }
        return row;
    }

    static Path jobDestination(Path project, String plateName, int plateNumber, int plateCount) {
        if (plateNumber < 1 || plateNumber > plateCount) throw new IllegalArgumentException("Invalid plate number");
        Path filename = project.getFileName();
        if (filename == null) throw new IllegalArgumentException("Expected a project file: " + project);
        String name = filename.toString();
        int extension = name.lastIndexOf('.');
        String number =
                String.format(Locale.ROOT, "%0" + Integer.toString(plateCount).length() + "d", plateNumber);
        StringBuilder escaped = new StringBuilder();
        int trailing = plateName.length();
        while (trailing > 0 && (plateName.charAt(trailing - 1) == '.' || plateName.charAt(trailing - 1) == ' ')) {
            trailing--;
        }
        for (int i = 0; i < plateName.length(); i++) {
            char character = plateName.charAt(i);
            if (Character.isISOControl(character) || "<>:\"/\\|?*%".indexOf(character) >= 0 || i >= trailing) {
                escaped.append(String.format(Locale.ROOT, "%%%02X", (int) character));
            } else {
                escaped.append(character);
            }
        }
        return project.resolveSibling(
                (extension > 0 ? name.substring(0, extension) : name) + "-" + number + "-" + escaped + ".gcode");
    }

    private static Color color(GcodeJob.Role role) {
        return switch (role) {
            case EXTERNAL_PERIMETER -> Color.DEEPSKYBLUE;
            case PERIMETER -> Color.ROYALBLUE;
            case INFILL, SOLID_INFILL, GAP_FILL, BRIDGE, IRONING -> Color.DARKORANGE;
            case SUPPORT -> Color.LIMEGREEN;
            case INTERFACE -> Color.MEDIUMPURPLE;
            case SKIRT_BRIM -> Color.CRIMSON;
            case TRAVEL, UNKNOWN -> Color.GRAY;
        };
    }

    PrintingPane(
            PrinterProfileCatalog printers,
            dev.voxelbrew.api.printer.PrinterStore printerRuntime,
            PrinterVisibility printerVisibility,
            DocumentController document,
            PrintController jobs,
            SlicerProviderRegistry slicers,
            SlicerSettings slicerSettings,
            ExporterProviderRegistry exporters,
            ExecutorService validator,
            TranslationService translations) {
        this.translations = translations;
        this.printers = printers;
        this.printerRuntime = printerRuntime;
        this.printerVisibility = printerVisibility;
        autoSupports.setText(translations.translate("gui.automatic_supports"));
        bedOnly.setText(translations.translate("gui.build_plate_only"));
        layerLabel.setText(translations.translate("gui.no_sliced_layers"));
        validationLabel.setText(translations.translate("gui.add_a_build_instance"));
        status.setText(translations.translate("gui.ready_to_slice"));
        this.document = document;
        nozzle = FormulaField.number(
                "0.4", () -> document.snapshot().document().parameters(), ParameterUnit.MM, translations);
        nozzle.setId("print-nozzle");
        nozzle.bounds(0, false, 5, true);
        nozzle.dragStep(0.05);
        nozzle.setPrefColumnCount(5);
        nozzle.setPrefWidth(170);
        nozzle.setMaxWidth(170);
        nozzle.validateWith(value -> {
            if (!Double.isFinite(value) || value <= 0 || value > 5) {
                throw new IllegalArgumentException(translations.translate("print.nozzleRange"));
            }
        });
        nozzle.setOnAction(event -> saveNozzle());
        nozzle.focusedProperty().addListener((o, before, focused) -> {
            if (!focused) saveNozzle();
        });
        this.jobs = jobs;
        this.slicers = slicers;
        this.slicerSettings = slicerSettings;
        this.validator = validator;
        configureWorkspaceControls();
        var placement = placementControls();
        var nozzleRow = new HBox(8, new Label(translations.translate("print.nozzleDiameter")), nozzle);
        nozzleRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        var process = new VBox(
                7,
                nozzleRow,
                new Label(translations.translate("gui.slicer_backend")),
                backends,
                new Label(translations.translate("gui.material")),
                materials,
                row("print-layer-height", translations.translate("gui.layer_height_mm"), "0.2"),
                row("print-walls", translations.translate("gui.perimeter_walls"), "2"),
                row("print-infill", translations.translate("gui.infill"), "20"),
                row("print-threshold", translations.translate("gui.support_threshold_degrees_from_horizontal"), "45"),
                row("print-gap", translations.translate("gui.support_contact_gap_mm"), "0.2"),
                row("print-interfaces", translations.translate("gui.interface_layers"), "3"),
                row("print-brim", translations.translate("gui.brim_width_mm"), "0"),
                row("print-raft", translations.translate("gui.raft_layers"), "0"));

        var supportsTab = supportControls();
        slice = button(translations.translate("gui.slice"), "print-slice", this::slice);
        sliceAll = button(translations.translate("print.sliceAll"), "print-slice-all", () -> slice(true));
        cancel = button(translations.translate("gui.cancel_slicing"), "print-cancel", jobs::cancel);
        cleanSlicing = button(translations.translate("print.cleanSlicing"), "print-clean-slicing", () -> {
            document.invalidateGcode();
            jobs.clear();
            draw();
        });
        cleanSlicing.setMaxWidth(Double.MAX_VALUE);
        var logScroll = new VirtualizedScrollPane<>(diagnostics);
        var job = new VBox(7, summary, logScroll, cleanSlicing);
        VBox.setVgrow(logScroll, Priority.ALWAYS);
        logScroll.setMinHeight(60);
        diagnostics.setEditable(false);
        diagnostics.setWrapText(true);
        diagnostics.setUndoManager(UndoUtils.noOpUndoManager());
        diagnostics.setUseInitialStyleForInsertion(true);
        diagnostics.setId("print-diagnostics");
        summary.setWrapText(true);
        var jobTab = tab("Job", job);
        tabs.getTabs()
                .setAll(tab("Placement", placement), tab("Process", process), tab("Supports", supportsTab), jobTab);
        jobDetails.setId("print-job-details");
        jobDetails.setText(translations.translate("print.jobDetails", jobTab.getText()));
        jobDetails.setWrapText(true);
        jobDetails.managedProperty().bind(jobDetails.visibleProperty());
        jobDetails.setOnAction(event -> {
            tabs.getSelectionModel().select(jobTab);
            diagnostics.requestFocus();
            diagnostics.moveTo(failureStart);
            diagnostics.requestFollowCaret();
        });
        tabs.setId("print-controls");
        tabs.setPrefWidth(360);
        tabs.setMinWidth(320);
        configurePrinterSidebar();
        localGizmoAxes.setId("print-gizmo-local");
        localGizmoAxes.setText(translations.translate("print.localGizmoAxes"));
        UiHelp.install(localGizmoAxes, translations);
        localGizmoAxes.setManaged(false);
        localGizmoAxes.setVisible(false);
        localGizmoAxes.setOnAction(event -> rotationSpace.setValue(
                localGizmoAxes.isSelected() ? PrintRotationGizmo.Space.LOCAL : PrintRotationGizmo.Space.WORLD));
        selectedObjectName.setId("print-selected-object-name");
        selectedObjectName.setManaged(false);
        var removeObject = button("", "print-remove", this::removeInstance);
        var closeIcon = new javafx.scene.shape.SVGPath();
        closeIcon.setContent("M 1 1 L 9 9 M 9 1 L 1 9");
        closeIcon.getStyleClass().add("object-close-icon");
        closeIcon.setMouseTransparent(true);
        removeObject.setGraphic(closeIcon);
        removeObject.getStyleClass().add("object-close-button");
        removeObject.setAccessibleText(translations.translate("gui.remove_instance"));
        UiHelp.describe(removeObject, translations.translate("gui.remove_instance"));
        selectedObjectName.setGraphic(removeObject);
        selectedObjectName.setContentDisplay(ContentDisplay.RIGHT);
        selectedObjectName.setVisible(false);
        selectedObjectName.getStyleClass().add("selected-object-label");
        var center = new StackPane(canvas, localGizmoAxes, selectedObjectName);
        if (navigationView != null) {
            center.getChildren().addAll(1, List.of(navigationView, dragOverlay));
            navigationView.widthProperty().bind(center.widthProperty());
            navigationView.heightProperty().bind(center.heightProperty());
            dragOverlay.widthProperty().bind(center.widthProperty());
            dragOverlay.heightProperty().bind(center.heightProperty());
            dragOverlay.setManaged(false);
            dragOverlay.setMouseTransparent(true);
            dragOverlay.setVisible(false);
        }
        navigationIdle.setOnFinished(event -> draw());
        center.setMinSize(150, 150);
        canvas.setManaged(false);
        canvas.widthProperty().bind(center.widthProperty());
        canvas.heightProperty().bind(center.heightProperty());
        canvas.widthProperty().addListener((o, a, b) -> draw());
        canvas.heightProperty().addListener((o, a, b) -> draw());
        var viewMode = new ToggleGroup();
        var topView = new RadioButton("2D");
        var solidView = new RadioButton("3D");
        topView.setId("print-view-2d");
        solidView.setId("print-view-3d");
        quickActions.put(topView.getId(), topView);
        quickActions.put(solidView.getId(), solidView);
        UiHelp.install(topView, translations);
        UiHelp.install(solidView, translations);
        topView.setToggleGroup(viewMode);
        solidView.setToggleGroup(viewMode);
        topView.setSelected(true);
        viewMode.selectedToggleProperty().addListener((o, a, b) -> {
            cancelDrag();
            navigating = false;
            threeDimensional = b == solidView;
            draw();
        });
        installViewCube(center, viewMode, topView, solidView);
        plateSize.setId("print-plate-size");
        plateSize.setTooltip(new Tooltip(translations.translate("print.editPlate")));
        plateSize.setOnMouseClicked(e -> {
            plateSize.requestFocus();
            if (e.getClickCount() == 2) editPlate();
        });
        plateSize.setFocusTraversable(true);
        plateSize.setOnKeyPressed(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ENTER) {
                editPlate();
                e.consume();
            }
        });
        var preview = new BorderPane(center);
        toolpathPreview.setText(translations.translate("print.preview"));
        toolpathPreview.setId("print-preview");
        quickActions.put(toolpathPreview.getId(), toolpathPreview);
        UiHelp.describe(toolpathPreview, translations.translate("print.previewHelp"));
        toolpathPreview.setOnAction(event -> {
            updatePreviewControls();
            draw();
        });
        var viewBar = new FlowPane(
                12,
                8,
                topView,
                solidView,
                new Separator(javafx.geometry.Orientation.VERTICAL),
                plateSize,
                toolpathPreview);
        var fitView = new Button(translations.translate("print.fitView"));
        fitView.setId("print-fit-view");
        quickActions.put(fitView.getId(), fitView);
        UiHelp.describe(fitView, translations.translate("print.cameraHelp"));
        fitView.setOnAction(event -> {
            cancelDrag();
            camera().fit();
            draw();
        });
        autoArrange = button(translations.translate("print.autoArrange"), "print-auto-arrange", this::autoArrange);
        orientAll = button(translations.translate("print.orientAll"), "print-orient-all", () -> autoOrient(true));
        viewBar.getChildren().addAll(fitView, new HBox(6, autoArrange, orientAll));
        canvas.setAccessibleHelp(
                translations.translate("print.cameraHelp") + "\n" + translations.translate("print.rotationGizmoHelp"));
        placementHint.setTooltip(UiHelp.tooltip(translations.translate("print.cameraHelp")));
        viewBar.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        viewBar.getStyleClass().add("slicer-toolbar");
        plateStrip.setId("print-plates");
        var plateScroll = new ScrollPane(plateStrip);
        plateScroll.setFitToHeight(true);
        plateScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        plateScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        var plateBar = new BorderPane(plateScroll);
        plateBar.setRight(button(
                translations.translate("print.addPlate"),
                "print-add-plate",
                () -> commit(setup().addPlate("plate-"
                        + document.snapshot().document().uuidGenerator().next()))));
        plateBar.getStyleClass().add("slicer-toolbar");
        placementHint.setText(
                translations.translate("print.dragHint") + " " + translations.translate("print.rotationGizmoHelp"));
        placementHint.setWrapText(true);
        placementHint.getStyleClass().add("slicer-hint");
        preview.setTop(new VBox(8, plateBar, viewBar));
        preview.setBottom(placementHint);
        setCenter(preview);
        var legend = configurePreviewControls();
        var statusLine = new HBox(4, status, jobDetails);
        statusLine.setAlignment(javafx.geometry.Pos.BASELINE_LEFT);
        var jobStatus = new VBox(4, statusLine, validationLabel);
        jobStatus.setMinWidth(0);
        status.setMinWidth(0);
        validationLabel.setMinWidth(0);
        slice.setMinWidth(Region.USE_PREF_SIZE);
        cancel.setMinWidth(Region.USE_PREF_SIZE);
        HBox.setHgrow(jobStatus, Priority.ALWAYS);
        export3mfButton = button(translations.translate("print.export3mf"), "print-export-3mf", () -> export3mf.run());
        var actions = new HBox(12, progress, jobStatus, cancel, export3mfButton, sliceAll, slice);
        actions.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        actions.getStyleClass().add("slicer-status-bar");
        slice.getStyleClass().add("slicer-primary-action");
        slice.setText(translations.translate("print.slicePlate"));
        setBottom(new VBox(8, previewControls, actions));
        BorderPane.setMargin(tabs, new Insets(0, 0, 0, 8));
        bindWorkspaceSettings(viewMode, solidView, topView, legend);
        jobs.onChanged(this::updateJob);
        updateJob();
    }

    private void configureWorkspaceControls() {
        setId("printing-workspace");
        getStyleClass().add("slicer-workspace");
        addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.DELETE
                    && !(getScene().getFocusOwner() instanceof TextInputControl)
                    && instances.getValue() != null) {
                removeInstance();
                event.consume();
            }
        });
        canvas.setId("print-canvas");
        canvas.setFocusTraversable(true);
        installDragging();
        setPadding(new Insets(8));
        bodies.setId("print-body");
        UiText.choices(bodies, this::bodyName);
        bodies.setMinWidth(220);
        UiText.choices(instances, this::instanceName);
        UiText.choices(supportOwners, this::instanceName);
        for (var choice : List.of(bodies, instances, supportOwners)) {
            choice.setCellFactory(list -> namedCell(choice));
            choice.setButtonCell(namedCell(choice));
        }
        for (var choice : List.of(instances, supportOwners, supportIds)) {
            choice.setMinWidth(220);
            choice.disableProperty().bind(javafx.beans.binding.Bindings.isEmpty(choice.getItems()));
            choice.setPromptText(
                    translations.translate(choice == supportIds ? "print.noSupports" : "print.noInstances"));
        }
        instances.setId("print-instance");
        materials.setId("print-material");
        backends.setId("print-backend");
        backends.setPromptText(translations.translate("slicer.installation.choose"));
        supportIds.setId("print-support-id");
        supportOwners.setId("print-support-instance");
        supportRole.setId("print-support-role");
        UiText.enums(supportRole, translations, "print.support.");
        autoSupports.setId("print-auto-supports");
        bedOnly.setId("print-bed-only");
        for (var check : List.of(autoSupports, bedOnly)) UiHelp.install(check, translations);
        materials.getItems().setAll("PLA", "PETG");
        supportRole.getItems().setAll(SupportVolume.Role.values());
        supportRole.setValue(SupportVolume.Role.SOLID_SUPPORT);
        backends.getItems().setAll(operationalSlicers());
        backends.setConverter(new StringConverter<>() {
            @Override
            public String toString(SlicerInstallation descriptor) {
                return descriptor == null ? "" : descriptor.name();
            }

            @Override
            public SlicerInstallation fromString(String text) {
                throw new UnsupportedOperationException();
            }
        });
    }

    private VBox placementControls() {
        bodies.setMinWidth(0);
        bodies.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(bodies, Priority.ALWAYS);
        var addBody = button(translations.translate("gui.add_selected_body"), "print-add", this::addInstance);
        addBody.setMinWidth(Region.USE_PREF_SIZE);
        var bodyRow = new HBox(8, bodies, addBody);
        bodyRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        var placement = new VBox(
                7,
                new Label(translations.translate("gui.cad_body")),
                bodyRow,
                new Separator(),
                new Label(translations.translate("gui.build_instance")),
                instances);
        showRotationGizmos.setId("print-show-rotation-gizmos");
        showRotationGizmos.setText(translations.translate("print.showRotationGizmos"));
        UiHelp.install(showRotationGizmos, translations);
        showRotationGizmos.selectedProperty().addListener((observable, before, after) -> {
            cancelDrag();
            draw();
        });
        rotationSpace.setId("print-rotation-space");
        rotationSpace.getItems().setAll(PrintRotationGizmo.Space.values());
        rotationSpace.setValue(PrintRotationGizmo.Space.WORLD);
        UiText.enums(rotationSpace, translations, "print.rotationSpace.");
        UiHelp.install(rotationSpace, translations);
        rotationSpace
                .disableProperty()
                .bind(showRotationGizmos.selectedProperty().not());
        rotationSpace.setOnAction(event -> {
            localGizmoAxes.setSelected(rotationSpace.getValue() == PrintRotationGizmo.Space.LOCAL);
            cancelDrag();
            draw();
        });
        rotationSpace.setMinWidth(0);
        rotationSpace.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(rotationSpace, Priority.ALWAYS);
        var gizmoControls = new HBox(8, showRotationGizmos, rotationSpace);
        gizmoControls.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        placement.getChildren().addAll(placementCoordinates(false), placementCoordinates(true));
        var applyPlacement = button(translations.translate("gui.apply_placement"), "print-place", this::applyPlacement);
        applyPlacement.setMaxWidth(Double.MAX_VALUE);
        placement
                .getChildren()
                .addAll(
                        applyPlacement,
                        gizmoControls,
                        new Separator(),
                        equalRow(
                                button(translations.translate("gui.lay_face_on_bed"), "print-lay-face", this::layFace),
                                button(
                                        translations.translate("print.autoOrient"),
                                        "print-auto-orient",
                                        this::autoOrient)));
        instances.setOnAction(e -> {
            if (!updating) {
                loadPlacement();
                draw();
            }
        });
        targetPlate.setId("print-target-plate");
        targetPlate.setMaxWidth(Double.MAX_VALUE);
        UiText.choices(targetPlate, this::plateName);
        targetPlate.setMinWidth(0);
        HBox.setHgrow(targetPlate, Priority.ALWAYS);
        var moveObject = button(translations.translate("print.moveObject"), "print-move-plate", () -> {
            String target = targetPlate.getValue();
            if (target != null) commit(setup().moveInstance(selected().id(), target));
        });
        moveObject.setMinWidth(Region.USE_PREF_SIZE);
        var moveRow = new HBox(8, targetPlate, moveObject);
        moveRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        placement
                .getChildren()
                .addAll(new Separator(), new Label(translations.translate("print.moveToPlate")), moveRow);

        return placement;
    }

    private VBox supportControls() {
        supportsEnabled.setId("print-supports-enabled");
        supportsEnabled.setText(translations.translate("print.supportsEnabled"));
        supportsEnabled.setSelected(false);
        UiHelp.install(supportsEnabled, translations);
        supportsEnabled.setOnAction(event -> {
            if (!updating) commit(setup().withSupportsDisabled(!supportsEnabled.isSelected()));
        });
        autoSupports.disableProperty().bind(supportsEnabled.selectedProperty().not());
        bedOnly.disableProperty().bind(supportsEnabled.selectedProperty().not());
        for (String id : List.of("threshold", "gap", "interfaces", "raft")) {
            fields.get("print-" + id)
                    .disableProperty()
                    .bind(supportsEnabled.selectedProperty().not());
        }
        var supports = new VBox(
                7,
                new Label(translations.translate("gui.existing_support_instruction")),
                supportIds,
                new Label(translations.translate("gui.attached_build_instance")),
                supportOwners,
                new Label(translations.translate("gui.role")),
                supportRole,
                row("print-support-name", translations.translate("gui.stable_support_id"), "support-1"));
        for (var name : List.of("x", "y", "z", "width", "depth", "height")) {
            supports.getChildren()
                    .add(row(
                            "print-support-" + name,
                            translations.translate("print.bodySpace", name),
                            List.of("x", "y", "z").contains(name) ? "0" : "5"));
        }
        supports.getChildren()
                .addAll(
                        button(
                                translations.translate("gui.add_update_support"),
                                "print-support-apply",
                                this::applySupport),
                        button(
                                translations.translate("gui.remove_support"),
                                "print-support-remove",
                                this::removeSupport),
                        new Label(translations.translate(
                                "gui.portable_export_choose_build_space_then_model_support_solids_or_both")));
        var generated =
                button(translations.translate("gui.export_generated_support_mesh"), "print-generated-support", () -> {
                    throw new IllegalStateException(translations.translate(
                            "gui.this_backend_provides_support_toolpaths_not_generated_support_meshes"));
                });
        generated.setDisable(true);
        UiHelp.describe(
                generated,
                translations.translate(
                        "gui.generated_support_meshes_are_unavailable_explicit_support_pillars_can_be_exported"));
        supports.getChildren().add(generated);
        supports.disableProperty().bind(supportsEnabled.selectedProperty().not());
        for (var child : supports.getChildren()) {
            if (child instanceof Control control) control.setMaxWidth(Double.MAX_VALUE);
            if (child instanceof Label label) label.setWrapText(true);
        }
        var supportsHelp = new Label(translations.translate("print.supportsEnabledHelp"));
        supportsHelp.setWrapText(true);
        var supportsTab = new VBox(7, supportsEnabled, supportsHelp, new Separator(), autoSupports, bedOnly, supports);

        supportIds.setOnAction(e -> {
            if (!updating) loadSupport();
        });

        return supportsTab;
    }

    private void configurePrinterSidebar() {
        printerChoice.setId("print-printer");
        printTarget.setId("print-target");
        var lockProfile = printTarget
                .disableProperty()
                .or(printTarget
                        .valueProperty()
                        .isNotNull()
                        .and(printTarget.valueProperty().isNotEqualTo("")));
        printerChoice.disableProperty().bind(lockProfile);
        plateSize.disableProperty().bind(lockProfile);
        printTarget.setMaxWidth(Double.MAX_VALUE);
        printerProfileLabel.setId("print-printer-profile-label");
        printTarget.setPromptText(translations.translate("print.profileOnly"));
        printTarget.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(String value) {
                return value == null || value.isBlank()
                        ? translations.translate("print.profileOnly")
                        : printTargetNames.getOrDefault(value, translations.translate("print.unavailablePrinter"));
            }

            @Override
            public String fromString(String value) {
                return value;
            }
        });
        printTarget.setCellFactory(list -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : printTarget.getConverter().toString(item));
            }
        });
        printTarget.setButtonCell(new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(printTarget.getConverter().toString(printTarget.getValue()));
            }
        });
        printTarget.valueProperty().addListener((observed, before, selected) -> {
            if (updating || java.util.Objects.equals(before, selected)) return;
            if (selected == null || selected.isBlank()) {
                commit(setup().withPrintTarget(null));
                return;
            }
            var device = printTargetDevices.get(selected);
            if (device == null) {
                status.setText("The selected printer is missing. Configure it again or choose a standalone profile.");
                return;
            }
            commit(setup().withPrinter(ProfileHardware.resolve(device.profile()))
                    .withPrintTarget(selected));
        });
        UiHelp.install(printerChoice, translations);
        printerChoice.setMaxWidth(Double.MAX_VALUE);
        printerChoice.setOnAction(event -> PrinterPickerDialog.choose(
                        getScene().getWindow(),
                        translations,
                        printerCatalog.entries().stream()
                                .filter(this::printerVisible)
                                .toList(),
                        setup().printerId())
                .ifPresent(chosen -> commit(setup().withPrinter(ProfileHardware.resolve(chosen.definition())))));
        printerDetails.setWrapText(true);
        printerDetails.setId("print-printer-details");
        printerImage.setFitWidth(96);
        printerImage.setFitHeight(72);
        printerImage.setPreserveRatio(true);
        var configureDevices =
                button(translations.translate("device.configure"), "print-configure-devices", this::configureDevices);
        configureDevices.setMinWidth(Region.USE_PREF_SIZE);
        configureDevices.disableProperty().bind(printTarget.disableProperty());
        var deviceRow = new HBox(6, printTarget, configureDevices);
        HBox.setHgrow(printTarget, Priority.ALWAYS);
        var printerPanel = new VBox(
                6,
                new Label(translations.translate("print.sliceTarget")),
                deviceRow,
                printerProfileLabel,
                printerChoice,
                new HBox(8, printerImage, printerDetails));
        printerPanel.setPadding(new Insets(8));
        printerPanel.setMaxWidth(360);
        var sidebar = new VBox(6, printerPanel, tabs);
        VBox.setVgrow(tabs, Priority.ALWAYS);
        setRight(sidebar);
        reloadPrinters();
    }

    private void bindWorkspaceSettings(
            ToggleGroup viewMode, RadioButton solidView, RadioButton topView, TitledPane legend) {
        fields.forEach((key, field) -> workspace.add(key, field::getText, field::setText));
        for (var choice : List.of(bodies, instances, materials, supportIds, supportOwners, targetPlate)) {
            workspace.add(choice.getId(), choice::getValue, value -> choice.setValue(value.isEmpty() ? null : value));
        }
        workspace.add(
                "print-support-role",
                () -> supportRole.getValue().name(),
                value -> supportRole.setValue(SupportVolume.Role.valueOf(value)));
        workspace.add(
                "print-rotation-space",
                () -> rotationSpace.getValue().name(),
                value -> rotationSpace.setValue(PrintRotationGizmo.Space.valueOf(value)));
        var checks = new ArrayList<CheckBox>(roles.values());
        checks.addAll(List.of(autoSupports, bedOnly, showRotationGizmos));
        for (var check : checks) {
            workspace.add(
                    check.getId(),
                    () -> Boolean.toString(check.isSelected()),
                    value -> check.setSelected(Boolean.parseBoolean(value)));
        }
        workspace.add(
                "print-view-3d",
                () -> Boolean.toString(threeDimensional),
                value -> viewMode.selectToggle(Boolean.parseBoolean(value) ? solidView : topView));
        workspace.add(
                "print-tab",
                () -> Integer.toString(tabs.getSelectionModel().getSelectedIndex()),
                value -> tabs.getSelectionModel().select(Integer.parseInt(value)));
        workspace.add(
                "print-legend",
                () -> Boolean.toString(legend.isExpanded()),
                value -> legend.setExpanded(Boolean.parseBoolean(value)));
        for (String id : List.of("layer-height", "walls", "infill", "threshold", "gap", "interfaces", "brim", "raft")) {
            var field = fields.get("print-" + id);
            field.setOnAction(event -> saveProcess());
            field.focusedProperty().addListener((o, before, focused) -> {
                if (!focused) saveProcess();
            });
        }
        backends.setOnAction(event -> saveProcess());
        materials.setOnAction(event -> saveProcess());
        autoSupports.setOnAction(event -> saveProcess());
        bedOnly.setOnAction(event -> saveProcess());
        workspace.initialize();
        fields.values().forEach(field -> field.textProperty().addListener((o, before, after) -> invalidateJob()));
        for (var choice : List.of(materials, backends, supportRole, supportOwners, rotationSpace)) {
            choice.valueProperty().addListener((o, before, after) -> invalidateJob());
        }
        for (var check : List.of(autoSupports, bedOnly, supportsEnabled)) {
            check.selectedProperty().addListener((o, before, after) -> invalidateJob());
        }
        document.printWorkspace(() -> {
            var settings = new LinkedHashMap<>(workspace.capture());
            String installation = SlicerInstallation.selectedId(setup());
            if (installation.isBlank()) settings.remove(SlicerInstallation.SELECTION);
            else {
                settings.put(SlicerInstallation.SELECTION, installation);
            }
            return settings;
        });
    }

    private void installViewCube(StackPane center, ToggleGroup viewMode, RadioButton topView, RadioButton solidView) {
        viewCube = new ViewCube(
                orientation -> {
                    cancelDrag();
                    boolean top = orientation.equals(ViewOrientation.named("Top"));
                    if (!top) spatialCamera.orient(orientation);
                    viewMode.selectToggle(top ? topView : solidView);
                    draw();
                },
                (dx, dy) -> {
                    cancelDrag();
                    if (!threeDimensional) spatialCamera.orient(topCamera.orientation());
                    spatialCamera.orbit(dx, dy);
                    viewMode.selectToggle(solidView);
                    drawNavigation();
                },
                () -> {
                    cancelDrag();
                    camera().orient(threeDimensional ? ViewOrientation.HOME : ViewOrientation.named("Top"));
                    camera().fit();
                    draw();
                },
                () -> {
                    camera().fit();
                    draw();
                },
                translations);
        viewCube.setId("print-view-cube");
        StackPane.setAlignment(viewCube, javafx.geometry.Pos.TOP_RIGHT);
        StackPane.setMargin(viewCube, new Insets(12));
        center.getChildren().add(viewCube);
    }

    private TitledPane configurePreviewControls() {
        layer.setId("print-layer");
        layer.setBlockIncrement(1);
        layer.valueProperty().addListener((o, a, b) -> draw());
        var filters = new TilePane(12, 8);
        filters.setPrefColumns(4);
        for (var role : GcodeJob.Role.values()) {
            var check = new CheckBox(translations.translate("print.role." + role.name()));
            check.setId("print-role-" + role.name().toLowerCase(Locale.ROOT));
            UiHelp.install(check, translations);
            check.setSelected(role != GcodeJob.Role.TRAVEL);
            check.setOnAction(e -> draw());
            var swatch = new javafx.scene.shape.Rectangle(10, 10, color(role));
            check.setGraphic(swatch);
            check.setMinWidth(155);
            roles.put(role, check);
            filters.getChildren().add(check);
        }
        progress.setPrefSize(18, 18);
        progress.setMaxSize(18, 18);
        status.setId("print-status");
        status.setWrapText(true);
        validationLabel.setId("print-validation");
        validationLabel.setWrapText(true);
        var legend = new TitledPane(translations.translate("print.toolpathLegend"), filters);
        legend.setExpanded(false);
        legend.setId("print-legend");
        layerLabel.getStyleClass().add("slicer-section-title");
        previewControls.getChildren().addAll(layerLabel, layer, legend);
        previewControls.getStyleClass().add("slicer-preview-controls");
        previewControls.managedProperty().bind(previewControls.visibleProperty());
        return legend;
    }

    private void refreshPrintSetup() {
        rebuildPlateStrip();
        String selected = instances.getValue();
        instances
                .getItems()
                .setAll(setup().instances().stream().map(BodyInstance::id).toList());
        instances.setValue(
                instances.getItems().contains(selected)
                        ? selected
                        : instances.getItems().stream().findFirst().orElse(null));
        supportOwners.getItems().setAll(instances.getItems());
        if (!supportOwners.getItems().contains(supportOwners.getValue())) {
            supportOwners.setValue(instances.getValue());
        }
        supportIds
                .getItems()
                .setAll(setup().supports().stream().map(SupportVolume::id).toList());
        if (!supportIds.getItems().contains(supportIds.getValue())) {
            supportIds.setValue(supportIds.getItems().stream().findFirst().orElse(null));
        }
        materials.setValue(setup().materialId());
        backends.setValue(selectedSlicer());
        var p = setup().process();
        String[] names = {"layer-height", "walls", "infill", "threshold", "gap", "interfaces", "brim", "raft"};
        double[] values = {
            p.layerHeight(),
            p.walls(),
            p.infillPercent(),
            p.supportThreshold(),
            p.supportContactGap(),
            p.interfaceLayers(),
            p.brimWidth(),
            p.raftLayers()
        };
        for (int i = 0; i < names.length; i++) {
            fields.get("print-" + names[i])
                    .setText(java.math.BigDecimal.valueOf(values[i])
                            .setScale(4, java.math.RoundingMode.HALF_UP)
                            .stripTrailingZeros()
                            .toPlainString());
        }
        supportsEnabled.setSelected(!setup().supportsDisabled());
        autoSupports.setSelected(p.automaticSupports());
        bedOnly.setSelected(p.supportsOnBedOnly());
        lastSetup = setup();
        loadPlacement();
        loadSupport();
    }

    private void validateDocument(dev.voxelbrew.api.model.CadDocument snapshot) {
        String revision = DocumentCodec.fingerprint(snapshot);
        if (!document.canExport()) {
            model = null;
            validation = null;
            validatedRevision = "";
            validationSequence++;
            validationLabel.setText(
                    translations.translate("gui.finish_rebuilding_the_complete_document_before_preparing_a_print"));
        } else if (!revision.equals(validatedRevision)) {
            validatedRevision = revision;
            validation = null;
            try {
                var meshes = new LinkedHashMap<String, MeshData>();
                document.build().bodies().forEach(b -> meshes.put(b.id(), b.mesh()));
                model = new ManufacturingModel(snapshot.id(), revision, setup(), PrintProfile.resolve(setup()), meshes);
                var captured = model;
                long ticket = ++validationSequence;
                if (validationTask != null) validationTask.cancel(false);
                validationLabel.setText(translations.translate("gui.validating_build_placement_and_meshes"));
                validationTask = validator.submit(() -> {
                    var result = PrintValidation.validate(captured);
                    Platform.runLater(() -> {
                        if (closed || ticket != validationSequence) return;
                        validation = result;
                        validationLabel.setText(
                                result.valid()
                                        ? translations.translate("gui.print_setup_valid_millimeters")
                                        : PrintValidationMessages.format(result, translations, this::instanceName));
                        updateJob();
                    });
                });
            } catch (RuntimeException failure) {
                model = null;
                validationLabel.setText(failure.getMessage());
            }
        }
    }

    private void updateSlicingActions() {
        boolean placementUnavailable =
                model == null || model.instances().isEmpty() || !document.canExport() || jobs.running() || orienting;
        autoArrange.setDisable(placementUnavailable);
        orientAll.setDisable(placementUnavailable);
        slice.setDisable(backends.getValue() == null
                || !document.canExport()
                || jobs.running()
                || validation == null
                || !validation.valid());
        sliceAll.setDisable(backends.getValue() == null
                || !document.canExport()
                || jobs.running()
                || setup().plates().stream().allMatch(plate -> plate.instances().isEmpty()));
    }

    private void updateJobPreview() {
        var result = jobs.result();
        if (result != displayed) {
            displayed = result;
            toolpathPreview.setSelected(result != null);
            updatePreviewControls();
            if (result == null) {
                previewLayers = List.of();
                summary.setText("");
            } else {
                var job = result.job();
                previewLayers = job.segments().stream()
                        .filter(GcodeJob.Segment::deposits)
                        .map(s -> Math.rint(s.z1() * 1e6) / 1e6)
                        .distinct()
                        .sorted()
                        .toList();
                summary.setText(job.report().get("printer")
                        + "\n"
                        + translations.translate(
                                "print.summary",
                                job.report().get("material"),
                                job.report().get("nozzle_mm"),
                                job.modelLayers().size(),
                                job.estimatedTime().orElse(translations.translate("print.unknown")),
                                (job.filamentMm().isPresent()
                                        ? String.format(
                                                Locale.ROOT,
                                                "%.1f mm",
                                                job.filamentMm().getAsDouble())
                                        : translations.translate("print.unknown"))));
                for (var entry : roles.entrySet()) {
                    entry.getValue().setDisable(job.segments().stream().noneMatch(s -> s.role() == entry.getKey()));
                }
            }
            layer.setMax(Math.max(0, previewLayers.size() - 1));
            layer.setValue(layer.getMax());
            layer.setDisable(previewLayers.isEmpty());
        }
    }

    private String jobMessage() {
        if (jobs.state() == PrintController.State.IDLE) {
            return translations.translate(
                    validation != null && !validation.valid() ? "print.validation.blocked" : "gui.ready_to_slice");
        }
        return jobs.state() == PrintController.State.SUCCEEDED
                ? translations.translate("print.jobReady")
                : jobs.message();
    }

    private void drawBed(double ox, double oy, double scale) {
        var graphics = canvas.getGraphicsContext2D();
        var hardware = PrintProfile.resolve(setup()).printer();
        if (threeDimensional) {
            graphics.setFill(dark ? Color.web("#101b21") : Color.web("#8b9ba3"));
            for (int i = 0; i < hardware.bed().size(); i++) {
                var a = hardware.bed().get(i);
                var b = hardware.bed().get((i + 1) % hardware.bed().size());
                double ax = ox + projectX(a.x(), a.y()) * scale;
                double ay = oy - projectY(a.x(), a.y(), 0) * scale;
                double bx = ox + projectX(b.x(), b.y()) * scale;
                double by = oy - projectY(b.x(), b.y(), 0) * scale;
                graphics.fillPolygon(new double[] {ax, bx, bx, ax}, new double[] {ay, by, by + 7, ay + 7}, 4);
            }
        }
        bedPath(hardware.bed(), ox, oy, scale);
        graphics.setFill(new LinearGradient(
                0,
                0,
                1,
                1,
                true,
                CycleMethod.NO_CYCLE,
                new Stop(0, dark ? Color.web("#394b53") : Color.web("#e5eef0")),
                new Stop(1, dark ? Color.web("#25343c") : Color.web("#c6d6dc"))));
        graphics.fill();
        graphics.setStroke(dark ? Color.web("#536c75") : Color.web("#a9bec5"));
        graphics.setLineWidth(1);
        graphics.save();
        bedPath(hardware.bed(), ox, oy, scale);
        graphics.clip();
        double grid = Math.max(10, Math.ceil(Math.max(plateWidth, plateDepth) / 100 / 10) * 10);
        for (double n = plateMinX; n <= plateMinX + plateWidth; n += grid) {
            line(graphics, ox, oy, scale, n, plateMinY, 0, n, plateMinY + plateDepth, 0);
        }
        for (double n = plateMinY; n <= plateMinY + plateDepth; n += grid) {
            line(graphics, ox, oy, scale, plateMinX, n, 0, plateMinX + plateWidth, n, 0);
        }
        graphics.restore();
        bedPath(hardware.bed(), ox, oy, scale);
        graphics.setStroke(dark ? Color.web("#9eb8bd") : Color.web("#718e98"));
        graphics.setLineWidth(3);
        graphics.stroke();
        graphics.setFill(dark ? Color.web("#18262c") : Color.web("#849ba4"));
        for (var corner : hardware.bed()) {
            double x = ox
                    + projectX(
                                    corner.x() + Math.copySign(5, plateMinX + plateWidth / 2 - corner.x()),
                                    corner.y() + Math.copySign(5, plateMinY + plateDepth / 2 - corner.y()))
                            * scale;
            double y = oy
                    - projectY(
                                    corner.x() + Math.copySign(5, plateMinX + plateWidth / 2 - corner.x()),
                                    corner.y() + Math.copySign(5, plateMinY + plateDepth / 2 - corner.y()),
                                    0)
                            * scale;
            graphics.fillOval(x - 3, y - 3, 6, 6);
        }
        for (var zone : hardware.excludedZones()) {
            bedPath(zone, ox, oy, scale);
            graphics.setFill(Color.color(0.85, 0.25, 0.15, 0.3));
            graphics.fill();
        }
        graphics.setFill(dark ? Color.LIGHTGRAY : Color.DARKSLATEGRAY);
        graphics.fillText("0,0", ox, oy + 16);
    }

    private void drawToolpath(double ox, double oy, double scale) {
        var graphics = canvas.getGraphicsContext2D();
        int index = Math.min(previewLayers.size() - 1, (int) Math.round(layer.getValue()));
        double z = previewLayers.get(index);
        layerLabel.setText(translations.translate(
                "print.layer", index + 1, previewLayers.size(), String.format(Locale.ROOT, "%.3f", z)));
        for (var segment : displayed.job().segments()) {
            if ((threeDimensional ? segment.z1() <= z + 1e-5 : Math.abs(segment.z1() - z) < 1e-5)
                    && roles.get(segment.role()).isSelected()) {
                graphics.setStroke(color(segment.role()));
                graphics.setLineWidth(segment.deposits() ? Math.max(1, segment.width() * scale) : 0.5);
                line(
                        graphics,
                        ox,
                        oy,
                        scale,
                        segment.x0(),
                        segment.y0(),
                        segment.z0(),
                        segment.x1(),
                        segment.y1(),
                        segment.z1());
            }
        }
    }

    private void drawSelectedObjectName(double ox, double oy, double scale, double w, double h) {
        for (var instance : model.instances()) {
            if (!model.meshes().containsKey(instance.bodyId())) continue;
            var shown = dragPreview != null && dragPreview.id().equals(instance.id()) ? dragPreview : instance;
            var mesh = model.placedMesh(shown);
            boolean selected = instance.id().equals(instances.getValue());
            if (selected) {
                selectedObjectName.setText(instanceName(instance.id()));
                selectedObjectName.setMaxWidth(Math.max(0, w - 16));
                selectedObjectName.autosize();
                double left = Double.POSITIVE_INFINITY, right = Double.NEGATIVE_INFINITY;
                double top = Double.POSITIVE_INFINITY;
                for (int t = 0; t < mesh.triangleCount(); t++) {
                    for (int c = 0; c < 3; c++) {
                        int v = mesh.vertexIndex(t, c);
                        double px = ox + projectX(mesh.coordinate(v, 0), mesh.coordinate(v, 1)) * scale;
                        double py = oy
                                - projectY(mesh.coordinate(v, 0), mesh.coordinate(v, 1), mesh.coordinate(v, 2)) * scale;
                        left = Math.min(left, px);
                        right = Math.max(right, px);
                        top = Math.min(top, py);
                    }
                }
                if (Double.isFinite(top)) {
                    selectedObjectName.relocate(
                            Math.max(
                                    8,
                                    Math.min(
                                            w - selectedObjectName.getWidth() - 8,
                                            (left + right - selectedObjectName.getWidth()) / 2)),
                            Math.max(
                                    8,
                                    Math.min(
                                            h - selectedObjectName.getHeight() - 8,
                                            top - selectedObjectName.getHeight() - 8)));
                    selectedObjectName.setVisible(true);
                }
            }
        }
    }

    private void drawSupportOutlines(GraphicsContext graphics, double ox, double oy, double scale) {
        for (var support : model.setup().supports()) {
            var owner = model.instances().stream()
                    .filter(i -> i.id().equals(support.instanceId()))
                    .findFirst();
            if (owner.isEmpty()) continue;
            var box = MeshGeometry.transform(
                    MeshGeometry.box(support),
                    dragPreview != null && dragPreview.id().equals(owner.get().id())
                            ? dragPreview.transform()
                            : owner.get().transform());
            graphics.setStroke(
                    switch (support.role()) {
                        case BLOCKER -> Color.CRIMSON;
                        case ENFORCER -> Color.MEDIUMPURPLE;
                        default -> Color.LIMEGREEN;
                    });
            graphics.setLineWidth(2);
            for (int t = 0; t < box.triangleCount(); t++) {
                for (int c = 0; c < 3; c++) {
                    int a = box.vertexIndex(t, c), b = box.vertexIndex(t, (c + 1) % 3);
                    line(
                            graphics,
                            ox,
                            oy,
                            scale,
                            box.coordinate(a, 0),
                            box.coordinate(a, 1),
                            box.coordinate(a, 2),
                            box.coordinate(b, 0),
                            box.coordinate(b, 1),
                            box.coordinate(b, 2));
                }
            }
        }
    }

    private void drawPlacementEdges(PrintDepthBuffer raster, double ox, double oy, double scale) {
        // Draw edges only after every surface has contributed to the shared depth buffer.
        for (var instance : model.instances()) {
            var body = document.build().bodies().stream()
                    .filter(b -> b.id().equals(instance.bodyId()))
                    .findFirst();
            if (body.isEmpty()) continue;
            var shown = dragPreview != null && dragPreview.id().equals(instance.id()) ? dragPreview : instance;
            for (var edge : body.get().edges().edges()) {
                if (!edge.boundary()) continue;
                var a = shown.transform()
                        .apply(edge.a().x(), edge.a().y(), edge.a().z());
                var b = shown.transform()
                        .apply(edge.b().x(), edge.b().y(), edge.b().z());
                raster.edge(
                        projected(a[0], a[1], a[2], ox, oy, scale),
                        projected(b[0], b[1], b[2], ox, oy, scale),
                        0xff000000);
            }
            if (instance.id().equals(instances.getValue())) {
                var bounds = MeshGeometry.bounds(model.placedMesh(shown));
                var a = projected(bounds.minX(), bounds.minY(), 0, ox, oy, scale);
                var b = projected(bounds.maxX(), bounds.minY(), 0, ox, oy, scale);
                var c = projected(bounds.maxX(), bounds.maxY(), 0, ox, oy, scale);
                var d = projected(bounds.minX(), bounds.maxY(), 0, ox, oy, scale);
                int color = dark ? 0xff9bdcff : 0xff075b91;
                raster.edge(a, b, color);
                raster.edge(b, c, color);
                raster.edge(c, d, color);
                raster.edge(d, a, color);
            }
        }
    }

    private void projectRotationRings(BodyInstance instance) {
        var shown = dragPreview == null ? instance : dragPreview;
        var bounds = MeshGeometry.bounds(model.placedMesh(shown));
        var center = new javafx.geometry.Point3D(
                (bounds.minX() + bounds.maxX()) / 2,
                (bounds.minY() + bounds.maxY()) / 2,
                (bounds.minZ() + bounds.maxZ()) / 2);
        double radius = Math.max(
                44 / viewScale,
                Math.sqrt(Math.pow(bounds.maxX() - bounds.minX(), 2)
                                        + Math.pow(bounds.maxY() - bounds.minY(), 2)
                                        + Math.pow(bounds.maxZ() - bounds.minZ(), 2))
                                / 2
                        + 12 / viewScale);
        var frame = PrintRotationGizmo.frame(shown.transform(), rotationSpace.getValue());
        rotationRings = Arrays.stream(PrintRotationGizmo.Axis.values())
                .filter(axis -> threeDimensional || axis == PrintRotationGizmo.topAxis(frame))
                .map(axis -> PrintRotationGizmo.ring(axis, center, radius, camera(), viewOx, viewOy, viewScale, frame))
                .toList();
    }

    private void positionRotationLabels() {
        if (!rotationRings.isEmpty()) {
            double top = rotationRings.stream()
                    .mapToDouble(ring -> ring.center().getY()
                            - Math.hypot(ring.u().getY(), ring.v().getY()))
                    .min()
                    .orElse(0);
            selectedObjectName.setLayoutY(
                    Math.max(8, Math.min(selectedObjectName.getLayoutY(), top - selectedObjectName.getHeight() - 12)));
            double bottom = rotationRings.stream()
                    .mapToDouble(ring -> ring.center().getY()
                            + Math.hypot(ring.u().getY(), ring.v().getY()))
                    .max()
                    .orElse(0);
            localGizmoAxes.setStyle("-fx-padding: 4 8; -fx-background-radius: 4; -fx-background-color: "
                    + (dark ? "#25262a;" : "white;"));
            localGizmoAxes.autosize();
            localGizmoAxes.relocate(
                    Math.max(
                            0,
                            Math.min(
                                    canvas.getWidth() - localGizmoAxes.getWidth(),
                                    rotationRings.getFirst().center().getX() - localGizmoAxes.getWidth() / 2)),
                    Math.max(0, Math.min(canvas.getHeight() - localGizmoAxes.getHeight(), bottom + 12)));
            localGizmoAxes.setVisible(true);
        }
    }

    private void pressCanvas(javafx.scene.input.MouseEvent event) {
        if (navigationIdle.getStatus() == javafx.animation.Animation.Status.RUNNING) draw();
        selectedObjectTooltip.hide();
        canvas.requestFocus();
        navigationX = event.getX();
        navigationY = event.getY();
        primaryNavigation = false;
        if (event.getButton() == javafx.scene.input.MouseButton.SECONDARY
                || event.getButton() == javafx.scene.input.MouseButton.MIDDLE) {
            cancelDrag();
            navigating = true;
            canvas.setCursor(javafx.scene.Cursor.CLOSED_HAND);
            event.consume();
            return;
        }
        if (event.getButton() != javafx.scene.input.MouseButton.PRIMARY) return;
        event.consume();
        navigating = true;
        primaryNavigation = true;
        if (toolpathPreview.isSelected() || model == null || !document.canExport() || jobs.running()) return;
        var rotation = rotationHit(event.getX(), event.getY());
        if (rotation != null) {
            navigating = false;
            primaryNavigation = false;
            dragSource = selected();
            dragPreview = dragSource;
            dragRevision = DocumentCodec.fingerprint(document.snapshot().document());
            rotationDrag =
                    new PrintRotationGizmo.Drag(rotation, new javafx.geometry.Point2D(event.getX(), event.getY()));
            rotationDegrees = 0;
            canvas.setCursor(javafx.scene.Cursor.CROSSHAIR);
            draw();
            return;
        }
        var instance = objectAt(event.getX(), event.getY());
        if (instance != null) {
            navigating = false;
            primaryNavigation = false;
            instances.setValue(instance.id());
            dragSource = instance;
            dragPreview = instance;
            dragRevision = DocumentCodec.fingerprint(document.snapshot().document());
            dragX = event.getX();
            dragY = event.getY();
            canvas.setCursor(javafx.scene.Cursor.CLOSED_HAND);
            event.consume();
        }
    }

    private void dragCanvas(javafx.scene.input.MouseEvent event) {
        if (navigating) {
            double dx = event.getX() - navigationX, dy = event.getY() - navigationY;
            navigationX = event.getX();
            navigationY = event.getY();
            if (event.isSecondaryButtonDown() || event.isMiddleButtonDown()) camera().pan(dx, dy);
            else if (primaryNavigation && event.isPrimaryButtonDown()) {
                camera().orbit(dx, dy);
            }
            canvas.setCursor(javafx.scene.Cursor.CLOSED_HAND);
            drawNavigation();
            event.consume();
            return;
        }
        if (dragSource == null) return;
        if (rotationDrag != null) {
            rotationDegrees =
                    rotationDrag.degrees(new javafx.geometry.Point2D(event.getX(), event.getY()), event.isShiftDown());
            dragPreview = PrintRotationGizmo.rotate(
                    dragSource,
                    model.meshes().get(dragSource.bodyId()),
                    rotationDrag.axis(),
                    rotationDegrees,
                    rotationSpace.getValue());
            draw();
            event.consume();
            return;
        }
        var delta = camera().bedDelta((event.getX() - dragX) / viewScale, -(event.getY() - dragY) / viewScale);
        if (delta == null) return;
        dragPreview = PrintPlacement.move(dragSource, delta.getX(), delta.getY(), false, event.isShiftDown());
        navigationFrame.start();
        event.consume();
    }

    private void releaseCanvas(javafx.scene.input.MouseEvent event) {
        if (navigating) {
            if (primaryNavigation && event.isStillSincePress()) {
                instances.setValue(null);
                draw();
            }
            navigating = false;
            primaryNavigation = false;
            canvas.setCursor(javafx.scene.Cursor.OPEN_HAND);
            event.consume();
            return;
        }
        if (dragSource == null) return;
        var before = dragSource;
        var after = dragPreview;
        boolean current = dragRevision.equals(
                DocumentCodec.fingerprint(document.snapshot().document()));
        clearDrag();
        if (current && after != null && !before.equals(after)) replace(after);
        else draw();
        event.consume();
    }

    private void reloadPrintTargets() {
        try {
            var selectedTarget = setup().workspaceSettings().get(PrintTarget.PRINTER_ID);
            var targets = printerRuntime.load().devices();
            boolean oldUpdating = updating;
            updating = true;
            printTargetNames = targets.stream()
                    .collect(java.util.stream.Collectors.toMap(
                            dev.voxelbrew.api.printer.Printer::id, dev.voxelbrew.api.printer.Printer::name));
            printTargetDevices = targets.stream()
                    .collect(java.util.stream.Collectors.toMap(
                            dev.voxelbrew.api.printer.Printer::id, java.util.function.Function.identity()));
            printTarget.getItems().setAll("");
            printTarget
                    .getItems()
                    .addAll(targets.stream()
                            .map(dev.voxelbrew.api.printer.Printer::id)
                            .toList());
            printTarget.setValue(selectedTarget == null ? "" : selectedTarget);
            printTarget.getButtonCell().setText(printTarget.getConverter().toString(printTarget.getValue()));
            updating = oldUpdating;
        } catch (java.io.IOException failure) {
            showDiagnostics(
                    jobs.failureDiagnostic(),
                    PrintController.timestampedDiagnostic(Objects.toString(failure.getMessage(), failure.toString())));
        }
    }

    private void updatePrinterImage(PrinterProfileCatalog.Entry entry) {
        String image = entry == null ? null : entry.image();
        if (image == null) {
            var fallback = printers.fallbackImage();
            if (fallback != null) image = fallback;
        }
        var loadedImage = image == null ? null : PrinterImages.load(image);
        if (loadedImage == null || loadedImage.isError()) {
            var fallback = printers.fallbackImage();
            loadedImage = fallback == null ? null : new javafx.scene.image.Image(fallback, 96, 72, true, true);
        }
        printerImage.setImage(loadedImage);
    }

    private PrinterProfileCatalog.Entry profileEntry(PrinterProfileCatalog.Entry entry) {
        var saved = setup().printerSnapshot();
        if (saved == null
                || (entry != null && ProfileHardware.resolve(entry.definition()).equals(saved))) return entry;
        return new PrinterProfileCatalog.Entry(
                new dev.voxelbrew.api.printer.Profile(
                        saved.printer().id(),
                        saved.printer().name(),
                        saved.manufacturer(),
                        saved.printer().bed(),
                        saved.printer().excludedZones(),
                        saved.printer().usableHeight(),
                        saved.printer().nozzleMm(),
                        saved.filamentDiameter(),
                        saved.toolCount(),
                        saved.printer().firmware(),
                        saved.printer().outputFormat(),
                        saved.genericGcode(),
                        null),
                entry == null ? null : entry.image(),
                "project",
                false);
    }

    private void paintRotationRing(javafx.scene.canvas.GraphicsContext graphics, PrintRotationGizmo.Ring ring) {
        graphics.setStroke(rotationColor(ring.axis()));
        graphics.setLineWidth(
                (rotationDrag != null && rotationDrag.axis() == ring.axis()) || hoveredRotation == ring.axis()
                        ? 3.5
                        : 1.8);
        double[] x = new double[73], y = new double[73];
        for (int i = 0; i <= 72; i++) {
            var point = ring.point(i * Math.PI / 36);
            x[i] = point.getX();
            y[i] = point.getY();
        }
        graphics.strokePolyline(x, y, x.length);
        var handle = ring.point(ring.handleAngle());
        graphics.setFill(dark ? Color.web("#25262a") : Color.WHITE);
        graphics.fillOval(handle.getX() - 10, handle.getY() - 10, 20, 20);
        graphics.strokeOval(handle.getX() - 10, handle.getY() - 10, 20, 20);
        graphics.setFill(rotationColor(ring.axis()));
        graphics.setTextAlign(javafx.scene.text.TextAlignment.CENTER);
        graphics.fillText(ring.axis().name(), handle.getX(), handle.getY() + 4);
    }

    void onExport3mf(Runnable action) {
        export3mf = Objects.requireNonNull(action);
    }

    void onConfigureDevices(Runnable action) {
        configureDevicesAction = Objects.requireNonNull(action);
    }

    void configureDevices() {
        var targetId = setup().workspaceSettings().get(PrintTarget.PRINTER_ID);
        var before = printTargetDevices.get(targetId);
        configureDevicesAction.run();
        reloadPrinters();
        var after = printTargetDevices.get(targetId);
        if (!printTarget.isDisabled()
                && before != null
                && after != null
                && !ProfileHardware.resolve(before.profile()).equals(ProfileHardware.resolve(after.profile()))) {
            commit(setup().withPrinter(ProfileHardware.resolve(after.profile())).withPrintTarget(targetId));
        }
    }

    QuickMenu quickMenu(javafx.scene.Parent theme, java.util.function.Supplier<List<QuickMenu.Action>> workspaces) {
        return new QuickMenu(
                canvas,
                theme,
                translations,
                () -> {
                    var radial = QuickMenu.controls(
                            quickActions::get,
                            "print-slice",
                            "print-slice-all",
                            "print-auto-arrange",
                            "print-auto-orient",
                            "print-fit-view",
                            "print-view-2d",
                            "print-view-3d",
                            "print-preview");
                    var additional = QuickMenu.controls(quickActions::get, "print-add", "print-lay-face");
                    var remove = QuickMenu.Action.button(quickActions.get("print-remove"), "×");
                    additional.add(new QuickMenu.Action(
                            remove.id(),
                            translations.translate("quick.removeObject"),
                            remove.icon(),
                            remove.disabled(),
                            remove.selected(),
                            remove.invoke()));
                    additional.addAll(QuickMenu.controls(quickActions::get, "print-cancel"));
                    return new QuickMenu.Content(radial, additional);
                },
                workspaces,
                (dx, dy) -> {
                    cancelDrag();
                    camera().pan(dx, dy);
                    draw();
                },
                target -> true);
    }

    private Tab tab(String name, VBox content) {
        content.setPadding(new Insets(8));
        for (var child : content.getChildren()) {
            if (child instanceof Control control) control.setMaxWidth(Double.MAX_VALUE);
        }
        content.getChildren().stream().filter(n -> n instanceof Label).forEach(n -> ((Label) n).setWrapText(true));
        var scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setFitToHeight(name.equals("Job"));
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        var tab = new Tab(translations.translate("tab." + name), scroll);
        tab.setClosable(false);
        tab.setId("print-tab-" + name.toLowerCase(Locale.ROOT));
        return tab;
    }

    private VBox row(String id, String label, String initial) {
        return row(id, label, initial, true);
    }

    private VBox placementCoordinates(boolean rotation) {
        var coordinates = new HBox(8);
        for (String axis : List.of("x", "y", "z")) {
            String id = "print-" + (rotation ? "rotate-" : "") + axis;
            String caption = rotation ? translations.translate("print.rotation", axis.toUpperCase(Locale.ROOT)) : axis;
            var column = row(id, caption, "0", false);
            column.setMinWidth(0);
            column.setPrefWidth(0);
            HBox.setHgrow(column, Priority.ALWAYS);
            fields.get(id).setMinWidth(0);
            fields.get(id).setPrefColumnCount(5);
            coordinates.getChildren().add(column);
        }
        var hint = new Label(translations.translate("print.property." + (rotation ? "rotation" : "position")));
        hint.setWrapText(true);
        hint.getStyleClass().add("slicer-property-help");
        return new VBox(4, coordinates, hint);
    }

    private VBox row(String id, String label, String initial, boolean showHelp) {
        java.util.function.Supplier<ParameterSet> parameters =
                () -> document.snapshot().document().parameters();
        ParameterUnit unit;
        if (id.startsWith("print-rotate-") || id.equals("print-threshold")) {
            unit = ParameterUnit.DEG;
        } else {
            unit = Set.of("print-face", "print-walls", "print-infill", "print-interfaces", "print-raft")
                            .contains(id)
                    ? ParameterUnit.NUMBER
                    : ParameterUnit.MM;
        }
        var field = Set.of("print-support-name", "print-output").contains(id)
                ? FormulaField.text(initial, parameters, translations)
                : FormulaField.number(initial, parameters, unit, translations);

        if (id.equals("print-infill")) field.displayUnit("%");
        var bounds = PrintFieldBounds.forField(id);
        if (bounds != null) {
            if (Set.of("print-support-width", "print-support-depth", "print-support-height")
                    .contains(id)) {
                field.bounds(0, false, Double.POSITIVE_INFINITY, false);
            } else {
                field.bounds(bounds.min(), true, bounds.max(), true);
            }
            field.dragStep(bounds.step());
            field.validateWith(value -> {
                if (!bounds.accepts(value)) {
                    throw new IllegalArgumentException(
                            translations.translate(bounds.helpKey(), bounds.min(), bounds.max()));
                }
            });
        }
        field.setId(id);
        fields.put(id, field);
        var caption = new Label(label);
        caption.setWrapText(true);
        String helpKey;
        if (id.startsWith("print-rotate-")) {
            helpKey = "rotation";
        } else {
            if (Set.of("print-x", "print-y", "print-z").contains(id)) {
                helpKey = "position";
            } else {
                if (id.startsWith("print-support-")) {
                    helpKey = "support";
                } else {
                    helpKey = id.substring(6);
                }
            }
        }
        String help = translations.tryTranslate("print.property." + helpKey).orElse("");
        if (bounds != null) {
            help += " " + translations.translate(bounds.helpKey(), bounds.min(), bounds.max());
        }
        var row = new VBox(4, caption, field);
        if (!help.isBlank()) {
            caption.setTooltip(UiHelp.tooltip(help));
            var hint = new Label(help);
            hint.setWrapText(true);
            hint.getStyleClass().add("slicer-property-help");
            if (showHelp) row.getChildren().add(hint);
            field.setAccessibleHelp(help);
        }
        return row;
    }

    private Button button(String title, String id, Runnable action) {
        var button = new Button(title);
        button.setId(id);
        if (!id.startsWith("print-remove-plate-")) quickActions.put(id, button);
        button.setMaxWidth(Double.MAX_VALUE);
        UiHelp.install(button, translations);
        button.setOnAction(e -> {
            e.consume();
            try {
                action.run();
            } catch (RuntimeException failure) {
                status.setText(Objects.toString(failure.getMessage(), failure.toString()));
            }
        });
        return button;
    }

    private double number(String id) {
        var field = fields.get(id);
        try {
            double value = field.value();
            if (!Double.isFinite(value)) throw new NumberFormatException();
            field.setStyle("");
            return value;
        } catch (IllegalArgumentException failure) {
            field.setStyle("-fx-border-color: #d04a3c;");
            throw new IllegalArgumentException(id.replace("print-", "") + ": " + failure.getMessage(), failure);
        }
    }

    private int integer(String id) {
        double value = number(id);
        if (value != Math.rint(value)) {
            throw new IllegalArgumentException(translations.translate("print.integer", id));
        }
        return Math.toIntExact((long) value);
    }

    private PrintSetup setup() {
        return document.snapshot().document().printSetup();
    }

    private boolean hasPrintDevice() {
        var id = setup().workspaceSettings().get(PrintTarget.PRINTER_ID);
        return id != null && !id.isBlank();
    }

    private BodyInstance selected() {
        return setup().instances().stream()
                .filter(i -> i.id().equals(instances.getValue()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(translations.translate("gui.choose_a_build_instance")));
    }

    private void commit(PrintSetup setup) {
        document.printSetup(setup);
    }

    private void addInstance() {
        String body =
                Objects.requireNonNull(bodies.getValue(), translations.translate("gui.choose_a_rebuilt_cad_body"));
        var mesh = document.build().bodies().stream()
                .filter(b -> b.id().equals(body))
                .findFirst()
                .orElseThrow()
                .mesh();
        var bounds = MeshGeometry.bounds(mesh);
        var all = new ArrayList<>(setup().instances());
        String id = "part-" + document.snapshot().document().uuidGenerator().next();
        all.add(new BodyInstance(
                id,
                body,
                Transform.translation(
                        plateMinX + (plateWidth - (bounds.maxX() - bounds.minX())) / 2 - bounds.minX(),
                        plateMinY + (plateDepth - (bounds.maxY() - bounds.minY())) / 2 - bounds.minY(),
                        -bounds.minZ())));
        commit(setup().withInstances(all));
        instances.setValue(id);
    }

    private void autoArrange() {
        if (model == null || !document.canExport() || jobs.running()) return;
        cancelDrag();
        var arranged = PrintArrangement.arrange(model, 0);
        if (arranged.isEmpty()) {
            status.setText(translations.translate("print.autoArrangeFailed"));
            return;
        }
        toolpathPreview.setSelected(false);
        var next = setup().withInstances(arranged.get());
        if (!next.equals(setup())) commit(next);
    }

    private void applyPlacement() {
        var selected = selected();
        var transform = Transform.translation(number("print-x"), number("print-y"), number("print-z"))
                .compose(Transform.rotation(
                        number("print-rotate-x"), number("print-rotate-y"), number("print-rotate-z")));
        replace(new BodyInstance(
                selected.id(),
                Objects.requireNonNull(bodies.getValue(), translations.translate("gui.choose_a_cad_body")),
                transform));
    }

    private void replace(BodyInstance selected) {
        commit(setup().withInstances(setup().instances().stream()
                .map(i -> i.id().equals(selected.id()) ? selected : i)
                .toList()));
    }

    private void removeInstance() {
        var id = selected().id();
        commit(setup().withInstances(setup().instances().stream()
                        .filter(i -> !i.id().equals(id))
                        .toList())
                .withSupports(setup().supports().stream()
                        .filter(support -> !support.instanceId().equals(id))
                        .toList()));
    }

    private void layFace() {
        var selected = selected();
        var mesh = document.build().bodies().stream()
                .filter(b -> b.id().equals(selected.bodyId()))
                .findFirst()
                .orElseThrow()
                .mesh();
        replace(new BodyInstance(
                selected.id(),
                selected.bodyId(),
                MeshGeometry.layOnFace(
                        mesh,
                        Objects.equals(selectedFaceBody, selected.bodyId()) ? selectedFaceIndex : 0,
                        selected.transform())));
    }

    void selectedFace(String body, int face) {
        if (face >= 0) {
            bodies.setValue(body);
            selectedFaceIndex = face;
            selectedFaceBody = body;
        }
    }

    private void loadPlacement() {
        if (instances.getValue() == null) return;
        var selected = selected();
        bodies.setValue(selected.bodyId());
        var m = selected.transform().matrix();
        double ry = Math.asin(Math.max(-1, Math.min(1, -m[8]))),
                rx = Math.atan2(m[9], m[10]),
                rz = Math.atan2(m[4], m[0]);
        if (Math.abs(Math.cos(ry)) < 1e-8) {
            rx = 0;
            rz = Math.atan2(-m[1], m[5]);
        }
        String[] names = {"x", "y", "z", "rotate-x", "rotate-y", "rotate-z"};
        double[] values = {
            m[3],
            m[7],
            m[11],
            dev.voxelbrew.api.model.units.Measurements.degrees(rx),
            dev.voxelbrew.api.model.units.Measurements.degrees(ry),
            dev.voxelbrew.api.model.units.Measurements.degrees(rz)
        };
        for (int i = 0; i < names.length; i++) {
            fields.get("print-" + names[i])
                    .setText(java.math.BigDecimal.valueOf(values[i])
                            .setScale(4, java.math.RoundingMode.HALF_UP)
                            .stripTrailingZeros()
                            .toPlainString());
        }
    }

    private void applyProcess() {
        var backend = backends.getValue();
        var process = new ProcessSettings(
                number("print-layer-height"),
                integer("print-walls"),
                number("print-infill"),
                autoSupports.isSelected(),
                bedOnly.isSelected(),
                number("print-threshold"),
                number("print-gap"),
                integer("print-interfaces"),
                number("print-brim"),
                integer("print-raft"));
        var next = setup().withProfile(
                        backend == null ? setup().backendId() : backend.providerId(),
                        setup().printerId(),
                        Objects.requireNonNull(materials.getValue(), translations.translate("gui.choose_a_material")),
                        process);
        if (backend != null) next = backend.select(next);
        if (!next.equals(setup())) commit(next);
    }

    private void applySupport() {
        var volume = new SupportVolume(
                fields.get("print-support-name").evaluatedText(),
                Objects.requireNonNull(
                        supportOwners.getValue(), translations.translate("gui.choose_an_attached_instance")),
                Objects.requireNonNull(supportRole.getValue()),
                number("print-support-x"),
                number("print-support-y"),
                number("print-support-z"),
                number("print-support-width"),
                number("print-support-depth"),
                number("print-support-height"));
        var all = new ArrayList<>(setup().supports());
        all.removeIf(s -> s.id().equals(volume.id()));
        all.add(volume);
        commit(setup().withSupports(all));
        supportIds.setValue(volume.id());
    }

    private void removeSupport() {
        String id = Objects.requireNonNull(
                supportIds.getValue(), translations.translate("gui.choose_a_support_instruction"));
        commit(setup().withSupports(setup().supports().stream()
                .filter(s -> !s.id().equals(id))
                .toList()));
    }

    private void loadSupport() {
        setup().supports().stream()
                .filter(s -> s.id().equals(supportIds.getValue()))
                .findFirst()
                .ifPresent(s -> {
                    fields.get("print-support-name").setText(s.id());
                    supportOwners.setValue(s.instanceId());
                    supportRole.setValue(s.role());
                    String[] names = {"x", "y", "z", "width", "depth", "height"};
                    double[] values = {s.x(), s.y(), s.z(), s.width(), s.depth(), s.height()};
                    for (int i = 0; i < names.length; i++) {
                        fields.get("print-support-" + names[i])
                                .setText(java.math.BigDecimal.valueOf(values[i])
                                        .setScale(4, java.math.RoundingMode.HALF_UP)
                                        .stripTrailingZeros()
                                        .toPlainString());
                    }
                });
    }

    private void slice() {
        slice(false);
    }

    private void invalidateJob() {
        if (updating) return;
        jobs.invalidateGeometry();
        document.invalidateGcode();
    }

    private void slice(boolean allPlates) {
        var target = setup().workspaceSettings().get(PrintTarget.PRINTER_ID);
        if (target != null && !printTarget.getItems().contains(target)) {
            throw new IllegalStateException("Selected printer connection is unavailable or references another profile");
        }
        if (!document.canExport()) {
            throw new IllegalStateException(
                    translations.translate("gui.slice_requires_a_complete_successful_rebuild_at_the_timeline_end"));
        }
        applyNozzle();
        applyProcess();
        tabs.getSelectionModel().select(3);
        document.captureSlicerWorkspace();
        var snapshot = document.snapshot().document();
        var selectedDevice = target == null ? null : printTargetDevices.get(target);
        if (selectedDevice != null
                && Double.compare(
                                selectedDevice.profile().nozzleDiameter(),
                                PrintProfile.resolve(snapshot.printSetup())
                                        .printer()
                                        .nozzleMm())
                        != 0) {
            throw new IllegalStateException("Selected nozzle differs from the configured printer");
        }
        var installation =
                Objects.requireNonNull(backends.getValue(), translations.translate("slicer.installation.choose"));
        var provider = slicers.require(installation.providerId());
        jobs.startEmbedded(snapshot, provider, installation.executionWindow(Map.of()), allPlates)
                .thenAccept(result -> {
                    var live = document.snapshot().document();
                    var stored = new LinkedHashMap<>(live.gcode());
                    stored.putAll(jobs.completedGcode());
                    var artifacts = new LinkedHashMap<>(live.printArtifacts());
                    artifacts.putAll(jobs.completedArtifacts());
                    document.attachPrintJobs(stored, artifacts);
                })
                .exceptionally(failure -> null);
    }

    private List<SlicerInstallation> operationalSlicers() {
        var providers =
                slicers.descriptors().stream().map(SlicerDescriptor::id).collect(java.util.stream.Collectors.toSet());
        return slicerSettings.installations().stream()
                .filter(SlicerInstallation::operational)
                .filter(entry -> providers.contains(entry.providerId()))
                .toList();
    }

    private SlicerInstallation selectedSlicer() {
        String selected = SlicerInstallation.selectedId(setup());
        if (!selected.isBlank()) {
            return backends.getItems().stream()
                    .filter(entry -> entry.id().toString().equals(selected))
                    .findFirst()
                    .orElse(null);
        }
        var matching = backends.getItems().stream()
                .filter(entry -> entry.providerId().equals(setup().backendId()))
                .toList();
        var custom = matching.stream().filter(entry -> !entry.bundled()).toList();
        if (custom.size() == 1) return custom.getFirst();
        return custom.isEmpty() && matching.size() == 1 ? matching.getFirst() : null;
    }

    void reloadSlicers() {
        var previous = backends.getValue();
        updating = true;
        try {
            backends.getItems().setAll(operationalSlicers());
            backends.setValue(selectedSlicer());
        } finally {
            updating = false;
        }
        if (previous != null && !previous.equals(backends.getValue())) invalidateJob();
        updateJob();
    }

    void documentChanged() {
        if (closed) return;
        var snapshot = document.snapshot().document();
        if (!workspace.belongsTo(snapshot.uuidGenerator())) {
            lastSetup = null;
            lastBuild = null;
        }
        updating = true;
        var pendingWorkspace = workspace.pending(snapshot.uuidGenerator());
        updating = false;
        if (dragSource != null && !DocumentCodec.fingerprint(snapshot).equals(dragRevision)) cancelDrag();
        var printer = PrintProfile.resolve(setup()).printer();
        plateWidth =
                printer.bed().stream().mapToDouble(PrintProfile.Point::x).max().orElse(200)
                        - printer.bed().stream()
                                .mapToDouble(PrintProfile.Point::x)
                                .min()
                                .orElse(0);
        plateDepth =
                printer.bed().stream().mapToDouble(PrintProfile.Point::y).max().orElse(200)
                        - printer.bed().stream()
                                .mapToDouble(PrintProfile.Point::y)
                                .min()
                                .orElse(0);
        plateMinX =
                printer.bed().stream().mapToDouble(PrintProfile.Point::x).min().orElse(0);
        plateMinY =
                printer.bed().stream().mapToDouble(PrintProfile.Point::y).min().orElse(0);
        updatePrinterDetails();
        plateSize.setText(String.format(Locale.ROOT, "%.1f \u00d7 %.1f mm", plateWidth, plateDepth));
        jobs.documentChanged(snapshot);
        if (document.rebuilding()) jobs.invalidateGeometry();
        else {
            jobs.restoreEmbedded(snapshot);
        }
        boolean refreshNames = lastBuild != document.build() || !setup().equals(lastSetup);
        updating = true;
        try {
            if (lastBuild != document.build()) {
                String selected = bodies.getValue();
                bodies.getSelectionModel().clearSelection();
                bodies.getItems()
                        .setAll(document.build().bodies().stream()
                                .map(DocumentController.Body::id)
                                .toList());
                if (!bodies.getItems().isEmpty()) {
                    bodies.getSelectionModel()
                            .select(Math.max(0, bodies.getItems().indexOf(selected)));
                }
                lastBuild = document.build();
            }
            if (!setup().equals(lastSetup)) {
                refreshPrintSetup();
            }
            workspace.restore(snapshot.uuidGenerator(), setup().workspaceSettings(), pendingWorkspace);
            if (refreshNames) {
                // Body renames keep instance IDs unchanged, so JavaFX does not refresh their cells.
                for (var choice : List.of(bodies, instances, supportOwners)) {
                    choice.setCellFactory(list -> namedCell(choice));
                    choice.setButtonCell(namedCell(choice));
                }
            }
        } finally {
            updating = false;
        }
        validateDocument(snapshot);
        updateJob();
    }

    private void updateJob() {
        export3mfButton.setDisable(!document.canExport() || jobs.running());
        boolean sliced =
                jobs.result() != null || !document.snapshot().document().gcode().isEmpty();
        printTarget.setDisable(sliced || jobs.running());
        for (int i = 0; i < 3; i++) tabs.getTabs().get(i).setDisable(sliced);
        if (sliced) tabs.getSelectionModel().select(3);
        cleanSlicing.setDisable(!sliced || jobs.running());
        updatePreviewControls();
        updateSlicingActions();
        cancel.setDisable(!jobs.running());
        cancel.setVisible(jobs.running());
        cancel.setManaged(jobs.running());
        progress.setVisible(jobs.running());
        String stateLabel = translations.translate("print.state." + jobs.state().name());
        boolean failed = jobs.state() == PrintController.State.FAILED;
        status.setText(failed ? translations.translate("print.slicingFailed") : stateLabel + ": " + jobMessage());
        jobDetails.setVisible(failed);
        status.setTooltip(failed ? null : UiHelp.tooltip(jobs.message()));
        showDiagnostics(jobs.failureDiagnostic(), String.join("\n", jobs.progressDiagnostics()));
        updateJobPreview();
        draw();
    }

    void setDark(boolean value) {
        dark = value;
        if (navigationView != null) navigationView.setDark(value);
        if (viewCube != null) viewCube.setDark(value);
        draw();
    }

    private void showDiagnostics(String failure, String progressText) {
        String separator = failure.isEmpty() || progressText.isEmpty() ? "" : "\n";
        String text = progressText + separator + failure;
        int start = progressText.length() + separator.length();
        if (diagnostics.getText().equals(text) && failureStart == start) return;
        diagnostics.replaceText(text);
        failureStart = start;
        if (!failure.isEmpty()) diagnostics.setStyleClass(start, text.length(), "job-failure");
        diagnostics.moveTo(start);
        diagnostics.requestFollowCaret();
    }

    private void drawNavigation() {
        navigationFrame.start();
        navigationIdle.playFromStart();
    }

    private Point2D dragOffset() {
        if (dragPreview == null) return Point2D.ZERO;
        var from = dragSource.transform().matrix();
        var to = dragPreview.transform().matrix();
        return new Point2D(to[3] - from[3], to[7] - from[7]);
    }

    private void drawTranslation() {
        if (model == null) return;
        if (!dragOverlay.isVisible()) {
            var bed = canvas.getGraphicsContext2D();
            bed.setFill(dark ? Color.web("#182028") : Color.web("#edf1f4"));
            bed.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
            drawBed(viewOx, viewOy, viewScale);
            placementRaster = null;
            dragOverlay.setVisible(true);
        }
        var offset = dragOffset();
        navigationView.show(model, document.build(), dragSource.id(), camera(), viewOx, viewOy, viewScale, offset);
        var delta = offset.subtract(detailedDragOffset);
        double dx = viewScale * projectX(delta.getX(), delta.getY());
        double dy = -viewScale * projectY(delta.getX(), delta.getY(), 0);
        selectedObjectName.setTranslateX(dx);
        selectedObjectName.setTranslateY(dy);
        localGizmoAxes.setTranslateX(dx);
        localGizmoAxes.setTranslateY(dy);
        var graphics = dragOverlay.getGraphicsContext2D();
        graphics.clearRect(0, 0, dragOverlay.getWidth(), dragOverlay.getHeight());
        drawSupportOutlines(graphics, viewOx, viewOy, viewScale);
        graphics.save();
        graphics.translate(dx, dy);
        for (var ring : rotationRings) paintRotationRing(graphics, ring);
        graphics.restore();
    }

    private void draw() {
        navigationFrame.stop();
        navigationIdle.stop();
        dragOverlay.setVisible(false);
        selectedObjectName.setTranslateX(0);
        selectedObjectName.setTranslateY(0);
        localGizmoAxes.setTranslateX(0);
        localGizmoAxes.setTranslateY(0);
        detailedDragOffset = dragOffset();
        if (navigationView != null) navigationView.hide(model);
        draw(false);
    }

    private void draw(boolean movingCamera) {
        if (viewCube != null) viewCube.setOrientation(camera().orientation());
        selectedObjectTooltip.hide();
        selectedObjectName.setVisible(false);
        placementRaster = null;
        var graphics = canvas.getGraphicsContext2D();
        localGizmoAxes.setVisible(false);
        double w = canvas.getWidth(), h = canvas.getHeight();
        graphics.setFill(dark ? Color.web("#182028") : Color.web("#edf1f4"));
        graphics.fillRect(0, 0, w, h);
        if (rotationDrag == null) rotationRings = List.of();
        if (w < 1 || h < 1) return;
        double extentX = threeDimensional ? Math.hypot(plateWidth, plateDepth) : plateWidth;
        double extentY = threeDimensional ? extentX : plateDepth;
        double scale = Math.max(0.001, Math.min((w - 48) / extentX, (h - 48) / extentY)) * camera().zoom();
        double ox = w / 2 - projectX(plateMinX + plateWidth / 2, plateMinY + plateDepth / 2) * scale + camera().panX();
        double oy =
                h / 2 + projectY(plateMinX + plateWidth / 2, plateMinY + plateDepth / 2, 0) * scale + camera().panY();
        viewScale = scale;
        viewOx = ox;
        viewOy = oy;
        drawBed(ox, oy, scale);
        if (toolpathPreview.isSelected() && displayed != null && !previewLayers.isEmpty()) {
            drawToolpath(ox, oy, scale);
        } else {
            layerLabel.setText(translations.translate("gui.build_placement_slice_to_preview_actual_toolpaths"));
            if (model == null) return;
            if (movingCamera && navigationView != null) {
                navigationView.show(
                        model, document.build(), instances.getValue(), camera(), ox, oy, scale, Point2D.ZERO);
                return;
            }
            renderPlacement(ox, oy, scale, (int) Math.ceil(w), (int) Math.ceil(h), movingCamera);
            drawSelectedObjectName(ox, oy, scale, w, h);
            drawSupportOutlines(graphics, ox, oy, scale);
            drawRotationGizmo();
        }
    }

    private PrintDepthBuffer.Point projected(double x, double y, double z, double ox, double oy, double scale) {
        return new PrintDepthBuffer.Point(
                ox + projectX(x, y) * scale, oy - projectY(x, y, z) * scale, camera().depth(x, y, z));
    }

    private void renderPlacement(double ox, double oy, double scale, int width, int height, boolean movingCamera) {
        // Render at physical-pixel resolution, with supersampling for smooth silhouettes.
        var window = canvas.getScene() == null ? null : canvas.getScene().getWindow();
        double pixelScale =
                Math.max(2, window == null ? 1 : Math.max(window.getOutputScaleX(), window.getOutputScaleY()));
        if (movingCamera) pixelScale = Math.min(1, Math.sqrt(250_000.0 / width / height));
        int imageWidth = (int) Math.ceil(width * pixelScale);
        int imageHeight = (int) Math.ceil(height * pixelScale);
        var raster = new PrintDepthBuffer(imageWidth, imageHeight, pixelScale);
        ox *= pixelScale;
        oy *= pixelScale;
        scale *= pixelScale;
        int palette = 0;
        var all = model.instances();
        for (int owner = 0; owner < all.size(); owner++) {
            var instance = all.get(owner);
            if (!model.meshes().containsKey(instance.bodyId())) continue;
            var shown = dragPreview != null && dragPreview.id().equals(instance.id()) ? dragPreview : instance;
            var mesh = model.placedMesh(shown);
            var color = instance.id().equals(instances.getValue())
                    ? Color.web("#4299c7")
                    : Color.hsb(195 + (palette++ * 48) % 160, 0.35, 0.72);
            int rgb = 0xff000000
                    | ((int) Math.round(color.getRed() * 255) << 16)
                    | ((int) Math.round(color.getGreen() * 255) << 8)
                    | (int) Math.round(color.getBlue() * 255);
            var points = new PrintDepthBuffer.Point[mesh.vertexCount()];
            for (int v = 0; v < points.length; v++) {
                points[v] =
                        projected(mesh.coordinate(v, 0), mesh.coordinate(v, 1), mesh.coordinate(v, 2), ox, oy, scale);
            }
            for (int t = 0; t < mesh.triangleCount(); t++) {
                raster.triangle(
                        points[mesh.vertexIndex(t, 0)],
                        points[mesh.vertexIndex(t, 1)],
                        points[mesh.vertexIndex(t, 2)],
                        rgb,
                        owner);
            }
        }
        drawPlacementEdges(raster, ox, oy, scale);
        var image = new javafx.scene.image.WritableImage(imageWidth, imageHeight);
        image.getPixelWriter()
                .setPixels(
                        0,
                        0,
                        imageWidth,
                        imageHeight,
                        javafx.scene.image.PixelFormat.getIntArgbInstance(),
                        raster.pixels(),
                        0,
                        imageWidth);
        canvas.getGraphicsContext2D().drawImage(image, 0, 0, width, height);
        placementRaster = raster;
        placementRasterScale = pixelScale;
    }

    private void drawRotationGizmo() {
        if (!showRotationGizmos.isSelected() || !document.canExport() || jobs.running()) return;
        var instance = model.instances().stream()
                .filter(i -> i.id().equals(instances.getValue()))
                .findFirst();
        if (instance.isEmpty() || !model.meshes().containsKey(instance.get().bodyId())) return;
        if (rotationDrag == null) {
            projectRotationRings(instance.get());
        }
        var graphics = canvas.getGraphicsContext2D();
        graphics.save();
        for (var ring : rotationRings) {
            paintRotationRing(graphics, ring);
        }
        if (rotationDrag != null && !rotationRings.isEmpty()) {
            var center = rotationRings.getFirst().center();
            graphics.setFill(dark ? Color.web("#25262a") : Color.WHITE);
            graphics.fillRoundRect(center.getX() - 42, center.getY() - 12, 84, 24, 6, 6);
            graphics.setFill(dark ? Color.WHITE : Color.web("#27282e"));
            graphics.fillText(
                    rotationDrag.axis().name() + " " + String.format(Locale.ROOT, "%.1f°", rotationDegrees),
                    center.getX(),
                    center.getY() + 4);
        }
        graphics.restore();
        positionRotationLabels();
    }

    private PrintRotationGizmo.Hit rotationHit(double x, double y) {
        if (!showRotationGizmos.isSelected()
                || toolpathPreview.isSelected()
                || !document.canExport()
                || jobs.running()) {
            return null;
        }
        var pointer = new javafx.geometry.Point2D(x, y);
        return rotationRings.stream()
                .map(ring -> ring.hit(pointer))
                .filter(Objects::nonNull)
                .min(Comparator.comparingDouble(PrintRotationGizmo.Hit::distance))
                .orElse(null);
    }

    private void updatePreviewControls() {
        boolean preview = jobs.result() != null && toolpathPreview.isSelected();
        toolpathPreview.setDisable(jobs.result() == null);
        previewControls.setVisible(preview);
        placementHint.setVisible(!preview);
        placementHint.setManaged(!preview);
    }

    private String plateName(String id) {
        for (int i = 0; i < setup().plates().size(); i++) {
            if (setup().plates().get(i).id().equals(id)) {
                String name = setup().plates().get(i).name();
                return name.isEmpty() ? translations.translate("print.plate", i + 1) : name;
            }
        }
        return id;
    }

    private void rebuildPlateStrip() {
        plateNameEditor = null;
        plateStrip.getChildren().clear();
        var group = new ToggleGroup();
        for (var plate : setup().plates()) {
            var tab = new ToggleButton(
                    plateName(plate.id()) + "  \u00b7  " + plate.instances().size());
            tab.setId("print-plate-" + plate.id());
            tab.setMaxWidth(220);
            UiHelp.describe(tab, plateName(plate.id()) + "\n" + translations.translate("print.renameHint"));
            tab.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, event -> {
                if (event.getButton() == javafx.scene.input.MouseButton.PRIMARY
                        && event.getClickCount() >= 2
                        && event.getClickCount() % 2 == 0) {
                    renamePlate(plate.id(), tab);
                    event.consume();
                }
            });
            tab.setToggleGroup(group);
            tab.setSelected(plate.id().equals(setup().activePlateId()));
            tab.setOnAction(event -> {
                if (plateNameEditor != null) return;
                if (!plate.id().equals(setup().activePlateId())) {
                    cancelDrag();
                    commit(setup().selectPlate(plate.id()));
                } else {
                    tab.setSelected(true);
                }
            });
            var remove = button("\u00d7", "print-remove-plate-" + plate.id(), () -> removePlate(plate.id()));
            remove.setAccessibleText(translations.translate("print.removePlate") + " " + plateName(plate.id()));
            remove.setTooltip(UiHelp.tooltip(translations.translate("print.removePlate")));
            remove.setDisable(setup().plates().size() == 1);
            tab.setGraphic(remove);
            tab.setContentDisplay(ContentDisplay.RIGHT);
            tab.setOnDragDetected(event -> {
                var drag = tab.startDragAndDrop(javafx.scene.input.TransferMode.MOVE);
                var content = new javafx.scene.input.ClipboardContent();
                content.putString(plate.id());
                drag.setContent(content);
                event.consume();
            });
            tab.setOnDragOver(event -> {
                if (event.getGestureSource() instanceof ToggleButton source
                        && source.getToggleGroup() == group
                        && event.getDragboard().hasString()
                        && !plate.id().equals(event.getDragboard().getString())) {
                    event.acceptTransferModes(javafx.scene.input.TransferMode.MOVE);
                }
                event.consume();
            });
            tab.setOnDragDropped(event -> {
                if (event.getGestureSource() instanceof ToggleButton source && source.getToggleGroup() == group) {
                    commit(PrintPlacement.reorder(setup(), event.getDragboard().getString(), plate.id()));
                    event.setDropCompleted(true);
                }
                event.consume();
            });
            plateStrip.getChildren().add(tab);
        }
        targetPlate
                .getItems()
                .setAll(setup().plates().stream()
                        .map(PrintPlate::id)
                        .filter(id -> !id.equals(setup().activePlateId()))
                        .toList());
        targetPlate.getSelectionModel().selectFirst();
        targetPlate.setDisable(targetPlate.getItems().isEmpty());
    }

    private void removePlate(String id) {
        if (setup().plates().size() == 1) return;
        var removing = setup().selectPlate(id);
        if (!removing.instances().isEmpty() || !removing.supports().isEmpty()) {
            var confirm = new Alert(
                    Alert.AlertType.CONFIRMATION,
                    translations.translate("print.removePlateConfirm"),
                    ButtonType.OK,
                    ButtonType.CANCEL);
            confirm.setHeaderText(translations.translate("print.removePlate"));
            if (Dialogs.show(confirm, getScene().getWindow(), translations).orElse(ButtonType.CANCEL)
                    != ButtonType.OK) {
                return;
            }
        }
        var next = removing.removeActivePlate();
        if (!id.equals(setup().activePlateId())) next = next.selectPlate(setup().activePlateId());
        commit(next);
    }

    private void saveProcess() {
        if (updating || materials.getValue() == null) return;
        try {
            applyProcess();
        } catch (IllegalArgumentException failure) {
            status.setText(failure.getMessage());
        }
    }

    private void applyNozzle() {
        var next = setup().withNozzle(nozzle.value());
        if (!next.equals(setup())) commit(next);
    }

    private void saveNozzle() {
        if (updating) return;
        try {
            applyNozzle();
        } catch (IllegalArgumentException failure) {
            status.setText(failure.getMessage());
        }
    }

    private void autoOrient() {
        autoOrient(false);
    }

    private void autoOrient(boolean all) {
        if (model == null || !document.canExport() || jobs.running() || orienting) return;
        cancelDrag();
        var captured = setup();
        var sources = all ? captured.instances() : List.of(selected());
        var meshes = model.meshes();
        String revision = DocumentCodec.fingerprint(document.snapshot().document());
        orienting = true;
        updateJob();
        CompletableFuture.supplyAsync(
                        () -> {
                            var oriented = new LinkedHashMap<String, BodyInstance>();
                            for (var source : sources) {
                                var placement =
                                        PrintPlacement.autoOrient(meshes.get(source.bodyId()), source.transform());
                                oriented.put(source.id(), new BodyInstance(source.id(), source.bodyId(), placement));
                            }
                            return captured.withInstances(captured.instances().stream()
                                    .map(source -> oriented.getOrDefault(source.id(), source))
                                    .toList());
                        },
                        validator)
                .whenComplete((next, failure) -> Platform.runLater(() -> {
                    orienting = false;
                    if (closed) return;
                    updateJob();
                    if (!revision.equals(
                            DocumentCodec.fingerprint(document.snapshot().document()))) {
                        return;
                    }
                    if (failure != null) status.setText(failure.getCause().getMessage());
                    else {
                        toolpathPreview.setSelected(false);
                        if (!next.equals(setup())) commit(next);
                        updatePreviewControls();
                        draw();
                    }
                }));
    }

    private void renamePlate(String plateId, ToggleButton tab) {
        int index = plateStrip.getChildren().indexOf(tab);
        if (index < 0 || plateNameEditor != null) return;
        var editor = new TextField(plateName(plateId));
        editor.setId("print-plate-name");
        editor.setPrefColumnCount(14);
        editor.setMaxWidth(220);
        editor.setTooltip(UiHelp.tooltip(translations.translate("print.renameHint")));
        plateNameEditor = editor;
        plateStrip.getChildren().set(index, editor);
        editor.setOnAction(event -> {
            try {
                if (editor.getText().strip().equals(plateName(plateId))) {
                    cancelPlateRename();
                    return;
                }
                var next = setup().renamePlate(plateId, editor.getText());
                if (next.equals(setup())) {
                    cancelPlateRename();
                    return;
                }
                plateNameEditor = null;
                commit(next);
            } catch (IllegalArgumentException failure) {
                editor.setStyle("-fx-border-color: #d04a3c;");
                status.setText(translations.translate("print.invalidPlateName"));
                editor.setTooltip(UiHelp.tooltip(translations.translate("print.invalidPlateName")));
            }
        });
        editor.focusedProperty().addListener((o, a, focused) -> {
            if (!focused && plateNameEditor == editor) cancelPlateRename();
        });
        editor.requestFocus();
        editor.selectAll();
    }

    boolean cancelPlateRename() {
        if (plateNameEditor == null) return false;
        plateNameEditor = null;
        rebuildPlateStrip();
        updateJob();
        return true;
    }

    boolean cancelDrag() {
        if (dragSource == null) return false;
        clearDrag();
        draw();
        return true;
    }

    private void clearDrag() {
        navigationFrame.stop();
        dragSource = null;
        dragPreview = null;
        rotationDrag = null;
        hoveredRotation = null;
        canvas.setCursor(javafx.scene.Cursor.OPEN_HAND);
    }

    private BodyInstance objectAt(double pointerX, double pointerY) {
        if (model == null || placementRaster == null || toolpathPreview.isSelected()) return null;
        int index = placementRaster.owner(pointerX * placementRasterScale, pointerY * placementRasterScale);
        return index < 0 || index >= model.instances().size()
                ? null
                : model.instances().get(index);
    }

    private void installDragging() {
        canvas.setCursor(javafx.scene.Cursor.OPEN_HAND);
        canvas.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_MOVED, event -> {
            var hovered = dragSource == null && !navigating ? objectAt(event.getX(), event.getY()) : null;
            String name = hovered == null ? "" : instanceName(hovered.id());
            if (!name.equals(selectedObjectTooltip.getText())) {
                selectedObjectTooltip.hide();
                Tooltip.uninstall(canvas, selectedObjectTooltip);
                selectedObjectTooltip.setText(name);
                if (!name.isBlank()) Tooltip.install(canvas, selectedObjectTooltip);
            }
        });
        canvas.setOnMouseExited(event -> selectedObjectTooltip.hide());
        canvas.focusedProperty().addListener((o, a, focused) -> {
            if (!focused) {
                cancelDrag();
                navigating = false;
            }
        });
        canvas.setOnMouseMoved(event -> {
            if (dragSource != null || navigating) return;
            var hit = rotationHit(event.getX(), event.getY());
            var axis = hit == null ? null : hit.ring().axis();
            canvas.setCursor(axis == null ? javafx.scene.Cursor.OPEN_HAND : javafx.scene.Cursor.CROSSHAIR);
            if (axis != hoveredRotation) {
                hoveredRotation = axis;
                draw();
            }
        });
        canvas.setOnScroll(event -> {
            if (dragSource != null) return;
            camera().scroll(event.getDeltaY());
            drawNavigation();
            event.consume();
        });
        canvas.setOnMousePressed(this::pressCanvas);
        canvas.setOnMouseDragged(this::dragCanvas);
        canvas.setOnMouseReleased(this::releaseCanvas);
    }

    private ListCell<String> namedCell(ComboBox<String> choice) {
        return new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(
                        empty || item == null
                                ? choice.getPromptText()
                                : choice.getConverter().toString(item));
                setGraphic(null);
                setTooltip(empty || item == null ? null : UiHelp.tooltip(getText()));
            }
        };
    }

    private String bodyName(String id) {
        int index = 1;
        for (var body : document.build().bodies()) {
            if (body.id().equals(id)) {
                return document.snapshot().document().bodyName(id, translations.translate("feature.body", index));
            }
            index++;
        }
        return id;
    }

    private String instanceName(String id) {
        for (int i = 0; i < setup().instances().size(); i++) {
            var instance = setup().instances().get(i);
            if (instance.id().equals(id)) return bodyName(instance.bodyId()) + " (" + (i + 1) + ")";
        }
        return id;
    }

    private void bedPath(List<PrintProfile.Point> points, double ox, double oy, double scale) {
        var graphics = canvas.getGraphicsContext2D();
        graphics.beginPath();
        for (int i = 0; i < points.size(); i++) {
            var p = points.get(i);
            double x = ox + projectX(p.x(), p.y()) * scale;
            double y = oy - projectY(p.x(), p.y(), 0) * scale;
            if (i == 0) graphics.moveTo(x, y);
            else {
                graphics.lineTo(x, y);
            }
        }
        graphics.closePath();
    }

    void openPrintersFolder() {
        try {
            java.nio.file.Files.createDirectories(printers.directory());
            java.awt.Desktop.getDesktop().open(printers.directory().toFile());
        } catch (java.io.IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    void exportDefaultPrinters() {
        var selected = PrinterExportDialog.choose(
                getScene().getWindow(), translations, printers.load().entries());
        if (selected.isEmpty()) return;
        try {
            int count = printers.exportDefaults(selected);
            reloadPrinters();
            var alert = new Alert(
                    Alert.AlertType.INFORMATION,
                    translations.translate(
                            "print.exportedPrinters",
                            count,
                            printers.directory().toString()),
                    ButtonType.OK);
            alert.initOwner(getScene().getWindow());
            alert.setTitle(translations.translate("print.exportPrinters"));
            alert.setHeaderText(null);
            alert.showAndWait();
        } catch (java.io.IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private boolean printerVisible(PrinterProfileCatalog.Entry entry) {
        return !entry.bundled()
                || !hiddenPrinters.contains(entry.definition().printer().id());
    }

    void manageVisiblePrinters() {
        try {
            var entries = printers.load().entries();
            var visible = PrinterExportDialog.manageVisible(
                    getScene().getWindow(), translations, entries, printerVisibility.hidden());
            if (visible.isEmpty()) return;
            printerVisibility.saveVisible(entries, visible.get());
            reloadPrinters();
        } catch (UserConfigException ex) {
            throw new IllegalStateException(ex);
        }
    }

    void reloadPrinters() {
        hiddenPrinters = printerVisibility.hidden();
        printerCatalog = printers.load();
        if (!printerCatalog.diagnostics().isEmpty()) {
            showDiagnostics(
                    jobs.failureDiagnostic(),
                    printerCatalog.diagnostics().stream()
                            .map(PrintController::timestampedDiagnostic)
                            .collect(java.util.stream.Collectors.joining("\n")));
        }
        updatePrinterDetails();
    }

    private void updatePrinterDetails() {
        if (printerCatalog == null) return;
        reloadPrintTargets();
        var saved = setup().printerSnapshot();
        var p = PrintProfile.resolve(setup()).printer();
        boolean deviceSelected = hasPrintDevice();
        printerProfileLabel.setText(translations
                .tryTranslate(deviceSelected ? "print.deviceProfile" : "print.printerSelector")
                .orElseThrow());
        if (!nozzle.isFocused()) nozzle.setText(Double.toString(p.nozzleMm()));
        var entry = printerCatalog.entries().stream()
                .filter(e -> e.definition().printer().id().equals(p.id()))
                .findFirst()
                .orElse(null);
        var selected = profileEntry(entry);
        printerChoice.setText((selected == null ? p.name() : selected.name())
                + (selected != null && selected.bundled()
                        ? " (" + translations.translate("print.builtInPrinter") + ")"
                        : "")
                + (saved != null && saved.modified()
                        ? " (" + translations.translate("print.modifiedPrinter") + ")"
                        : "")
                + (deviceSelected ? "" : "..."));
        printerDetails.setText(translations.translate(
                        "print.buildVolume",
                        String.format(Locale.ROOT, "%.1f", plateWidth),
                        String.format(Locale.ROOT, "%.1f", plateDepth),
                        String.format(Locale.ROOT, "%.1f", p.usableHeight()))
                + (saved == null
                        ? ""
                        : translations.translate(
                                "print.printerHardware", Double.toString(saved.filamentDiameter()), saved.toolCount()))
                + "\n" + translations.translate("print.genericPrinter"));
        updatePrinterImage(entry);
    }

    private void editPlate() {
        if (plateSize.isDisabled()) return;
        var dialog = new Dialog<ButtonType>();
        dialog.initOwner(getScene().getWindow());
        dialog.setTitle(translations.translate("print.editPlate"));
        var width = FormulaField.number(
                Double.toString(plateWidth),
                () -> document.snapshot().document().parameters(),
                ParameterUnit.MM,
                translations);
        var depth = FormulaField.number(
                Double.toString(plateDepth),
                () -> document.snapshot().document().parameters(),
                ParameterUnit.MM,
                translations);
        var height = FormulaField.number(
                Double.toString(PrintProfile.resolve(setup()).printer().usableHeight()),
                () -> document.snapshot().document().parameters(),
                ParameterUnit.MM,
                translations);
        for (var field : List.of(width, depth, height)) {
            field.bounds(0, false, 10000, true);
            field.dragStep(1);
            field.validateWith(value -> {
                if (!Double.isFinite(value) || value <= 0 || value > 10000) {
                    throw new IllegalArgumentException(translations.translate("print.buildRange"));
                }
            });
        }
        width.setId("print-bed-width");
        depth.setId("print-bed-depth");
        height.setId("print-bed-height");
        var error = new Label(translations.translate("print.buildRange"));
        error.setWrapText(true);
        dialog.getDialogPane()
                .setContent(new VBox(
                        8,
                        new Label(translations.translate("print.width")),
                        width,
                        new Label(translations.translate("print.depth")),
                        depth,
                        new Label(translations.translate("print.height")),
                        height,
                        error));
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.getDialogPane().lookupButton(ButtonType.OK).addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            try {
                String printer = PrintProfile.rectangularPrinterId(width.value(), depth.value(), height.value());
                if (setup().printerSnapshot() == null) {
                    commit(setup().withProfile(setup().backendId(), printer, setup().materialId(), setup().process()));
                } else {
                    commit(setup().withPrinter(setup().printerSnapshot()
                            .rectangular(width.value(), depth.value(), height.value())));
                }
            } catch (IllegalArgumentException failure) {
                error.setText(translations.translate("print.invalidDimensions"));
                event.consume();
            }
        });
        Dialogs.show(dialog, getScene().getWindow(), translations);
    }

    private PrintCamera camera() {
        return threeDimensional ? spatialCamera : topCamera;
    }

    private double projectX(double x, double y) {
        return camera().x(x, y);
    }

    private double projectY(double x, double y, double z) {
        return camera().y(x, y, z);
    }

    private void line(
            GraphicsContext graphics,
            double ox,
            double oy,
            double scale,
            double x0,
            double y0,
            double z0,
            double x1,
            double y1,
            double z1) {
        graphics.strokeLine(
                ox + projectX(x0, y0) * scale,
                oy - projectY(x0, y0, z0) * scale,
                ox + projectX(x1, y1) * scale,
                oy - projectY(x1, y1, z1) * scale);
    }

    PrintController jobs() {
        return jobs;
    }

    @Override
    public void close() {
        navigationFrame.stop();
        navigationIdle.stop();
        selectedObjectTooltip.hide();
        Tooltip.uninstall(canvas, selectedObjectTooltip);
        closed = true;
        validationSequence++;
        jobs.close();
        diagnostics.dispose();
        validator.shutdownNow();
    }
}
