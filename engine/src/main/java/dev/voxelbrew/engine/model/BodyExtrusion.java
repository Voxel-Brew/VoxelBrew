package dev.voxelbrew.engine.model;

import dev.voxelbrew.api.model.CadDocument;
import dev.voxelbrew.api.model.Feature;
import dev.voxelbrew.engine.persistence.proto.DocumentProto;

/** Editable extrusion and optional boolean target, using the released model API's feature envelope. */
public record BodyExtrusion(Feature.Extrude extrusion, Operation operation, Feature.BodyRef target) {
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
        if (custom.payloadVersion() != 1) throw new IllegalArgumentException("Unsupported body extrusion version");
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
                            data.getTarget().getFeatureId(), data.getTarget().getOutputName()));
        } catch (com.google.protobuf.InvalidProtocolBufferException failure) {
            throw new IllegalArgumentException("Invalid body extrusion", failure);
        }
    }

    public BodyExtrusion {
        java.util.Objects.requireNonNull(extrusion);
        java.util.Objects.requireNonNull(operation);
        if (operation != Operation.NEW_BODY) {
            if (target == null) throw new IllegalArgumentException("Select a target body");
            CadDocument.identifier(target.featureId(), "target feature");
            CadDocument.identifier(target.outputName(), "target output");
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
                .build();
        return new Feature.Custom(extrusion.id(), "dev.voxelbrew", "body-extrusion", 1, data.toByteArray());
    }

    public Feature withDistance(double value) {
        return new BodyExtrusion(new Feature.Extrude(extrusion.id(), extrusion.profile(), value), operation, target)
                .feature();
    }
}
