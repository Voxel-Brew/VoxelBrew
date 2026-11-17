package dev.voxelbrew.engine.geometry;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.model.mesh.MeshData;
import dev.voxelbrew.api.model.mesh.MeshValidation;
import org.junit.jupiter.api.Test;

class TessellationCleanupTest {
    @Test
    void removesCollapsedFacetsPreservingFaceMappingsAndOpenMeshDiagnostics() {
        var mesh = new MeshData(
                new double[] {0, 0, 0, 1, 0, 0, 0, 1, 0, 1e-9, 0, 0, 2, 0, 0},
                new int[] {0, 3, 1, 0, 1, 2, 0, 1, 4},
                new int[] {7, 8, 9});
        var cleaned = TessellationCleanup.removeCollapsedTriangles(mesh);
        assertThat(cleaned.triangleCount()).isEqualTo(1);
        assertThat(cleaned.triangles()).containsExactly(new int[] {0, 1, 2});
        assertThat(cleaned.faceIndices()).containsExactly(new int[] {8});
        assertThat(cleaned.vertices()).containsExactly(mesh.vertices());
        assertThat(mesh.triangleCount()).as("input stays immutable").isEqualTo(3);
        var report = MeshValidation.inspect(cleaned);
        assertThat(report.degenerateTriangles()).isEqualTo(0);
        assertThat(report.boundaryEdges()).isEqualTo(3);
        assertThat(report.closed()).as("cleanup must not conceal an open mesh").isFalse();
    }

    @Test
    void rejectsEntirelyCollapsedMesh() {
        var mesh = new MeshData(new double[] {0, 0, 0}, new int[] {0, 0, 0}, new int[] {0});
        assertThatThrownBy(() -> TessellationCleanup.removeCollapsedTriangles(mesh))
                .isInstanceOf(GeometryException.class);
    }
}
