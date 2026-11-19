package dev.voxelbrew.slicer.prusa;

import dev.voxelbrew.api.model.printing.Cancellation;
import dev.voxelbrew.slicer.internal.ExecutableDiscovery;
import dev.voxelbrew.slicer.internal.ProcessSlicerProvider;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

public final class PrusaSlicerProvider extends ProcessSlicerProvider {
    public PrusaSlicerProvider() {
        super(PrusaSlicerBackend::new);
    }

    @Override
    public List<Path> discoverExecutables(Cancellation cancellation) {
        return ExecutableDiscovery.find(
                "prusa-slicer",
                "prusa-slicer-console.exe",
                Path.of("Contents", "MacOS", "PrusaSlicer"),
                Set.of("prusaslicer", "prusa3d", "prusa-slicer"),
                true,
                List.of(PrusaSlicerBackend.defaultExecutable()),
                cancellation);
    }
}
