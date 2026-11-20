package dev.voxelbrew.util.testing;

import dev.voxelbrew.api.importer.FileImportDialog;
import dev.voxelbrew.api.importer.ImportDialog;
import dev.voxelbrew.api.importer.ImportWindow;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/** Headless local file selection for importer tests. */
public final class FileImportWindows {
    public static ImportWindow source(Path path) {
        return new ImportWindow() {
            @Override
            public <R> Optional<R> show(ImportDialog<R> dialog) {
                if (!(dialog instanceof FileImportDialog file)) {
                    throw new UnsupportedOperationException(
                            "Unsupported import dialog: " + dialog.getClass().getName());
                }
                return Optional.of(
                        dialog.buildSettings(Map.of(file.sourceInput().id(), path.toString())));
            }
        };
    }

    private FileImportWindows() {}
}
