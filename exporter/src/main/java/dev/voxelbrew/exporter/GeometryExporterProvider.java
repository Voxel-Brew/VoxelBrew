package dev.voxelbrew.exporter;

import dev.voxelbrew.api.exporter.*;
import java.nio.file.Path;
import java.util.function.Function;

/** Shared implementation for registered geometry format providers. */
public class GeometryExporterProvider implements ExporterProvider<MeshExportDialog.Settings> {
    private final ExporterDescriptor descriptor;
    private final Function<Path, ? extends Exporter<MeshExportDialog.Settings>> factory;

    public GeometryExporterProvider(
            ExporterDescriptor descriptor, Function<Path, ? extends Exporter<MeshExportDialog.Settings>> factory) {
        this.descriptor = java.util.Objects.requireNonNull(descriptor);
        this.factory = java.util.Objects.requireNonNull(factory);
    }

    @Override
    public ExporterDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public Exporter<MeshExportDialog.Settings> create() {
        return factory.apply(dev.voxelbrew.util.config.ApplicationPaths.plugins());
    }
}
