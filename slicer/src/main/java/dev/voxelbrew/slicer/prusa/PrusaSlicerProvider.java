package dev.voxelbrew.slicer.prusa;

import dev.voxelbrew.slicer.internal.ProcessSlicerProvider;

public final class PrusaSlicerProvider extends ProcessSlicerProvider {
    public PrusaSlicerProvider() {
        super(PrusaSlicerBackend::new);
    }
}
