package dev.voxelbrew.exporter;

import dev.voxelbrew.api.exporter.Exporter;
import dev.voxelbrew.api.exporter.ExporterDescriptor;
import dev.voxelbrew.api.exporter.ExporterProvider;
import dev.voxelbrew.api.exporter.FileExportDialog;

public final class VoxelBrewExporterProvider implements ExporterProvider<FileExportDialog.Destination> {

    @Override
    public ExporterDescriptor descriptor() {
        return new ExporterDescriptor("vxb", "VXB", dev.voxelbrew.util.update.BuildVersion.current());
    }

    @Override
    public Exporter<FileExportDialog.Destination> create() {
        return new VoxelBrewExporter();
    }
}
