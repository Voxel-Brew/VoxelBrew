package dev.voxelbrew.gui;

import static org.assertj.core.api.Assertions.assertThat;

import javafx.geometry.Point3D;
import org.junit.jupiter.api.Test;

class GroundGridTest {
    @Test
    void zoomKeepsReadableLinesOnePixelWideAndFadesCrowdedIntervals() {
        // With a 1000 px focal length, 10 mm lines at these depths are 100, 20, 10 and 5 px apart.
        assertThat(projectedWidth(10, 100)).isCloseTo(1, org.assertj.core.data.Offset.offset(.001));
        assertThat(projectedWidth(10, 500)).isCloseTo(1, org.assertj.core.data.Offset.offset(.001));
        assertThat(projectedWidth(10, 1000)).isBetween(.65, .68);
        assertThat(projectedWidth(10, 2000)).isZero();
        assertThat(projectedWidth(100, 2000)).isCloseTo(1, org.assertj.core.data.Offset.offset(.001));
    }

    private double projectedWidth(double at, double depth) {
        var points = GroundGrid.mesh(p -> new Point3D(p.getX(), -p.getZ(), p.getY()), depth, .5, .3, 600, .01, 100000)
                .getPoints();
        for (int i = 0; i < points.size(); i += 12) {
            if (Math.abs((points.get(i) + points.get(i + 3)) / 2 - at) < .001 && points.get(i) != points.get(i + 3)) {
                return Math.abs(points.get(i) - points.get(i + 3)) * 1000 / depth;
            }
        }
        return 0;
    }

    @Test
    void panningFarFromOriginStillCoversVisibleWorldAlignedGridLines() {
        var mesh = GroundGrid.mesh(
                p -> new Point3D(10003 + p.getX(), -p.getZ(), -20007 + p.getY()), 180, .5, .3, 600, .01, 100000);
        var points = mesh.getPoints();
        boolean coversFarEdge = false;
        for (int i = 0; i < points.size(); i += 12) {
            double center = (points.get(i) + points.get(i + 3)) / 2;
            if (Math.abs(center - 10090) < .001
                    && points.get(i + 2) <= -20007 - 54
                    && points.get(i + 8) >= -20007 + 54) {
                coversFarEdge = true;
            }
        }
        assertThat(coversFarEdge)
                .as("10 mm grid line at the panned viewport edge")
                .isTrue();
    }
}
