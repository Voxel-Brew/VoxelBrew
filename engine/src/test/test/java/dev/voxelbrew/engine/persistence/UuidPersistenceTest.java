package dev.voxelbrew.engine.persistence;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.engine.model.*;
import dev.voxelbrew.engine.parameters.ParameterUnit;
import org.junit.jupiter.api.Test;

class UuidPersistenceTest {
    @Test
    void reloadResumesSharedDocumentSequence() throws Exception {
        var session = new ModelSession(CadDocument.empty());
        session.document().uuidGenerator().next();
        session.setParameters(session.document().parameters().put("width", ParameterUnit.MM, "10 mm", ""));
        long expected = session.document().uuidGenerator().nextValue();
        var reopened = DocumentCodec.decode(DocumentCodec.encode(session.document()));
        assertThat(reopened.uuidGenerator().next()).isEqualTo(expected);
        assertThat(reopened.parameters().uuidGenerator().next()).isEqualTo(expected + 1);
    }
}
