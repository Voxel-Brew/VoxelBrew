package dev.voxelbrew.exporter;

import dev.voxelbrew.util.config.PluginDirectorySource;

/** Exact CAD exchange with STEP AP242; exports complete design solids in millimeters. */
public final class StepExporter extends FileExporter {
    public StepExporter() {
        super(
                "step",
                "STEP AP242 (*.step)",
                (document, destination, overwrite, window) -> StepDocumentWriter.save(
                        document,
                        destination,
                        overwrite,
                        window instanceof PluginDirectorySource source
                                ? source.pluginDirectory()
                                : dev.voxelbrew.engine.feature.FeatureRegistry.defaultDirectory()));
    }
}
