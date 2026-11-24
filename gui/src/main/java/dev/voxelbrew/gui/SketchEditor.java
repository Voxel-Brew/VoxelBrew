package dev.voxelbrew.gui;

import static dev.voxelbrew.api.model.sketch.SketchGeometry.*;

import dev.voxelbrew.api.i18n.TranslationService;
import dev.voxelbrew.api.model.Feature;
import dev.voxelbrew.api.model.parameters.*;
import dev.voxelbrew.api.model.sketch.*;
import dev.voxelbrew.api.model.units.Measurements;
import dev.voxelbrew.engine.parameters.*;
import dev.voxelbrew.engine.sketch.*;
import java.util.*;
import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;

/**
 * Window-local 2D sketch workspace; Finish publishes one engine transaction, Cancel publishes none.
 */
final class SketchEditor extends BorderPane implements AutoCloseable {
    private static final PseudoClass ACTIVE_TOOL = PseudoClass.getPseudoClass("active-tool");
    private static final double TEXT_ROTATION_RADIUS = 34;

    private final SketchController controller;

    private final dev.voxelbrew.api.model.UuidGenerator uuidGenerator;

    private final TranslationService translations;
    private final java.text.NumberFormat pointerNumber;

    private final Canvas canvas = new Canvas();

    private final Canvas gizmoAxes = new Canvas(52, 52);

    private final LinkedHashSet<Ref> selection = new LinkedHashSet<>();
    private final List<Entity> projection;

    private final List<Vec> drawing = new ArrayList<>();

    private final List<Ref> snaps = new ArrayList<>();

    private final List<Badge> badges = new ArrayList<>();

    private final ListView<Constraint> constraints = new ListView<>();

    private final ComboBox<Ref> references = new ComboBox<>();

    private final ComboBox<Type> dimension = new ComboBox<>();

    private final Button createType;

    private final VBox toolOptions = new VBox(6);

    private final FormulaField toolRadius,
            toolRho,
            textContent,
            textFont,
            textHeight,
            textAngle,
            pathTextAngle,
            textSpacing,
            pointX,
            pointY;

    private final ComboBox<Integer> splineDegree = new ComboBox<>();

    private final ComboBox<String> textStyle = new ComboBox<>();
    private final ComboBox<SketchText.Horizontal> textHorizontal;
    private final ComboBox<SketchText.Vertical> textVertical;
    private final CheckBox mirrorHorizontal, mirrorVertical;

    private final CheckBox closedCurve = new CheckBox("");

    private final FormulaField dimensionValue, sides;
    private final ComboBox<SketchTools.Tool> polygonMode = new ComboBox<>();
    private final CheckBox construction = new CheckBox(""), auto = new CheckBox(""), showBadges = new CheckBox("");
    private final Label selected = new Label(""), status = new Label(""), error = new Label(), hint = new Label();
    private final Label valueCaption = new Label();
    private final Label profileWarning = new Label();

    private final Label pointerHint = new Label();
    private final Button finish, undo, redo;

    private final Map<String, Button> shortcutActions = new LinkedHashMap<>();
    private final FormulaField rotationInput;
    private final Label rotationDelta = new Label();
    private final HBox rotationEntry;

    private final java.util.function.BiConsumer<Feature.Sketch, ParameterSet> accept;

    private final Feature.Sketch original;

    private final String featureId;

    private final List<Feature> documentFeatures;

    private final List<DimensionHit> dimensionHits = new ArrayList<>();

    private final QuickMenu quickMenu;

    private final boolean dark;

    private final Feature.Plane selectedPlane;

    private SketchSnapping.Snap hoverSnap;

    private SketchTools.Tool advanced;

    private Runnable dismiss = () -> {};

    private Draw tool = Draw.SELECT;

    private SketchViewBasis viewBasis = SketchViewBasis.DEFAULT;

    private Type activeConstraint;

    private boolean dimensionTool;

    private HBox dimensionEntry;
    private HBox sizeEntry;
    private String polygonSides = "6";
    private int previewSides = 6;

    private Vec dimensionEntryPosition;

    private DimensionHit draggedDimension;

    private Vec dragLabelPosition;

    private Ref dragPoint;
    private Entity rotatingText;
    private double rotationAngle;

    private boolean boxCandidate, boxSelecting, boxAdditive;

    private Vec cursor = new Vec(0, 0);
    private javafx.geometry.Point2D pointer;

    private double centerX, centerY, scale = 4, lastX, lastY, pressX, pressY;
    private boolean moving, panning, refreshing, closed;

    private int nextId = 1;

    enum Draw {
        SELECT,
        POINT,
        LINE,
        RECTANGLE,
        CIRCLE,
        ARC,
        SPLINE,
        POLYGON,
        ADVANCED
    }

    private record Hit(Ref ref, Vec position) {}

    private record TextEdit(SketchGeometry geometry, ParameterSet parameters) {}

    private record DimensionEndpoints(Vec start, Vec end) {}

    private record Badge(Constraint constraint, double x, double y) {}

    private record DimensionHit(
            Constraint constraint,
            Vec label,
            double x,
            double y,
            double width,
            double ax,
            double ay,
            double bx,
            double by) {}

    static void show(
            Stage stage,
            SketchController.Factory controllers,
            Feature.Sketch sketch,
            Feature.Plane selectedPlane,
            List<Entity> projection,
            List<Feature> features,
            ParameterSet parameters,
            TranslationService translations,
            java.util.function.BiConsumer<Feature.Sketch, ParameterSet> accept) {
        var scene = stage.getScene();
        Parent previous = WindowFrame.content(scene);
        Node previousFocus = scene.getFocusOwner();
        var previousClose = stage.getOnCloseRequest();
        var editor = new SketchEditor(
                controllers,
                sketch,
                selectedPlane,
                projection,
                features,
                parameters,
                previous.getStyleClass().contains("dark"),
                translations,
                accept);
        editor.getStylesheets().setAll(previous.getStylesheets());
        editor.getStyleClass().addAll(previous.getStyleClass());
        var host = new StackPane();
        host.getStylesheets().setAll(previous.getStylesheets());
        host.getStyleClass().add(previous.getStyleClass().contains("dark") ? "dark" : "light");
        WindowFrame.content(scene, host);
        host.getChildren().addAll(previous, editor);
        StackPane.setMargin(editor, new Insets(8));
        boolean disabled = previous.isDisable();
        previous.setDisable(true);
        javafx.event.EventHandler<KeyEvent> escape = event -> {
            if (event.getCode() == KeyCode.ESCAPE && WindowFrame.content(scene) == host) {
                event.consume();
                if (editor.dimensionEntry != null) editor.closeDimensionEntry();
                else {
                    editor.cancelDrawing();
                }
            }
        };
        stage.addEventFilter(KeyEvent.KEY_PRESSED, escape);
        javafx.event.EventHandler<WindowEvent> hidden = event -> {
            stage.removeEventFilter(KeyEvent.KEY_PRESSED, escape);
            editor.close();
        };
        stage.addEventHandler(WindowEvent.WINDOW_HIDDEN, hidden);
        editor.dismiss = () -> {
            editor.close();
            stage.removeEventFilter(KeyEvent.KEY_PRESSED, escape);
            stage.removeEventHandler(WindowEvent.WINDOW_HIDDEN, hidden);
            stage.setOnCloseRequest(previousClose);
            host.getChildren().clear();
            WindowFrame.content(scene, previous);
            previous.setDisable(disabled);
            if (previousFocus != null) previousFocus.requestFocus();
        };
        stage.setOnCloseRequest(event -> {
            event.consume();
            editor.dismiss.run();
        });
        Platform.runLater(() -> {
            // The persistent window frame must allocate its center before fitting.
            // Laying out only the editor can leave the canvas at its minimum size.
            scene.getRoot().applyCss();
            scene.getRoot().layout();
            editor.fit(true);
            editor.canvas.requestFocus();
        });
    }

    private static Vec displayPoint(SketchGeometry geometry, Entity entity, Ref point) {
        if (entity.kind() == Kind.TEXT) {
            try {
                return SketchText.anchor(geometry, entity);
            } catch (IllegalArgumentException invalidPath) {
                // Keep the point selectable while its path is being repaired.
            }
        }
        return entity.point(point);
    }

    private static Entity rotatedText(Entity text, double angle) {
        var values = new ArrayList<>(text.values());
        values.set(3, angle);
        return text.withValues(values);
    }

    private static List<Entity> displayCurves(SketchGeometry geometry, Entity entity) {
        try {
            return geometry.curves(entity);
        } catch (IllegalArgumentException invalid) {
            return List.of(entity);
        }
    }

    private static String symbol(Type type) {
        return switch (type) {
            case HORIZONTAL -> "—";
            case VERTICAL -> "│";
            case COINCIDENT -> "●";
            case TANGENT -> "○/";
            case EQUAL -> "=";
            case PARALLEL -> "∥";
            case PERPENDICULAR -> "⊥";
            case FIX -> "▣";
            case MIDPOINT -> "△";
            case CONCENTRIC -> "◎";
            case COLLINEAR -> "↗";
            case SYMMETRY -> "││";
            case CURVATURE -> "∿";
            case POLYGON -> "⬡";
            default -> "d";
        };
    }

    private static double hintCoordinate(double position, double size, double limit) {
        return Math.max(
                0, position + 16 + size <= limit ? position + 16 : Math.min(limit - size, position - size - 12));
    }

    private static void setSketchStroke(GraphicsContext graphics, boolean construction) {
        if (construction) graphics.setLineDashes(7, 5);
        else {
            graphics.setLineDashes();
        }
    }

    private static double span(Vec axis, double width, double height) {
        return Math.abs(axis.x()) * width + Math.abs(axis.y()) * height;
    }

    private static String number(double value) {
        return String.format(Locale.ROOT, "%.6g", value);
    }

    private static double value(TextField field, String label) {
        try {
            double v = field instanceof FormulaField formula
                    ? formula.value()
                    : Double.parseDouble(field.getText().strip());
            if (!Double.isFinite(v)) throw new NumberFormatException();
            field.getStyleClass().remove("invalid");
            return v;
        } catch (IllegalArgumentException failure) {
            field.getStyleClass().add("invalid");
            throw new IllegalArgumentException(label + ": " + failure.getMessage(), failure);
        }
    }

    private SketchEditor(
            SketchController.Factory controllers,
            Feature.Sketch sketch,
            Feature.Plane selectedPlane,
            List<Entity> projection,
            List<Feature> features,
            ParameterSet parameters,
            boolean dark,
            TranslationService translations,
            java.util.function.BiConsumer<Feature.Sketch, ParameterSet> accept) {
        this.translations = translations;
        pointerNumber = java.text.NumberFormat.getNumberInstance(translations.currentLocale());
        pointerNumber.setMaximumFractionDigits(2);
        finish = button("", "constraint-finish", this::finish);
        undo = button("", "sketch-undo", () -> controllerUndo(false));
        redo = button("", "sketch-redo", () -> controllerUndo(true));
        closedCurve.setText(translations.translate("gui.closed_curve"));
        construction.setText(translations.translate("gui.construction"));
        auto.setText(translations.translate("gui.auto_constraints"));
        showBadges.setText(translations.translate("gui.show_badges"));
        selected.setText(translations.translate("gui.select_a_curve_or_point_shift_click_adds_to_the_selection"));
        status.setText(translations.translate("gui.solving"));
        finish.setText(translations.translate("gui.finish_sketch"));
        undo.setText(translations.translate("action.undo"));
        redo.setText(translations.translate("action.redo"));
        this.original = sketch;
        this.projection = List.copyOf(projection);
        uuidGenerator = parameters.uuidGenerator();
        this.selectedPlane = sketch == null ? selectedPlane : sketch.plane();

        featureId = sketch == null ? "sketch-" + parameters.uuidGenerator().next() : sketch.id();
        documentFeatures = List.copyOf(features);
        this.dark = dark;
        this.accept = accept;
        var converted = sketch == null
                ? new FeatureDimensions.Converted(SketchGeometry.empty(), parameters)
                : FeatureDimensions.convert(sketch, parameters);
        controller = controllers.create(converted.geometry(), converted.parameters(), featureId);
        toolRadius = numeric("5", ParameterUnit.MM);
        toolRho = numeric("0.5", ParameterUnit.NUMBER).dragStep(0.01);
        textContent = FormulaField.text("Text", this::formulaParameters, translations);
        textFont = FormulaField.text("SansSerif", this::formulaParameters, translations);
        textHeight = numeric("10", ParameterUnit.MM);
        textAngle = numeric(Integer.toString(SketchTools.Tool.TEXT.defaultTextAngleDegrees()), ParameterUnit.DEG)
                .bounds(-180, true, 180, true);
        pathTextAngle = numeric(
                        Integer.toString(SketchTools.Tool.TEXT_ON_PATH.defaultTextAngleDegrees()), ParameterUnit.DEG)
                .bounds(-180, true, 180, true);
        textSpacing = numeric("0", ParameterUnit.NUMBER).dragStep(0.01);
        pointX = numeric("0", ParameterUnit.MM);
        pointY = numeric("0", ParameterUnit.MM);
        dimensionValue = FormulaField.number("10", this::formulaParameters, this::dimensionUnit, translations);
        sides = numeric(polygonSides, ParameterUnit.NUMBER).dragStep(1);
        UiText.enums(dimension, translations, "constraint.");
        setId("sketch-editor");
        getStyleClass().add("sketch-editor");
        var title = new Label(
                sketch == null
                        ? translations.translate("gui.create_constrained_sketch")
                        : translations.translate("gui.edit_sketch"));
        title.getStyleClass().add("selection-title");
        var space = new Region();
        HBox.setHgrow(space, Priority.ALWAYS);
        finish.getStyleClass().add("primary");
        var header = new HBox(
                10,
                title,
                button(translations.translate("action.parameters"), "sketch-parameters", this::parameters),
                space,
                finish,
                button(translations.translate("action.cancel"), "constraint-cancel", () -> dismiss.run()));
        header.setAlignment(Pos.CENTER_LEFT);
        var tools = new FlowPane(5, 5);
        for (var draw : Draw.values()) {
            if (draw == Draw.ADVANCED) continue;
            var button = button(
                    translations.translate("draw." + draw.name()),
                    "sketch-tool-" + draw.name().toLowerCase(Locale.ROOT),
                    () -> tool(draw));
            tools.getChildren().add(button);
        }
        createType =
                button(translations.translate("gui.more_sketch_types"), "sketch-create-type", this::chooseSketchType);
        tools.getChildren().add(createType);
        tools.getChildren()
                .add(button(translations.translate("gui.sketch_dimension"), "sketch-dimension", this::startDimension));
        splineDegree.getItems().setAll(3, 5);
        splineDegree.setValue(3);
        splineDegree.setId("sketch-spline-degree");
        textStyle
                .getItems()
                .setAll(
                        translations.translate("gui.regular"),
                        translations.translate("gui.bold"),
                        translations.translate("gui.italic"),
                        translations.translate("gui.bold_italic"));
        textStyle.getSelectionModel().select(0);
        textStyle.setId("sketch-text-style");
        mirrorHorizontal = mirrorOption("horizontal", false, false);
        mirrorVertical = mirrorOption("vertical", false, false);
        textHorizontal =
                alignmentChoice("sketch-text-horizontal", SketchText.Horizontal.values(), SketchText.Horizontal.LEFT);
        textVertical =
                alignmentChoice("sketch-text-vertical", SketchText.Vertical.values(), SketchText.Vertical.BASELINE);
        closedCurve.setId("sketch-closed-curve");
        toolRadius.setId("sketch-tool-radius");
        toolRho.setId("sketch-tool-rho");
        textContent.setId("sketch-text-content");
        textFont.setId("sketch-text-font");
        textHeight.setId("sketch-text-height");
        textAngle.setId("sketch-text-angle");
        pathTextAngle.setId("sketch-text-angle");
        textSpacing.setId("sketch-text-spacing");
        pointX.setId("sketch-point-x");
        pointY.setId("sketch-point-y");
        sides.setId("sketch-polygon-sides");
        sides.setPrefColumnCount(4);
        polygonMode.setId("sketch-polygon-mode");
        polygonMode.getItems().setAll(SketchTools.Tool.POLYGON_INSCRIBED, SketchTools.Tool.POLYGON_CIRCUMSCRIBED);
        UiText.enums(polygonMode, translations, "tool.");
        polygonMode.getSelectionModel().selectFirst();
        polygonMode.setOnAction(event -> {
            canvas.requestFocus();
            draw();
        });
        construction.setId("sketch-construction");
        KeyboardShortcuts.letter("sketch-toggle-construction")
                .ifPresent(key -> ShortcutLabels.install(construction, key));
        construction.selectedProperty().addListener(o -> draw());
        auto.setId("sketch-auto");
        for (var check : List.of(auto, construction, closedCurve)) UiHelp.install(check, translations);
        auto.setSelected(true);
        tools.getChildren()
                .addAll(
                        construction,
                        undo,
                        redo,
                        button(translations.translate("action.fit"), "sketch-fit", () -> fit(false)),
                        button(
                                "F1",
                                "keyboard-shortcuts",
                                () -> KeyboardShortcuts.show(getScene().getWindow(), translations)));
        var menu = new MenuButton(translations.translate("gui.constraints"));
        menu.setId("sketch-constraint-menu");
        UiHelp.menu(menu, translations);
        var constraintBar = new FlowPane(4, 4, menu, auto);
        for (var type : Type.values()) {
            if (type.dimension()) continue;
            String label = type == Type.FIX
                    ? translations.translate("gui.fix_unfix")
                    : translations.translate("constraint." + type.name());
            var item = new MenuItem(label);
            item.setId("constraint-menu-" + type.name().toLowerCase(Locale.ROOT));
            UiHelp.menuItem(item, translations.translate("constraint.hint." + type.name()));
            item.setOnAction(e -> request(type));
            menu.getItems().add(item);
            var action =
                    button(symbol(type), "constraint-" + type.name().toLowerCase(Locale.ROOT), () -> request(type));
            action.getStyleClass().add("constraint-icon");
            action.setAccessibleText(label);
            UiHelp.describe(action, label + " · " + translations.translate("constraint.hint." + type.name()));
            constraintBar.getChildren().add(action);
        }
        hint.setWrapText(true);
        hint.getStyleClass().add("muted");
        setTop(new VBox(9, header, tools, constraintBar, operationsBar(), hint));
        BorderPane.setMargin(getTop(), new Insets(0, 0, 8, 0));
        canvas.setId("sketch-canvas");
        canvas.setFocusTraversable(true);
        pointerHint.setId("sketch-pointer-hint");
        pointerHint.setManaged(false);
        pointerHint.setWrapText(true);
        pointerHint.maxWidthProperty().bind(canvas.widthProperty().multiply(.8));
        pointerHint.setMouseTransparent(true);
        pointerHint.setVisible(false);
        rotationInput = numeric("0", ParameterUnit.DEG).bounds(-180, true, 180, true);
        rotationInput.setId("sketch-rotation-angle");
        rotationInput.setPrefColumnCount(6);
        rotationInput.setAccessibleText(translations.translate("gui.angle_degrees"));
        rotationInput.setOnAction(event -> enterRotation());
        rotationEntry = new HBox(4, rotationInput, new Label("°"), rotationDelta);
        rotationEntry.setAlignment(Pos.CENTER_LEFT);
        rotationEntry.setStyle("-fx-background-color: -fx-control-inner-background; -fx-padding: 3;");
        rotationEntry.setManaged(false);
        rotationEntry.setVisible(false);
        var gizmo = viewGizmo();
        var canvasHost = new StackPane(canvas, gizmo, pointerHint, rotationEntry);
        StackPane.setAlignment(gizmo, Pos.TOP_RIGHT);
        StackPane.setMargin(gizmo, new Insets(6));
        canvasHost.setMinSize(250, 200);
        canvas.widthProperty().bind(canvasHost.widthProperty());
        canvas.heightProperty().bind(canvasHost.heightProperty());
        canvas.widthProperty().addListener(o -> draw());
        canvas.heightProperty().addListener(o -> draw());
        setCenter(canvasHost);
        setRight(inspector());
        status.setId("sketch-solve-status");
        error.setId("sketch-error");
        error.setWrapText(true);
        error.getStyleClass().add("error");
        profileWarning.setId("sketch-profile-warning");
        profileWarning.setWrapText(true);
        profileWarning.getStyleClass().add("error");
        profileWarning.managedProperty().bind(profileWarning.visibleProperty());
        setBottom(new VBox(5, status, profileWarning, error));
        BorderPane.setMargin(getBottom(), new Insets(8, 0, 0, 0));
        controller.onChanged(this::refresh);
        installGestures();
        installSizeShortcuts();
        installShortcuts(menu);
        tool(Draw.SELECT);
        refresh();
        quickMenu = new QuickMenu(
                canvas,
                this,
                translations,
                () -> new QuickMenu.Content(
                        QuickMenu.controls(
                                shortcutActions::get,
                                "sketch-tool-line",
                                "sketch-tool-rectangle",
                                "sketch-tool-circle",
                                "sketch-tool-arc",
                                "sketch-tool-select",
                                "constraint-finish",
                                "sketch-undo",
                                "sketch-redo"),
                        QuickMenu.controls(
                                shortcutActions::get,
                                "sketch-tool-point",
                                "sketch-tool-spline",
                                "sketch-tool-polygon",
                                "sketch-create-type",
                                "constraint-cancel")),
                () -> QuickMenu.sketchWorkspaces(translations),
                (dx, dy) -> {
                    centerX -= dx / scale;
                    centerY += dy / scale;
                    draw();
                },
                target -> true);
    }

    private void chooseSketchType() {
        var search = new TextField();
        search.setId("sketch-type-search");
        search.setPromptText(translations.translate("gui.search_sketch_types"));
        search.setAccessibleText(translations.translate("gui.search_sketch_types"));
        var results = new ListView<SketchTools.Tool>();
        results.setId("sketch-type-results");
        results.setPrefHeight(300);
        results.setPlaceholder(new Label(translations.translate("gui.no_sketch_types")));
        results.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(SketchTools.Tool item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : translations.translate("tool." + item.name()));
            }
        });
        results.getItems().setAll(SketchTools.Tool.values());
        results.getSelectionModel().selectFirst();
        search.textProperty().addListener((observable, oldValue, query) -> {
            String filter = query.strip().toLowerCase(Locale.ROOT);
            results.getItems()
                    .setAll(Arrays.stream(SketchTools.Tool.values())
                            .filter(value -> translations
                                    .translate("tool." + value.name())
                                    .toLowerCase(Locale.ROOT)
                                    .contains(filter))
                            .toList());
            results.getSelectionModel().selectFirst();
        });
        var dialog = new Dialog<SketchTools.Tool>();
        dialog.setTitle(translations.translate("gui.more_sketch_types_"));
        var content = new VBox(8, search, results);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        var confirm = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
        confirm.setId("sketch-type-confirm");
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
        // Focus search after the modal host's initial button focus pass.
        Platform.runLater(() -> Platform.runLater(search::requestFocus));
        Dialogs.show(dialog, getScene().getWindow(), translations).ifPresent(chosen -> {
            advanced = chosen;
            var refs = List.copyOf(selection);
            tool(Draw.ADVANCED);
            selection.addAll(refs);
            selectionChanged();
            canvas.requestFocus();
        });
    }

    private void cancelDrawing() {
        if (draggedDimension != null) {
            dragLabelPosition = null;
            draggedDimension = null;
        }
        if (dragPoint != null) controller.cancelDrag();
        dragPoint = null;
        boxCandidate = boxSelecting = false;
        moving = false;
        drawing.clear();
        snaps.clear();
        tool(Draw.SELECT);
        selection.clear();
        refresh();
    }

    private Node inspector() {
        selected.setId("sketch-selection");
        selected.setWrapText(true);
        references.setId("sketch-reference");
        references.setMaxWidth(Double.MAX_VALUE);
        references.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(Ref r) {
                return r == null ? "" : r.toString();
            }

            @Override
            public Ref fromString(String value) {
                return Ref.parse(value);
            }
        });
        var add = button(translations.translate("gui.add_to_selection"), "sketch-select-ref", () -> {
            if (dimensionTool && (dimensionEntry != null || selection.size() >= 2)) return;
            if (references.getValue() != null) {
                constraints.getSelectionModel().clearSelection();
                selection.add(references.getValue());
                selectionChanged();
            }
        });
        var clear = button(translations.translate("gui.clear"), "sketch-clear-selection", () -> {
            constraints.getSelectionModel().clearSelection();
            selection.clear();
            activeConstraint = null;
            selectionChanged();
        });
        dimension.setId("sketch-dimension-type");
        dimension
                .getItems()
                .setAll(Arrays.stream(Type.values()).filter(Type::dimension).toList());
        dimension.setValue(Type.LENGTH);
        dimension.valueProperty().addListener(o -> {
            dimensionValue.refreshFormula();
            refreshDimensionValue();
        });
        dimension.setMaxWidth(Double.MAX_VALUE);
        dimensionValue.setId("sketch-dimension-value");
        dimensionValue.setOnAction(e -> dimension());
        var apply = button(translations.translate("gui.apply_dimension"), "sketch-apply-dimension", this::dimension);
        valueCaption.setText(translations.translate("gui.driving_value_mm_degrees_ratio"));
        constraints.setId("sketch-constraints");
        constraints.setPrefHeight(140);
        constraints.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(Constraint value, boolean empty) {
                super.updateItem(value, empty);
                if (empty || value == null) setText(null);
                else {
                    var screenType = viewBasis.mapAxisConstraint(value.type());
                    String label = symbol(screenType) + " " + translations.translate("constraint." + screenType.name());
                    if (SketchConstraints.referenceDimension(value)) {
                        label += " = " + dimensionLabel(value, controller.definition()) + "  "
                                + translations.translate("sketch.referenceDimension");
                    } else if (value.type().dimension()) {
                        label += " = " + number(value.values().getFirst());
                    }
                    setText(label + " · " + value.id());
                }
                if (!empty && value != null) {
                    setTooltip(UiHelp.tooltip(value.refs().toString()));
                }
            }
        });
        constraints.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            boolean reference = b != null && SketchConstraints.referenceDimension(b);
            apply.setDisable(reference);
            dimension.setDisable(reference);
            if (!refreshing) refreshDimensionValue();
            dimensionValue.refreshFormula();
            if (refreshing || b == null) return;
            activeConstraint = null;
            selection.clear();
            selection.addAll(b.refs());
            if (b.type().dimension()) {
                dimension.setValue(b.type());
                String expression =
                        controller.parameters().binding(new ParameterSet.Target(featureId, "constraint:" + b.id()));
                dimensionValue.setText(
                        expression.isEmpty() ? number(dimensionValue(b, controller.definition())) : expression);
            }
            selectionChanged();
        });
        showBadges.setSelected(true);
        showBadges.setId("sketch-show-badges");
        UiHelp.install(showBadges, translations);
        showBadges.setOnAction(e -> draw());
        var content = new VBox(
                8,
                toolOptions,
                new Label(translations.translate("gui.selected_geometry")),
                selected,
                references,
                new HBox(5, add, clear),
                button(translations.translate("gui.apply_active_constraint"), "sketch-apply-constraint", () -> {
                    if (activeConstraint != null) apply(activeConstraint);
                }),
                new Separator(),
                valueCaption,
                dimension,
                dimensionValue,
                apply,
                new Separator(),
                new Label(translations.translate("gui.constraints_")),
                constraints,
                button(translations.translate("gui.remove_unfix"), "sketch-remove-constraint", this::removeConstraint),
                showBadges,
                button(translations.translate("gui.edit_selected_text"), "sketch-edit-text", this::editText),
                new Separator(),
                button(
                        translations.translate("gui.toggle_construction"),
                        "sketch-toggle-construction",
                        this::toggleConstruction),
                button(translations.translate("gui.use_selected_as_profile"), "sketch-use-profile", this::useProfile),
                button(
                        translations.translate("gui.use_all_nonconstruction_curves"),
                        "sketch-all-profile",
                        () -> edit(controller
                                .definition()
                                .withBoundary(controller.definition().entities().stream()
                                        .filter(e -> !e.construction() && e.kind() != Kind.POINT)
                                        .map(Entity::id)
                                        .toList()))),
                button(
                        translations.translate("gui.delete_selected_geometry"),
                        "sketch-delete-geometry",
                        this::deleteGeometry));
        var scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setPrefWidth(270);
        scroll.setMinWidth(240);
        BorderPane.setMargin(scroll, new Insets(0, 0, 0, 10));
        return scroll;
    }

    private void installGestures() {
        canvas.setOnMousePressed(this::pressCanvas);
        canvas.setOnMouseDragged(this::dragCanvas);
        canvas.setOnMouseReleased(this::releaseCanvas);
        canvas.setOnMouseClicked(this::clickCanvas);
        canvas.setOnMouseMoved(e -> {
            pointer = new javafx.geometry.Point2D(e.getX(), e.getY());
            if (sizeEntry != null) return;
            cursor = drawingPosition(e);
            if (tool != Draw.SELECT || dimensionTool) draw();
        });
        canvas.setOnMouseExited(e -> {
            pointer = null;
            hoverSnap = null;
            draw();
        });
        canvas.setOnScroll(e -> {
            var anchor = model(e.getX(), e.getY());
            scale = Math.clamp(scale * Math.exp(e.getDeltaY() * 0.002), 0.15, 100);
            var moved = model(e.getX(), e.getY());
            var offset = viewBasis.project(anchor.minus(moved));
            centerX += offset.x();
            centerY += offset.y();
            draw();
            e.consume();
        });
    }

    private void selectToolForGesture() {
        tool = Draw.SELECT;
        drawing.clear();
        snaps.clear();
        hoverSnap = null;
    }

    private void tool(Draw next) {
        rotatingText = null;
        closeSizeEntry();
        closeDimensionEntry();
        dimensionTool = false;
        tool = next;
        hoverSnap = null;
        activeConstraint = null;
        drawing.clear();
        snaps.clear();
        selection.clear();
        hint.setText(
                switch (next) {
                    case SELECT -> translations.translate("sketch.selectionHelp");
                    case POINT -> translations.translate("gui.click_to_place_a_sketch_point");
                    case LINE ->
                        translations.translate(
                                "gui.click_two_endpoints_nearby_endpoints_and_horizontal_vertical_directions_snap_when_auto_constrai");
                    case RECTANGLE ->
                        translations.translate(
                                "gui.click_two_opposite_corners_horizontal_vertical_and_coincident_constraints_are_added");
                    case CIRCLE -> translations.translate("gui.click_a_center_then_a_radius_point");
                    case ARC -> translations.translate("gui.click_center_start_then_end_the_arc_runs_counterclockwise");
                    case SPLINE ->
                        translations.translate("gui.click_start_first_control_point_second_control_point_then_end");
                    case POLYGON ->
                        translations.translate(
                                "gui.click_center_then_a_vertex_the_sides_remain_equal_with_a_regular_polygon_constraint");
                    case ADVANCED ->
                        translations.translate("tool.hint."
                                + (centerPolygonTool() ? SketchTools.Tool.POLYGON_INSCRIBED : advanced).name());
                });
        showToolOptions();
        refreshDimensionValue();
        if (polygonTool()) canvas.requestFocus();
        draw();
    }

    private void showToolOptions() {
        toolOptions.getChildren().clear();
        boolean visible = tool == Draw.ADVANCED || tool == Draw.POLYGON;
        toolOptions.setVisible(visible);
        toolOptions.setManaged(visible);
        if (!visible) return;
        if (tool == Draw.POLYGON) {
            option(translations.translate("draw.POLYGON"), polygonMode);
            return;
        }
        toolOptions.getChildren().add(new Label(translations.translate("tool." + advanced.name())));
        switch (advanced) {
            case CIRCLE_TWO_TANGENT -> option(translations.translate("gui.radius_mm"), toolRadius);
            case CONIC -> option(translations.translate("gui.rho_0_1"), toolRho);
            case CONTROL_SPLINE -> {
                option(translations.translate("gui.degree"), splineDegree);
                toolOptions.getChildren().add(closedCurve);
            }
            case FIT_SPLINE -> toolOptions.getChildren().add(closedCurve);
            case TEXT, TEXT_ON_PATH -> {
                option(translations.translate("gui.text_up_to_32_characters"), textContent);
                option(translations.translate("gui.font_family"), textFont);
                option(translations.translate("gui.style"), textStyle);
                toolOptions.getChildren().addAll(mirrorHorizontal, mirrorVertical);
                option(translations.translate("gui.height_mm"), textHeight);
                if (advanced == SketchTools.Tool.TEXT) {
                    option(translations.translate("sketch.text.horizontal"), textHorizontal);
                    option(translations.translate("sketch.text.vertical"), textVertical);
                }
                option(translations.translate("gui.angle_degrees"), activeTextAngle());
                option(translations.translate("gui.extra_spacing_em"), textSpacing);
            }
            default -> {}
        }
        option(translations.translate("gui.next_point_x_mm"), pointX);
        option(translations.translate("gui.next_point_y_mm"), pointY);
        toolOptions
                .getChildren()
                .add(button(translations.translate("gui.add_numeric_point"), "sketch-add-point", () -> {
                    try {
                        advancedPoint(new Vec(value(pointX, "X"), value(pointY, "Y")));
                    } catch (RuntimeException failure) {
                        error.setText(failure.getMessage());
                    }
                }));
        toolOptions
                .getChildren()
                .add(button(
                        translations.translate("gui.complete_retry"), "sketch-complete-curve", this::finishAdvanced));
        toolOptions.getChildren().add(new Separator());
    }

    private void option(String label, Node input) {
        toolOptions.getChildren().addAll(new Label(label), input);
    }

    private FormulaField activeTextAngle() {
        return advanced == SketchTools.Tool.TEXT_ON_PATH ? pathTextAngle : textAngle;
    }

    private SketchTools.Options options() {
        return new SketchTools.Options(
                polygonTool() ? sides.integer() : 6,
                splineDegree.getValue(),
                closedCurve.isSelected(),
                construction.isSelected(),
                value(toolRadius, translations.translate("gui.radius")),
                value(toolRho, translations.translate("gui.rho")),
                textContent.evaluatedText(),
                textFont.evaluatedText().strip(),
                textStyle.getSelectionModel().getSelectedIndex(),
                value(textHeight, translations.translate("gui.text_height")),
                advanced == SketchTools.Tool.TEXT || advanced == SketchTools.Tool.TEXT_ON_PATH
                        ? Measurements.radians(value(activeTextAngle(), translations.translate("gui.text_angle")))
                        : 0,
                value(textSpacing, translations.translate("gui.text_spacing")),
                textHorizontal.getValue(),
                textVertical.getValue());
    }

    private void advancedPoint(Vec point) {
        error.setText("");
        if (advanced == SketchTools.Tool.TEXT_ON_PATH) {
            var path = hitCurve(px(point), py(point), true);
            if (path != null) {
                selection.clear();
                selection.add(path.ref());
                point = path.position();
            } else if (selection.size() != 1 || selection.getFirst().part() != Part.OBJECT) {
                error.setText(translations.translate("tool.hint.TEXT_ON_PATH"));
                return;
            }
        }
        if (advanced.clicks() > 0 && drawing.size() >= advanced.clicks()) drawing.removeLast();
        if (advanced.clicks() == 0 && drawing.size() >= 3 && drawing.getFirst().distance(point) * scale < 7) {
            closedCurve.setSelected(true);
            finishAdvanced();
            return;
        }
        drawing.add(point);
        if (advanced.clicks() > 0 && drawing.size() == advanced.clicks()) finishAdvanced();
        else {
            draw();
        }
    }

    private void finishAdvanced() {
        finishAdvanced(null);
    }

    private void finishAdvanced(SketchSizeEntry.Dimensions sizes) {
        if (tool != Draw.ADVANCED) return;
        try {
            String entityId = id(advanced.name().toLowerCase(Locale.ROOT));
            var next = SketchTools.add(
                    withReferences(controller.definition(), selection),
                    advanced,
                    entityId,
                    List.copyOf(drawing),
                    List.copyOf(selection),
                    options());
            if (sizes != null) next = sizes.apply(next, entityId);
            boolean placingText = advanced == SketchTools.Tool.TEXT || advanced == SketchTools.Tool.TEXT_ON_PATH;
            if (placingText) {
                next = next.replace(new TextMirror(mirrorHorizontal.isSelected(), mirrorVertical.isSelected())
                        .apply(next.entity(entityId)));
                var parameters = bindText(controller.parameters(), entityId, textContent, textFont);
                for (var entry : Map.of(
                                Type.TEXT_HEIGHT,
                                textHeight,
                                Type.ANGLE,
                                activeTextAngle(),
                                Type.TEXT_SPACING,
                                textSpacing)
                        .entrySet()) {
                    if (Expressions.isNumber(entry.getValue().getText())) continue;
                    String constraintId = id("dimension");
                    next = next.add(SketchConstraints.create(
                            next,
                            constraintId,
                            entry.getKey(),
                            List.of(new Ref(entityId, Part.OBJECT)),
                            entry.getValue().value()));
                    parameters = parameters.bind(
                            new ParameterSet.Target(featureId, "constraint:" + constraintId),
                            entry.getValue().getText());
                }
                parameters.validate();
                controller.edit(next, parameters);
            } else {
                edit(next);
            }
            drawing.clear();
            snaps.clear();
            tool(Draw.SELECT);
            if (placingText) editText(next.entity(entityId));
        } catch (RuntimeException failure) {
            error.setText(failure.getMessage());
            // Numeric options can be repaired and the same points retried with Enter.
            draw();
        }
    }

    private void editText() {
        selection.stream()
                .flatMap(ref -> editableText(ref).stream())
                .findFirst()
                .ifPresentOrElse(
                        this::editText, () -> error.setText(translations.translate("gui.select_a_text_entity_first")));
    }

    private Optional<Entity> editableText(Ref ref) {
        var geometry = controller.definition();
        String id = SketchCopies.originalId(geometry, ref.entityId());
        return geometry.entities().stream()
                .filter(e -> e.kind() == Kind.TEXT && e.id().equals(id))
                .findFirst();
    }

    private void editText(Entity chosen) {
        try {
            var dialog = new Dialog<TextEdit>();
            dialog.initOwner(getScene().getWindow());
            dialog.setTitle(translations.translate("gui.edit_sketch_text"));
            var content = FormulaField.text(
                    textExpression(chosen, "content", chosen.text().content()), this::formulaParameters, translations);

            content.setId("sketch-edit-text-content");
            var font = FormulaField.text(
                    textExpression(chosen, "font", chosen.text().font()), this::formulaParameters, translations);

            font.setId("sketch-edit-text-font");
            var path = new ComboBox<String>();
            path.setId("sketch-edit-text-path");
            path.getItems().add("");
            for (var entity : controller.definition().entities()) {
                if (entity.kind() != Kind.TEXT && entity.kind() != Kind.POINT) {
                    path.getItems().add(entity.id());
                }
            }
            if (!path.getItems().contains(chosen.text().pathId())) {
                path.getItems().add(chosen.text().pathId());
            }
            path.setValue(chosen.text().pathId());
            path.setPromptText(translations.translate("gui.no_path_baseline_position"));
            var style = new ComboBox<String>();
            style.setId("sketch-edit-text-style");
            style.getItems().setAll(textStyle.getItems());
            style.getSelectionModel().select(chosen.text().style());
            var horizontal = alignmentChoice(
                    "sketch-edit-text-horizontal",
                    SketchText.Horizontal.values(),
                    chosen.text().horizontal());
            var vertical = alignmentChoice(
                    "sketch-edit-text-vertical",
                    SketchText.Vertical.values(),
                    chosen.text().vertical());
            horizontal.disableProperty().bind(path.valueProperty().isNotEqualTo(""));
            vertical.disableProperty().bind(horizontal.disableProperty());
            var mirror = TextMirror.of(chosen);
            var flipX = mirrorOption("horizontal", true, mirror.horizontal());
            var flipY = mirrorOption("vertical", true, mirror.vertical());
            var heightConstraint = textConstraint(chosen, Type.TEXT_HEIGHT);
            var angleConstraint = textConstraint(chosen, Type.ANGLE);
            var height = textDimensionField(heightConstraint, chosen.value(2), ParameterUnit.MM);
            height.setId("sketch-edit-text-height");
            var angle = textDimensionField(
                            angleConstraint,
                            SketchRotationAngles.signed(Measurements.degrees(chosen.value(3))),
                            ParameterUnit.DEG)
                    .bounds(-180, true, 180, true);
            angle.setId("sketch-edit-text-angle");
            var message = new Label();
            message.setWrapText(true);
            var fields = new GridPane();
            fields.setVgap(6);
            fields.setHgap(10);
            fields.addRow(0, new Label(translations.translate("tool.TEXT")), content);
            fields.addRow(1, new Label(translations.translate("gui.font_family")), font);
            fields.addRow(2, new Label(translations.translate("gui.style")), style);
            fields.addRow(3, new Label(translations.translate("gui.height_mm")), height);
            fields.addRow(4, new Label(translations.translate("sketch.text.horizontal")), horizontal);
            fields.addRow(5, new Label(translations.translate("sketch.text.vertical")), vertical);
            fields.addRow(6, new Label(translations.translate("gui.path_blank_to_detach")), path);
            fields.addRow(7, flipX, flipY);
            fields.addRow(8, new Label(translations.translate("gui.angle_degrees")), angle);
            dialog.getDialogPane().setContent(new VBox(7, fields, message));
            dialog.getDialogPane().getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
            var result = new TextEdit[1];
            dialog.getDialogPane()
                    .lookupButton(ButtonType.OK)
                    .addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
                        try {
                            var geometry = controller.definition();
                            var current = geometry.entity(chosen.id());
                            var position = new Vec(current.value(0), current.value(1));
                            if (!chosen.text().pathId().equals(path.getValue())) {
                                var anchor = SketchText.anchor(geometry, current);
                                position = path.getValue().isEmpty()
                                        ? anchor
                                        : new Vec(SketchTools.pathOffset(geometry.entity(path.getValue()), anchor), 0);
                                if (!path.getValue().isEmpty()) {
                                    geometry = geometry.withBoundary(geometry.boundary().stream()
                                            .filter(id -> !id.equals(path.getValue()))
                                            .toList());
                                }
                            }
                            double size = value(height, translations.translate("gui.text_height"));
                            double degrees = value(angle, translations.translate("gui.text_angle"));
                            var text = SketchText.create(
                                    chosen.id(),
                                    content.evaluatedText(),
                                    font.evaluatedText().strip(),
                                    style.getSelectionModel().getSelectedIndex(),
                                    position.x(),
                                    position.y(),
                                    size,
                                    Measurements.radians(degrees),
                                    chosen.value(4),
                                    path.getValue(),
                                    chosen.construction(),
                                    horizontal.getValue(),
                                    vertical.getValue());
                            var edit = new TextEdit(
                                    geometry.replace(
                                            new TextMirror(flipX.isSelected(), flipY.isSelected()).apply(text)),
                                    bindText(controller.parameters(), chosen.id(), content, font));
                            edit = applyTextDimension(
                                    edit, text, Type.TEXT_HEIGHT, heightConstraint, size, height.getText());
                            edit = applyTextDimension(
                                    edit, text, Type.ANGLE, angleConstraint, degrees, angle.getText());
                            SketchConstraints.validateGeometry(edit.geometry());
                            edit.parameters().validate();
                            result[0] = edit;
                        } catch (RuntimeException failure) {
                            message.setText(failure.getMessage());
                            event.consume();
                        }
                    });
            dialog.setResultConverter(button -> button == ButtonType.OK ? result[0] : null);
            Dialogs.show(dialog, getScene().getWindow(), translations)
                    .ifPresent(e -> controller.edit(e.geometry(), e.parameters()));
        } catch (RuntimeException failure) {
            error.setText(failure.getMessage());
        }
    }

    private Optional<Constraint> textConstraint(Entity text, Type type) {
        return controller.definition().constraints().stream()
                .filter(c -> !SketchConstraints.referenceDimension(c)
                        && c.type() == type
                        && c.refs().equals(List.of(text.ref())))
                .findFirst();
    }

    private FormulaField textDimensionField(Optional<Constraint> constraint, double currentValue, ParameterUnit unit) {
        String expression = constraint
                .map(c -> controller.parameters().binding(new ParameterSet.Target(featureId, "constraint:" + c.id())))
                .orElse("");
        return numeric(expression.isEmpty() ? Double.toString(currentValue) : expression, unit);
    }

    private TextEdit applyTextDimension(
            TextEdit edit, Entity text, Type type, Optional<Constraint> constraint, double value, String expression) {
        var geometry = edit.geometry();
        var parameters = edit.parameters();
        boolean formula = !Expressions.isNumber(expression);
        if (constraint.isPresent() || formula) {
            String constraintId = constraint.map(Constraint::id).orElseGet(() -> id("dimension"));
            if (constraint.isPresent()) {
                geometry = geometry.withConstraints(geometry.constraints().stream()
                        .map(c -> c.id().equals(constraintId) ? SketchConstraints.drivingValue(c, value) : c)
                        .toList());
            } else {
                geometry = geometry.add(
                        SketchConstraints.create(geometry, constraintId, type, List.of(text.ref()), value));
            }
            parameters = parameters.bind(
                    new ParameterSet.Target(featureId, "constraint:" + constraintId), formula ? expression : "");
        }
        return new TextEdit(geometry, parameters);
    }

    private Vec drawingPosition(MouseEvent event) {
        var pointer = model(event.getX(), event.getY());
        hoverSnap = dimensionTool || auto.isSelected() && tool != Draw.SELECT
                ? SketchSnapping.find(visibleEntities(controller.displayed()), pointer, scale)
                : null;
        if (hoverSnap != null) return hoverSnap.position();
        if ((tool == Draw.LINE || centerPolygonTool())
                && drawing.size() == 1
                && (auto.isSelected() || event.isShiftDown())) {
            return SketchSnapping.axis(drawing.getFirst(), pointer, scale, event.isShiftDown());
        }
        return pointer;
    }

    private void drawingClick(MouseEvent event) {
        if (sizeEntry != null && sides.getParent() == sizeEntry && !acceptPolygonSides()) return;
        closeSizeEntry();
        var position = drawingPosition(event);
        placeDrawingPoint(position, hoverSnap, null);
    }

    private void placeDrawingPoint(Vec p, SketchSnapping.Snap hit, SketchSizeEntry.Dimensions sizes) {
        try {
            if (tool == Draw.ADVANCED && sizes != null) {
                drawing.add(p);
                finishAdvanced(sizes);
                return;
            }
            if (tool == Draw.ADVANCED) {
                if (advanced == SketchTools.Tool.POLYGON_CIRCUMSCRIBED && drawing.size() == 1) {
                    // The numeric tool takes a side midpoint. Mouse placement tracks a vertex.
                    var layout = SketchTools.PolygonLayout.fromVertex(drawing.getFirst(), p);
                    p = p.mix(layout.vertex(1, sides.integer()), .5);
                }
                advancedPoint(p);
                return;
            }
            drawing.add(p);
            snaps.add(hit == null ? null : hit.ref());
            int count = switch (tool) {
                case POINT -> 1;
                case ARC -> 3;
                case SPLINE -> 4;
                default -> 2;
            };
            if (drawing.size() < count) {
                draw();
                return;
            }
            String id = id(tool.name().toLowerCase(Locale.ROOT));
            SketchGeometry addition;
            var a = drawing.getFirst();
            var b = drawing.getLast();
            addition = drawnGeometry(id, a, b, sizes);
            if (construction.isSelected()) {
                addition = addition.withEntities(addition.entities().stream()
                                .map(e -> e.withConstruction(true))
                                .toList())
                        .withBoundary(List.of());
            }
            var g = withReferences(controller.definition(), snaps);
            var entities = new ArrayList<>(g.entities());
            entities.addAll(addition.entities());
            var constraints = new ArrayList<>(g.constraints());
            constraints.addAll(addition.constraints());
            var boundary = new ArrayList<>(g.boundary());
            boundary.addAll(addition.boundary());
            var next = new SketchGeometry(entities, constraints, boundary);
            next = addSnapConstraints(next, addition, id);
            SketchConstraints.validateGeometry(next);
            edit(next);
            drawing.clear();
            snaps.clear();
            if (tool == Draw.LINE && (hit == null || hit.ref().part() == Part.OBJECT)) {
                drawing.add(b);
                snaps.add(new Ref(id, Part.END));
                hoverSnap = null;
                draw();
            } else {
                tool(Draw.SELECT);
            }
        } catch (RuntimeException failure) {
            error.setText(failure.getMessage());
            drawing.clear();
            snaps.clear();
            draw();
        }
    }

    private void request(Type type) {
        closeSizeEntry();
        dimensionTool = false;
        tool = Draw.SELECT;
        drawing.clear();
        snaps.clear();
        activeConstraint = viewBasis.mapAxisConstraint(type);
        hint.setText(translations.translate("constraint." + type.name())
                + " · "
                + translations.translate("constraint.hint." + type.name()));
        if (enough(activeConstraint)) apply(activeConstraint);
        draw();
    }

    private boolean enough(Type type) {
        int n = selection.size();
        return switch (type) {
            case FIX -> n == 1;
            case HORIZONTAL, VERTICAL ->
                n == 2 || (n == 1 && selection.getFirst().part() == Part.OBJECT);
            case SYMMETRY -> n == 3;
            case POLYGON -> n >= 3;
            default -> n == 2;
        };
    }

    private void selectionChanged() {
        refreshDimensionValue();
        if (dimensionTool) {
            List<Ref> refs;
            try {
                refs = SketchDimensions.references(
                        withReferences(controller.displayed(), selection), List.copyOf(selection));
            } catch (IllegalArgumentException invalid) {
                error.setText(invalid.getMessage());
                selection.clear();
                return;
            }
            selection.clear();
            selection.addAll(refs);
        }
        selected.setText(
                selection.isEmpty()
                        ? translations.translate("gui.nothing_selected")
                        : selection.stream()
                                .map(Ref::toString)
                                .reduce((a, b) -> a + "\n" + b)
                                .orElse(""));
        if (activeConstraint != null && activeConstraint != Type.POLYGON && enough(activeConstraint)) {
            apply(activeConstraint);
        }
        if (dimensionTool
                && dimensionEntry == null
                && selection.size() == 2
                && constraints.getSelectionModel().getSelectedItem() == null) {
            Vec position = cursor.plus(new Vec(0, -20 / scale));
            if (selection.stream().allMatch(r -> r.part() != Part.OBJECT)) {
                var refs = List.copyOf(selection);
                var g = withReferences(controller.displayed(), refs);
                var a = g.point(refs.get(0));
                var b = g.point(refs.get(1));
                var middle = a.plus(b).times(0.5);
                if (Math.abs(b.x() - a.x()) > 1e-8 && Math.abs(b.y() - a.y()) > 1e-8) position = middle;
                else {
                    position = Math.abs(b.y() - a.y()) > Math.abs(b.x() - a.x())
                            ? new Vec(Math.max(a.x(), b.x()) + 20 / scale, middle.y())
                            : new Vec(middle.x(), Math.min(a.y(), b.y()) - 20 / scale);
                }
            }
            placeDimension(position);
        }
        draw();
    }

    private void apply(Type type) {
        try {
            var g = withReferences(controller.definition(), selection);
            var refs = List.copyOf(selection);
            if (refs.stream().allMatch(r -> FaceProjection.reference(r.entityId()))) return;
            if (type == Type.FIX) {
                var existing = g.constraints().stream()
                        .filter(c -> c.type() == Type.FIX && c.refs().equals(refs))
                        .findFirst();
                if (existing.isPresent()) {
                    edit(g.withConstraints(g.constraints().stream()
                            .filter(c -> c != existing.get())
                            .toList()));
                    activeConstraint = null;
                    return;
                }
            }
            var c = SketchConstraints.create(g, id("constraint"), type, refs, 0);
            activeConstraint = null;
            edit(g.add(c));
        } catch (RuntimeException failure) {
            error.setText(failure.getMessage());
        }
    }

    private void startDimension() {
        var refs = selection.stream().limit(2).toList();
        tool(Draw.SELECT);
        selection.addAll(refs);
        constraints.getSelectionModel().clearSelection();
        dimensionTool = true;
        String guidance = translations.translate("gui.dimension_hint");
        hint.setText(
                projection.isEmpty()
                        ? guidance
                        : guidance + "\n" + translations.translate("sketch.projection.readOnly"));
        canvas.requestFocus();
        selectionChanged();
    }

    private void placeDimension(Vec position) {
        if (dimensionEntry != null || controller.busy()) return;
        try {
            var candidate = SketchDimensions.measure(
                    withReferences(controller.displayed(), selection), List.copyOf(selection), position);
            dimension.setValue(candidate.type());
            if (addReferenceDimension(candidate.type(), position)) return;
            dimensionValue.setText(number(candidate.value()));
            editDimensionValue(position);
        } catch (RuntimeException failure) {
            error.setText(translations.translate("gui.dimension_hint") + "\n" + failure.getMessage());
        }
    }

    private void editDimensionValue() {
        var chosen = constraints.getSelectionModel().getSelectedItem();
        if (chosen != null && SketchConstraints.referenceDimension(chosen)) return;
        editDimensionValue(
                chosen == null
                        ? cursor
                        : SketchConstraints.labelPosition(chosen).orElse(cursor));
    }

    private void editDimensionValue(Vec position) {
        closeDimensionEntry();
        dimensionEntryPosition = position;
        var input = FormulaField.number(
                dimensionValue.getText(), this::formulaParameters, this::dimensionUnit, translations);
        input.setId("sketch-inline-dimension");
        input.setPrefColumnCount(14);
        input.setMinWidth(200);
        input.setPrefWidth(200);
        input.setAccessibleText(translations.translate("gui.sketch_dimension"));
        var apply = new Button("✓");
        apply.setId("sketch-inline-dimension-apply");
        apply.setMinWidth(Region.USE_PREF_SIZE);
        UiHelp.describe(apply, translations.translate("gui.apply_dimension"));
        Runnable commit = () -> {
            if (controller.busy()) return;
            dimensionValue.setText(input.getText());
            if (dimension()) {
                closeDimensionEntry();
                if (dimensionTool) selection.clear();
                draw();
            } else {
                input.requestFocus();
            }
        };
        input.setOnAction(e -> commit.run());
        apply.setOnAction(e -> commit.run());
        dimensionEntry = new HBox(3, input, apply);
        dimensionEntry.setManaged(false);
        dimensionEntry.setStyle("-fx-background-color: -fx-control-inner-background; -fx-padding: 3;");
        ((StackPane) canvas.getParent()).getChildren().add(dimensionEntry);
        dimensionEntry.applyCss();
        positionDimensionEntry();
        draw();
        Platform.runLater(() -> {
            if (dimensionEntry == null) return;
            positionDimensionEntry();
            input.requestFocus();
            input.selectAll();
        });
    }

    private void positionDimensionEntry() {
        if (dimensionEntry == null) return;
        double width = dimensionEntry.prefWidth(-1);
        double height = dimensionEntry.prefHeight(width);
        dimensionEntry.resizeRelocate(
                Math.clamp(px(dimensionEntryPosition) - width / 2, 0, Math.max(0, canvas.getWidth() - width)),
                Math.clamp(py(dimensionEntryPosition) - height / 2, 0, Math.max(0, canvas.getHeight() - height)),
                width,
                height);
    }

    private void closeDimensionEntry() {
        if (dimensionEntry == null) return;
        ((StackPane) canvas.getParent()).getChildren().remove(dimensionEntry);
        dimensionEntry = null;
        canvas.requestFocus();
    }

    private boolean dimension() {
        try {
            var g = withReferences(controller.definition(), selection);
            var chosen = constraints.getSelectionModel().getSelectedItem();
            if (controller.busy()) return false;
            if (chosen != null && !chosen.type().dimension()) chosen = null;
            var type = chosen == null ? dimension.getValue() : chosen.type();
            if (chosen != null && SketchConstraints.referenceDimension(chosen)) return false;
            if (chosen == null && addReferenceDimension(type, dimensionEntry == null ? null : dimensionEntryPosition)) {
                return true;
            }
            driveDimension(g, chosen, type);
            return true;
        } catch (RuntimeException failure) {
            error.setText(failure.getMessage());
            dimensionValue.getStyleClass().add("invalid");
            return false;
        }
    }

    private void driveDimension(SketchGeometry g, Constraint chosen, Type type) {
        String expression = dimensionValue.getText().strip();
        double value = controller.parameters().value(expression, FeatureDimensions.unit(type));
        String id = chosen != null ? chosen.id() : id("dimension");
        SketchGeometry next;
        if (chosen != null) {
            next = g.withConstraints(g.constraints().stream()
                    .map(c -> c.id().equals(id) ? SketchConstraints.drivingValue(c, value) : c)
                    .toList());
        } else {
            next = g.add(SketchConstraints.create(
                    g, id, type, SketchDimensions.drivingReferences(g, List.copyOf(selection), type), value));
        }
        if (dimensionEntry != null) {
            next = next.withConstraints(next.constraints().stream()
                    .map(c -> c.id().equals(id) ? SketchConstraints.positionLabel(c, dimensionEntryPosition) : c)
                    .toList());
        }
        var parameters = controller
                .parameters()
                .bind(
                        new ParameterSet.Target(featureId, "constraint:" + id),
                        expression.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")
                                ? ""
                                : expression);
        parameters.validate();
        error.setText("");
        dimensionValue.getStyleClass().remove("invalid");
        controller.edit(next, parameters);
    }

    private void refreshDimensionValue() {
        var chosen = constraints.getSelectionModel().getSelectedItem();
        boolean readOnly = chosen != null && SketchConstraints.referenceDimension(chosen);
        if (readOnly) {
            dimensionValue.setText(number(SketchConstraints.measuredValue(controller.definition(), chosen)));
        } else if (chosen == null && fixedSelection()) {
            readOnly = true;
            try {
                var g = withReferences(controller.displayed(), selection);
                var type = dimension.getValue();
                var refs = SketchDimensions.drivingReferences(g, List.copyOf(selection), type);
                dimensionValue.setText(
                        number(SketchConstraints.measuredValue(g, new Constraint("preview", type, refs, List.of()))));
            } catch (IllegalArgumentException invalidSelection) {
                dimensionValue.clear();
            }
        }
        dimensionValue.setDisable(readOnly);
        valueCaption.setText(
                translations.translate(readOnly ? "sketch.referenceDimension" : "gui.driving_value_mm_degrees_ratio"));
    }

    private boolean fixedSelection() {
        if (selection.isEmpty()
                || controller.busy()
                || controller.result() == null
                || !controller.result().solved()) return false;
        var fixed = controller.result().fullyConstrainedReferences();
        return selection.stream().allMatch(ref -> fixed.contains(ref) || FaceProjection.reference(ref.entityId()));
    }

    private boolean addReferenceDimension(Type type, Vec position) {
        if (!fixedSelection()) return false;
        var g = withReferences(controller.displayed(), selection);
        var refs = SketchDimensions.drivingReferences(g, List.copyOf(selection), type);
        var reference = SketchConstraints.referenceDimension(g, id("dimension"), type, refs);
        if (position == null) {
            var ends = dimensionEndpoints(reference, g);
            position = ends.start().mix(ends.end(), .5).minus(new Vec(0, 24 / scale));
        }
        reference = SketchConstraints.positionLabel(reference, position);
        edit(g.add(reference));
        constraints.getSelectionModel().select(reference);
        selection.clear();
        hint.setText(translations.translate("sketch.referenceDimension"));
        return true;
    }

    private void removeConstraint() {
        var chosen = constraints.getSelectionModel().getSelectedItem();
        if (chosen == null) {
            error.setText(translations.translate("gui.select_a_constraint_in_the_list_or_click_its_badge_first"));
            return;
        }
        edit(controller
                .definition()
                .withConstraints(controller.definition().constraints().stream()
                        .filter(c -> !c.id().equals(chosen.id()))
                        .toList()));
    }

    private void toggleConstruction() {
        if (tool != Draw.SELECT) {
            construction.setSelected(!construction.isSelected());
            return;
        }
        var ids = selection.stream()
                .map(Ref::entityId)
                .filter(id -> !FaceProjection.reference(id))
                .map(id -> SketchCopies.originalId(controller.definition(), id))
                .collect(java.util.stream.Collectors.toSet());
        var g = controller.definition();
        var entities = g.entities().stream()
                .map(e -> ids.contains(e.id()) ? e.withConstruction(!e.construction()) : e)
                .toList();
        if (ids.isEmpty()) return;
        var boundary = new ArrayList<>(g.boundary().stream()
                .filter(id -> entities.stream().anyMatch(e -> e.id().equals(id) && !e.construction()))
                .toList());
        for (var entity : entities) {
            if (ids.contains(entity.id())
                    && !entity.construction()
                    && entity.kind() != Kind.POINT
                    && !boundary.contains(entity.id())) boundary.add(entity.id());
        }
        edit(new SketchGeometry(entities, g.constraints(), boundary));
    }

    private FlowPane operationsBar() {
        var bar = new FlowPane(4, 4, new Label(translations.translate("action.operations")));
        bar.setAlignment(Pos.CENTER_LEFT);
        for (var key :
                List.of("action.fillet", "sketch.copy.rectangular", "sketch.copy.mirror", "sketch.copy.circular")) {
            String operation = key.substring(key.lastIndexOf('.') + 1);
            String name = translations.translate(key);
            var action = new Button(null, OperationIcons.icon(operation));
            action.setId("sketch-operation-" + operation);
            action.getStyleClass().add("constraint-icon");
            action.setAccessibleText(name);
            String help =
                    translations.translate(key.equals("action.fillet") ? "help.sketch-fillet" : "sketch.copy.select");
            UiHelp.describe(action, name + " · " + help);
            action.setOnAction(event -> showOperation(key, help));
            bar.getChildren().add(action);
        }
        return bar;
    }

    private void showOperation(String key, String help) {
        var selectedRefs = List.copyOf(selection);
        tool(Draw.SELECT);
        selection.addAll(selectedRefs);
        selectionChanged();
        toolOptions.setManaged(true);
        toolOptions.setVisible(true);
        toolOptions.getChildren().add(new Label(translations.translate(key)));
        hint.setText(help);
        switch (key) {
            case "action.fillet" -> {
                var radius = operationField("gui.radius_mm", "5", ParameterUnit.MM);
                radius.setId("sketch-fillet-radius");
                var apply = button(translations.translate("dialog.apply"), "sketch-fillet", () -> fillet(radius));
                radius.setOnAction(event -> apply.fire());
                toolOptions.getChildren().add(apply);
            }
            case "sketch.copy.rectangular" -> {
                var nx = operationField("sketch.copy.countX", "2", ParameterUnit.NUMBER);
                var dx = operationField("sketch.copy.stepX", "10", ParameterUnit.MM);
                var ny = operationField("sketch.copy.countY", "1", ParameterUnit.NUMBER);
                var dy = operationField("sketch.copy.stepY", "10", ParameterUnit.MM);
                copyButton(copies -> copies.rectangular(nx.integer(), ny.integer(), dx.value(), dy.value()));
            }
            case "sketch.copy.circular" -> {
                var count = operationField("sketch.copy.count", "4", ParameterUnit.NUMBER);
                var x = operationField("gui.center_x_mm", "0", ParameterUnit.MM);
                var y = operationField("gui.center_y_mm", "0", ParameterUnit.MM);
                var angle = operationField("gui.angle_degrees", "360", ParameterUnit.DEG);
                copyButton(copies -> copies.circular(new Vec(x.value(), y.value()), count.integer(), angle.value()));
            }
            case "sketch.copy.mirror" -> mirrorControls();
            default -> throw new IllegalArgumentException(key);
        }
    }

    private FormulaField operationField(String label, String initial, ParameterUnit unit) {
        var field = numeric(initial, unit);
        field.setId("sketch-operation-" + label.substring(label.lastIndexOf('.') + 1));
        option(translations.translate(label), field);
        return field;
    }

    private void mirrorControls() {
        var axis = new ComboBox<String>();
        axis.setId("sketch-mirror-axis");
        axis.getItems().addAll("X", "Y");
        var lines = visibleEntities(controller.displayed()).stream()
                .filter(e -> e.kind() == Kind.LINE)
                .toList();
        lines.forEach(e -> axis.getItems().add(e.id()));
        axis.getSelectionModel().selectFirst();
        option(translations.translate("sketch.copy.axis"), axis);
        copyButton(copies -> {
            int index = axis.getSelectionModel().getSelectedIndex();
            var origin = new Vec(0, 0);
            if (index < 2) return copies.mirror(origin, index == 0 ? new Vec(1, 0) : new Vec(0, 1));
            var line = lines.get(index - 2);
            return copies.mirror(line);
        });
    }

    private void copyButton(java.util.function.Function<SketchCopies, SketchGeometry> operation) {
        toolOptions.getChildren().add(button(translations.translate("dialog.apply"), "sketch-copy-apply", () -> {
            try {
                var ids = new LinkedHashSet<String>();
                for (var ref : selection) {
                    if (!ref.equals(ORIGIN) && !FaceProjection.reference(ref.entityId())) ids.add(ref.entityId());
                }
                var next = operation.apply(new SketchCopies(controller.displayed(), ids, () -> id("copy")));
                tool(Draw.SELECT);
                edit(next);
            } catch (IllegalArgumentException failure) {
                error.setText(translations.tryTranslate(failure.getMessage()).orElse(failure.getMessage()));
            }
        }));
    }

    private void fillet(FormulaField radius) {
        if (selection.size() != 2
                || selection.stream()
                        .anyMatch(r -> r.part() != Part.OBJECT || FaceProjection.reference(r.entityId()))) {
            error.setText(translations.translate("help.sketch-fillet"));
            return;
        }
        try {
            String arc = "fillet-" + uuidGenerator.next();
            var next = SketchFillet.round(
                    controller.displayed(),
                    arc,
                    selection.getFirst(),
                    selection.getLast(),
                    value(radius, translations.translate("gui.radius")));
            var parameters = controller.parameters();
            if (!Expressions.isNumber(radius.getText())) {
                parameters = parameters.bind(
                        new ParameterSet.Target(featureId, "constraint:" + arc + "-radius"), radius.getText());
                parameters.validate();
            }
            error.setText("");
            selection.clear();
            selection.add(next.entity(arc).ref());
            controller.edit(next, parameters);
        } catch (IllegalArgumentException invalid) {
            error.setText(translations.tryTranslate(invalid.getMessage()).orElse(invalid.getMessage()));
        }
    }

    private void useProfile() {
        try {
            var ids = selection.stream()
                    .filter(r -> r.part() == Part.OBJECT)
                    .map(Ref::entityId)
                    .distinct()
                    .toList();
            if (ids.isEmpty()) {
                throw new IllegalArgumentException(
                        translations.translate("gui.select_the_boundary_curves_including_any_holes"));
            }
            var next = controller.definition().withBoundary(ids);
            SketchProfiles.profile(next);
            edit(next);
        } catch (RuntimeException failure) {
            error.setText(failure.getMessage());
        }
    }

    private void deleteGeometry() {
        var g = controller.definition();
        for (String id : selection.stream().map(Ref::entityId).distinct().toList()) {
            if (!id.equals("origin")
                    && !FaceProjection.reference(id)
                    && g.entities().stream().anyMatch(e -> e.id().equals(id))) {
                g = Sketches.removeEntity(g, id);
            }
        }
        selection.clear();
        edit(g);
    }

    private void controllerUndo(boolean forward) {
        tool(Draw.SELECT);
        selection.clear();
        if (forward) controller.redo();
        else {
            controller.undo();
        }
    }

    private void parameters() {
        var features = new ArrayList<>(documentFeatures);
        features.removeIf(f -> f.id().equals(featureId));
        features.add(new Feature.Sketch(
                featureId,
                original == null ? "profile" : original.profileId(),
                selectedPlane,
                new Feature.Constrained(controller.definition())));
        ParameterDialog.show(getScene().getWindow(), features, controller.parameters(), translations)
                .ifPresent(p -> controller.edit(controller.definition(), p));
    }

    private void edit(SketchGeometry next) {
        error.setText("");
        dimensionValue.getStyleClass().remove("invalid");
        controller.edit(next);
    }

    private void refresh() {
        if (closed) return;
        refreshing = true;
        try {
            var prior = constraints.getSelectionModel().getSelectedItem();
            constraints
                    .getItems()
                    .setAll(controller.definition().constraints().stream()
                            .filter(c -> !SketchCopies.linked(c))
                            .toList());
            if (prior != null) {
                constraints.getItems().stream()
                        .filter(c -> c.id().equals(prior.id()))
                        .findFirst()
                        .ifPresent(c -> constraints.getSelectionModel().select(c));
            }
            var refs = new ArrayList<Ref>();
            refs.add(ORIGIN);
            for (var e : visibleEntities(controller.definition())) {
                refs.add(e.ref());
                refs.addAll(e.pointRefs());
            }
            var selectedRef = references.getValue();
            references.getItems().setAll(refs);
            references.setValue(refs.contains(selectedRef) ? selectedRef : ORIGIN);
            selection.removeIf(r -> !refs.contains(r));
            refreshDimensionValue();
            undo.setDisable(!controller.canUndo());
            redo.setDisable(!controller.canRedo());
            finish.setDisable(!controller.canFinish()
                    || controller.definition().entities().isEmpty());
            status.setText(solverStatus());
            profileWarning.setVisible(!controller.extrusionIssue().isEmpty());
            profileWarning.setText(translations.translate("action.extrude") + ": " + controller.extrusionIssue());
            if (!controller.busy() && !controller.interactionError().isEmpty()) {
                error.setText(controller.interactionError());
            } else if (!controller.busy()
                    && controller.result() != null
                    && !controller.result().solved()) {
                error.setText(controller.result().problems().stream()
                        .map(SketchSolver.Problem::message)
                        .reduce((a, b) -> a + "\n" + b)
                        .orElse(translations.translate("gui.constraint_conflict")));
            }
            selected.setText(
                    selection.isEmpty()
                            ? translations.translate("gui.nothing_selected")
                            : selection.stream()
                                    .map(Ref::toString)
                                    .reduce((a, b) -> a + "\n" + b)
                                    .orElse(""));
            draw();
        } finally {
            refreshing = false;
        }
    }

    private void finish() {
        if (!controller.canFinish()) return;
        try {
            var result = new Feature.Sketch(
                    featureId,
                    original == null ? "profile-" + uuidGenerator.next() : original.profileId(),
                    selectedPlane,
                    new Feature.Constrained(controller.definition()));
            accept.accept(result, controller.parameters());
            dismiss.run();
        } catch (RuntimeException failure) {
            error.setText(failure.getMessage());
        }
    }

    private String id(String prefix) {
        String id;
        do {
            id = prefix + "-" + nextId++;
        } while (hasId(id));
        return id;
    }

    private boolean hasId(String id) {
        return controller.definition().entities().stream().anyMatch(e -> e.id().equals(id))
                || controller.definition().constraints().stream().anyMatch(c -> c.id().equals(id));
    }

    private HBox viewGizmo() {
        var axes = gizmoAxes.getGraphicsContext2D();
        axes.setLineWidth(2);
        axes.setStroke(Color.web("#ce6663"));
        axes.strokeLine(24, 24, 24, 43);
        axes.setFill(Color.web("#ce6663"));
        axes.fillText("X", 29, 46);
        axes.setStroke(Color.web("#52a287"));
        axes.strokeLine(24, 24, 43, 24);
        axes.setFill(Color.web("#52a287"));
        axes.fillText("Y", 39, 20);
        var controls = new HBox(2, turnButton(false), gizmoAxes, turnButton(true));
        controls.setId("sketch-view-gizmo");
        controls.setAlignment(Pos.CENTER);
        controls.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        controls.setStyle(
                "-fx-background-color: -fx-control-inner-background; -fx-background-radius: 5; -fx-padding: 3;");
        return controls;
    }

    private Button turnButton(boolean clockwise) {
        var id = clockwise ? "sketch-view-clockwise" : "sketch-view-anticlockwise";
        var button = new Button(clockwise ? "↻" : "↺");
        button.setId(id);
        button.setStyle("-fx-font-size: 18px; -fx-padding: 2 6;");
        String description = translations.translate("help." + id);
        button.setAccessibleText(description);
        UiHelp.describe(button, description);
        button.setOnAction(event -> {
            var center = viewBasis.unproject(centerX, centerY);
            viewBasis = viewBasis.quarterTurn(clockwise);
            var projected = viewBasis.project(center);
            centerX = projected.x();
            centerY = projected.y();
            gizmoAxes.setRotate(viewBasis.clockwiseDegrees());
            constraints.refresh();
            hoverSnap = null;
            pointer = null;
            positionSizeEntry();
            draw();
            canvas.requestFocus();
        });
        return button;
    }

    private void fit(boolean centerOnOrigin) {
        var points = visibleEntities(controller.displayed()).stream()
                .flatMap(e -> displayCurves(controller.displayed(), e).stream())
                .flatMap(e -> e.sample().stream())
                .map(viewBasis::project)
                .toList();
        if (points.isEmpty()) {

            centerX = 0;
            centerY = 0;
            scale = Math.max(
                    0.15,
                    Math.min(
                            canvas.getWidth() / span(viewBasis.right(), 150, 120),
                            canvas.getHeight() / span(viewBasis.up(), 150, 120)));
            draw();
            return;
        }
        double minX = points.stream().mapToDouble(Vec::x).min().orElse(0),
                maxX = points.stream().mapToDouble(Vec::x).max().orElse(60);
        double minY = points.stream().mapToDouble(Vec::y).min().orElse(0),
                maxY = points.stream().mapToDouble(Vec::y).max().orElse(40);
        if (centerOnOrigin) {
            maxX = Math.max(Math.abs(minX), Math.abs(maxX));
            maxY = Math.max(Math.abs(minY), Math.abs(maxY));
            minX = -maxX;
            minY = -maxY;
        }
        centerX = minX / 2 + maxX / 2;
        centerY = minY / 2 + maxY / 2;
        scale = Math.clamp(
                Math.min(canvas.getWidth() / (maxX - minX + 20), canvas.getHeight() / (maxY - minY + 20)), 0.15, 40);
        draw();
    }

    Point2D modelToScreen(Vec point) {
        return canvas.localToScreen(px(point), py(point));
    }

    SketchController controller() {
        return controller;
    }

    private Vec model(double x, double y) {
        return viewBasis.unproject(
                centerX + (x - canvas.getWidth() / 2) / scale, centerY - (y - canvas.getHeight() / 2) / scale);
    }

    private double px(Vec point) {
        return canvas.getWidth() / 2 + (viewBasis.right().dot(point) - centerX) * scale;
    }

    private double py(Vec point) {
        return canvas.getHeight() / 2 - (viewBasis.up().dot(point) - centerY) * scale;
    }

    private Hit hitPoint(double x, double y, double radius) {
        Hit result = null;
        double distance = radius;
        for (var e : visibleEntities(controller.displayed())) {
            for (var ref : e.pointRefs()) {
                var p = displayPoint(controller.displayed(), e, ref);
                double d = Math.hypot(px(p) - x, py(p) - y);
                if (d < distance || (result != null && d == distance && tool == Draw.SELECT && e.kind() == Kind.TEXT)) {
                    distance = d;
                    result = new Hit(ref, p);
                }
            }
        }
        if (Math.hypot(px(new Vec(0, 0)) - x, py(new Vec(0, 0)) - y) < distance) return new Hit(ORIGIN, new Vec(0, 0));
        return result;
    }

    private Entity selectedTextAnchor(SketchGeometry geometry) {
        if (tool != Draw.SELECT || dimensionTool || activeConstraint != null || selection.size() != 1) return null;
        var point = selection.getFirst();
        if (point.part() != Part.POINT) return null;
        for (var entity : geometry.entities()) {
            if (entity.kind() == Kind.TEXT && entity.id().equals(point.entityId())) return entity;
        }
        return null;
    }

    private boolean rotationLocked(Entity text) {
        return textConstraint(text, Type.FIX).isPresent()
                || textConstraint(text, Type.ANGLE)
                        .map(c -> controller
                                .parameters()
                                .binding(new ParameterSet.Target(featureId, "constraint:" + c.id())))
                        .filter(expression -> !expression.isEmpty())
                        .isPresent();
    }

    private void drawTextRotation(GraphicsContext graphics, SketchGeometry geometry, Entity text) {
        var anchor = displayPoint(geometry, text, text.ref(Part.POINT));
        double x = px(anchor), y = py(anchor), r = TEXT_ROTATION_RADIUS;
        graphics.save();
        boolean locked = rotationLocked(text);
        var rotationColor = Color.web(dark ? "#80caff" : "#096bb0");
        graphics.setStroke(locked ? Color.GRAY : rotationColor);
        graphics.setLineWidth(2);
        graphics.setLineDashes();
        graphics.strokeOval(x - r, y - r, r * 2, r * 2);
        graphics.strokeLine(x, y - r, x - 6, y - r - 4);
        graphics.strokeLine(x, y - r, x - 6, y - r + 4);
        graphics.strokeLine(x, y + r, x + 6, y + r - 4);
        graphics.strokeLine(x, y + r, x + 6, y + r + 4);
        graphics.restore();
        double degrees = SketchRotationAngles.signed(Measurements.degrees(text.value(3)));
        rotationInput.setDisable(locked);
        if (!rotationInput.isFocused() || rotatingText != null) {
            rotationInput.setText(java.math.BigDecimal.valueOf(degrees)
                    .setScale(4, java.math.RoundingMode.HALF_UP)
                    .stripTrailingZeros()
                    .toPlainString());
        }
        rotationDelta.setManaged(rotatingText != null);
        rotationDelta.setVisible(rotatingText != null);
        if (rotatingText != null) {
            double delta = SketchRotationAngles.signed(degrees - Measurements.degrees(rotatingText.value(3)));
            rotationDelta.setText("Δ " + (delta > 0 ? "+" : "") + pointerNumber.format(delta) + "°");
        }
        rotationEntry.autosize();
        rotationEntry.relocate(
                Math.clamp(
                        x - rotationEntry.getWidth() / 2, 0, Math.max(0, canvas.getWidth() - rotationEntry.getWidth())),
                y + r + 8 + rotationEntry.getHeight() <= canvas.getHeight()
                        ? y + r + 8
                        : Math.max(0, y - r - 8 - rotationEntry.getHeight()));
    }

    private Hit hit(double x, double y) {
        var point = hitPoint(x, y, 7);
        if (point != null) return point;
        return hitCurve(x, y, false);
    }

    private Hit hitCurve(double x, double y, boolean textPath) {
        Hit result = null;
        double best = 6 / scale;
        var p = model(x, y);
        for (var e : visibleEntities(controller.displayed())) {
            if (textPath && (e.kind() == Kind.TEXT || e.kind() == Kind.POINT)) continue;
            for (var curve : displayCurves(controller.displayed(), e)) {
                var samples = curve.sample();
                for (int i = 1; i < samples.size(); i++) {
                    var a = samples.get(i - 1);
                    var d = samples.get(i).minus(a);
                    double t = Math.clamp(p.minus(a).dot(d) / Math.max(1e-20, d.dot(d)), 0, 1);
                    var near = a.plus(d.times(t));
                    double distance = p.distance(near);
                    if (distance < best) {
                        best = distance;
                        result = new Hit(e.ref(), near);
                    }
                }
            }
        }
        return result;
    }

    private DimensionHit hitDimension(double x, double y) {
        for (var hit : dimensionHits.reversed()) {
            var local = viewBasis.annotationOffset(x - hit.x(), y - hit.y());
            if (Math.abs(local.x()) <= hit.width() / 2 + 3 && Math.abs(local.y()) <= 11) return hit;
        }
        if (!dimensionHits.isEmpty() && tool == Draw.SELECT && !dimensionTool && activeConstraint == null) {
            var point = hitPoint(x, y, 7);
            if (point != null
                    && point.ref().part() == Part.POINT
                    && !point.ref().equals(ORIGIN)
                    && !FaceProjection.reference(point.ref().entityId())
                    && controller.displayed().entity(point.ref().entityId()).kind() == Kind.TEXT) return null;
        }
        return hitDimensionLine(x, y);
    }

    private DimensionHit hitDimensionLine(double x, double y) {
        for (var hit : dimensionHits.reversed()) {
            double dx = hit.bx() - hit.ax(), dy = hit.by() - hit.ay();
            double length = dx * dx + dy * dy;
            if (length < 1) continue;
            double t = Math.clamp(((x - hit.ax()) * dx + (y - hit.ay()) * dy) / length, 0, 1);
            if (Math.hypot(x - hit.ax() - t * dx, y - hit.ay() - t * dy) <= 6) return hit;
        }
        return null;
    }

    private void draw() {
        highlightTools();
        updatePointerHint();
        var graphics = canvas.getGraphicsContext2D();
        double w = canvas.getWidth(), h = canvas.getHeight();
        if (w <= 0 || h <= 0 || !Double.isFinite(scale) || scale <= 0) return;
        drawGrid(graphics, w, h);
        if (controller == null) return;
        var g = controller.displayed();
        if (rotatingText != null) g = g.replace(rotatedText(rotatingText, rotationAngle));
        dimensionHits.clear();
        drawEntities(graphics, g);
        drawDimensionPreview(g);
        drawBadges(graphics, g);
        drawSelectionOverlay(graphics);
        drawToolPreview(graphics);
        var selectedText = selectedTextAnchor(g);
        rotationEntry.setVisible(selectedText != null);
        if (selectedText != null) drawTextRotation(graphics, g, selectedText);
    }

    private void drawEntities(GraphicsContext graphics, SketchGeometry g) {
        boolean invalid = controller.result() != null && !controller.result().solved();
        var fixed = controller.result() != null && controller.result().solved() && !controller.busy()
                ? controller.result().fullyConstrainedReferences()
                : Set.<Ref>of();
        Color constrainedColor = Color.web(dark ? "#e2e8f0" : "#000000");
        for (var entity : projection) drawEntity(graphics, g, entity, false, Set.of(), constrainedColor);
        for (var entity : g.entities()) {
            if (projection.isEmpty() || !FaceProjection.reference(entity.id())) {
                drawEntity(graphics, g, entity, invalid, fixed, constrainedColor);
            }
        }
    }

    private void drawDimension(Constraint c, SketchGeometry g) {
        var graphics = canvas.getGraphicsContext2D();
        var endpoints = dimensionEndpoints(c, g);
        var a = endpoints.start();
        var b = endpoints.end();
        var label = draggedDimension != null
                        && draggedDimension.constraint().id().equals(c.id())
                        && dragLabelPosition != null
                ? dragLabelPosition
                : SketchConstraints.labelPosition(c).orElse(a.plus(b).times(0.5).plus(new Vec(0, 20 / scale)));
        double x = px(label), y = py(label);
        String text = dimensionLabel(c, g);
        double width = Math.max(40, text.length() * 8);
        double lineAx = px(a), lineAy = py(a), lineBx = x, lineBy = y;
        graphics.setStroke(Color.web(dark ? "#bacbdd" : "#263746"));
        graphics.setLineWidth(1);
        graphics.setLineDashes();
        if (switch (c.type()) {
            case LENGTH, DISTANCE, DISTANCE_X, DISTANCE_Y, RADIUS, DIAMETER -> true;
            default -> false;
        }) {
            Vec u = b.minus(a);
            if (c.type() == Type.DISTANCE_X) u = new Vec(1, 0);
            if (c.type() == Type.DISTANCE_Y) u = new Vec(0, 1);
            if (u.distance(new Vec(0, 0)) > 1e-10) {
                u = u.unit();
                var start = label.plus(u.times(a.minus(label).dot(u)));
                var end = label.plus(u.times(b.minus(label).dot(u)));
                lineAx = px(start);
                lineAy = py(start);
                lineBx = px(end);
                lineBy = py(end);
                graphics.strokeLine(px(a), py(a), px(start), py(start));
                graphics.strokeLine(px(b), py(b), px(end), py(end));
                graphics.strokeLine(px(start), py(start), px(end), py(end));
                dimensionArrow(px(start), py(start), px(end), py(end));
                dimensionArrow(px(end), py(end), px(start), py(start));
            }
        } else {
            graphics.strokeLine(px(a), py(a), x, y);
        }
        paintDimensionLabel(c, text, x, y, width);
        for (double dx = -width / 2 + 8; dx < width / 2; dx += 16) {
            var offset = viewBasis.screenOffset(dx, 0);
            badges.add(new Badge(c, x + offset.x(), y + offset.y()));
        }
        if (!c.id().equals("dimension-preview")) {
            dimensionHits.add(new DimensionHit(c, label, x, y, width, lineAx, lineAy, lineBx, lineBy));
        }
    }

    private void paintDimensionLabel(Constraint c, String text, double x, double y, double width) {
        var graphics = canvas.getGraphicsContext2D();
        graphics.save();
        graphics.translate(x, y);
        graphics.rotate(viewBasis.clockwiseDegrees());
        graphics.setFill(Color.web(dark ? "#161c23" : "#f8fafc"));
        if (SketchConstraints.referenceDimension(c)) {
            graphics.setFill(Color.web(dark ? "#283b49" : "#e4edf3"));
            graphics.fillRoundRect(-width / 2, -10, width, 20, 8, 8);
        } else {
            graphics.fillRect(-width / 2, -10, width, 20);
        }
        Color labelColor = Color.web(dark ? "#bacbdd" : "#263746");
        graphics.setFill(
                c.equals(constraints.getSelectionModel().getSelectedItem()) ? Color.web("#2688ed") : labelColor);
        graphics.setFont(Font.font(13));
        graphics.fillText(text, -width / 2 + 3, 5);
        graphics.restore();
    }

    private void dimensionArrow(double x, double y, double towardX, double towardY) {
        double angle = Math.atan2(towardY - y, towardX - x);
        var graphics = canvas.getGraphicsContext2D();
        for (double offset : new double[] {-0.4, 0.4}) {
            graphics.strokeLine(x, y, x + 8 * Math.cos(angle + offset), y + 8 * Math.sin(angle + offset));
        }
    }

    private ParameterSet formulaParameters() {
        return controller.parameters();
    }

    private FormulaField numeric(String text, ParameterUnit unit) {
        return FormulaField.number(text, this::formulaParameters, unit, translations);
    }

    private CheckBox mirrorOption(String axis, boolean edit, boolean selectedValue) {
        var box = new CheckBox(translations.translate("sketch.text.mirror." + axis));
        box.setId((edit ? "sketch-edit-text-mirror-" : "sketch-text-mirror-") + axis);
        box.setSelected(selectedValue);
        return box;
    }

    private <T extends Enum<T>> ComboBox<T> alignmentChoice(String id, T[] values, T selectedValue) {
        var choice = new ComboBox<T>();
        choice.setId(id);
        choice.getItems().addAll(Arrays.asList(values));
        choice.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(T item) {
                return item == null
                        ? ""
                        : translations.translate("sketch.text." + item.name().toLowerCase(Locale.ROOT));
            }

            @Override
            public T fromString(String name) {
                throw new UnsupportedOperationException();
            }
        });
        choice.setValue(selectedValue);
        return choice;
    }

    private ParameterUnit dimensionUnit() {
        var selected = constraints.getSelectionModel().getSelectedItem();
        var type = selected != null && selected.type().dimension() ? selected.type() : dimension.getValue();
        return type == null ? ParameterUnit.MM : FeatureDimensions.unit(type);
    }

    private String textExpression(Entity entity, String property, String fallback) {
        String expression = controller
                .parameters()
                .binding(new ParameterSet.Target(featureId, FeatureText.key(entity.id(), property)));
        return expression.isEmpty() ? Expressions.literalText(fallback) : expression;
    }

    private ParameterSet bindText(ParameterSet parameters, String entityId, FormulaField content, FormulaField font) {
        for (var entry : Map.of("content", content, "font", font).entrySet()) {
            String expression = entry.getValue().getText();
            parameters = parameters.bind(
                    new ParameterSet.Target(featureId, FeatureText.key(entityId, entry.getKey())),
                    Expressions.isFormula(expression) ? expression : "");
        }
        return parameters;
    }

    private Button button(String title, String id, Runnable action) {
        var button = new Button(title);
        shortcutActions.put(id, button);
        button.setId(id);
        UiHelp.install(button, translations);
        button.setOnAction(e -> action.run());
        return button;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            quickMenu.close();
            controller.close();
        }
    }

    private void installSizeShortcuts() {
        canvas.addEventHandler(KeyEvent.KEY_TYPED, e -> {
            if (!e.isShortcutDown()
                    && !e.isAltDown()
                    && canEnterSize()
                    && e.getCharacter().matches("[0-9.,]")) {
                e.consume();
                openSizeEntry(e.getCharacter());
            }
        });
        canvas.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.TAB && canEnterSize()) {
                e.consume();
                openSizeEntry("");
            }
        });
    }

    private void installShortcuts(MenuButton menu) {
        addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (isDisabled() || menu.isShowing()) return;
            var focus = getScene().getFocusOwner();
            if (KeyboardShortcuts.textUndo(e, focus)) return;
            if (e.getCode() == KeyCode.ESCAPE) {
                if (dimensionEntry != null) closeDimensionEntry();
                else {
                    cancelDrawing();
                }
                e.consume();
            } else if (e.getCode() == KeyCode.ENTER
                    && !e.isShortcutDown()
                    && !e.isAltDown()
                    && !e.isShiftDown()
                    && tool == Draw.ADVANCED
                    && !(focus instanceof TextInputControl)) {
                finishAdvanced();
                e.consume();
            } else {
                fireSketchShortcut(e, focus);
            }
        });
    }

    private boolean canEnterSize() {
        return sizeEntry == null && (polygonTool() || drawing.size() == 1 && sizeShape() != null);
    }

    private SketchSizeEntry.Shape sizeShape() {
        return switch (tool) {
            case LINE -> SketchSizeEntry.Shape.LINE;
            case CIRCLE -> SketchSizeEntry.Shape.CIRCLE;
            case RECTANGLE -> SketchSizeEntry.Shape.RECTANGLE;
            case ADVANCED ->
                switch (advanced) {
                    case CIRCLE_CENTER -> SketchSizeEntry.Shape.CIRCLE;
                    case RECTANGLE_TWO_POINT -> SketchSizeEntry.Shape.RECTANGLE;
                    case RECTANGLE_CENTER -> SketchSizeEntry.Shape.CENTER_RECTANGLE;
                    default -> null;
                };
            default -> null;
        };
    }

    private void openSizeEntry(String typed) {
        if (polygonTool()) {
            openPolygonSides(typed);
            return;
        }
        var entry = new SketchSizeEntry(
                drawing.getFirst(),
                cursor,
                sizeShape(),
                translations,
                point -> {
                    cursor = point;
                    hoverSnap = null;
                    draw();
                },
                (point, sizes) -> {
                    closeSizeEntry();
                    hoverSnap = null;
                    placeDrawingPoint(point, null, sizes);
                    canvas.requestFocus();
                });
        showSizeEntry(entry);
        entry.begin(typed);
        draw();
    }

    private void openPolygonSides(String typed) {
        var label = new Label(translations.translate("gui.sides"));
        label.setLabelFor(sides);
        var entry = new HBox(5, label, sides);
        entry.setAlignment(Pos.CENTER_LEFT);
        entry.setManaged(false);
        entry.setId("sketch-size-entry");
        sides.setOnAction(event -> {
            if (!acceptPolygonSides()) return;
            closeSizeEntry();
            canvas.requestFocus();
            draw();
        });
        showSizeEntry(entry);
        sides.requestFocus();
        sides.selectAll();
        if (!typed.isEmpty()) sides.replaceSelection(typed);
        draw();
    }

    private boolean acceptPolygonSides() {
        try {
            int count = sides.integer();
            if (count < 3 || count > 32) {
                throw new IllegalArgumentException(translations.translate("gui.sides") + " (3–32)");
            }
            polygonSides = sides.getText();
            previewSides = count;
            error.setText("");
            return true;
        } catch (IllegalArgumentException invalid) {
            error.setText(invalid.getMessage());
            sides.requestFocus();
            sides.selectAll();
            return false;
        }
    }

    private void showSizeEntry(HBox entry) {
        sizeEntry = entry;
        ((StackPane) canvas.getParent()).getChildren().add(sizeEntry);
        sizeEntry.applyCss();
        sizeEntry.autosize();
        positionSizeEntry();
    }

    private void positionSizeEntry() {
        if (sizeEntry == null) return;
        sizeEntry.relocate(
                hintCoordinate(px(cursor), sizeEntry.getWidth(), canvas.getWidth()),
                hintCoordinate(py(cursor), sizeEntry.getHeight(), canvas.getHeight()));
    }

    private void closeSizeEntry() {
        if (sizeEntry == null) return;
        ((StackPane) canvas.getParent()).getChildren().remove(sizeEntry);
        if (sizeEntry.getChildren().remove(sides)) {
            sides.setText(polygonSides);
            sides.setOnAction(null);
        }
        sizeEntry = null;
    }

    private void fireSketchShortcut(KeyEvent e, Node focus) {
        String action = KeyboardShortcuts.match(KeyboardShortcuts.Scope.SKETCH, e, focus);
        if (action != null) {
            KeyboardShortcuts.consume(e, getScene());
            if (action.equals("sketch-remove-constraint")
                    && constraints.getSelectionModel().getSelectedItem() == null
                    && focus != constraints
                    && !selection.isEmpty()) {
                action = "sketch-delete-geometry";
            }
            shortcutActions.get(action).fire();
        }
    }

    private void pressCanvas(MouseEvent e) {
        if (dimensionEntry != null && e.getButton() == MouseButton.PRIMARY) return;
        canvas.requestFocus();
        lastX = pressX = e.getX();
        lastY = pressY = e.getY();
        moving = false;
        panning = e.getButton() == MouseButton.SECONDARY;
        boxCandidate = boxSelecting = false;
        boxAdditive = e.isControlDown() || e.isShiftDown();
        if (e.getButton() == MouseButton.PRIMARY && tool == Draw.SELECT && activeConstraint == null) {
            var geometry = controller.displayed();
            var text = selectedTextAnchor(geometry);
            if (text != null && !boxAdditive) {
                var anchor = displayPoint(geometry, text, text.ref(Part.POINT));
                double distance = Math.hypot(e.getX() - px(anchor), e.getY() - py(anchor));
                if (Math.abs(distance - TEXT_ROTATION_RADIUS) <= 6) {
                    if (!rotationLocked(text)) {
                        rotatingText = text;
                        rotationAngle = text.value(3);
                    }
                    moving = true;
                    e.consume();
                    return;
                }
            }
            draggedDimension = hitDimension(e.getX(), e.getY());
            if (draggedDimension != null) {
                dragLabelPosition = null;
                constraints.getSelectionModel().select(draggedDimension.constraint());
                e.consume();
                return;
            }
        }
        if (e.getButton() == MouseButton.PRIMARY && activeConstraint == null && !dimensionTool) beginPointDrag(e);
        e.consume();
    }

    private void dragCanvas(MouseEvent e) {
        if (panning) {
            centerX -= (e.getX() - lastX) / scale;
            centerY += (e.getY() - lastY) / scale;
            draw();
        } else if (rotatingText != null && e.isPrimaryButtonDown()) {
            updateRotation(e);
            draw();
        } else if (draggedDimension != null && e.isPrimaryButtonDown()) {
            if (Math.hypot(e.getX() - pressX, e.getY() - pressY) > 3) {
                moving = true;
                dragLabelPosition =
                        draggedDimension.label().plus(model(e.getX(), e.getY()).minus(model(pressX, pressY)));
                draw();
            }
        } else if (boxCandidate && e.isPrimaryButtonDown() && Math.hypot(e.getX() - pressX, e.getY() - pressY) > 3) {
            moving = boxSelecting = true;
            selectToolForGesture();
            lastX = e.getX();
            lastY = e.getY();
            draw();
        } else if (dragPoint != null
                && e.isPrimaryButtonDown()
                && Math.hypot(e.getX() - pressX, e.getY() - pressY) > 3) {
            moving = true;
            controller.drag(dragPoint, dragDestination(e), false);
        }
        lastX = e.getX();
        lastY = e.getY();
        e.consume();
    }

    private Vec dragDestination(MouseEvent event) {
        var position = model(event.getX(), event.getY());
        var geometry = controller.definition();
        var entity = geometry.entity(dragPoint.entityId());
        if (entity.kind() == Kind.TEXT && !entity.text().pathId().isEmpty()) {
            return SketchTools.pathCoordinates(geometry.entity(entity.text().pathId()), position);
        }
        return position;
    }

    private void updateRotation(MouseEvent event) {
        var anchor = displayPoint(controller.displayed(), rotatingText, rotatingText.ref(Part.POINT));
        var start = model(pressX, pressY).minus(anchor);
        var current = model(event.getX(), event.getY()).minus(anchor);
        if (current.length() * scale > 2) {
            double delta = Math.atan2(start.cross(current), start.dot(current));
            if (delta != 0 || rotationAngle != rotatingText.value(3)) {
                rotationAngle = Measurements.radians(
                        SketchRotationAngles.snap(Measurements.degrees(rotatingText.value(3) + delta)));
            }
        }
    }

    private void enterRotation() {
        var text = selectedTextAnchor(controller.definition());
        if (text == null || rotationLocked(text)) return;
        try {
            double degrees = value(rotationInput, translations.translate("gui.angle_degrees"));
            commitRotation(text, Measurements.radians(degrees));
            error.setText("");
            canvas.requestFocus();
            draw();
        } catch (IllegalArgumentException failure) {
            error.setText(failure.getMessage());
        }
    }

    private void commitRotation(Entity originalText, double angle) {
        var text = rotatedText(originalText, angle);
        if (text.equals(originalText)) return;
        var geometry = controller.definition().replace(text);
        var constraint = textConstraint(text, Type.ANGLE);
        if (constraint.isPresent()) {
            var dimension = constraint.get();
            geometry = geometry.withConstraints(geometry.constraints().stream()
                    .map(c -> c.equals(dimension) ? SketchConstraints.drivingValue(c, Measurements.degrees(angle)) : c)
                    .toList());
        }
        controller.edit(geometry);
    }

    private void releaseCanvas(MouseEvent e) {
        if (rotatingText != null) {
            updateRotation(e);
            var text = rotatingText;
            rotatingText = null;
            commitRotation(text, rotationAngle);
            draw();
            e.consume();
            return;
        }
        if (draggedDimension != null) {
            if (dragLabelPosition != null) {
                var g = controller.definition();
                controller.edit(g.withConstraints(g.constraints().stream()
                        .map(c -> c.id().equals(draggedDimension.constraint().id())
                                ? SketchConstraints.positionLabel(c, dragLabelPosition)
                                : c)
                        .toList()));
            }
            draggedDimension = null;
            dragLabelPosition = null;
            e.consume();
            return;
        }
        if (boxSelecting) {
            if (!boxAdditive) selection.clear();
            constraints.getSelectionModel().clearSelection();
            selection.addAll(SketchSelection.inBox(
                    controller.displayed(),
                    new Vec(pressX, pressY),
                    new Vec(e.getX(), e.getY()),
                    e.getX() < pressX,
                    p -> new Vec(px(p), py(p))));
            boxSelecting = false;
            selectionChanged();
        }
        boxCandidate = false;
        if (moving && dragPoint != null) controller.drag(dragPoint, dragDestination(e), true);
        dragPoint = null;
        panning = false;
        e.consume();
    }

    private void clickCanvas(MouseEvent e) {
        if (e.getButton() != MouseButton.PRIMARY || !e.isStillSincePress() || moving) return;
        if (dimensionEntry != null) {
            dimensionEntry.getChildren().getFirst().requestFocus();
            return;
        }
        error.setText("");
        if (tool != Draw.SELECT && !e.isControlDown()) {
            drawingClick(e);
            return;
        }
        var annotation = hitDimension(e.getX(), e.getY());
        if (annotation != null) {
            constraints.getSelectionModel().select(annotation.constraint());
            if (e.getClickCount() == 2) editDimensionValue();
            return;
        }
        selectToolForGesture();
        var hit = hit(e.getX(), e.getY());
        if (dimensionTool) {
            selectDimension(e);
            return;
        }
        if (hit == null && selectBadge(e)) return;
        selectGeometry(e, hit);
        e.consume();
    }

    private void selectDimension(MouseEvent e) {
        // Use the same screen-space snap for hover and selection, including endpoints.
        hoverSnap = SketchSnapping.find(visibleEntities(controller.displayed()), model(e.getX(), e.getY()), scale);
        if (hoverSnap != null) {
            constraints.getSelectionModel().clearSelection();
            if (selection.contains(hoverSnap.ref())) selection.remove(hoverSnap.ref());
            else if (selection.size() < 2) {
                selection.add(hoverSnap.ref());
            }
            selectionChanged();
        } else {
            placeDimension(model(e.getX(), e.getY()));
        }
    }

    private boolean selectBadge(MouseEvent e) {
        for (var badge : badges) {
            var local = viewBasis.annotationOffset(e.getX() - badge.x(), e.getY() - badge.y());
            if (Math.abs(local.x()) < 11 && Math.abs(local.y()) < 9) {
                constraints.getSelectionModel().select(badge.constraint());
                if (badge.constraint().type().dimension() && e.getClickCount() == 2) editDimensionValue();
                return true;
            }
        }
        return false;
    }

    private SketchGeometry drawnGeometry(String id, Vec a, Vec b, SketchSizeEntry.Dimensions sizes) {
        SketchGeometry addition;
        if (tool == Draw.RECTANGLE) {
            addition = Sketches.rectangle(
                    id,
                    Math.min(a.x(), b.x()),
                    Math.min(a.y(), b.y()),
                    Math.abs(a.x() - b.x()),
                    Math.abs(a.y() - b.y()),
                    false);
        } else if (tool == Draw.POLYGON) {
            addition = SketchTools.PolygonLayout.fromVertex(a, b).geometry(id, sides.integer());
        } else {
            Entity entity = switch (tool) {
                case POINT -> Sketches.entity(id, Kind.POINT, true, a.x(), a.y());
                case LINE -> Sketches.entity(id, Kind.LINE, construction.isSelected(), a.x(), a.y(), b.x(), b.y());
                case CIRCLE -> Sketches.entity(id, Kind.CIRCLE, construction.isSelected(), a.x(), a.y(), a.distance(b));
                case ARC -> {
                    var start = drawing.get(1).minus(a);
                    var end = b.minus(a);
                    double angle = Math.atan2(start.y(), start.x()), sweep = Math.atan2(end.y(), end.x()) - angle;
                    if (sweep <= 0) sweep += Math.TAU;
                    yield Sketches.entity(
                            id, Kind.ARC, construction.isSelected(), a.x(), a.y(), start.length(), angle, sweep);
                }
                case SPLINE ->
                    Sketches.entity(
                            id,
                            Kind.SPLINE,
                            construction.isSelected(),
                            a.x(),
                            a.y(),
                            drawing.get(1).x(),
                            drawing.get(1).y(),
                            drawing.get(2).x(),
                            drawing.get(2).y(),
                            b.x(),
                            b.y());
                default -> throw new IllegalArgumentException(translations.translate("gui.choose_a_drawing_tool"));
            };
            addition = new SketchGeometry(List.of(entity), List.of(), entity.construction() ? List.of() : List.of(id));
            if (tool == Draw.LINE && auto.isSelected() && (sizes == null || sizes.second() == null)) {
                if (Math.abs(a.y() - b.y()) < 1e-9) {
                    addition = addition.add(SketchConstraints.create(
                            addition, id + "-horizontal", Type.HORIZONTAL, List.of(entity.ref()), 0));
                } else if (Math.abs(a.x() - b.x()) < 1e-9) {
                    addition = addition.add(SketchConstraints.create(
                            addition, id + "-vertical", Type.VERTICAL, List.of(entity.ref()), 0));
                }
            }
        }
        return sizes == null ? addition : sizes.apply(addition, id);
    }

    private String solverStatus() {
        if (controller.busy()) {
            return translations.translate("gui.solving_constraints");
        }
        if (controller.result() == null) {
            return translations.translate("status.ready");
        }
        if (!controller.result().solved()) {
            return translations.translate("solver.failed");
        }
        if (controller.result().degreesOfFreedom() == 0) {
            return translations.translate("solver.full");
        }
        return translations.translate("solver.free", controller.result().degreesOfFreedom());
    }

    private boolean centerPolygonTool() {
        return polygonTool() && !(tool == Draw.ADVANCED && advanced == SketchTools.Tool.POLYGON_EDGE);
    }

    private boolean polygonTool() {
        return tool == Draw.POLYGON
                || tool == Draw.ADVANCED
                        && (advanced == SketchTools.Tool.POLYGON_INSCRIBED
                                || advanced == SketchTools.Tool.POLYGON_CIRCUMSCRIBED
                                || advanced == SketchTools.Tool.POLYGON_EDGE);
    }

    private void highlightTools() {
        for (var candidate : Draw.values()) {
            Node control = candidate == Draw.ADVANCED
                    ? createType
                    : shortcutActions.get("sketch-tool-" + candidate.name().toLowerCase(Locale.ROOT));
            if (control != null) {
                control.pseudoClassStateChanged(
                        ACTIVE_TOOL, tool == candidate && activeConstraint == null && !dimensionTool);
            }
        }
        shortcutActions.get("sketch-dimension").pseudoClassStateChanged(ACTIVE_TOOL, dimensionTool);
    }

    private void drawGrid(GraphicsContext graphics, double w, double h) {
        graphics.setFill(Color.web(dark ? "#161c23" : "#f8fafc"));
        graphics.fillRect(0, 0, w, h);
        graphics.setLineWidth(1);
        graphics.setLineDashes();
        graphics.setStroke(Color.web(dark ? "#2a3541" : "#e1e7ed"));
        double spacing = Math.pow(10, Math.floor(Math.log10(60 / Math.max(scale, 0.15))));
        var center = model(w / 2, h / 2);
        double halfX =
                (Math.abs(viewBasis.right().x()) * w + Math.abs(viewBasis.up().x()) * h) / (2 * scale);
        double halfY =
                (Math.abs(viewBasis.right().y()) * w + Math.abs(viewBasis.up().y()) * h) / (2 * scale);
        double minX = center.x() - halfX, maxX = center.x() + halfX;
        double minY = center.y() - halfY, maxY = center.y() + halfY;
        double firstX = Math.ceil(minX / spacing) * spacing;
        double firstY = Math.ceil(minY / spacing) * spacing;
        for (int i = 0; i < 500 && firstX + i * spacing <= maxX; i++) {
            double x = firstX + i * spacing;
            strokeLine(graphics, new Vec(x, minY), new Vec(x, maxY));
        }
        for (int i = 0; i < 500 && firstY + i * spacing <= maxY; i++) {
            double y = firstY + i * spacing;
            strokeLine(graphics, new Vec(minX, y), new Vec(maxX, y));
        }
        graphics.setStroke(Color.web("#ce6663"));
        drawAxis(graphics, new Vec(1, 0), minX, maxX);
        graphics.setStroke(Color.web("#52a287"));
        drawAxis(graphics, new Vec(0, 1), minY, maxY);
        graphics.setFill(Color.web("#a6b3bf"));
        graphics.fillOval(px(new Vec(0, 0)) - 3, py(new Vec(0, 0)) - 3, 6, 6);
    }

    private void drawAxis(GraphicsContext graphics, Vec direction, double min, double max) {
        if (min < 0) {
            graphics.setLineDashes(7, 5);
            strokeLine(graphics, direction.times(Math.min(0, max)), direction.times(min));
            graphics.setLineDashes();
        }
        if (max > 0) strokeLine(graphics, direction.times(Math.max(0, min)), direction.times(max));
    }

    private void strokeLine(GraphicsContext graphics, Vec a, Vec b) {
        graphics.strokeLine(px(a), py(a), px(b), py(b));
    }

    private void drawEntity(
            GraphicsContext graphics,
            SketchGeometry g,
            Entity entity,
            boolean invalid,
            Set<Ref> fixed,
            Color constrainedColor) {
        boolean chosen = selection.stream().anyMatch(r -> r.entityId().equals(entity.id()));
        boolean hovered = dimensionTool && hoverSnap != null && hoverSnap.ref().equals(entity.ref());
        Color color = entityColor(hovered, chosen, invalid, fixed.contains(entity.ref()), constrainedColor);
        boolean projected = FaceProjection.reference(entity.id());
        if (projected && !chosen && !hovered) color = Color.web(dark ? "#cba5ee" : "#8355a8");
        graphics.setStroke(color);
        graphics.setLineWidth(chosen || hovered ? 2.5 : 1.5);
        setSketchStroke(graphics, entity.construction() && !projected);
        for (var curve : displayCurves(g, entity)) {
            var points = curve.sample();
            graphics.beginPath();
            graphics.moveTo(px(points.getFirst()), py(points.getFirst()));
            for (var p : points) graphics.lineTo(px(p), py(p));
            graphics.stroke();
        }
        graphics.setLineDashes();
        drawControlPolygon(graphics, entity);
        drawEntityPoints(graphics, g, entity, chosen, invalid, fixed, constrainedColor, color);
    }

    private Color entityColor(boolean hovered, boolean chosen, boolean invalid, boolean fixed, Color constrainedColor) {
        if (hovered) return Color.web("#2688ed");
        if (chosen) return Color.web("#e6a239");
        if (invalid) return Color.web("#db6860");
        if (fixed) return constrainedColor;
        return Color.web(dark ? "#7fbcdd" : "#286d98");
    }

    private void drawControlPolygon(GraphicsContext graphics, Entity entity) {
        if (entity.kind() == Kind.SPLINE) {
            graphics.setLineWidth(1);
            graphics.setStroke(Color.web(dark ? "#647587" : "#abb7c2"));
            graphics.setLineDashes(3, 4);
            var p0 = entity.at(0);
            var p1 = entity.point(Part.CONTROL1);
            var p2 = entity.point(Part.CONTROL2);
            var p3 = entity.at(1);
            graphics.strokeLine(px(p0), py(p0), px(p1), py(p1));
            graphics.strokeLine(px(p2), py(p2), px(p3), py(p3));
            graphics.setLineDashes();
        }
        if (entity.kind() == Kind.CONTROL_SPLINE || entity.kind() == Kind.CONIC) {
            graphics.setStroke(Color.web(dark ? "#647587" : "#abb7c2"));
            graphics.setLineDashes(3, 4);
            var controls = entity.kind() == Kind.CONTROL_SPLINE
                    ? entity.pointRefs().stream()
                            .filter(r -> r.part() == Part.CONTROL)
                            .map(entity::point)
                            .toList()
                    : List.of(entity.at(0), entity.point(Part.CONTROL1), entity.at(1));
            for (int i = 1; i < controls.size(); i++) {
                graphics.strokeLine(
                        px(controls.get(i - 1)), py(controls.get(i - 1)), px(controls.get(i)), py(controls.get(i)));
            }
            graphics.setLineDashes();
        }
    }

    private void drawEntityPoints(
            GraphicsContext graphics,
            SketchGeometry geometry,
            Entity entity,
            boolean chosen,
            boolean invalid,
            Set<Ref> fixed,
            Color constrainedColor,
            Color color) {
        for (var ref : entity.pointRefs()) {
            var p = displayPoint(geometry, entity, ref);
            boolean hoveredPoint =
                    dimensionTool && hoverSnap != null && hoverSnap.ref().equals(ref);
            if (hoveredPoint) {
                graphics.setFill(Color.web("#2688ed"));
            } else if (selection.contains(ref)) {
                graphics.setFill(Color.web("#e6a239"));
            } else {
                graphics.setFill(!chosen && !invalid && fixed.contains(ref) ? constrainedColor : color);
            }
            double radius = hoveredPoint ? 5 : 3.5;
            graphics.fillOval(px(p) - radius, py(p) - radius, radius * 2, radius * 2);
        }
    }

    private void drawDimensionPreview(SketchGeometry g) {
        positionDimensionEntry();
        if (dimensionEntry != null && constraints.getSelectionModel().getSelectedItem() == null) {
            try {
                g = withReferences(g, selection);
                var measurement = SketchDimensions.measure(g, List.copyOf(selection), dimensionEntryPosition);
                var preview = SketchConstraints.create(
                        g,
                        "dimension-preview",
                        measurement.type(),
                        SketchDimensions.drivingReferences(g, List.copyOf(selection), measurement.type()),
                        measurement.value());
                drawDimension(SketchConstraints.positionLabel(preview, dimensionEntryPosition), g);
            } catch (IllegalArgumentException invalidSelection) {
                // A selection may be removed while an inline edit is open.
            }
        }
    }

    private void drawBadges(GraphicsContext graphics, SketchGeometry g) {
        badges.clear();
        if (showBadges.isSelected()) {
            graphics.setFont(Font.font(11));
            int index = 0;
            for (var c : g.constraints()) {
                if (c.refs().isEmpty() || SketchCopies.linked(c)) continue;
                try {
                    if (c.type().dimension()) {
                        drawDimension(c, g);
                        continue;
                    }
                    var ref = c.refs().getFirst();
                    var p = ref.part() == Part.OBJECT ? g.entity(ref.entityId()).at(0.5) : g.point(ref);
                    var offset = viewBasis.screenOffset(14 + (index % 3) * 18, 14 + (index / 3 % 2) * 18);
                    double x = px(p) + offset.x(), y = py(p) + offset.y();
                    index++;
                    badges.add(new Badge(c, x, y));
                    graphics.save();
                    graphics.translate(x, y);
                    graphics.rotate(viewBasis.clockwiseDegrees());
                    graphics.setFill(Color.web(dark ? "#28323e" : "#ffffff"));
                    graphics.fillRoundRect(-9, -8, 18, 16, 4, 4);
                    graphics.setFill(Color.web(
                            c.equals(constraints.getSelectionModel().getSelectedItem()) ? "#e6a239" : "#d15d55"));
                    graphics.fillText(symbol(SketchViewBasis.DEFAULT.mapAxisConstraint(c.type())), -6, 4, 14);
                    graphics.restore();
                } catch (IllegalArgumentException broken) {
                    /* Broken references remain removable in the constraint list. */
                }
            }
        }
    }

    private void drawSelectionOverlay(GraphicsContext graphics) {
        if (boxSelecting) {
            var color = Color.web(lastX < pressX ? "#42b883" : "#438de0");
            graphics.setFill(color.deriveColor(0, 1, 1, 0.15));
            graphics.setStroke(color);
            graphics.setLineWidth(1);
            graphics.setLineDashes(lastX < pressX ? new double[] {5, 4} : new double[] {});
            double x = Math.min(pressX, lastX), y = Math.min(pressY, lastY);
            graphics.fillRect(x, y, Math.abs(lastX - pressX), Math.abs(lastY - pressY));
            graphics.strokeRect(x, y, Math.abs(lastX - pressX), Math.abs(lastY - pressY));
            graphics.setLineDashes();
        }
        if (hoverSnap != null && (dimensionTool || auto.isSelected() && tool != Draw.SELECT)) {
            graphics.setStroke(Color.web(dimensionTool ? "#2688ed" : "#e6a239"));
            graphics.setLineWidth(2);
            var target = hoverSnap.position();
            graphics.strokeOval(px(target) - 6, py(target) - 6, 12, 12);
            if (dimensionTool && hoverSnap.ref().part() != Part.OBJECT) {
                // Coincident endpoints may be drawn by several entities; keep the target on top.
                graphics.setFill(Color.web("#2688ed"));
                graphics.fillOval(px(target) - 4, py(target) - 4, 8, 8);
            }
        }
    }

    private void updatePointerHint() {
        boolean active =
                (tool != Draw.SELECT || dimensionTool) && dimensionEntry == null && sizeEntry == null && !panning;
        canvas.setCursor(active ? javafx.scene.Cursor.CROSSHAIR : javafx.scene.Cursor.DEFAULT);
        pointerHint.setVisible(active && pointer != null);
        if (!pointerHint.isVisible()) return;
        String count = polygonTool() ? " · " + translations.translate("gui.sides") + " " + polygonSides : "";
        pointerHint.setText(pointerInstruction() + count + (canEnterSize() ? "  ·  ⌨ Tab" : ""));
        pointerHint.autosize();
        pointerHint.relocate(
                hintCoordinate(pointer.getX(), pointerHint.getWidth(), canvas.getWidth()),
                hintCoordinate(pointer.getY(), pointerHint.getHeight(), canvas.getHeight()));
    }

    private String pointerInstruction() {
        if (dimensionTool) {
            if (selection.isEmpty()) return translations.translate("pointer.selectGeometry");
            if (selection.size() == 1 && selection.getFirst().part() != Part.OBJECT) {
                return translations.translate("pointer.second");
            }
            return translations.translate("pointer.placeDimension");
        }
        boolean first = drawing.isEmpty();
        return switch (tool) {
            case POINT -> translations.translate("gui.click_to_place_a_sketch_point");
            case CIRCLE -> circleInstruction(first);
            case RECTANGLE -> rectangleInstruction(first);
            case LINE -> first ? translations.translate("pointer.start") : lineInstruction();
            case POLYGON -> translations.translate(first ? "pointer.center" : "pointer.vertex");
            case ADVANCED -> advancedInstruction(first);
            case ARC, SPLINE -> hint.getText();
            case SELECT -> "";
        };
    }

    private String circleInstruction(boolean first) {
        return first ? translations.translate("pointer.center") : lengthInstruction("gui.choose_circle_radius");
    }

    private String lineInstruction() {
        double degrees = SketchSizeEntry.angleDegrees(cursor.minus(drawing.getFirst()));
        return lengthInstruction("constraint.LENGTH") + " · " + translations.translate("constraint.ANGLE") + " "
                + pointerNumber.format(degrees) + "°";
    }

    private String lengthInstruction(String label) {
        return translations.translate(label) + " · "
                + pointerNumber.format(drawing.getFirst().distance(cursor)) + " mm";
    }

    private String rectangleInstruction(boolean first) {
        if (first) return translations.translate("pointer.corner");
        var delta = cursor.minus(drawing.getFirst());
        return translations.translate("gui.choose_opposite_corner") + " · " + pointerNumber.format(Math.abs(delta.x()))
                + " × " + pointerNumber.format(Math.abs(delta.y())) + " mm";
    }

    private String advancedInstruction(boolean first) {
        return switch (advanced) {
            case POLYGON_INSCRIBED, POLYGON_CIRCUMSCRIBED ->
                translations.translate(first ? "pointer.center" : "pointer.vertex");
            case CIRCLE_CENTER -> circleInstruction(first);
            case RECTANGLE_TWO_POINT -> rectangleInstruction(first);
            default ->
                translations.translate("tool." + advanced.name())
                        + (advanced.clicks() > 0
                                ? " · " + Math.min(drawing.size() + 1, advanced.clicks()) + "/" + advanced.clicks()
                                : "");
        };
    }

    private void drawToolPreview(GraphicsContext graphics) {
        if (!drawing.isEmpty()) {
            graphics.setStroke(Color.web("#e6a239"));
            graphics.setLineWidth(1.5);
            setSketchStroke(graphics, construction.isSelected());
            var a = drawing.getFirst();
            var shape = sizeShape();
            boolean circle = shape == SketchSizeEntry.Shape.CIRCLE;
            boolean centered = shape == SketchSizeEntry.Shape.CENTER_RECTANGLE;
            if (centered || shape == SketchSizeEntry.Shape.RECTANGLE) {
                if (centered) a = a.times(2).minus(cursor);
                var b = new Vec(cursor.x(), a.y());
                var d = new Vec(a.x(), cursor.y());
                graphics.strokePolygon(
                        new double[] {px(a), px(b), px(cursor), px(d)},
                        new double[] {py(a), py(b), py(cursor), py(d)},
                        4);
            } else if (polygonTool()) {
                drawPolygonPreview(graphics, a);
            } else if (circle) {
                double r = a.distance(cursor) * scale;
                graphics.strokeOval(px(a) - r, py(a) - r, 2 * r, 2 * r);
                graphics.strokeLine(px(a), py(a), px(cursor), py(cursor));
            } else {
                graphics.beginPath();
                graphics.moveTo(px(a), py(a));
                for (var p : drawing) graphics.lineTo(px(p), py(p));
                graphics.lineTo(px(cursor), py(cursor));
                graphics.stroke();
            }
            graphics.setLineDashes();
        }
    }

    private void drawPolygonPreview(GraphicsContext graphics, Vec start) {
        boolean edge = tool == Draw.ADVANCED && advanced == SketchTools.Tool.POLYGON_EDGE;
        var end = edge && drawing.size() > 1 ? drawing.get(1) : cursor;
        double distance = start.distance(end);
        if (distance < 1e-6) return;
        var layout = edge
                ? SketchTools.polygonLayout(advanced, start, end, cursor, previewSides)
                : SketchTools.PolygonLayout.fromVertex(start, end);
        double r = scale * layout.guideRadius(tool == Draw.POLYGON ? polygonMode.getValue() : advanced, previewSides);
        setSketchStroke(graphics, true);
        graphics.strokeOval(px(layout.center()) - r, py(layout.center()) - r, 2 * r, 2 * r);
        setSketchStroke(graphics, construction.isSelected());
        graphics.beginPath();
        var first = layout.vertex(0, previewSides);
        graphics.moveTo(px(first), py(first));
        for (int i = 1; i < previewSides; i++) {
            var vertex = layout.vertex(i, previewSides);
            graphics.lineTo(px(vertex), py(vertex));
        }
        graphics.closePath();
        graphics.stroke();
        if (!edge) {
            graphics.setFill(graphics.getStroke());
            graphics.fillOval(px(first) - 3, py(first) - 3, 6, 6);
        }
    }

    private double dimensionValue(Constraint c, SketchGeometry g) {
        return SketchConstraints.referenceDimension(c)
                ? SketchConstraints.measuredValue(g, c)
                : c.values().getFirst();
    }

    private String dimensionLabel(Constraint c, SketchGeometry g) {
        String prefix = switch (c.type()) {
            case DIAMETER -> "Ø";
            case RADIUS -> "R";
            default -> "";
        };
        String expression = controller.parameters().binding(new ParameterSet.Target(featureId, "constraint:" + c.id()));
        String text = (expression.isEmpty() ? "" : "fx: ")
                + prefix
                + String.format(Locale.ROOT, "%.2f", dimensionValue(c, g))
                + (c.type() == Type.ANGLE ? "°" : "");
        return SketchConstraints.referenceDimension(c) ? "(" + text + ")" : text;
    }

    private SketchGeometry addSnapConstraints(SketchGeometry next, SketchGeometry addition, String id) {
        if (auto.isSelected() && addition.entities().size() == 1) {
            var entity = addition.entities().getFirst();
            for (int i = 0; i < snaps.size(); i++) {
                if (snaps.get(i) == null) continue;
                Part part = switch (tool) {
                    case POINT -> Part.POINT;
                    case LINE -> i == 0 ? Part.START : Part.END;
                    case CIRCLE -> i == 0 ? Part.CENTER : null;
                    case ARC ->
                        switch (i) {
                            case 0 -> Part.CENTER;
                            case 1 -> Part.START;
                            default -> Part.END;
                        };
                    case SPLINE -> new Part[] {Part.START, Part.CONTROL1, Part.CONTROL2, Part.END}[i];
                    default -> null;
                };
                if (part != null) {
                    next = next.add(SketchConstraints.create(
                            next, id + "-snap-" + i, Type.COINCIDENT, List.of(entity.ref(part), snaps.get(i)), 0));
                }
            }
        }
        return next;
    }

    private DimensionEndpoints dimensionEndpoints(Constraint c, SketchGeometry g) {
        if (c.type() == Type.DISTANCE) {
            var pointLine = SketchConstraints.pointLineDistance(g, c.refs());
            if (pointLine.isPresent()) {
                var measurement = pointLine.get();
                return new DimensionEndpoints(measurement.point(), measurement.foot());
            }
        }
        return curveOrPointEndpoints(c, g);
    }

    private DimensionEndpoints curveOrPointEndpoints(Constraint c, SketchGeometry g) {
        var ref = c.refs().getFirst();
        var entity = ref.part() == Part.OBJECT ? g.entity(ref.entityId()) : null;
        Vec a = entity == null ? g.point(ref) : entity.at(0);
        Vec b;
        if (c.refs().size() == 2) {
            b = (c.refs().get(1).part() == Part.OBJECT
                    ? g.entity(c.refs().get(1).entityId()).at(0.5)
                    : g.point(c.refs().get(1)));
        } else {
            if (entity == null) {
                b = a;
            } else {
                b = entity.at(1);
            }
        }
        if (c.type() == Type.RADIUS || c.type() == Type.DIAMETER) {
            a = entity.point(Part.CENTER);
            b = a.plus(new Vec(entity.value(2), 0));
            if (c.type() == Type.DIAMETER) a = a.minus(new Vec(entity.value(2), 0));
        }
        if (c.type() == Type.DISTANCE && c.refs().stream().allMatch(r -> r.part() == Part.OBJECT)) {
            var other = g.entity(c.refs().getLast().entityId());
            var line = entity.kind() == Kind.LINE ? entity : other;
            var target = line == entity ? other : entity;
            var direction = line.at(1).minus(line.at(0)).unit();
            if (target.kind() == Kind.CIRCLE) b = target.point(Part.CENTER);
            a = line.at(0).plus(direction.times(b.minus(line.at(0)).dot(direction)));
            if (target.kind() == Kind.CIRCLE) b = b.plus(a.minus(b).unit().times(target.value(2)));
        }
        return new DimensionEndpoints(a, b);
    }

    private void beginPointDrag(MouseEvent e) {
        var hit = hit(e.getX(), e.getY());
        boxCandidate = hit == null || boxAdditive || tool != Draw.SELECT;
        dragPoint = tool == Draw.SELECT
                        && hit != null
                        && hit.ref().part() != Part.OBJECT
                        && !hit.ref().equals(ORIGIN)
                        && !FaceProjection.reference(hit.ref().entityId())
                        && !boxAdditive
                ? hit.ref()
                : null;
    }

    private List<Entity> visibleEntities(SketchGeometry g) {
        if (projection.isEmpty()) return g.entities();
        var result = new ArrayList<Entity>();
        g.entities().stream().filter(e -> !FaceProjection.reference(e.id())).forEach(result::add);
        result.addAll(projection);
        return result;
    }

    private SketchGeometry withReferences(SketchGeometry g, Collection<Ref> refs) {
        if (projection.isEmpty()) return g;
        var needed = new HashSet<String>();
        for (var ref : refs) {
            if (ref != null && FaceProjection.reference(ref.entityId())) needed.add(ref.entityId());
        }
        g.entities().forEach(e -> needed.remove(e.id()));
        if (needed.isEmpty()) return g;
        var entities = new ArrayList<>(g.entities());
        projection.stream().filter(e -> needed.contains(e.id())).forEach(entities::add);
        return g.withEntities(entities);
    }

    private void selectGeometry(MouseEvent e, Hit hit) {
        if (hit != null && e.getClickCount() == 2 && activeConstraint == null) {
            var text = editableText(hit.ref());
            if (text.isPresent()) {
                editText(text.get());
                return;
            }
        }
        if (!e.isShiftDown() && !e.isControlDown() && activeConstraint == null) selection.clear();
        constraints.getSelectionModel().clearSelection();
        if (hit != null) {
            if ((e.isShiftDown() || e.isControlDown()) && selection.contains(hit.ref())) {
                selection.remove(hit.ref());
            } else {
                selection.add(hit.ref());
            }
        }
        selectionChanged();
    }
}
