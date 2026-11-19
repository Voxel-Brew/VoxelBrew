package dev.voxelbrew.slicer;

import dev.voxelbrew.slicer.internal.BundledSlicers;
import dev.voxelbrew.util.config.FileUserConfiguration;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import lombok.extern.slf4j.Slf4j;

/** Stores named installations and their last check results alongside legacy provider defaults. */
@Slf4j
public final class SlicerInstallationStore {
    private static final String PREFIX = "installation-";
    private static final String EXTENSION = ".properties";
    private static final String INPUT_PREFIX = "input.";

    private final Path directory;

    public SlicerInstallationStore(Path configuration) {
        directory = configuration.resolveSibling("slicers");
    }

    public List<SlicerInstallation> load() throws IOException {
        var installations = new ArrayList<>(BundledSlicers.installations());
        if (Files.exists(directory))
            try (var paths = Files.list(directory)) {
                for (var path : paths.filter(Files::isRegularFile).toList()) {
                    var file = path.getFileName().toString();
                    if (!file.startsWith(PREFIX) || !file.endsWith(EXTENSION)) continue;
                    String id = file.substring(PREFIX.length(), file.length() - EXTENSION.length());
                    try {
                        UUID key = UUID.fromString(id);
                        if (!BundledSlicers.reserved(key)) installations.add(read(key, path));
                    } catch (IOException | IllegalArgumentException invalid) {
                        log.warn("Cannot read slicer installation {}", path, invalid);
                    }
                }
            }
        return installations.stream()
                .sorted(Comparator.comparing(SlicerInstallation::name, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(SlicerInstallation::id))
                .toList();
    }

    public SlicerInstallation require(String id) throws IOException {
        var key = UUID.fromString(id);
        if (BundledSlicers.reserved(key))
            return BundledSlicers.installations().stream()
                    .filter(installation -> installation.id().equals(key))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Bundled slicer installation is unavailable"));
        var path = path(key);
        if (!Files.isRegularFile(path)) throw new IllegalArgumentException("Saved slicer installation is unavailable");
        return read(key, path);
    }

    public void save(SlicerInstallation installation) throws IOException {
        if (installation.bundled()) throw new IllegalArgumentException("Bundled slicer settings are read-only");
        if (!installation.operational()) throw new IllegalArgumentException("Slicer installation must pass its check");
        var values = new LinkedHashMap<String, String>();
        values.put("name", installation.name());
        values.put("provider", installation.providerId());
        values.put("version", installation.version());
        values.put("failure", installation.failure());
        installation.settings().forEach((key, value) -> values.put(INPUT_PREFIX + key, value));
        var configuration = new FileUserConfiguration(path(installation.id()));
        var removed = new HashSet<>(configuration.read().keySet());
        removed.removeAll(values.keySet());
        configuration.update(values, removed);
    }

    private static SlicerInstallation read(UUID id, Path path) throws IOException {
        var values = new FileUserConfiguration(path).read();
        var settings = new LinkedHashMap<String, String>();
        values.forEach((key, value) -> {
            if (key.startsWith(INPUT_PREFIX)) settings.put(key.substring(INPUT_PREFIX.length()), value);
        });
        return new SlicerInstallation(
                id,
                values.get("provider"),
                values.get("name"),
                values.getOrDefault("version", ""),
                values.getOrDefault("failure", ""),
                settings);
    }

    private Path path(UUID id) {
        return directory.resolve(PREFIX + id + EXTENSION);
    }
}
