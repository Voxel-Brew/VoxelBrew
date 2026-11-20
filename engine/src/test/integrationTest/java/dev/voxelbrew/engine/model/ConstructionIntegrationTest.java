package dev.voxelbrew.engine.model;

import static dev.voxelbrew.api.model.construction.ConstructionGeometry.*;
import static dev.voxelbrew.api.model.construction.ConstructionTools.Tool.*;
import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.model.CadDocument;
import dev.voxelbrew.api.model.ConstructionFeature;
import dev.voxelbrew.api.model.Feature;
import dev.voxelbrew.api.model.Transform;
import dev.voxelbrew.engine.geometry.Solid;
import dev.voxelbrew.engine.persistence.DocumentCodec;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConstructionIntegrationTest {
    private static final Vector ZERO = new Vector(0, 0, 0);

    @Test
    void constructionPlaneDrivesSolidAfterSaveEditUndoAndRollback() throws Exception {
        var session = new ModelSession(CadDocument.empty());
        var offset = new ConstructionFeature(
                "offset", OFFSET_PLANE, List.of(ConstructionFeature.Input.originPlane(2)), 5, false);
        session.add(offset);
        session.add(new Feature.Sketch(
                "sketch",
                "profile",
                new Feature.Plane("support", 0, Transform.IDENTITY, null, "offset"),
                new Feature.Rectangle(0, 0, 60, 40)));
        session.add(new Feature.Extrude("solid", new Feature.ProfileRef("sketch", "profile"), 8));
        session = new ModelSession(DocumentCodec.decode(DocumentCodec.encode(session.document())));
        try (var result = Evaluator.evaluate(session.document())) {
            assertThat(result.valid()).as(result.diagnostics().toString()).isTrue();
            assertThat(result.bodies().getFirst().solid().volume())
                    .isCloseTo(19200, org.assertj.core.data.Offset.offset(.01));
            assertThat(((Plane) result.construction().get("offset")).origin().z())
                    .isCloseTo(5, org.assertj.core.data.Offset.offset(1e-9));
            var sketch = (Feature.Sketch) result.resolvedFeatures().get(1);
            assertThat(sketch.plane().placement().apply(0, 0, 0)[2])
                    .isCloseTo(5, org.assertj.core.data.Offset.offset(1e-9));
        }
        session.replace(new ConstructionFeature("offset", OFFSET_PLANE, offset.inputs(), 12, false));
        try (var result = Evaluator.evaluate(session.document())) {
            assertThat(result.valid()).isTrue();
            assertThat(((Feature.Sketch) result.resolvedFeatures().get(1))
                            .plane()
                            .placement()
                            .apply(0, 0, 0)[2])
                    .isCloseTo(12, org.assertj.core.data.Offset.offset(1e-9));
        }
        session.undo();
        try (var result = Evaluator.evaluate(session.document())) {
            assertThat(((Plane) result.construction().get("offset")).origin().z())
                    .isCloseTo(5, org.assertj.core.data.Offset.offset(1e-9));
        }
        session.setCursor(0);
        try (var result = Evaluator.evaluate(session.document())) {
            assertThat(result.construction().isEmpty()).isTrue();
            assertThat(result.bodies().isEmpty()).isTrue();
        }
    }

    @Test
    void analyticCylinderProvidesAxisTangentCenterAndArcLengthPoint() throws Exception {
        var session = new ModelSession(CadDocument.empty());
        session.add(new Feature.Sketch("circle", "profile", Feature.Plane.origin("XY"), new Feature.Circle(10, 20, 8)));
        session.add(new Feature.Extrude("cylinder", new Feature.ProfileRef("circle", "profile"), 12));
        int side = -1, circle = -1;
        try (var result = Evaluator.evaluate(session.document())) {
            var solid = result.bodies().getFirst().solid();
            int faces = (int) solid.constructionInput(0, -1, .5)[0];
            for (int i = 0; i < faces; i++) {
                if (solid.constructionInput(0, i, .5)[0] == 2) {
                    side = i;
                }
            }
            int edges = (int) solid.constructionInput(1, -1, .5)[0];
            for (int i = 0; i < edges; i++) {
                if (solid.constructionInput(1, i, .5)[0] == 7) {
                    circle = i;
                    break;
                }
            }
        }
        assertThat(side >= 0).isTrue();
        assertThat(circle >= 0).isTrue();
        var face = new ConstructionFeature.Input(ConstructionFeature.Selection.FACE, "cylinder", "body", side, ZERO);
        var edge = new ConstructionFeature.Input(ConstructionFeature.Selection.EDGE, "cylinder", "body", circle, ZERO);
        session.add(new ConstructionFeature("axis", AXIS_SURFACE, List.of(face), 0, false));
        session.add(new ConstructionFeature(
                "normal",
                AXIS_NORMAL,
                List.of(
                        face,
                        new ConstructionFeature.Input(
                                ConstructionFeature.Selection.POSITION, "", "", 0, new Vector(14, 20, 6))),
                0,
                false));
        session.add(new ConstructionFeature("tangent", TANGENT_PLANE, List.of(face), .25, false));
        session.add(new ConstructionFeature("center", POINT_CENTER, List.of(edge), 0, false));
        session.add(new ConstructionFeature("path", POINT_ALONG_PATH, List.of(edge), .25, false));
        session.add(new ConstructionFeature("path-plane", PLANE_ALONG_PATH, List.of(edge), .25, false));
        var document = DocumentCodec.decode(DocumentCodec.encode(session.document()));
        assertThat(document).isEqualTo(session.document());
        try (var result = Evaluator.evaluate(document)) {
            assertThat(result.valid()).as(result.diagnostics().toString()).isTrue();
            var axis = (Axis) result.construction().get("axis");
            assertThat(axis.origin().x()).isCloseTo(10, org.assertj.core.data.Offset.offset(1e-8));
            assertThat(axis.origin().y()).isCloseTo(20, org.assertj.core.data.Offset.offset(1e-8));
            assertThat(Math.abs(axis.direction().z())).isCloseTo(1, org.assertj.core.data.Offset.offset(1e-8));
            assertThat(((Axis) result.construction().get("normal")).direction().x())
                    .isCloseTo(1, org.assertj.core.data.Offset.offset(1e-8));
            var point = ((Point) result.construction().get("path")).position();
            var center = ((Point) result.construction().get("center")).position();
            assertThat(point.distance(center)).isCloseTo(4, org.assertj.core.data.Offset.offset(1e-7));
            var plane = (Plane) result.construction().get("path-plane");
            assertThat(plane.normal().dot(point.minus(center))).isCloseTo(0, org.assertj.core.data.Offset.offset(1e-7));
            assertThat(((Plane) result.construction().get("tangent")).normal().z())
                    .isCloseTo(0, org.assertj.core.data.Offset.offset(1e-7));
        }
    }

    @Test
    void filletAndChamferExposeRealTorusAndConeConstructionAxes() {
        try (var cylinder = Solid.circlePrism(10, 20, 0, 12, 10)) {
            int edge = -1;
            for (int i = 0; i < cylinder.constructionInput(1, -1, .5)[0]; i++) {
                if (cylinder.constructionInput(1, i, .5)[0] == 7) {
                    edge = i + 1;
                    break;
                }
            }
            assertThat(edge > 0).isTrue();
            for (boolean fillet : List.of(false, true)) {
                try (var finished = cylinder.edgeFinish(fillet, 1, new int[] {edge})) {
                    int face = -1;
                    for (int i = 0; i < finished.constructionInput(0, -1, .5)[0]; i++) {
                        if (finished.constructionInput(0, i, .5)[0] == (fillet ? 5 : 3)) {
                            face = i;
                            break;
                        }
                    }
                    assertThat(face >= 0)
                            .as("Expected analytic " + (fillet ? "torus" : "cone"))
                            .isTrue();
                    var input = new ConstructionFeature.Input(
                            ConstructionFeature.Selection.FACE, "body", "body", face, ZERO);
                    var feature = new ConstructionFeature("axis", AXIS_SURFACE, List.of(input), 0, false);
                    var axis = (Axis) dev.voxelbrew.engine.construction.ConstructionEvaluator.evaluate(
                            feature, java.util.Map.of(), ref -> finished);
                    assertThat(axis.origin().x()).isCloseTo(10, org.assertj.core.data.Offset.offset(1e-8));
                    assertThat(axis.origin().y()).isCloseTo(20, org.assertj.core.data.Offset.offset(1e-8));
                    assertThat(Math.abs(axis.direction().z())).isCloseTo(1, org.assertj.core.data.Offset.offset(1e-8));
                    if (fillet) {
                        var center = new ConstructionFeature("center", POINT_CENTER, List.of(input), 0, false);
                        var point = (Point) dev.voxelbrew.engine.construction.ConstructionEvaluator.evaluate(
                                center, java.util.Map.of(), ref -> finished);
                        assertThat(point.position()).isEqualTo(axis.origin());
                    }
                }
            }
        }
    }

    @Test
    void offsetFromReversedFaceFollowsItsOutwardNormal() {
        var session = new ModelSession(CadDocument.empty());
        session.add(
                new Feature.Sketch("base", "profile", Feature.Plane.origin("XY"), new Feature.Rectangle(0, 0, 10, 20)));
        session.add(new Feature.Extrude("body", new Feature.ProfileRef("base", "profile"), 5));
        try (var result = session.evaluate()) {
            var solid = result.bodies().getFirst().solid();
            int bottom = -1;
            for (int face = 0; face < solid.constructionInput(0, -1, .5)[0]; face++) {
                var packet = solid.constructionInput(0, face, .5);
                if (packet[0] == 1 && packet[15] < -.99) bottom = face;
            }
            assertThat(bottom >= 0).isTrue();
            var feature = new ConstructionFeature(
                    "offset",
                    OFFSET_PLANE,
                    List.of(new ConstructionFeature.Input(
                            ConstructionFeature.Selection.FACE, "body", "body", bottom, ZERO)),
                    3,
                    false);
            var plane = (Plane) dev.voxelbrew.engine.construction.ConstructionEvaluator.evaluate(
                    feature, java.util.Map.of(), ref -> solid);
            assertThat(plane.normal().z()).isCloseTo(-1, org.assertj.core.data.Offset.offset(1e-9));
            assertThat(plane.origin().z()).isCloseTo(-3, org.assertj.core.data.Offset.offset(1e-9));
        }
    }

    @Test
    void missingReferenceFailsAndBlocksDependentsWithoutPublishingGeometry() {
        var session = new ModelSession(CadDocument.empty());
        session.add(new ConstructionFeature(
                "broken",
                OFFSET_PLANE,
                List.of(new ConstructionFeature.Input(ConstructionFeature.Selection.DATUM, "missing", "", 0, ZERO)),
                4,
                false));
        session.add(new ConstructionFeature(
                "dependent",
                OFFSET_PLANE,
                List.of(new ConstructionFeature.Input(ConstructionFeature.Selection.DATUM, "broken", "", 0, ZERO)),
                2,
                false));
        try (var result = Evaluator.evaluate(session.document())) {
            assertThat(result.valid()).isFalse();
            assertThat(result.diagnostics().get(0).state()).isEqualTo(Evaluation.State.FAILED);
            assertThat(result.diagnostics().get(1).state()).isEqualTo(Evaluation.State.BLOCKED);
            assertThatThrownBy(result::construction).isInstanceOf(IllegalStateException.class);
        }
    }
}
