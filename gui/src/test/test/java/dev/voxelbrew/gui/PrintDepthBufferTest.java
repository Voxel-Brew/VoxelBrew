package dev.voxelbrew.gui;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PrintDepthBufferTest {
    private static PrintDepthBuffer.Point point(double x, double y, double depth) {
        return new PrintDepthBuffer.Point(x, y, depth);
    }

    @Test
    void nearestSurfaceAndPickingDoNotDependOnInsertionOrder() {
        var first = new PrintDepthBuffer(20, 20);
        var second = new PrintDepthBuffer(20, 20);
        first.triangle(point(0, 0, 5), point(20, 0, 5), point(0, 20, 5), 0xff112233, 1);
        first.triangle(point(0, 0, 10), point(20, 0, 10), point(0, 20, 10), 0xff445566, 2);
        second.triangle(point(0, 0, 10), point(20, 0, 10), point(0, 20, 10), 0xff445566, 2);
        second.triangle(point(0, 0, 5), point(20, 0, 5), point(0, 20, 5), 0xff112233, 1);
        assertThat(second.pixels()).containsExactly(first.pixels());
        assertThat(first.owner(4, 4)).isEqualTo(1);
        assertThat(second.owner(4, 4)).isEqualTo(1);
        assertThat(first.owner(19, 19)).isEqualTo(-1);
        assertThat(first.owner(-0.1, 4)).isEqualTo(-1);
    }

    @Test
    void crossingFacesUseDepthAtEachPixelRatherThanTheirCenters() {
        var raster = new PrintDepthBuffer(20, 20);
        raster.triangle(point(0, 0, 0), point(20, 0, 20), point(0, 20, 0), 0xff112233, 1);
        raster.triangle(point(0, 0, 8), point(20, 0, 8), point(0, 20, 8), 0xff445566, 2);
        assertThat(raster.owner(2, 2)).isEqualTo(1);
        assertThat(raster.owner(12, 2)).isEqualTo(2);
    }

    @Test
    void hiddenEdgesDoNotShowThroughAndVisibleEdgesKeepSurfacePicking() {
        var raster = new PrintDepthBuffer(20, 20);
        raster.triangle(point(0, 0, 5), point(20, 0, 5), point(0, 20, 5), 0xff112233, 1);
        raster.edge(point(2, 4.5, 10), point(12, 4.5, 10), 0xff000000);
        assertThat(raster.pixels()[4 * 20 + 4]).isEqualTo(0xff112233);
        raster.edge(point(2, 4.5, 5), point(12, 4.5, 5), 0xff000000);
        assertThat(raster.pixels()[4 * 20 + 4]).isEqualTo(0xff000000);
        assertThat(raster.owner(4, 4)).isEqualTo(1);
    }

    @Test
    void overlappingEdgesUseDepthAndStableTieBreaking() {
        var first = new PrintDepthBuffer(20, 20);
        var second = new PrintDepthBuffer(20, 20);
        first.edge(point(2, 4.5, 5), point(12, 4.5, 5), 0xff000000);
        first.edge(point(2, 4.5, 10), point(12, 4.5, 10), 0xff075b91);
        second.edge(point(2, 4.5, 10), point(12, 4.5, 10), 0xff075b91);
        second.edge(point(2, 4.5, 5), point(12, 4.5, 5), 0xff000000);
        assertThat(second.pixels()).containsExactly(first.pixels());
        first.edge(point(2, 4.5, 5), point(12, 4.5, 5), 0xff075b91);
        assertThat(second.pixels()).containsExactly(first.pixels());
    }

    @Test
    void highResolutionEdgesRetainLogicalWidthAndDepthOcclusion() {
        var raster = new PrintDepthBuffer(40, 40, 2);
        raster.triangle(point(0, 0, 5), point(40, 0, 5), point(0, 40, 5), 0xff112233, 1);
        raster.edge(point(2, 8, 10), point(24, 8, 10), 0xff000000);
        assertThat(raster.pixels()[7 * 40 + 8]).isEqualTo(0xff112233);
        raster.edge(point(2, 8, 5), point(24, 8, 5), 0xff000000);
        assertThat(raster.pixels()[7 * 40 + 8]).isEqualTo(0xff000000);
        assertThat(raster.pixels()[8 * 40 + 8]).isEqualTo(0xff000000);
        assertThat(raster.owner(8, 8)).isEqualTo(1);
    }

    @Test
    void cameraDepthFollowsOrbitAndTopViewLooksDown() {
        var top = new PrintCamera(false);
        assertThat(top.depth(0, 0, 10) < top.depth(0, 0, 0)).isTrue();
        var camera = new PrintCamera(true);
        double before = camera.depth(100, 0, 0);
        camera.orbit(360, 0);
        assertThat(camera.depth(100, 0, 0)).isCloseTo(-before, org.assertj.core.data.Offset.offset(1e-9));
    }
}
