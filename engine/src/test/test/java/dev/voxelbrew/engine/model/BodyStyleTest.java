package dev.voxelbrew.engine.model;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.engine.persistence.DocumentCodec;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BodyStyleTest {
    private static final BodyStyle RED_PETG = new BodyStyle("PETG", "#FF0000");
    private static final BodyStyle BLUE_PLA = new BodyStyle("PLA", "#0000FF");

    @Test
    void validatesOpaqueColorsAndCustomMaterialLabels() {
        assertThat(new BodyStyle("  Nylon CF  ", "#abcdef")).isEqualTo(new BodyStyle("Nylon CF", "#ABCDEF"));
        assertThatThrownBy(() -> new BodyStyle("PLA", "red")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BodyStyle("PLA", "#FF000080")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BodyStyle("PET\nG", "#FF0000")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BodyStyle("x".repeat(65), "#FF0000")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void assignmentsAreAtomicUndoableAndIndependentOfTimelineAndParameters() {
        var session = new ModelSession(CadDocument.empty());
        session.setBodyStyle("one.body", RED_PETG);
        long revision = session.revision();
        session.setBodyStyle("one.body", RED_PETG);
        assertThat(session.revision())
                .as("Identical assignments create no history entry")
                .isEqualTo(revision);
        session.setBodyStyle("two.body", BLUE_PLA);
        var saved = session.document();
        session.setCursor(0);
        session.undo();
        assertThat(session.document().bodyStyle("one.body")).isEqualTo(RED_PETG);
        assertThat(session.document().bodyStyle("two.body")).isEqualTo(BodyStyle.DEFAULT);
        session.redo();
        assertThat(session.document()).isEqualTo(saved);
        session.setParameters(
                saved.parameters().put("width", dev.voxelbrew.engine.parameters.ParameterUnit.MM, "10", ""));
        assertThat(session.document().bodyStyles()).isEqualTo(saved.bodyStyles());
        session.setBodyStyle("one.body", null);
        assertThat(session.document().bodyStyle("one.body")).isEqualTo(BodyStyle.DEFAULT);
        session.undo();
        assertThat(session.document().bodyStyle("one.body")).isEqualTo(RED_PETG);
        assertThatThrownBy(() -> saved.bodyStyles().clear()).isInstanceOf(UnsupportedOperationException.class);
        String longestFeatureBody = "a".repeat(128) + ".body";
        session.setBodyStyle(longestFeatureBody, BLUE_PLA);
        assertThat(session.document().bodyStyle(longestFeatureBody)).isEqualTo(BLUE_PLA);
    }

    @Test
    void splitInheritanceOverridesAndDeterministicPersistenceSurviveBrokenReferences() throws Exception {
        var split =
                new Feature.SplitBody("split", new Feature.BodyRef("cut", "body"), new Feature.SplitPlane(1, 0, 0, 30));
        var cut = new Feature.ThroughCut(
                "cut", new Feature.ProfileRef("hole", "profile"), new Feature.BodyRef("base", "body"));
        var document = new CadDocument(
                "styles",
                List.of(cut, split),
                2,
                dev.voxelbrew.engine.printing.PrintSetup.empty(),
                dev.voxelbrew.engine.parameters.ParameterSet.empty(),
                Map.of("base.body", RED_PETG, "split.positive", BLUE_PLA));
        var reopened = DocumentCodec.decode(DocumentCodec.encode(document));
        assertThat(reopened).isEqualTo(document);
        assertThat(reopened.bodyStyle("split.negative")).isEqualTo(RED_PETG);
        assertThat(reopened.bodyStyle("split.positive")).isEqualTo(BLUE_PLA);
        var session = new ModelSession(reopened);
        session.setBodyStyle("split.positive", null);
        assertThat(session.document().bodyStyle("split.positive")).isEqualTo(RED_PETG);
        assertThat(DocumentCodec.fingerprint(session.document())).isNotEqualTo(DocumentCodec.fingerprint(document));
        session.setBodyStyle("base.body", new BodyStyle("TPU", "#00FF00"));
        assertThat(session.document().bodyStyle("split.negative").material()).isEqualTo("TPU");
        var reverse = new java.util.LinkedHashMap<String, BodyStyle>();
        reverse.put("split.positive", BLUE_PLA);
        reverse.put("base.body", RED_PETG);
        assertThat(DocumentCodec.encode(new CadDocument(
                        document.id(),
                        document.features(),
                        document.cursor(),
                        document.printSetup(),
                        document.parameters(),
                        reverse)))
                .containsExactly(DocumentCodec.encode(document));
        var cyclic = new CadDocument(
                "cyclic",
                List.of(new Feature.SplitBody("split", new Feature.BodyRef("split", "negative"), split.plane())),
                1);
        assertThat(cyclic.bodyStyle("split.negative")).isEqualTo(BodyStyle.DEFAULT);
    }
}
