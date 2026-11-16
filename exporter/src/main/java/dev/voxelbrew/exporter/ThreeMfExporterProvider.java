package dev.voxelbrew.exporter;

import dev.voxelbrew.api.exporter.ExporterDescriptor;

/** Service provider for portable 3MF. */
public final class ThreeMfExporterProvider extends GeometryExporterProvider {
    public ThreeMfExporterProvider() {
        super(
                new ExporterDescriptor("3mf", "Portable 3MF Core", dev.voxelbrew.util.update.BuildVersion.current()),
                ThreeMfExporter::new);
    }
}
