package dev.voxelbrew.cli;

import dev.voxelbrew.api.importer.ImportDialog;
import dev.voxelbrew.api.importer.ImportWindow;
import dev.voxelbrew.api.model.vui.*;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Resolves importer component values from the CLI source and named options. */
final class CliImportWindow implements ImportWindow {
    private final Path source;
    private final Map<String, String> options;

    private static void collect(Component component, Map<String, Input> inputs) {
        if (component instanceof Input input) {
            if (inputs.putIfAbsent(input.id(), input) != null)
                throw new IllegalArgumentException("Duplicate import input: " + input.id());
        } else if (component instanceof HorizontalLayout layout) {
            layout.children().forEach(child -> collect(child, inputs));
        } else if (component instanceof VerticalLayout layout) {
            layout.children().forEach(child -> collect(child, inputs));
        }
    }

    CliImportWindow(Path source, Map<String, String> options) {
        this.source = java.util.Objects.requireNonNull(source, "source");
        this.options = Map.copyOf(options);
    }

    @Override
    public <R> Optional<R> show(ImportDialog<R> dialog) {
        var inputs = new LinkedHashMap<String, Input>();
        collect(dialog.layout(), inputs);
        var values = new LinkedHashMap<String, String>();
        for (var input : inputs.values()) {
            if (input instanceof FileSource) values.put(input.id(), source.toString());
            else if (input instanceof TextField field) values.put(input.id(), field.value());
            else if (input instanceof TextArea area) values.put(input.id(), area.value());
            else if (input instanceof CheckBox box) values.put(input.id(), Boolean.toString(box.selected()));
            else if (input instanceof ComboBox box) values.put(input.id(), box.selected());
            else if (input instanceof RadioButtonGroup group) values.put(input.id(), group.selected());
            else if (input instanceof Slider slider) values.put(input.id(), Double.toString(slider.value()));
        }
        for (var option : options.entrySet()) {
            if (!inputs.containsKey(option.getKey()) || inputs.get(option.getKey()) instanceof FileSource)
                throw new IllegalArgumentException("Unknown import option: " + option.getKey());
            values.put(option.getKey(), option.getValue());
        }
        var errors = dialog.validate(values);
        if (!errors.isEmpty()) throw new IllegalArgumentException("Invalid import inputs: " + errors);
        return Optional.of(dialog.buildSettings(values));
    }
}
