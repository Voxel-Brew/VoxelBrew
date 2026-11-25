package dev.voxelbrew.engine.model;

import dev.voxelbrew.api.model.Feature;
import dev.voxelbrew.engine.persistence.proto.DocumentProto;

/** Editable extrusion and optional boolean target, using the released model API's feature envelope. */
public record BodyExtrusion(
        Feature.Extrude extrusion,
        Operation operation,
        Feature.BodyRef target,
        java.util.List<Feature.BodyRef> additionalTargets) {
    public enum Operation {
        NEW_BODY,
        JOIN,
        CUT
    }

    public static boolean modifiesBody(Feature feature) {
        return feature instanceof Feature.Custom custom
                && custom.providerId().equals("dev.voxelbrew")
                && custom.type().equals("body-extrusion");
    }

    public static BodyExtrusion from(Feature feature) {
        if (feature instanceof Feature.Extrude e) return new BodyExtrusion(e, Operation.NEW_BODY, null);
        if (!modifiesBody(feature)) throw new IllegalArgumentException("Expected an extrusion");
        var custom = (Feature.Custom) feature;
        if (custom.payloadVersion() != 1 && custom.payloadVersion() != 2) {
            throw new IllegalArgumentException("Unsupported body extrusion version");
        }
        try {
            var data = DocumentProto.BodyExtrusion.parseFrom(custom.payload());
            var profile = data.getExtrusion().getProfile();
            return new BodyExtrusion(
                    new Feature.Extrude(
                            feature.id(),
                            new Feature.ProfileRef(profile.getSketchId(), profile.getProfileId()),
                            data.getExtrusion().getDistance()),
                    data.getSubtract() ? Operation.CUT : Operation.JOIN,
                    new Feature.BodyRef(
                            data.getTarget().getFeatureId(), data.getTarget().getOutputName()),
                    data.getAdditionalTargetsList().stream()
                            .map(ref -> new Feature.BodyRef(ref.getFeatureId(), ref.getOutputName()))
                            .toList());
        } catch (com.google.protobuf.InvalidProtocolBufferException failure) {
            throw new IllegalArgumentException("Invalid body extrusion", failure);
        }
    }

    public BodyExtrusion(Feature.Extrude extrusion, Operation operation, Feature.BodyRef target) {
        this(extrusion, operation, target, java.util.List.of());
    }

    public BodyExtrusion {
        additionalTargets = java.util.List.copyOf(additionalTargets);
        if (!additionalTargets.isEmpty() && operation != Operation.CUT) {
            throw new IllegalArgumentException("Only cuts accept multiple target bodies");
        }
        java.util.Objects.requireNonNull(extrusion);
        java.util.Objects.requireNonNull(operation);
        if (operation != Operation.NEW_BODY) {
            if (target == null) throw new IllegalArgumentException("Select a target body");
            CutTargets.validate(
                    java.util.stream.Stream.concat(java.util.stream.Stream.of(target), additionalTargets.stream())
                            .toList());
        }
    }

    public Feature feature() {
        if (operation == Operation.NEW_BODY) return extrusion;
        var ref = extrusion.profile();
        var data = DocumentProto.BodyExtrusion.newBuilder()
                .setExtrusion(DocumentProto.Extrude.newBuilder()
                        .setProfile(DocumentProto.ProfileRef.newBuilder()
                                .setSketchId(ref.sketchId())
                                .setProfileId(ref.profileId()))
                        .setDistance(extrusion.distance()))
                .setTarget(DocumentProto.BodyRef.newBuilder()
                        .setFeatureId(target.featureId())
                        .setOutputName(target.outputName()))
                .setSubtract(operation == Operation.CUT)
                .addAllAdditionalTargets(
                        additionalTargets.stream().map(CutTargets::encode).toList())
                .build();
        return new Feature.Custom(
                extrusion.id(),
                "dev.voxelbrew",
                "body-extrusion",
                additionalTargets.isEmpty() ? 1 : 2,
                data.toByteArray());
    }

    public Feature withDistance(double value) {
        return new BodyExtrusion(
                        new Feature.Extrude(extrusion.id(), extrusion.profile(), value),
                        operation,
                        target,
                        additionalTargets)
                .feature();
    }

    public java.util.List<Feature.BodyRef> targets() {
        if (target == null) return java.util.List.of();
        return java.util.stream.Stream.concat(java.util.stream.Stream.of(target), additionalTargets.stream())
                .toList();
    }
}
