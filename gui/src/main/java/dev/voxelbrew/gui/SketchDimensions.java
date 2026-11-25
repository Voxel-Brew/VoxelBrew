package dev.voxelbrew.gui;

import static dev.voxelbrew.api.model.sketch.SketchGeometry.*;

import dev.voxelbrew.api.model.sketch.SketchGeometry;
import java.util.List;

/** Infers a driving measurement from the selected references and annotation placement. */
final class SketchDimensions {
    record Measurement(Type type, double value) {}

    static List<Ref> references(SketchGeometry g, List<Ref> refs) {
        return refs.stream()
                .map(r -> r.part() == Part.OBJECT && g.entity(r.entityId()).kind() == Kind.POINT
                        ? g.entity(r.entityId()).ref(Part.POINT)
                        : r)
                .toList();
    }

    static List<Ref> drivingReferences(SketchGeometry g, List<Ref> selected, Type type) {
        var refs = references(g, selected);
        if (refs.size() == 2 && (type == Type.DISTANCE_X || type == Type.DISTANCE_Y)) {
            var delta = g.point(refs.getLast()).minus(g.point(refs.getFirst()));
            if ((type == Type.DISTANCE_X ? delta.x() : delta.y()) < 0) return refs.reversed();
        }
        return refs;
    }

    static Measurement measure(SketchGeometry g, List<Ref> refs, Vec position) {
        refs = references(g, refs);
        var pointLine = dev.voxelbrew.engine.sketch.SketchConstraints.pointLineDistance(g, refs);
        if (pointLine.isPresent()) {
            return new Measurement(Type.DISTANCE, pointLine.get().value());
        }
        if (refs.size() == 1 && refs.getFirst().part() == Part.OBJECT) {
            var e = g.entity(refs.getFirst().entityId());
            if (e.kind() == Kind.LINE) {
                return new Measurement(Type.LENGTH, e.at(0).distance(e.at(1)));
            }
            if (e.kind() == Kind.CIRCLE || e.kind() == Kind.ARC) return new Measurement(Type.RADIUS, e.value(2));
        }
        if (refs.size() == 2 && refs.stream().allMatch(r -> r.part() == Part.OBJECT)) {
            return measureCurves(
                    g.entity(refs.getFirst().entityId()),
                    g.entity(refs.getLast().entityId()));
        }
        if (refs.size() == 2 && refs.stream().allMatch(r -> r.part() != Part.OBJECT)) {
            return measurePoints(g.point(refs.get(0)), g.point(refs.get(1)), position);
        }
        throw new IllegalArgumentException("Unsupported dimension selection");
    }

    private static Measurement measureCurves(Entity a, Entity b) {
        var circle = a.kind() == Kind.CIRCLE ? a : b;
        var line = circle == a ? b : a;
        if (circle.kind() == Kind.CIRCLE && line.kind() == Kind.LINE) {
            var direction = line.at(1).minus(line.at(0)).unit();
            double gap = Math.abs(direction.cross(circle.point(Part.CENTER).minus(line.at(0)))) - circle.value(2);
            if (gap >= 0) return new Measurement(Type.DISTANCE, gap);
        }
        if (a.kind() == Kind.LINE && b.kind() == Kind.LINE) {
            var u = a.at(1).minus(a.at(0));
            var v = b.at(1).minus(b.at(0));
            if (Math.abs(u.unit().cross(v.unit())) < 1e-6) {
                return new Measurement(
                        Type.DISTANCE, Math.abs(u.unit().cross(b.at(0).minus(a.at(0)))));
            }
            return new Measurement(Type.ANGLE, Math.toDegrees(Math.atan2(u.cross(v), u.dot(v))));
        }
        throw new IllegalArgumentException("Unsupported dimension selection");
    }

    private static Measurement measurePoints(Vec a, Vec b, Vec position) {
        if (Math.abs(b.x() - a.x()) > 1e-8
                && Math.abs(b.y() - a.y()) > 1e-8
                && position.x() >= Math.min(a.x(), b.x())
                && position.x() <= Math.max(a.x(), b.x())
                && position.y() >= Math.min(a.y(), b.y())
                && position.y() <= Math.max(a.y(), b.y())) {
            return new Measurement(Type.DISTANCE, a.distance(b));
        }
        boolean vertical = position.x() < Math.min(a.x(), b.x()) || position.x() > Math.max(a.x(), b.x());
        return vertical
                ? new Measurement(Type.DISTANCE_Y, Math.abs(b.y() - a.y()))
                : new Measurement(Type.DISTANCE_X, Math.abs(b.x() - a.x()));
    }

    private SketchDimensions() {}
}
