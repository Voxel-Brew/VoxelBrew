package dev.voxelbrew.exporter;

import dev.voxelbrew.api.exporter.ExporterDescriptor;
import dev.voxelbrew.api.i18n.TranslationService;
import dev.voxelbrew.util.services.Services;

/** Service provider for binary STL. */
public final class StlExporterProvider extends GeometryExporterProvider {
    public StlExporterProvider() {
        this(Services.load(TranslationService.class));
    }

    public StlExporterProvider(@lombok.NonNull TranslationService translations) {
        super(
                new ExporterDescriptor(
                        "stl",
                        translations.translate("export.stl.name"),
                        dev.voxelbrew.util.update.BuildVersion.current()),
                BinaryStlExporter::new);
    }
}
