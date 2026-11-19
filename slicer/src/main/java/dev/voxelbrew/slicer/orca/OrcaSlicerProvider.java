package dev.voxelbrew.slicer.orca;

import dev.voxelbrew.slicer.internal.ProcessSlicerProvider;

public final class OrcaSlicerProvider extends ProcessSlicerProvider {
    public OrcaSlicerProvider() {
        super(OrcaSlicerBackend::new);
    }
}
