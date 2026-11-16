package dev.voxelbrew.exporter;

import dev.voxelbrew.api.exporter.ExporterDescriptor;

/** Service provider for binary STL. */
public final class StlExporterProvider extends GeometryExporterProvider {
    public StlExporterProvider() {
        super(
                new ExporterDescriptor("stl", "Binary STL", dev.voxelbrew.util.update.BuildVersion.current()),
                BinaryStlExporter::new);
    }
}
