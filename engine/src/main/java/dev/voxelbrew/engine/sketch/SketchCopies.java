package dev.voxelbrew.engine.sketch;

import static dev.voxelbrew.api.model.sketch.SketchGeometry.*;

import dev.voxelbrew.api.model.sketch.SketchGeometry;
import dev.voxelbrew.api.model.sketch.SketchText;
import java.util.*;
import java.util.function.Supplier;

/** Creates and evaluates driven sketch instances in creation (dependency) order. */
public record SketchCopies(SketchGeometry source, Set<String> selected, Supplier<String> nextId) {
    private static final String LINK_PREFIX = "pattern-link-";

    private record Placement(Vec origin, Vec x, Vec y) {
        Vec vector(double a, double b) {
            return x.times(a).plus(y.times(b));
        }

        Entity copy(Entity entity, String id) {
            var values = new ArrayList<>(entity.values());
            int coordinates = switch (entity.kind()) {
                case ARC, ELLIPSE, CIRCLE, TEXT -> 2;
                case CONIC -> 6;
                default -> values.size();
            };
            for (int i = 0; i < coordinates; i += 2) {
                var p = origin.plus(vector(entity.value(i), entity.value(i + 1)));
                values.set(i, p.x());
                values.set(i + 1, p.y());
            }
            switch (entity.kind()) {
                case ARC -> {
                    values.set(3, angle(entity.value(3)));
                    if (x.cross(y) < 0) values.set(4, -entity.value(4));
                }
                case ELLIPSE -> values.set(4, angle(entity.value(4)));
                case TEXT -> values.set(3, angle(entity.value(3)));
                default -> {}
            }
            return new Entity(
                    id, entity.kind(), values, entity.construction(), entity.degree(), entity.closed(), entity.text());
        }

        double angle(double radians) {
            var v = vector(Math.cos(radians), Math.sin(radians));
            return Math.atan2(v.y(), v.x());
        }

        Entity instance(SketchGeometry geometry, Entity original, String id) {
            if (original.kind() != Kind.TEXT
                    || (x.cross(y) > 0 && original.text().pathId().isEmpty())) {
                return copy(original, id);
            }
            // A reflected or path-bound label may change its glyph count. Store the complete
            // transformed outline in one text instance so its ID survives content and font edits.
            var data = original.text();
            var anchor = SketchText.anchor(geometry, original);
            var placed = origin.plus(vector(anchor.x(), anchor.y()));
            var curves = geometry.curves(original).iterator();
            double height = original.value(2);
            var glyphs = new ArrayList<SketchText.Glyph>();
            for (var glyph : data.glyphs()) {
                var contours = new ArrayList<List<Entity>>();
                for (var contour : glyph.contours()) {
                    var transformed = new ArrayList<Entity>();
                    for (var segment : contour) {
                        var curve = curves.next();
                        var values = new ArrayList<>(curve.values());
                        for (int i = 0; i < values.size(); i += 2) {
                            var p = vector(curve.value(i) - anchor.x(), curve.value(i + 1) - anchor.y())
                                    .times(1000 / height);
                            values.set(i, p.x());
                            values.set(i + 1, p.y());
                        }
                        transformed.add(segment.withValues(values));
                    }
                    contours.add(transformed);
                }
                glyphs.add(new SketchText.Glyph(0, contours));
            }
            return new Entity(
                    id,
                    Kind.TEXT,
                    List.of(placed.x(), placed.y(), height, 0., 0.),
                    original.construction(),
                    0,
                    false,
                    new SketchText.Data(data.content(), data.font(), data.style(), "", glyphs));
        }
    }

    /** App-defined relative pose, encoded as PATTERN_LINK in the design file. */
    public static boolean linked(Constraint constraint) {
        return constraint.type() == Type.FIX
                && constraint.refs().size() >= 2
                && constraint.id().startsWith(LINK_PREFIX);
    }

    public static String originalId(SketchGeometry geometry, String id) {
        for (var c : geometry.constraints().reversed()) {
            if (linked(c) && c.refs().get(1).entityId().equals(id)) {
                id = c.refs().getFirst().entityId();
            }
        }
        return id;
    }

    static SketchGeometry refresh(SketchGeometry geometry, List<Constraint> links) {
        if (links.isEmpty()) return geometry;
        var boundary = new LinkedHashSet<>(geometry.boundary());
        for (var link : links) {
            var original = geometry.entity(link.refs().getFirst().entityId());
            String target = link.refs().get(1).entityId();
            var previous = geometry.entity(target);
            var v = link.values();
            if (v.size() != 6 || link.refs().size() > 3) {
                throw new IllegalArgumentException("Invalid sketch pattern link: " + link.id());
            }
            Placement placement;
            if (link.refs().size() == 3) {
                var axis = geometry.entity(link.refs().get(2).entityId());
                placement = reflection(axis.at(0), axis.at(1));
            } else {
                placement = new Placement(
                        new Vec(v.get(0), v.get(1)), new Vec(v.get(2), v.get(3)), new Vec(v.get(4), v.get(5)));
            }
            geometry = geometry.replace(placement.instance(geometry, original, target));
            if (original.construction()) {
                boundary.remove(target);
            } else if (previous.construction() && boundary.contains(original.id())) {
                boundary.add(target);
            }
        }
        return geometry.withBoundary(List.copyOf(boundary));
    }

    private static Placement reflection(Vec a, Vec b) {
        if (a.distance(b) < TOLERANCE) throw new IllegalArgumentException("sketch.copy.invalid");
        var axis = b.minus(a).unit();
        var x = axis.times(2 * axis.x()).minus(new Vec(1, 0));
        var y = axis.times(2 * axis.y()).minus(new Vec(0, 1));
        return new Placement(a.minus(x.times(a.x())).minus(y.times(a.y())), x, y);
    }

    public SketchCopies {
        selected = Set.copyOf(selected);
        if (selected.isEmpty()) throw new IllegalArgumentException("sketch.copy.select");
        selected.forEach(source::entity);
    }

    public SketchGeometry rectangular(int nx, int ny, double dx, double dy) {
        if (nx < 1 || ny < 1 || (nx == 1 && ny == 1) || (nx > 1 && dx == 0) || (ny > 1 && dy == 0)) {
            throw new IllegalArgumentException("sketch.copy.invalid");
        }
        checkCapacity((long) nx * ny - 1);
        var placements = new ArrayList<Placement>();
        for (int j = 0; j < ny; j++) {
            for (int i = 0; i < nx; i++) {
                if (i != 0 || j != 0) {
                    placements.add(new Placement(new Vec(i * dx, j * dy), new Vec(1, 0), new Vec(0, 1)));
                }
            }
        }
        return copies(placements);
    }

    public SketchGeometry circular(Vec center, int count, double sweepDegrees) {
        if (count < 2 || sweepDegrees == 0 || Math.abs(sweepDegrees) > 360) {
            throw new IllegalArgumentException("sketch.copy.invalid");
        }
        checkCapacity(count - 1);
        double step = Math.toRadians(sweepDegrees) / (Math.abs(sweepDegrees) == 360 ? count : count - 1);
        var placements = new ArrayList<Placement>();
        for (int i = 1; i < count; i++) {
            var x = new Vec(Math.cos(i * step), Math.sin(i * step));
            var y = x.normal();
            placements.add(new Placement(center.minus(x.times(center.x())).minus(y.times(center.y())), x, y));
        }
        return copies(placements);
    }

    public SketchGeometry mirror(Vec a, Vec b) {
        return copies(List.of(reflection(a, b)));
    }

    public SketchGeometry mirror(Entity axis) {
        if (axis.kind() != Kind.LINE) throw new IllegalArgumentException("sketch.copy.invalid");
        return copies(List.of(reflection(axis.at(0), axis.at(1))), axis);
    }

    private void checkCapacity(long copies) {
        if (copies > (64 - source.entities().size()) / selected.size()) {
            throw new IllegalArgumentException("sketch.copy.limit");
        }
    }

    private SketchGeometry copies(List<Placement> placements) {
        return copies(placements, null);
    }

    private SketchGeometry copies(List<Placement> placements, Entity axis) {
        var entities = new ArrayList<>(source.entities());
        var boundary = new ArrayList<>(source.boundary());
        var constraints = new ArrayList<>(source.constraints());
        if (axis != null && entities.stream().noneMatch(e -> e.id().equals(axis.id()))) entities.add(axis);
        for (var placement : placements) {
            for (var original : source.entities()) {
                if (!selected.contains(original.id())) continue;
                if (entities.size() >= 64) throw new IllegalArgumentException("sketch.copy.limit");
                String id = nextId.get();
                var instance = placement.instance(source, original, id);
                entities.add(instance);
                if (source.boundary().contains(original.id())) boundary.add(id);
                var refs = new ArrayList<>(List.of(original.ref(), instance.ref()));
                if (axis != null) refs.add(axis.ref());
                constraints.add(new Constraint(
                        LINK_PREFIX + id,
                        Type.FIX,
                        refs,
                        List.of(
                                placement.origin.x(),
                                placement.origin.y(),
                                placement.x.x(),
                                placement.x.y(),
                                placement.y.x(),
                                placement.y.y())));
            }
        }
        return new SketchGeometry(entities, constraints, boundary);
    }
}
