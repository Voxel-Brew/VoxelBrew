package dev.voxelbrew.exporter;

import dev.voxelbrew.api.exporter.Exporter;
import dev.voxelbrew.api.exporter.ExporterDescriptor;
import dev.voxelbrew.api.exporter.ExporterProvider;
import dev.voxelbrew.api.exporter.FileExportDialog;

public final class JsonExporterProvider implements ExporterProvider<FileExportDialog.Destination> {
    @javax.inject.Inject
    public JsonExporterProvider() {}

    @Override
    public ExporterDescriptor descriptor() {
        return new ExporterDescriptor("json", "JSON", dev.voxelbrew.util.update.BuildVersion.current());
    }

    @Override
    public Exporter<FileExportDialog.Destination> create() {
        return new JsonExporter();
    }
}
