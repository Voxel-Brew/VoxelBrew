package dev.voxelbrew.engine.model;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.model.FaceSupport;
import dev.voxelbrew.api.model.Feature;
import dev.voxelbrew.api.model.geometry.PlanarFace;
import java.util.List;
import org.junit.jupiter.api.Test;

class FaceSupportTest {
    @Test
    void supportUsesOrientedPlaneGroupsInsteadOfPersistingRevisionLocalFaceIds() {
        var output = new Feature.BodyRef("body", "solid");
        var plane = FaceSupport.attach(
                output,
                List.of(new PlanarFace(7, 0, 0, 1, 5), new PlanarFace(8, 0, 0, 1, 5), new PlanarFace(3, 0, 0, -1, 0)),
                8);
        assertThat(plane.support().count()).isEqualTo(1);
        var moved = plane.support().resolve(List.of(new PlanarFace(33, 0, 0, 1, 8)), plane.frame());
        assertThat(moved.apply(2, 3, 0)).usingComparatorWithPrecision(1e-12).containsExactly(new double[] {2, 3, 8});
        assertThatThrownBy(() -> plane.support()
                        .resolve(List.of(new PlanarFace(7, 0, 0, 1, 5), new PlanarFace(8, 0, 0, 1, 8)), plane.frame()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> plane.support().resolve(List.of(), plane.frame()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FaceSupport.attach(output, List.of(), 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
