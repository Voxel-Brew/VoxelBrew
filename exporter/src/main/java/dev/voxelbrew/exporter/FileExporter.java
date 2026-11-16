package dev.voxelbrew.exporter;

import dev.voxelbrew.api.exporter.ExportWindow;
import dev.voxelbrew.api.exporter.Exporter;
import dev.voxelbrew.api.exporter.FileExportDialog;
import dev.voxelbrew.engine.model.CadDocument;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import lombok.RequiredArgsConstructor;

/** Shared destination dialog and publication for document exporters. */
@RequiredArgsConstructor
abstract class FileExporter implements Exporter<FileExportDialog.Destination> {
    @FunctionalInterface
    interface Writer {
        void save(CadDocument document, Path path, boolean overwrite, ExportWindow window) throws IOException;
    }

    private final String extension;
    private final String title;
    private final Writer writer;

    @Override
    public final Optional<FileExportDialog.Destination> export(CadDocument document, ExportWindow window)
            throws IOException {
        var selected = window.show(new FileExportDialog(title, extension));
        if (selected.isEmpty()) return Optional.empty();
        var settings = selected.get();
        Path target = settings.path().toAbsolutePath().normalize();
        writer.save(document, target, settings.overwrite(), window);
        return selected;
    }
}
