package dev.voxelbrew.exporter;

import dev.voxelbrew.api.i18n.TranslationService;
import dev.voxelbrew.util.preferences.PluginDirectorySource;
import dev.voxelbrew.util.services.Services;

/** Exact CAD exchange with STEP AP242; exports complete design solids in millimeters. */
public final class StepExporter extends FileExporter {
    public StepExporter() {
        this(Services.load(TranslationService.class));
    }

    public StepExporter(@lombok.NonNull TranslationService translations) {
        super(
                "step",
                translations.translate("export.step.title"),
                (document, destination, overwrite, window) -> StepDocumentWriter.save(
                        document,
                        destination,
                        overwrite,
                        window instanceof PluginDirectorySource source
                                ? source.pluginDirectory()
                                : dev.voxelbrew.engine.feature.FeatureRegistry.defaultDirectory()));
    }
}
