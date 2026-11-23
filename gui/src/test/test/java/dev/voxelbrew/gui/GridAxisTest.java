package dev.voxelbrew.gui;

import static org.assertj.core.api.Assertions.assertThat;

import javafx.geometry.Point2D;
import javafx.geometry.Point3D;
import javafx.scene.shape.TriangleMesh;
import javafx.scene.transform.Translate;
import org.junit.jupiter.api.Test;

class GridAxisTest {
    @Test
    void labelsFollowPannedAxesAndSkipAxesThatDoNotReachAnEdge() {
        var axis = new GridAxis("x", new Point3D(1, 0, 0), "#bb6666");
        assertThat(axis.update(new Translate(0, 15), 100, .5, .3, 600, .01, 100000))
                .containsExactly(new Point2D(1000, 450), new Point2D(0, 450));
        assertThat(axis.update(new Translate(0, 50), 100, .5, .3, 600, .01, 100000))
                .isEmpty();
        var endOn = new GridAxis("y", new Point3D(0, 0, 1), "#55a184");
        assertThat(endOn.update(new Translate(), 100, .5, .3, 600, .01, 100000)).isEmpty();
    }

    @Test
    void pannedViewClipsSolidAndDashedSidesFarFromTheOrigin() {
        var axis = new GridAxis("x", new Point3D(1, 0, 0), "#bb6666");
        axis.update(new Translate(-10003, 0), 100, .5, .3, 600, .01, 100000);
        var points = ((TriangleMesh) axis.getMesh()).getPoints();
        assertThat(points.size()).isEqualTo(24);
        assertThat(points.get(0)).isEqualTo(9953);
        assertThat(points.get(12)).isEqualTo(10053);

        axis.update(new Translate(10003, 0), 100, .5, .3, 600, .01, 100000);
        points = ((TriangleMesh) axis.getMesh()).getPoints();
        assertThat(points.size()).isEqualTo(10 * 24);
        for (int i = 0; i < 10; i++) {
            int start = -10050 + i * 10;
            assertThat(points.get(i * 24)).isEqualTo(start);
            assertThat(points.get(i * 24 + 12)).isEqualTo(start + 6);
        }
    }
}
