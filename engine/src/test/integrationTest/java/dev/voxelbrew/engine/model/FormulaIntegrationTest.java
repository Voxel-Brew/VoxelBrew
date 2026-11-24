package dev.voxelbrew.engine.model;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.model.CadDocument;
import dev.voxelbrew.api.model.Feature;
import dev.voxelbrew.api.model.parameters.*;
import dev.voxelbrew.api.model.sketch.*;
import dev.voxelbrew.engine.parameters.*;
import dev.voxelbrew.engine.sketch.*;
import dev.voxelbrew.exporter.DocumentWriter;
import dev.voxelbrew.importer.DocumentReader;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FormulaIntegrationTest {
    @TempDir
    Path directory;

    private static void verify(ModelSession session, double width, String label) {
        try (var evaluation = session.evaluate()) {
            var bodies = evaluation.requireCompleteDocument();
            assertThat(bodies.size()).isEqualTo(1);
            assertThat(bodies.getFirst().solid().volume())
                    .isCloseTo(width * 40 * (1 + 2.0 / 3), org.assertj.core.data.Offset.offset(.001));
            assertThat(bodies.getFirst().solid().bounds().maxX())
                    .isCloseTo(width, org.assertj.core.data.Offset.offset(.001));
            var sketch = (Feature.Sketch) evaluation.resolvedFeatures().get(2);
            var entity = ((Feature.Constrained) sketch.profile()).geometry().entity("label");
            assertThat(entity.text().content()).isEqualTo(label);
            assertThat(entity.text().font()).isEqualTo("SansSerif");
            assertThat(entity.id()).isEqualTo("label");
        }
    }

    @Test
    void numericAndTextFormulasPersistAndFollowConfigurationsHistoryAndRename() throws Exception {
        var session = new ModelSession(CadDocument.empty());
        var text = SketchText.create("label", "Width: 6 cm", "SansSerif", 0, 0, 50, 5, 0, 0, "", false);
        session.add(new Feature.Sketch(
                "plate", "profile", new Feature.Plane("origin-xy", 0), new Feature.Rectangle(0, 0, 30, 40)));
        session.add(new Feature.Extrude("solid", new Feature.ProfileRef("plate", "profile"), 5));
        session.add(new Feature.Sketch(
                "annotation",
                "profile",
                new Feature.Plane("origin-xy", 0),
                new Feature.Constrained(new SketchGeometry(List.of(text), List.of(), List.of("label")))));
        session.setParameters(ParameterSet.empty()
                .put("width", ParameterUnit.MM, "=60", "")
                .addConfiguration("Wide", false)
                .override("width", "Wide", "=80"));
        session.bindExpression("plate", "width", "=clamp($width/2, 1 mm, 100 mm)");
        session.bindExpression("solid", "distance", "=1+2/3");
        session.bindExpression(
                "annotation", FeatureText.key("label", "content"), "=\"Width: \" + formatUnit($width, cm)");
        session.bindExpression("annotation", FeatureText.key("label", "font"), "=\"Sans\" + \"Serif\"");
        verify(session, 30, "Width: 6 cm");
        session.selectConfiguration("Wide");
        verify(session, 40, "Width: 8 cm");
        assertThat(session.undo()).isTrue();
        verify(session, 30, "Width: 6 cm");
        assertThat(session.redo()).isTrue();
        var path = directory.resolve("formula part.vxb");
        DocumentWriter.save(session.document(), path, false);
        assertThat(DocumentReader.load(path)).isEqualTo(session.document());
        var reopened = new ModelSession(DocumentReader.load(path));
        verify(reopened, 40, "Width: 8 cm");
        reopened.setParameters(reopened.document().parameters().rename("width", "plateWidth"));
        assertThat(reopened.document()
                        .parameters()
                        .binding(new ParameterSet.Target("annotation", "text:label:content")))
                .isEqualTo("=\"Width: \" + formatUnit($plateWidth, cm)");
        verify(reopened, 40, "Width: 8 cm");
        assertThatThrownBy(() -> reopened.document().parameters().remove("plateWidth"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reopened.bindExpression("plate", "width", "=\"invalid\""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reopened.bindExpression("annotation", "text:missing:content", "=\"invalid\""))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
