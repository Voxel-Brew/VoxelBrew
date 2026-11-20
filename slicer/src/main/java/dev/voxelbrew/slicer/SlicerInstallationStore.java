package dev.voxelbrew.slicer;

import dev.voxelbrew.api.userconfig.UserConfigException;
import dev.voxelbrew.api.userconfig.UserConfigService;
import dev.voxelbrew.slicer.internal.BundledSlicers;
import dev.voxelbrew.util.preferences.UserConfigServiceImpl;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

/** Stores named installations, their check results, and provider defaults. */
@Slf4j
public final class SlicerInstallationStore {
    private static final String PREFIX = "installation-";

    private static final String EXTENSION = ".properties";

    private static final String INPUT_PREFIX = "input.";

    private final Path directory;

    private final UserConfigService configuration;

    private static String fileName(UUID id) {
        return PREFIX + id + EXTENSION;
    }

    private static UserConfigServiceImpl openConfig(Path home, String fileName) throws IOException {
        try {
            return new UserConfigServiceImpl(home, fileName);
        } catch (IllegalArgumentException failure) {
            throw new IOException("Cannot open slicer settings " + home.resolve(fileName), failure);
        }
    }

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "The injected service provides current preferences.")
    public SlicerInstallationStore(@NonNull UserConfigService configuration) {
        this.configuration = configuration;
        directory = configuration.slicersFolder();
    }

    public Map<String, String> providerDefaults(String id) throws IOException {
        var current = providerConfig(id);
        String prefix = "slicer." + id + ".";
        var defaults = new TreeMap<String, String>();
        configuration.readUserConfig().forEach((key, value) -> {
            if (key.startsWith(prefix)) defaults.put(key.substring(prefix.length()), value);
        });
        defaults.putAll(openConfig(configuration.homeFolder(), "config-slicer-" + id + EXTENSION)
                .readUserConfig());
        defaults.putAll(current.readUserConfig());
        return Collections.unmodifiableMap(defaults);
    }

    public void saveProviderDefaults(String id, Map<String, String> values) throws IOException {
        providerConfig(id).update(values, Set.of());
    }

    private UserConfigServiceImpl providerConfig(String id) throws IOException {
        if (id == null || !id.matches("[a-z0-9][a-z0-9.-]*")) {
            throw new IllegalArgumentException("Invalid slicer provider ID");
        }
        return openConfig(directory, id + EXTENSION);
    }

    private SlicerInstallation read(UUID id) {
        var values = new UserConfigServiceImpl(directory, fileName(id)).readUserConfig();
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

    public List<SlicerInstallation> load() throws IOException {
        var installations = new ArrayList<>(BundledSlicers.installations());
        if (Files.exists(directory)) {
            try (var paths = Files.list(directory)) {
                for (var path : paths.filter(Files::isRegularFile).toList()) {
                    Path name = path.getFileName();
                    if (name == null) continue;
                    String file = name.toString();
                    if (!file.startsWith(PREFIX) || !file.endsWith(EXTENSION)) continue;
                    String id = file.substring(PREFIX.length(), file.length() - EXTENSION.length());
                    try {
                        UUID key = UUID.fromString(id);
                        if (!BundledSlicers.reserved(key)) installations.add(read(key));
                    } catch (UserConfigException | IllegalArgumentException invalid) {
                        log.warn("Cannot read slicer installation {}", path, invalid);
                    }
                }
            }
        }
        return installations.stream()
                .sorted(Comparator.comparing(SlicerInstallation::name, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(SlicerInstallation::id))
                .toList();
    }

    public SlicerInstallation require(String id) {
        var key = UUID.fromString(id);
        if (BundledSlicers.reserved(key)) {
            return BundledSlicers.installations().stream()
                    .filter(installation -> installation.id().equals(key))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Bundled slicer installation is unavailable"));
        }
        var path = path(key);
        if (!Files.isRegularFile(path)) throw new IllegalArgumentException("Saved slicer installation is unavailable");
        return read(key);
    }

    public void save(SlicerInstallation installation) {
        if (installation.bundled()) throw new IllegalArgumentException("Bundled slicer settings are read-only");
        if (!installation.operational()) throw new IllegalArgumentException("Slicer installation must pass its check");
        var values = new LinkedHashMap<String, String>();
        values.put("name", installation.name());
        values.put("provider", installation.providerId());
        values.put("version", installation.version());
        values.put("failure", installation.failure());
        installation.settings().forEach((key, value) -> values.put(INPUT_PREFIX + key, value));
        var configuration = new UserConfigServiceImpl(directory, fileName(installation.id()));
        var removed = new HashSet<>(configuration.readUserConfig().keySet());
        removed.removeAll(values.keySet());
        configuration.update(values, removed);
    }

    public void remove(@NonNull UUID id) throws IOException {
        if (BundledSlicers.reserved(id)) throw new IllegalArgumentException("Bundled slicer settings are read-only");
        Files.deleteIfExists(path(id));
    }

    private Path path(UUID id) {
        return directory.resolve(fileName(id));
    }
}
