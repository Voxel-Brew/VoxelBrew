package dev.voxelbrew.util.config;

import java.nio.file.Path;
import lombok.NonNull;

/** System-property-backed defaults, captured once for each service instance. */
@lombok.Getter
@lombok.experimental.Accessors(fluent = true)
public final class SystemApplicationPaths implements ApplicationPaths {
    private final Path home;
    private final Path configuration;

    public SystemApplicationPaths() {
        this(
                Path.of(System.getProperty(
                        "voxelbrew.home",
                        Path.of(System.getProperty("user.home"), ".voxelbrew").toString())),
                System.getProperty("voxelbrew.config"));
    }

    private SystemApplicationPaths(Path home, String configuration) {
        this(home, configuration == null ? home.resolve("config.properties") : Path.of(configuration));
    }

    public SystemApplicationPaths(@NonNull Path home, @NonNull Path configuration) {
        this.home = home.toAbsolutePath().normalize();
        this.configuration = configuration.toAbsolutePath().normalize();
    }
}
