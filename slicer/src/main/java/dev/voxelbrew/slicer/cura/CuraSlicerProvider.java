package dev.voxelbrew.slicer.cura;

import dev.voxelbrew.api.model.printing.Cancellation;
import dev.voxelbrew.slicer.internal.ExecutableDiscovery;
import dev.voxelbrew.slicer.internal.ProcessSlicerProvider;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

public final class CuraSlicerProvider extends ProcessSlicerProvider {
    public CuraSlicerProvider() {
        super(CuraSlicerBackend::new);
    }

    @Override
    public List<Path> discoverExecutables(Cancellation cancellation) {
        return ExecutableDiscovery.find(
                "CuraEngine",
                "CuraEngine.exe",
                Path.of("Contents", "Resources", "CuraEngine"),
                Set.of("cura", "ultimaker cura"),
                false,
                List.of(),
                cancellation);
    }
}
