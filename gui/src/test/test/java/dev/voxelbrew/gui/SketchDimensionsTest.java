package dev.voxelbrew.gui;

import static dev.voxelbrew.api.model.sketch.SketchGeometry.*;
import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.model.sketch.SketchGeometry;
import java.util.List;
import org.junit.jupiter.api.Test;

class SketchDimensionsTest {
    @Test
    void standalonePointsAndEndpointsSupportTruePointToPointDistance() {
        var a = new Entity("a", Kind.POINT, List.of(10., 10.), false);
        var b = new Entity("b", Kind.POINT, List.of(40., 50.), false);
        var g = new SketchGeometry(List.of(a, b), List.of(), List.of());
        var refs = SketchDimensions.references(g, List.of(a.ref(), b.ref()));
        assertThat(refs).isEqualTo(List.of(a.ref(Part.POINT), b.ref(Part.POINT)));
        var measured = SketchDimensions.measure(g, List.of(a.ref(), b.ref()), new Vec(25, 30));
        assertThat(measured).isEqualTo(new SketchDimensions.Measurement(Type.DISTANCE, 50));
        var c = dev.voxelbrew.engine.sketch.SketchConstraints.create(g, "distance", measured.type(), refs, 60);
        var solved = dev.voxelbrew.engine.sketch.SketchSolver.solve(g.add(c));
        assertThat(solved.solved()).isTrue();
        assertThat(solved.geometry()
                        .point(refs.get(0))
                        .distance(solved.geometry().point(refs.get(1))))
                .isCloseTo(60, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void parallelLinesMeasureSeparationInsteadOfAnAngle() {
        var a = new Entity("a", Kind.LINE, List.of(0., 0., 0., 40.), false);
        var b = new Entity("b", Kind.LINE, List.of(60., 40., 60., 0.), false);
        var g = new SketchGeometry(List.of(a, b), List.of(), List.of());
        assertThat(SketchDimensions.measure(g, List.of(a.ref(), b.ref()), new Vec(30, -10)))
                .isEqualTo(new SketchDimensions.Measurement(Type.DISTANCE, 60));
    }

    @Test
    void centerDistanceProjectsOntoAnInclinedLineInEitherSelectionOrder() {
        var line = new Entity("line", Kind.LINE, List.of(0., 0., 4., 3.), false);
        var circle = new Entity("circle", Kind.CIRCLE, List.of(5., 10., 2.), false);
        var g = new SketchGeometry(List.of(line, circle), List.of(), List.of());
        for (var refs :
                List.of(List.of(line.ref(), circle.ref(Part.CENTER)), List.of(circle.ref(Part.CENTER), line.ref()))) {
            assertThat(SketchDimensions.measure(g, refs, new Vec(0, 0)).value()).isCloseTo(5, within(1e-9));
            var constraint =
                    dev.voxelbrew.engine.sketch.SketchConstraints.create(g, "center-distance", Type.DISTANCE, refs, 8);
            var solved = dev.voxelbrew.engine.sketch.SketchSolver.solve(g.add(constraint));
            assertThat(solved.solved()).isTrue();
            assertThat(SketchDimensions.measure(solved.geometry(), refs, new Vec(0, 0))
                            .value())
                    .isCloseTo(8, within(1e-6));
            assertThat(solved.geometry().entity("circle").value(2)).isCloseTo(2, within(1e-9));
        }
    }

    @Test
    void circleClearanceUsesTheRimInEitherSelectionOrder() {
        var line = new Entity("line", Kind.LINE, List.of(0., 0., 0., 40.), false);
        var circle = new Entity("circle", Kind.CIRCLE, List.of(10., 10., 3.), false);
        var g = new SketchGeometry(List.of(line, circle), List.of(), List.of());
        for (var refs : List.of(List.of(line.ref(), circle.ref()), List.of(circle.ref(), line.ref()))) {
            assertThat(SketchDimensions.measure(g, refs, new Vec(0, 10)))
                    .isEqualTo(new SketchDimensions.Measurement(Type.DISTANCE, 7));
            var constraint = dev.voxelbrew.engine.sketch.SketchConstraints.create(g, "gap", Type.DISTANCE, refs, 5);
            var solved = dev.voxelbrew.engine.sketch.SketchSolver.solve(g.add(constraint));
            assertThat(solved.solved()).isTrue();
            assertThat(SketchDimensions.measure(solved.geometry(), refs, new Vec(0, 10))
                            .value())
                    .isCloseTo(5, within(1e-6));
        }
    }

    @Test
    void inferredRadiusDrivesTheRealSketchSolver() {
        var circle = new Entity("circle", Kind.CIRCLE, List.of(10., 10., 3.), false);
        var g = new SketchGeometry(List.of(circle), List.of(), List.of());
        var measurement = SketchDimensions.measure(g, List.of(circle.ref()), new Vec(20, 20));
        var constraint = dev.voxelbrew.engine.sketch.SketchConstraints.create(
                g, "radius", measurement.type(), List.of(circle.ref()), 12);
        var solved = dev.voxelbrew.engine.sketch.SketchSolver.solve(g.add(constraint));
        assertThat(solved.solved()).as(solved.problems().toString()).isTrue();
        assertThat(solved.geometry().entity("circle").value(2))
                .isCloseTo(12, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void infersArcRadiusAndDirectedAngle() {
        var arc = new Entity("arc", Kind.ARC, List.of(0., 0., 5., 0., Math.PI), false);
        var a = new Entity("a", Kind.LINE, List.of(0., 0., 10., 0.), false);
        var b = new Entity("b", Kind.LINE, List.of(0., 0., 0., 10.), false);
        var g = new SketchGeometry(List.of(arc, a, b), List.of(), List.of());
        assertThat(SketchDimensions.measure(g, List.of(arc.ref()), new Vec(20, 20)))
                .isEqualTo(new SketchDimensions.Measurement(Type.RADIUS, 5));
        assertThat(SketchDimensions.measure(g, List.of(a.ref(), b.ref()), new Vec(20, 20)))
                .isEqualTo(new SketchDimensions.Measurement(Type.ANGLE, 90));
    }

    @Test
    void infersLineCircleAndPositivePointMeasurements() {
        var line = new Entity("line", Kind.LINE, List.of(0., 0., 30., 40.), false);
        var circle = new Entity("circle", Kind.CIRCLE, List.of(10., 10., 3.), false);
        var g = new SketchGeometry(List.of(line, circle), List.of(), List.of());
        assertThat(SketchDimensions.measure(g, List.of(line.ref()), new Vec(20, 60)))
                .isEqualTo(new SketchDimensions.Measurement(Type.LENGTH, 50));
        assertThat(SketchDimensions.measure(g, List.of(circle.ref()), new Vec(20, 60)))
                .isEqualTo(new SketchDimensions.Measurement(Type.RADIUS, 3));
        var refs = List.of(line.ref(Part.END), line.ref(Part.START));
        assertThat(SketchDimensions.measure(g, refs, new Vec(15, 60)))
                .isEqualTo(new SketchDimensions.Measurement(Type.DISTANCE_X, 30));
        assertThat(SketchDimensions.measure(g, refs, new Vec(50, 20)))
                .isEqualTo(new SketchDimensions.Measurement(Type.DISTANCE_Y, 40));
        for (var order : List.of(refs, refs.reversed())) {
            for (var position : List.of(new Vec(15, 60), new Vec(50, 20))) {
                var measurement = SketchDimensions.measure(g, order, position);
                var constraint = dev.voxelbrew.engine.sketch.SketchConstraints.create(
                        g,
                        "dimension",
                        measurement.type(),
                        SketchDimensions.drivingReferences(g, order, measurement.type()),
                        measurement.value());
                assertThat(dev.voxelbrew.engine.sketch.SketchConstraints.residuals(g, constraint))
                        .containsExactly(0.0);
            }
        }
        assertThatThrownBy(() -> SketchDimensions.measure(g, List.of(), new Vec(0, 0)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
