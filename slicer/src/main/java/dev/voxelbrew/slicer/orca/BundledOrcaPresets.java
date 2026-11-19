package dev.voxelbrew.slicer.orca;

import com.google.gson.*;
import dev.voxelbrew.api.model.printing.Cancellation;
import dev.voxelbrew.api.model.printing.PrintProfile;
import dev.voxelbrew.api.slicer.SlicerException;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import lombok.experimental.UtilityClass;

/** Resolves vendor profiles against the selected single-tool Bambu printer and material. */
@UtilityClass
public class BundledOrcaPresets {
    public static Map<String, String> prepare(Path root, PrintProfile profile, Cancellation cancellation)
            throws IOException, SlicerException {
        var catalog = readCatalog(root.resolve("voxelbrew-bbl"), cancellation);
        var printer = profile.printer();
        String material = profile.material().id();
        String machineName = catalog.values().stream()
                .filter(preset -> field(preset, "type").equals("machine"))
                .filter(preset -> OrcaSlicerBackend.normalize(field(preset, "printer_model"))
                        .equals(OrcaSlicerBackend.normalize(printer.id())))
                .filter(preset -> values(preset, "nozzle_diameter")
                        .equals(List.of(java.math.BigDecimal.valueOf(printer.nozzleMm())
                                .stripTrailingZeros()
                                .toPlainString())))
                .map(preset -> field(preset, "name"))
                .filter(name -> name.endsWith(" nozzle"))
                .sorted()
                .findFirst()
                .orElseThrow(() -> new SlicerException.UnsupportedInput("Bundled OrcaSlicer has no profile for "
                        + printer.id() + " with a " + printer.nozzleMm() + " mm nozzle"));
        var machine = resolve(catalog, machineName, new HashSet<>());
        var process = resolve(catalog, field(machine, "default_print_profile"), new HashSet<>());
        JsonObject filament = null;
        for (String name : values(machine, "default_filament_profile")) {
            var candidate = resolve(catalog, name, new HashSet<>());
            if (values(candidate, "filament_type").contains(material)) {
                filament = candidate;
                break;
            }
        }
        if (filament == null) {
            for (var entry : catalog.entrySet()) {
                cancellation.check();
                var preset = entry.getValue();
                if (!field(preset, "type").equals("filament")
                        || !entry.getKey().startsWith("Bambu " + material)
                        || !values(preset, "compatible_printers").contains(machineName)) continue;
                var candidate = resolve(catalog, entry.getKey(), new HashSet<>());
                if (values(candidate, "filament_type").contains(material)) {
                    filament = candidate;
                    break;
                }
            }
        }
        if (filament == null)
            throw new SlicerException.UnsupportedInput(
                    "Bundled OrcaSlicer has no " + material + " preset for " + machineName);
        return Map.of(
                "machine", write(root, "machine", machine),
                "process", write(root, "process", process),
                "filament", write(root, "filament", filament));
    }

    private static Map<String, JsonObject> readCatalog(Path directory, Cancellation cancellation)
            throws IOException, SlicerException.Configuration {
        var catalog = new TreeMap<String, JsonObject>();
        try (var paths = Files.walk(directory)) {
            for (var path : paths.filter(file -> file.toString().endsWith(".json"))
                    .sorted()
                    .toList()) {
                cancellation.check();
                try (var reader = Files.newBufferedReader(path)) {
                    var preset = JsonParser.parseReader(reader).getAsJsonObject();
                    Path name = path.getFileName();
                    if (name == null) throw new IOException("Expected a bundled preset file: " + path);
                    catalog.put(name.toString().replaceFirst("\\.json$", ""), preset);
                }
            }
        } catch (JsonParseException | IllegalStateException invalid) {
            throw new SlicerException.Configuration("Invalid bundled OrcaSlicer preset: " + invalid.getMessage());
        }
        return catalog;
    }

    private static JsonObject resolve(Map<String, JsonObject> catalog, String name, Set<String> parents)
            throws SlicerException.Configuration {
        var preset = catalog.get(name);
        if (preset == null || !parents.add(name))
            throw new SlicerException.Configuration("Cannot resolve bundled OrcaSlicer preset " + name);
        String parent = field(preset, "inherits");
        var resolved = parent.isEmpty() ? new JsonObject() : resolve(catalog, parent, parents);
        for (String included : values(preset, "include")) merge(resolved, resolve(catalog, included, parents));
        merge(resolved, preset);
        parents.remove(name);
        resolved.remove("inherits");
        resolved.remove("include");
        return resolved;
    }

    private static void merge(JsonObject result, JsonObject source) {
        source.entrySet().forEach(entry -> result.add(entry.getKey(), entry.getValue()));
    }

    private static String field(JsonObject object, String key) {
        var element = object.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : "";
    }

    private static List<String> values(JsonObject object, String key) {
        var element = object.get(key);
        return element != null && element.isJsonArray()
                ? element.getAsJsonArray().asList().stream()
                        .map(JsonElement::getAsString)
                        .toList()
                : List.of();
    }

    private static String write(Path root, String key, JsonObject profile) throws IOException {
        return Files.writeString(root.resolve(key + ".json"), profile.toString())
                .toString();
    }
}
