package dev.voxelbrew.engine.construction;

import static dev.voxelbrew.api.model.construction.ConstructionGeometry.*;
import static dev.voxelbrew.api.model.construction.ConstructionTools.*;
import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ConstructionToolsTest {
    private static final Vector O = new Vector(0, 0, 0),
            X = new Vector(1, 0, 0),
            Y = new Vector(0, 1, 0),
            Z = new Vector(0, 0, 1);

    @Test
    void allTwentyOperationsProduceExpectedGeometry() {
        var xy = new Plane(O, Z);
        var yz = new Plane(O, X);
        var zx = new Plane(O, Y);
        var x = new Axis(O, X);
        var y = new Axis(O, Y);
        var path = new Path() {
            @Override
            public Vector position(double t) {
                return new Vector(10 * t, 0, 0);
            }

            @Override
            public Vector tangent(double t) {
                return X;
            }
        };
        assertThat(Tool.values().length).isEqualTo(20);
        assertThat(coordinateSystem(new Vector(1, 2, 3), X, Y).frame().apply(0, 0, 0))
                .usingComparatorWithPrecision(1e-9)
                .containsExactly(new double[] {1, 2, 3});
        assertThat(offsetPlane(xy, 8).origin()).isEqualTo(new Vector(0, 0, 8));
        assertThat(Math.abs(angledPlane(xy, x, 90).normal().dot(Y)))
                .isCloseTo(1, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(tangentPlane(O, Z).normal()).isEqualTo(Z);
        assertThat(midplane(xy, offsetPlane(xy, 8), false).origin()).isEqualTo(new Vector(0, 0, 4));
        assertThat(perpendicularPlane(x, O).normal()).isEqualTo(X);
        assertThat(planeTwoEdges(x, y).normal()).isEqualTo(Z);
        assertThat(planeThreePoints(O, X, Y).normal()).isEqualTo(Z);
        assertThat(planeAlongPath(path, .5).origin()).isEqualTo(new Vector(5, 0, 0));
        assertThat(axisSurface(O, Z)).isEqualTo(new Axis(O, Z));
        assertThat(axisNormal(O, Z)).isEqualTo(new Axis(O, Z));
        assertThat(Math.abs(axisTwoPlanes(xy, yz).direction().dot(Y)))
                .isCloseTo(1, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(axisTwoPoints(O, X)).isEqualTo(x);
        assertThat(axisEdge(x)).isEqualTo(x);
        assertThat(pointVertex(X)).isEqualTo(new Point(X));
        assertThat(pointTwoEdges(x, y)).isEqualTo(new Point(O));
        assertThat(pointThreePlanes(xy, yz, zx)).isEqualTo(new Point(O));
        assertThat(pointCenter(Y)).isEqualTo(new Point(Y));
        assertThat(pointEdgePlane(x, offsetPlane(yz, 3))).isEqualTo(new Point(new Vector(3, 0, 0)));
        assertThat(pointAlongPath(path, .25)).isEqualTo(new Point(new Vector(2.5, 0, 0)));
    }

    @Test
    void ambiguousAndNonfiniteInputsAreRejected() {
        var xy = new Plane(O, Z);
        assertThatThrownBy(() -> axisTwoPoints(O, O)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> planeThreePoints(O, X, X.times(2))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> axisTwoPlanes(xy, offsetPlane(xy, 1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pointTwoEdges(new Axis(O, X), new Axis(Z, Y)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> planeTwoEdges(new Axis(O, X), new Axis(Z, Y)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pointEdgePlane(new Axis(O, X), xy)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> coordinateSystem(O, X, X)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> offsetPlane(xy, Double.NaN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> angledPlane(xy, new Axis(Z, X), 45)).isInstanceOf(IllegalArgumentException.class);
    }
}
