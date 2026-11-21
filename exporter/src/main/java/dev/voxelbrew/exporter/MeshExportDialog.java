package dev.voxelbrew.exporter;

import dev.voxelbrew.api.exporter.ExportDialog;
import dev.voxelbrew.api.exporter.FileExportDialog;
import dev.voxelbrew.api.i18n.TranslationService;
import dev.voxelbrew.api.model.mesh.MeshOptions;
import dev.voxelbrew.api.model.printing.ManufacturingModel;
import dev.voxelbrew.api.model.vui.CheckBox;
import dev.voxelbrew.api.model.vui.ComboBox;
import dev.voxelbrew.api.model.vui.Component;
import dev.voxelbrew.api.model.vui.FileDestination;
import dev.voxelbrew.api.model.vui.Layout;
import dev.voxelbrew.api.model.vui.TextField;
import dev.voxelbrew.api.model.vui.VerticalLayout;
import dev.voxelbrew.util.services.Services;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.NonNull;

/** Options request owned by the built-in geometry exporters, not a plugin SPI. */
@lombok.experimental.Accessors(fluent = true)
public final class MeshExportDialog implements ExportDialog<MeshExportDialog.Settings> {
    @lombok.Getter
    @lombok.NonNull
    private final MeshFormat format;

    private final TranslationService translations;

    private final boolean plateOnly;

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

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    public MeshExportDialog(@NonNull MeshFormat format) {
        this(format, false);
    }

    MeshExportDialog(MeshFormat format, boolean plateOnly) {
        this(format, Services.load(TranslationService.class), plateOnly);
    }

    public MeshExportDialog(MeshFormat format, TranslationService translations) {
        this(format, translations, false);
    }

    private MeshExportDialog(@NonNull MeshFormat format, @NonNull TranslationService translations, boolean plateOnly) {
        this.translations = translations;
        this.format = format;
        this.plateOnly = plateOnly;
    }

    @Override
    public Layout layout() {
        var children = new ArrayList<Component>();
        children.add(new FileDestination("destination", format.extensions().get(0)));
        children.add(new CheckBox("overwrite", translations.translate("export.mesh.overwrite"), false));
        children.add(new ComboBox(
                "space",
                translations.translate("export.mesh.space").orElseThrow(),
                plateOnly ? List.of("BUILD_PLATE") : List.of("DESIGN", "BUILD_PLATE"),
                plateOnly ? "BUILD_PLATE" : "DESIGN"));
        children.add(new ComboBox(
                "selection",
                translations.translate("export.mesh.contents"),
                List.of("MODEL", "SUPPORTS", "ALL"),
                "MODEL"));
        children.add(new TextField("body", translations.translate("export.mesh.body"), ""));
        children.add(new TextField("instance", translations.translate("export.mesh.instance"), ""));
        children.add(new TextField("chord-tolerance", translations.translate("export.mesh.chord"), "0.02"));
        children.add(new TextField("angular-tolerance", translations.translate("export.mesh.angle"), "5"));
        for (var option : format.options()) {
            String id = "option." + option.key();
            children.add(
                    option.type() == MeshExportOption.Type.CHOICE
                            ? new ComboBox(
                                    id,
                                    translations
                                            .tryTranslate("export.option." + option.key())
                                            .orElse(option.label()),
                                    option.choices(),
                                    option.defaultValue())
                            : new TextField(
                                    id,
                                    translations
                                            .tryTranslate("export.option." + option.key())
                                            .orElse(option.label()),
                                    option.defaultValue()));
        }
        return new VerticalLayout(children);
    }

    @Override
    public Map<String, String> validate(Map<String, String> values) {
        var errors = new LinkedHashMap<String, String>();
        var destination = values.get("destination");
        if (destination == null || destination.isBlank()) {
            errors.put("destination", translations.translate("export.mesh.destination"));
        } else {
            try {
                Path.of(destination);
            } catch (java.nio.file.InvalidPathException failure) {
                errors.put(
                        "destination", translations.translate("export.mesh.invalidDestination", failure.getReason()));
            }
        }
        if (!"true".equals(values.getOrDefault("overwrite", "false"))
                && !"false".equals(values.getOrDefault("overwrite", "false"))) {
            errors.put("overwrite", translations.translate("export.mesh.invalidOverwrite"));
        }
        if (!"DESIGN".equals(values.getOrDefault("space", "DESIGN"))
                && !"BUILD_PLATE".equals(values.getOrDefault("space", "DESIGN"))) {
            errors.put("space", translations.translate("export.mesh.invalidSpace"));
        }
        if (!"MODEL".equals(values.getOrDefault("selection", "MODEL"))
                && !"SUPPORTS".equals(values.getOrDefault("selection", "MODEL"))
                && !"ALL".equals(values.getOrDefault("selection", "MODEL"))) {
            errors.put("selection", translations.translate("export.mesh.invalidContents"));
        }
        validateTolerances(values, errors);
        validateFormatOptions(values, errors);
        return errors;
    }

    private void validateTolerances(Map<String, String> values, Map<String, String> errors) {
        try {
            double chord = Double.parseDouble(values.getOrDefault("chord-tolerance", "0.02"));
            if (!Double.isFinite(chord) || chord < 1e-6) throw new NumberFormatException();
        } catch (NumberFormatException failure) {
            errors.put("chord-tolerance", translations.translate("export.mesh.invalidChord"));
        }
        try {
            double angle = Double.parseDouble(values.getOrDefault("angular-tolerance", "5"));
            if (!Double.isFinite(angle) || angle <= 0 || angle > 180) throw new NumberFormatException();
        } catch (NumberFormatException failure) {
            errors.put("angular-tolerance", translations.translate("export.mesh.invalidAngle"));
        }
    }

    private void validateFormatOptions(Map<String, String> values, Map<String, String> errors) {
        for (var option : format.options()) {
            var id = "option." + option.key();
            var value = values.getOrDefault(id, option.defaultValue());
            if (value == null) {
                errors.put(id, translations.translate("export.mesh.missing"));
                continue;
            }
            try {
                option.validate(value);
            } catch (IllegalArgumentException failure) {
                errors.put(id, translations.translate("export.mesh.invalidOption", option.label(), value));
            }
        }
        for (var key : values.keySet()) {
            if (key.startsWith("option.")
                    && format.options().stream().noneMatch(option -> key.equals("option." + option.key()))) {
                errors.put(key, translations.translate("export.mesh.unknown", key));
            }
        }
    }

    @Override
    public Settings buildSettings(Map<String, String> values) {
        var errors = validate(values);
        if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("; ", errors.values()));
        var destination = values.get("destination");
        var overwrite = values.getOrDefault("overwrite", "false");
        var selection = new ExportSelection(
                plateOnly
                        ? ExportSelection.Space.BUILD_PLATE
                        : ExportSelection.Space.valueOf(values.getOrDefault("space", "DESIGN")),
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
            if (key.startsWith("option.") && !options.containsKey(key.substring("option.".length()))) {
                throw new IllegalArgumentException(translations.translate("export.mesh.unknown", key));
            }
        }
        return new Settings(
                selection,
                mesh,
                options,
                new FileExportDialog.Destination(Path.of(destination), Boolean.parseBoolean(overwrite)));
    }

    @Override
    public String title() {
        return translations.tryTranslate("export.format." + format.id()).orElse(format.name());
    }
}
