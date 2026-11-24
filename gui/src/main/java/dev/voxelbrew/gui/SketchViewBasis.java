package dev.voxelbrew.gui;

import dev.voxelbrew.api.model.sketch.SketchGeometry.Type;
import dev.voxelbrew.api.model.sketch.SketchGeometry.Vec;

/** Face-on sketch coordinates with local +Y right and local +X down. */
record SketchViewBasis(Vec right, Vec up) {
    static final SketchViewBasis DEFAULT = new SketchViewBasis(new Vec(0, 1), new Vec(-1, 0));

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
