package dev.voxelbrew.cli;

import dev.voxelbrew.api.slicer.*;
import dev.voxelbrew.slicer.SlicerInputs;
import dev.voxelbrew.util.config.UserConfiguration;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.*;

final class CliSlicerWindow implements SlicerWindow {
    private final UserConfiguration configuration;
    private final String providerId;
    private final Map<String, String> overrides;

    CliSlicerWindow(UserConfiguration configuration, String providerId, Map<String, String> overrides) {
        this.configuration = configuration;
        this.providerId = providerId;
        this.overrides = Map.copyOf(overrides);
    }

    @Override
    public <T> Optional<T> show(SlicerDialog<T> dialog) {
        try {
            var saved = configuration.section("slicer", providerId);
            var values = SlicerInputs.resolve(dialog.layout(), saved.read(), overrides);
            T settings = SlicerInputs.build(dialog, values);
            saved.update(values, Set.of());
            return Optional.of(settings);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
