package dev.voxelbrew.exporter;

import dev.voxelbrew.api.exporter.*;
import java.nio.file.Path;
import java.util.function.Function;
import lombok.NonNull;

/** Shared implementation for registered geometry format providers. */
public class GeometryExporterProvider implements ExporterProvider<MeshExportDialog.Settings> {
    private final ExporterDescriptor descriptor;
    private final Function<Path, ? extends Exporter<MeshExportDialog.Settings>> factory;

    public GeometryExporterProvider(
            @NonNull ExporterDescriptor descriptor,
            @NonNull Function<Path, ? extends Exporter<MeshExportDialog.Settings>> factory) {
        this.descriptor = descriptor;
        this.factory = factory;
    }

    @Override
    public ExporterDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public Exporter<MeshExportDialog.Settings> create() {
        return factory.apply(
                dev.voxelbrew.util.services.Services.load(dev.voxelbrew.api.userconfig.UserConfigService.class)
                        .pluginsFolder());
    }
}
