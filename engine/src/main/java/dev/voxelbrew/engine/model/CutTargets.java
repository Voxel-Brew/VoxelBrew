package dev.voxelbrew.engine.model;

import dev.voxelbrew.api.model.Feature;
import dev.voxelbrew.engine.persistence.proto.DocumentProto;
import java.util.*;
import java.util.stream.Stream;

/** Ordered target slots. The first output keeps the original single-body output name. */
public record CutTargets(List<Feature.BodyRef> bodies) {
    public static String output(int index) {
        return index == 0 ? "body" : "body-" + (index + 1);
    }

    static DocumentProto.BodyRef encode(Feature.BodyRef ref) {
        return DocumentProto.BodyRef.newBuilder()
                .setFeatureId(ref.featureId())
                .setOutputName(ref.outputName())
                .build();
    }

    static CutTargets decode(DocumentProto.BodyRef first, List<DocumentProto.BodyRef> additional) {
        return new CutTargets(Stream.concat(Stream.of(first), additional.stream())
                .map(ref -> new Feature.BodyRef(ref.getFeatureId(), ref.getOutputName()))
                .toList());
    }

    public static boolean through(Feature feature) {
        return feature instanceof Feature.ThroughCut
                || feature instanceof Feature.Custom custom
                        && custom.providerId().equals("dev.voxelbrew")
                        && custom.type().equals("multi-through-cut");
    }

    public static Feature.ThroughCut firstThroughCut(Feature feature) {
        if (feature instanceof Feature.ThroughCut cut) return cut;
        var data = throughData(feature).getFirst();
        return new Feature.ThroughCut(
                feature.id(),
                new Feature.ProfileRef(
                        data.getProfile().getSketchId(), data.getProfile().getProfileId()),
                new Feature.BodyRef(
                        data.getBody().getFeatureId(), data.getBody().getOutputName()));
    }

    public static CutTargets throughTargets(Feature feature) {
        if (feature instanceof Feature.ThroughCut cut) return new CutTargets(List.of(cut.body()));
        var data = throughData(feature);
        return decode(data.getFirst().getBody(), data.getAdditionalTargetsList());
    }

    private static DocumentProto.MultiThroughCut throughData(Feature feature) {
        var custom = (Feature.Custom) feature;
        if (!through(custom) || custom.payloadVersion() != 1) {
            throw new IllegalArgumentException("Unsupported through cut");
        }
        try {
            return DocumentProto.MultiThroughCut.parseFrom(custom.payload());
        } catch (com.google.protobuf.InvalidProtocolBufferException failure) {
            throw new IllegalArgumentException("Invalid through cut", failure);
        }
    }

    static void validate(List<Feature.BodyRef> bodies) {
        if (bodies.isEmpty() || new HashSet<>(bodies).size() != bodies.size()) {
            throw new IllegalArgumentException("Choose distinct target bodies");
        }
        for (var body : bodies) {
            dev.voxelbrew.api.model.CadDocument.identifier(body.featureId(), "target feature");
            if (body.outputName() == null || body.outputName().isBlank()) {
                throw new IllegalArgumentException("Choose a target output");
            }
        }
    }

    public CutTargets {
        bodies = List.copyOf(bodies);
        validate(bodies);
    }

    public Feature throughFeature(String id, Feature.ProfileRef profile) {
        if (bodies.size() == 1) return new Feature.ThroughCut(id, profile, bodies.getFirst());
        var first = DocumentProto.ThroughCut.newBuilder()
                .setProfile(DocumentProto.ProfileRef.newBuilder()
                        .setSketchId(profile.sketchId())
                        .setProfileId(profile.profileId()))
                .setBody(encode(bodies.getFirst()));
        var data = DocumentProto.MultiThroughCut.newBuilder()
                .setFirst(first)
                .addAllAdditionalTargets(encodedAdditional())
                .build();
        return new Feature.Custom(id, "dev.voxelbrew", "multi-through-cut", 1, data.toByteArray());
    }

    List<DocumentProto.BodyRef> encodedAdditional() {
        return bodies.stream().skip(1).map(CutTargets::encode).toList();
    }
}
