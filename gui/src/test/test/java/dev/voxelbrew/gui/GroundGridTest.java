package dev.voxelbrew.gui;

import static org.assertj.core.api.Assertions.assertThat;

import javafx.geometry.Point3D;
import org.junit.jupiter.api.Test;

class GroundGridTest {
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
