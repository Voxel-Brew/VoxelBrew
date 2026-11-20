package dev.voxelbrew.exporter;

import dev.voxelbrew.api.exporter.ExporterDescriptor;
import dev.voxelbrew.api.i18n.TranslationService;
import dev.voxelbrew.util.services.Services;

/** Service provider for portable 3MF. */
public final class ThreeMfExporterProvider extends GeometryExporterProvider {
    public ThreeMfExporterProvider() {
        this(Services.load(TranslationService.class));
    }

    public ThreeMfExporterProvider(@lombok.NonNull TranslationService translations) {
        super(
                new ExporterDescriptor(
                        "3mf",
                        translations.translate("export.format.3mf").orElseThrow(),
                        dev.voxelbrew.util.update.BuildVersion.current()),
                ThreeMfExporter::new);
    }
}
