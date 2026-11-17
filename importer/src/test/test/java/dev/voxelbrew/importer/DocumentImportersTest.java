package dev.voxelbrew.importer;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.importer.*;
import dev.voxelbrew.api.model.CadDocument;
import dev.voxelbrew.engine.persistence.DocumentCodec;
import dev.voxelbrew.util.testing.FileImportWindows;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentImportersTest {
    @TempDir
    Path directory;

    @Test
    void vxbAndJsonPreserveCompleteEditableDefinitionsAndNeverWriteSource() throws Exception {
        CadDocument document;
        try (var input = getClass().getResourceAsStream("/fixtures/reference-schema10.vxb")) {
            document = DocumentCodec.decode(Objects.requireNonNull(input).readAllBytes());
        }
        document.parameters().builtins().fileVersion(17);
        document = document.withGcode(
                Map.of(document.printSetup().activePlateId(), List.of("G21", "", "; saved job", "M104 S0")));
        for (var format : List.of("vxb", "json")) {
            Path source = directory.resolve("editable design." + format);
            if (format.equals("vxb")) Files.write(source, DocumentCodec.encode(document));
            else Files.writeString(source, DocumentCodec.encodeJson(document));
            byte[] original = Files.readAllBytes(source);
            try (var registry = new ServiceLoaderImporterProviderRegistry(directory)) {
                var actual = registry.require(format)
                        .create()
                        .importFrom(FileImportWindows.source(source))
                        .orElseThrow();
                assertThat(DocumentCodec.encode(actual)).containsExactly(DocumentCodec.encode(document));
                assertThat(actual.parameters().builtins().fileVersion()).isEqualTo(17);
                assertThat(actual.features()).hasSize(4);
            }
            assertThat(Files.readAllBytes(source)).containsExactly(original);
        }
    }

    @Test
    void cancellationReturnsEmptyBeforeReading() throws Exception {
        for (var provider : List.of(new VoxelBrewImporterProvider(), new JsonImporterProvider())) {
            ImportWindow cancelled = new ImportWindow() {
                @Override
                public <R> Optional<R> show(ImportDialog<R> dialog) {
                    return Optional.empty();
                }
            };
            assertThat(provider.create().importFrom(cancelled)).isEmpty();
        }
    }

    @Test
    void missingAndMalformedFilesFailWithoutChangingSource() throws Exception {
        assertThatThrownBy(() -> new JsonImporter().importFrom(FileImportWindows.source(directory.resolve("missing"))))
                .isInstanceOf(IllegalArgumentException.class);
        String valid = DocumentCodec.encodeJson(CadDocument.empty());
        for (var invalid : List.of("{", "{}", valid.replace("\"schemaVersion\": 10", "\"schemaVersion\": 999"))) {
            Path source = directory.resolve("bad.json");
            Files.writeString(source, invalid);
            assertThatThrownBy(() -> new JsonImporter().importFrom(FileImportWindows.source(source)))
                    .isInstanceOf(IOException.class);
            assertThat(Files.readString(source)).isEqualTo(invalid);
        }
    }
}
