package dev.voxelbrew.engine;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.model.*;
import dev.voxelbrew.api.model.printing.PrintArtifact;
import dev.voxelbrew.engine.model.*;
import dev.voxelbrew.engine.persistence.DocumentCodec;
import java.util.*;
import org.junit.jupiter.api.Test;

class PrintArtifactTest {
    @Test
    void artifactIsImmutableAndInvalidatesWithDerivedJobs() throws Exception {
        byte[] bytes = {1, 2, 3};
        var artifact = PrintArtifact.of("gcode.3mf", bytes, Map.of("model", "Bambu Lab A1"));
        bytes[0] = 9;
        assertThat(artifact.bytes()).containsExactly(new byte[] {1, 2, 3});
        var document = new CadDocument("test", List.of(), 0)
                .withGcode(Map.of("plate", List.of("G1 X1")))
                .withPrintArtifacts(Map.of("plate", artifact));
        var session = new ModelSession(document);
        assertThat(DocumentCodec.decode(DocumentCodec.encode(document))).isEqualTo(document);
        assertThat(DocumentCodec.decodeJson(DocumentCodec.encodeJson(document))).isEqualTo(document);
        assertThat(session.document().printArtifacts()).isEqualTo(document.printArtifacts());
        assertThat(DocumentCodec.fingerprint(document.withGcode(Map.of())))
                .isEqualTo(DocumentCodec.fingerprint(document));
        session.invalidateGcode();
        assertThat(session.document().printArtifacts().isEmpty()).isTrue();
        assertThat(session.document().gcode().isEmpty()).isTrue();
    }

    @Test
    void checksumMismatchIsRejected() {
        assertThatThrownBy(() -> new PrintArtifact("gcode.3mf", "AQID", "wrong", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
