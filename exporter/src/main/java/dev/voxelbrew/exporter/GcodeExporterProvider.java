package dev.voxelbrew.exporter;

import dev.voxelbrew.api.exporter.*;

public final class GcodeExporterProvider implements ExporterProvider<FileExportDialog.Destination> {
    @Override
    public ExporterDescriptor descriptor() {
        return new ExporterDescriptor("gcode", "G-code", dev.voxelbrew.util.update.BuildVersion.current());
    }

    @Override
    public Exporter<FileExportDialog.Destination> create() {
        return new FileExporter("gcode", "G-code (*.gcode)", (document, path, overwrite, window) -> {
            var lines = document.activeGcode();
            if (lines.isEmpty()) throw new java.io.IOException("No G-code for the active plate; slice it first");
            AtomicExport.write(new FileExportDialog.Destination(path, overwrite), output -> {
                var writer = new java.io.OutputStreamWriter(output, java.nio.charset.StandardCharsets.UTF_8);
                for (String line : lines) {
                    writer.write(line);
                    writer.write('\n');
                }
                writer.flush();
            });
        }) {};
    }
}
