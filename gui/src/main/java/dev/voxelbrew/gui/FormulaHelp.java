package dev.voxelbrew.gui;

import dev.voxelbrew.api.i18n.TranslationService;
import dev.voxelbrew.api.model.parameters.Expressions;
import javafx.geometry.Side;
import javafx.scene.control.*;
import lombok.RequiredArgsConstructor;

/** Keyboard-accessible reference shared by numeric and text fields. */
@RequiredArgsConstructor
final class FormulaHelp {
    private final FormulaField field;
    private final TranslationService translations;
    private final ContextMenu popup = new ContextMenu();

    void show() {
        if (popup.isShowing()) {
            popup.hide();
            return;
        }
        boolean numeric = field.expressionType() == FormulaField.ExpressionType.NUMBER;
        String input = field.getText();
        int anchor = field.getAnchor();
        int caret = field.getCaretPosition();
        int start = Math.min(anchor, caret);
        int end = Math.max(anchor, caret);
        field.requestFocus();
        field.selectRange(anchor, caret);

        var title = new Label((numeric ? "ƒx   " : "ƒs   ") + translations.translate("formula.helpTitle"));
        title.getStyleClass().add("formula-help-title");
        var intro = helpText(numeric ? "formula.numberSyntax" : "formula.textSyntax");
        var conversion = helpText(numeric ? "formula.numericConversion" : "formula.textConversion");

        var constants = new Menu(translations.translate("formula.insertConstant"));
        boolean formula = Expressions.isFormula(input);
        boolean editable = field.isEditable()
                && (numeric || (formula && start > input.indexOf('=')))
                && !ParameterSuggestions.insideQuotedString(input, start)
                && !ParameterSuggestions.insideQuotedString(input, end);
        for (var symbol : new String[][] {
            {"π", "pi", "π ≈ 3.14159"},
            {"τ", "tau", "τ = 2π"},
            {"φ", "phi", "φ = (1 + √5) / 2"},
            {"e", "e", "e ≈ 2.71828"}
        }) {
            var item = new MenuItem(symbol[1] + "     " + symbol[2]);
            item.setId("formula-insert-" + symbol[1]);
            item.setDisable(!editable);
            item.setOnAction(event -> {
                field.replaceText(start, end, symbol[0]);
                field.requestFocus();
                field.positionCaret(start + symbol[0].length());
            });
            constants.getItems().add(item);
        }
        var reference = new Menu(translations.translate("formula.reference"));
        reference
                .getItems()
                .addAll(
                        reference("arithmetic", "+   -   *   /   %   ^   ( )", "-7 % 5 → 3"),
                        reference(
                                "rounding",
                                "abs   floor   ceil / ceiling   round   min   max   clamp",
                                "ceiling(-2.3) → -2"),
                        reference("logs", "log(1000) → 3      ln(e) → 1", "sqrt($foo)"),
                        reference("angles", "sin   cos   tan   asin   acos   atan", "(tau rad) / $count"),
                        reference(
                                "units",
                                "mm   cm   m   in   ft   deg   rad",
                                "convertUnit($foo, m)     formatUnit($foo, m)"),
                        reference("geometry", "$side * sqrt(2)      $side * sqrt(3)", "(360 deg) / phi^2"));
        popup.getItems()
                .setAll(
                        new CustomMenuItem(title, false),
                        new CustomMenuItem(intro, false),
                        new CustomMenuItem(conversion, false),
                        new SeparatorMenuItem(),
                        constants,
                        reference);
        var root = field.getScene().getRoot();
        popup.getStyleClass().removeAll("light", "dark");
        popup.getStyleClass()
                .add(root.getStyleClass().contains("dark") || root.lookup(".dark") != null ? "dark" : "light");
        popup.getScene().getStylesheets().setAll(field.getScene().getStylesheets());
        popup.show(field, Side.BOTTOM, 0, 2);
    }

    private javafx.scene.layout.VBox helpText(String key) {
        var content = new javafx.scene.layout.VBox(3);
        content.setPrefWidth(345);
        for (String line : translations.translate(key).split("\\n")) {
            var label = new javafx.scene.text.Text(line);
            label.setWrappingWidth(345);
            label.getStyleClass().add("formula-help-line");
            content.getChildren().add(label);
        }
        return content;
    }

    private Menu reference(String key, String syntax, String example) {
        var section = new Menu(translations.translate("formula." + key));
        section.getItems().add(new CustomMenuItem(new Label(syntax + "\n" + example), false));
        return section;
    }
}
