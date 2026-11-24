package dev.voxelbrew.gui;

import dev.voxelbrew.api.model.sketch.SketchGeometry.Type;
import dev.voxelbrew.api.model.sketch.SketchGeometry.Vec;

/** Face-on sketch coordinates with local +Y right and local +X down. */
record SketchViewBasis(Vec right, Vec up) {
    static final SketchViewBasis DEFAULT = new SketchViewBasis(new Vec(0, 1), new Vec(-1, 0));

    SketchViewBasis quarterTurn(boolean clockwise) {
        return clockwise ? new SketchViewBasis(up, right.times(-1)) : new SketchViewBasis(up.times(-1), right);
    }

    double clockwiseDegrees() {
        return Math.toDegrees(Math.atan2(-up.y(), right.y()));
    }

    /** Rotate an annotation offset from the initial canvas orientation into the current one. */
    Vec screenOffset(double x, double y) {
        var projected = project(DEFAULT.unproject(x, -y));
        return new Vec(projected.x(), -projected.y());
    }

    Vec annotationOffset(double x, double y) {
        var projected = DEFAULT.project(unproject(x, -y));
        return new Vec(projected.x(), -projected.y());
    }

    /** The same axis swap converts both toolbar commands to model constraints and model badges to screen symbols. */
    Type mapAxisConstraint(Type type) {
        if (Math.abs(right.x()) >= Math.abs(right.y())) return type;
        return switch (type) {
            case HORIZONTAL -> Type.VERTICAL;
            case VERTICAL -> Type.HORIZONTAL;
            default -> type;
        };
    }

    Vec project(Vec point) {
        return new Vec(right.dot(point), up.dot(point));
    }

    Vec unproject(double x, double y) {
        return right.times(x).plus(up.times(y));
    }
}
