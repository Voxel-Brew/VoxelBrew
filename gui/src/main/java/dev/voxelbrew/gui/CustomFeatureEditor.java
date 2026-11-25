package dev.voxelbrew.gui;

import dev.voxelbrew.api.i18n.TranslationService;
import dev.voxelbrew.api.model.Feature;
import dev.voxelbrew.api.model.parameters.ParameterSet;
import dev.voxelbrew.engine.feature.*;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;

final class CustomFeatureEditor extends VBox {
    private final ComboBox<FeatureDescriptor> type = new ComboBox<>();
    private final LinkedHashMap<String, FormulaField> fields = new LinkedHashMap<>();
    private final Label errors = new Label();
    private final Path directory;
    private final FeatureRegistry.Factory features;
    private final ParameterSet parameters;

    CustomFeatureEditor(
            Path directory, FeatureRegistry.Factory features, ParameterSet parameters, TranslationService translations)
            throws IOException {
        super(8);
        this.directory = directory;
        this.features = features;
        this.parameters = parameters;
        type.setId("custom-type");
        type.setMaxWidth(Double.MAX_VALUE);
        var form = new VBox(8);
        errors.setWrapText(true);
        errors.setId("custom-error");
        errors.getStyleClass().add("error");
        var diagnostics = new Label();
        try (var registry = features.open(directory)) {
            type.getItems().setAll(registry.descriptors());
            diagnostics.setText(String.join("\n", registry.diagnostics()));
        }
        diagnostics.setWrapText(true);
        var details = new TitledPane(translations.translate("gui.plugin_diagnostics"), diagnostics);
        details.setExpanded(false);
        type.valueProperty().addListener((obs, previous, descriptor) -> {
            fields.clear();
            form.getChildren().clear();
            errors.setText("");
            if (descriptor == null) return;
            for (var p : descriptor.parameters()) {
                var input = FormulaField.number(
                        Double.toString(p.defaultValue()), () -> parameters, FormulaField.unit(p.unit()), translations);
                input.setId("custom-" + p.key());
                fields.put(p.key(), input);
                form.getChildren().addAll(new Label(p.label()), input);
            }
        });
        type.getSelectionModel().selectFirst();
        var location = new Label(translations.translate("plugin.directory", directory));
        location.setWrapText(true);
        getChildren()
                .addAll(new Label(translations.translate("gui.feature_type")), type, form, errors, location, details);
        if (!available()) {
            errors.setText(translations.translate(
                    "gui.no_feature_providers_installed_add_a_trusted_jar_to_this_directory_and_reopen_features"));
        }
    }

    boolean available() {
        return type.getValue() != null;
    }

    Feature.Custom feature() {
        fields.values().forEach(f -> f.getStyleClass().remove("invalid"));
        var values = new LinkedHashMap<String, Double>();
        var descriptor = type.getValue();
        try (var registry = features.open(directory)) {
            for (var p : descriptor.parameters()) {
                var field = fields.get(p.key());
                try {
                    double value = field.declaredValue();
                    p.validate(value);
                    values.put(p.key(), value);
                } catch (IllegalArgumentException invalid) {
                    field.getStyleClass().add("invalid");
                    throw new IllegalArgumentException(p.label() + ": " + invalid.getMessage(), invalid);
                }
            }
            return registry.create(
                    "custom-" + parameters.uuidGenerator().next(), descriptor.providerId(), descriptor.type(), values);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        } catch (RuntimeException failure) {
            errors.setText(failure.getMessage());
            fields.forEach((key, field) -> {
                if (String.valueOf(failure.getMessage()).contains(key)) {
                    field.getStyleClass().add("invalid");
                }
            });
            throw failure;
        }
    }
}
