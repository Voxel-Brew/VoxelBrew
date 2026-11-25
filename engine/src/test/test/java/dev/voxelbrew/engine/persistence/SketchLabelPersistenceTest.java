package dev.voxelbrew.engine.persistence;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.model.Feature;
import dev.voxelbrew.api.model.sketch.SketchGeometry.Type;
import dev.voxelbrew.api.model.sketch.SketchGeometry.Vec;
import dev.voxelbrew.engine.parameters.FeatureDimensions;
import dev.voxelbrew.engine.sketch.SketchConstraints;
import dev.voxelbrew.engine.sketch.Sketches;
import java.util.List;
import org.junit.jupiter.api.Test;

class SketchLabelPersistenceTest {
    @Test
    void referenceFollowsGeometryWithoutDrivingOrBindingIt() {
        var g = Sketches.rectangle("r", 0, 0, 10, 5, true);
        var ref = SketchConstraints.referenceDimension(
                g, "measured", Type.LENGTH, List.of(g.entity("r-bottom").ref()));
        ref = SketchConstraints.positionLabel(ref, new Vec(8, 9));
        var saved = SketchCodec.toProto(g.add(ref));
        assertThat(saved.getConstraintsList().getLast().getReferenceDimension()).isTrue();
        var reopened = SketchCodec.fromProto(saved);
        assertThat(reopened).isEqualTo(g.add(ref));
        assertThat(SketchConstraints.residuals(reopened, ref)).isEmpty();
        var sketch = new Feature.Sketch(
                "sketch", "profile", new Feature.Plane("origin-xy", 0), new Feature.Constrained(reopened));
        assertThat(FeatureDimensions.list(sketch))
                .extracting(d -> d.target().field())
                .doesNotContain("constraint:measured");
        var edited = Sketches.editDimension(reopened, "width", 15);
        assertThat(SketchConstraints.measuredValue(edited, ref)).isCloseTo(15, within(1e-6));
        assertThatThrownBy(() -> Sketches.editDimension(edited, "measured", 22))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void placedLabelSurvivesSavingAndEditingTheDrivingValue() {
        var g = Sketches.rectangle("r", 0, 0, 10, 5, true);
        var position = new Vec(-12, 23);
        g = g.withConstraints(g.constraints().stream()
                .map(c -> c.id().equals("width") ? SketchConstraints.positionLabel(c, position) : c)
                .toList());
        var saved = SketchCodec.toProto(g);
        assertThat(saved.getConstraintsList().stream()
                        .filter(c -> c.getId().equals("width"))
                        .findFirst()
                        .orElseThrow()
                        .getValuesList())
                .containsExactly(10.0);
        var reopened = SketchCodec.fromProto(saved);
        assertThat(reopened).isEqualTo(g);
        var edited = Sketches.editDimension(reopened, "width", 15);
        var width = edited.constraints().stream()
                .filter(c -> c.id().equals("width"))
                .findFirst()
                .orElseThrow();
        assertThat(SketchConstraints.labelPosition(width)).contains(position);
    }
}
