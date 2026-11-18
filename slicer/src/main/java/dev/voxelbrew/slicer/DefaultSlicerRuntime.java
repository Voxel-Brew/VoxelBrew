package dev.voxelbrew.slicer;

import dev.voxelbrew.api.slicer.*;
import dev.voxelbrew.api.slicer.SlicerCatalog;
import dev.voxelbrew.api.slicer.Slicing;
import dev.voxelbrew.slicer.prusa.PrusaSlicerBackend;
import java.nio.file.Path;

/** Runtime implementation; the public API has no dependency on this module. */
public final class DefaultSlicerRuntime implements SlicerRuntime {
    @Override
    public SlicerCatalog.Factory slicers() {
        return SlicerRegistry::new;
    }

    @Override
    public Slicing slicing() {
        return SliceService::slice;
    }

    @Override
    public Path defaultSlicerExecutable() {
        return PrusaSlicerBackend.defaultExecutable();
    }
}
