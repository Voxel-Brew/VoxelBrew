package dev.voxelbrew.exporter;

import dev.voxelbrew.api.i18n.TranslationService;
import dev.voxelbrew.util.services.Services;

/** Editable binary document exporter; retains schema and overwrite protections. */
public final class VoxelBrewExporter extends FileExporter {
    public VoxelBrewExporter() {
        this(Services.load(TranslationService.class));
    }

    public VoxelBrewExporter(@lombok.NonNull TranslationService translations) {
        this(translations, (document, path, overwrite, window) -> DocumentWriter.save(document, path, overwrite));
    }

    VoxelBrewExporter(Writer writer) {
        this(Services.load(TranslationService.class), writer);
    }

    VoxelBrewExporter(TranslationService translations, Writer writer) {
        super("vxb", translations.translate("export.vxb.title").orElseThrow(), writer);
    }
}
