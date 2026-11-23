package dev.voxelbrew.gui;

import lombok.experimental.UtilityClass;

@UtilityClass
class SketchRotationAngles {
    private static final double SNAP_TOLERANCE = 3;

    static double signed(double degrees) {
        double angle = degrees % 360;
        if (angle > 180) return angle - 360;
        if (angle < -180) return angle + 360;
        return angle == 0 ? 0 : angle;
    }

    static double snap(double degrees) {
        double angle = degrees % 360;
        double thirty = 30 * Math.rint(angle / 30);
        double fortyFive = 45 * Math.rint(angle / 45);
        double nearest = Math.abs(angle - thirty) <= Math.abs(angle - fortyFive) ? thirty : fortyFive;
        return signed(Math.abs(angle - nearest) <= SNAP_TOLERANCE ? nearest : angle);
    }
}
