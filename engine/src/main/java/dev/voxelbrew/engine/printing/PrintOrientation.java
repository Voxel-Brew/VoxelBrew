package dev.voxelbrew.engine.printing;

import dev.voxelbrew.api.model.Transform;
import dev.voxelbrew.api.model.mesh.MeshData;
import dev.voxelbrew.api.model.mesh.MeshValidation;
import dev.voxelbrew.api.model.printing.MeshGeometry;
import java.awt.geom.Rectangle2D;

/** Shared automatic bed orientation for desktop and headless preparation. */
public final class PrintOrientation {
    /** Reduce the XY footprint while preserving the horizontal center and each vertex's height. */
    public static Transform autoOrient(MeshData mesh, Transform current) {
        var world = MeshGeometry.transform(mesh, current);
        requireSurface(world);
        var original = footprint(world, 0);
        var best = original;
        int bestAngle = 0;
        // Bounding rectangle area repeats every quarter turn. Keep the current pose on a tie.
        for (int degrees = -44; degrees <= 45; degrees++) {
            if (degrees == 0) continue;
            var candidate = footprint(world, degrees);
            if (candidate.width * candidate.height < best.width * best.height * (1 - 1e-12)) {
                best = candidate;
                bestAngle = degrees;
            }
        }
        if (bestAngle == 0) return current;
        return Transform.translation(
                        original.getCenterX() - best.getCenterX(), original.getCenterY() - best.getCenterY(), 0)
                .compose(Transform.rotation(0, 0, bestAngle))
                .compose(current);
    }

    private static Rectangle2D.Double footprint(MeshData world, int degrees) {
        double angle = Math.toRadians(degrees);
        double cos = Math.cos(angle), sin = Math.sin(angle);
        var bounds = new Rectangle2D.Double();
        for (int vertex = 0; vertex < world.vertexCount(); vertex++) {
            double x = world.coordinate(vertex, 0), y = world.coordinate(vertex, 1);
            double rotatedX = cos * x - sin * y, rotatedY = sin * x + cos * y;
            if (vertex == 0) bounds.setRect(rotatedX, rotatedY, 0, 0);
            else bounds.add(rotatedX, rotatedY);
        }
        return bounds;
    }

    private static void requireSurface(MeshData mesh) {
        for (int triangle = 0; triangle < mesh.triangleCount(); triangle++) {
            var normal = MeshValidation.normal(mesh, triangle);
            if (Math.hypot(Math.hypot(normal[0], normal[1]), normal[2]) >= 1e-12) return;
        }
        throw new IllegalArgumentException("Cannot orient a mesh with no nondegenerate triangles");
    }

    /** Rotate about the placed center in world/local axes, preserving bed clearance. */
    public static dev.voxelbrew.api.model.BodyInstance rotate(
            dev.voxelbrew.api.model.BodyInstance source,
            dev.voxelbrew.api.model.mesh.MeshData mesh,
            int axis,
            double degrees,
            boolean local) {
        if (axis < 0 || axis > 2 || !Double.isFinite(degrees)) throw new IllegalArgumentException("Invalid rotation");
        if (Math.abs(degrees) < 1e-9) return source;
        var before = MeshGeometry.bounds(MeshGeometry.transform(mesh, source.transform()));
        double x = (before.minX() + before.maxX()) / 2,
                y = (before.minY() + before.maxY()) / 2,
                z = (before.minZ() + before.maxZ()) / 2;
        var rotation = Transform.rotation(axis == 0 ? degrees : 0, axis == 1 ? degrees : 0, axis == 2 ? degrees : 0);
        var matrix = source.transform().matrix();
        matrix[3] = matrix[7] = matrix[11] = 0;
        var frame = local ? new Transform(matrix) : Transform.IDENTITY;
        rotation = frame.compose(rotation).compose(frame.inverse());
        var placed = Transform.translation(x, y, z)
                .compose(rotation)
                .compose(Transform.translation(-x, -y, -z))
                .compose(source.transform());
        var after = MeshGeometry.bounds(MeshGeometry.transform(mesh, placed));
        return new dev.voxelbrew.api.model.BodyInstance(
                source.id(),
                source.bodyId(),
                Transform.translation(0, 0, before.minZ() - after.minZ()).compose(placed));
    }

    private PrintOrientation() {}
}
