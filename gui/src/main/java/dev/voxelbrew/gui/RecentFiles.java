package dev.voxelbrew.gui;

import dev.voxelbrew.api.userconfig.UserConfigException;
import dev.voxelbrew.api.userconfig.UserConfigService;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/** Window-local recent documents, persisted separately from document contents. */
@Slf4j
final class RecentFiles {
    private static final int LIMIT = 10;
    private static final String PREFIX = "recent.file.";
    private final UserConfigService configuration;
    private final List<Path> paths = new ArrayList<>();

    RecentFiles(UserConfigService configuration) {
        this.configuration = configuration;
        try {
            var values = configuration.readUserConfig();
            for (int i = 0; i < LIMIT; i++) {
                String value = values.getOrDefault(PREFIX + i, "");
                if (value.isBlank()) continue;
                try {
                    Path path = Path.of(value).toAbsolutePath().normalize();
                    if (Files.isRegularFile(path) && !paths.contains(path)) paths.add(path);
                } catch (InvalidPathException invalid) {
                    log.warn("Ignoring invalid recent file path", invalid);
                }
            }
        } catch (UserConfigException | SecurityException failure) {
            log.warn("Cannot load recent files", failure);
        }
    }

    synchronized List<Path> paths() {
        return List.copyOf(paths);
    }

    synchronized void remember(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        paths.remove(normalized);
        paths.addFirst(normalized);
        if (paths.size() > LIMIT) paths.removeLast();
    }

    void save() {
        var snapshot = paths();
        var values = new LinkedHashMap<String, String>();
        for (int i = 0; i < LIMIT; i++) {
            values.put(PREFIX + i, i < snapshot.size() ? snapshot.get(i).toString() : "");
        }
        try {
            configuration.update(values, java.util.Set.of());
        } catch (UserConfigException | SecurityException failure) {
            log.warn("Cannot save recent files", failure);
        }
    }
}
