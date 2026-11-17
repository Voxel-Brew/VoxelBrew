package dev.voxelbrew.gui;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.model.mesh.MeshData;
import java.util.*;
import org.junit.jupiter.api.Test;

class TechnicalEdgesTest {
    @Test
    void weldedFaceBoundariesExcludePlanarTriangleDiagonals() {
        double[] points = {0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0, 0, 0, 1, 1, 0, 1, 1, 1, 1, 0, 1, 1};
        int[] triangles = {
            0, 2, 1, 0, 3, 2, 4, 5, 6, 4, 6, 7, 0, 1, 5, 0, 5, 4, 1, 2, 6, 1, 6, 5, 2, 3, 7, 2, 7, 6, 3, 0, 4, 3, 4, 7
        };
        // Duplicate vertices at every triangle, as face-local native tessellations can do.
        double[] detached = new double[triangles.length * 3];
        int[] indices = new int[triangles.length], faces = new int[triangles.length / 3];
        for (int i = 0; i < triangles.length; i++) {
            System.arraycopy(points, triangles[i] * 3, detached, i * 3, 3);
            indices[i] = i;
            faces[i / 3] = i / 6;
        }
        var mesh = new MeshData(detached, indices, faces);
        var edges = TechnicalEdges.from(mesh).edges();
        assertThat(edges.size()).isEqualTo(18);
        assertThat(edges.stream().filter(TechnicalEdges.Edge::boundary).count()).isEqualTo(12);
        for (var eye : List.of(new TechnicalEdges.Point(3, 4, 5), new TechnicalEdges.Point(-3, -4, -5)))
            assertThat(edges.stream().filter(edge -> edge.visibleFrom(eye)).count())
                    .isEqualTo(12);
        assertThat(mesh.vertices())
                .as("Rendering adjacency must not change exported geometry")
                .containsExactly(detached);
    }

    @Test
    void smoothCylinderKeepsRimsAndViewDependentSilhouettesWithoutTriangulation() {
        int n = 32;
        double[] points = new double[(2 * n + 2) * 3];
        int[] triangles = new int[n * 12], faces = new int[n * 4];
        for (int i = 0; i < n; i++) {
            for (int z = 0; z < 2; z++) {
                int v = 3 * (i + z * n);
                points[v] = Math.cos(2 * Math.PI * i / n);
                points[v + 1] = Math.sin(2 * Math.PI * i / n);
                points[v + 2] = z;
            }
            int next = (i + 1) % n;
            int[] patch = {i, next, i + n, next, next + n, i + n, 2 * n, next, i, 2 * n + 1, i + n, next + n};
            System.arraycopy(patch, 0, triangles, i * 12, 12);
            faces[4 * i + 2] = 1;
            faces[4 * i + 3] = 2;
        }
        points[points.length - 1] = 1;
        var edges = TechnicalEdges.from(new MeshData(points, triangles, faces)).edges();
        assertThat(edges.stream().filter(TechnicalEdges.Edge::boundary).count()).isEqualTo(2 * n);
        var eye = new TechnicalEdges.Point(10, 3, 2);
        var silhouettes =
                edges.stream().filter(e -> !e.boundary() && e.visibleFrom(eye)).toList();
        assertThat(silhouettes.size()).isEqualTo(2);
        assertThat(silhouettes.stream().allMatch(e -> e.a().x() == e.b().x() && e.a().y() == e.b().y()))
                .isTrue();
        assertThat(edges.stream()
                        .filter(e -> !e.boundary() && e.visibleFrom(new TechnicalEdges.Point(3, 10, 2)))
                        .toList())
                .isNotEqualTo(silhouettes);
    }
}
