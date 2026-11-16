package dev.voxelbrew.exporter;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.util.JsonFormat;
import dev.voxelbrew.api.exporter.FileExportDialog;
import dev.voxelbrew.engine.model.CadDocument;
import dev.voxelbrew.engine.persistence.DocumentCodec;
import dev.voxelbrew.engine.persistence.proto.DocumentProto;
import dev.voxelbrew.importer.DocumentReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentExportersTest {
    @TempDir
    Path directory;

    @Test
    void cancellationDoesNotWriteForFileFormats() throws Exception {
        for (var provider : java.util.List.of(
                new VoxelBrewExporterProvider(), new JsonExporterProvider(), new StepExporterProvider())) {
            var format = provider.descriptor().id();
            var exporter = provider.create();
            dev.voxelbrew.api.exporter.ExportWindow window = new dev.voxelbrew.api.exporter.ExportWindow() {
                @Override
                public <R> Optional<R> show(dev.voxelbrew.api.exporter.ExportDialog<R> dialog) {
                    assertEquals(format, ((FileExportDialog) dialog).extension());
                    return Optional.empty();
                }
            };
            assertEquals(Optional.empty(), exporter.export(CadDocument.empty(), window));
        }
    }

    @Test
    void nativeLoadingFailuresPropagateToTheCaller() {
        var failure = new UnsatisfiedLinkError("unavailable native dependency");
        var exporter = new FileExporter("step", "STEP", (document, target, overwrite, window) -> {
            throw failure;
        }) {};
        assertSame(
                failure,
                assertThrows(
                        UnsatisfiedLinkError.class,
                        () -> exporter.export(
                                CadDocument.empty(),
                                dev.voxelbrew.util.testing.FileExportWindows.destination(
                                        directory.resolve("part.step"), false))));
    }

    @Test
    void vxbUsesTheSameHeadlessParentAndPreservesExistingFiles() throws Exception {
        var document = CadDocument.empty();
        Path target = directory.resolve("editable.vxb");
        var exporter = new VoxelBrewExporter();
        assertEquals(
                Optional.of(new FileExportDialog.Destination(target, false)),
                exporter.export(document, dev.voxelbrew.util.testing.FileExportWindows.destination(target, false)));
        assertEquals(document.id(), DocumentReader.load(target).id());
        byte[] original = Files.readAllBytes(target);
        assertThrows(
                IllegalArgumentException.class,
                () -> exporter.export(
                        document, dev.voxelbrew.util.testing.FileExportWindows.destination(target, false)));
        assertArrayEquals(original, Files.readAllBytes(target));
        assertEquals(1, document.parameters().builtins().fileVersion());
    }

    @Test
    void jsonPreservesCompleteSchemaWithoutChangingSaveMetadata() throws Exception {
        CadDocument document;
        try (var input = getClass().getResourceAsStream("/fixtures/reference-schema10.vxb")) {
            document =
                    DocumentCodec.decode(java.util.Objects.requireNonNull(input).readAllBytes());
        }
        long previousVersion = document.parameters().builtins().fileVersion();
        Path project = directory.resolve("original.vxb");
        DocumentWriter.save(document, project, false);
        byte[] encoded = DocumentCodec.encode(document);
        var expected = DocumentProto.Document.parseFrom(Arrays.copyOfRange(encoded, 44, encoded.length));
        Path target = directory.resolve("document.json");
        var exporter = new JsonExporter();
        exporter.export(document, dev.voxelbrew.util.testing.FileExportWindows.destination(target, false));
        var actual = DocumentProto.Document.newBuilder();
        JsonFormat.parser().merge(Files.readString(target), actual);
        assertEquals(expected, actual.build());
        assertEquals(previousVersion + 1, document.parameters().builtins().fileVersion());
        assertEquals(project.toString(), document.parameters().text("=$vb.project.path"));
        byte[] original = Files.readAllBytes(target);
        assertThrows(
                IllegalArgumentException.class,
                () -> exporter.export(
                        document, dev.voxelbrew.util.testing.FileExportWindows.destination(target, false)));
        assertArrayEquals(original, Files.readAllBytes(target));
        exporter.export(document, dev.voxelbrew.util.testing.FileExportWindows.destination(target, true));
        assertArrayEquals(original, Files.readAllBytes(target));
        try (var files = Files.list(directory)) {
            assertEquals(2, files.count());
        }
    }
}
