package dev.voxelbrew.engine.model;

import dev.voxelbrew.api.model.Feature;
import dev.voxelbrew.api.model.Transform;
import dev.voxelbrew.engine.geometry.Solid;
import dev.voxelbrew.engine.persistence.proto.DocumentProto;
import lombok.NonNull;

/** A non-destructive pattern of exact solids. Counts include the source at (0, 0). */
public record BodyPattern(
        @NonNull Kind kind,
        @NonNull Feature.BodyRef source,
        @NonNull Axis axis,
        int firstCount,
        int secondCount,
        double firstStep,
        double secondStep) {
    public enum Kind {
        RECTANGULAR,
        CIRCULAR,
        MIRROR
    }

    public enum Axis {
        X,
        Y,
        Z
    }

    public static boolean matches(Feature feature) {
        return feature instanceof Feature.Custom c
                && c.providerId().equals("dev.voxelbrew")
                && c.type().equals("body-pattern");
    }

    public static BodyPattern from(Feature feature) {
        if (!matches(feature) || ((Feature.Custom) feature).payloadVersion() != 1) {
            throw new IllegalArgumentException("Unsupported body pattern");
        }
        try {
            var data = DocumentProto.BodyPattern.parseFrom(((Feature.Custom) feature).payload());
            var body = data.getSource();
            return new BodyPattern(
                    Kind.valueOf(data.getKind()),
                    new Feature.BodyRef(body.getFeatureId(), body.getOutputName()),
                    Axis.valueOf(data.getAxis()),
                    data.getFirstCount(),
                    data.getSecondCount(),
                    data.getFirstStep(),
                    data.getSecondStep());
        } catch (com.google.protobuf.InvalidProtocolBufferException failure) {
            throw new IllegalArgumentException("Invalid body pattern", failure);
        }
    }

    private static void validateSpacing(int count, double step) {
        if (count > 1 && step == 0) {
            throw new IllegalArgumentException("Choose nonzero spacing for each repeated direction");
        }
    }

    public BodyPattern {
        long total = (long) firstCount * secondCount;
        if (firstCount < 1 || secondCount < 1 || total < 2 || total > 256) {
            throw new IllegalArgumentException("Choose between 2 and 256 instances including the original");
        }
        if (!Double.isFinite(firstStep) || !Double.isFinite(secondStep)) {
            throw new IllegalArgumentException("Pattern spacing must be finite");
        }
        if (kind == Kind.RECTANGULAR) {
            validateSpacing(firstCount, firstStep);
            validateSpacing(secondCount, secondStep);
        } else if (secondCount != 1) {
            throw new IllegalArgumentException("Unexpected second direction");
        }
        if (kind == Kind.CIRCULAR && (firstStep == 0 || Math.abs(firstStep) > 360)) {
            throw new IllegalArgumentException("Choose a sweep angle between -360 and 360 degrees, excluding zero");
        }
        if (kind == Kind.MIRROR && firstCount != 2) throw new IllegalArgumentException("Mirror creates one copy");
    }

    public Feature feature(String id) {
        var payload = DocumentProto.BodyPattern.newBuilder()
                .setKind(kind.name())
                .setAxis(axis.name())
                .setSource(DocumentProto.BodyRef.newBuilder()
                        .setFeatureId(source.featureId())
                        .setOutputName(source.outputName()))
                .setFirstCount(firstCount)
                .setSecondCount(secondCount)
                .setFirstStep(firstStep)
                .setSecondStep(secondStep)
                .build();
        return new Feature.Custom(id, "dev.voxelbrew", "body-pattern", 1, payload.toByteArray());
    }

    Solid copy(Solid original, int i, int j) {
        if (kind == Kind.MIRROR) return original.mirrored(axis.ordinal(), firstStep);
        if (kind == Kind.CIRCULAR) {
            double angle = firstStep * i / (Math.abs(firstStep) == 360 ? firstCount : firstCount - 1);
            return original.transformed(Transform.rotation(
                    axis == Axis.X ? angle : 0, axis == Axis.Y ? angle : 0, axis == Axis.Z ? angle : 0));
        }
        double a = firstStep * i, b = secondStep * j;
        return original.transformed(
                switch (axis) {
                    case X -> Transform.translation(0, a, b);
                    case Y -> Transform.translation(a, 0, b);
                    case Z -> Transform.translation(a, b, 0);
                });
    }
}
