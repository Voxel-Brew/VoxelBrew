package dev.voxelbrew.exporter;

import static org.assertj.core.api.Assertions.*;

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

@SuppressWarnings("deprecation")
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
                    assertThat(((FileExportDialog) dialog).extension()).isEqualTo(format);
                    return Optional.empty();
                }
            };
            assertThat(exporter.export(CadDocument.empty(), window)).isEqualTo(Optional.empty());
        }
    }

    @Test
    void nativeLoadingFailuresPropagateToTheCaller() {
        var failure = new UnsatisfiedLinkError("unavailable native dependency");
        var exporter = new FileExporter("step", "STEP", (document, target, overwrite, window) -> {
            throw failure;
        }) {};
        assertThat(org.assertj.core.api.Assertions.catchThrowableOfType(
                        () -> exporter.export(
                                CadDocument.empty(),
                                dev.voxelbrew.util.testing.FileExportWindows.destination(
                                        directory.resolve("part.step"), false)),
                        UnsatisfiedLinkError.class))
                .isSameAs(failure);
    }

    @Test
    void vxbUsesTheSameHeadlessParentAndPreservesExistingFiles() throws Exception {
        var document = CadDocument.empty();
        Path target = directory.resolve("editable.vxb");
        var exporter = new VoxelBrewExporter();
        assertThat(exporter.export(document, dev.voxelbrew.util.testing.FileExportWindows.destination(target, false)))
                .isEqualTo(Optional.of(new FileExportDialog.Destination(target, false)));
        assertThat(DocumentReader.load(target).id()).isEqualTo(document.id());
        byte[] original = Files.readAllBytes(target);
        assertThatThrownBy(() -> exporter.export(
                        document, dev.voxelbrew.util.testing.FileExportWindows.destination(target, false)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(Files.readAllBytes(target)).containsExactly(original);
        assertThat(document.parameters().builtins().fileVersion()).isEqualTo(1);
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
        assertThat(actual.build()).isEqualTo(expected);
        assertThat(document.parameters().builtins().fileVersion()).isEqualTo(previousVersion + 1);
        assertThat(document.parameters().text("=$vb.project.path")).isEqualTo(project.toString());
        byte[] original = Files.readAllBytes(target);
        assertThatThrownBy(() -> exporter.export(
                        document, dev.voxelbrew.util.testing.FileExportWindows.destination(target, false)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(Files.readAllBytes(target)).containsExactly(original);
        exporter.export(document, dev.voxelbrew.util.testing.FileExportWindows.destination(target, true));
        assertThat(Files.readAllBytes(target)).containsExactly(original);
        try (var files = Files.list(directory)) {
            assertThat(files.count()).isEqualTo(2);
        }
    }
}
