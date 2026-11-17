package dev.voxelbrew.importer;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.engine.model.CadDocument;
import dev.voxelbrew.engine.persistence.DocumentCodec;
import dev.voxelbrew.importer.api.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@SuppressWarnings("deprecation")
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
            try (var registry = new ImporterRegistry(directory)) {
                var actual =
                        ((ImportResult.Document) ImportService.read(registry, format, source, Map.of())).document();
                assertThat(DocumentCodec.encode(actual)).containsExactly(DocumentCodec.encode(document));
                assertThat(actual.parameters().builtins().fileVersion()).isEqualTo(17);
                assertThat(actual.features().size()).isEqualTo(4);
            }
            assertThat(Files.readAllBytes(source)).containsExactly(original);
        }
    }

    @Test
    void cancellationDoesNotScheduleReadsAndRejectedWorkersCompleteExceptionally() {
        for (var provider : List.of(new VoxelBrewImporterProvider(), new JsonImporterProvider())) {
            var tasks = new AtomicInteger();
            var importer = provider.create(task -> tasks.incrementAndGet());
            var host = new ImportDialogs().register(FileImportDialog.class, request -> Optional.empty());
            assertThatThrownBy(() -> importer.importFrom(host).join()).isInstanceOf(CancellationException.class);
            assertThat(tasks.get()).isEqualTo(0);
            var failure = new RejectedExecutionException("closed");
            var rejected = provider.create(task -> {
                        throw failure;
                    })
                    .importFrom(FileImportDialog.source(directory.resolve("missing")));
            assertThat(org.assertj.core.api.Assertions.catchThrowableOfType(rejected::join, CompletionException.class)
                            .getCause())
                    .isSameAs(failure);
        }
    }

    @Test
    void jsonRejectsMalformedUnknownAndFutureDocumentsWithoutChangingSource() throws Exception {
        String valid = DocumentCodec.encodeJson(CadDocument.empty());
        for (var invalid : List.of(
                "{",
                "{}",
                valid.replace("\"schemaVersion\": 10", "\"schemaVersion\": 999"),
                valid.replaceFirst("\\{", "{\"unknownField\":true,"),
                valid.replace("MILLIMETERS", "UNRECOGNIZED"))) {
            Path source = directory.resolve("bad.json");
            Files.writeString(source, invalid);
            assertThatThrownBy(() -> new JsonImporter(Runnable::run)
                            .importFrom(FileImportDialog.source(source))
                            .join())
                    .isInstanceOf(CompletionException.class);
            assertThat(Files.readString(source)).isEqualTo(invalid);
        }
    }

    @Test
    void interactionRunsBeforeWorkerAndMissingFileFailsOnWorker() {
        var queued = new ArrayList<Runnable>();
        var importer = new JsonImporter(queued::add);
        var result = importer.importFrom(FileImportDialog.source(directory.resolve("missing.json")));
        assertThat(result.isDone()).isFalse();
        assertThat(queued.size()).isEqualTo(1);
        queued.removeFirst().run();
        assertThat(org.assertj.core.api.Assertions.catchThrowableOfType(result::join, CompletionException.class)
                        .getCause())
                .isInstanceOf(java.io.IOException.class);
    }
}
