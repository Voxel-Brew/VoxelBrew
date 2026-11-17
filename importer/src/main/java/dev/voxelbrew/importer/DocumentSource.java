package dev.voxelbrew.importer;

import dev.voxelbrew.api.model.CadDocument;
import java.io.IOException;
import java.nio.file.Path;

/** Application input port for editable documents. */
@FunctionalInterface
public interface DocumentSource {
    CadDocument load(Path path) throws IOException;
}
