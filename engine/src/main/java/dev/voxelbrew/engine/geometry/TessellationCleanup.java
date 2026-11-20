package dev.voxelbrew.engine.geometry;

import dev.voxelbrew.api.model.mesh.MeshData;
import dev.voxelbrew.api.model.mesh.MeshValidation;
import java.util.Arrays;

/** Removes collapsed facets emitted at curved-surface singularities by the native mesher. */
final class TessellationCleanup {
    static MeshData removeCollapsedTriangles(MeshData mesh) {
        int[] triangles = mesh.triangles();
        int[] faces = mesh.faceIndices();
        int kept = 0;
        for (int t = 0; t < mesh.triangleCount(); t++) {
            int a = mesh.vertexIndex(t, 0), b = mesh.vertexIndex(t, 1), c = mesh.vertexIndex(t, 2);
            double[] normal = MeshValidation.normal(mesh, t);
            // Match preflight's welding grid so facets collapsed by welding are removed too.
            if (coincident(mesh, a, b)
                    || coincident(mesh, b, c)
                    || coincident(mesh, c, a)
                    || Math.hypot(Math.hypot(normal[0], normal[1]), normal[2]) < 1e-15) continue;
            triangles[3 * kept] = a;
            triangles[3 * kept + 1] = b;
            triangles[3 * kept + 2] = c;
            faces[kept++] = mesh.faceIndex(t);
        }
        if (kept == mesh.triangleCount()) return mesh;
        if (kept == 0) throw new GeometryException("Tessellation contains only collapsed triangles");
        // Preserve vertex coordinates and original face IDs for picking and exported face mappings.
        // Do not fill holes or relax topology checks: print preflight still validates the result.
        return new MeshData(mesh.vertices(), Arrays.copyOf(triangles, 3 * kept), Arrays.copyOf(faces, kept));
    }

    private static boolean coincident(MeshData mesh, int a, int b) {
        for (int axis = 0; axis < 3; axis++) {
            if (Math.round(mesh.coordinate(a, axis) / MeshValidation.WELD_MM)
                    != Math.round(mesh.coordinate(b, axis) / MeshValidation.WELD_MM)) return false;
        }
        return true;
    }

    private TessellationCleanup() {}
}
