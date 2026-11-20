package consumer;

import dev.voxelbrew.api.importer.*;
import dev.voxelbrew.api.model.*;
import dev.voxelbrew.api.model.vui.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Separate plugin compiled only against published import and model API artifacts. */
public class ProbeImporter implements ImporterProvider {
    public record PlateSettings(Path path, double scale) {}

    public static final class PlateDialog implements ImportDialog<PlateSettings> {
        private final FileSource source = new FileSource("source", "vbplate");
        private final TextField scale = new TextField("scale", "Scale", "1");

        @Override
        public String title() {
            return "External plate";
        }

        @Override
        public Layout layout() {
            return new VerticalLayout(source, scale);
        }

        @Override
        public Map<String, String> validate(Map<String, String> values) {
            var errors = new LinkedHashMap<String, String>();
            String path = values.get("source");
            if (path == null || path.isBlank()) errors.put("source", "Choose a source");
            else {
                try {
                    var file = Path.of(path);
                    if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
                        errors.put("source", "Source must be a readable file");
                    }
                } catch (java.nio.file.InvalidPathException failure) {
                    errors.put("source", "Invalid source path");
                }
            }
            try {
                double value = Double.parseDouble(values.getOrDefault("scale", "1"));
                if (!Double.isFinite(value) || value <= 0) errors.put("scale", "Scale must be positive");
            } catch (NumberFormatException failure) {
                errors.put("scale", "Scale must be numeric");
            }
            return Map.copyOf(errors);
        }

        @Override
        public PlateSettings buildSettings(Map<String, String> values) {
            var errors = validate(values);
            if (!errors.isEmpty()) throw new IllegalArgumentException(errors.toString());
            return new PlateSettings(
                    Path.of(values.get("source")), Double.parseDouble(values.getOrDefault("scale", "1")));
        }
    }

    public static final class Duplicate extends ProbeImporter {}

    public static final class Broken extends ProbeImporter {
        @Override
        public ImporterDescriptor descriptor() {
            throw new AssertionError("Broken descriptor");
        }
    }

    public static class Failing extends ProbeImporter {
        @Override
        public ImporterDescriptor descriptor() {
            return new ImporterDescriptor("failing", "Failing reader", "1.0.0");
        }

        @Override
        public CadDocument read(Path source, double scale) throws IOException {
            throw new IOException("Deliberate importer failure");
        }
    }

    @Override
    public ImporterDescriptor descriptor() {
        return new ImporterDescriptor("plate", "External plate", "1.0.0");
    }

    @Override
    public Importer create() {
        return window -> {
            var accepted = window.show(new PlateDialog());
            if (accepted.isEmpty()) return Optional.empty();
            var settings = accepted.orElseThrow();
            return Optional.of(read(settings.path(), settings.scale()));
        };
    }

    public CadDocument read(Path source, double scale) throws IOException {
        byte[] bytes;
        try (var input = Files.newInputStream(source)) {
            bytes = input.readNBytes(4097);
        }
        if (bytes.length > 4096) throw new IOException("Plate input exceeds 4 KiB");
        String[] dimensions = new String(bytes, StandardCharsets.UTF_8).strip().split(",");
        if (dimensions.length != 3) throw new IOException("Expected width,height,thickness");
        var sketch = new Feature.Sketch(
                "plate",
                "profile",
                new Feature.Plane("origin-xy", 0),
                new Feature.Rectangle(
                        0, 0, Double.parseDouble(dimensions[0]) * scale, Double.parseDouble(dimensions[1]) * scale));
        var extrude = new Feature.Extrude(
                "thickness", new Feature.ProfileRef("plate", "profile"), Double.parseDouble(dimensions[2]) * scale);
        return new CadDocument("imported-plate", List.of(sketch, extrude), 2);
    }
}
