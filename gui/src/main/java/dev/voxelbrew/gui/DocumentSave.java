package dev.voxelbrew.gui;

import dev.voxelbrew.engine.model.CadDocument;
import java.io.IOException;
import java.nio.file.Path;

/** Save callback for desktop document workflows. */
@FunctionalInterface
interface DocumentSave {
    void save(CadDocument document, Path path, boolean overwrite) throws IOException;
}
