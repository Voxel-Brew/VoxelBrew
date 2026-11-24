package dev.voxelbrew.gui;

import dev.voxelbrew.api.i18n.TranslationService;
import dev.voxelbrew.api.model.parameters.*;
import dev.voxelbrew.api.model.units.Measurements;
import dev.voxelbrew.engine.parameters.*;
import java.util.Objects;
import java.util.function.Supplier;
import javafx.beans.binding.Bindings;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.css.PseudoClass;
import javafx.scene.control.*;
import javafx.scene.control.skin.TextFieldSkin;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/** A normal editable TextField with explicit formula affordance and non-destructive previews. */
final class FormulaField extends TextField {
    private static final PseudoClass FORMULA = PseudoClass.getPseudoClass("formula");

    private static final PseudoClass INVALID = PseudoClass.getPseudoClass("formula-invalid");

    private final ExpressionType expressionType;

    private final Supplier<ParameterSet> parameters;

    private final Supplier<ParameterUnit> unit;

    private final ObjectProperty<NumericFieldBounds> bounds = new SimpleObjectProperty<>(NumericFieldBounds.UNBOUNDED);

    private final StringProperty unitSuffix = new SimpleStringProperty("");

    private final Tooltip preview = UiHelp.tooltip("");

    private final ParameterSuggestions suggestions;

    private final FormulaHelp formulaHelp;

    private final TranslationService translations;

    private java.util.function.DoubleConsumer validation = value -> {};

    private String displayUnit;

    private Double dragStep;

    @RequiredArgsConstructor
    enum ExpressionType {
        NUMBER("ƒx", "number-expression"),
        STRING("ƒs", "string-expression");

        private final String mark;
        private final String styleClass;
    }

    static FormulaField number(
            String text, Supplier<ParameterSet> parameters, ParameterUnit unit, TranslationService translations) {
        return number(text, parameters, () -> unit, translations);
    }

    static FormulaField number(
            String text,
            Supplier<ParameterSet> parameters,
            @NonNull Supplier<ParameterUnit> unit,
            TranslationService translations) {
        return new FormulaField(text, parameters, unit, translations);
    }

    static FormulaField text(String text, Supplier<ParameterSet> parameters, TranslationService translations) {
        return new FormulaField(text, parameters, null, translations);
    }

    static ParameterUnit unit(String label) {
        try {
            return ParameterUnit.parse(label);
        } catch (IllegalArgumentException unsupported) {
            return ParameterUnit.NUMBER;
        }
    }

    private FormulaField(
            String text,
            @NonNull Supplier<ParameterSet> parameters,
            Supplier<ParameterUnit> unit,
            @NonNull TranslationService translations) {
        super(text);
        this.parameters = parameters;
        this.unit = unit;
        this.translations = translations;
        expressionType = unit == null ? ExpressionType.STRING : ExpressionType.NUMBER;
        getStyleClass().addAll("formula-field", expressionType.styleClass);
        setTooltip(preview);
        localizeHelp();
        textProperty().addListener((o, a, b) -> {
            refreshFormula();
            requestLayout();
        });
        focusedProperty().addListener((o, a, b) -> refreshFormula());
        sceneProperty().addListener((o, a, b) -> {
            if (b != null) refreshFormula();
        });
        preview.setOnShowing(e -> refreshFormula());
        suggestions = new ParameterSuggestions(this, parameters, translations);
        formulaHelp = new FormulaHelp(this, translations);
        addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == javafx.scene.input.KeyCode.F1) {
                event.consume();
                showHelp();
            }
        });
        if (expressionType == ExpressionType.NUMBER) new NumericFieldDrag(this);
    }

    ExpressionType expressionType() {
        return expressionType;
    }

    void showHelp() {
        suggestions.dismiss();
        preview.hide();
        formulaHelp.show();
    }

    private void localizeHelp() {
        setPromptText(translations.translate(unit == null ? "formula.textPrompt" : "formula.numberPrompt"));
        setAccessibleHelp(translations.translate(unit == null ? "formula.textSyntax" : "formula.numberSyntax"));
    }

    double value() {
        if (unit == null) throw new IllegalStateException("This is a text field");
        var selected = Objects.requireNonNull(unit.get());
        double result = parameters.get().value(getText(), selected);
        double displayed = selected.displayValue(Measurements.quantity(result, selected.canonicalUnit()));
        if (!bounds.get().accepts(displayed)) {
            throw new IllegalArgumentException(translations.translate(
                    "formula.bounds", bounds.get().minimumHint(), bounds.get().maximumHint()));
        }
        validation.accept(result);
        return result;
    }

    FormulaField bounds(double min, boolean minInclusive, double max, boolean maxInclusive) {
        if (unit == null) throw new IllegalStateException("This is a text field");
        bounds.set(new NumericFieldBounds(min, minInclusive, max, maxInclusive));
        requestLayout();
        refreshFormula();
        return this;
    }

    NumericFieldBounds bounds() {
        return bounds.get();
    }

    /** Change in declared display units per horizontal screen pixel during middle-button dragging. */
    FormulaField dragStep(double step) {
        if (unit == null) throw new IllegalStateException("This is a text field");
        if (!Double.isFinite(step) || step <= 0) {
            throw new IllegalArgumentException("Drag step must be finite and positive");
        }
        dragStep = step;
        return this;
    }

    double dragStep() {
        if (dragStep != null) return dragStep;
        if (unit == null) throw new IllegalStateException("This is a text field");
        return switch (Objects.requireNonNull(unit.get())) {
            case DEG -> 1;
            case RAD -> 0.01;
            default -> 0.1;
        };
    }

    /** Display-only unit for quantities such as percentages whose stored values are unitless. */
    FormulaField displayUnit(@NonNull String label) {
        if (unit == null) throw new IllegalStateException("This is a text field");
        displayUnit = label;
        refreshFormula();
        return this;
    }

    void validateWith(@NonNull java.util.function.DoubleConsumer validation) {
        this.validation = validation;
        refreshFormula();
    }

    /** Plugin payloads use their declared unit rather than the CAD model's canonical unit. */
    double declaredValue() {
        if (unit == null) throw new IllegalStateException("This is a text field");
        var selected = Objects.requireNonNull(unit.get());
        return selected.displayValue(Measurements.quantity(value(), selected.canonicalUnit()));
    }

    int integer() {
        double result = value();
        if (result != Math.rint(result) || result < Integer.MIN_VALUE || result > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    translations.translate("gui.expected_a_whole_number_in_the_supported_range"));
        }
        return (int) result;
    }

    /** Evaluated input for one-time settings; getText() always retains the user's formula. */
    String evaluatedText() {
        return unit == null
                ? parameters.get().text(getText())
                : Expressions.format(Measurements.quantity(value(), Measurements.ONE));
    }

    void refreshFormula() {
        boolean formula = Expressions.isFormula(getText())
                || (unit != null && !getText().isBlank() && !Expressions.isNumber(getText()));
        pseudoClassStateChanged(FORMULA, formula);
        try {
            var selected = unit == null ? null : unit.get();
            String label = displayUnit;
            if (label == null) {
                label = switch (selected) {
                    case null -> "";
                    case NUMBER -> "";
                    case DEG -> "°";
                    default -> selected.toString();
                };
            }
            if (!unitSuffix.get().equals(label)) {
                unitSuffix.set(label);
                requestLayout();
            }
            String result = getText().isBlank() ? "" : evaluatedText();
            String suffix = unit == null || unit.get() == ParameterUnit.NUMBER
                    ? ""
                    : " " + unit.get().canonicalUnit();
            preview.setText(expressionType.mark
                    + "  "
                    + (formula
                            ? translations.translate("formula.result", result, suffix)
                            : translations.translate("gui.supports_formulas_and_parameters")));
            pseudoClassStateChanged(INVALID, false);
        } catch (RuntimeException failure) {
            preview.setText(expressionType.mark + "  " + failure.getMessage());
            pseudoClassStateChanged(INVALID, true);
        }
    }

    @Override
    protected Skin<?> createDefaultSkin() {
        return new TextFieldSkin(this) {
            private final Label mark = new Label(expressionType.mark);
            private final Button help = new Button("?");
            private final Label minimum = new Label();
            private final Label maximum = new Label();
            private final Label unitLabel = new Label();
            private final javafx.scene.text.Text textMeasure = new javafx.scene.text.Text();

            {
                mark.getStyleClass().add("formula-mark");
                mark.setManaged(false);
                mark.setMouseTransparent(true);
                minimum.textProperty()
                        .bind(Bindings.createStringBinding(() -> bounds.get().minimumHint(), bounds));
                maximum.textProperty()
                        .bind(Bindings.createStringBinding(() -> bounds.get().maximumHint(), bounds));
                minimum.getStyleClass().add("formula-minimum");
                maximum.getStyleClass().add("formula-maximum");
                unitLabel.getStyleClass().add("formula-unit");
                unitLabel.textProperty().bind(unitSuffix);
                for (var hint : java.util.List.of(minimum, maximum, unitLabel)) {
                    hint.getStyleClass().add("formula-bound");
                    hint.setManaged(false);
                    hint.setMouseTransparent(true);
                }
                help.getStyleClass().add("formula-help");
                help.setManaged(false);
                var title = translations.translate("formula.helpTitle") + " (F1)";
                help.setAccessibleText(title);
                help.setTooltip(UiHelp.tooltip(title));
                help.setOnAction(event -> showHelp());
                getChildren().addAll(mark, minimum, unitLabel, maximum, help);
            }

            private double hintWidth(Label hint) {
                return hint.getText().isEmpty() ? 0 : snapSizeX(hint.prefWidth(-1)) + 6;
            }

            @Override
            protected double computePrefWidth(
                    double height, double topInset, double rightInset, double bottomInset, double leftInset) {
                return super.computePrefWidth(height, topInset, rightInset, bottomInset, leftInset)
                        + hintWidth(minimum)
                        + hintWidth(unitLabel)
                        + hintWidth(maximum);
            }

            @Override
            protected void layoutChildren(double x, double y, double width, double height) {
                double left = hintWidth(minimum);
                double right = hintWidth(maximum);
                double unitWidth = hintWidth(unitLabel);
                double editorWidth = Math.max(0, width - left - unitWidth - right);
                if (right > 0 || unitWidth > 0) {
                    textMeasure.setFont(getFont());
                    textMeasure.setText(getText());
                    editorWidth = Math.min(
                            editorWidth, snapSizeX(textMeasure.getLayoutBounds().getWidth()) + 2);
                }
                super.layoutChildren(x + left, y, editorWidth, height);
                minimum.resizeRelocate(x, y, Math.max(0, left - 6), height);
                unitLabel.resizeRelocate(x + left + editorWidth + 6, y, Math.max(0, unitWidth - 6), height);
                maximum.resizeRelocate(x + left + editorWidth + unitWidth + 6, y, Math.max(0, right - 6), height);
                mark.resizeRelocate(7, y, 18, height);
                help.resizeRelocate(getWidth() - 25, y + (height - 18) / 2, 18, 18);
            }
        };
    }
}
