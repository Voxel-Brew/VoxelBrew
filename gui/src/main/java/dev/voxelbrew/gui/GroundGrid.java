package dev.voxelbrew.gui;

import java.util.DoubleSummaryStatistics;
import java.util.function.UnaryOperator;
import javafx.geometry.Point3D;
import javafx.scene.shape.TriangleMesh;
import lombok.experimental.UtilityClass;

/** Generate world-aligned grid strips within the camera's visible depth range. */
@UtilityClass
class GroundGrid {
    private record Band(TriangleMesh mesh, double step, double near, double far, double originDepth) {
        void strips(
                DoubleSummaryStatistics across,
                DoubleSummaryStatistics along,
                double acrossSlope,
                double alongSlope,
                boolean swap) {
            double first = Math.ceil(across.getMin() / step) * step;
            int count = (int) Math.floor((across.getMax() - first) / step) + 1;
            for (int i = 0; i < count; i++) {
                double at = first + i * step;
                if (at == 0) continue;
                double depth = originDepth + at * acrossSlope;
                double low = along.getMin(), high = along.getMax();
                if (Math.abs(alongSlope) > 1e-12) {
                    double a = (near - depth) / alongSlope, b = (far - depth) / alongSlope;
                    low = Math.max(low, Math.min(a, b));
                    high = Math.min(high, Math.max(a, b));
                } else if (depth < near || depth > far) {
                    continue;
                }
                quad(swap, at, low, high);
            }
        }

        private void quad(boolean swap, double at, double low, double high) {
            if (low >= high) return;
            int index = mesh.getPoints().size() / 3;
            float a = (float) (at - step * .004), b = (float) (at + step * .004);
            float start = (float) low, end = (float) high;
            if (swap) mesh.getPoints().addAll(start, 0, a, start, 0, b, end, 0, b, end, 0, a);
            else mesh.getPoints().addAll(a, 0, start, b, 0, start, b, 0, end, a, 0, end);
            mesh.getFaces().addAll(index, 0, index + 1, 0, index + 2, 0, index, 0, index + 2, 0, index + 3, 0);
        }
    }

    static TriangleMesh mesh(
            UnaryOperator<Point3D> toWorld,
            double distance,
            double slopeX,
            double slopeY,
            double height,
            double near,
            double far) {
        var mesh = new TriangleMesh();
        mesh.getTexCoords().addAll(0, 0);
        var eye = toWorld.apply(new Point3D(0, 0, -distance));
        var forward = toWorld.apply(new Point3D(0, 0, 1 - distance)).subtract(eye);
        double originDepth = -eye.dotProduct(forward);
        // Coarsen by decades once a grid interval projects to fewer than eight pixels.
        double start = near;
        for (double step = 10; start < far; step *= 10) {
            double end = Math.min(far, step * height / (16 * slopeY));
            if (end <= start) continue;
            var x = new DoubleSummaryStatistics();
            var y = new DoubleSummaryStatistics();
            var z = new DoubleSummaryStatistics();
            for (double depth : new double[] {start, end}) {
                for (double sx : new double[] {-slopeX, slopeX}) {
                    for (double sy : new double[] {-slopeY, slopeY}) {
                        var corner = toWorld.apply(new Point3D(sx * depth, sy * depth, depth - distance));
                        x.accept(corner.getX());
                        y.accept(corner.getY());
                        z.accept(corner.getZ());
                    }
                }
            }
            if (y.getMin() <= 0 && y.getMax() >= 0) {
                var band = new Band(mesh, step, start, end, originDepth);
                band.strips(x, z, forward.getX(), forward.getZ(), false);
                band.strips(z, x, forward.getZ(), forward.getX(), true);
            }
            start = end;
        }
        return mesh;
    }
}
