package dev.voxelbrew.gui;

import static dev.voxelbrew.api.model.sketch.SketchGeometry.*;
import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.model.sketch.SketchGeometry;
import java.util.List;
import org.junit.jupiter.api.Test;

class SketchSelectionTest {
    @Test
    void windowRequiresContainmentWhileCrossingIncludesSegmentsWithBothEndsOutside() {
        var inside = new Entity("inside", Kind.LINE, List.of(2., 2., 8., 8.), false);
        var crossing = new Entity("crossing", Kind.LINE, List.of(-5., 5., 15., 5.), false);
        var outside = new Entity("outside", Kind.LINE, List.of(-5., 12., 15., 12.), false);
        var geometry = new SketchGeometry(List.of(inside, crossing, outside), List.of(), List.of());
        var window = SketchSelection.inBox(geometry, new Vec(0, 10), new Vec(10, 0), false);
        assertThat(window).isEqualTo(java.util.Set.of(inside.ref()));
        var crossed = SketchSelection.inBox(geometry, new Vec(10, 0), new Vec(0, 10), true);
        assertThat(crossed).isEqualTo(java.util.Set.of(inside.ref(), crossing.ref()));
    }

    @Test
    void partialWindowDoesNotSelectALineOrItsEndpoints() {
        var line = new Entity("line", Kind.LINE, List.of(2., 2., 20., 20.), false);
        var geometry = new SketchGeometry(List.of(line), List.of(), List.of());
        assertThat(SketchSelection.inBox(geometry, new Vec(0, 0), new Vec(10, 10), false))
                .isEqualTo(java.util.Set.of());
        assertThat(SketchSelection.inBox(geometry, new Vec(10, 10), new Vec(0, 0), true))
                .isEqualTo(java.util.Set.of(line.ref()));
    }

    @Test
    void boxSelectsStandalonePointsButNotTheCenterOfAPartiallyEnclosedCircle() {
        var point = new Entity("point", Kind.POINT, List.of(5., 5.), true);
        var circle = new Entity("circle", Kind.CIRCLE, List.of(5., 5., 20.), false);
        var geometry = new SketchGeometry(List.of(point, circle), List.of(), List.of());
        assertThat(SketchSelection.inBox(geometry, new Vec(0, 0), new Vec(10, 10), false))
                .isEqualTo(java.util.Set.of(point.ref(Part.POINT)));
    }
}
