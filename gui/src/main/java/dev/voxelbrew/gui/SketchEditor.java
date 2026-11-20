package dev.voxelbrew.gui;

import static dev.voxelbrew.api.model.sketch.SketchGeometry.*;

import dev.voxelbrew.api.i18n.TranslationService;
import dev.voxelbrew.api.model.Feature;
import dev.voxelbrew.api.model.parameters.*;
import dev.voxelbrew.api.model.sketch.*;
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

    private final SketchController controller;

    private final dev.voxelbrew.api.model.UuidGenerator uuidGenerator;

    private final TranslationService translations;

    private final Canvas canvas = new Canvas();

    private final LinkedHashSet<Ref> selection = new LinkedHashSet<>();

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
            textSpacing,
            pointX,
            pointY;

    private final ComboBox<Integer> splineDegree = new ComboBox<>();

    private final ComboBox<String> textStyle = new ComboBox<>();

    private final CheckBox closedCurve = new CheckBox("");

    private final FormulaField dimensionValue, sides;
    private final CheckBox construction = new CheckBox(""), auto = new CheckBox(""), showBadges = new CheckBox("");
    private final Label selected = new Label(""), status = new Label(""), error = new Label(), hint = new Label();
    private final Button finish, undo, redo;

    private final Map<String, Button> shortcutActions = new LinkedHashMap<>();

    private final java.util.function.BiConsumer<Feature.Sketch, ParameterSet> accept;

    private final Feature.Sketch original;

    private final String featureId;

    private final List<Feature> documentFeatures;

    private final Map<String, Vec> dimensionPositions = new HashMap<>();

    private final List<DimensionHit> dimensionHits = new ArrayList<>();

    private final QuickMenu quickMenu;

    private final boolean dark;

    private final Feature.Plane selectedPlane;

    private SketchSnapping.Snap hoverSnap;

    private SketchTools.Tool advanced;

    private Runnable dismiss = () -> {};

    private Draw tool = Draw.SELECT;

    private Type activeConstraint;

    private boolean dimensionTool;

    private HBox dimensionEntry;

    private Vec dimensionEntryPosition;

    private DimensionHit draggedDimension;

    private Vec previousDimensionPosition;

    private Ref dragPoint;

    private boolean boxCandidate, boxSelecting, boxAdditive;

    private Vec cursor = new Vec(0, 0);

    private double centerX = 30, centerY = 20, scale = 4, lastX, lastY, pressX, pressY;
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
            editor.fit();
            editor.canvas.requestFocus();
        });
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
            List<Feature> features,
            ParameterSet parameters,
            boolean dark,
            TranslationService translations,
            java.util.function.BiConsumer<Feature.Sketch, ParameterSet> accept) {
        this.translations = translations;
        finish = button("", "constraint-finish", this::finish);
        undo = button("", "sketch-undo", () -> controllerUndo(false));
        redo = button("", "sketch-redo", () -> controllerUndo(true));
        closedCurve.setText(translations.translate("gui.closed_curve").orElseThrow());
        construction.setText(translations.translate("gui.construction").orElseThrow());
        auto.setText(translations.translate("gui.auto_constraints").orElseThrow());
        showBadges.setText(translations.translate("gui.show_badges").orElseThrow());
        selected.setText(translations
                .translate("gui.select_a_curve_or_point_shift_click_adds_to_the_selection")
                .orElseThrow());
        status.setText(translations.translate("gui.solving").orElseThrow());
        finish.setText(translations.translate("gui.finish_sketch").orElseThrow());
        undo.setText(translations.translate("action.undo").orElseThrow());
        redo.setText(translations.translate("action.redo").orElseThrow());
        this.original = sketch;
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
        textAngle = numeric("0", ParameterUnit.DEG);
        textSpacing = numeric("0", ParameterUnit.NUMBER).dragStep(0.01);
        pointX = numeric("0", ParameterUnit.MM);
        pointY = numeric("0", ParameterUnit.MM);
        dimensionValue = FormulaField.number("10", this::formulaParameters, this::dimensionUnit, translations);
        sides = numeric("6", ParameterUnit.NUMBER).dragStep(1);
        UiText.enums(dimension, translations, "constraint.");
        setId("sketch-editor");
        getStyleClass().add("sketch-editor");
        var title = new Label(
                sketch == null
                        ? translations
                                .translate("gui.create_constrained_sketch")
                                .orElseThrow()
                        : translations.translate("gui.edit_sketch").orElseThrow());
        title.getStyleClass().add("selection-title");
        var space = new Region();
        HBox.setHgrow(space, Priority.ALWAYS);
        finish.getStyleClass().add("primary");
        var header = new HBox(
                10,
                title,
                button(
                        translations.translate("action.parameters").orElseThrow(),
                        "sketch-parameters",
                        this::parameters),
                space,
                finish,
                button(
                        translations.translate("action.cancel").orElseThrow(),
                        "constraint-cancel",
                        () -> dismiss.run()));
        header.setAlignment(Pos.CENTER_LEFT);
        var tools = new FlowPane(5, 5);
        for (var draw : Draw.values()) {
            if (draw == Draw.ADVANCED) continue;
            var button = button(
                    translations.translate("draw." + draw.name()).orElseThrow(),
                    "sketch-tool-" + draw.name().toLowerCase(Locale.ROOT),
                    () -> tool(draw));
            tools.getChildren().add(button);
        }
        createType = button(
                translations.translate("gui.more_sketch_types").orElseThrow(),
                "sketch-create-type",
                this::chooseSketchType);
        tools.getChildren().add(createType);
        tools.getChildren()
                .add(button(
                        translations.translate("gui.sketch_dimension").orElseThrow(),
                        "sketch-dimension",
                        this::startDimension));
        splineDegree.getItems().setAll(3, 5);
        splineDegree.setValue(3);
        splineDegree.setId("sketch-spline-degree");
        textStyle
                .getItems()
                .setAll(
                        translations.translate("gui.regular").orElseThrow(),
                        translations.translate("gui.bold").orElseThrow(),
                        translations.translate("gui.italic").orElseThrow(),
                        translations.translate("gui.bold_italic").orElseThrow());
        textStyle.getSelectionModel().select(0);
        textStyle.setId("sketch-text-style");
        closedCurve.setId("sketch-closed-curve");
        toolRadius.setId("sketch-tool-radius");
        toolRho.setId("sketch-tool-rho");
        textContent.setId("sketch-text-content");
        textFont.setId("sketch-text-font");
        textHeight.setId("sketch-text-height");
        textAngle.setId("sketch-text-angle");
        textSpacing.setId("sketch-text-spacing");
        pointX.setId("sketch-point-x");
        pointY.setId("sketch-point-y");
        sides.setId("sketch-polygon-sides");
        sides.setPrefColumnCount(2);
        construction.setId("sketch-construction");
        auto.setId("sketch-auto");
        for (var check : List.of(auto, construction, closedCurve)) UiHelp.install(check, translations);
        auto.setSelected(true);
        tools.getChildren()
                .addAll(
                        new Label(translations.translate("gui.sides").orElseThrow()),
                        sides,
                        construction,
                        auto,
                        undo,
                        redo,
                        button(translations.translate("action.fit").orElseThrow(), "sketch-fit", this::fit),
                        button(
                                "F1",
                                "keyboard-shortcuts",
                                () -> KeyboardShortcuts.show(getScene().getWindow(), translations)));
        var menu = new MenuButton(translations.translate("gui.constraints").orElseThrow());
        menu.setId("sketch-constraint-menu");
        UiHelp.menu(menu, translations);
        var constraintBar = new FlowPane(4, 4, menu);
        for (var type : Type.values()) {
            if (type.dimension()) continue;
            String label = type == Type.FIX
                    ? translations.translate("gui.fix_unfix").orElseThrow()
                    : translations.translate("constraint." + type.name()).orElseThrow();
            var item = new MenuItem(label);
            item.setId("constraint-menu-" + type.name().toLowerCase(Locale.ROOT));
            UiHelp.menuItem(
                    item,
                    translations.translate("constraint.hint." + type.name()).orElseThrow());
            item.setOnAction(e -> request(type));
            menu.getItems().add(item);
            var action =
                    button(symbol(type), "constraint-" + type.name().toLowerCase(Locale.ROOT), () -> request(type));
            action.getStyleClass().add("constraint-icon");
            action.setAccessibleText(label);
            UiHelp.describe(
                    action,
                    label + " · "
                            + translations
                                    .translate("constraint.hint." + type.name())
                                    .orElseThrow());
            constraintBar.getChildren().add(action);
        }
        hint.setWrapText(true);
        hint.getStyleClass().add("muted");
        setTop(new VBox(9, header, tools, constraintBar, hint));
        BorderPane.setMargin(getTop(), new Insets(0, 0, 8, 0));
        canvas.setId("sketch-canvas");
        canvas.setFocusTraversable(true);
        var canvasHost = new StackPane(canvas);
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
        setBottom(new VBox(5, status, error));
        BorderPane.setMargin(getBottom(), new Insets(8, 0, 0, 0));
        controller.onChanged(this::refresh);
        installGestures();
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
        search.setPromptText(translations.translate("gui.search_sketch_types").orElseThrow());
        search.setAccessibleText(
                translations.translate("gui.search_sketch_types").orElseThrow());
        var results = new ListView<SketchTools.Tool>();
        results.setId("sketch-type-results");
        results.setPrefHeight(300);
        results.setPlaceholder(
                new Label(translations.translate("gui.no_sketch_types").orElseThrow()));
        results.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(SketchTools.Tool item, boolean empty) {
                super.updateItem(item, empty);
                setText(
                        empty || item == null
                                ? null
                                : translations.translate("tool." + item.name()).orElseThrow());
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
                                    .orElseThrow()
                                    .toLowerCase(Locale.ROOT)
                                    .contains(filter))
                            .toList());
            results.getSelectionModel().selectFirst();
        });
        var dialog = new Dialog<SketchTools.Tool>();
        dialog.setTitle(translations.translate("gui.more_sketch_types_").orElseThrow());
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
            if (previousDimensionPosition == null) {
                dimensionPositions.remove(draggedDimension.constraint().id());
            } else {
                dimensionPositions.put(draggedDimension.constraint().id(), previousDimensionPosition);
            }
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
        var add = button(translations.translate("gui.add_to_selection").orElseThrow(), "sketch-select-ref", () -> {
            if (dimensionTool && (dimensionEntry != null || selection.size() >= 2)) return;
            if (references.getValue() != null) {
                selection.add(references.getValue());
                selectionChanged();
            }
        });
        var clear = button(translations.translate("gui.clear").orElseThrow(), "sketch-clear-selection", () -> {
            selection.clear();
            activeConstraint = null;
            selectionChanged();
        });
        dimension.setId("sketch-dimension-type");
        dimension
                .getItems()
                .setAll(Arrays.stream(Type.values()).filter(Type::dimension).toList());
        dimension.setValue(Type.LENGTH);
        dimension.valueProperty().addListener(o -> dimensionValue.refreshFormula());
        dimension.setMaxWidth(Double.MAX_VALUE);
        dimensionValue.setId("sketch-dimension-value");
        dimensionValue.setOnAction(e -> dimension());
        var apply = button(
                translations.translate("gui.apply_dimension").orElseThrow(), "sketch-apply-dimension", this::dimension);
        constraints.setId("sketch-constraints");
        constraints.setPrefHeight(140);
        constraints.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(Constraint value, boolean empty) {
                super.updateItem(value, empty);
                if (empty || value == null) setText(null);
                else {
                    setText(symbol(value.type()) + " "
                            + translations
                                    .translate("constraint." + value.type().name())
                                    .orElseThrow()
                            + (value.type().dimension()
                                    ? " = " + number(value.values().getFirst())
                                    : "") + " · " + value.id());
                }
                if (!empty && value != null) {
                    setTooltip(UiHelp.tooltip(value.refs().toString()));
                }
            }
        });
        constraints.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            dimensionValue.refreshFormula();
            if (refreshing || b == null) return;
            activeConstraint = null;
            selection.clear();
            selection.addAll(b.refs());
            if (b.type().dimension()) {
                dimension.setValue(b.type());
                String expression =
                        controller.parameters().binding(new ParameterSet.Target(featureId, "constraint:" + b.id()));
                dimensionValue.setText(expression.isEmpty() ? number(b.values().getFirst()) : expression);
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
                new Label(translations.translate("gui.selected_geometry").orElseThrow()),
                selected,
                references,
                new HBox(5, add, clear),
                button(
                        translations.translate("gui.apply_active_constraint").orElseThrow(),
                        "sketch-apply-constraint",
                        () -> {
                            if (activeConstraint != null) apply(activeConstraint);
                        }),
                new Separator(),
                new Label(translations
                        .translate("gui.driving_value_mm_degrees_ratio")
                        .orElseThrow()),
                dimension,
                dimensionValue,
                apply,
                new Separator(),
                new Label(translations.translate("gui.constraints_").orElseThrow()),
                constraints,
                button(
                        translations.translate("gui.remove_unfix").orElseThrow(),
                        "sketch-remove-constraint",
                        this::removeConstraint),
                showBadges,
                button(
                        translations.translate("gui.edit_selected_text").orElseThrow(),
                        "sketch-edit-text",
                        this::editText),
                new Separator(),
                button(
                        translations.translate("gui.toggle_construction").orElseThrow(),
                        "sketch-toggle-construction",
                        this::toggleConstruction),
                button(
                        translations.translate("gui.use_selected_as_profile").orElseThrow(),
                        "sketch-use-profile",
                        this::useProfile),
                button(
                        translations
                                .translate("gui.use_all_nonconstruction_curves")
                                .orElseThrow(),
                        "sketch-all-profile",
                        () -> edit(controller
                                .definition()
                                .withBoundary(controller.definition().entities().stream()
                                        .filter(e -> !e.construction() && e.kind() != Kind.POINT)
                                        .map(Entity::id)
                                        .toList()))),
                button(
                        translations.translate("gui.delete_selected_geometry").orElseThrow(),
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
            cursor = drawingPosition(e);
            if (tool != Draw.SELECT || dimensionTool) draw();
        });
        canvas.setOnMouseExited(e -> {
            hoverSnap = null;
            draw();
        });
        canvas.setOnScroll(e -> {
            var anchor = model(e.getX(), e.getY());
            scale = Math.clamp(scale * Math.exp(e.getDeltaY() * 0.002), 0.15, 100);
            var moved = model(e.getX(), e.getY());
            centerX += anchor.x() - moved.x();
            centerY += anchor.y() - moved.y();
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
                    case SELECT ->
                        translations
                                .translate(
                                        "gui.select_curves_or_points_shift_click_adds_a_selection_drag_points_to_reshape_middle_drag_pans_wh")
                                .orElseThrow();
                    case POINT ->
                        translations
                                .translate("gui.click_to_place_a_sketch_point")
                                .orElseThrow();
                    case LINE ->
                        translations
                                .translate(
                                        "gui.click_two_endpoints_nearby_endpoints_and_horizontal_vertical_directions_snap_when_auto_constrai")
                                .orElseThrow();
                    case RECTANGLE ->
                        translations
                                .translate(
                                        "gui.click_two_opposite_corners_horizontal_vertical_and_coincident_constraints_are_added")
                                .orElseThrow();
                    case CIRCLE ->
                        translations
                                .translate("gui.click_a_center_then_a_radius_point")
                                .orElseThrow();
                    case ARC ->
                        translations
                                .translate("gui.click_center_start_then_end_the_arc_runs_counterclockwise")
                                .orElseThrow();
                    case SPLINE ->
                        translations
                                .translate("gui.click_start_first_control_point_second_control_point_then_end")
                                .orElseThrow();
                    case POLYGON ->
                        translations
                                .translate(
                                        "gui.click_center_then_a_vertex_the_sides_remain_equal_with_a_regular_polygon_constraint")
                                .orElseThrow();
                    case ADVANCED ->
                        translations.translate("tool.hint." + advanced.name()).orElseThrow();
                });
        showToolOptions();
        draw();
    }

    private void showToolOptions() {
        toolOptions.getChildren().clear();
        boolean visible = tool == Draw.ADVANCED;
        toolOptions.setVisible(visible);
        toolOptions.setManaged(visible);
        if (!visible) return;
        toolOptions
                .getChildren()
                .add(new Label(translations.translate("tool." + advanced.name()).orElseThrow()));
        switch (advanced) {
            case CIRCLE_TWO_TANGENT ->
                option(translations.translate("gui.radius_mm").orElseThrow(), toolRadius);
            case CONIC -> option(translations.translate("gui.rho_0_1").orElseThrow(), toolRho);
            case CONTROL_SPLINE -> {
                option(translations.translate("gui.degree").orElseThrow(), splineDegree);
                toolOptions.getChildren().add(closedCurve);
            }
            case FIT_SPLINE -> toolOptions.getChildren().add(closedCurve);
            case TEXT, TEXT_ON_PATH -> {
                option(translations.translate("gui.text_up_to_32_characters").orElseThrow(), textContent);
                option(translations.translate("gui.font_family").orElseThrow(), textFont);
                option(translations.translate("gui.style").orElseThrow(), textStyle);
                option(translations.translate("gui.height_mm").orElseThrow(), textHeight);
                option(translations.translate("gui.angle_degrees").orElseThrow(), textAngle);
                option(translations.translate("gui.extra_spacing_em").orElseThrow(), textSpacing);
            }
            default -> {}
        }
        option(translations.translate("gui.next_point_x_mm").orElseThrow(), pointX);
        option(translations.translate("gui.next_point_y_mm").orElseThrow(), pointY);
        toolOptions
                .getChildren()
                .add(button(translations.translate("gui.add_numeric_point").orElseThrow(), "sketch-add-point", () -> {
                    try {
                        advancedPoint(new Vec(value(pointX, "X"), value(pointY, "Y")));
                    } catch (RuntimeException failure) {
                        error.setText(failure.getMessage());
                    }
                }));
        toolOptions
                .getChildren()
                .add(button(
                        translations.translate("gui.complete_retry").orElseThrow(),
                        "sketch-complete-curve",
                        this::finishAdvanced));
        toolOptions.getChildren().add(new Separator());
    }

    private void option(String label, Node input) {
        toolOptions.getChildren().addAll(new Label(label), input);
    }

    private SketchTools.Options options() {
        return new SketchTools.Options(
                sides.integer(),
                splineDegree.getValue(),
                closedCurve.isSelected(),
                construction.isSelected(),
                value(toolRadius, translations.translate("gui.radius").orElseThrow()),
                value(toolRho, translations.translate("gui.rho").orElseThrow()),
                textContent.evaluatedText(),
                textFont.evaluatedText().strip(),
                textStyle.getSelectionModel().getSelectedIndex(),
                value(textHeight, translations.translate("gui.text_height").orElseThrow()),
                dev.voxelbrew.api.model.units.Measurements.radians(value(
                        textAngle, translations.translate("gui.text_angle").orElseThrow())),
                value(textSpacing, translations.translate("gui.text_spacing").orElseThrow()));
    }

    private void advancedPoint(Vec point) {
        error.setText("");
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
        if (tool != Draw.ADVANCED) return;
        try {
            String entityId = id(advanced.name().toLowerCase(Locale.ROOT));
            var next = SketchTools.add(
                    controller.definition(),
                    advanced,
                    entityId,
                    List.copyOf(drawing),
                    List.copyOf(selection),
                    options());
            if (advanced == SketchTools.Tool.TEXT || advanced == SketchTools.Tool.TEXT_ON_PATH) {
                var parameters = bindText(controller.parameters(), entityId, textContent, textFont);
                for (var entry : Map.of(
                                Type.TEXT_HEIGHT, textHeight, Type.ANGLE, textAngle, Type.TEXT_SPACING, textSpacing)
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
        } catch (RuntimeException failure) {
            error.setText(failure.getMessage());
            // Numeric options can be repaired and the same points retried with Enter.
            draw();
        }
    }

    private void editText() {
        try {
            var chosen = selection.stream()
                    .map(r -> controller.definition().entity(r.entityId()))
                    .filter(e -> e.kind() == Kind.TEXT)
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(translations
                            .translate("gui.select_a_text_entity_first")
                            .orElseThrow()));
            var dialog = new Dialog<Entity>();
            dialog.initOwner(getScene().getWindow());
            dialog.setTitle(translations.translate("gui.edit_sketch_text").orElseThrow());
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
            path.setPromptText(
                    translations.translate("gui.no_path_baseline_position").orElseThrow());
            var message = new Label();
            message.setWrapText(true);
            dialog.getDialogPane()
                    .setContent(new VBox(
                            7,
                            new Label(translations.translate("tool.TEXT").orElseThrow()),
                            content,
                            new Label(translations.translate("gui.font_family").orElseThrow()),
                            font,
                            new Label(translations
                                    .translate("gui.path_blank_to_detach")
                                    .orElseThrow()),
                            path,
                            message));
            dialog.getDialogPane().getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
            var result = new Entity[1];
            var parameters = new ParameterSet[1];
            dialog.getDialogPane()
                    .lookupButton(ButtonType.OK)
                    .addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
                        try {
                            result[0] = SketchText.create(
                                    chosen.id(),
                                    content.evaluatedText(),
                                    font.evaluatedText().strip(),
                                    chosen.text().style(),
                                    chosen.value(0),
                                    chosen.value(1),
                                    chosen.value(2),
                                    chosen.value(3),
                                    chosen.value(4),
                                    path.getValue(),
                                    chosen.construction());
                            parameters[0] = bindText(controller.parameters(), chosen.id(), content, font);
                            parameters[0].validate();
                        } catch (RuntimeException failure) {
                            message.setText(failure.getMessage());
                            event.consume();
                        }
                    });
            dialog.setResultConverter(button -> button == ButtonType.OK ? result[0] : null);
            Dialogs.show(dialog, getScene().getWindow(), translations)
                    .ifPresent(e -> controller.edit(controller.definition().replace(e), parameters[0]));
        } catch (RuntimeException failure) {
            error.setText(failure.getMessage());
        }
    }

    private Vec drawingPosition(MouseEvent event) {
        var pointer = model(event.getX(), event.getY());
        hoverSnap = dimensionTool || auto.isSelected() && tool != Draw.SELECT
                ? SketchSnapping.find(controller.displayed(), pointer, scale)
                : null;
        if (hoverSnap != null) return hoverSnap.position();
        if (tool == Draw.LINE && drawing.size() == 1 && (auto.isSelected() || event.isShiftDown())) {
            var a = drawing.getFirst();
            var d = pointer.minus(a);
            if (event.isShiftDown() || Math.min(Math.abs(d.x()), Math.abs(d.y())) < 5 / scale) {
                return Math.abs(d.x()) >= Math.abs(d.y()) ? new Vec(pointer.x(), a.y()) : new Vec(a.x(), pointer.y());
            }
        }
        return pointer;
    }

    private void drawingClick(MouseEvent event) {
        try {
            var p = drawingPosition(event);
            var hit = hoverSnap;
            if (tool == Draw.ADVANCED) {
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
            addition = drawnGeometry(id, a, b);
            if (construction.isSelected()) {
                addition = addition.withEntities(addition.entities().stream()
                                .map(e -> e.withConstruction(true))
                                .toList())
                        .withBoundary(List.of());
            }
            var g = controller.definition();
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
        dimensionTool = false;
        tool = Draw.SELECT;
        drawing.clear();
        snaps.clear();
        activeConstraint = type;
        hint.setText(translations.translate("constraint." + type.name()).orElseThrow()
                + " · "
                + translations.translate("constraint.hint." + type.name()).orElseThrow());
        if (enough(type)) apply(type);
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
        if (dimensionTool) {
            var refs = SketchDimensions.references(controller.displayed(), List.copyOf(selection));
            selection.clear();
            selection.addAll(refs);
        }
        selected.setText(
                selection.isEmpty()
                        ? translations.translate("gui.nothing_selected").orElseThrow()
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
                var a = controller.displayed().point(refs.get(0));
                var b = controller.displayed().point(refs.get(1));
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
            var g = controller.definition();
            var refs = List.copyOf(selection);
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
        hint.setText(translations.translate("gui.dimension_hint").orElseThrow());
        canvas.requestFocus();
        selectionChanged();
    }

    private void placeDimension(Vec position) {
        if (dimensionEntry != null || controller.busy()) return;
        try {
            var candidate = SketchDimensions.measure(controller.displayed(), List.copyOf(selection), position);
            dimension.setValue(candidate.type());
            dimensionValue.setText(number(candidate.value()));
            editDimensionValue(position);
        } catch (RuntimeException failure) {
            error.setText(translations.translate("gui.dimension_hint").orElseThrow() + "\n" + failure.getMessage());
        }
    }

    private void editDimensionValue() {
        var chosen = constraints.getSelectionModel().getSelectedItem();
        editDimensionValue(chosen == null ? cursor : dimensionPositions.getOrDefault(chosen.id(), cursor));
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
        input.setAccessibleText(translations.translate("gui.sketch_dimension").orElseThrow());
        var apply = new Button("✓");
        apply.setId("sketch-inline-dimension-apply");
        apply.setMinWidth(Region.USE_PREF_SIZE);
        UiHelp.describe(apply, translations.translate("gui.apply_dimension").orElseThrow());
        Runnable commit = () -> {
            if (controller.busy()) return;
            dimensionValue.setText(input.getText());
            int before = controller.definition().constraints().size();
            if (dimension()) {
                var added = controller.definition().constraints();
                var chosen = constraints.getSelectionModel().getSelectedItem();
                String id;
                if (added.size() > before) {
                    id = added.getLast().id();
                } else {
                    if (chosen == null) {
                        id = null;
                    } else {
                        id = chosen.id();
                    }
                }
                if (id != null) dimensionPositions.put(id, position);
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
                Math.clamp(px(dimensionEntryPosition.x()) - width / 2, 0, Math.max(0, canvas.getWidth() - width)),
                Math.clamp(py(dimensionEntryPosition.y()) - height / 2, 0, Math.max(0, canvas.getHeight() - height)),
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
            var g = controller.definition();
            var chosen = constraints.getSelectionModel().getSelectedItem();
            var type = chosen != null && chosen.type().dimension() ? chosen.type() : dimension.getValue();
            String expression = dimensionValue.getText().strip();
            double value = controller.parameters().value(expression, FeatureDimensions.unit(type));
            String id = chosen != null && chosen.type().dimension() ? chosen.id() : id("dimension");
            SketchGeometry next;
            if (chosen != null && chosen.type().dimension()) {
                next = g.withConstraints(g.constraints().stream()
                        .map(c -> c.id().equals(id) ? c.withValue(value) : c)
                        .toList());
            } else {
                next = g.add(SketchConstraints.create(
                        g, id, type, SketchDimensions.references(g, List.copyOf(selection)), value));
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
            return true;
        } catch (RuntimeException failure) {
            error.setText(failure.getMessage());
            dimensionValue.getStyleClass().add("invalid");
            return false;
        }
    }

    private void removeConstraint() {
        var chosen = constraints.getSelectionModel().getSelectedItem();
        if (chosen == null) {
            error.setText(translations
                    .translate("gui.select_a_constraint_in_the_list_or_click_its_badge_first")
                    .orElseThrow());
            return;
        }
        edit(controller
                .definition()
                .withConstraints(controller.definition().constraints().stream()
                        .filter(c -> !c.id().equals(chosen.id()))
                        .toList()));
    }

    private void toggleConstruction() {
        var ids = selection.stream().map(Ref::entityId).collect(java.util.stream.Collectors.toSet());
        var g = controller.definition();
        var entities = g.entities().stream()
                .map(e -> ids.contains(e.id()) ? e.withConstruction(!e.construction()) : e)
                .toList();
        var boundary = g.boundary().stream()
                .filter(id -> entities.stream().anyMatch(e -> e.id().equals(id) && !e.construction()))
                .toList();
        edit(new SketchGeometry(entities, g.constraints(), boundary));
    }

    private void useProfile() {
        try {
            var ids = selection.stream()
                    .filter(r -> r.part() == Part.OBJECT)
                    .map(Ref::entityId)
                    .distinct()
                    .toList();
            if (ids.isEmpty()) {
                throw new IllegalArgumentException(translations
                        .translate("gui.select_the_boundary_curves_including_any_holes")
                        .orElseThrow());
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
            if (!id.equals("origin")) g = Sketches.removeEntity(g, id);
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
            constraints.getItems().setAll(controller.definition().constraints());
            if (prior != null) {
                constraints.getItems().stream()
                        .filter(c -> c.id().equals(prior.id()))
                        .findFirst()
                        .ifPresent(c -> constraints.getSelectionModel().select(c));
            }
            var refs = new ArrayList<Ref>();
            refs.add(ORIGIN);
            for (var e : controller.definition().entities()) {
                refs.add(e.ref());
                refs.addAll(e.pointRefs());
            }
            var selectedRef = references.getValue();
            references.getItems().setAll(refs);
            references.setValue(refs.contains(selectedRef) ? selectedRef : ORIGIN);
            selection.removeIf(r -> !refs.contains(r));
            undo.setDisable(!controller.canUndo());
            redo.setDisable(!controller.canRedo());
            finish.setDisable(!controller.canFinish()
                    || controller.definition().entities().isEmpty());
            status.setText(solverStatus());
            if (!controller.busy() && !controller.interactionError().isEmpty()) {
                error.setText(controller.interactionError());
            } else if (!controller.busy()
                    && controller.result() != null
                    && !controller.result().solved()) {
                error.setText(controller.result().problems().stream()
                        .map(SketchSolver.Problem::message)
                        .reduce((a, b) -> a + "\n" + b)
                        .orElse(translations
                                .translate("gui.constraint_conflict")
                                .orElseThrow()));
            }
            selected.setText(
                    selection.isEmpty()
                            ? translations.translate("gui.nothing_selected").orElseThrow()
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

    private void fit() {
        var points = controller.displayed().entities().stream()
                .flatMap(e -> displayCurves(controller.displayed(), e).stream())
                .flatMap(e -> e.sample().stream())
                .toList();
        if (points.isEmpty()) {
            centerX = 30;
            centerY = 20;
            scale = Math.max(0.15, Math.min(canvas.getWidth() / 100, canvas.getHeight() / 70));
            draw();
            return;
        }
        double minX = points.stream().mapToDouble(Vec::x).min().orElse(0),
                maxX = points.stream().mapToDouble(Vec::x).max().orElse(60);
        double minY = points.stream().mapToDouble(Vec::y).min().orElse(0),
                maxY = points.stream().mapToDouble(Vec::y).max().orElse(40);
        centerX = minX / 2 + maxX / 2;
        centerY = minY / 2 + maxY / 2;
        scale = Math.clamp(
                Math.min(canvas.getWidth() / (maxX - minX + 20), canvas.getHeight() / (maxY - minY + 20)), 0.15, 40);
        draw();
    }

    Point2D modelToScreen(Vec point) {
        return canvas.localToScreen(px(point.x()), py(point.y()));
    }

    SketchController controller() {
        return controller;
    }

    private Vec model(double x, double y) {
        return new Vec(centerX + (x - canvas.getWidth() / 2) / scale, centerY - (y - canvas.getHeight() / 2) / scale);
    }

    private double px(double x) {
        return canvas.getWidth() / 2 + (x - centerX) * scale;
    }

    private double py(double y) {
        return canvas.getHeight() / 2 - (y - centerY) * scale;
    }

    private Hit hitPoint(double x, double y, double radius) {
        Hit result = null;
        double distance = radius;
        for (var e : controller.displayed().entities()) {
            for (var ref : e.pointRefs()) {
                var p = e.point(ref);
                double d = Math.hypot(px(p.x()) - x, py(p.y()) - y);
                if (d < distance) {
                    distance = d;
                    result = new Hit(ref, p);
                }
            }
        }
        if (Math.hypot(px(0) - x, py(0) - y) < distance) return new Hit(ORIGIN, new Vec(0, 0));
        return result;
    }

    private Hit hit(double x, double y) {
        var point = hitPoint(x, y, 7);
        if (point != null) return point;
        Hit result = null;
        double best = 6 / scale;
        var p = model(x, y);
        for (var e : controller.displayed().entities()) {
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
            if (Math.abs(x - hit.x()) <= hit.width() / 2 + 3 && Math.abs(y - hit.y()) <= 11) return hit;
        }
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
        var graphics = canvas.getGraphicsContext2D();
        double w = canvas.getWidth(), h = canvas.getHeight();
        if (w <= 0 || h <= 0 || !Double.isFinite(scale) || scale <= 0) return;
        drawGrid(graphics, w, h);
        if (controller == null) return;
        var g = controller.displayed();
        dimensionHits.clear();
        boolean invalid = controller.result() != null && !controller.result().solved();
        var fixed = controller.result() != null && controller.result().solved() && !controller.busy()
                ? controller.result().fullyConstrainedReferences()
                : Set.<Ref>of();
        Color constrainedColor = Color.web(dark ? "#e2e8f0" : "#000000");
        for (var entity : g.entities()) drawEntity(graphics, g, entity, invalid, fixed, constrainedColor);
        drawDimensionPreview(g);
        drawBadges(graphics, g);
        drawSelectionOverlay(graphics);
        drawToolPreview(graphics);
    }

    private void drawDimension(Constraint c, SketchGeometry g) {
        var graphics = canvas.getGraphicsContext2D();
        var endpoints = dimensionEndpoints(c, g);
        var a = endpoints.start();
        var b = endpoints.end();
        var label = dimensionPositions.getOrDefault(c.id(), a.plus(b).times(0.5).plus(new Vec(0, 20 / scale)));
        double x = px(label.x()), y = py(label.y());
        String text = dimensionLabel(c);
        double width = Math.max(40, text.length() * 8);
        double lineAx = px(a.x()), lineAy = py(a.y()), lineBx = x, lineBy = y;
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
                lineAx = px(start.x());
                lineAy = py(start.y());
                lineBx = px(end.x());
                lineBy = py(end.y());
                graphics.strokeLine(px(a.x()), py(a.y()), px(start.x()), py(start.y()));
                graphics.strokeLine(px(b.x()), py(b.y()), px(end.x()), py(end.y()));
                graphics.strokeLine(px(start.x()), py(start.y()), px(end.x()), py(end.y()));
                dimensionArrow(px(start.x()), py(start.y()), px(end.x()), py(end.y()));
                dimensionArrow(px(end.x()), py(end.y()), px(start.x()), py(start.y()));
            }
        } else {
            graphics.strokeLine(px(a.x()), py(a.y()), x, y);
        }
        graphics.setFill(Color.web(dark ? "#161c23" : "#f8fafc"));
        graphics.fillRect(x - width / 2, y - 10, width, 20);
        Color labelColor = Color.web(dark ? "#bacbdd" : "#263746");
        graphics.setFill(
                c.equals(constraints.getSelectionModel().getSelectedItem()) ? Color.web("#2688ed") : labelColor);
        graphics.setFont(Font.font(13));
        graphics.fillText(text, x - width / 2 + 3, y + 5);
        for (double dx = -width / 2 + 8; dx < width / 2; dx += 16) badges.add(new Badge(c, x + dx, y));
        if (!c.id().equals("dimension-preview")) {
            dimensionHits.add(new DimensionHit(c, label, x, y, width, lineAx, lineAy, lineBx, lineBy));
        }
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
        panning = e.getButton() == MouseButton.MIDDLE;
        boxCandidate = boxSelecting = false;
        boxAdditive = e.isControlDown() || e.isShiftDown();
        if (e.getButton() == MouseButton.PRIMARY && tool == Draw.SELECT && activeConstraint == null) {
            draggedDimension = hitDimension(e.getX(), e.getY());
            if (draggedDimension != null) {
                previousDimensionPosition =
                        dimensionPositions.get(draggedDimension.constraint().id());
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
        } else if (draggedDimension != null && e.isPrimaryButtonDown()) {
            if (Math.hypot(e.getX() - pressX, e.getY() - pressY) > 3) {
                moving = true;
                dimensionPositions.put(
                        draggedDimension.constraint().id(),
                        draggedDimension.label().plus(model(e.getX(), e.getY()).minus(model(pressX, pressY))));
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
            controller.drag(dragPoint, model(e.getX(), e.getY()), false);
        }
        lastX = e.getX();
        lastY = e.getY();
        e.consume();
    }

    private void releaseCanvas(MouseEvent e) {
        if (draggedDimension != null) {
            draggedDimension = null;
            previousDimensionPosition = null;
            e.consume();
            return;
        }
        if (boxSelecting) {
            if (!boxAdditive) selection.clear();
            constraints.getSelectionModel().clearSelection();
            selection.addAll(SketchSelection.inBox(
                    controller.displayed(), model(pressX, pressY), model(e.getX(), e.getY()), e.getX() < pressX));
            boxSelecting = false;
            selectionChanged();
        }
        boxCandidate = false;
        if (moving && dragPoint != null) controller.drag(dragPoint, model(e.getX(), e.getY()), true);
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
        hoverSnap = SketchSnapping.find(controller.displayed(), model(e.getX(), e.getY()), scale);
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
            if (Math.abs(e.getX() - badge.x()) < 11 && Math.abs(e.getY() - badge.y()) < 9) {
                constraints.getSelectionModel().select(badge.constraint());
                if (badge.constraint().type().dimension() && e.getClickCount() == 2) editDimensionValue();
                return true;
            }
        }
        return false;
    }

    private SketchGeometry drawnGeometry(String id, Vec a, Vec b) {
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
            addition = Sketches.polygon(id, a.x(), a.y(), a.distance(b), sides.integer());
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
                default ->
                    throw new IllegalArgumentException(
                            translations.translate("gui.choose_a_drawing_tool").orElseThrow());
            };
            addition = new SketchGeometry(List.of(entity), List.of(), entity.construction() ? List.of() : List.of(id));
            if (tool == Draw.LINE && auto.isSelected()) {
                if (Math.abs(a.y() - b.y()) < 1e-9) {
                    addition = addition.add(SketchConstraints.create(
                            addition, id + "-horizontal", Type.HORIZONTAL, List.of(entity.ref()), 0));
                } else if (Math.abs(a.x() - b.x()) < 1e-9) {
                    addition = addition.add(SketchConstraints.create(
                            addition, id + "-vertical", Type.VERTICAL, List.of(entity.ref()), 0));
                }
            }
        }
        return addition;
    }

    private String solverStatus() {
        if (controller.busy()) {
            return translations.translate("gui.solving_constraints").orElseThrow();
        }
        if (controller.result() == null) {
            return translations.translate("status.ready").orElseThrow();
        }
        if (!controller.result().solved()) {
            return translations.translate("solver.failed").orElseThrow();
        }
        if (controller.result().degreesOfFreedom() == 0) {
            return translations.translate("solver.full").orElseThrow();
        }
        return translations
                .translate("solver.free", controller.result().degreesOfFreedom())
                .orElseThrow();
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
        var lo = model(0, h);
        double firstX = Math.ceil(lo.x() / spacing) * spacing;
        double firstY = Math.ceil(lo.y() / spacing) * spacing;
        // Bound work by screen pixels, including the first layout pulse and extreme coordinates.
        for (int i = 0; i <= Math.min(500, Math.ceil(w / (scale * spacing))); i++) {
            double x = firstX + i * spacing;
            graphics.strokeLine(px(x), 0, px(x), h);
        }
        for (int i = 0; i <= Math.min(500, Math.ceil(h / (scale * spacing))); i++) {
            double y = firstY + i * spacing;
            graphics.strokeLine(0, py(y), w, py(y));
        }
        graphics.setStroke(Color.web("#ce6663"));
        graphics.strokeLine(0, py(0), w, py(0));
        graphics.setStroke(Color.web("#52a287"));
        graphics.strokeLine(px(0), 0, px(0), h);
        graphics.setFill(Color.web("#a6b3bf"));
        graphics.fillOval(px(0) - 3, py(0) - 3, 6, 6);
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
        graphics.setStroke(color);
        graphics.setLineWidth(chosen || hovered ? 2.5 : 1.5);
        graphics.setLineDashes(entity.construction() ? new double[] {7, 5} : new double[] {});
        for (var curve : displayCurves(g, entity)) {
            var points = curve.sample();
            graphics.beginPath();
            graphics.moveTo(px(points.getFirst().x()), py(points.getFirst().y()));
            for (var p : points) graphics.lineTo(px(p.x()), py(p.y()));
            graphics.stroke();
        }
        graphics.setLineDashes();
        drawControlPolygon(graphics, entity);
        drawEntityPoints(graphics, entity, chosen, invalid, fixed, constrainedColor, color);
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
            graphics.strokeLine(px(p0.x()), py(p0.y()), px(p1.x()), py(p1.y()));
            graphics.strokeLine(px(p2.x()), py(p2.y()), px(p3.x()), py(p3.y()));
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
                        px(controls.get(i - 1).x()),
                        py(controls.get(i - 1).y()),
                        px(controls.get(i).x()),
                        py(controls.get(i).y()));
            }
            graphics.setLineDashes();
        }
    }

    private void drawEntityPoints(
            GraphicsContext graphics,
            Entity entity,
            boolean chosen,
            boolean invalid,
            Set<Ref> fixed,
            Color constrainedColor,
            Color color) {
        for (var ref : entity.pointRefs()) {
            var p = entity.point(ref);
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
            graphics.fillOval(px(p.x()) - radius, py(p.y()) - radius, radius * 2, radius * 2);
        }
    }

    private void drawDimensionPreview(SketchGeometry g) {
        positionDimensionEntry();
        if (dimensionEntry != null && constraints.getSelectionModel().getSelectedItem() == null) {
            try {
                var measurement = SketchDimensions.measure(g, List.copyOf(selection), dimensionEntryPosition);
                var preview = SketchConstraints.create(
                        g, "dimension-preview", measurement.type(), List.copyOf(selection), measurement.value());
                dimensionPositions.put(preview.id(), dimensionEntryPosition);
                drawDimension(preview, g);
                dimensionPositions.remove(preview.id());
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
                if (c.refs().isEmpty()) continue;
                try {
                    if (c.type().dimension()) {
                        drawDimension(c, g);
                        continue;
                    }
                    var ref = c.refs().getFirst();
                    var p = ref.part() == Part.OBJECT ? g.entity(ref.entityId()).at(0.5) : g.point(ref);
                    double x = px(p.x()) + 14 + (index % 3) * 18, y = py(p.y()) + 14 + (index / 3 % 2) * 18;
                    index++;
                    badges.add(new Badge(c, x, y));
                    graphics.setFill(Color.web(dark ? "#28323e" : "#ffffff"));
                    graphics.fillRoundRect(x - 9, y - 8, 18, 16, 4, 4);
                    graphics.setFill(Color.web(
                            c.equals(constraints.getSelectionModel().getSelectedItem()) ? "#e6a239" : "#d15d55"));
                    graphics.fillText(symbol(c.type()), x - 6, y + 4, 14);
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
            graphics.strokeOval(px(target.x()) - 6, py(target.y()) - 6, 12, 12);
            if (dimensionTool && hoverSnap.ref().part() != Part.OBJECT) {
                // Coincident endpoints may be drawn by several entities; keep the target on top.
                graphics.setFill(Color.web("#2688ed"));
                graphics.fillOval(px(target.x()) - 4, py(target.y()) - 4, 8, 8);
            }
        }
    }

    private void drawToolPreview(GraphicsContext graphics) {
        if (!drawing.isEmpty()) {
            graphics.setStroke(Color.web("#e6a239"));
            graphics.setLineWidth(1.5);
            graphics.setLineDashes(5, 4);
            var a = drawing.getFirst();
            if (tool == Draw.RECTANGLE) {
                graphics.strokeRect(
                        Math.min(px(a.x()), px(cursor.x())),
                        Math.min(py(a.y()), py(cursor.y())),
                        Math.abs(a.x() - cursor.x()) * scale,
                        Math.abs(a.y() - cursor.y()) * scale);
            } else if (tool == Draw.CIRCLE || tool == Draw.POLYGON) {
                double r = a.distance(cursor) * scale;
                graphics.strokeOval(px(a.x()) - r, py(a.y()) - r, 2 * r, 2 * r);
            } else {
                graphics.beginPath();
                graphics.moveTo(px(a.x()), py(a.y()));
                for (var p : drawing) graphics.lineTo(px(p.x()), py(p.y()));
                graphics.lineTo(px(cursor.x()), py(cursor.y()));
                graphics.stroke();
            }
            graphics.setLineDashes();
        }
    }

    private String dimensionLabel(Constraint c) {
        String prefix = switch (c.type()) {
            case DIAMETER -> "Ø";
            case RADIUS -> "R";
            default -> "";
        };
        String expression = controller.parameters().binding(new ParameterSet.Target(featureId, "constraint:" + c.id()));
        String text = (expression.isEmpty() ? "" : "fx: ")
                + prefix
                + String.format(Locale.ROOT, "%.2f", c.values().getFirst())
                + (c.type() == Type.ANGLE ? "°" : "");
        return text;
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
            var direction = entity.at(1).minus(entity.at(0)).unit();
            a = entity.at(0).plus(direction.times(b.minus(entity.at(0)).dot(direction)));
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
                        && !boxAdditive
                ? hit.ref()
                : null;
    }

    private void selectGeometry(MouseEvent e, Hit hit) {
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
