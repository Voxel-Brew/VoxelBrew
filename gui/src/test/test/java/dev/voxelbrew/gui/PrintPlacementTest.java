package dev.voxelbrew.gui;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.engine.model.*;
import org.junit.jupiter.api.Test;

class PrintPlacementTest {
    @Test
    void automaticOrientationUsesBroadBedFaceAndPreservesCenter() {
        var mesh = dev.voxelbrew.engine.printing.MeshGeometry.box(new dev.voxelbrew.engine.printing.SupportVolume(
                "support", "part", dev.voxelbrew.engine.printing.SupportVolume.Role.SOLID_SUPPORT, 0, 0, 0, 60, 40, 5));
        var current = Transform.translation(80, 90, 20).compose(Transform.rotation(90, 0, 30));
        var before = dev.voxelbrew.engine.printing.MeshGeometry.bounds(
                dev.voxelbrew.engine.printing.MeshGeometry.transform(mesh, current));
        var oriented = PrintPlacement.autoOrient(mesh, current);
        var after = dev.voxelbrew.engine.printing.MeshGeometry.bounds(
                dev.voxelbrew.engine.printing.MeshGeometry.transform(mesh, oriented));
        assertThat(after.minZ()).isCloseTo(0, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(after.maxZ()).isCloseTo(5, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(after.minX() + after.maxX())
                .isCloseTo(before.minX() + before.maxX(), org.assertj.core.data.Offset.offset(1e-6));
        assertThat(after.minY() + after.maxY())
                .isCloseTo(before.minY() + before.maxY(), org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void plateReorderPreservesSelectionContentsAndPreferences() {
        var setup = dev.voxelbrew.engine.printing.PrintSetup.empty()
                .addPlate("second")
                .addPlate("third")
                .withWorkspaceSettings(java.util.Map.of("print-overwrite", "true"));
        var reordered = PrintPlacement.reorder(setup, "plate-1", "third");
        assertThat(reordered.plates().stream()
                        .map(dev.voxelbrew.engine.printing.PrintPlate::id)
                        .toList())
                .isEqualTo(java.util.List.of("second", "third", "plate-1"));
        assertThat(reordered.activePlateId()).isEqualTo(setup.activePlateId());
        assertThat(reordered.workspaceSettings()).isEqualTo(setup.workspaceSettings());
        assertThat(reordered.plates().getLast()).isEqualTo(setup.plates().getFirst());
        assertThat(PrintPlacement.reorder(reordered, "plate-1", "second")).isEqualTo(setup);
    }

    @Test
    void dragInvertsBothProjectionsAndKeepsHeightAndRotation() {
        var source = new BodyInstance(
                "part", "body", Transform.translation(10, 20, 3).compose(Transform.rotation(0, 0, 30)));
        var top = PrintPlacement.move(source, 12, 8, false, false).transform().matrix();
        assertThat(top[3]).isCloseTo(22, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(top[7]).isCloseTo(28, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(top[11]).isCloseTo(3, org.assertj.core.data.Offset.offset(1e-9));
        var iso = PrintPlacement.move(source, 4, 10, true, false).transform().matrix();
        assertThat(iso[3]).isCloseTo(22, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(iso[7]).isCloseTo(28, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(iso[0]).isCloseTo(source.transform().matrix()[0], org.assertj.core.data.Offset.offset(1e-9));
        var snapped =
                PrintPlacement.move(source, 1.2, -2.6, false, true).transform().matrix();
        assertThat(snapped[3]).isCloseTo(11, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(snapped[7]).isCloseTo(17, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void pickingRejectsEmptySpaceAndEdgeOnTriangles() {
        assertThat(PrintPlacement.contains(2, 2, new double[] {0, 10, 0}, new double[] {0, 0, 10}))
                .isTrue();
        assertThat(PrintPlacement.contains(2, 2, new double[] {0, 0, 10}, new double[] {0, 10, 0}))
                .isTrue();
        assertThat(PrintPlacement.contains(8, 8, new double[] {0, 10, 0}, new double[] {0, 0, 10}))
                .isFalse();
        assertThat(PrintPlacement.contains(2, 0, new double[] {0, 10, 20}, new double[] {0, 0, 0}))
                .isFalse();
    }
}
