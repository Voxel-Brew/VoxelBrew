package dev.voxelbrew.engine.sketch;

import static dev.voxelbrew.api.model.sketch.SketchGeometry.*;

import dev.voxelbrew.api.model.Feature;
import dev.voxelbrew.api.model.geometry.PlanarFace;
import dev.voxelbrew.api.model.sketch.SketchGeometry;
import java.util.*;
import lombok.experimental.UtilityClass;

/** Reference curves of the supporting face, in sketch coordinates. */
@UtilityClass
public class FaceProjection {
    private static final String PREFIX = "face-projection-";
    private static final double TOLERANCE = 1e-6;

    public static boolean reference(String id) {
        return id.startsWith(PREFIX);
    }

    public static List<Entity> edges(Feature.Plane plane, List<PlanarFace> faces, int[] faceEdges, double[] samples) {
        var support = plane.support();
        var frame = support.resolve(faces, plane.frame());
        var local = frame.inverse();
        var origin = frame.apply(0, 0, 0);
        double offset = origin[0] * support.nx() + origin[1] * support.ny() + origin[2] * support.nz();
        var selected = new HashSet<Integer>();
        for (var face : faces) {
            if (Math.abs(face.nx() - support.nx())
                                    + Math.abs(face.ny() - support.ny())
                                    + Math.abs(face.nz() - support.nz())
                            < 1e-8
                    && Math.abs(face.offset() - offset) <= TOLERANCE) {
                selected.add(face.faceIndex());
            }
        }
        var edges = new TreeMap<Integer, List<Vec>>();
        for (int i = 0; i < faceEdges.length; i += 2) {
            if (selected.contains(faceEdges[i])) edges.computeIfAbsent(faceEdges[i + 1], ignored -> new ArrayList<>());
        }
        for (int i = 0; i < samples.length; i += 4) {
            var points = edges.get((int) samples[i]);
            if (points == null) continue;
            var xyz = local.apply(samples[i + 1], samples[i + 2], samples[i + 3]);
            points.add(new Vec(xyz[0], xyz[1]));
        }
        // OCCT traversal indices are local to this build. Name parallel edges by their order
        // across the plane so resizing a plate preserves its left/right and top/bottom references.
        var groups = new TreeMap<String, List<Entity>>();
        for (var points : edges.values()) {
            var curve = curve(points);
            if (curve != null) {
                groups.computeIfAbsent(family(curve), ignored -> new ArrayList<>())
                        .add(curve);
            }
        }
        var result = new ArrayList<Entity>();
        groups.forEach((key, group) -> {
            group.sort(Comparator.comparingDouble(FaceProjection::position)
                    .thenComparingDouble(e -> e.at(.5).x())
                    .thenComparingDouble(e -> e.at(.5).y())
                    .thenComparingDouble(e -> e.value(2)));
            for (int i = 0; i < group.size(); i++) {
                var e = group.get(i);
                result.add(new Entity(
                        PREFIX + key + "-" + group.size() + "-" + i,
                        e.kind(),
                        e.values(),
                        true,
                        e.degree(),
                        e.closed(),
                        e.text()));
            }
        });
        return List.copyOf(result);
    }

    private static String family(Entity e) {
        if (e.kind() != Kind.LINE) return e.kind().name().toLowerCase(Locale.ROOT);
        var d = e.at(1).minus(e.at(0));
        return "line-" + Math.round(Math.atan2(d.y(), d.x()) * 1e7);
    }

    private static double position(Entity e) {
        if (e.kind() == Kind.LINE) return e.at(1).minus(e.at(0)).unit().normal().dot(e.at(.5));
        return e.value(0);
    }

    private static Entity curve(List<Vec> points) {
        if (points.size() < 3) return null;
        var a = points.getFirst();
        if (points.stream().allMatch(p -> a.distance(p) < TOLERANCE)) return null;
        var b = points.getLast();
        var d = b.minus(a);
        if (d.length() > TOLERANCE
                && points.stream().allMatch(p -> Math.abs(d.unit().cross(p.minus(a))) < TOLERANCE)) {
            boolean reverse = Math.abs(d.x()) < TOLERANCE ? d.y() < 0 : d.x() < 0;
            var start = reverse ? b : a;
            var end = reverse ? a : b;
            return Sketches.entity("edge", Kind.LINE, true, start.x(), start.y(), end.x(), end.y());
        }
        var u = points.get(points.size() / 4).minus(a);
        var v = points.get(points.size() / 2).minus(a);
        double cross = u.cross(v);
        if (Math.abs(cross) > 1e-14) {
            var center = a.plus(
                    u.normal().times(v.dot(v)).minus(v.normal().times(u.dot(u))).times(.5 / cross));
            double radius = center.distance(a);
            if (points.stream().allMatch(p -> Math.abs(center.distance(p) - radius) < TOLERANCE)) {
                if (a.distance(b) < TOLERANCE) {
                    return Sketches.entity("edge", Kind.CIRCLE, true, center.x(), center.y(), radius);
                } else {
                    var first = a.minus(center);
                    double sweep = 0;
                    for (int i = 1; i < points.size(); i++) {
                        var before = points.get(i - 1).minus(center);
                        var after = points.get(i).minus(center);
                        sweep += Math.atan2(before.cross(after), before.dot(after));
                    }
                    return Sketches.entity(
                            "edge",
                            Kind.ARC,
                            true,
                            center.x(),
                            center.y(),
                            radius,
                            Math.atan2(first.y(), first.x()),
                            sweep);
                }
            }
        }
        boolean closed = a.distance(b) < TOLERANCE;
        var values = new ArrayList<Double>();
        points.stream().limit(closed ? points.size() - 1 : points.size()).forEach(point -> {
            values.add(point.x());
            values.add(point.y());
        });
        return new Entity("edge", Kind.FIT_SPLINE, values, true, 0, closed, null);
    }

    /** Preserve saved reference names when adding or removing unrelated edges renumbers a family. */
    public static List<Entity> retainReferenceIds(SketchGeometry geometry, List<Entity> projected) {
        var byId = new HashMap<String, Entity>();
        projected.forEach(edge -> byId.put(edge.id(), edge));
        var references = geometry.entities().stream()
                .filter(edge -> reference(edge.id()))
                .toList();
        var reserved = new HashSet<String>();
        references.forEach(edge -> reserved.add(edge.id()));
        var renamed = new HashMap<String, Entity>();
        for (var saved : references) {
            if (byId.containsKey(saved.id())) continue;
            var matches = projected.stream()
                    .filter(edge -> !reserved.contains(edge.id()) && sameCurve(saved, edge))
                    .limit(2)
                    .toList();
            if (matches.size() != 1) continue;
            var edge = matches.getFirst();
            if (renamed.containsKey(edge.id())) {
                throw new IllegalArgumentException("Ambiguous supporting face edge: " + saved.id());
            }
            renamed.put(
                    edge.id(),
                    new Entity(
                            saved.id(), edge.kind(), edge.values(), true, edge.degree(), edge.closed(), edge.text()));
        }
        return renamed.isEmpty()
                ? projected
                : projected.stream()
                        .map(edge -> renamed.getOrDefault(edge.id(), edge))
                        .toList();
    }

    private static boolean sameCurve(Entity first, Entity second) {
        if (first.kind() != second.kind()
                || first.degree() != second.degree()
                || first.closed() != second.closed()
                || first.values().size() != second.values().size()) return false;
        for (int i = 0; i < first.values().size(); i++) {
            if (Math.abs(first.value(i) - second.value(i)) > TOLERANCE) return false;
        }
        return true;
    }

    public static SketchGeometry refresh(SketchGeometry geometry, List<Entity> projected) {
        if (geometry.entities().stream().noneMatch(e -> reference(e.id()))) return geometry;
        var byId = new HashMap<String, Entity>();
        projected.forEach(e -> byId.put(e.id(), e));
        var entities = new ArrayList<Entity>();
        for (var entity : geometry.entities()) {
            var replacement = reference(entity.id()) ? byId.get(entity.id()) : entity;
            if (replacement == null) {
                throw new IllegalArgumentException("Supporting face edge is missing: " + entity.id());
            }
            entities.add(replacement);
        }
        return entities.equals(geometry.entities()) ? geometry : geometry.withEntities(entities);
    }

    /** Store only reference geometry actually needed by a driving constraint or text path. */
    public static SketchGeometry usedReferences(SketchGeometry geometry) {
        if (geometry.entities().stream().noneMatch(e -> reference(e.id()))) return geometry;
        var used = new HashSet<String>();
        geometry.constraints().forEach(c -> c.refs().forEach(r -> used.add(r.entityId())));
        geometry.entities().stream()
                .filter(e -> e.kind() == Kind.TEXT)
                .forEach(e -> used.add(e.text().pathId()));
        var entities = geometry.entities().stream()
                .filter(e -> !reference(e.id()) || used.contains(e.id()))
                .toList();
        return geometry.entities().size() == entities.size() ? geometry : geometry.withEntities(entities);
    }
}
