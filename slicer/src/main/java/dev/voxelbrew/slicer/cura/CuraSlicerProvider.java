package dev.voxelbrew.slicer.cura;

import dev.voxelbrew.api.i18n.TranslationService;
import dev.voxelbrew.api.model.printing.Cancellation;
import dev.voxelbrew.slicer.internal.ExecutableDiscovery;
import dev.voxelbrew.slicer.internal.ProcessSlicerProvider;
import dev.voxelbrew.util.services.Services;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

public final class CuraSlicerProvider extends ProcessSlicerProvider {
    public CuraSlicerProvider() {
        this(Services.load(TranslationService.class));
    }

    public CuraSlicerProvider(@lombok.NonNull TranslationService translations) {
        super(CuraSlicerBackend::new, translations);
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
