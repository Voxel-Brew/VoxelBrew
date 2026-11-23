package dev.voxelbrew.gui;

import java.util.ArrayList;
import java.util.List;
import javafx.geometry.Point2D;
import javafx.geometry.Point3D;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.CullFace;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.TriangleMesh;
import javafx.scene.transform.Transform;

/** A signed model axis clipped to the perspective camera's viewing volume. */
final class GridAxis extends MeshView {
    private final Point3D direction;
    private final Point3D side;
    private final Point3D up;

    private static final class Range {
        private double low = Double.NEGATIVE_INFINITY;
        private double high = Double.POSITIVE_INFINITY;

        // Intersect with the half-space offset + slope * axisCoordinate >= 0.
        void clip(double offset, double slope) {
            if (slope > 0) {
                low = Math.max(low, -offset / slope);
            } else if (slope < 0) {
                high = Math.min(high, -offset / slope);
            } else if (offset < 0) {
                high = Double.NEGATIVE_INFINITY;
            }
        }
    }

    GridAxis(String name, Point3D direction, String color) {
        this.direction = direction;
        side = direction.getX() == 0 ? new Point3D(1, 0, 0) : new Point3D(0, 1, 0);
        up = direction.crossProduct(side);
        setId("grid-axis-" + name);
        setAccessibleText(name.toUpperCase(java.util.Locale.ROOT));
        setMaterial(new PhongMaterial(Color.web(color)));
        setCullFace(CullFace.NONE);
        setManaged(false);
    }

    List<Point2D> update(
            Transform world, double distance, double slopeX, double slopeY, double height, double near, double far) {
        var origin = world.transform(Point3D.ZERO).add(0, 0, distance);
        var ray = world.deltaTransform(direction);
        var range = new Range();
        range.clip(origin.getZ() - near, ray.getZ());
        range.clip(far - origin.getZ(), -ray.getZ());
        for (int sign : new int[] {-1, 1}) {
            range.clip(origin.getZ() * slopeX + sign * origin.getX(), ray.getZ() * slopeX + sign * ray.getX());
            range.clip(origin.getZ() * slopeY + sign * origin.getY(), ray.getZ() * slopeY + sign * ray.getY());
        }
        var mesh = new TriangleMesh();
        mesh.getTexCoords().addAll(0, 0);
        if (range.low < range.high) {
            // Match the ground grid's depth-dependent spacing so distant gaps remain visible.
            double start = near;
            for (double step = 10; start < far; step *= 10) {
                double end = Math.min(far, step * height / (16 * slopeY));
                if (end <= start) continue;
                var band = new Range();
                band.low = range.low;
                band.high = range.high;
                band.clip(origin.getZ() - start, ray.getZ());
                band.clip(end - origin.getZ(), -ray.getZ());
                if (band.low < band.high) {
                    segment(mesh, Math.max(0, band.low), band.high, step);
                    double first = Math.floor(band.low / step) * step;
                    double limit = Math.min(0, band.high);
                    int count = (int) Math.ceil((limit - first) / step);
                    for (int i = 0; i < count; i++) {
                        double dash = first + i * step;
                        segment(mesh, Math.max(band.low, dash), Math.min(limit, dash + .6 * step), step);
                    }
                }
                start = end;
            }
        }
        setMesh(mesh);
        if (range.low >= range.high) return List.of();
        double width = height * slopeX / slopeY;
        var ends = new ArrayList<Point2D>(2);
        for (double coordinate : new double[] {range.high, range.low}) {
            var point = origin.add(ray.multiply(coordinate));
            double x = point.getX() / (point.getZ() * slopeX);
            double y = point.getY() / (point.getZ() * slopeY);
            // Depth clipping can end an axis inside the view, including an axis seen end-on.
            if (Math.max(Math.abs(x), Math.abs(y)) >= 1 - 1e-7) {
                ends.add(new Point2D((1 + x) * width / 2, (1 + y) * height / 2));
            }
        }
        return ends;
    }

    private void segment(TriangleMesh mesh, double low, double high, double step) {
        if (low >= high) return;
        int base = mesh.getPoints().size() / 3;
        double radius = .0075 * step;
        for (double coordinate : new double[] {low, high}) {
            var center = direction.multiply(coordinate);
            for (int i = 0; i < 4; i++) {
                var point = center.add(side.multiply(i < 2 ? radius : -radius))
                        .add(up.multiply(i == 1 || i == 2 ? radius : -radius));
                mesh.getPoints().addAll((float) point.getX(), (float) point.getY(), (float) point.getZ());
            }
        }
        for (int i = 0; i < 4; i++) {
            int a = base + i, b = base + (i + 1) % 4;
            mesh.getFaces().addAll(a, 0, b, 0, b + 4, 0, a, 0, b + 4, 0, a + 4, 0);
        }
        mesh.getFaces().addAll(base, 0, base + 1, 0, base + 2, 0, base, 0, base + 2, 0, base + 3, 0);
        mesh.getFaces().addAll(base + 4, 0, base + 5, 0, base + 6, 0, base + 4, 0, base + 6, 0, base + 7, 0);
    }
}
