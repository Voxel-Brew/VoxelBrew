package dev.voxelbrew.engine.geometry;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.mesh.MeshData;
import dev.voxelbrew.engine.mesh.MeshValidation;
import org.junit.jupiter.api.Test;

class TessellationCleanupTest {
    @Test
    void removesCollapsedFacetsPreservingFaceMappingsAndOpenMeshDiagnostics() {
        var mesh = new MeshData(
                new double[] {0, 0, 0, 1, 0, 0, 0, 1, 0, 1e-9, 0, 0, 2, 0, 0},
                new int[] {0, 3, 1, 0, 1, 2, 0, 1, 4},
                new int[] {7, 8, 9});
        var cleaned = TessellationCleanup.removeCollapsedTriangles(mesh);
        assertEquals(1, cleaned.triangleCount());
        assertArrayEquals(new int[] {0, 1, 2}, cleaned.triangles());
        assertArrayEquals(new int[] {8}, cleaned.faceIndices());
        assertArrayEquals(mesh.vertices(), cleaned.vertices());
        assertEquals(3, mesh.triangleCount(), "input stays immutable");
        var report = MeshValidation.inspect(cleaned);
        assertEquals(0, report.degenerateTriangles());
        assertEquals(3, report.boundaryEdges());
        assertFalse(report.closed(), "cleanup must not conceal an open mesh");
    }

    @Test
    void rejectsEntirelyCollapsedMesh() {
        var mesh = new MeshData(new double[] {0, 0, 0}, new int[] {0, 0, 0}, new int[] {0});
        assertThrows(GeometryException.class, () -> TessellationCleanup.removeCollapsedTriangles(mesh));
    }
}
