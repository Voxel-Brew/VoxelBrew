package dev.voxelbrew.gui;

import java.util.DoubleSummaryStatistics;
import java.util.function.UnaryOperator;
import javafx.geometry.Point3D;
import javafx.scene.shape.TriangleMesh;
import lombok.experimental.UtilityClass;

/** World-aligned ground lines with perspective-correct pixel widths. */
@UtilityClass
class GroundGrid {
    private static final double MIN_SPACING = 6;
    private static final double FULL_SPACING = 12;
    private static final double HALF_WIDTH_PIXELS = .5;

    private record View(Point3D eye, Point3D forward, Point3D right, Point3D up, double focal, double near) {
        double depth(Point3D point) {
            return point.subtract(eye).dotProduct(forward);
        }

        void strip(
                TriangleMesh mesh,
                Point3D anchor,
                Point3D direction,
                double low,
                double high,
                double step,
                double far) {
            // The plane through the eye and this line projects to a line on screen.
            var normal = anchor.subtract(eye).crossProduct(direction);
            double nx = normal.dotProduct(right), ny = normal.dotProduct(up);
            double length = Math.hypot(nx, ny);
            if (length == 0) return;
            var side = right.multiply(nx / length).add(up.multiply(ny / length));
            double spacing = step * focal * Math.abs(eye.getY()) / length;
            double endDepth = Math.min(far, spacing / MIN_SPACING);
            if (endDepth <= near) return;
            double base = depth(anchor), slope = direction.dotProduct(forward);
            if (Math.abs(slope) > 1e-12) {
                double a = (near - base) / slope, b = (endDepth - base) / slope;
                low = Math.max(low, Math.min(a, b));
                high = Math.min(high, Math.max(a, b));
            } else if (base < near || base > endDepth) {
                return;
            }
            if (low >= high) return;
            var first = anchor.add(direction.multiply(low));
            var last = anchor.add(direction.multiply(high));
            // Split at the start of the taper so the nearby part keeps its full width.
            double fadeDepth = spacing / FULL_SPACING;
            if (Math.min(depth(first), depth(last)) < fadeDepth && fadeDepth < Math.max(depth(first), depth(last))) {
                var split = anchor.add(direction.multiply((fadeDepth - base) / slope));
                quad(mesh, first, split, side, spacing);
                first = split;
            }
            quad(mesh, first, last, side, spacing);
        }

        private void quad(TriangleMesh mesh, Point3D start, Point3D end, Point3D side, double spacing) {
            int index = mesh.getPoints().size() / 3;
            var a = offset(start, side, spacing);
            var b = offset(end, side, spacing);
            for (var point : new Point3D[] {start.subtract(a), start.add(a), end.add(b), end.subtract(b)}) {
                mesh.getPoints().addAll((float) point.getX(), (float) point.getY(), (float) point.getZ());
            }
            mesh.getFaces().addAll(index, 0, index + 1, 0, index + 2, 0, index, 0, index + 2, 0, index + 3, 0);
        }

        private Point3D offset(Point3D point, Point3D side, double spacing) {
            double depth = depth(point);
            double fade = Math.clamp((spacing / depth - MIN_SPACING) / (FULL_SPACING - MIN_SPACING), 0, 1);
            return side.multiply(HALF_WIDTH_PIXELS * depth / focal * fade);
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
        var view = new View(
                eye,
                toWorld.apply(new Point3D(0, 0, 1 - distance)).subtract(eye),
                toWorld.apply(new Point3D(1, 0, -distance)).subtract(eye),
                toWorld.apply(new Point3D(0, 1, -distance)).subtract(eye),
                height / (2 * slopeY),
                near);
        // A frustum corner bounds the maximum projected spacing for any visible strip.
        double cornerScale = Math.sqrt(1 + slopeX * slopeX + slopeY * slopeY);
        for (double step = 10; ; step *= 10) {
            boolean last = step >= far * cornerScale * 2;
            double end = Math.min(far, step * view.focal() * cornerScale / MIN_SPACING);
            var x = new DoubleSummaryStatistics();
            var y = new DoubleSummaryStatistics();
            var z = new DoubleSummaryStatistics();
            for (double depth : new double[] {near, end}) {
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
                strips(mesh, view, x, z, step, end, last, false);
                strips(mesh, view, z, x, step, end, last, true);
            }
            if (last) return mesh;
        }
    }

    private static void strips(
            TriangleMesh mesh,
            View view,
            DoubleSummaryStatistics across,
            DoubleSummaryStatistics along,
            double step,
            double end,
            boolean last,
            boolean swap) {
        double first = Math.ceil(across.getMin() / step) * step;
        int count = (int) Math.floor((across.getMax() - first) / step) + 1;
        for (int i = 0; i < count; i++) {
            double at = first + i * step;
            // Axes have their own renderer. Each other line belongs to exactly one decade.
            if (at == 0 || !last && at % (step * 10) == 0) continue;
            var anchor = swap ? new Point3D(0, 0, at) : new Point3D(at, 0, 0);
            var direction = swap ? new Point3D(1, 0, 0) : new Point3D(0, 0, 1);
            view.strip(mesh, anchor, direction, along.getMin(), along.getMax(), step, end);
        }
    }
}
