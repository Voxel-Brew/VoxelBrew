package dev.voxelbrew.exporter;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.model.CadDocument;
import dev.voxelbrew.engine.persistence.DocumentCodec;
import dev.voxelbrew.engine.persistence.DocumentFormatException;
import dev.voxelbrew.engine.persistence.proto.DocumentProto;
import dev.voxelbrew.importer.DocumentReader;
import dev.voxelbrew.util.Version;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentWriterTest {
    @TempDir
    Path directory;

    @Test
    void saveCounterTracksPublicationAndSaveAsWithoutAffectingDesignIdentity() throws Exception {
        var document = CadDocument.empty();
        var fingerprint = DocumentCodec.fingerprint(document);
        Path first = directory.resolve("first.vxb");
        DocumentWriter.save(document, first, false);
        assertThat(document.parameters().text("=$vb.project.name")).isEqualTo("first");
        assertThat(document.parameters().text("=$vb.project.filename")).isEqualTo("first.vxb");
        assertThat(document.parameters().text("=$vb.project.path"))
                .isEqualTo(first.toAbsolutePath().toString());
        assertThat(document.parameters().text("=$vb.project.folder"))
                .isEqualTo(directory.toAbsolutePath().toString());
        assertThat(document.parameters().builtins().fileVersion()).isEqualTo(1);
        assertThatThrownBy(() -> DocumentWriter.save(document, first, false)).isInstanceOf(IOException.class);
        assertThat(document.parameters().builtins().fileVersion()).isEqualTo(1);
        var reopened = DocumentReader.load(first);
        assertThat(reopened.parameters().builtins().fileVersion()).isEqualTo(1);
        assertThat(reopened.parameters().text("=$vb.project.name")).isEqualTo("first");
        Path existing = directory.resolve("existing.vxb");
        DocumentWriter.save(CadDocument.empty(), existing, false);
        assertThatThrownBy(() -> DocumentWriter.save(reopened, existing, false)).isInstanceOf(IOException.class);
        assertThat(reopened.parameters().text("=$vb.project.name")).isEqualTo("first");
        DocumentWriter.save(reopened, directory.resolve("copy.vxb"), false);
        assertThat(reopened.parameters().text("=$vb.project.name")).isEqualTo("copy");
        assertThat(DocumentReader.load(directory.resolve("copy.vxb"))
                        .parameters()
                        .text("=$vb.project.name"))
                .isEqualTo("copy");
        assertThat(reopened.parameters().builtins().fileVersion()).isEqualTo(2);
        assertThat(DocumentCodec.fingerprint(reopened)).isEqualTo(fingerprint);
    }

    @Test
    void currentSchemaUpdatesMetadataWithoutChangingDefinitions() throws Exception {
        byte[] baseline;
        try (var input = getClass().getResourceAsStream("/fixtures/reference-schema10.vxb")) {
            baseline = Objects.requireNonNull(input).readAllBytes();
        }
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(baseline)))
                .isEqualTo("c4e5b9b089b98334a8feaad44189ae30102bd48fea47acd410e6c38b31339053");
        var document = DocumentCodec.decode(baseline);
        var upgradedDefinitions = message(baseline).toBuilder()
                .setSchemaVersion(DocumentCodec.SCHEMA_VERSION)
                .clearApplicationVersion();
        for (var feature : upgradedDefinitions.getFeaturesBuilderList())
            if (feature.hasSketch())
                feature.getSketchBuilder()
                        .getPlaneBuilder()
                        .addAllFrame(Arrays.stream(dev.voxelbrew.api.model.Transform.IDENTITY.matrix())
                                .boxed()
                                .toList());
        assertThat(DocumentCodec.fingerprint(document))
                .isEqualTo(HexFormat.of()
                        .formatHex(MessageDigest.getInstance("SHA-256")
                                .digest(upgradedDefinitions.build().toByteArray())));
        Path file = directory.resolve("saved with spaces.vxb");
        DocumentWriter.save(document, file, false);
        byte[] saved = Files.readAllBytes(file);
        assertThat(message(saved))
                .isEqualTo(upgradedDefinitions
                        .setApplicationVersion(Version.version())
                        .setFileVersion(1)
                        .build());
        assertThat(DocumentReader.load(file)).isEqualTo(document);
        DocumentWriter.save(document, file, true);
        assertThat(DocumentReader.load(file).parameters().builtins().fileVersion())
                .isEqualTo(2);
    }

    @Test
    void olderApplicationVersionUpgradesOnExplicitSaveAndPreservesTheDesign() throws Exception {
        byte[] baseline;
        try (var input = getClass().getResourceAsStream("/fixtures/reference-schema10.vxb")) {
            baseline = Objects.requireNonNull(input).readAllBytes();
        }
        byte[] older = envelope(
                message(baseline).toBuilder().setApplicationVersion("0.0.0").build());
        Path file = directory.resolve("older application.vxb");
        Files.write(file, older);
        var document = DocumentReader.load(file);
        assertThat(Files.readAllBytes(file))
                .as("Opening must not rewrite the original")
                .containsExactly(older);
        assertThat(document).isEqualTo(DocumentCodec.decode(baseline));
        DocumentWriter.save(document, file, true);
        assertThat(message(Files.readAllBytes(file)).getApplicationVersion()).isEqualTo(Version.version());
        assertThat(DocumentReader.load(file)).isEqualTo(document);
    }

    @Test
    void newerAndMalformedApplicationVersionsCannotBeOverwritten() throws Exception {
        var document = CadDocument.empty();
        var current = message(DocumentCodec.encode(document));
        for (String version : List.of("65534.65534.65534", "invalid")) {
            byte[] future =
                    envelope(current.toBuilder().setApplicationVersion(version).build());
            Path file = directory.resolve(version + ".vxb");
            Files.write(file, future);
            assertThatThrownBy(() -> DocumentReader.load(file)).isInstanceOf(DocumentFormatException.class);
            assertThatThrownBy(() -> DocumentWriter.save(document, file, true))
                    .isInstanceOf(DocumentFormatException.class);
            assertThat(Files.readAllBytes(file)).containsExactly(future);
        }
        try (var paths = Files.list(directory)) {
            assertThat(paths.count()).as("No temporary files should remain").isEqualTo(2);
        }
    }

    @Test
    void failedOverwritePreservesExistingBytesAndLeavesNoTemporaryFiles() throws Exception {
        Path file = directory.resolve("existing.vxb");
        var document = CadDocument.empty();
        DocumentWriter.save(document, file, false);
        byte[] original = Files.readAllBytes(file);
        assertThatThrownBy(() -> DocumentWriter.save(CadDocument.empty(), file, false))
                .isInstanceOf(IOException.class);
        assertThat(Files.readAllBytes(file)).containsExactly(original);
        Files.writeString(file, "Unsupported or damaged original");
        assertThatThrownBy(() -> DocumentWriter.save(document, file, true)).isInstanceOf(IOException.class);
        assertThat(Files.readString(file)).isEqualTo("Unsupported or damaged original");
        try (var paths = Files.list(directory)) {
            assertThat(paths.toList()).isEqualTo(List.of(file));
        }
    }

    @Test
    void concurrentCreateNeverReplacesTheWinningDocument() throws Exception {
        Path destination = directory.resolve("winner.vxb");
        var documents = List.of(CadDocument.empty(), CadDocument.empty());
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var results = new ArrayList<Future<Boolean>>();
        try (var workers = Executors.newFixedThreadPool(2)) {
            for (var document : documents)
                results.add(workers.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("Writer start timed out");
                    try {
                        DocumentWriter.save(document, destination, false);
                        return true;
                    } catch (IOException expectedRace) {
                        return false;
                    }
                }));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            int winner = -1;
            for (int index = 0; index < results.size(); index++) {
                if (results.get(index).get(10, TimeUnit.SECONDS)) {
                    assertThat(winner).as("Both writers published").isEqualTo(-1);
                    winner = index;
                }
            }
            assertThat(winner >= 0).isTrue();
            assertThat(DocumentReader.load(destination)).isEqualTo(documents.get(winner));
        } finally {
            start.countDown();
        }
    }

    private static DocumentProto.Document message(byte[] bytes) throws Exception {
        return DocumentProto.Document.parseFrom(Arrays.copyOfRange(bytes, 44, bytes.length));
    }

    private static byte[] envelope(DocumentProto.Document message) throws Exception {
        byte[] payload = message.toByteArray();
        return ByteBuffer.allocate(44 + payload.length)
                .put(new byte[] {'J', 'C', 'A', 'D', 13, 37, 13, 37})
                .putInt(payload.length)
                .put(MessageDigest.getInstance("SHA-256").digest(payload))
                .put(payload)
                .array();
    }
}
