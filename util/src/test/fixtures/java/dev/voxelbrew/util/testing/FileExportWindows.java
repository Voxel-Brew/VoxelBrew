package dev.voxelbrew.util.testing;

import dev.voxelbrew.api.exporter.ExportDialog;
import dev.voxelbrew.api.exporter.ExportWindow;
import dev.voxelbrew.api.exporter.FileExportDialog;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Headless file destination for exporter and frontend tests. */
public final class FileExportWindows {
    public static ExportWindow destination(Path path, boolean overwrite) {
        Objects.requireNonNull(path);
        return new ExportWindow() {
            @Override
            public <R> Optional<R> show(ExportDialog<R> dialog) {
                if (!(dialog instanceof FileExportDialog fileDialog)) {
                    throw new UnsupportedOperationException(
                            "Unsupported export dialog: " + dialog.getClass().getName());
                }
                var values = Map.of(
                        fileDialog.destinationInput().id(), path.toString(),
                        fileDialog.overwriteInput().id(), Boolean.toString(overwrite));
                return Optional.of(dialog.buildSettings(values));
            }
        };
    }

    private FileExportWindows() {}
}
