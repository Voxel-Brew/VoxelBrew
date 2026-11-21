package dev.voxelbrew.exporter;

import dev.voxelbrew.api.exporter.ExporterDescriptor;
import dev.voxelbrew.api.i18n.TranslationService;
import dev.voxelbrew.util.services.Services;

/** Service provider for PrusaSlicer projects. */
public final class PrusaProjectExporterProvider extends GeometryExporterProvider {
    public PrusaProjectExporterProvider() {
        this(Services.load(TranslationService.class));
    }

    public PrusaProjectExporterProvider(@lombok.NonNull TranslationService translations) {
        super(
                new ExporterDescriptor(
                        "prusa-project",
                        translations.translate("export.prusaproject.name"),
                        dev.voxelbrew.util.update.BuildVersion.current()),
                dev.voxelbrew.exporter.printing.PrusaProjectExporter::new);
    }
}
