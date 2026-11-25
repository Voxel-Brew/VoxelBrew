package dev.voxelbrew.engine.model;

import dev.voxelbrew.api.model.ConstructionFeature;
import dev.voxelbrew.api.model.Feature;
import java.util.ArrayList;
import java.util.List;
import lombok.experimental.UtilityClass;

/** Direct inputs used by replay. An empty output name identifies a sketch or construction datum. */
@UtilityClass
class GeometryInputs {
    static List<Feature.BodyRef> of(Feature feature) {
        return switch (feature) {
            case ConstructionFeature c ->
                c.inputs().stream()
                        .flatMap(input -> switch (input.selection()) {
                            case FACE, EDGE, VERTEX ->
                                java.util.stream.Stream.of(new Feature.BodyRef(input.source(), input.output()));
                            case DATUM -> java.util.stream.Stream.of(definition(input.source()));
                            default -> java.util.stream.Stream.empty();
                        })
                        .toList();
            case Feature.Sketch s -> {
                var plane = s.plane();
                if (!plane.constructionSupport().isEmpty()) yield List.of(definition(plane.constructionSupport()));
                yield plane.support() == null
                        ? List.of()
                        : List.of(plane.support().body());
            }
            case Feature.Extrude e -> List.of(definition(e.profile().sketchId()));
            case Feature.Custom custom
            when BodyExtrusion.modifiesBody(custom) -> {
                var extrusion = BodyExtrusion.from(custom);
                yield targets(extrusion.extrusion().profile(), extrusion.targets());
            }
            case Feature.Custom custom
            when CutTargets.through(custom) ->
                targets(
                        CutTargets.firstThroughCut(custom).profile(),
                        CutTargets.throughTargets(custom).bodies());
            case Feature.ThroughCut cut -> targets(cut.profile(), List.of(cut.body()));
            case Feature.Custom custom
            when BodyPattern.matches(custom) -> List.of(BodyPattern.from(custom).source());
            case Feature.Custom ignored -> List.of();
            case Feature.SplitBody split -> List.of(split.body());
            case Feature.EdgeFinish finish -> List.of(finish.body());
            case Feature.Shell shell -> List.of(shell.body());
        };
    }

    private static Feature.BodyRef definition(String id) {
        return new Feature.BodyRef(id, "");
    }

    private static List<Feature.BodyRef> targets(Feature.ProfileRef profile, List<Feature.BodyRef> bodies) {
        var inputs = new ArrayList<Feature.BodyRef>(1 + bodies.size());
        inputs.add(definition(profile.sketchId()));
        inputs.addAll(bodies);
        return inputs;
    }
}
