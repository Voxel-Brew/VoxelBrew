package dev.voxelbrew.gui;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PrintCameraTest {
    @Test
    void standardViewNavigationUpdatesProjectionAndKeepsTopViewPlanar() {
        var camera = new PrintCamera(true);
        camera.orient(ViewOrientation.named("Front"));
        assertThat(camera.orientation()).isEqualTo(ViewOrientation.named("Front"));
        assertThat(camera.y(0, 1, 0)).isCloseTo(0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(camera.y(0, 0, 1)).isCloseTo(1, org.assertj.core.data.Offset.offset(1e-9));
        camera.orient(ViewOrientation.named("Right"));
        assertThat(camera.x(0, 1)).isCloseTo(1, org.assertj.core.data.Offset.offset(1e-9));
        var top = new PrintCamera(false);
        top.orient(ViewOrientation.HOME);
        assertThat(top.orientation()).isEqualTo(ViewOrientation.named("Top"));
    }

    @Test
    void threeDimensionalOrbitUsesDesignerOrientationAndBedMovementInvertsProjection() {
        var camera = new PrintCamera(true);
        camera.orbit(34, 18);
        assertThat(camera.orientation()).isEqualTo(ViewOrientation.HOME.dragged(34, 18));
        var delta = camera.bedDelta(camera.x(12, -7), camera.y(12, -7, 0));
        assertThat(delta).isNotNull();
        assertThat(delta.getX()).isCloseTo(12, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(delta.getY()).isCloseTo(-7, org.assertj.core.data.Offset.offset(1e-9));
        camera.orbit(0, -camera.orientation().tilt() * 2);
        assertThat(camera.bedDelta(1, 1))
                .as("Edge-on movement must not produce infinite placements")
                .isNull();
    }

    @Test
    void topViewPansInsteadOfTiltingAndFitRestoresPanAndZoom() {
        var camera = new PrintCamera(false);
        camera.orbit(40, -30);
        camera.pan(10, 20);
        assertThat(camera.panX()).isEqualTo(50);
        assertThat(camera.panY()).isEqualTo(-10);
        assertThat(camera.orientation()).isEqualTo(ViewOrientation.named("Top"));
        var delta = camera.bedDelta(4, 7);
        assertThat(delta.getX()).isCloseTo(4, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(delta.getY()).isCloseTo(7, org.assertj.core.data.Offset.offset(1e-9));
        camera.scroll(120);
        assertThat(camera.zoom()).isCloseTo(Math.exp(120 * .0015), org.assertj.core.data.Offset.offset(1e-9));
        camera.scroll(Double.MAX_VALUE);
        assertThat(camera.zoom()).isEqualTo(100);
        camera.scroll(-Double.MAX_VALUE);
        assertThat(camera.zoom()).isEqualTo(.05);
        camera.fit();
        assertThat(camera.panX()).isEqualTo(0);
        assertThat(camera.panY()).isEqualTo(0);
        assertThat(camera.zoom()).isEqualTo(1);
    }
}
