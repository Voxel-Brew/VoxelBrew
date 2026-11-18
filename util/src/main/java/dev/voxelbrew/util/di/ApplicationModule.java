package dev.voxelbrew.util.di;

import dev.voxelbrew.util.config.ApplicationPaths;
import dev.voxelbrew.util.config.UserConfiguration;
import dev.voxelbrew.util.update.*;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/** Runtime inputs and shared application services; one instance per application graph. */
@RequiredArgsConstructor
public final class ApplicationModule {
    @NonNull
    private final UserConfiguration configuration;

    @NonNull
    private final UpdateServiceFactory updates;

    @NonNull
    private final ApplicationPaths paths;

    public ApplicationModule(UserConfiguration configuration, UpdateServiceFactory updates) {
        this(configuration, updates, dev.voxelbrew.util.services.Services.load(ApplicationPaths.class));
    }

    public static ApplicationModule system(ApplicationImage.Component component) {
        return new ApplicationModule(UserConfiguration.user(), () -> UpdateService.system(component));
    }

    public UserConfiguration configuration() {
        return configuration;
    }

    public UpdateServiceFactory updates() {
        return updates;
    }

    public ApplicationPaths paths() {
        return paths;
    }
}
