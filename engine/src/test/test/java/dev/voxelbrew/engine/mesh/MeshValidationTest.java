package dev.voxelbrew.engine.mesh;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class MeshValidationTest {
    @Test
    void invalidMeshesAndTolerancesAreRejected() {
        assertThatThrownBy(() -> new MeshOptions(Double.NaN, 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MeshOptions(0.02, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MeshValidation.requireClosed(
                        new MeshData(new double[] {0, 0, 0, 1, 0, 0, 0, 1, 0}, new int[] {0, 1, 2}, new int[] {0})))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
