package dev.voxelbrew.engine.persistence;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.model.CadDocument;
import dev.voxelbrew.engine.persistence.proto.DocumentProto;
import dev.voxelbrew.util.Version;
import dev.voxelbrew.util.update.ReleaseVersion;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

@SuppressWarnings("deprecation")
class DocumentVersionTest {
    @Test
    void usesTheCurrentMagicAndRejectsThePreviousSignature() throws Exception {
        var document = CadDocument.empty();
        byte[] bytes = DocumentCodec.encode(document);
        assertThat(Arrays.copyOf(bytes, 8)).containsExactly(new byte[] {'J', 'C', 'A', 'D', 13, 37, 13, 37});
        assertThat(DocumentCodec.decode(bytes)).isEqualTo(document);
        byte[] previous = {'J', 'C', 'A', 'D', 13, 10, 26, 10};
        System.arraycopy(previous, 0, bytes, 0, previous.length);
        assertThat(org.assertj.core.api.Assertions.catchThrowableOfType(
                                () -> DocumentCodec.decode(bytes), DocumentFormatException.class)
                        .getMessage()
                        .contains("magic mismatch"))
                .isTrue();
    }

    @Test
    void savesTheRuntimeVersionWithoutAddingItToCadIdentity() throws Exception {
        var document = CadDocument.empty();
        byte[] bytes = DocumentCodec.encode(document);
        var message = message(bytes);
        assertThat(message.getApplicationVersion()).isEqualTo(Version.version());
        assertThat(DocumentCodec.decode(bytes)).isEqualTo(document);
        assertThat(DocumentCodec.fingerprint(document))
                .isEqualTo(java.util.HexFormat.of()
                        .formatHex(MessageDigest.getInstance("SHA-256")
                                .digest(message.toBuilder()
                                        .clearApplicationVersion()
                                        .build()
                                        .toByteArray())));
        bytes[bytes.length - 1] ^= 1;
        assertThat(org.assertj.core.api.Assertions.catchThrowableOfType(
                                () -> DocumentCodec.decode(bytes), DocumentFormatException.class)
                        .getMessage()
                        .contains("checksum"))
                .isTrue();
    }

    @Test
    void olderSchemasAreRejectedAndCurrentSchemaPreservesOlderWriterMetadata() throws Exception {
        var original = CadDocument.empty();
        var current = message(DocumentCodec.encode(original));
        for (int schema = 1; schema <= DocumentCodec.SCHEMA_VERSION; schema++) {
            for (String version : List.of("", "0.0.0", Version.version())) {
                byte[] old = envelope(current.toBuilder()
                        .setSchemaVersion(schema)
                        .setApplicationVersion(version)
                        .build());
                byte[] before = old.clone();
                if (schema != DocumentCodec.SCHEMA_VERSION) {
                    assertThatThrownBy(() -> DocumentCodec.decode(old)).isInstanceOf(DocumentFormatException.class);
                    continue;
                }
                var document = DocumentCodec.decode(old);
                assertThat(document).isEqualTo(original);
                assertThat(old).containsExactly(before);
                var upgraded = message(DocumentCodec.encode(document));
                assertThat(upgraded.getSchemaVersion()).isEqualTo(DocumentCodec.SCHEMA_VERSION);
                assertThat(upgraded.getApplicationVersion()).isEqualTo(Version.version());
                assertThat(DocumentCodec.fingerprint(document)).isEqualTo(DocumentCodec.fingerprint(original));
            }
        }
    }

    @Test
    void newerMajorMinorPatchAndFinalReleaseAreRejected() throws Exception {
        var current = ReleaseVersion.parse(Version.version());
        var future = new java.util.ArrayList<String>();
        future.add((current.major() + 1) + ".0.0");
        future.add(current.major() + "." + (current.minor() + 1) + ".0");
        future.add(current.major() + "." + current.minor() + "." + (current.patch() + 1));
        if (current.snapshot()) future.add(Version.version().replace("-SNAPSHOT", ""));
        var message = message(DocumentCodec.encode(CadDocument.empty()));
        for (String version : future) {
            byte[] bytes =
                    envelope(message.toBuilder().setApplicationVersion(version).build());
            var error = org.assertj.core.api.Assertions.catchThrowableOfType(
                    () -> DocumentCodec.decode(bytes), DocumentFormatException.class);
            assertThat(error.getMessage().contains("VoxelBrew " + version)).isTrue();
            assertThat(error.getMessage().contains("supported version " + Version.version()))
                    .isTrue();
            assertThat(error.getMessage().contains("Update VoxelBrew")).isTrue();
        }
    }

    @Test
    void malformedWriterVersionsAreNotTreatedAsLegacy() throws Exception {
        var message = message(DocumentCodec.encode(CadDocument.empty()));
        for (String version : List.of(" ", "1.2", "v1.2.3", "01.2.3", "-1.0.0", "1.2.3-rc1", "65535.0.0")) {
            byte[] bytes =
                    envelope(message.toBuilder().setApplicationVersion(version).build());
            var error = org.assertj.core.api.Assertions.catchThrowableOfType(
                    () -> DocumentCodec.decode(bytes), DocumentFormatException.class);
            assertThat(error.getMessage().contains("Invalid .vxb application version"))
                    .as(() -> "Unexpected diagnostic for writer version '" + version + "': " + error.getMessage())
                    .isTrue();
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
