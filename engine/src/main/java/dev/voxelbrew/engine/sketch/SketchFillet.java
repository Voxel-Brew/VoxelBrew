package dev.voxelbrew.engine.sketch;

import static dev.voxelbrew.api.model.sketch.SketchGeometry.*;

import dev.voxelbrew.api.model.sketch.SketchGeometry;
import java.util.ArrayList;
import java.util.List;

/** Trims a shared corner and joins the surviving line segments with a driving-radius tangent arc. */
public final class SketchFillet {
    private record Side(Entity line, Part corner) {
        Part far() {
            return corner == Part.START ? Part.END : Part.START;
        }

        Vec vector() {
            return line.point(far()).minus(line.point(corner));
        }

        Entity trimmed(Vec contact) {
            Vec start = corner == Part.START ? contact : line.at(0);
            Vec end = corner == Part.END ? contact : line.at(1);
            return line.withValues(List.of(start.x(), start.y(), end.x(), end.y()));
        }
    }

    public static SketchGeometry round(SketchGeometry g, String id, Ref first, Ref second, double radius) {
        var a = g.entity(first.entityId());
        var b = g.entity(second.entityId());
        if (first.part() == Part.OBJECT
                && second.part() == Part.OBJECT
                && !a.equals(b)
                && a.kind() == Kind.LINE
                && b.kind() == Kind.LINE
                && a.construction() == b.construction()
                && !FaceProjection.reference(a.id())
                && !FaceProjection.reference(b.id())) {
            for (Part p : a.points()) {
                for (Part q : b.points()) {
                    if (a.point(p).distance(b.point(q)) <= TOLERANCE) {
                        return join(g, id, new Side(a, p), new Side(b, q), radius);
                    }
                }
            }
        }
        throw new IllegalArgumentException("help.sketch-fillet");
    }

    private static SketchGeometry join(SketchGeometry g, String id, Side a, Side b, double radius) {
        Vec u = a.vector().unit(), v = b.vector().unit();
        double cross = u.cross(v);
        if (Math.abs(cross) < TOLERANCE) throw new IllegalArgumentException("help.sketch-fillet");
        double halfAngle = Math.atan2(Math.abs(cross), u.dot(v)) / 2;
        double trim = radius / Math.tan(halfAngle);
        if (!Double.isFinite(trim)
                || radius <= TOLERANCE
                || trim >= Math.min(a.vector().length(), b.vector().length()) - TOLERANCE) {
            throw new IllegalArgumentException("sketch.fillet.size");
        }
        Vec corner = a.line().point(a.corner());
        Vec start = corner.plus(u.times(trim)), end = corner.plus(v.times(trim));
        Vec center = start.plus(u.normal().times(Math.copySign(radius, cross)));
        Vec radial = start.minus(center);
        var arc = SketchGeometry.entity(
                id,
                Kind.ARC,
                a.line().construction(),
                center.x(),
                center.y(),
                radius,
                Math.atan2(radial.y(), radial.x()),
                -Math.copySign(Math.PI - 2 * halfAngle, cross));
        var remaining = g.constraints().stream()
                .filter(c -> !(c.type() == Type.COINCIDENT
                        && c.refs().contains(a.line().ref(a.corner()))
                        && c.refs().contains(b.line().ref(b.corner()))))
                .toList();
        var next = g.withConstraints(remaining)
                .replace(a.trimmed(start))
                .replace(b.trimmed(end))
                .add(arc);
        next = preserveDimensions(next, id + "-a", a, b);
        next = preserveDimensions(next, id + "-b", b, a);
        next = next.add(SketchConstraints.create(
                next, id + "-start", Type.TANGENT, List.of(a.line().ref(a.corner()), arc.ref(Part.START)), 0));
        next = next.add(SketchConstraints.create(
                next, id + "-end", Type.TANGENT, List.of(b.line().ref(b.corner()), arc.ref(Part.END)), 0));
        next = next.add(SketchConstraints.create(next, id + "-radius", Type.RADIUS, List.of(arc.ref()), radius));
        if (g.boundary().contains(a.line().id())
                && g.boundary().contains(b.line().id())) {
            var boundary = new ArrayList<>(g.boundary());
            boundary.add(id);
            next = next.withBoundary(boundary);
        }
        return next;
    }

    // A dimension on the original length or corner still measures its virtual sharp intersection.
    private static SketchGeometry preserveDimensions(SketchGeometry g, String id, Side side, Side other) {
        boolean needsGuide = g.constraints().stream()
                .anyMatch(c -> c.refs().contains(side.line().ref(side.corner()))
                        || c.refs().contains(side.line().ref())
                                && switch (c.type()) {
                                    case LENGTH, EQUAL, FIX, MIDPOINT, POLYGON, SYMMETRY -> true;
                                    default -> false;
                                });
        if (!needsGuide) return g;
        var guide = new Entity(id, Kind.LINE, side.line().values(), true);
        var next = g.add(guide)
                .withConstraints(g.constraints().stream()
                        .map(c -> new Constraint(
                                c.id(),
                                c.type(),
                                c.refs().stream()
                                        .map(r ->
                                                r.entityId().equals(side.line().id()) ? guide.ref(r.part()) : r)
                                        .toList(),
                                c.values()))
                        .toList());
        next = next.add(SketchConstraints.create(
                next,
                id + "-far",
                Type.COINCIDENT,
                List.of(guide.ref(side.far()), side.line().ref(side.far())),
                0));
        next = next.add(SketchConstraints.create(
                next, id + "-along", Type.COINCIDENT, List.of(side.line().ref(side.corner()), guide.ref()), 0));
        return next.add(SketchConstraints.create(
                next,
                id + "-corner",
                Type.COINCIDENT,
                List.of(guide.ref(side.corner()), other.line().ref()),
                0));
    }

    private SketchFillet() {}
}
