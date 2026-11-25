package dev.voxelbrew.gui;

import dev.voxelbrew.api.i18n.TranslationService;
import dev.voxelbrew.api.model.Feature;
import dev.voxelbrew.api.model.parameters.*;
import dev.voxelbrew.engine.model.BodyPattern;
import dev.voxelbrew.engine.parameters.FeatureDimensions;
import java.util.*;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;

final class BodyPatternEditor extends VBox {
    private final BodyPattern initial;
    private final String id;
    private final ParameterSet parameters;
    private final ComboBox<Feature.BodyRef> source = new ComboBox<>();
    private final ComboBox<BodyPattern.Axis> axis = new ComboBox<>();
    private final Map<String, FormulaField> inputs = new LinkedHashMap<>();

    static String axisName(BodyPattern.Axis axis, boolean circular) {
        return circular
                ? axis.name()
                : switch (axis) {
                    case X -> "YZ";
                    case Y -> "XZ";
                    case Z -> "XY";
                };
    }

    static String caption(String key, BodyPattern.Kind kind, BodyPattern.Axis normal, TranslationService tr) {
        boolean count = key.equals("count") || key.equals("rows");
        if (kind == BodyPattern.Kind.MIRROR) return tr.translate("gui.offset_mm");
        if (kind == BodyPattern.Kind.CIRCULAR) {
            return count ? tr.translate("sketch.copy.count") : tr.translate("pattern.sweep") + " (°)";
        }
        String plane = axisName(normal, false);
        char direction = plane.charAt(key.equals("rows") || key.equals("spacing") ? 1 : 0);
        return count ? tr.translate("sketch.copy.count") + " (" + direction + ")" : direction + " Δ (mm)";
    }

    BodyPatternEditor(
            String id,
            BodyPattern initial,
            Map<Feature.BodyRef, String> sources,
            ParameterSet parameters,
            TranslationService tr) {
        super(6);
        this.id = id;
        this.initial = initial;
        this.parameters = parameters;
        source.setId("pattern-source");
        source.getItems().setAll(sources.keySet());
        UiText.choices(source, sources::get);
        source.setValue(initial.source());
        axis.setId("pattern-axis");
        axis.getItems().setAll(BodyPattern.Axis.values());
        axis.setValue(initial.axis());
        boolean circular = initial.kind() == BodyPattern.Kind.CIRCULAR;
        UiText.choices(axis, a -> axisName(a, circular));
        getChildren()
                .addAll(
                        InspectorDetails.field(new Label(tr.translate("gui.input_body")), source),
                        InspectorDetails.field(
                                new Label(tr.translate(circular ? "pattern.axis" : "pattern.plane")), axis));
        for (var dim : FeatureDimensions.list(initial.feature(id))) {
            String key = dim.target().field();
            var binding = parameters.binding(dim.target());
            var field = FormulaField.number(
                    binding.isEmpty() ? Double.toString(dim.value()) : binding, () -> parameters, dim.unit(), tr);
            field.setId("pattern-" + key);
            inputs.put(key, field);
            var label = new Label();
            label.setLabelFor(field);
            label.textProperty().bind(axis.valueProperty().map(a -> caption(key, initial.kind(), a, tr)));
            getChildren().add(InspectorDetails.field(label, field));
        }
    }

    Feature feature() {
        var changes = new LinkedHashMap<String, Double>();
        inputs.forEach((key, field) -> changes.put(key, field.value()));
        var definition = new BodyPattern(
                initial.kind(),
                source.getValue(),
                axis.getValue(),
                initial.firstCount(),
                initial.secondCount(),
                initial.firstStep(),
                initial.secondStep());
        return FeatureDimensions.apply(definition.feature(id), changes);
    }

    ParameterSet bindings() {
        var result = parameters;
        for (var entry : inputs.entrySet()) {
            String text = entry.getValue().expression();
            result = result.bind(new ParameterSet.Target(id, entry.getKey()), Expressions.isNumber(text) ? "" : text);
        }
        return result;
    }
}
