package dev.voxelbrew.exporter;

import dev.voxelbrew.api.exporter.ExportDialog;
import dev.voxelbrew.api.exporter.FileExportDialog;
import dev.voxelbrew.api.vui.CheckBox;
import dev.voxelbrew.api.vui.ComboBox;
import dev.voxelbrew.api.vui.Component;
import dev.voxelbrew.api.vui.FileDestination;
import dev.voxelbrew.api.vui.Layout;
import dev.voxelbrew.api.vui.TextField;
import dev.voxelbrew.api.vui.VerticalLayout;
import dev.voxelbrew.engine.mesh.MeshOptions;
import dev.voxelbrew.engine.printing.ManufacturingModel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.NonNull;

/** Options request owned by the built-in geometry exporters, not a plugin SPI. */
@lombok.Getter
@lombok.experimental.Accessors(fluent = true)
public final class MeshExportDialog implements ExportDialog<MeshExportDialog.Settings> {
    public MeshExportDialog(@NonNull MeshFormat format) {
        this.format = format;
    }

    @Override
    public Layout layout() {
        var children = new ArrayList<Component>();
        children.add(new FileDestination("destination", format.extensions().get(0)));
        children.add(new CheckBox("overwrite", "Overwrite", false));
        children.add(new ComboBox("space", "Space", List.of("DESIGN", "BUILD_PLATE"), "DESIGN"));
        children.add(new ComboBox("selection", "Contents", List.of("MODEL", "SUPPORTS", "ALL"), "MODEL"));
        children.add(new TextField("body", "Body", ""));
        children.add(new TextField("instance", "Instance", ""));
        children.add(new TextField("chord-tolerance", "Chord tolerance (mm)", "0.02"));
        children.add(new TextField("angular-tolerance", "Angular tolerance (degrees)", "5"));
        for (var option : format.options()) {
            String id = "option." + option.key();
            children.add(
                    option.type() == MeshExportOption.Type.CHOICE
                            ? new ComboBox(id, option.label(), option.choices(), option.defaultValue())
                            : new TextField(id, option.label(), option.defaultValue()));
        }
        return new VerticalLayout(children);
    }

    @Override
    public Map<String, String> validate(Map<String, String> values) {
        var errors = new LinkedHashMap<String, String>();
        var destination = values.get("destination");
        if (destination == null || destination.isBlank()) errors.put("destination", "Choose a destination");
        else {
            try {
                Path.of(destination);
            } catch (java.nio.file.InvalidPathException failure) {
                errors.put("destination", "Invalid destination path: " + failure.getReason());
            }
        }
        if (!"true".equals(values.getOrDefault("overwrite", "false"))
                && !"false".equals(values.getOrDefault("overwrite", "false")))
            errors.put("overwrite", "Overwrite must be true or false");
        if (!"DESIGN".equals(values.getOrDefault("space", "DESIGN"))
                && !"BUILD_PLATE".equals(values.getOrDefault("space", "DESIGN")))
            errors.put("space", "Choose DESIGN or BUILD_PLATE");
        if (!"MODEL".equals(values.getOrDefault("selection", "MODEL"))
                && !"SUPPORTS".equals(values.getOrDefault("selection", "MODEL"))
                && !"ALL".equals(values.getOrDefault("selection", "MODEL")))
            errors.put("selection", "Choose MODEL, SUPPORTS or ALL");
        try {
            double chord = Double.parseDouble(values.getOrDefault("chord-tolerance", "0.02"));
            if (!Double.isFinite(chord) || chord < 1e-6) throw new NumberFormatException();
        } catch (NumberFormatException failure) {
            errors.put("chord-tolerance", "Chord tolerance must be finite and at least 0.000001 mm");
        }
        try {
            double angle = Double.parseDouble(values.getOrDefault("angular-tolerance", "5"));
            if (!Double.isFinite(angle) || angle <= 0 || angle > 180) throw new NumberFormatException();
        } catch (NumberFormatException failure) {
            errors.put("angular-tolerance", "Angular tolerance must be greater than 0 and at most 180 degrees");
        }
        for (var option : format.options()) {
            var id = "option." + option.key();
            var value = values.getOrDefault(id, option.defaultValue());
            if (value == null) {
                errors.put(id, "Choose a value");
                continue;
            }
            try {
                option.validate(value);
            } catch (IllegalArgumentException failure) {
                errors.put(id, failure.getMessage());
            }
        }
        for (var key : values.keySet()) {
            if (key.startsWith("option.")
                    && format.options().stream().noneMatch(option -> key.equals("option." + option.key())))
                errors.put(key, "Unknown exporter option: " + key);
        }
        return errors;
    }

    @Override
    public Settings buildSettings(Map<String, String> values) {
        var errors = validate(values);
        if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("; ", errors.values()));
        var destination = values.get("destination");
        var overwrite = values.getOrDefault("overwrite", "false");
        var selection = new ExportSelection(
                ExportSelection.Space.valueOf(values.getOrDefault("space", "DESIGN")),
                ExportSelection.Contents.valueOf(values.getOrDefault("selection", "MODEL")),
                emptyToNull(values.get("body")),
                emptyToNull(values.get("instance")));
        var mesh = new MeshOptions(
                Double.parseDouble(values.getOrDefault("chord-tolerance", "0.02")),
                Double.parseDouble(values.getOrDefault("angular-tolerance", "5")));
        var options = new LinkedHashMap<String, String>();
        for (var option : format.options()) {
            var value = values.getOrDefault("option." + option.key(), option.defaultValue());
            option.validate(value);
            options.put(option.key(), value);
        }
        for (var key : values.keySet()) {
            if (key.startsWith("option.") && !options.containsKey(key.substring("option.".length())))
                throw new IllegalArgumentException("Unknown exporter option: " + key);
        }
        return new Settings(
                selection,
                mesh,
                options,
                new FileExportDialog.Destination(Path.of(destination), Boolean.parseBoolean(overwrite)));
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    @lombok.NonNull
    private final MeshFormat format;

    @Override
    public String title() {
        return format.name();
    }

    public record Settings(
            ExportSelection selection,
            MeshOptions mesh,
            Map<String, String> options,
            FileExportDialog.Destination destination,
            ManufacturingModel snapshot) {
        public Settings {
            Objects.requireNonNull(selection);
            Objects.requireNonNull(mesh);
            Objects.requireNonNull(destination);
            options = Map.copyOf(options);
        }

        public Settings(
                ExportSelection selection,
                MeshOptions mesh,
                Map<String, String> options,
                FileExportDialog.Destination destination) {
            this(selection, mesh, options, destination, null);
        }
    }
}
