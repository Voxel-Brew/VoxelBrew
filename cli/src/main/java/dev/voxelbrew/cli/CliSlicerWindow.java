package dev.voxelbrew.cli;

import dev.voxelbrew.api.slicer.*;
import dev.voxelbrew.slicer.SlicerInputs;
import dev.voxelbrew.slicer.SlicerInstallationStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.*;

final class CliSlicerWindow implements SlicerWindow {
    private final SlicerInstallationStore store;
    private final String providerId;
    private final Map<String, String> overrides;

    CliSlicerWindow(SlicerInstallationStore store, String providerId, Map<String, String> overrides) {
        this.store = store;
        this.providerId = providerId;
        this.overrides = Map.copyOf(overrides);
    }

    @Override
    public <T> Optional<T> show(SlicerDialog<T> dialog) {
        try {
            var values = SlicerInputs.resolve(dialog.layout(), store.providerDefaults(providerId), overrides);
            T settings = SlicerInputs.build(dialog, values);
            store.saveProviderDefaults(providerId, values);
            return Optional.of(settings);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
