package dev.voxelbrew.util.services;

import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import lombok.NonNull;
import lombok.experimental.UtilityClass;

/** Discovery for dependencies which require exactly one provider. Does not cache instances. */
@UtilityClass
public class Services {
    public <T> T load(@NonNull Class<T> service) {
        return load(service, Thread.currentThread().getContextClassLoader());
    }

    public <T> T load(@NonNull Class<T> service, ClassLoader loader) {
        var providers = ServiceLoader.load(service, loader).stream().toList();
        if (providers.size() != 1)
            throw new ServiceConfigurationError("Expected exactly one provider for " + service.getName() + "; found "
                    + providers.stream()
                            .map(provider -> provider.type().getName())
                            .toList());
        return providers.getFirst().get();
    }
}
