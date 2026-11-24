package dev.voxelbrew.engine.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import dev.voxelbrew.api.model.sketch.SketchGeometry.Vec;
import dev.voxelbrew.engine.sketch.SketchConstraints;
import dev.voxelbrew.engine.sketch.Sketches;
import org.junit.jupiter.api.Test;

class SketchLabelPersistenceTest {
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
