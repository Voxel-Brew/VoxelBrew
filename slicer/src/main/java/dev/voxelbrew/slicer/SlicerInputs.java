package dev.voxelbrew.slicer;

import dev.voxelbrew.api.model.vui.*;
import dev.voxelbrew.api.slicer.SlicerDialog;
import dev.voxelbrew.api.slicer.SlicerWindow;
import java.util.*;
import lombok.experimental.UtilityClass;

/** Resolves provider fields from invocation values, saved settings and declared defaults. */
@UtilityClass
public class SlicerInputs {
    public static SlicerWindow window(Map<String, String> saved, Map<String, String> overrides) {
        var snapshot = Map.copyOf(saved);
        var invocation = Map.copyOf(overrides);
        return new SlicerWindow() {
            @Override
            public <T> Optional<T> show(SlicerDialog<T> dialog) {
                return Optional.of(build(dialog, resolve(dialog.layout(), snapshot, invocation)));
            }
        };
    }

    public static Map<String, String> resolve(Layout layout, Map<String, String> saved, Map<String, String> overrides) {
        var values = new LinkedHashMap<String, String>();
        collect(layout, values);
        for (var id : overrides.keySet())
            if (!values.containsKey(id)) throw new IllegalArgumentException("Unknown slicer input: " + id);
        saved.forEach((id, value) -> values.computeIfPresent(id, (key, fallback) -> value));
        values.putAll(overrides);
        return Map.copyOf(values);
    }

    public static <T> T build(SlicerDialog<T> dialog, Map<String, String> values) {
        var errors = dialog.validate(values);
        if (!errors.isEmpty()) throw new IllegalArgumentException("Invalid slicer inputs: " + errors);
        return Objects.requireNonNull(dialog.buildSettings(values), "slicer settings");
    }

    private static void collect(Component component, Map<String, String> values) {
        switch (component) {
            case HorizontalLayout layout -> layout.children().forEach(child -> collect(child, values));
            case VerticalLayout layout -> layout.children().forEach(child -> collect(child, values));
            case Input input -> {
                String fallback = switch (input) {
                    case TextField field -> field.value();
                    case PasswordField field -> field.value();
                    case TextArea area -> area.value();
                    case CheckBox box -> Boolean.toString(box.selected());
                    case ComboBox box -> box.selected();
                    case RadioButtonGroup group -> group.selected();
                    case Slider slider -> Double.toString(slider.value());
                    case FileSource source -> Objects.toString(source.defaultPath(), "");
                    case FileDestination destination -> Objects.toString(destination.defaultPath(), "");
                };
                if (values.putIfAbsent(input.id(), fallback) != null)
                    throw new IllegalArgumentException("Duplicate slicer input: " + input.id());
            }
            default -> {}
        }
    }
}
