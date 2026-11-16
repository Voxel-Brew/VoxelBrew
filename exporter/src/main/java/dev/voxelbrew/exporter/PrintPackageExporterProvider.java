package dev.voxelbrew.exporter;

import dev.voxelbrew.api.exporter.*;

public final class PrintPackageExporterProvider implements ExporterProvider<FileExportDialog.Destination> {
    @Override
    public ExporterDescriptor descriptor() {
        return new ExporterDescriptor(
                "gcode-package", "Sliced printer package", dev.voxelbrew.util.update.BuildVersion.current());
    }

    @Override
    public Exporter<FileExportDialog.Destination> create() {
        return new FileExporter(
                "gcode.3mf", "Sliced printer package (*.gcode.3mf)", (document, path, overwrite, window) -> {
                    var artifact =
                            document.printArtifacts().get(document.printSetup().activePlateId());
                    if (artifact == null || !artifact.format().equals("gcode.3mf"))
                        throw new java.io.IOException("No sliced package for this plate");
                    AtomicExport.write(
                            new FileExportDialog.Destination(path, overwrite),
                            output -> output.write(artifact.bytes()));
                }) {};
    }
}
