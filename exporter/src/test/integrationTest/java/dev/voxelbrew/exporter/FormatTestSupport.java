package dev.voxelbrew.exporter;

import dev.voxelbrew.api.exporter.ExporterProviderRegistry;
import dev.voxelbrew.api.exporter.FileExportDialog;
import dev.voxelbrew.api.model.mesh.MeshOptions;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/** Exercise serializers against selected synthetic views without inventing CAD history. */
public final class FormatTestSupport {
    public static void write(
            ExporterProviderRegistry registry,
            String format,
            ModelView view,
            MeshOptions mesh,
            Map<String, String> options,
            Path target,
            boolean overwrite)
            throws IOException {
        var exporter = (MeshExporter) registry.require(format).create();
        exporter.writeFile(
                view,
                new MeshExportDialog.Settings(
                        ExportSelection.design(null),
                        mesh,
                        options,
                        new FileExportDialog.Destination(target, overwrite)));
    }

    private FormatTestSupport() {}
}
