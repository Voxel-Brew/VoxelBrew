package dev.voxelbrew.gui;

import dev.voxelbrew.api.i18n.TranslationService;
import dev.voxelbrew.api.model.Feature;
import dev.voxelbrew.api.model.parameters.*;
import dev.voxelbrew.engine.parameters.*;
import java.util.*;
import java.util.function.Function;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Window;

/**
 * One cancellable parameter/configuration transaction, usable from the workbench or sketch draft.
 */
final class ParameterDialog {
    private final TranslationService translations;

    private final List<Feature> features;

    private final TableView<ParameterSet.Parameter> parameters = new TableView<>();

    private final CheckBox showBuiltins = new CheckBox();

    private final TableView<String> configurations = new TableView<>();

    private final TableView<FeatureDimensions.Dimension> dimensions = new TableView<>();

    private final TextField name = textField("parameter-name"), configName = textField("configuration-name");
    private final FormulaField expression, stringExpression, comment, override, binding;

    private final ComboBox<ParameterUnit> unit = new ComboBox<>();

    private final ComboBox<ParameterSet.Parameter> overrideParameter = new ComboBox<>();

    private final Label error = new Label(), active = new Label();

    private ParameterSet draft;

    private boolean refreshing;

    private String selectedParameter;

    static Optional<ParameterSet> show(
            Window owner, List<Feature> features, ParameterSet initial, TranslationService translations) {
        var editor = new ParameterDialog(features, initial, translations);
        var dialog = new Dialog<ParameterSet>();
        dialog.setTitle(
                translations.translate("gui.parameters_and_configurations").orElseThrow());
        dialog.getDialogPane().setContent(editor.content());
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.APPLY, ButtonType.CANCEL);
        dialog.getDialogPane().lookupButton(ButtonType.APPLY).setId("parameters-apply");
        dialog.getDialogPane().lookupButton(ButtonType.CANCEL).setId("parameters-cancel");
        dialog.getDialogPane().lookupButton(ButtonType.APPLY).addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            try {
                editor.draft.validate();
            } catch (RuntimeException failure) {
                editor.error.setText(failure.getMessage());
                e.consume();
            }
        });
        dialog.setResultConverter(b -> b == ButtonType.APPLY ? editor.draft : null);
        return Dialogs.show(dialog, owner, translations);
    }

    private static TextField textField(String id) {
        var field = new TextField();
        field.setId(id);
        return field;
    }

    private static <T> TableColumn<T, String> column(String title, double width, Function<T, String> value) {
        var c = new TableColumn<T, String>(title);
        c.setPrefWidth(width);
        c.setCellValueFactory(v -> new ReadOnlyStringWrapper(value.apply(v.getValue())));
        return c;
    }

    static List<ParameterSet.Parameter> groupedParameters(ParameterSet set) {
        return set.allParameters().stream()
                .sorted(Comparator.comparing((ParameterSet.Parameter p) -> {
                            int dot = p.name().indexOf('.');
                            return dot < 0 ? "" : p.name().substring(0, dot);
                        })
                        .thenComparing(ParameterSet.Parameter::name))
                .toList();
    }

    private ParameterDialog(List<Feature> features, ParameterSet initial, TranslationService translations) {
        this.translations = translations;
        this.features = List.copyOf(features);
        draft = initial;
        expression = field("parameter-expression");
        stringExpression = field("parameter-string-expression");
        comment = field("parameter-comment");
        override = field("configuration-expression");
        binding = field("parameter-binding-expression");
    }

    private FormulaField field(String id) {
        var field = switch (id) {
            case "parameter-expression" ->
                FormulaField.number(
                        "",
                        () -> draft,
                        () -> unit.getValue() == null ? ParameterUnit.MM : unit.getValue(),
                        translations);
            case "configuration-expression" ->
                FormulaField.number(
                        "",
                        () -> draft.select(selectedConfig()),
                        () -> requiredParameter().unit(),
                        translations);
            case "parameter-binding-expression" ->
                FormulaField.number(
                        "",
                        () -> draft,
                        () -> {
                            var selected = dimensions.getSelectionModel().getSelectedItem();
                            return selected == null ? ParameterUnit.MM : selected.unit();
                        },
                        translations);
            default -> FormulaField.text("", () -> draft, translations);
        };
        field.setId(id);
        return field;
    }

    private Button button(String label, String id, Runnable action) {
        var b = new Button(label);
        b.setId(id);
        if (id.equals("parameter-save") || id.equals("parameter-remove"))
            b.disableProperty()
                    .bind(javafx.beans.binding.Bindings.createBooleanBinding(
                            () -> parameters.getSelectionModel().getSelectedItem() != null
                                    && parameters
                                            .getSelectionModel()
                                            .getSelectedItem()
                                            .name()
                                            .startsWith("vb."),
                            parameters.getSelectionModel().selectedItemProperty()));
        UiHelp.install(b, translations);
        b.setOnAction(e -> attempt(action));
        return b;
    }

    private void attempt(Runnable action) {
        try {
            action.run();
            error.setText("");
        } catch (RuntimeException failure) {
            error.setText(failure.getMessage());
        }
    }

    private void update(ParameterSet next) {
        FeatureDimensions.validateBindings(features, next);
        draft = next;
        refresh();
    }

    private Pane content() {
        showBuiltins.setId("parameters-show-builtins");
        showBuiltins.setText(translations.translate("parameters.showBuiltins").orElseThrow());
        UiHelp.install(showBuiltins, translations);
        showBuiltins.setSelected(true);
        showBuiltins.selectedProperty().addListener((o, before, show) -> {
            var selected = parameters.getSelectionModel().getSelectedItem();
            if (!show && selected != null && selected.name().startsWith("vb.")) {
                selectedParameter = null;
                parameters.getSelectionModel().clearSelection();
                name.clear();
                expression.clear();
                stringExpression.clear();
                comment.clear();
            }
            refresh();
        });
        parameters.setId("parameter-table");
        parameters.setPlaceholder(
                new Label(translations.translate("parameters.empty").orElseThrow()));
        parameters.setPrefHeight(175);
        parameters
                .getColumns()
                .add(column(translations.translate("gui.name").orElseThrow(), 110, ParameterSet.Parameter::name));
        parameters
                .getColumns()
                .add(column(
                        translations.translate("gui.unit").orElseThrow(),
                        65,
                        p -> BuiltinParameters.isString(p.name())
                                ? "string"
                                : p.unit().toString()));
        parameters
                .getColumns()
                .add(column(
                        translations.translate("gui.expression").orElseThrow(),
                        180,
                        p -> draft.expression(p, draft.active())));
        parameters.getColumns().add(column(translations.translate("gui.value").orElseThrow(), 90, p -> {
            try {
                if (p.name().startsWith("vb."))
                    return Expressions.format(draft.builtins().value(p.name()));
                return String.format(
                        Locale.ROOT,
                        "%.6g",
                        p.unit().displayValue(draft.values().get(p.name())));
            } catch (RuntimeException failure) {
                return translations.translate("gui.error").orElseThrow();
            }
        }));
        parameters
                .getColumns()
                .add(column(translations.translate("gui.comment").orElseThrow(), 130, ParameterSet.Parameter::comment));
        parameters.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        parameters.getSelectionModel().selectedItemProperty().addListener((o, a, p) -> {
            boolean builtin = p != null && p.name().startsWith("vb.");
            boolean string = p != null && BuiltinParameters.isString(p.name());
            stringExpression.setVisible(string);
            stringExpression.setManaged(string);
            expression.setVisible(!string);
            expression.setManaged(!string);
            unit.setPromptText(string ? "string" : "");
            if (!string && unit.getValue() == null) unit.setValue(ParameterUnit.MM);
            for (var control : List.of(name, unit, expression, comment)) control.setDisable(builtin);
            if (builtin) {
                name.setText(p.name());
                unit.setValue(string ? null : p.unit());
                if (string) stringExpression.setText(p.expression());
                else expression.setText(p.expression());
                comment.setText(p.comment());
            }
            if (refreshing || p == null) return;
            if (p.name().startsWith("vb.")) return;
            selectedParameter = p.id();
            name.setText(p.name());
            unit.setValue(p.unit());
            expression.setText(draft.expression(p, draft.active()));
            comment.setText(Expressions.literalText(p.comment()));
        });
        unit.setId("parameter-unit");
        unit.valueProperty().addListener(o -> expression.refreshFormula());
        unit.getItems().setAll(ParameterUnit.values());
        unit.setValue(ParameterUnit.MM);
        stringExpression.setEditable(false);
        stringExpression.setVisible(false);
        stringExpression.setManaged(false);
        var expressionFields = new StackPane(expression, stringExpression);
        var fields = new GridPane();
        fields.setHgap(8);
        fields.setVgap(5);
        fields.addRow(
                0,
                new Label(translations.translate("gui.name").orElseThrow()),
                name,
                new Label(translations.translate("gui.unit").orElseThrow()),
                unit);
        fields.addRow(
                1,
                new Label(translations.translate("gui.expression").orElseThrow()),
                expressionFields,
                new Label(translations.translate("gui.comment").orElseThrow()),
                comment);
        var parameterButtons = new FlowPane(
                6,
                6,
                button(translations.translate("gui.add_update").orElseThrow(), "parameter-save", () -> {
                    ParameterSet next = draft;
                    if (selectedParameter != null
                            && !next.parameter(selectedParameter)
                                    .name()
                                    .equals(name.getText().strip()))
                        next = next.rename(selectedParameter, name.getText().strip());
                    update(next.put(
                            name.getText().strip(),
                            unit.getValue(),
                            expression.getText().strip(),
                            comment.evaluatedText()));
                }),
                button(translations.translate("action.new").orElseThrow(), "parameter-new", () -> {
                    selectedParameter = null;
                    parameters.getSelectionModel().clearSelection();
                    name.clear();
                    expression.clear();
                    comment.clear();
                    name.requestFocus();
                }),
                button(translations.translate("gui.remove").orElseThrow(), "parameter-remove", () -> {
                    update(draft.remove(name.getText().strip()));
                    selectedParameter = null;
                }));
        var parameterPane = new VBox(
                7,
                new Label(translations
                        .translate(
                                "gui.expressions_use_names_arithmetic_and_units_edits_affect_the_active_configuration")
                        .orElseThrow()),
                showBuiltins,
                parameters,
                fields,
                parameterButtons);

        configurations.setId("configuration-table");
        configurations.setPrefHeight(175);
        configurations.setEditable(true);
        configurations.getSelectionModel().selectedItemProperty().addListener((o, a, id) -> {
            if (refreshing || id == null) return;
            configName.setText(Expressions.literalText(configLabel(id)));
            updateOverride();
        });
        overrideParameter.setId("configuration-parameter");
        overrideParameter.setPrefWidth(145);
        overrideParameter.valueProperty().addListener((o, a, p) -> {
            if (!refreshing) updateOverride();
            override.refreshFormula();
        });
        var configButtons = new FlowPane(
                6,
                6,
                new Label(translations.translate("gui.name").orElseThrow()),
                configName,
                button(
                        translations.translate("gui.add").orElseThrow(),
                        "configuration-add",
                        () -> update(draft.addConfiguration(configName.getText().strip(), false))),
                button(
                        translations.translate("gui.duplicate_active").orElseThrow(),
                        "configuration-copy",
                        () -> update(draft.addConfiguration(configName.getText().strip(), true))),
                button(
                        translations.translate("gui.rename").orElseThrow(),
                        "configuration-rename",
                        () -> update(draft.renameConfiguration(
                                selectedConfig(), configName.getText().strip()))),
                button(
                        translations.translate("gui.remove").orElseThrow(),
                        "configuration-remove",
                        () -> update(draft.removeConfiguration(selectedConfig()))),
                button(
                        translations.translate("gui.activate_selected").orElseThrow(),
                        "configuration-activate",
                        () -> update(draft.select(selectedConfig()))));
        var overrides = new FlowPane(
                6,
                6,
                overrideParameter,
                override,
                button(
                        translations.translate("gui.set_override").orElseThrow(),
                        "configuration-set",
                        () -> update(draft.override(requiredParameter().id(), selectedConfig(), override.getText()))),
                button(
                        translations.translate("gui.inherit_default").orElseThrow(),
                        "configuration-inherit",
                        () -> update(draft.override(requiredParameter().id(), selectedConfig(), ""))));
        var configurationPane = new VBox(
                7,
                new Label(translations
                        .translate(
                                "gui.rows_are_variants_double_click_a_cell_to_edit_its_expression_blank_inherits_default")
                        .orElseThrow()),
                configurations,
                configButtons,
                overrides);

        dimensions.setId("parameter-dimensions");
        dimensions.setPlaceholder(
                new Label(translations.translate("dimensions.empty").orElseThrow()));
        dimensions.setPrefHeight(205);
        dimensions
                .getColumns()
                .add(column(
                        translations.translate("gui.feature_dimension").orElseThrow(),
                        280,
                        d -> d.target().toString()));
        dimensions
                .getColumns()
                .add(column(
                        translations.translate("gui.unit").orElseThrow(),
                        60,
                        d -> d.unit().toString()));
        dimensions
                .getColumns()
                .add(column(
                        translations.translate("gui.expression").orElseThrow(), 220, d -> draft.binding(d.target())));
        dimensions
                .getItems()
                .setAll(features.stream()
                        .flatMap(f -> FeatureDimensions.list(f).stream())
                        .toList());
        dimensions.getSelectionModel().selectedItemProperty().addListener((o, a, d) -> {
            if (d != null) binding.setText(draft.binding(d.target()));
            binding.refreshFormula();
        });
        var dimensionsPane = new VBox(
                7,
                new Label(translations
                        .translate(
                                "gui.bind_driving_sketch_dimensions_extrusion_distances_and_offset_planes_to_parameters")
                        .orElseThrow()),
                dimensions,
                new Label(translations
                        .translate("gui.expression_blank_restores_the_stored_numeric_value")
                        .orElseThrow()),
                binding,
                button(translations.translate("gui.bind_dimension").orElseThrow(), "parameter-bind", () -> {
                    var chosen = dimensions.getSelectionModel().getSelectedItem();
                    if (chosen == null)
                        throw new IllegalArgumentException(
                                translations.translate("gui.select_a_dimension").orElseThrow());
                    var doc = new dev.voxelbrew.api.model.CadDocument(
                            "parameter-editor",
                            features,
                            features.size(),
                            dev.voxelbrew.api.model.printing.PrintSetup.empty(),
                            draft);
                    update(FeatureDimensions.bind(doc, chosen.target(), binding.getText()));
                }));
        var tabs = new TabPane(
                new Tab(translations.translate("gui.parameters").orElseThrow(), parameterPane),
                new Tab(translations.translate("gui.configurations").orElseThrow(), configurationPane),
                new Tab(translations.translate("gui.dimensions").orElseThrow(), dimensionsPane));
        tabs.setId("parameters-tabs");
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.setPrefHeight(370);
        error.setWrapText(true);
        error.getStyleClass().add("error");
        error.setId("parameters-error");
        active.setId("parameters-active");
        var result = new VBox(8, active, tabs, error);
        refresh();
        return result;
    }

    private String selectedConfig() {
        String id = configurations.getSelectionModel().getSelectedItem();
        if (id == null)
            throw new IllegalArgumentException(
                    translations.translate("gui.select_a_configuration_row").orElseThrow());
        return id;
    }

    private String configLabel(String id) {
        return id.isEmpty()
                ? translations.translate("configuration.default").orElseThrow()
                : draft.configurations().stream()
                        .filter(c -> c.id().equals(id))
                        .findFirst()
                        .orElseThrow()
                        .name();
    }

    private ParameterSet.Parameter requiredParameter() {
        var p = overrideParameter.getValue();
        if (p == null)
            throw new IllegalArgumentException(
                    translations.translate("gui.choose_a_parameter").orElseThrow());
        return p;
    }

    private void updateOverride() {
        var p = overrideParameter.getValue();
        var id = configurations.getSelectionModel().getSelectedItem();
        if (p != null && id != null) override.setText(draft.expression(p, id));
    }

    private void refresh() {
        refreshing = true;
        try {
            var config = configurations.getSelectionModel().getSelectedItem();
            var chosen = overrideParameter.getValue();
            parameters
                    .getItems()
                    .setAll(groupedParameters(draft).stream()
                            .filter(p -> showBuiltins.isSelected() || !p.name().startsWith("vb."))
                            .toList());
            if (selectedParameter != null)
                parameters
                        .getSelectionModel()
                        .select(draft.parameters().stream()
                                .filter(p -> p.id().equals(selectedParameter))
                                .findFirst()
                                .orElse(null));
            overrideParameter.getItems().setAll(draft.parameters());
            overrideParameter.setValue(
                    chosen == null
                            ? draft.parameters().stream().findFirst().orElse(null)
                            : draft.parameters().stream()
                                    .filter(p -> p.id().equals(chosen.id()))
                                    .findFirst()
                                    .orElse(null));
            configurations.getColumns().clear();
            configurations
                    .getColumns()
                    .add(column(
                            translations.translate("gui.configuration").orElseThrow(),
                            160,
                            id -> (draft.active().equals(id) ? "● " : "") + configLabel(id)));
            for (var p : draft.parameters()) {
                var c = ParameterDialog.<String>column(
                        p.name() + " (" + p.unit() + ")", 130, id -> draft.expression(p, id));
                c.setCellFactory(column -> new TableCell<>() {
                    private FormulaField editor;

                    @Override
                    public void startEdit() {
                        super.startEdit();
                        if (!isEditing()) return;
                        editor = FormulaField.number(
                                getItem(), () -> draft.select(getTableRow().getItem()), p.unit(), translations);
                        editor.setOnAction(e -> commitEdit(editor.getText()));
                        editor.setOnKeyPressed(e -> {
                            if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) cancelEdit();
                        });
                        setText(null);
                        setGraphic(editor);
                        editor.requestFocus();
                        editor.selectAll();
                    }

                    @Override
                    public void cancelEdit() {
                        super.cancelEdit();
                        setGraphic(null);
                        setText(getItem());
                    }

                    @Override
                    protected void updateItem(String text, boolean empty) {
                        super.updateItem(text, empty);
                        setText(empty || isEditing() ? null : text);
                        setGraphic(empty || !isEditing() ? null : editor);
                    }
                });
                c.setOnEditCommit(event ->
                        attempt(() -> update(draft.override(p.id(), event.getRowValue(), event.getNewValue()))));
                configurations.getColumns().add(c);
            }
            configurations.getItems().setAll("");
            draft.configurations().forEach(c -> configurations.getItems().add(c.id()));
            configurations
                    .getSelectionModel()
                    .select(config != null && configurations.getItems().contains(config) ? config : draft.active());
            active.setText(translations
                    .translate(
                            "configuration.active",
                            draft.active().isEmpty()
                                    ? translations
                                            .translate("configuration.default")
                                            .orElseThrow()
                                    : draft.activeName())
                    .orElseThrow());
            dimensions.refresh();
        } finally {
            refreshing = false;
        }
        updateOverride();
    }
}
