package dev.voxelbrew.importer;

import dev.voxelbrew.api.model.printing.GcodeJob;
import java.io.IOException;
import java.nio.file.Path;

/** Application input port for G-code jobs. */
@FunctionalInterface
public interface GcodeSource {
    GcodeJob read(Path path) throws IOException;

    default GcodeJob readLines(java.util.List<String> lines) throws IOException {
        throw new IOException("This G-code reader does not support embedded jobs");
    }
}
