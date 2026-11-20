package dev.voxelbrew.engine.sketch;

import static dev.voxelbrew.api.model.sketch.SketchGeometry.*;
import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.model.sketch.SketchGeometry;
import java.util.List;
import org.junit.jupiter.api.Test;

class SketchSolverTest {
    private static Entity line(String id, double x, double y, double xx, double yy) {
        return Sketches.entity(id, Kind.LINE, false, x, y, xx, yy);
    }

    private static Entity circle(String id, double x, double y, double radius) {
        return Sketches.entity(id, Kind.CIRCLE, false, x, y, radius);
    }

    private static SketchGeometry geometry(Entity... entities) {
        return new SketchGeometry(List.of(entities), List.of(), List.of());
    }

    private static SketchGeometry add(SketchGeometry g, String id, Type type, double value, Ref... refs) {
        return g.add(SketchConstraints.create(g, id, type, List.of(refs), value));
    }

    private static SketchGeometry solve(SketchGeometry g) {
        var result = SketchSolver.solve(g);
        assertThat(result.solved()).as(result.problems().toString()).isTrue();
        SketchConstraints.requireSatisfied(result.geometry());
        return result.geometry();
    }

    private static void point(Vec actual, double x, double y) {
        assertThat(actual.x()).isCloseTo(x, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(actual.y()).isCloseTo(y, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void mobilityClassifiesIndividualPointsAndLinesInsteadOfOnlyWholeSketchDof() {
        var a = line("a", 10, 10, 30, 10);
        var free = line("free", 10, 30, 30, 30);
        var g = add(geometry(a, free), "anchor", Type.FIX, 0, a.ref(Part.START));
        g = add(g, "length", Type.LENGTH, 20, a.ref());
        var rotating = SketchSolver.solve(g);
        assertThat(rotating.solved()).isTrue();
        assertThat(rotating.fullyConstrainedReferences().contains(a.ref(Part.START)))
                .isTrue();
        assertThat(rotating.fullyConstrainedReferences().contains(a.ref(Part.END)))
                .isFalse();
        assertThat(rotating.fullyConstrainedReferences().contains(a.ref())).isFalse();
        g = add(g, "horizontal", Type.HORIZONTAL, 0, a.ref());
        var fixed = SketchSolver.solve(g);
        assertThat(fixed.solved()).isTrue();
        assertThat(fixed.degreesOfFreedom() > 0)
                .as("Unrelated geometry remains free")
                .isTrue();
        assertThat(fixed.fullyConstrainedReferences().containsAll(List.of(a.ref(), a.ref(Part.START), a.ref(Part.END))))
                .isTrue();
        assertThat(fixed.fullyConstrainedReferences().contains(free.ref())).isFalse();
    }

    @Test
    void pointNeedsBothCoordinatesAndCoincidentPointsInheritItsConstraintState() {
        var a = Sketches.entity("a", Kind.POINT, false, 10, 20);
        var b = Sketches.entity("b", Kind.POINT, false, 10, 20);
        var g = add(geometry(a, b), "x", Type.DISTANCE_X, 10, ORIGIN, a.ref(Part.POINT));
        g = add(g, "coincident", Type.COINCIDENT, 0, a.ref(Part.POINT), b.ref(Part.POINT));
        assertThat(SketchSolver.solve(g).fullyConstrainedReferences().contains(a.ref(Part.POINT)))
                .isFalse();
        g = add(g, "y", Type.DISTANCE_Y, 20, ORIGIN, a.ref(Part.POINT));
        var result = SketchSolver.solve(g);
        assertThat(result.solved()).isTrue();
        assertThat(result.fullyConstrainedReferences()
                        .containsAll(List.of(a.ref(), b.ref(), a.ref(Part.POINT), b.ref(Part.POINT))))
                .isTrue();
    }

    @Test
    void distanceBetweenParallelLinesDrivesSeparationAndSurvivesReversedDirections() {
        var a = line("a", 0, 0, 0, 40);
        var b = line("b", 60, 30, 60, -10);
        var g = add(geometry(a, b), "fixed", Type.FIX, 0, a.ref());
        g = add(g, "distance", Type.DISTANCE, 80, a.ref(), b.ref());
        var solved = solve(g);
        assertThat(Math.abs(solved.entity("b").at(0).x())).isCloseTo(80, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(solved.entity("b").at(1).x())
                .isCloseTo(solved.entity("b").at(0).x(), org.assertj.core.data.Offset.offset(1e-6));
        var edited = Sketches.editDimension(solved, "distance", 25);
        assertThat(Math.abs(edited.entity("b").at(0).x())).isCloseTo(25, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void horizontalVerticalLengthAndEqualProduceDimensionedLines() {
        var a = line("a", 0, 0, 10, 2);
        var b = line("b", 20, 0, 21, 12);
        var g = geometry(a, b);
        g = add(g, "a-fixed", Type.FIX, 0, a.ref(Part.START));
        g = add(g, "b-fixed", Type.FIX, 0, b.ref(Part.START));
        g = add(g, "horizontal", Type.HORIZONTAL, 0, a.ref());
        g = add(g, "vertical", Type.VERTICAL, 0, b.ref());
        g = add(g, "length", Type.LENGTH, 10, a.ref());
        g = add(g, "equal", Type.EQUAL, 0, a.ref(), b.ref());
        var result = SketchSolver.solve(g);
        assertThat(result.solved()).as(result.problems().toString()).isTrue();
        assertThat(result.degreesOfFreedom()).isEqualTo(0);
        point(result.geometry().entity("a").at(1), 10, 0);
        point(result.geometry().entity("b").at(1), 20, 10);
        var edited = Sketches.editDimension(result.geometry(), "length", 20);
        point(edited.entity("b").at(1), 20, 20);
    }

    @Test
    void parallelPerpendicularAndCollinearKeepTheReferenceLineFixed() {
        var a = line("a", 0, 0, 10, 0);
        var b = line("b", 2, 7, 14, 8);
        var c = line("c", 20, 0, 21, 10);
        var g = add(geometry(a, b, c), "anchor", Type.FIX, 0, a.ref());
        g = add(g, "parallel", Type.PARALLEL, 0, a.ref(), b.ref());
        g = add(g, "perpendicular", Type.PERPENDICULAR, 0, a.ref(), c.ref());
        g = solve(g);
        assertThat(g.entity("b").at(1).y())
                .isCloseTo(g.entity("b").at(0).y(), org.assertj.core.data.Offset.offset(1e-6));
        assertThat(g.entity("c").at(1).x())
                .isCloseTo(g.entity("c").at(0).x(), org.assertj.core.data.Offset.offset(1e-6));
        g = solve(add(g, "collinear", Type.COLLINEAR, 0, a.ref(), b.ref()));
        assertThat(g.entity("b").at(0).y()).isCloseTo(0, org.assertj.core.data.Offset.offset(1e-6));
        point(g.entity("a").at(0), 0, 0);
        point(g.entity("a").at(1), 10, 0);
    }

    @Test
    void coincidentMidpointAndSignedPointDimensionsUseTheOrigin() {
        var line = line("line", 0, 0, 20, 0);
        var p = Sketches.entity("point", Kind.POINT, true, 4, 2);
        var g = add(geometry(line, p), "anchor", Type.FIX, 0, line.ref());
        g = solve(add(g, "mid", Type.MIDPOINT, 0, p.ref(Part.POINT), line.ref()));
        point(g.entity("point").at(0), 10, 0);
        var moved = geometry(p);
        moved = add(moved, "x", Type.DISTANCE_X, -4, ORIGIN, p.ref(Part.POINT));
        moved = add(moved, "y", Type.DISTANCE_Y, 7, ORIGIN, p.ref(Part.POINT));
        point(solve(moved).entity("point").at(0), -4, 7);
        var coincident = add(geometry(line, p), "fixed", Type.FIX, 0, line.ref());
        coincident = solve(add(coincident, "on-line", Type.COINCIDENT, 0, p.ref(Part.POINT), line.ref()));
        point(coincident.entity("point").at(0), 4, 0);
        var origin = solve(add(geometry(p), "origin", Type.COINCIDENT, 0, p.ref(Part.POINT), ORIGIN));
        point(origin.entity("point").at(0), 0, 0);
    }

    @Test
    void concentricEqualAndRadiusPropagateToBothCircles() {
        var a = circle("a", 0, 0, 2);
        var b = circle("b", 4, 3, 5);
        var g = add(geometry(a, b), "anchor", Type.FIX, 0, a.ref(Part.CENTER));
        g = add(g, "centers", Type.CONCENTRIC, 0, a.ref(), b.ref());
        g = add(g, "equal", Type.EQUAL, 0, a.ref(), b.ref());
        g = add(g, "radius", Type.RADIUS, 3, a.ref());
        g = solve(g);
        point(g.entity("b").point(Part.CENTER), 0, 0);
        assertThat(g.entity("b").value(2)).isCloseTo(3, org.assertj.core.data.Offset.offset(1e-6));
        var edited = Sketches.editDimension(g, "radius", 8);
        assertThat(edited.entity("b").value(2)).isCloseTo(8, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void lineCircleAndExternalInternalCircleTangenciesUseTheChosenBranch() {
        var a = line("a", -10, 0, 10, 0);
        var c = circle("circle", 0, 5, 2);
        var g = add(geometry(a, c), "anchor", Type.FIX, 0, a.ref());
        g = add(g, "radius", Type.RADIUS, 2, c.ref());
        g = solve(add(g, "tangent", Type.TANGENT, 0, a.ref(), c.ref()));
        assertThat(g.entity("circle").value(1)).isCloseTo(2, org.assertj.core.data.Offset.offset(1e-6));
        for (double distance : new double[] {13, 7}) {
            var outer = circle("outer", 0, 0, 10);
            var inner = circle("inner", distance, 0, 2);
            var circles = add(geometry(outer, inner), "fixed", Type.FIX, 0, outer.ref());
            circles = add(circles, "radius", Type.RADIUS, 2, inner.ref());
            circles = solve(add(circles, "tangent", Type.TANGENT, 0, outer.ref(), inner.ref()));
            assertThat(circles.entity("inner").point(Part.CENTER).length())
                    .isCloseTo(distance > 10 ? 12 : 8, org.assertj.core.data.Offset.offset(1e-6));
        }
    }

    @Test
    void symmetryReflectsPointsAndCirclesAcrossAConstructionLine() {
        var axis = line("axis", 0, -10, 0, 10).withConstruction(true);
        var a = circle("a", -7, 3, 2);
        var b = circle("b", 5, 4, 3);
        var g = add(geometry(axis, a, b), "axis-fixed", Type.FIX, 0, axis.ref());
        g = add(g, "a-fixed", Type.FIX, 0, a.ref());
        g = solve(add(g, "symmetry", Type.SYMMETRY, 0, a.ref(), b.ref(), axis.ref()));
        point(g.entity("b").point(Part.CENTER), 7, 3);
        assertThat(g.entity("b").value(2)).isCloseTo(2, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void polygonConstraintRestoresRegularityAndPreservesFourRigidSizeDegreesOfFreedom() {
        var g = Sketches.polygon("hex", 0, 0, 10, 6);
        var distorted = g.entity("hex-2");
        var values = new java.util.ArrayList<>(distorted.values());
        values.set(0, values.get(0) + 1.2);
        g = g.replace(distorted.withValues(values));
        var result = SketchSolver.solve(g);
        assertThat(result.solved()).as(result.problems().toString()).isTrue();
        assertThat(result.degreesOfFreedom()).isEqualTo(4);
        var lines = result.geometry().entities();
        double length = lines.getFirst().at(0).distance(lines.getFirst().at(1));
        for (int i = 0; i < 6; i++) {
            assertThat(lines.get(i).at(0).distance(lines.get(i).at(1)))
                    .isCloseTo(length, org.assertj.core.data.Offset.offset(1e-6));
            assertThat(lines.get(i).at(1).distance(lines.get((i + 1) % 6).at(0)))
                    .isCloseTo(0, org.assertj.core.data.Offset.offset(1e-6));
        }
    }

    @Test
    void curvatureMakesACubicSplineJoinAnArcWithG2Continuity() {
        var arc = Sketches.entity("arc", Kind.ARC, false, 0, 0, 10, 0, Math.PI / 2);
        var spline = Sketches.entity("spline", Kind.SPLINE, false, 0.2, 10.1, -3, 10.3, -8, 6, -10, 0);
        var g = add(geometry(arc, spline), "arc-fixed", Type.FIX, 0, arc.ref());
        g = solve(add(g, "smooth", Type.CURVATURE, 0, arc.ref(Part.END), spline.ref(Part.START)));
        var a = g.entity("arc");
        var b = g.entity("spline");
        assertThat(a.at(1).distance(b.at(0))).isCloseTo(0, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(a.derivative(1).unit().dot(b.derivative(0).unit()))
                .isCloseTo(1, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(b.curvature(0)).isCloseTo(0.1, org.assertj.core.data.Offset.offset(1e-8));
    }

    @Test
    void tangentCurveEndpointsCoincideAndStaySmooth() {
        var a = line("line", -10, 0, 0, 0);
        var b = Sketches.entity("spline", Kind.SPLINE, false, 0.2, 1, 4, 2, 6, 6, 10, 10);
        var g = add(geometry(a, b), "line-fixed", Type.FIX, 0, a.ref());
        g = solve(add(g, "tangent", Type.TANGENT, 0, a.ref(Part.END), b.ref(Part.START)));
        point(g.entity("spline").at(0), 0, 0);
        assertThat(g.entity("spline").derivative(0).y()).isCloseTo(0, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(g.entity("spline").derivative(0).x() > 0).isTrue();
    }

    @Test
    void angleAndDistanceDriveALine() {
        var line = line("line", 0, 0, 9, 9);
        var g = add(geometry(line), "anchor", Type.COINCIDENT, 0, line.ref(Part.START), ORIGIN);
        g = add(g, "angle", Type.ANGLE, 30, line.ref());
        g = solve(add(g, "distance", Type.DISTANCE, 20, line.ref(Part.START), line.ref(Part.END)));
        point(g.entity("line").at(1), 10 * Math.sqrt(3), 10);
    }

    @Test
    void conflictsAreDiagnosedWithoutMutatingTheDefinitionAndRemovalRepairsIt() {
        var g = Sketches.rectangle("plate", 0, 0, 60, 40, true);
        assertThat(SketchSolver.solve(g).degreesOfFreedom()).isEqualTo(0);
        var inconsistent =
                add(g, "wrong-width", Type.LENGTH, 80, g.entities().getFirst().ref());
        var result = SketchSolver.solve(inconsistent);
        assertThat(result.solved()).isFalse();
        assertThat(result.problems().stream().anyMatch(p -> p.constraintId().equals("wrong-width")))
                .isTrue();
        assertThat(g.entities().getFirst().at(1).x()).isEqualTo(60);
        assertThatThrownBy(result::requireSolved).isInstanceOf(IllegalArgumentException.class);
        assertThat(SketchSolver.solve(g).solved()).isTrue();
        var invalid = new Constraint("missing", Type.FIX, List.of(new Ref("missing", Part.START)), List.of(0.0, 0.0));
        assertThat(SketchSolver.solve(g.add(invalid)).solved()).isFalse();
        assertThatThrownBy(() -> SketchConstraints.create(
                        g, "bad", Type.RADIUS, List.of(g.entities().getFirst().ref()), -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void draggingRespectsConstraintsAndFixedGeometry() {
        var g = Sketches.rectangle("plate", 0, 0, 60, 40, false);
        var result = Sketches.drag(g, g.entities().getFirst().ref(Part.START), new Vec(4, 5));
        assertThat(result.solved()).as(result.problems().toString()).isTrue();
        point(result.geometry().entities().getFirst().at(0), 4, 5);
        assertThat(result.geometry().constraints()).isEqualTo(g.constraints());
        var fixed = Sketches.rectangle("plate", 0, 0, 60, 40, true);
        assertThat(Sketches.drag(fixed, fixed.entities().getFirst().ref(Part.START), new Vec(4, 5))
                        .solved())
                .isFalse();
    }
}
