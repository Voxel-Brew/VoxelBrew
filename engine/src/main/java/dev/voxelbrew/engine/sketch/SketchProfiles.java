package dev.voxelbrew.engine.sketch;

import static dev.voxelbrew.api.model.sketch.SketchGeometry.*;

import dev.voxelbrew.api.model.geometry.PlanarProfile;
import dev.voxelbrew.api.model.sketch.SketchCurves;
import dev.voxelbrew.api.model.sketch.SketchGeometry;
import java.util.*;

/**
 * Resolves selected curves into exact regions; screen samples only classify nesting/orientation.
 */
public final class SketchProfiles {
    public record Region(String id, PlanarProfile profile) {}

    private record Junction(String id, Vec position) {}

    private record Edge(Entity entity, boolean reversed) {
        Vec start() {
            return entity.at(reversed ? 1 : 0);
        }

        Vec end() {
            return entity.at(reversed ? 0 : 1);
        }

        Edge reverse() {
            return new Edge(entity, !reversed);
        }
    }

    public static PlanarProfile profile(SketchGeometry g) {
        var regions = regions(g);
        if (regions.size() != 1) {
            throw new IllegalArgumentException(
                    "Profile contains separate regions; choose one region or extrude them as separate bodies");
        }
        return regions.getFirst().profile();
    }

    private static int[] enclosingLoops(List<List<Edge>> loops, List<List<Vec>> polygons, double[] areas) {
        int[] parent = new int[loops.size()];
        Arrays.fill(parent, -1);
        for (int i = 0; i < loops.size(); i++) {
            if (!Double.isFinite(areas[i]) || Math.abs(areas[i]) < 1e-12) {
                throw new IllegalArgumentException("Profile has zero or invalid area");
            }
            double size = Double.POSITIVE_INFINITY;
            for (int j = 0; j < loops.size(); j++) {
                if (Math.abs(areas[j]) > Math.abs(areas[i])
                        && Math.abs(areas[j]) < size
                        && inside(loops.get(i).getFirst().start(), polygons.get(j))) {
                    parent[i] = j;
                    size = Math.abs(areas[j]);
                }
            }
        }
        return parent;
    }

    public static List<Region> regions(SketchGeometry g) {
        return regions(g, false);
    }

    /** Individual bounded faces, including faces separated by a shared sketch edge. */
    public static List<Region> selectableRegions(SketchGeometry g) {
        return regions(g, true);
    }

    /** Pick the innermost bounded face at a point in sketch coordinates. */
    public static Optional<String> regionAt(SketchGeometry geometry, Vec point) {
        double smallest = Double.POSITIVE_INFINITY;
        String picked = null;
        for (var loop : profileLoops(geometry, true)) {
            var polygon = samples(loop);
            double size = Math.abs(area(polygon));
            if (size < smallest && inside(point, polygon)) {
                smallest = size;
                picked = regionId(loop);
            }
        }
        return Optional.ofNullable(picked);
    }

    public static String regionProfileId(String profileId, Region region) {
        return profileId + "/region/" + region.id();
    }

    public static String selectedProfileId(String profileId, List<String> ids) {
        if (ids.isEmpty()) throw new IllegalArgumentException("Select at least one sketch region");
        if (ids.size() == 1) return profileId + "/region/" + ids.getFirst();
        return profileId + "/regions/" + String.join(".", new TreeSet<>(ids));
    }

    public static List<Region> referencedRegions(dev.voxelbrew.api.model.Feature.Sketch sketch, String profileId) {
        var geometry = ((dev.voxelbrew.api.model.Feature.Constrained) sketch.profile()).geometry();
        if (sketch.profileId().equals(profileId)) return regions(geometry);
        String prefix = sketch.profileId() + "/regions/";
        if (profileId.startsWith(prefix)) {
            var selected = new HashSet<>(
                    Arrays.asList(profileId.substring(prefix.length()).split("\\.", -1)));
            var loops = profileLoops(geometry, true);
            var polygons = loops.stream().map(SketchProfiles::samples).toList();
            var parent = enclosingLoops(
                    loops,
                    polygons,
                    polygons.stream().mapToDouble(SketchProfiles::area).toArray());
            var boundary = new LinkedHashSet<Entity>();
            for (int i = 0; i < loops.size(); i++) {
                if (!selected.remove(regionId(loops.get(i)))) continue;
                // Curves shared by two selected faces are interior to their union.
                for (int j = 0; j < loops.size(); j++) {
                    if (j == i || parent[j] == i) {
                        for (var edge : loops.get(j)) {
                            if (!boundary.add(edge.entity())) boundary.remove(edge.entity());
                        }
                    }
                }
            }
            if (!selected.isEmpty()) throw new IllegalArgumentException("Selected sketch region no longer exists");
            return buildRegions(boundaryLoops(List.copyOf(boundary), true), false, true).stream()
                    .sorted(Comparator.comparing(Region::id))
                    .toList();
        }
        return List.of(selectableRegions(geometry).stream()
                .filter(region -> regionProfileId(sketch.profileId(), region).equals(profileId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Selected sketch region no longer exists")));
    }

    /** Sampled rings of the whole extrusion profile, for even-odd filling on the sketch plane. */
    public static List<List<Vec>> profileContours(SketchGeometry geometry) {
        return profileLoops(geometry, false).stream()
                .map(SketchProfiles::samples)
                .toList();
    }

    private static List<List<Edge>> profileLoops(SketchGeometry g, boolean individual) {
        SketchConstraints.requireSatisfied(g);
        if (g.boundary().isEmpty()) {
            throw new IllegalArgumentException("Select closed profile geometry in Edit sketch before extruding");
        }
        var boundary = g.boundary().stream().map(g::entity).toList();
        var endpoints = new ArrayList<Junction>();
        for (var entity : boundary) {
            if (entity.kind() == Kind.TEXT || entity.loop()) continue;
            endpoints.add(new Junction(entity.id(), entity.at(0)));
            endpoints.add(new Junction(entity.id(), entity.at(1)));
        }
        var remaining = new ArrayList<Entity>();
        for (var entity : boundary) {
            if (entity.kind() == Kind.LINE) remaining.addAll(splitAtJunctions(entity, endpoints));
            else remaining.addAll(g.curves(entity));
        }
        if (remaining.isEmpty()) throw new IllegalArgumentException("Profile has no curves");
        return boundaryLoops(remaining, individual);
    }

    /** Reports profile problems while the sketch can still be edited or saved as an open sketch. */
    public static String extrusionIssue(SketchGeometry g) {
        if (g.entities().isEmpty()) return "";
        try {
            var loops = profileLoops(g, false);
            var texts = g.boundary().stream()
                    .map(g::entity)
                    .filter(e -> e.kind() == Kind.TEXT)
                    .toList();
            if (texts.isEmpty()) return "";
            // Interior dividers are absent from the whole profile. Only test its actual boundary.
            var walls = new LinkedHashMap<Entity, String>();
            for (var loop : loops) {
                for (var edge : loop) {
                    var wall = edge.entity();
                    if (wall.kind() != Kind.LINE) continue;
                    for (var id : g.boundary()) {
                        var source = g.entity(id);
                        if (source.kind() == Kind.LINE
                                && onSegment(source, wall.at(0))
                                && onSegment(source, wall.at(1))) {
                            walls.put(wall, id);
                            break;
                        }
                    }
                }
            }
            for (var text : texts) {
                for (var curve : g.curves(text)) {
                    var points = curve.sample();
                    for (var wall : walls.entrySet()) {
                        if (crosses(points, wall.getKey())) {
                            return "Text '" + text.text().content() + "' (" + text.id() + ") crosses profile edge "
                                    + wall.getValue()
                                    + ". Move the text clear of the edge or exclude it from the profile.";
                        }
                    }
                }
            }
            return "";
        } catch (IllegalArgumentException invalid) {
            return invalid.getMessage();
        }
    }

    private static boolean onSegment(Entity line, Vec point) {
        var start = line.at(0);
        var direction = line.at(1).minus(start);
        var offset = point.minus(start);
        var unit = direction.unit();
        return Math.abs(offset.cross(unit)) <= TOLERANCE
                && offset.dot(unit) >= -TOLERANCE
                && offset.dot(unit) <= direction.length() + TOLERANCE;
    }

    private static boolean crosses(List<Vec> points, Entity line) {
        var start = line.at(0);
        var unit = line.at(1).minus(start).unit();
        var previous = points.getFirst();
        double side = previous.minus(start).cross(unit);
        for (int i = 1; i < points.size(); i++) {
            var point = points.get(i);
            double next = point.minus(start).cross(unit);
            if (side * next < 0 && onSegment(line, previous.mix(point, side / (side - next)))) return true;
            previous = point;
            side = next;
        }
        return false;
    }

    private static List<Entity> splitAtJunctions(Entity line, List<Junction> endpoints) {
        var start = line.at(0);
        var end = line.at(1);
        var delta = end.minus(start);
        double length = delta.length();
        var unit = delta.unit();
        var cuts = new TreeMap<Double, String>();
        for (var endpoint : endpoints) {
            var offset = endpoint.position().minus(start);
            double along = offset.dot(unit);
            if (along <= TOLERANCE || along >= length - TOLERANCE || Math.abs(offset.cross(unit)) > TOLERANCE) continue;
            cuts.merge(along, endpoint.id(), (a, b) -> a.compareTo(b) < 0 ? a : b);
        }
        if (cuts.isEmpty()) return List.of(line);
        // Name each span by its bounding junctions so unrelated cuts do not renumber it.
        boolean ascending = Math.abs(delta.x()) > Math.abs(delta.y()) ? delta.x() > 0 : delta.y() > 0;
        var previous = start;
        String previousKey = ascending ? "@low" : "@high";
        var spans = new ArrayList<Entity>();
        cuts.put(length, ascending ? "@high" : "@low");
        for (var cut : cuts.entrySet()) {
            var point = cut.getKey() == length ? end : start.plus(unit.times(cut.getKey()));
            if (point.distance(previous) <= TOLERANCE) continue;
            String key = cut.getValue();
            String pair = previousKey.compareTo(key) < 0 ? previousKey + ":" + key : key + ":" + previousKey;
            String id = UUID.nameUUIDFromBytes(
                            (line.id() + ":" + pair).getBytes(java.nio.charset.StandardCharsets.UTF_8))
                    .toString();
            spans.add(Sketches.entity(id, Kind.LINE, false, previous.x(), previous.y(), point.x(), point.y()));
            previous = point;
            previousKey = key;
        }
        return spans;
    }

    private static List<Region> regions(SketchGeometry g, boolean individual) {
        return buildRegions(profileLoops(g, individual), individual, individual);
    }

    private static List<Region> buildRegions(List<List<Edge>> loops, boolean includeNested, boolean stableIds) {
        var polygons = loops.stream().map(SketchProfiles::samples).toList();
        var areas = polygons.stream().mapToDouble(SketchProfiles::area).toArray();
        int[] parent = enclosingLoops(loops, polygons, areas);
        var result = new ArrayList<Region>();
        for (int i = 0; i < loops.size(); i++) {
            int depth = 0;
            for (int p = parent[i]; p >= 0; p = parent[p]) depth++;
            if (!includeNested && depth % 2 != 0) continue;
            var wires = new ArrayList<List<PlanarProfile.Segment>>();
            wires.add(segments(loops.get(i), areas[i] < 0));
            for (int j = 0; j < loops.size(); j++) {
                if (parent[j] == i) {
                    wires.add(segments(loops.get(j), areas[j] > 0));
                }
            }
            String id = stableIds
                    ? regionId(loops.get(i))
                    : loops.get(i).getFirst().entity().id();
            result.add(new Region(id, new PlanarProfile(wires)));
        }
        return List.copyOf(result);
    }

    /** Walk faces on the left; clockwise walks bound the exterior of connected regions. */
    private static List<List<Edge>> boundaryLoops(List<Entity> entities, boolean individual) {
        var loops = new ArrayList<List<Edge>>();
        var directed = new ArrayList<Edge>();
        for (var entity : entities) {
            if (entity.loop()) loops.add(List.of(new Edge(entity, false)));
            else {
                directed.add(new Edge(entity, false));
                directed.add(new Edge(entity, true));
            }
        }
        omitIsolatedSegments(directed, loops);
        var next = new HashMap<Edge, Edge>();
        for (var edge : directed) {
            var outgoing = directed.stream()
                    .filter(e -> e.start().distance(edge.end()) <= TOLERANCE)
                    .sorted(Comparator.comparingDouble(SketchProfiles::angle))
                    .toList();
            if (outgoing.size() < 2) {
                throw new IllegalArgumentException(
                        "Open profile at " + edge.entity().id() + "; add Coincident constraints to close it");
            }
            int reverse = outgoing.indexOf(edge.reverse());
            next.put(edge, outgoing.get((reverse + outgoing.size() - 1) % outgoing.size()));
        }
        var visited = new HashSet<Edge>();
        for (var start : directed) {
            if (visited.contains(start)) continue;
            var path = new ArrayList<Edge>();
            var edge = start;
            do {
                if (!visited.add(edge)) {
                    throw new IllegalArgumentException(
                            "Ambiguous profile boundary at " + edge.entity().id());
                }
                path.add(edge);
                edge = next.get(edge);
            } while (!edge.equals(start));
            double signedArea = area(samples(path));
            if (Math.abs(signedArea) < 1e-12) throw new IllegalArgumentException("Profile has zero or invalid area");
            if (signedArea < 0 && path.stream().map(Edge::entity).distinct().count() != path.size()) {
                throw new IllegalArgumentException("Profile contains an open connecting edge");
            }
            if (individual ? signedArea > 0 : signedArea < 0) {
                if (path.stream().map(Edge::entity).distinct().count() != path.size()) {
                    throw new IllegalArgumentException("Profile contains an open connecting edge");
                }
                loops.add(path);
            }
        }
        loops.sort(
                Comparator.comparingInt(loop -> entities.indexOf(loop.getFirst().entity())));
        return loops;
    }

    private static void omitIsolatedSegments(List<Edge> directed, List<List<Edge>> loops) {
        // A standalone segment (for example a line joining two hole centers) bounds no face.
        // Keep edges attached to other curves so an incomplete contour is still diagnosed.
        var isolated = directed.stream()
                .filter(edge -> directed.stream()
                        .noneMatch(other -> !other.entity().equals(edge.entity())
                                && (other.start().distance(edge.start()) <= TOLERANCE
                                        || other.start().distance(edge.end()) <= TOLERANCE)))
                .toList();
        directed.removeAll(isolated);
        if (directed.isEmpty() && loops.isEmpty()) {
            throw new IllegalArgumentException("Open profile; select closed geometry before extruding");
        }
    }

    private static String regionId(List<Edge> loop) {
        // Length-prefix IDs so shared edges, entity ordering and direction cannot alias faces.
        String key = loop.stream()
                .map(e -> e.entity().id())
                .sorted()
                .map(id -> id.length() + ":" + id)
                .collect(java.util.stream.Collectors.joining());
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static double angle(Edge edge) {
        // Only ordering uses a near-endpoint sample; output retains the exact curve.
        var start = edge.start();
        var nearby = edge.entity().at(edge.reversed() ? 1 - 1e-5 : 1e-5);
        return Math.atan2(nearby.y() - start.y(), nearby.x() - start.x());
    }

    private static List<PlanarProfile.Segment> segments(List<Edge> loop, boolean reverse) {
        var edges = new ArrayList<>(loop);
        if (reverse) {
            Collections.reverse(edges);
            for (int i = 0; i < edges.size(); i++) edges.set(i, edges.get(i).reverse());
        }
        return edges.stream()
                .flatMap(e -> SketchCurves.segments(e.entity(), e.reversed()).stream())
                .toList();
    }

    public static boolean hasProfile(SketchGeometry g) {
        try {
            return !regions(g).isEmpty();
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static List<Vec> samples(List<Edge> loop) {
        var result = new ArrayList<Vec>();
        for (var edge : loop) {
            var points = new ArrayList<>(edge.entity().sample());
            if (edge.reversed()) Collections.reverse(points);
            result.addAll(points);
        }
        return result;
    }

    private static double area(List<Vec> polygon) {
        double sum = 0;
        for (int i = 0; i < polygon.size(); i++) sum += polygon.get(i).cross(polygon.get((i + 1) % polygon.size()));
        return sum / 2;
    }

    private static boolean inside(Vec p, List<Vec> polygon) {
        boolean result = false;
        for (int i = 0, j = polygon.size() - 1; i < polygon.size(); j = i++) {
            var a = polygon.get(i);
            var b = polygon.get(j);
            if ((a.y() > p.y()) != (b.y() > p.y())
                    && p.x() < (b.x() - a.x()) * (p.y() - a.y()) / (b.y() - a.y()) + a.x()) {
                result = !result;
            }
        }
        return result;
    }

    private SketchProfiles() {}
}
