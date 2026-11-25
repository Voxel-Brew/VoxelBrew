package dev.voxelbrew.gui;

import dev.voxelbrew.api.i18n.TranslationService;
import dev.voxelbrew.api.model.Feature;
import dev.voxelbrew.api.model.parameters.*;
import dev.voxelbrew.engine.parameters.*;
import java.util.*;
import java.util.function.Function;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.*;
import javafx.stage.Window;

/**
 * One cancellable parameter/configuration transaction, usable from the workbench or sketch draft.
 */
final class ParameterDialog {
    private static final javafx.css.PseudoClass INVALID = javafx.css.PseudoClass.getPseudoClass("invalid");

    private final TranslationService translations;

    private final List<Feature> features;

    private final TableView<ParameterSet.Parameter> parameters = new TableView<>();

    private final CheckBox showBuiltins = new CheckBox();

    private final TableView<String> configurations = new TableView<>();

    private final TableView<FeatureDimensions.Dimension> dimensions = new TableView<>();

    private final TextField name = textField("parameter-name"), configName = textField("configuration-name");
    private final FormulaField expression, comment, override, binding;

    private final ComboBox<ParameterUnit> unit = new ComboBox<>();

    private final ComboBox<ParameterSet.Parameter> overrideParameter = new ComboBox<>();

    private final Label error = new Label(), active = new Label();

    private ParameterSet draft;

    private boolean refreshing;

    private RowEditor rowEditor;

    private final class RowEditor {
        final ParameterSet.Parameter parameter;
        final TextField rowName = textField("parameter-row-name");
        final ComboBox<ParameterUnit> rowUnit = new ComboBox<>();
        final FormulaField rowExpression;
        final FormulaField rowComment;

        RowEditor(ParameterSet.Parameter parameter) {
            this.parameter = parameter;
            rowName.setText(parameter.name());
            rowUnit.setId("parameter-row-unit");
            rowUnit.getItems().setAll(ParameterUnit.values());
            rowUnit.setValue(parameter.unit());
            rowUnit.setMaxWidth(Double.MAX_VALUE);
            rowExpression = FormulaField.number(
                    draft.expression(parameter, draft.active()), () -> draft, rowUnit::getValue, translations);
            rowExpression.setId("parameter-row-expression");
            rowExpression.onUnitSelected(rowUnit::setValue);
            rowUnit.valueProperty().addListener(o -> rowExpression.refreshFormula());
            rowComment = FormulaField.text(Expressions.literalText(parameter.comment()), () -> draft, translations);
            rowComment.setId("parameter-row-comment");
            watchName(rowName, parameter);
            rowExpression.focusedProperty().addListener((o, before, focused) -> {
                if (!focused && rowEditor == this) {
                    attempt(() -> draft.value(rowExpression.expression(), rowUnit.getValue()));
                }
            });
            for (var editor : List.of(rowName, rowExpression, rowComment)) {
                editor.setOnAction(event -> {
                    event.consume();
                    attempt(ParameterDialog.this::saveRow);
                });
            }
            for (var editor : List.of(rowName, rowUnit, rowExpression, rowComment)) {
                editor.addEventHandler(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
                    if (event.getCode() == KeyCode.ESCAPE) {
                        event.consume();
                        cancelRow();
                    }
                });
            }
        }
    }

    static Optional<ParameterSet> show(
            Window owner, List<Feature> features, ParameterSet initial, TranslationService translations) {
        var editor = new ParameterDialog(features, initial, translations);
        var dialog = new Dialog<ParameterSet>();
        dialog.setTitle(translations.translate("gui.parameters_and_configurations"));
        dialog.getDialogPane().setPrefWidth(840);
        dialog.getDialogPane().setContent(editor.content());
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.APPLY, ButtonType.CANCEL);
        var apply = dialog.getDialogPane().lookupButton(ButtonType.APPLY);
        apply.setId("parameters-apply");
        apply.getStyleClass().add("parameter-primary-action");
        dialog.getDialogPane().lookupButton(ButtonType.CANCEL).setId("parameters-cancel");
        apply.addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            try {
                editor.saveRow();
                editor.draft.validate();
            } catch (RuntimeException failure) {
                editor.error.setText(failure.getMessage());
                e.consume();
            }
        });
        dialog.setResultConverter(b -> b == ButtonType.APPLY ? editor.draft : null);
        return Dialogs.show(dialog, owner, translations);
    }

    private static Button rowAction() {
        var button = new Button();
        button.getStyleClass().add("parameter-row-action");
        button.setTooltip(new Tooltip());
        return button;
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
        return ParameterSuggestions.availableParameters(set).stream()
                .sorted(Comparator.comparing((ParameterSet.Parameter p) -> {
                            int dot = p.name().indexOf('.');
                            return dot < 0 ? "" : p.name().substring(0, dot);
                        })
                        .thenComparing(ParameterSet.Parameter::name))
                .toList();
    }

    static ParameterSet removeParameter(ParameterSet set, String name, TranslationService translations) {
        for (var parameter : set.parameters()) {
            if (parameter.name().equals(name)) continue;
            checkReference(name, parameter.name(), parameter.expression(), translations);
            for (var configuration : set.configurations()) {
                var override = configuration.overrides().get(parameter.id());
                if (override != null) {
                    checkReference(name, configuration.name() + " / " + parameter.name(), override, translations);
                }
            }
        }
        for (var binding : set.bindings()) {
            checkReference(name, binding.target().toString(), binding.expression(), translations);
        }
        return set.remove(name);
    }

    static ParameterSet freezeSketchText(ParameterSet set, String name) {
        var next = set;
        for (var binding : set.bindings()) {
            String field = binding.target().field();
            if (FeatureText.isField(field) && field.endsWith(":content") && references(binding.expression(), name)) {
                String value = set.text(binding.expression());
                next = next.bind(binding.target(), "=\"" + value.replace("\"", "\"\"") + "\"");
            }
        }
        return next;
    }

    private static boolean references(String expression, String name) {
        // Use the formula engine's token rules so quoted text and longer names are left alone.
        return !expression.equals(Expressions.rename(expression, name, ""));
    }

    private static void checkReference(String name, String owner, String expression, TranslationService translations) {
        if (references(expression, name)) {
            throw new IllegalArgumentException(
                    translations.translate("parameters.removeReferenced", name, owner, expression));
        }
    }

    private ParameterDialog(List<Feature> features, ParameterSet initial, TranslationService translations) {
        this.translations = translations;
        this.features = List.copyOf(features);
        draft = initial;
        expression = field("parameter-expression");
        expression.onUnitSelected(unit::setValue);
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
                        () -> overrideParameter.getValue() == null
                                ? null
                                : overrideParameter.getValue().unit(),
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

    private void requestRemoval(String parameterName) {
        var frozen = freezeSketchText(draft, parameterName);
        var next = removeParameter(frozen, parameterName, translations);
        if (frozen != draft) {
            var confirm = new Alert(
                    Alert.AlertType.CONFIRMATION,
                    translations.translate("parameters.freezeSketchText", parameterName, configLabel(draft.active())),
                    ButtonType.YES,
                    ButtonType.CANCEL);
            confirm.setHeaderText(translations.translate("gui.remove"));
            if (Dialogs.show(confirm, parameters.getScene().getWindow(), translations)
                            .orElse(ButtonType.CANCEL)
                    != ButtonType.YES) return;
        }
        update(next);
    }

    private void update(ParameterSet next) {
        FeatureDimensions.validateBindings(features, next);
        draft = next;
        refresh();
    }

    private Pane content() {
        configureParameterTable();
        unit.setMaxWidth(Double.MAX_VALUE);
        watchName(name, null);
        expression.focusedProperty().addListener((o, before, focused) -> {
            if (!focused && !expression.isDisabled() && !expression.expression().isBlank()) {
                attempt(() -> draft.value(expression.expression(), unit.getValue()));
            }
        });
        unit.setId("parameter-unit");
        unit.valueProperty().addListener(o -> expression.refreshFormula());
        unit.getItems().setAll(ParameterUnit.values());
        unit.setValue(ParameterUnit.MM);
        var fields = new GridPane();
        fields.setHgap(8);
        fields.setVgap(5);
        var inputColumn = new ColumnConstraints(0, 0, Double.MAX_VALUE);
        inputColumn.setHgrow(Priority.ALWAYS);
        fields.getColumnConstraints()
                .setAll(new ColumnConstraints(), inputColumn, new ColumnConstraints(), inputColumn);
        fields.addRow(
                0,
                new Label(translations.translate("gui.name")),
                name,
                new Label(translations.translate("gui.unit")),
                unit);
        fields.addRow(
                1,
                new Label(translations.translate("gui.expression")),
                expression,
                new Label(translations.translate("gui.comment")),
                comment);
        var save = button(translations.translate("gui.add"), "parameter-save", this::addParameter);
        save.getStyleClass().add("parameter-primary-action");
        var parameterButtons = new FlowPane(
                6, 6, save, button("↺ " + translations.translate("gui.clear"), "parameter-new", this::clearFields));
        var parameterPane = new VBox(
                7,
                new Label(translations.translate(
                        "gui.expressions_use_names_arithmetic_and_units_edits_affect_the_active_configuration")),
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
                new Label(translations.translate("gui.name")),
                configName,
                button(
                        translations.translate("gui.add"),
                        "configuration-add",
                        () -> update(draft.addConfiguration(configName.getText().strip(), false))),
                button(
                        translations.translate("gui.duplicate_active"),
                        "configuration-copy",
                        () -> update(draft.addConfiguration(configName.getText().strip(), true))),
                button(
                        translations.translate("gui.rename"),
                        "configuration-rename",
                        () -> update(draft.renameConfiguration(
                                selectedConfig(), configName.getText().strip()))),
                button(
                        translations.translate("gui.remove"),
                        "configuration-remove",
                        () -> update(draft.removeConfiguration(selectedConfig()))),
                button(
                        translations.translate("gui.activate_selected"),
                        "configuration-activate",
                        () -> update(draft.select(selectedConfig()))));
        var overrides = new FlowPane(
                6,
                6,
                overrideParameter,
                override,
                button(
                        translations.translate("gui.set_override"),
                        "configuration-set",
                        () -> update(
                                draft.override(requiredParameter().id(), selectedConfig(), override.expression()))),
                button(
                        translations.translate("gui.inherit_default"),
                        "configuration-inherit",
                        () -> update(draft.override(requiredParameter().id(), selectedConfig(), ""))));
        var configurationPane = new VBox(
                7,
                new Label(translations.translate(
                        "gui.rows_are_variants_double_click_a_cell_to_edit_its_expression_blank_inherits_default")),
                configurations,
                configButtons,
                overrides);

        dimensions.setId("parameter-dimensions");
        dimensions.setPlaceholder(new Label(translations.translate("dimensions.empty")));
        dimensions.setPrefHeight(205);
        dimensions
                .getColumns()
                .add(column(
                        translations.translate("gui.feature_dimension"),
                        280,
                        d -> d.target().toString()));
        dimensions
                .getColumns()
                .add(column(
                        translations.translate("gui.unit"), 60, d -> d.unit().toString()));
        dimensions
                .getColumns()
                .add(column(translations.translate("gui.expression"), 220, d -> draft.binding(d.target())));
        dimensions
                .getItems()
                .setAll(features.stream()
                        .flatMap(f -> FeatureDimensions.list(f).stream())
                        .toList());
        dimensions.getSelectionModel().selectedItemProperty().addListener((o, a, d) -> {
            if (d != null) binding.setExpression(draft.binding(d.target()));
            binding.refreshFormula();
        });
        var dimensionsPane = new VBox(
                7,
                new Label(translations.translate(
                        "gui.bind_driving_sketch_dimensions_extrusion_distances_and_offset_planes_to_parameters")),
                dimensions,
                new Label(translations.translate("gui.expression_blank_restores_the_stored_numeric_value")),
                binding,
                button(translations.translate("gui.bind_dimension"), "parameter-bind", () -> {
                    var chosen = dimensions.getSelectionModel().getSelectedItem();
                    if (chosen == null) {
                        throw new IllegalArgumentException(translations.translate("gui.select_a_dimension"));
                    }
                    var doc = new dev.voxelbrew.api.model.CadDocument(
                            "parameter-editor",
                            features,
                            features.size(),
                            dev.voxelbrew.api.model.printing.PrintSetup.empty(),
                            draft);
                    update(FeatureDimensions.bind(doc, chosen.target(), binding.expression()));
                }));
        var tabs = new TabPane(
                new Tab(translations.translate("gui.parameters"), parameterPane),
                new Tab(translations.translate("gui.configurations"), configurationPane),
                new Tab(translations.translate("gui.dimensions"), dimensionsPane));
        tabs.getSelectionModel().selectedIndexProperty().addListener((o, before, after) -> {
            if (before.intValue() != 0 || rowEditor == null) return;
            attempt(this::saveRow);
            if (rowEditor != null) tabs.getSelectionModel().select(0);
        });
        save.defaultButtonProperty()
                .bind(tabs.getSelectionModel().selectedIndexProperty().isEqualTo(0));
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
        if (id == null) {
            throw new IllegalArgumentException(translations.translate("gui.select_a_configuration_row"));
        }
        return id;
    }

    private String configLabel(String id) {
        return id.isEmpty()
                ? translations.translate("configuration.default")
                : draft.configurations().stream()
                        .filter(c -> c.id().equals(id))
                        .findFirst()
                        .orElseThrow()
                        .name();
    }

    private ParameterSet.Parameter requiredParameter() {
        var p = overrideParameter.getValue();
        if (p == null) {
            throw new IllegalArgumentException(translations.translate("gui.choose_a_parameter"));
        }
        return p;
    }

    private void updateOverride() {
        var p = overrideParameter.getValue();
        var id = configurations.getSelectionModel().getSelectedItem();
        if (p != null && id != null) override.setExpression(draft.expression(p, id));
    }

    private void refresh() {
        refreshing = true;
        try {
            var config = configurations.getSelectionModel().getSelectedItem();
            var chosen = overrideParameter.getValue();
            var rows = groupedParameters(draft);
            boolean missingBuiltins = rows.size() == draft.parameters().size();
            showBuiltins.setDisable(missingBuiltins);
            showBuiltins.setText(translations.translate(
                    missingBuiltins ? "parameters.builtinsUnavailable" : "parameters.showBuiltins"));
            parameters
                    .getItems()
                    .setAll(rows.stream()
                            .filter(p -> showBuiltins.isSelected() || !p.name().startsWith("vb."))
                            .toList());
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
                            translations.translate("gui.configuration"),
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
                        editor.setOnAction(e -> commitEdit(editor.expression()));
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
            active.setText(translations.translate(
                    "configuration.active",
                    draft.active().isEmpty() ? translations.translate("configuration.default") : draft.activeName()));
            dimensions.refresh();
        } finally {
            refreshing = false;
        }
        updateOverride();
    }

    private void configureParameterTable() {
        showBuiltins.setId("parameters-show-builtins");
        UiHelp.install(showBuiltins, translations);
        showBuiltins.setSelected(true);
        showBuiltins.selectedProperty().addListener(o -> refresh());
        parameters.setId("parameter-table");
        parameters.setPlaceholder(new Label(translations.translate("parameters.empty")));
        parameters.setPrefHeight(175);
        parameters.getColumns().add(rowColumn("gui.name", 110, ParameterSet.Parameter::name, r -> r.rowName));
        parameters
                .getColumns()
                .add(rowColumn("gui.expression", 180, p -> draft.expression(p, draft.active()), r -> r.rowExpression));
        var valueColumn = ParameterDialog.<ParameterSet.Parameter>column(translations.translate("gui.value"), 90, p -> {
            try {
                if (p.name().startsWith("vb.")) {
                    return Expressions.format(draft.builtins().value(p.name()));
                }
                return String.format(
                        Locale.ROOT,
                        "%.6g",
                        p.unit().displayValue(draft.values().get(p.name())));
            } catch (RuntimeException failure) {
                return translations.translate("gui.error");
            }
        });
        valueColumn.setCellFactory(c -> new TableCell<>() {
            {
                setOnMouseClicked(event -> {
                    if (!isEmpty() && event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
                        attempt(() -> startRow(getTableRow().getItem(), r -> r.rowExpression));
                    }
                });
            }

            @Override
            protected void updateItem(String text, boolean empty) {
                super.updateItem(text, empty);
                setText(empty ? null : text);
            }
        });
        parameters.getColumns().add(valueColumn);
        parameters
                .getColumns()
                .add(rowColumn(
                        "gui.unit",
                        85,
                        p -> BuiltinParameters.isString(p.name())
                                ? "string"
                                : p.unit().toString(),
                        r -> r.rowUnit));
        parameters.getColumns().add(rowColumn("gui.comment", 130, this::parameterComment, r -> r.rowComment));
        var actions = new TableColumn<ParameterSet.Parameter, Void>();
        actions.setMinWidth(60);
        actions.setPrefWidth(60);
        actions.setMaxWidth(60);
        actions.setSortable(false);
        actions.setCellFactory(column -> new TableCell<>() {
            private final Button edit = rowAction();
            private final Button remove = rowAction();
            private final HBox buttons = new HBox(2, edit, remove);

            {
                edit.setOnAction(event -> attempt(() -> {
                    if (editing(getTableRow().getItem())) saveRow();
                    else startRow(getTableRow().getItem(), r -> r.rowName);
                }));
                remove.setOnAction(event -> attempt(() -> {
                    var parameter = getTableRow().getItem();
                    if (editing(parameter)) cancelRow();
                    else requestRemoval(parameter.name());
                }));
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                var parameter = empty ? null : getTableView().getItems().get(getIndex());
                boolean editing = editing(parameter);
                labelRowAction(edit, editing ? "✓" : "✎", editing ? "action.save" : "parameter.edit");
                labelRowAction(remove, editing ? "↺" : "×", editing ? "action.cancel" : "gui.remove");
                String prefix = editing ? "parameter-row-save-" : "parameter-row-edit-";
                edit.setId(parameter == null ? null : prefix + parameter.name());
                setGraphic(parameter == null || parameter.name().startsWith("vb.") ? null : buttons);
            }
        });
        parameters.getColumns().add(actions);
        parameters.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
    }

    private String parameterComment(ParameterSet.Parameter parameter) {
        if (!parameter.name().startsWith("vb.")) return parameter.comment();
        return translations
                .tryTranslate("parameter.builtin." + parameter.name().substring(3))
                .flatMap(description -> translations.tryTranslate("parameter.builtin.readOnly", description))
                .orElse(parameter.comment());
    }

    private void labelRowAction(Button button, String glyph, String key) {
        String label = translations.translate(key);
        button.setText(glyph);
        button.setAccessibleText(label);
        button.getTooltip().setText(label);
    }

    private void addParameter() {
        String newName = validateName(name, null);
        update(draft.put(newName, unit.getValue(), expression.expression().strip(), comment.evaluatedText()));
        clearFields();
    }

    private void clearFields() {
        name.pseudoClassStateChanged(INVALID, false);
        name.clear();
        expression.setExpression("");
        comment.clear();
        unit.setValue(ParameterUnit.MM);
        name.requestFocus();
    }

    private void watchName(TextField field, ParameterSet.Parameter original) {
        field.getStyleClass().add("parameter-name-field");
        field.focusedProperty().addListener((o, before, focused) -> {
            if (!focused && (original == null ? !field.getText().isBlank() : editing(original))) {
                attempt(() -> validateName(field, original));
            }
        });
        field.textProperty().addListener(o -> {
            if (field.getPseudoClassStates().contains(INVALID)) attempt(() -> validateName(field, original));
        });
    }

    private String validateName(TextField field, ParameterSet.Parameter original) {
        String candidate = field.getText().strip();
        try {
            ParameterSet.requireName(candidate);
        } catch (IllegalArgumentException invalid) {
            field.pseudoClassStateChanged(INVALID, true);
            throw new IllegalArgumentException(translations.translate("parameter.invalidName"), invalid);
        }
        boolean duplicate = draft.parameters().stream()
                .anyMatch(p -> p.name().equals(candidate) && (original == null || !p.id().equals(original.id())));
        field.pseudoClassStateChanged(INVALID, duplicate);
        if (duplicate) throw new IllegalArgumentException(translations.translate("parameter.duplicateName", candidate));
        return candidate;
    }

    private boolean editing(ParameterSet.Parameter p) {
        return rowEditor != null && p != null && rowEditor.parameter.id().equals(p.id());
    }

    private void startRow(ParameterSet.Parameter p, Function<RowEditor, Node> field) {
        if (p == null || p.name().startsWith("vb.")) return;
        if (!editing(p)) {
            saveRow();
            rowEditor = new RowEditor(p);
            parameters.refresh();
        }
        var editor = field.apply(rowEditor);
        javafx.application.Platform.runLater(() -> {
            editor.requestFocus();
            if (editor instanceof TextField input) input.selectAll();
        });
    }

    private void saveRow() {
        if (rowEditor == null) return;
        var row = rowEditor;
        String newName = validateName(row.rowName, row.parameter);
        var next = newName.equals(row.parameter.name()) ? draft : draft.rename(row.parameter.id(), newName);
        update(next.put(
                newName,
                row.rowUnit.getValue(),
                row.rowExpression.expression().strip(),
                row.rowComment.evaluatedText()));
        cancelRow();
    }

    private void cancelRow() {
        rowEditor = null;
        error.setText("");
        parameters.refresh();
    }

    private TableColumn<ParameterSet.Parameter, String> rowColumn(
            String title,
            double width,
            Function<ParameterSet.Parameter, String> value,
            Function<RowEditor, Node> editor) {
        var column = column(translations.translate(title), width, value);
        column.setCellFactory(c -> new TableCell<>() {
            {
                setOnMouseClicked(event -> {
                    if (!isEmpty()
                            && event.getButton() == MouseButton.PRIMARY
                            && event.getClickCount() == 2
                            && getGraphic() == null) {
                        attempt(() -> startRow(getTableRow().getItem(), editor));
                    }
                });
            }

            @Override
            protected void updateItem(String text, boolean empty) {
                super.updateItem(text, empty);
                var p = empty ? null : getTableView().getItems().get(getIndex());
                boolean activeRow = editing(p);
                setText(empty || activeRow ? null : text);
                setGraphic(activeRow ? editor.apply(rowEditor) : null);
            }
        });
        return column;
    }
}
