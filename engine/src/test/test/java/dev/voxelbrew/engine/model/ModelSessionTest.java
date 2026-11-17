package dev.voxelbrew.engine.model;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ModelSessionTest {
    @Test
    void deletionPreservesCursorIdentityAndIsOneUndoTransaction() {
        var session = new ModelSession(CadDocument.empty());
        for (int i = 0; i < 4; i++)
            session.add(new Feature.Sketch(
                    "s" + i, "p" + i, new Feature.Plane("origin-xy", 0), new Feature.Rectangle(0, 0, 60, 40)));
        var original = session.document().features();
        session.setCursor(2);
        session.deleteFeatures(0, 2);
        assertThat(session.document().cursor()).isEqualTo(0);
        assertThat(session.document().features()).isEqualTo(original.subList(2, 4));
        session.undo();
        assertThat(session.document().features()).isEqualTo(original);
        session.redo();
        assertThat(session.document().features()).isEqualTo(original.subList(2, 4));
        session.setCursor(2);
        session.deleteFeatures(1, 2);
        assertThat(session.document().cursor()).isEqualTo(1);
        session.undo();
        session.deleteFeatures(1, 2);
        assertThat(session.document().features().size()).isEqualTo(1);
        var before = session.snapshot();
        assertThatThrownBy(() -> session.deleteFeatures(0, 2)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThat(session.snapshot()).isEqualTo(before);
    }

    @Test
    void sharedOffsetPlaneEditsAreOneTransactionAndOriginIsFixed() {
        var session = new ModelSession(CadDocument.empty());
        var plane = new Feature.Plane("offset-12", 12);
        session.add(new Feature.Sketch("one", "one.profile", plane, new Feature.Rectangle(0, 0, 60, 40)));
        session.add(new Feature.Sketch("two", "two.profile", plane, new Feature.Circle(10, 10, 4)));
        session.editDimension("one", "offsetZ", 15);
        for (var feature : session.document().features()) {
            assertThat(((Feature.Sketch) feature).plane().offsetZ()).isEqualTo(15);
        }
        session.undo();
        for (var feature : session.document().features()) {
            assertThat(((Feature.Sketch) feature).plane().offsetZ()).isEqualTo(12);
        }
        var reference = new ModelSession(CadDocument.empty());
        reference.add(new Feature.Sketch(
                "plate", "profile", new Feature.Plane("origin-xy", 0), new Feature.Rectangle(0, 0, 60, 40)));
        var before = reference.document();
        assertThatThrownBy(() -> reference.editDimension("plate", "offsetZ", 12))
                .isInstanceOf(ModelException.class);
        assertThat(reference.document()).isEqualTo(before);
    }
}
