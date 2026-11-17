package dev.voxelbrew.engine.model;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.model.CadDocument;
import dev.voxelbrew.api.model.Feature;
import dev.voxelbrew.engine.persistence.DocumentCodec;
import java.util.List;
import org.junit.jupiter.api.Test;

class FeatureNameTest {
    @Test
    void renamePersistsAndParticipatesInUndoWithoutChangingFeatureIdentity() throws Exception {
        var sketch = new Feature.Sketch(
                "sketch", "profile", new Feature.Plane("origin-xy", 0), new Feature.Rectangle(0, 0, 60, 40));
        var initial = new CadDocument("named", List.of(sketch), 1);
        var session = new ModelSession(initial);
        session.renameFeature("sketch", "  Base profile  ");
        assertThat(session.document().featureName("sketch", "Sketch")).isEqualTo("Base profile");
        assertThat(session.document().features()).isEqualTo(initial.features());
        assertThat(DocumentCodec.decode(DocumentCodec.encode(session.document())))
                .isEqualTo(session.document());
        assertThat(session.undo()).isTrue();
        assertThat(session.document()).isEqualTo(initial);
        assertThat(session.redo()).isTrue();
        assertThat(session.document().featureNames().get("sketch")).isEqualTo("Base profile");
        assertThatThrownBy(() -> session.renameFeature("missing", "Invalid"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
