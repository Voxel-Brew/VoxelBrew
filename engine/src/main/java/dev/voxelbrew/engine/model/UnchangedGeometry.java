package dev.voxelbrew.engine.model;

import dev.voxelbrew.api.model.Feature;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/** Compares two successfully resolved histories of the same document in dependency order. */
public final class UnchangedGeometry implements Predicate<Feature.BodyRef> {
    private final Set<String> features = new HashSet<>();
    private final Set<Feature.BodyRef> outputs = new HashSet<>();

    public static UnchangedGeometry between(List<Feature> previous, List<Feature> current) {
        var result = new UnchangedGeometry();
        var byId = new HashMap<String, Feature>();
        previous.forEach(feature -> byId.put(feature.id(), feature));
        for (var feature : current) {
            var old = byId.get(feature.id());
            if (old != null) result.compare(old, feature);
        }
        return result;
    }

    private static List<Feature> perTarget(Feature feature) {
        if (CutTargets.through(feature)) {
            var profile = CutTargets.firstThroughCut(feature).profile();
            return CutTargets.throughTargets(feature).bodies().stream()
                    .<Feature>map(target -> new Feature.ThroughCut(feature.id(), profile, target))
                    .toList();
        }
        if (BodyExtrusion.modifiesBody(feature)) {
            var e = BodyExtrusion.from(feature);
            return e.targets().stream()
                    .map(target -> new BodyExtrusion(e.extrusion(), e.operation(), target).feature())
                    .toList();
        }
        return List.of();
    }

    private UnchangedGeometry() {}

    private void compare(Feature old, Feature current) {
        // An external provider may read state outside the document.
        if (current instanceof Feature.Custom
                && !BodyExtrusion.modifiesBody(current)
                && !BodyPattern.matches(current)
                && !CutTargets.through(current)) return;
        if (current.equals(old) && GeometryInputs.of(current).stream().allMatch(this)) {
            features.add(current.id());
            return;
        }
        // Each target is cut separately. A change to its sibling does not affect its geometry.
        var next = perTarget(current);
        if (next.isEmpty()) return;
        var prior = perTarget(old);
        for (int i = 0; i < Math.min(next.size(), prior.size()); i++) {
            var slot = next.get(i);
            if (slot.equals(prior.get(i)) && GeometryInputs.of(slot).stream().allMatch(this)) {
                outputs.add(new Feature.BodyRef(current.id(), CutTargets.output(i)));
            }
        }
    }

    @Override
    public boolean test(Feature.BodyRef ref) {
        return features.contains(ref.featureId()) || outputs.contains(ref);
    }
}
