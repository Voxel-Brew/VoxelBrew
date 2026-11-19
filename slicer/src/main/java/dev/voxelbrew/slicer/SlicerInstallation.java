package dev.voxelbrew.slicer;

import dev.voxelbrew.api.model.printing.PrintSetup;
import dev.voxelbrew.api.slicer.SlicerWindow;
import dev.voxelbrew.slicer.internal.BundledSlicers;
import java.util.Map;
import java.util.UUID;

/** A named provider configuration with the result of its most recent operational check. */
public record SlicerInstallation(
        UUID id, String providerId, String name, String version, String failure, Map<String, String> settings) {
    public static final String SELECTION = "print.slicer.installation";

    public SlicerInstallation {
        if (providerId == null || !providerId.matches("[a-z0-9][a-z0-9.-]*"))
            throw new IllegalArgumentException("Invalid slicer provider ID");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Enter an installation name");
        java.util.Objects.requireNonNull(id);
        java.util.Objects.requireNonNull(version);
        java.util.Objects.requireNonNull(failure);
        if (version.isBlank() == failure.isBlank())
            throw new IllegalArgumentException("Invalid installation check result");
        settings = Map.copyOf(settings);
    }

    public boolean operational() {
        return !version.isBlank();
    }

    public boolean bundled() {
        return BundledSlicers.reserved(id);
    }

    public SlicerWindow executionWindow(Map<String, String> overrides) {
        if (!bundled()) return SlicerInputs.window(settings, overrides);
        if (!overrides.isEmpty()) throw new IllegalArgumentException("Bundled slicer settings are read-only");
        return BundledSlicers.window(id);
    }

    public static String selectedId(PrintSetup setup) {
        return setup.workspaceSettings().getOrDefault(SELECTION, "");
    }

    public PrintSetup select(PrintSetup setup) {
        var workspace = new java.util.HashMap<>(setup.workspaceSettings());
        workspace.put(SELECTION, id.toString());
        return setup.withWorkspaceSettings(workspace);
    }
}
