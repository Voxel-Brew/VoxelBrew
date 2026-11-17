package dev.voxelbrew.engine.sketch;

import static dev.voxelbrew.engine.sketch.SketchGeometry.*;
import static org.assertj.core.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class SketchCurveTypesTest {
    private Entity spline(Kind kind, int degree, boolean closed, double... points) {
        return new Entity("spline", kind, Arrays.stream(points).boxed().toList(), false, degree, closed, null);
    }

    @Test
    void ellipseAndRationalConicHaveExactPositionsAndCurvature() {
        var e = Sketches.entity("ellipse", Kind.ELLIPSE, false, 3, 4, 10, 5, Math.PI / 2);
        assertThat(e.at(0).distance(new Vec(3, 14))).isCloseTo(0, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(e.curvature(0)).isCloseTo(0.4, org.assertj.core.data.Offset.offset(1e-12));
        double weight = Math.sqrt(0.5);
        var c = Sketches.entity("conic", Kind.CONIC, false, 10, 0, 10, 10, 0, 10, weight / (1 + weight));
        for (double t : new double[] {0, .2, .5, .8, 1}) {
            assertThat(c.at(t).length()).isCloseTo(10, org.assertj.core.data.Offset.offset(1e-10));
            assertThat(c.curvature(t)).isCloseTo(.1, org.assertj.core.data.Offset.offset(1e-10));
        }
        var reflected = Sketches.entity("reflected", Kind.ELLIPSE, false, -3, 4, 10, 5, Math.PI / 2);
        var axis = Sketches.entity("axis", Kind.LINE, true, 0, 0, 0, 20);
        var geometry = new SketchGeometry(List.of(e, reflected, axis), List.of(), List.of());
        var symmetry = SketchConstraints.create(
                geometry, "symmetry", Type.SYMMETRY, List.of(e.ref(), reflected.ref(), axis.ref()), 0);
        SketchConstraints.requireSatisfied(geometry.add(symmetry));
    }

    @Test
    void fitSplinesInterpolateAndClosedSplinesJoinWithC2Continuity() {
        var e = spline(Kind.FIT_SPLINE, 0, false, 0, 0, 10, 10, 20, 0, 30, 10);
        for (int i = 0; i < 4; i++)
            assertThat(e.at(i / 3.0).distance(e.point(new Ref(e.id(), Part.FIT, i))))
                    .isCloseTo(0, org.assertj.core.data.Offset.offset(1e-10));
        var closed = spline(Kind.FIT_SPLINE, 0, true, 0, 0, 10, 0, 10, 10, 0, 10);
        assertThat(closed.at(0).distance(closed.at(1))).isCloseTo(0, org.assertj.core.data.Offset.offset(1e-10));
        assertThat(closed.derivative(0).distance(closed.derivative(1)))
                .isCloseTo(0, org.assertj.core.data.Offset.offset(1e-10));
        assertThat(closed.curvature(1)).isCloseTo(closed.curvature(0), org.assertj.core.data.Offset.offset(1e-10));
        assertThat(Ref.parse("spline.fit[2]")).isEqualTo(new Ref("spline", Part.FIT, 2));
        var longId = new Entity("s".repeat(128), e.kind(), e.values(), false);
        assertThat(longId.sample()).isEqualTo(e.sample());
    }

    @Test
    void controlSplinesSupportBothDegreesAndPeriodicClosure() {
        for (int degree : new int[] {3, 5}) {
            var e = spline(Kind.CONTROL_SPLINE, degree, false, 0, 0, 0, 10, 10, 20, 20, 20, 30, 10, 30, 0);
            assertThat(e.at(0)).isEqualTo(new Vec(0, 0));
            assertThat(e.at(1)).isEqualTo(new Vec(30, 0));
            var closed = new Entity(e.id(), e.kind(), e.values(), false, degree, true, null);
            assertThat(closed.at(0).distance(closed.at(1))).isCloseTo(0, org.assertj.core.data.Offset.offset(1e-9));
            assertThat(closed.derivative(0).distance(closed.derivative(1)))
                    .isCloseTo(0, org.assertj.core.data.Offset.offset(1e-9));
            assertThat(closed.curvature(1)).isCloseTo(closed.curvature(0), org.assertj.core.data.Offset.offset(1e-9));
        }
    }

    @Test
    void indexedFitAndControlPointsRemainConstrainable() {
        var e = spline(Kind.CONTROL_SPLINE, 3, false, 0, 0, 0, 10, 10, 10, 10, 0);
        var g = new SketchGeometry(List.of(e), List.of(), List.of());
        var ref = new Ref(e.id(), Part.CONTROL, 1);
        var moved = Sketches.drag(g, ref, new Vec(2, 12)).requireSolved();
        assertThat(moved.point(ref).distance(new Vec(2, 12))).isCloseTo(0, org.assertj.core.data.Offset.offset(1e-6));
        var fixed = moved.add(SketchConstraints.create(moved, "lock", Type.FIX, List.of(ref), 0));
        assertThat(Sketches.drag(fixed, ref, new Vec(3, 12)).solved()).isFalse();
    }

    @Test
    void invalidNewCurvesAreRejectedBeforeKernelEvaluation() {
        for (var e : List.of(
                Sketches.entity("ellipse", Kind.ELLIPSE, false, 0, 0, -1, 2, 0),
                Sketches.entity("conic", Kind.CONIC, false, 0, 0, 1, 1, 2, 0, 1),
                spline(Kind.FIT_SPLINE, 0, false, 0, 0, 0, 0))) {
            assertThat(SketchSolver.solve(new SketchGeometry(List.of(e), List.of(), List.of()))
                            .solved())
                    .isFalse();
        }
        assertThatThrownBy(() -> spline(Kind.CONTROL_SPLINE, 5, false, 0, 0, 1, 1, 2, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
