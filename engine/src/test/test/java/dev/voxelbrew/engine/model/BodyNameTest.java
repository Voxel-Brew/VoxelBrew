package dev.voxelbrew.engine.model;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.model.BodyStyle;
import dev.voxelbrew.api.model.CadDocument;
import dev.voxelbrew.api.model.Feature;
import dev.voxelbrew.engine.persistence.DocumentCodec;
import java.util.*;
import org.junit.jupiter.api.Test;

class BodyNameTest {
    @Test
    void namesAreUndoableWithoutChangingIdsGeometryOrOtherMetadata() {
        var sketch = new Feature.Sketch(
                "sketch", "profile", new Feature.Plane("origin-xy", 0), new Feature.Rectangle(0, 0, 60, 40));
        var extrusion = new Feature.Extrude("solid", new Feature.ProfileRef("sketch", "profile"), 5);
        var initial = new CadDocument("named", List.of(sketch, extrusion), 2);
        var session = new ModelSession(initial);
        session.renameBody("solid.body", "  Mounting plate  ");
        assertThat(session.document().bodyName("solid.body", "Body 1")).isEqualTo("Mounting plate");
        assertThat(session.document().features()).isEqualTo(initial.features());
        assertThat(session.document().id()).isEqualTo(initial.id());
        long revision = session.revision();
        session.renameBody("solid.body", "Mounting plate");
        assertThat(session.revision()).isEqualTo(revision);
        assertThat(session.undo()).isTrue();
        assertThat(session.document()).isEqualTo(initial);
        assertThat(session.canUndo()).as("Rename adds exactly one transaction").isFalse();
        session.redo();
        var style = new BodyStyle("PETG", "#FF0000");
        session.setBodyStyle("solid.body", style);
        session.editDimension("solid", "distance", 8);
        assertThat(session.document().bodyNames().get("solid.body")).isEqualTo("Mounting plate");
        session.setCursor(1);
        session.undo();
        assertThat(session.document().cursor()).isEqualTo(1);
        assertThat(((Feature.Extrude) session.document().features().get(1)).distance())
                .isEqualTo(5);
        assertThat(session.document().bodyStyle("solid.body")).isEqualTo(style);
        session.redo();
        assertThat(session.document().cursor()).isEqualTo(1);
        session.renameBody("solid.body", null);
        assertThat(session.document().bodyName("solid.body", "Body 1")).isEqualTo("Body 1");
        session.undo();
        assertThat(session.document().bodyNames().get("solid.body")).isEqualTo("Mounting plate");
    }

    @Test
    void unicodeNamesRoundTripDeterministicallyAndRemainIndependentOfStableReferences() throws Exception {
        var names = new LinkedHashMap<String, String>();
        names.put("z.body", "Pokrywa żółta");
        names.put("a.body", "Support / left");
        var document = new CadDocument(
                "names",
                List.of(),
                0,
                dev.voxelbrew.api.model.printing.PrintSetup.empty(),
                dev.voxelbrew.api.model.parameters.ParameterSet.empty(),
                Map.of(),
                names);
        names.clear();
        assertThat(document.bodyNames().size()).isEqualTo(2);
        assertThat(DocumentCodec.decode(DocumentCodec.encode(document))).isEqualTo(document);
        assertThatThrownBy(() -> document.bodyNames().clear()).isInstanceOf(UnsupportedOperationException.class);
        var reverse = new TreeMap<>(document.bodyNames());
        var reordered = new CadDocument(
                document.id(),
                document.features(),
                document.cursor(),
                document.printSetup(),
                document.parameters(),
                document.bodyStyles(),
                reverse);
        assertThat(DocumentCodec.encode(reordered)).containsExactly(DocumentCodec.encode(document));
        assertThat(DocumentCodec.fingerprint(reordered)).isEqualTo(DocumentCodec.fingerprint(document));
        var session = new ModelSession(document);
        session.renameBody("a.body", "Right support");
        assertThat(DocumentCodec.fingerprint(session.document())).isNotEqualTo(DocumentCodec.fingerprint(document));
        var unnamed = new CadDocument("names", List.of(), 0);
        assertThat(DocumentCodec.decode(DocumentCodec.encode(unnamed))
                        .bodyNames()
                        .isEmpty())
                .isTrue();
    }

    @Test
    void invalidNamesLeaveTheDocumentAndUndoHistoryUntouched() {
        var session = new ModelSession(CadDocument.empty());
        var before = session.snapshot();
        for (String name : List.of("", "   ", "line\nbreak", "x\u0000y", "x".repeat(129)))
            assertThatThrownBy(() -> session.renameBody("body.one", name)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> session.renameBody("invalid body ID", "Plate"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(session.snapshot()).isEqualTo(before);
    }
}
