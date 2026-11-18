package dev.voxelbrew.exporter;

import dev.voxelbrew.api.exporter.*;

public final class StepExporterProvider implements ExporterProvider<FileExportDialog.Destination> {
    public StepExporterProvider() {}

    @Override
    public ExporterDescriptor descriptor() {
        return new ExporterDescriptor(
                "step", "STEP AP242 (exact solids)", dev.voxelbrew.util.update.BuildVersion.current());
    }

    @Override
    public Exporter<FileExportDialog.Destination> create() {
        return new StepExporter();
    }
}
