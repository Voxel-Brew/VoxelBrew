package dev.voxelbrew.engine.sketch;

import static dev.voxelbrew.api.model.sketch.SketchGeometry.*;
import static dev.voxelbrew.engine.sketch.SketchTools.Tool.*;
import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.model.sketch.SketchGeometry;
import dev.voxelbrew.api.model.sketch.SketchText;
import java.util.*;
import org.junit.jupiter.api.Test;

class SketchToolsTest {
    private SketchGeometry draw(SketchTools.Tool tool, double... xy) {
        var points = new ArrayList<Vec>();
        for (int i = 0; i < xy.length; i += 2) points.add(new Vec(xy[i], xy[i + 1]));
        var result = SketchTools.add(
                SketchGeometry.empty(), tool, "shape", points, List.of(), SketchTools.Options.defaults(tool));
        assertThat(SketchSolver.solve(result).solved()).isTrue();
        return result;
    }

    @Test
    void freeTextStartsAlongPositiveY() {
        assertThat(draw(TEXT, 3, 7).entity("shape").value(3)).isCloseTo(Math.PI / 2, within(1e-9));
    }

    @Test
    void textCreationRejectsAnglesOutsideHalfATurn() {
        var path = SketchGeometry.entity("path", Kind.LINE, true, 0, 0, 100, 0);
        var source = new SketchGeometry(List.of(path), List.of(), List.of());
        for (var tool : List.of(TEXT, TEXT_ON_PATH)) {
            for (double degrees : new double[] {-181, -180, 180, 181}) {
                var options = new SketchTools.Options(
                        6, 3, false, false, 5, .5, "A", "SansSerif", 0, 10, Math.toRadians(degrees), 0);
                var points = List.of(new Vec(10, 0));
                var refs = tool == TEXT_ON_PATH ? List.of(path.ref()) : List.<Ref>of();
                if (Math.abs(degrees) == 180) {
                    var result = SketchTools.add(source, tool, "label", points, refs, options);
                    assertThat(result.entity("label").value(3)).isEqualTo(options.angle());
                } else {
                    assertThatThrownBy(() -> SketchTools.add(source, tool, "label", points, refs, options))
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessageContaining("rotation");
                }
            }
        }
    }

    @Test
    void rectangleAndCircleVariantsHonorTheirDefiningPoints() {
        for (var g : List.of(
                draw(RECTANGLE_TWO_POINT, 0, 0, 20, 10),
                draw(RECTANGLE_CENTER, 10, 5, 20, 10),
                draw(RECTANGLE_THREE_POINT, 0, 0, 20, 0, 20, 10))) {
            assertThat(g.entities().size()).isEqualTo(4);
            assertThat(SketchProfiles.hasProfile(g)).isTrue();
            assertThat(g.entities()
                            .getFirst()
                            .at(0)
                            .distance(g.entities().getFirst().at(1)))
                    .isCloseTo(20, org.assertj.core.data.Offset.offset(1e-9));
        }
        for (var g : List.of(
                draw(CIRCLE_CENTER, 0, 0, 10, 0),
                draw(CIRCLE_TWO_POINT, -10, 0, 10, 0),
                draw(CIRCLE_THREE_POINT, 10, 0, 0, 10, -10, 0))) {
            assertThat(g.entities().getFirst().value(2)).isCloseTo(10, org.assertj.core.data.Offset.offset(1e-9));
            assertThat(g.entities().getFirst().point(Part.CENTER).length())
                    .isCloseTo(0, org.assertj.core.data.Offset.offset(1e-9));
        }
    }

    @Test
    void tangentCirclesUseNearestValidBranchAndRejectImpossibleRadius() {
        var a = Sketches.entity("a", Kind.LINE, true, 0, 0, 10, 0);
        var b = Sketches.entity("b", Kind.LINE, true, 0, 0, 0, 10);
        var c = Sketches.entity("c", Kind.LINE, true, 10, 0, 0, 10);
        var source = new SketchGeometry(List.of(a, b, c), List.of(), List.of());
        var g = SketchTools.add(
                source,
                CIRCLE_TWO_TANGENT,
                "circle",
                List.of(new Vec(5, 5)),
                List.of(a.ref(), b.ref()),
                SketchTools.Options.defaults());
        assertThat(g.entity("circle").point(Part.CENTER).distance(new Vec(5, 5)))
                .isCloseTo(0, org.assertj.core.data.Offset.offset(1e-9));
        SketchConstraints.requireSatisfied(g);
        g = SketchTools.add(
                source,
                CIRCLE_THREE_TANGENT,
                "circle",
                List.of(new Vec(2, 2)),
                List.of(a.ref(), b.ref(), c.ref()),
                SketchTools.Options.defaults());
        assertThat(g.entity("circle").value(2))
                .isCloseTo(10 / (2 + Math.sqrt(2)), org.assertj.core.data.Offset.offset(1e-9));
        SketchConstraints.requireSatisfied(g);
        var parallel = Sketches.entity("parallel", Kind.LINE, true, 0, 10, 10, 10);
        source = new SketchGeometry(List.of(a, parallel), List.of(), List.of());
        g = SketchTools.add(
                source,
                CIRCLE_TWO_TANGENT,
                "circle",
                List.of(new Vec(20, 5)),
                List.of(a.ref(), parallel.ref()),
                SketchTools.Options.defaults());
        assertThat(g.entity("circle").point(Part.CENTER).distance(new Vec(20, 5)))
                .isCloseTo(0, org.assertj.core.data.Offset.offset(1e-9));
        SketchConstraints.requireSatisfied(g);
        var wrong = Sketches.entity("wrong", Kind.LINE, true, 0, 11, 10, 11);
        var invalid = new SketchGeometry(List.of(a, wrong), List.of(), List.of());
        assertThatThrownBy(() -> SketchTools.add(
                        invalid,
                        CIRCLE_TWO_TANGENT,
                        "circle",
                        List.of(new Vec(5, 5)),
                        List.of(a.ref(), wrong.ref()),
                        SketchTools.Options.defaults()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void arcVariantsPreserveSignedSweepAndTangentAttachments() {
        var a = draw(ARC_THREE_POINT, 10, 0, -10, 0, 0, -10).entities().getFirst();
        assertThat(a.value(4)).isCloseTo(-Math.PI, org.assertj.core.data.Offset.offset(1e-9));
        var b = draw(ARC_CENTER, 0, 0, 10, 0, 0, 10).entities().getFirst();
        assertThat(b.value(4)).isCloseTo(Math.PI / 2, org.assertj.core.data.Offset.offset(1e-9));
        var line = Sketches.entity("line", Kind.LINE, true, -10, 0, 0, 0);
        var source = new SketchGeometry(List.of(line), List.of(), List.of());
        var g = SketchTools.add(
                source,
                ARC_TANGENT,
                "arc",
                List.of(new Vec(10, 10)),
                List.of(line.ref(Part.END)),
                SketchTools.Options.defaults());
        SketchConstraints.requireSatisfied(g);
        assertThat(g.entity("arc").value(2)).isCloseTo(10, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void cursorVertexAndGuideCircleFollowPolygonRotation() {
        var center = new Vec(-3, 8);
        var cursor = new Vec(-12, 20);
        var layout = SketchTools.PolygonLayout.fromVertex(center, cursor);
        for (int sides : List.of(3, 5, 8, 32)) {
            var polygon = layout.geometry("poly", sides);
            var side = polygon.entities().getFirst();
            assertThat(side.at(0).distance(cursor)).isLessThan(1e-9);
            assertThat(layout.guideRadius(POLYGON_INSCRIBED, sides)).isCloseTo(center.distance(cursor), within(1e-9));
            assertThat(layout.guideRadius(POLYGON_CIRCUMSCRIBED, sides))
                    .isCloseTo(center.distance(side.at(.5)), within(1e-9));
            SketchConstraints.requireSatisfied(polygon);
        }
    }

    @Test
    void polygonModesPreserveTheRadiusApothemOrSelectedEdge() {
        assertThat(draw(POLYGON_INSCRIBED, 0, 0, 10, 0)
                        .entities()
                        .getFirst()
                        .at(0)
                        .length())
                .isCloseTo(10, org.assertj.core.data.Offset.offset(1e-9));
        var g = draw(POLYGON_CIRCUMSCRIBED, 0, 0, 10, 0);
        assertThat(g.entities().getFirst().at(.5).length()).isCloseTo(10, org.assertj.core.data.Offset.offset(1e-9));
        for (double side : new double[] {10, -10}) {
            g = draw(POLYGON_EDGE, 0, 0, 10, 0, 5, side);
            assertThat(g.entities().getFirst().at(0).distance(new Vec(0, 0)))
                    .isCloseTo(0, org.assertj.core.data.Offset.offset(1e-9));
            assertThat(g.entities().getFirst().at(1).distance(new Vec(10, 0)))
                    .isCloseTo(0, org.assertj.core.data.Offset.offset(1e-9));
        }
    }

    @Test
    void straightAndCurvedSlotsRemainClosedAndTangent() {
        for (var g : List.of(
                draw(SLOT_CENTER_TO_CENTER, 0, 0, 20, 0, 10, 2),
                draw(SLOT_CENTER_POINT, 10, 0, 20, 0, 10, 2),
                draw(SLOT_OVERALL, -2, 0, 22, 0, 10, 2),
                draw(SLOT_ARC_CENTER, 0, 0, 10, 0, 0, 10, 12, 0),
                draw(SLOT_ARC_THREE_POINT, 10, 0, 0, 10, Math.sqrt(50), Math.sqrt(50), 12, 0))) {
            assertThat(g.entities().size()).isEqualTo(4);
            assertThat(SketchProfiles.hasProfile(g)).isTrue();
            SketchConstraints.requireSatisfied(g);
        }
        assertThatThrownBy(() -> draw(SLOT_OVERALL, 0, 0, 1, 0, 0, 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> draw(ELLIPSE, 0, 0, 1, 0, 1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> draw(CIRCLE_THREE_POINT, 0, 0, 1, 0, 2, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void textStartsAtTheClickedPositionInsideALineSegment() {
        var line = SketchGeometry.entity("path", Kind.LINE, true, 10, 20, 110, 20);
        var source = new SketchGeometry(List.of(line), List.of(), List.of());
        var result = SketchTools.add(
                source,
                TEXT_ON_PATH,
                "label",
                List.of(new Vec(65, 21)),
                List.of(line.ref()),
                SketchTools.Options.defaults(TEXT_ON_PATH));
        assertThat(result.entity("label").value(3)).isZero();
        assertThat(SketchTools.pathCoordinates(line, new Vec(65, 18)).distance(new Vec(55, -2)))
                .isLessThan(1e-9);
        assertThat(result.entity("label").value(0)).isCloseTo(55, within(1e-9));
        assertThat(SketchText.anchor(result, result.entity("label")).distance(new Vec(65, 20)))
                .isLessThan(1e-9);
    }

    @Test
    void newDrivingDimensionsEditCurvesAndTextWithoutChangingIds() {
        var g = draw(ELLIPSE, 0, 0, 10, 0, 0, 5);
        var ellipse = g.entities().getFirst();
        g = g.add(SketchConstraints.create(g, "major", Type.MAJOR_RADIUS, List.of(ellipse.ref()), 10));
        g = Sketches.editDimension(g, "major", 20);
        assertThat(g.entity(ellipse.id()).value(2)).isCloseTo(20, org.assertj.core.data.Offset.offset(1e-6));
        g = draw(CONIC, 0, 0, 20, 0, 10, 10);
        var conic = g.entities().getFirst();
        g = g.add(SketchConstraints.create(g, "rho", Type.RHO, List.of(conic.ref()), .5));
        g = Sketches.editDimension(g, "rho", .7);
        assertThat(g.entity(conic.id()).value(6)).isCloseTo(.7, org.assertj.core.data.Offset.offset(1e-6));
        var text = SketchText.create("text", "AB", "SansSerif", 0, 0, 0, 10, 0, 0, "", false);
        g = new SketchGeometry(List.of(text), List.of(), List.of(text.id()));
        g = g.add(SketchConstraints.create(g, "height", Type.TEXT_HEIGHT, List.of(text.ref()), 10));
        assertThat(Sketches.editDimension(g, "height", 12).entity("text").value(2))
                .isCloseTo(12, org.assertj.core.data.Offset.offset(1e-6));
    }
}
