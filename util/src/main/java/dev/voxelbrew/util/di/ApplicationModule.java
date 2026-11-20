package dev.voxelbrew.util.di;

import dev.voxelbrew.api.i18n.TranslationService;
import dev.voxelbrew.api.userconfig.UserConfigService;
import dev.voxelbrew.util.services.Services;
import dev.voxelbrew.util.update.*;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/** Runtime inputs and shared application services; one instance per application graph. */
@RequiredArgsConstructor
public final class ApplicationModule {
    @NonNull
    private final UserConfigService configuration;

    @NonNull
    private final UpdateServiceFactory updates;

    @NonNull
    private final TranslationService translations;

    public static ApplicationModule system(ApplicationImage.Component component) {
        return system(() -> UpdateService.system(component));
    }

    public static ApplicationModule system(UpdateServiceFactory updates) {
        return new ApplicationModule(
                Services.load(UserConfigService.class), updates, Services.load(TranslationService.class));
    }

    public UserConfigService configuration() {
        return configuration;
    }

    public UpdateServiceFactory updates() {
        return updates;
    }

    @SuppressFBWarnings(
            value = "EI_EXPOSE_REP",
            justification = "The application graph shares its translation service.")
    public TranslationService translations() {
        return translations;
    }
}
