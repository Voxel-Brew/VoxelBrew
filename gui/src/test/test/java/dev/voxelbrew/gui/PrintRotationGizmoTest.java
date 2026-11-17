package dev.voxelbrew.gui;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.engine.model.*;
import dev.voxelbrew.engine.printing.*;
import javafx.geometry.Point2D;
import org.junit.jupiter.api.Test;

class PrintRotationGizmoTest {
    @Test
    void localRotationUsesObjectAxesWhileWorldUsesPlateAxes() {
        var mesh = MeshGeometry.box(
                new SupportVolume("box", "part", SupportVolume.Role.SOLID_SUPPORT, 0, 0, 0, 60, 40, 5));
        var source = new BodyInstance(
                "part", "body", Transform.translation(50, 50, 0).compose(Transform.rotation(0, 0, 90)));
        var local =
                PrintRotationGizmo.rotate(source, mesh, PrintRotationGizmo.Axis.X, 30, PrintRotationGizmo.Space.LOCAL);
        var world = PrintRotationGizmo.rotate(source, mesh, PrintRotationGizmo.Axis.Y, 30);
        assertThat(local.transform().matrix())
                .usingComparatorWithPrecision(1e-8)
                .containsExactly(world.transform().matrix());
        assertThat(local).isNotEqualTo(PrintRotationGizmo.rotate(source, mesh, PrintRotationGizmo.Axis.X, 30));
        var frame = PrintRotationGizmo.frame(source.transform(), PrintRotationGizmo.Space.LOCAL);
        var ring = PrintRotationGizmo.ring(
                PrintRotationGizmo.Axis.X, javafx.geometry.Point3D.ZERO, 1, new PrintCamera(false), 0, 0, 1, frame);
        assertThat(ring.u().getX()).isCloseTo(-1, org.assertj.core.data.Offset.offset(1e-8));
        assertThat(ring.u().getY()).isCloseTo(0, org.assertj.core.data.Offset.offset(1e-8));
        assertThat(PrintRotationGizmo.topAxis(Transform.rotation(0, 90, 0))).isEqualTo(PrintRotationGizmo.Axis.X);
        assertThat(PrintRotationGizmo.topAxis(Transform.IDENTITY)).isEqualTo(PrintRotationGizmo.Axis.Z);
    }

    @Test
    void rotatesEveryAxisAroundCenterAndPreservesClearance() {
        var mesh = MeshGeometry.box(
                new SupportVolume("box", "part", SupportVolume.Role.SOLID_SUPPORT, 0, 0, 0, 60, 40, 5));
        var source = new BodyInstance("part", "body", Transform.translation(10, 20, 3));
        double[][] sizes = {{60, 5, 40}, {5, 40, 60}, {40, 60, 5}};
        for (var axis : PrintRotationGizmo.Axis.values()) {
            var rotated = PrintRotationGizmo.rotate(source, mesh, axis, 90);
            var b = MeshGeometry.bounds(MeshGeometry.transform(mesh, rotated.transform()));
            var size = sizes[axis.ordinal()];
            assertThat(b.maxX() - b.minX()).isCloseTo(size[0], org.assertj.core.data.Offset.offset(1e-8));
            assertThat(b.maxY() - b.minY()).isCloseTo(size[1], org.assertj.core.data.Offset.offset(1e-8));
            assertThat(b.maxZ() - b.minZ()).isCloseTo(size[2], org.assertj.core.data.Offset.offset(1e-8));
            assertThat((b.minX() + b.maxX()) / 2).isCloseTo(40, org.assertj.core.data.Offset.offset(1e-8));
            assertThat((b.minY() + b.maxY()) / 2).isCloseTo(40, org.assertj.core.data.Offset.offset(1e-8));
            assertThat(b.minZ()).isCloseTo(3, org.assertj.core.data.Offset.offset(1e-8));
            assertThat(rotated.id()).isEqualTo(source.id());
            assertThat(PrintRotationGizmo.rotate(source, mesh, axis, 0)).isSameAs(source);
        }
    }

    @Test
    void picksRingButLeavesCenterForMovingAndUnwrapsSnappedAngles() {
        var ring = new PrintRotationGizmo.Ring(
                PrintRotationGizmo.Axis.Z, Point2D.ZERO, new Point2D(100, 0), new Point2D(0, 100));
        assertThat(ring.hit(Point2D.ZERO)).isNull();
        var start = ring.point(Math.toRadians(170));
        var hit = ring.hit(start);
        assertThat(hit).isNotNull();
        var drag = new PrintRotationGizmo.Drag(hit, start);
        assertThat(drag.degrees(ring.point(Math.toRadians(-170)), false))
                .isCloseTo(20, org.assertj.core.data.Offset.offset(1e-8));
        assertThat(drag.degrees(ring.point(Math.toRadians(-162)), true))
                .isCloseTo(30, org.assertj.core.data.Offset.offset(1e-8));
    }

    @Test
    void edgeOnRingRemainsDraggable() {
        var ring =
                new PrintRotationGizmo.Ring(PrintRotationGizmo.Axis.X, Point2D.ZERO, new Point2D(0, 100), Point2D.ZERO);
        var start = ring.point(ring.handleAngle());
        var drag = new PrintRotationGizmo.Drag(ring.hit(start), start);
        assertThat(drag.degrees(start.add(0, -20), true)).isCloseTo(15, org.assertj.core.data.Offset.offset(1e-8));
    }
}
