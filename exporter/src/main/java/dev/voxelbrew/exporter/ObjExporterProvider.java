package dev.voxelbrew.exporter;

import dev.voxelbrew.api.exporter.ExporterDescriptor;

/** Service provider for Wavefront OBJ. */
public final class ObjExporterProvider extends GeometryExporterProvider {
    public ObjExporterProvider() {
        super(
                new ExporterDescriptor("obj", "Wavefront OBJ", dev.voxelbrew.util.update.BuildVersion.current()),
                ObjExporter::new);
    }
}
