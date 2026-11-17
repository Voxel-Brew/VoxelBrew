package dev.voxelbrew.engine.printing;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.model.*;
import dev.voxelbrew.api.model.mesh.MeshData;
import dev.voxelbrew.api.model.printing.MeshGeometry;
import dev.voxelbrew.api.model.printing.SupportVolume;
import dev.voxelbrew.engine.model.*;
import org.junit.jupiter.api.Test;

/** Analytic mesh fixtures: no kernel, display, native libraries or external processes. */
class PrintOrientationTest {
    private MeshData box() {
        return MeshGeometry.box(new SupportVolume("box", "part", SupportVolume.Role.SOLID_SUPPORT, 0, 0, 0, 60, 40, 8));
    }

    private BodyInstance source() {
        return new BodyInstance(
                "instance", "body", Transform.translation(20, 30, 7).compose(Transform.rotation(0, 0, 35)));
    }

    @Test
    void zeroRotationRetainsTheOriginalInstance() {
        var source = source();
        assertThat(PrintOrientation.rotate(source, box(), 0, 0, false)).isSameAs(source);
    }

    @Test
    void rotationPreservesIdentityCenterAndBedClearanceWithoutMutatingInputs() {
        var source = source();
        var mesh = box();
        var matrix = source.transform().matrix();
        var vertices = mesh.vertices();
        var before = MeshGeometry.bounds(MeshGeometry.transform(mesh, source.transform()));
        var result = PrintOrientation.rotate(source, mesh, 0, 90, false);
        var after = MeshGeometry.bounds(MeshGeometry.transform(mesh, result.transform()));
        assertThat(result.id()).isEqualTo(source.id());
        assertThat(result.bodyId()).isEqualTo(source.bodyId());
        assertThat(after.minZ()).isCloseTo(before.minZ(), org.assertj.core.data.Offset.offset(1e-8));
        assertThat((after.minX() + after.maxX()) / 2)
                .isCloseTo((before.minX() + before.maxX()) / 2, org.assertj.core.data.Offset.offset(1e-8));
        assertThat((after.minY() + after.maxY()) / 2)
                .isCloseTo((before.minY() + before.maxY()) / 2, org.assertj.core.data.Offset.offset(1e-8));
        assertThat(source.transform().matrix()).containsExactly(matrix);
        assertThat(mesh.vertices()).containsExactly(vertices);
    }

    @Test
    void localAndWorldAxesProduceDifferentOrientationsForAnAlreadyRotatedObject() {
        var source = source();
        var mesh = box();
        var world = PrintOrientation.rotate(source, mesh, 0, 90, false);
        var local = PrintOrientation.rotate(source, mesh, 0, 90, true);
        var worldBounds = MeshGeometry.bounds(MeshGeometry.transform(mesh, world.transform()));
        var localBounds = MeshGeometry.bounds(MeshGeometry.transform(mesh, local.transform()));
        assertThat(Math.abs(worldBounds.maxZ() - localBounds.maxZ()) > 1).isTrue();
        assertThat(localBounds.minZ()).isCloseTo(7, org.assertj.core.data.Offset.offset(1e-8));
        assertThat(worldBounds.minZ()).isCloseTo(7, org.assertj.core.data.Offset.offset(1e-8));
    }

    @Test
    void invalidAxesAndNonfiniteAnglesAreRejectedBeforeGeometryWork() {
        var source = source();
        var mesh = box();
        for (int axis : new int[] {-1, 3})
            assertThatThrownBy(() -> PrintOrientation.rotate(source, mesh, axis, 10, false))
                    .isInstanceOf(IllegalArgumentException.class);
        for (double angle : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
            assertThatThrownBy(() -> PrintOrientation.rotate(source, mesh, 0, angle, true))
                    .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void automaticOrientationChoosesABroadBedFaceAndPreservesHorizontalCenter() {
        var mesh = box();
        var source = new BodyInstance(
                "instance", "body", Transform.translation(20, 30, 7).compose(Transform.rotation(90, 0, 0)));
        var before = MeshGeometry.bounds(MeshGeometry.transform(mesh, source.transform()));
        var result = PrintOrientation.autoOrient(mesh, source.transform());
        var after = MeshGeometry.bounds(MeshGeometry.transform(mesh, result));
        assertThat(after.minZ()).isCloseTo(0, org.assertj.core.data.Offset.offset(1e-8));
        assertThat(after.maxZ()).isCloseTo(8, org.assertj.core.data.Offset.offset(1e-8));
        assertThat((after.minX() + after.maxX()) / 2)
                .isCloseTo((before.minX() + before.maxX()) / 2, org.assertj.core.data.Offset.offset(1e-8));
        assertThat((after.minY() + after.maxY()) / 2)
                .isCloseTo((before.minY() + before.maxY()) / 2, org.assertj.core.data.Offset.offset(1e-8));
    }

    @Test
    void automaticOrientationRejectsMeshesWithoutASupportingPlanarFace() {
        var mesh = new MeshData(new double[] {0, 0, 0, 1, 0, 0, 2, 0, 0}, new int[] {0, 1, 2}, new int[] {0});
        assertThatThrownBy(() -> PrintOrientation.autoOrient(mesh, Transform.IDENTITY))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
