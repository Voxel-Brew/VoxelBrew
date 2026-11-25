package dev.voxelbrew.engine.model;

import static dev.voxelbrew.engine.model.Evaluation.State.*;

import dev.voxelbrew.api.model.CadDocument;
import dev.voxelbrew.api.model.ConstructionFeature;
import dev.voxelbrew.api.model.Feature;
import dev.voxelbrew.api.model.ModelException;
import dev.voxelbrew.api.model.Transform;
import dev.voxelbrew.api.model.sketch.SketchGeometry.Entity;
import dev.voxelbrew.engine.feature.FeatureRegistry;
import dev.voxelbrew.engine.geometry.Solid;
import dev.voxelbrew.engine.sketch.FaceProjection;
import dev.voxelbrew.engine.sketch.SketchSolver;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Deterministic full replay. Only earlier, named outputs can be referenced. */
@lombok.extern.slf4j.Slf4j
public final class Evaluator {
    private static final class ReplayGeometry {
        private final List<Solid> owned = new ArrayList<>();
        private final Map<String, Feature.Sketch> sketches = new LinkedHashMap<>();
        private final Map<String, Feature.Plane> planes = new LinkedHashMap<>();
        private final Map<String, List<Entity>> projections = new LinkedHashMap<>();
        private final Map<Feature.BodyRef, Evaluation.Body> outputs = new LinkedHashMap<>();
        private final Map<String, Evaluation.Body> active = new LinkedHashMap<>();
        private final Map<String, Evaluation.PatternCopy> patternCopies = new LinkedHashMap<>();
        private final Map<String, Integer> copyCounts = new LinkedHashMap<>();
        private final Map<String, dev.voxelbrew.api.model.construction.ConstructionGeometry.Datum> construction =
                new LinkedHashMap<>();

        private void rebuild(
                Feature feature, int index, List<Feature> resolved, Function<Feature.Custom, Solid> customEvaluator) {
            switch (feature) {
                case ConstructionFeature definition -> rebuildConstructionFeature(definition);
                case Feature.Sketch s -> rebuildSketch(s, index, resolved);
                case Feature.Custom custom
                when BodyExtrusion.modifiesBody(custom) -> rebuildExtrude(BodyExtrusion.from(custom));
                case Feature.Custom custom
                when BodyPattern.matches(custom) -> rebuildPattern(custom.id(), BodyPattern.from(custom));
                case Feature.Custom custom when CutTargets.through(custom) -> rebuildThroughCut(custom);
                case Feature.Custom custom -> rebuildCustom(custom, customEvaluator);
                case Feature.Extrude e -> rebuildExtrude(BodyExtrusion.from(e));
                case Feature.EdgeFinish finish -> rebuildEdgeFinish(finish);
                case Feature.Shell shell -> rebuildShell(shell);
                case Feature.SplitBody split -> rebuildSplitBody(split);
                case Feature.ThroughCut cut -> rebuildThroughCut(cut);
                default -> {}
            }
        }

        private void rebuildConstructionFeature(ConstructionFeature definition) {
            construction.put(
                    definition.id(),
                    dev.voxelbrew.engine.construction.ConstructionEvaluator.evaluate(
                            definition,
                            construction,
                            ref -> outputs.containsKey(ref) ? outputs.get(ref).solid() : null));
        }

        private Feature resolve(Feature feature, CadDocument document, Map<String, javax.measure.Quantity<?>> values) {
            var projected = projectSupport(feature);
            var resolved = dev.voxelbrew.engine.parameters.FeatureDimensions.resolve(
                    projected, document.features(), document.parameters(), values);
            if (feature instanceof Feature.Sketch old
                    && projected instanceof Feature.Sketch next
                    && old.profile() != next.profile()
                    && resolved instanceof Feature.Sketch sketch
                    && sketch.profile() instanceof Feature.Constrained c) {
                return new Feature.Sketch(
                        sketch.id(),
                        sketch.profileId(),
                        sketch.plane(),
                        new Feature.Constrained(SketchSolver.solve(c.geometry()).requireSolved()));
            }
            return resolved;
        }

        private Feature projectSupport(Feature feature) {
            if (!(feature instanceof Feature.Sketch s) || s.plane().support() == null) return feature;
            var support = s.plane().support();
            var source = outputs.get(support.body());
            if (source == null) throw new ModelException(s.id(), "Missing supporting body output " + support.body());
            var solid = source.solid();
            var faces = solid.planarFaces();
            var plane = new Feature.Plane(
                    s.plane().id(),
                    s.plane().offsetZ(),
                    support.resolve(faces, s.plane().frame()),
                    support);
            var edges = FaceProjection.edges(plane, faces, solid.faceEdges(), solid.edgeSamples());
            var profile = s.profile();
            if (profile instanceof Feature.Constrained c) {
                edges = FaceProjection.retainReferenceIds(c.geometry(), edges);
            }
            projections.put(s.id(), edges);
            if (profile instanceof Feature.Constrained c) {
                var next = FaceProjection.refresh(c.geometry(), edges);
                if (next != c.geometry()) profile = new Feature.Constrained(next);
            }
            return new Feature.Sketch(s.id(), s.profileId(), plane, profile);
        }

        private void rebuildSketch(Feature.Sketch s, int index, List<Feature> resolved) {
            if (!s.plane().constructionSupport().isEmpty()) {
                var datum = construction.get(s.plane().constructionSupport());
                if (!(datum instanceof dev.voxelbrew.api.model.construction.ConstructionGeometry.Plane plane)) {
                    throw new ModelException(s.id(), "Supporting construction output is not a plane");
                }
                s = new Feature.Sketch(
                        s.id(),
                        s.profileId(),
                        new Feature.Plane(
                                s.plane().id(),
                                s.plane().offsetZ(),
                                plane.frame(),
                                null,
                                s.plane().constructionSupport()),
                        s.profile());
                resolved.set(index, s);
            }
            var earlier = planes.get(s.plane().id());
            if (earlier != null && !earlier.equals(s.plane())) {
                throw new ModelException(
                        s.id(), "conflicting definition of plane '" + s.plane().id() + "'");
            }
            planes.put(s.plane().id(), s.plane());
            sketches.put(s.id(), s);
        }

        private void rebuildCustom(Feature.Custom custom, Function<Feature.Custom, Solid> customEvaluator) {
            Solid solid = customEvaluator.apply(custom);
            owned.add(solid);
            var body = new Evaluation.Body(custom.id() + ".body", new Feature.BodyRef(custom.id(), "body"), solid);
            outputs.put(body.output(), body);
            active.put(body.id(), body);
        }

        private void rebuildExtrude(BodyExtrusion definition) {
            var e = definition.extrusion();
            Feature.Sketch sketch = profile(e.id(), e.profile(), sketches);
            Solid solid;
            var additionalBodies = new ArrayList<Evaluation.Body>();
            if (sketch.profile() instanceof Feature.Rectangle r) {
                solid = place(
                        Solid.rectanglePrism(
                                r.x(), r.y(), sketch.plane().offsetZ(), r.width(), r.height(), e.distance()),
                        sketch.plane().frame());
            } else if (sketch.profile() instanceof Feature.Circle c) {
                solid = place(
                        Solid.circlePrism(
                                c.centerX(), c.centerY(), sketch.plane().offsetZ(), c.diameter(), e.distance()),
                        sketch.plane().frame());
            } else if (sketch.profile() instanceof Feature.Constrained) {
                var regions = dev.voxelbrew.engine.sketch.SketchProfiles.referencedRegions(
                        sketch, e.profile().profileId());
                solid = place(
                        Solid.profilePrism(
                                regions.getFirst().profile(), sketch.plane().offsetZ(), e.distance()),
                        sketch.plane().frame());
                owned.add(solid);
                for (int region = 1; region < regions.size(); region++) {
                    var r = regions.get(region);
                    var extra = place(
                            Solid.profilePrism(r.profile(), sketch.plane().offsetZ(), e.distance()),
                            sketch.plane().frame());
                    owned.add(extra);
                    String name = "body-" + r.id();
                    var body = new Evaluation.Body(e.id() + "." + name, new Feature.BodyRef(e.id(), name), extra);
                    additionalBodies.add(body);
                }
            } else {
                throw new ModelException(e.id(), "unsupported extrusion profile");
            }
            if (!owned.contains(solid)) owned.add(solid);
            if (definition.target() != null) {
                var tools = new ArrayList<Solid>();
                tools.add(solid);
                additionalBodies.forEach(body -> tools.add(body.solid()));
                modifyTargets(
                        e.id(),
                        definition.targets(),
                        input -> input.combine(tools, definition.operation() == BodyExtrusion.Operation.CUT));
                return;
            }
            var body = new Evaluation.Body(e.id() + ".body", new Feature.BodyRef(e.id(), "body"), solid);
            outputs.put(body.output(), body);
            active.put(body.id(), body);
            for (var additional : additionalBodies) {
                outputs.put(additional.output(), additional);
                active.put(additional.id(), additional);
            }
        }

        private void rebuildPattern(String id, BodyPattern pattern) {
            var source = outputs.get(pattern.source());
            if (source == null || active.get(source.id()) != source) {
                throw new ModelException(id, "Pattern source is missing or superseded");
            }
            var copies = new ArrayList<Evaluation.Body>();
            for (int j = 0; j < pattern.secondCount(); j++) {
                for (int i = 0; i < pattern.firstCount(); i++) {
                    if (i == 0 && j == 0) continue;
                    var solid = pattern.copy(source.solid(), i, j);
                    owned.add(solid);
                    var name = "copy-" + i + "-" + j;
                    copies.add(new Evaluation.Body(id + "." + name, new Feature.BodyRef(id, name), solid));
                }
            }
            int sourceNumber = new ArrayList<>(active.keySet()).indexOf(source.id()) + 1;
            for (var body : copies) {
                int index = 1 + copyCounts.merge(source.id(), 1, Integer::sum);
                patternCopies.put(body.id(), new Evaluation.PatternCopy(source.id(), sourceNumber, index));
                outputs.put(body.output(), body);
                active.put(body.id(), body);
            }
        }

        private void rebuildEdgeFinish(Feature.EdgeFinish finish) {
            Evaluation.Body input = outputs.get(finish.body());
            if (input == null) {
                throw new ModelException(finish.id(), "missing body output " + finish.body());
            }
            if (active.get(input.id()) != input) {
                throw new ModelException(finish.id(), "body input has been superseded by a later feature");
            }
            Solid solid = input.solid()
                    .edgeFinish(
                            finish.kind() == Feature.EdgeFinish.Kind.FILLET,
                            finish.size(),
                            finish.edges().stream().mapToInt(Integer::intValue).toArray());
            owned.add(solid);
            var body = new Evaluation.Body(input.id(), new Feature.BodyRef(finish.id(), "body"), solid);
            outputs.put(body.output(), body);
            active.put(body.id(), body);
        }

        private void rebuildShell(Feature.Shell shell) {
            Evaluation.Body input = outputs.get(shell.body());
            if (input == null) throw new ModelException(shell.id(), "missing body output " + shell.body());
            if (active.get(input.id()) != input) {
                throw new ModelException(shell.id(), "body input has been superseded by a later feature");
            }
            Solid solid = input.solid().shell(shell.thickness(), shell.removedFace());
            owned.add(solid);
            var body = new Evaluation.Body(input.id(), new Feature.BodyRef(shell.id(), "body"), solid);
            outputs.put(body.output(), body);
            active.put(body.id(), body);
        }

        private void rebuildSplitBody(Feature.SplitBody split) {
            Evaluation.Body input = outputs.get(split.body());
            if (input == null) throw new ModelException(split.id(), "missing body output " + split.body());
            if (active.get(input.id()) != input) {
                throw new ModelException(split.id(), "body input has been superseded by a later feature");
            }
            var p = split.plane();
            var parts = input.solid().split(p.normalX(), p.normalY(), p.normalZ(), p.offset());
            owned.add(parts.negative());
            owned.add(parts.positive());
            active.remove(input.id());
            for (var side : List.of("negative", "positive")) {
                var body = new Evaluation.Body(
                        split.id() + "." + side,
                        new Feature.BodyRef(split.id(), side),
                        side.equals("negative") ? parts.negative() : parts.positive());
                outputs.put(body.output(), body);
                active.put(body.id(), body);
            }
        }

        private void modifyTargets(String id, List<Feature.BodyRef> targets, Function<Solid, Solid> operation) {
            var results = new ArrayList<Evaluation.Body>();
            for (var target : targets) {
                var input = outputs.get(target);
                if (input == null) throw new ModelException(id, "missing body output " + target);
                if (active.get(input.id()) != input) {
                    throw new ModelException(id, "body input has been superseded: " + target);
                }
                var solid = operation.apply(input.solid());
                if (!owned.contains(solid)) owned.add(solid);
                var output = new Feature.BodyRef(id, CutTargets.output(results.size()));
                results.add(new Evaluation.Body(input.id(), output, solid));
            }
            for (var result : results) {
                outputs.put(result.output(), result);
                active.put(result.id(), result);
            }
        }

        private void rebuildThroughCut(Feature feature) {
            var cut = CutTargets.firstThroughCut(feature);
            Feature.Sketch sketch = profile(cut.id(), cut.profile(), sketches);
            if (!(sketch.profile() instanceof Feature.Circle) && !(sketch.profile() instanceof Feature.Constrained)) {
                throw new ModelException(cut.id(), "through-all cut requires a circle profile");
            }
            modifyTargets(
                    cut.id(), CutTargets.throughTargets(feature).bodies(), input -> throughSolid(sketch, cut, input));
        }

        private Solid throughSolid(Feature.Sketch sketch, Feature.ThroughCut cut, Solid input) {
            // Evaluate through-all in sketch coordinates, then restore model
            // coordinates.
            var frame = sketch.plane().frame();
            Solid local = input;
            if (!frame.equals(Transform.IDENTITY)) {
                local = local.transformed(frame.inverse());
                owned.add(local);
            }
            Solid solid;
            if (sketch.profile() instanceof Feature.Circle circle) {
                solid = local.cutThroughCircle(circle.centerX(), circle.centerY(), circle.diameter());
            } else {
                solid = local;
                for (var region : dev.voxelbrew.engine.sketch.SketchProfiles.referencedRegions(
                        sketch, cut.profile().profileId())) {
                    solid = solid.cutThroughProfile(region.profile());
                    owned.add(solid);
                }
            }
            if (!frame.equals(Transform.IDENTITY)) {
                if (!owned.contains(solid)) owned.add(solid);
                solid = solid.transformed(frame);
            }
            return solid;
        }
    }

    private static final class Blocked extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Blocked(String message) {
            super(message);
        }
    }

    public static Evaluation evaluate(CadDocument document) {
        return evaluate(document, FeatureRegistry.defaultDirectory());
    }

    /** The registry lives only on the evaluating thread; results contain engine-owned solids. */
    public static Evaluation evaluate(CadDocument document, Path pluginDirectory) {
        if (document.features().stream()
                .noneMatch(f -> f instanceof Feature.Custom
                        && !BodyExtrusion.modifiesBody(f)
                        && !BodyPattern.matches(f)
                        && !CutTargets.through(f))) {
            return replay(document, custom -> {
                throw new AssertionError("No custom features");
            });
        }
        Evaluation result = null;
        try (var registry = new FeatureRegistry(pluginDirectory)) {
            result = replay(document, registry::evaluate);
            return result;
        } catch (IOException failure) {
            log.warn("Cannot load feature plugins from {}", pluginDirectory, failure);
            if (result != null) result.close();
            return replay(document, custom -> {
                throw new ModelException(custom.id(), "Cannot load feature plugins: " + failure.getMessage(), failure);
            });
        }
    }

    private static Evaluation replay(CadDocument document, Function<Feature.Custom, Solid> customEvaluator) {
        long started = System.nanoTime();
        log.debug(
                "Rebuilding document {}: {} features, cursor {}",
                document.id(),
                document.features().size(),
                document.cursor());
        var diagnostics = new ArrayList<Evaluation.Diagnostic>();
        var geometry = new ReplayGeometry();
        Map<String, Evaluation.State> states = new LinkedHashMap<>();
        var resolved = new ArrayList<>(document.features());
        Map<String, javax.measure.Quantity<?>> parameterValues;
        try {
            parameterValues = document.parameters().values();
            for (var binding : document.parameters().bindings()) {
                if (document.features().stream()
                        .noneMatch(f -> f.id().equals(binding.target().featureId()))) {
                    throw new IllegalArgumentException("Binding refers to missing feature " + binding.target());
                }
            }
        } catch (IllegalArgumentException failure) {
            log.debug("Parameter evaluation failed for document {}", document.id(), failure);
            return new Evaluation(
                    document,
                    List.of(new Evaluation.Diagnostic("parameters", FAILED, failure.getMessage())),
                    List.of(),
                    List.of());
        }
        try {
            for (int index = 0; index < document.features().size(); index++) {
                Feature feature = document.features().get(index);
                if (index >= document.cursor()) {
                    diagnostics.add(new Evaluation.Diagnostic(feature.id(), ROLLED_BACK, "after timeline cursor"));
                    continue;
                }
                try {
                    for (String input : references(feature)) {
                        if (!states.containsKey(input)) {
                            throw new ModelException(feature.id(), "missing or non-earlier input '" + input + "'");
                        }
                        if (states.get(input) != OK) {
                            throw new Blocked("input '" + input + "' did not rebuild");
                        }
                    }
                    feature = geometry.resolve(feature, document, parameterValues);
                    resolved.set(index, feature);
                    FeatureValidation.validate(feature);
                    geometry.rebuild(feature, index, resolved, customEvaluator);
                    states.put(feature.id(), OK);
                    diagnostics.add(new Evaluation.Diagnostic(feature.id(), OK, "rebuilt"));
                } catch (Blocked failure) {
                    log.debug("Feature {} blocked: {}", feature.id(), failure.getMessage());
                    states.put(feature.id(), BLOCKED);
                    diagnostics.add(new Evaluation.Diagnostic(feature.id(), BLOCKED, failure.getMessage()));
                } catch (RuntimeException failure) {
                    log.debug("Feature {} failed in document {}", feature.id(), document.id(), failure);
                    states.put(feature.id(), FAILED);
                    diagnostics.add(new Evaluation.Diagnostic(feature.id(), FAILED, failure.getMessage()));
                }
            }
            if (log.isDebugEnabled()) {
                log.debug(
                        "Rebuilt document {} in {} ms: {} active bodies, {} failed features",
                        document.id(),
                        (System.nanoTime() - started) / 1_000_000,
                        geometry.active.size(),
                        diagnostics.stream().filter(d -> d.state() == FAILED).count());
            }
            var result = new Evaluation(
                    document, diagnostics, new ArrayList<>(geometry.active.values()), geometry.owned, resolved);
            result.construction(geometry.construction);
            result.projections(geometry.projections);
            result.indexBodyOutputs(geometry.outputs);
            result.patternCopies(geometry.patternCopies);
            return result;
        } catch (Throwable failure) {
            geometry.owned.forEach(Solid::close);
            throw failure;
        }
    }

    private static Feature.Sketch profile(String owner, Feature.ProfileRef ref, Map<String, Feature.Sketch> sketches) {
        Feature.Sketch sketch = sketches.get(ref.sketchId());
        if (sketch != null && sketch.profile() instanceof Feature.Constrained) {
            dev.voxelbrew.engine.sketch.SketchProfiles.referencedRegions(sketch, ref.profileId());
            return sketch;
        }
        if (sketch == null || !sketch.profileId().equals(ref.profileId())) {
            throw new ModelException(owner, "missing sketch/profile '" + ref.sketchId() + "/" + ref.profileId() + "'");
        }
        return sketch;
    }

    private static Solid place(Solid local, Transform frame) {
        if (frame.equals(Transform.IDENTITY)) return local;
        try (local) {
            return local.transformed(frame);
        }
    }

    private static List<String> cutReferences(Feature.ProfileRef profile, List<Feature.BodyRef> targets) {
        return java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(profile.sketchId()),
                        targets.stream().map(Feature.BodyRef::featureId))
                .distinct()
                .toList();
    }

    private static List<String> references(Feature feature) {
        if (feature instanceof ConstructionFeature construction) return construction.dependencies();
        if (feature instanceof Feature.Sketch s
                && !s.plane().constructionSupport().isEmpty()) {
            return List.of(s.plane().constructionSupport());
        }
        if (feature instanceof Feature.Sketch s && s.plane().support() != null) {
            return List.of(s.plane().support().body().featureId());
        }
        if (feature instanceof Feature.Extrude e) {
            return List.of(e.profile().sketchId());
        }
        if (BodyExtrusion.modifiesBody(feature)) {
            var definition = BodyExtrusion.from(feature);
            return cutReferences(definition.extrusion().profile(), definition.targets());
        }
        if (CutTargets.through(feature)) {
            return cutReferences(
                    CutTargets.firstThroughCut(feature).profile(),
                    CutTargets.throughTargets(feature).bodies());
        }
        if (BodyPattern.matches(feature)) {
            return List.of(BodyPattern.from(feature).source().featureId());
        }
        if (feature instanceof Feature.SplitBody split) {
            return List.of(split.body().featureId());
        }
        if (feature instanceof Feature.EdgeFinish finish) {
            return List.of(finish.body().featureId());
        }
        if (feature instanceof Feature.Shell shell) return List.of(shell.body().featureId());
        return List.of();
    }

    private Evaluator() {}
}
