package dev.voxelbrew.slicer.cura;

import dev.voxelbrew.slicer.internal.ProcessSlicerProvider;

public final class CuraSlicerProvider extends ProcessSlicerProvider {
    public CuraSlicerProvider() {
        super(CuraSlicerBackend::new);
    }
}
