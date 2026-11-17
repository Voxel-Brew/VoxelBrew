package dev.voxelbrew.importer;

import dev.voxelbrew.api.importer.*;
import dev.voxelbrew.api.model.CadDocument;
import java.io.*;
import java.util.Optional;

/** Shared synchronous file interaction and bounded reading for built-ins. */
@lombok.RequiredArgsConstructor
abstract class FileImporter implements Importer {
    private final String title;
    private final String extension;

    @Override
    public final Optional<CadDocument> importFrom(ImportWindow window) throws IOException {
        var selected = window.show(new FileImportDialog(title, extension));
        if (selected.isEmpty()) return Optional.empty();
        try (var input = ImportInput.open(selected.orElseThrow().path())) {
            return Optional.of(read(input));
        }
    }

    protected abstract CadDocument read(InputStream input) throws IOException;
}
