package dev.voxelbrew.gui;

import dev.voxelbrew.api.model.Transform;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.CullFace;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.TriangleMesh;

/** One depth-tested, screen-width ribbon per CAD face boundary. */
final class PrintBoundaryMesh extends MeshView {
    private final double[] endpoints;
    private final float[] points;

    private static double[] placedEndpoints(TechnicalEdges edges, Transform placement) {
        var boundaries =
                edges.edges().stream().filter(TechnicalEdges.Edge::boundary).toList();
        var endpoints = new double[boundaries.size() * 6];
        for (int i = 0; i < boundaries.size(); i++) {
            var a = boundaries.get(i).a();
            var b = boundaries.get(i).b();
            System.arraycopy(placement.apply(a.x(), a.y(), a.z()), 0, endpoints, i * 6, 3);
            System.arraycopy(placement.apply(b.x(), b.y(), b.z()), 0, endpoints, i * 6 + 3, 3);
        }
        return endpoints;
    }

    static PrintBoundaryMesh footprint(dev.voxelbrew.api.model.geometry.Bounds bounds, Color color) {
        double x = bounds.minX(), y = bounds.minY(), right = bounds.maxX(), top = bounds.maxY();
        return new PrintBoundaryMesh(
                new double[] {
                    x, y, 0, right, y, 0, right, y, 0, right, top, 0, right, top, 0, x, top, 0, x, top, 0, x, y, 0
                },
                color);
    }

    PrintBoundaryMesh(TechnicalEdges edges, Transform placement) {
        this(placedEndpoints(edges, placement), Color.BLACK);
    }

    private PrintBoundaryMesh(double[] endpoints, Color color) {
        super(new TriangleMesh());
        this.endpoints = endpoints;
        points = new float[endpoints.length * 2];
        int[] faces = new int[points.length];
        int[] corners = {0, 1, 2, 2, 1, 3};
        for (int i = 0; i < endpoints.length / 6; i++) {
            for (int c = 0; c < corners.length; c++) faces[i * 12 + c * 2] = i * 4 + corners[c];
        }
        var mesh = (TriangleMesh) getMesh();
        mesh.getTexCoords().setAll(0, 0);
        mesh.getPoints().setAll(points);
        mesh.getFaces().setAll(faces);
        setCullFace(CullFace.NONE);
        setMaterial(new PhongMaterial(color));
    }

    void update(PrintCamera camera, double scale) {
        double nx = camera.depth(1, 0, 0), ny = camera.depth(0, 1, 0), nz = camera.depth(0, 0, 1);
        double radius = 0.5 / scale;
        double bias = radius;
        for (int i = 0; i < endpoints.length; i += 6) {
            double x = endpoints[i + 3] - endpoints[i];
            double y = endpoints[i + 4] - endpoints[i + 1];
            double z = endpoints[i + 5] - endpoints[i + 2];
            double sx = y * nz - z * ny, sy = z * nx - x * nz, sz = x * ny - y * nx;
            double length = Math.sqrt(sx * sx + sy * sy + sz * sz);
            double factor = length == 0 ? 0 : radius / length;
            for (int end = i; end <= i + 3; end += 3) {
                // A subpixel depth bias lets the line sit on its own face without z-fighting.
                double ax = endpoints[end] - bias * nx;
                double ay = endpoints[end + 1] - bias * ny;
                double az = endpoints[end + 2] - bias * nz;
                int p = end * 2;
                points[p] = (float) (ax + sx * factor);
                points[p + 1] = (float) (ay + sy * factor);
                points[p + 2] = (float) (az + sz * factor);
                points[p + 3] = (float) (ax - sx * factor);
                points[p + 4] = (float) (ay - sy * factor);
                points[p + 5] = (float) (az - sz * factor);
            }
        }
        ((TriangleMesh) getMesh()).getPoints().setAll(points);
    }
}
