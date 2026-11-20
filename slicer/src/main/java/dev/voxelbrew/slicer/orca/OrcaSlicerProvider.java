package dev.voxelbrew.slicer.orca;

import dev.voxelbrew.api.i18n.TranslationService;
import dev.voxelbrew.api.model.printing.Cancellation;
import dev.voxelbrew.slicer.internal.ExecutableDiscovery;
import dev.voxelbrew.slicer.internal.ProcessSlicerProvider;
import dev.voxelbrew.util.services.Services;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

public final class OrcaSlicerProvider extends ProcessSlicerProvider {
    public OrcaSlicerProvider() {
        this(Services.load(TranslationService.class));
    }

    public OrcaSlicerProvider(@lombok.NonNull TranslationService translations) {
        super(OrcaSlicerBackend::new, translations);
    }

    @Override
    public List<Path> discoverExecutables(Cancellation cancellation) {
        return ExecutableDiscovery.find(
                "orca-slicer",
                "orca-slicer.exe",
                Path.of("Contents", "MacOS", "OrcaSlicer"),
                Set.of("orcaslicer", "orca-slicer"),
                true,
                List.of(),
                cancellation);
    }
}
