package dev.voxelbrew.importer;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.engine.model.Feature;
import dev.voxelbrew.engine.persistence.DocumentCodec;
import dev.voxelbrew.engine.persistence.DocumentFormatException;
import java.io.RandomAccessFile;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@SuppressWarnings("deprecation")
class DocumentReaderTest {
    @TempDir
    Path directory;

    @Test
    void opensCurrentReferenceThroughTheImportModule() throws Exception {
        Path file = directory.resolve("legacy design with spaces.vxb");
        try (var input = getClass().getResourceAsStream("/fixtures/reference-schema10.vxb")) {
            Files.copy(java.util.Objects.requireNonNull(input), file);
        }
        var document = DocumentReader.load(file);
        assertThat(document.features().size()).isEqualTo(4);
        assertThat(document.cursor()).isEqualTo(4);
        var extrusion = document.features().stream()
                .filter(Feature.Extrude.class::isInstance)
                .map(Feature.Extrude.class::cast)
                .findFirst()
                .orElseThrow();
        assertThat(extrusion.distance()).isEqualTo(8);
        assertThat(document.features().getLast() instanceof Feature.ThroughCut).isTrue();
    }

    @Test
    void rejectsOversizedTruncatedAndCorruptFilesWithoutChangingThem() throws Exception {
        Path file = directory.resolve("bad.vxb");
        try (var sparse = new RandomAccessFile(file.toFile(), "rw")) {
            sparse.setLength(DocumentCodec.MAX_ENCODED_BYTES + 4096L);
        }
        long size = Files.size(file);
        assertThatThrownBy(() -> DocumentReader.load(file)).isInstanceOf(DocumentFormatException.class);
        assertThat(Files.size(file)).isEqualTo(size);
        Files.write(file, new byte[] {'J', 'C', 'A', 'D'});
        assertThatThrownBy(() -> DocumentReader.load(file)).isInstanceOf(DocumentFormatException.class);
        byte[] data;
        try (var input = getClass().getResourceAsStream("/fixtures/reference-schema10.vxb")) {
            data = java.util.Objects.requireNonNull(input).readAllBytes();
        }
        data[data.length - 1] ^= 1;
        Files.write(file, data);
        assertThat(org.assertj.core.api.Assertions.catchThrowableOfType(
                                () -> DocumentReader.load(file), DocumentFormatException.class)
                        .getMessage()
                        .contains("checksum"))
                .isTrue();
        assertThat(Files.readAllBytes(file)).containsExactly(data);
    }
}
