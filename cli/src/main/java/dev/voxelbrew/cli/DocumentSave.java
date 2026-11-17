package dev.voxelbrew.cli;

import dev.voxelbrew.api.model.CadDocument;
import java.io.IOException;
import java.nio.file.Path;

/** Save callback for CLI document commands. */
@FunctionalInterface
interface DocumentSave {
    void save(CadDocument document, Path path, boolean overwrite) throws IOException;
}
