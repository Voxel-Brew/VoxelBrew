package dev.voxelbrew.importer;

import dev.voxelbrew.api.i18n.TranslationService;
import dev.voxelbrew.api.importer.*;
import dev.voxelbrew.util.services.Services;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

public final class VoxelBrewImporterProvider implements ImporterProvider {
    private final TranslationService translations;

    public VoxelBrewImporterProvider() {
        this(Services.load(TranslationService.class));
    }

    @SuppressFBWarnings(
            value = "EI_EXPOSE_REP2",
            justification = "The injected translation service is intentionally shared.")
    public VoxelBrewImporterProvider(@lombok.NonNull TranslationService translations) {
        this.translations = translations;
    }

    @Override
    public ImporterDescriptor descriptor() {
        return new ImporterDescriptor(
                "vxb",
                translations.translate("import.vxb.title").orElseThrow(),
                dev.voxelbrew.util.update.BuildVersion.current());
    }

    @Override
    public Importer create() {
        return new VoxelBrewImporter(translations);
    }
}
