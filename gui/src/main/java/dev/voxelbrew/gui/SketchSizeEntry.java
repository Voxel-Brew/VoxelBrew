package dev.voxelbrew.gui;

import dev.voxelbrew.api.i18n.TranslationService;
import dev.voxelbrew.api.model.sketch.SketchGeometry.Vec;
import java.util.function.Consumer;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;

/** Keyboard entry for line length and angle, circle radius or rectangle size. */
final class SketchSizeEntry extends HBox {
    private final TextField first;
    private final TextField second;
    private final Shape shape;
    private final Vec anchor;
    private final Vec direction;
    private final double rectangleFactor;

    enum Shape {
        LINE,
        CIRCLE,
        RECTANGLE,
        CENTER_RECTANGLE
    }

    private static void focus(TextField field) {
        field.requestFocus();
        field.selectAll();
    }

    static double angleDegrees(Vec direction) {
        return Math.toDegrees(Math.atan2(direction.y(), direction.x()));
    }

    private static double readValue(TextField field, boolean validate, double minimum) {
        try {
            double value = Double.parseDouble(field.getText().strip().replace(',', '.'));
            if (Double.isFinite(value) && value >= minimum) return value;
        } catch (NumberFormatException invalid) {
            // Select the invalid entry so the next keystroke replaces it.
        }
        if (validate) {
            if (!field.getStyleClass().contains("invalid")) {
                field.getStyleClass().add("invalid");
            }
            focus(field);
        }
        return Double.NaN;
    }

    SketchSizeEntry(
            Vec anchor,
            Vec pointer,
            Shape shape,
            TranslationService translations,
            Consumer<Vec> preview,
            Consumer<Vec> finish) {
        super(5);
        this.anchor = anchor;
        this.shape = shape;
        direction = pointer.minus(anchor);
        rectangleFactor = shape == Shape.CENTER_RECTANGLE ? 2 : 1;
        setId("sketch-size-entry");
        setAlignment(Pos.CENTER_LEFT);
        setManaged(false);
        switch (shape) {
            case LINE -> {
                first = field("length", translations.translate("constraint.LENGTH"), direction.length());
                getChildren().add(new Label("mm"));
                second = field("angle", translations.translate("constraint.ANGLE"), angleDegrees(direction));
            }
            case CIRCLE -> {
                first = field("radius", translations.translate("gui.radius"), direction.length());
                second = null;
            }
            case RECTANGLE, CENTER_RECTANGLE -> {
                first = field(
                        "width", translations.translate("dimension.width"), Math.abs(direction.x()) * rectangleFactor);
                second = field(
                        "height",
                        translations.translate("dimension.height"),
                        Math.abs(direction.y()) * rectangleFactor);
            }
            default -> throw new IllegalArgumentException("Unsupported sketch shape: " + shape);
        }
        getChildren().add(new Label(shape == Shape.LINE ? "°" : "mm"));
        javafx.beans.InvalidationListener changed = ignored -> submit(false, preview);
        first.textProperty().addListener(changed);
        if (second != null) second.textProperty().addListener(changed);
        addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.TAB) {
                event.consume();
                focus(first.isFocused() && second != null ? second : first);
            } else if (event.getCode() == KeyCode.ENTER) {
                event.consume();
                submit(true, finish);
            }
        });
    }

    private void submit(boolean validate, Consumer<Vec> accept) {
        double size = readValue(first, validate, 1e-6);
        if (!Double.isFinite(size)) return;
        if (shape == Shape.LINE) {
            double degrees = readValue(second, validate, Double.NEGATIVE_INFINITY);
            if (Double.isFinite(degrees)) {
                double radians = Math.toRadians(degrees % 360);
                accept.accept(anchor.plus(new Vec(Math.cos(radians), Math.sin(radians)).times(size)));
            }
        } else if (second == null) {
            var axis = direction.length() > 0 ? direction.unit() : new Vec(1, 0);
            accept.accept(anchor.plus(axis.times(size)));
        } else {
            double h = readValue(second, validate, 1e-6);
            if (Double.isFinite(h)) {
                accept.accept(anchor.plus(new Vec(
                        Math.copySign(size / rectangleFactor, direction.x()),
                        Math.copySign(h / rectangleFactor, direction.y()))));
            }
        }
    }

    private TextField field(String id, String caption, double value) {
        var input = new TextField(Double.toString(value));
        input.setId("sketch-size-" + id);
        input.setAccessibleText(caption);
        input.setPrefColumnCount(6);
        input.textProperty().addListener(o -> input.getStyleClass().remove("invalid"));
        var label = new Label(caption);
        label.setLabelFor(input);
        getChildren().addAll(label, input);
        return input;
    }

    void begin(String typed) {
        focus(first);
        if (!typed.isEmpty()) first.replaceSelection(typed);
    }
}
