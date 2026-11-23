package dev.voxelbrew.gui;

import static org.assertj.core.api.Assertions.*;

import javafx.geometry.Point3D;
import javafx.scene.transform.Rotate;
import org.junit.jupiter.api.Test;

class ViewOrientationTest {
    @Test
    void viewportBasisPreservesMillimetersAndOrientation() {
        var point = new Point3D(10, 20, 30);
        assertThat(Viewport.toModel(Viewport.toView(point.getX(), point.getY(), point.getZ())))
                .isEqualTo(point);
        assertThat(Viewport.toView(0, 0, 1)).isEqualTo(new Point3D(0, -1, 0));
        assertThat(Viewport.toView(1, 0, 0).crossProduct(Viewport.toView(0, 1, 0)))
                .isEqualTo(Viewport.toView(0, 0, 1));
    }

    @Test
    void sketchFrameConversionPreservesModelCoordinatesForAllPlanesAndArbitraryFaces() {
        var frames = new java.util.ArrayList<dev.voxelbrew.api.model.Transform>();
        for (String name : new String[] {"XY", "XZ", "YZ"}) {
            frames.add(dev.voxelbrew.api.model.Feature.Plane.origin(name).frame());
        }
        frames.add(dev.voxelbrew.api.model.Transform.translation(3, 7, -9)
                .compose(dev.voxelbrew.api.model.Transform.rotation(21, 33, 47)));
        for (var frame : frames) {
            var model = frame.apply(4, -8, 12);
            assertThat(Viewport.viewTransform(frame)
                            .transform(Viewport.toView(4, -8, 12))
                            .distance(Viewport.toView(model[0], model[1], model[2])))
                    .isCloseTo(0, org.assertj.core.data.Offset.offset(1e-12));
            assertThat(frame.inverse().apply(model[0], model[1], model[2]))
                    .usingComparatorWithPrecision(1e-12)
                    .containsExactly(new double[] {4, -8, 12});
        }
    }

    @Test
    void allFacesEdgesAndCornersPointTowardsCameraInTheCadBasis() {
        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    if (x == 0 && y == 0 && z == 0) continue;
                    var view = ViewOrientation.lookingFrom(x, y, z);
                    var yaw = new Rotate(view.orbit(), Rotate.Y_AXIS);
                    var pitch = new Rotate(view.tilt(), Rotate.X_AXIS);
                    var direction = new Point3D(x, -z, y).normalize();
                    assertThat(pitch.transform(yaw.transform(direction)).distance(new Point3D(0, 0, -1)))
                            .isCloseTo(0, org.assertj.core.data.Offset.offset(1e-12));
                    // Independently compare the cube projection with the main scene's FX
                    // transforms.
                    var point = new Point3D(17, -23, 41);
                    assertThat(view.project(point).distance(pitch.transform(yaw.transform(new Point3D(17, -41, -23)))))
                            .isCloseTo(0, org.assertj.core.data.Offset.offset(1e-12));
                }
            }
        }
    }

    @Test
    void sketchLocalAxesAreScreenAlignedAndInvertible() {
        var basis = SketchViewBasis.DEFAULT;
        var point = new dev.voxelbrew.api.model.sketch.SketchGeometry.Vec(13, -23);
        var projected = basis.project(point);
        assertThat(projected.x()).isEqualTo(-23);
        assertThat(projected.y()).isEqualTo(-13);
        assertThat(basis.unproject(projected.x(), projected.y())).isEqualTo(point);
    }

    @Test
    void standardViewsKeepZUpAndCorrectLeftRightHandedness() {
        assertThat(ViewOrientation.named("Top")).isEqualTo(new ViewOrientation(90, 0));
        assertThat(ViewOrientation.named("Bottom")).isEqualTo(new ViewOrientation(-90, 0));
        assertThat(ViewOrientation.named("Front")).isEqualTo(new ViewOrientation(0, 0));
        assertThat(ViewOrientation.named("Back")).isEqualTo(new ViewOrientation(0, -180));
        assertThat(ViewOrientation.named("Right")).isEqualTo(new ViewOrientation(0, 90));
        assertThat(ViewOrientation.named("Left")).isEqualTo(new ViewOrientation(0, -90));
        assertThat(ViewOrientation.named("Front").project(new Point3D(1, 0, 0))).isEqualTo(new Point3D(1, 0, 0));
        for (String name : new String[] {"Isometric", "Top", "Bottom", "Front", "Back", "Right", "Left"}) {
            assertThat(ViewOrientation.named(name).name()).isEqualTo(name);
        }
        assertThat(new ViewOrientation(12, 34).name()).isEqualTo("Custom");
    }

    @Test
    void homeLooksFromThePositiveDiagonalWithZUp() {
        var home = ViewOrientation.HOME;
        var diagonal = new Point3D(1, 1, 1).normalize();
        assertThat(home.project(diagonal).distance(new Point3D(0, 0, -1))).isCloseTo(0, within(1e-12));
        assertThat(home.project(new Point3D(0, 0, 1)).getY()).isNegative();
        assertThat(ViewOrientation.named("Isometric")).isEqualTo(home);
    }

    @Test
    void draggingMovesTheFacingSurfaceWithThePointer() {
        var front = ViewOrientation.named("Front");
        var facingPoint = new Point3D(0, -10, 0);
        assertThat(front.dragged(20, 0).project(facingPoint).getX() > 0)
                .as("Drag right")
                .isTrue();
        assertThat(front.dragged(-20, 0).project(facingPoint).getX() < 0)
                .as("Drag left")
                .isTrue();
        assertThat(front.dragged(0, 20).project(facingPoint).getY() > 0)
                .as("Drag down")
                .isTrue();
        assertThat(front.dragged(0, -20).project(facingPoint).getY() < 0)
                .as("Drag up")
                .isTrue();
        assertThat(front.dragged(20, 30).dragged(-20, -30)).isEqualTo(front);
    }

    @Test
    void orbitWrapsAndRejectsInvalidAngles() {
        assertThat(new ViewOrientation(20, 685)).isEqualTo(new ViewOrientation(20, -35));
        assertThat(new ViewOrientation(0, 0).dragged(20, -1000)).isEqualTo(new ViewOrientation(-89, -10));
        assertThat(new ViewOrientation(0, 0).dragged(-20, 1000)).isEqualTo(new ViewOrientation(89, 10));
        assertThatThrownBy(() -> new ViewOrientation(Double.NaN, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ViewOrientation(0, Double.POSITIVE_INFINITY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ViewOrientation(91, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ViewOrientation.lookingFrom(0, 0, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
