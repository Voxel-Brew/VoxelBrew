package dev.voxelbrew.exporter;

import dev.voxelbrew.api.exporter.ExporterDescriptor;

/** Service provider for PrusaSlicer projects. */
public final class PrusaProjectExporterProvider extends GeometryExporterProvider {
    public PrusaProjectExporterProvider() {
        super(
                new ExporterDescriptor(
                        "prusa-project", "PrusaSlicer project (3MF)", dev.voxelbrew.util.update.BuildVersion.current()),
                dev.voxelbrew.exporter.printing.PrusaProjectExporter::new);
    }
}
