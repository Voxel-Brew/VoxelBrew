package dev.voxelbrew.engine.geometry;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@SuppressWarnings("deprecation")
class NativeKernelTest {
    @Test
    void bearingMountFilletsAndSubsequentCutsProducePrintableMeshes() {
        int before = NativeKernel.liveHandles();
        try (Solid box = Solid.rectanglePrism(0, 0, 0, 76, 52, 6);
                Solid rounded = box.edgeFinish(
                        true, 2, java.util.stream.IntStream.rangeClosed(1, 12).toArray());
                Solid cut1 = rounded.cutThroughCircle(9, 9, 6);
                Solid cut2 = cut1.cutThroughCircle(67, 9, 6);
                Solid cut3 = cut2.cutThroughCircle(67, 43, 6);
                Solid cut4 = cut3.cutThroughCircle(9, 43, 6);
                Solid bored = cut4.cutThroughCircle(38, 26, 20)) {
            for (var solid : java.util.List.of(rounded, cut1, cut2, cut3, cut4, bored)) {
                var mesh = solid.tessellate(dev.voxelbrew.engine.mesh.MeshOptions.DEFAULT);
                var report = dev.voxelbrew.engine.mesh.MeshValidation.inspect(mesh);
                assertThat(report.closed()).as(report.toString()).isTrue();
                for (int t = 0; t < mesh.triangleCount(); t++) {
                    assertThat(mesh.faceIndex(t) >= 0).isTrue();
                }
            }
            assertThat(bored.volume())
                    .isCloseTo(20786.053208586636, org.assertj.core.data.Offset.offset(VOLUME_TOLERANCE_MM3));
        }
        assertThat(NativeKernel.liveHandles()).isEqualTo(before);
    }

    private static final double VOLUME_TOLERANCE_MM3 = 0.01;
    private static final double BOUNDS_TOLERANCE_MM = 0.001;

    @Test
    void edgeFinishesAndShellProduceValidIndependentSolids() {
        int before = NativeKernel.liveHandles();
        try (Solid box = Solid.rectanglePrism(0, 0, 0, 20, 20, 10);
                Solid fillet = box.edgeFinish(true, 1, new int[] {1});
                Solid chamfer = box.edgeFinish(false, 1, new int[] {1});
                Solid shell = box.shell(1, 1)) {
            assertThat(fillet.volume() < box.volume()).isTrue();
            assertThat(chamfer.volume() < box.volume()).isTrue();
            assertThat(shell.volume() < box.volume()).isTrue();
            assertThat(shell.volume() > 0).isTrue();
            assertThat(box.volume()).isCloseTo(4000, org.assertj.core.data.Offset.offset(VOLUME_TOLERANCE_MM3));
            assertThat(box.faceEdges().length >= 2).isTrue();
            assertThat(box.faceEdges().length % 2).isEqualTo(0);
            assertThat(box.edgeSamples().length % 4).isEqualTo(0);
        }
        assertThat(NativeKernel.liveHandles()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(doubles = {5, 8})
    void referencePlateUsesRealKernel(double thickness) {
        assertThat(Solid.kernelVersion()).isEqualTo("7.9.0");
        int before = NativeKernel.liveHandles();
        try (Solid plate = Solid.rectanglePrism(0, 0, 0, 60, 40, thickness);
                Solid drilled = plate.cutThroughCircle(10, 10, 4)) {
            double expected = (60 * 40 - 4 * Math.PI) * thickness;
            assertThat(drilled.volume()).isCloseTo(expected, org.assertj.core.data.Offset.offset(VOLUME_TOLERANCE_MM3));
            assertBounds(new Bounds(0, 0, 0, 60, 40, thickness), drilled.bounds());
            for (double fraction : new double[] {0.01, 0.5, 0.99}) {
                assertThat(drilled.contains(10, 10, thickness * fraction))
                        .as("hole must be open throughout")
                        .isFalse();
                assertThat(drilled.contains(13, 10, thickness * fraction))
                        .as("surrounding material must remain")
                        .isTrue();
            }
            assertThat(plate.volume())
                    .isCloseTo(2400 * thickness, org.assertj.core.data.Offset.offset(VOLUME_TOLERANCE_MM3));
            System.out.printf(
                    java.util.Locale.ROOT,
                    "OCCT %s thickness=%.1f mm volume=%.12f mm^3 expected=%.12f bounds=%s%n",
                    Solid.kernelVersion(),
                    thickness,
                    drilled.volume(),
                    expected,
                    drilled.bounds());
        }
        assertThat(NativeKernel.liveHandles()).as("all native owners released").isEqualTo(before);
    }

    @Test
    void offsetAndSignedExtrusion() {
        try (Solid positive = Solid.rectanglePrism(0, 0, 12, 60, 40, 5);
                Solid negative = Solid.rectanglePrism(0, 0, 12, 60, 40, -3)) {
            assertBounds(new Bounds(0, 0, 12, 60, 40, 17), positive.bounds());
            assertBounds(new Bounds(0, 0, 9, 60, 40, 12), negative.bounds());
            assertThat(negative.volume()).isCloseTo(7200, org.assertj.core.data.Offset.offset(VOLUME_TOLERANCE_MM3));
        }
    }

    @Test
    void resultSurvivesSourceCloseAndCloseIsIdempotent() {
        int before = NativeKernel.liveHandles();
        Solid result;
        try (Solid source = Solid.rectanglePrism(0, 0, 0, 60, 40, 8)) {
            result = source.cutThroughCircle(10, 10, 4);
        }
        try (result) {
            assertThat(result.volume())
                    .isCloseTo((2400 - 4 * Math.PI) * 8, org.assertj.core.data.Offset.offset(VOLUME_TOLERANCE_MM3));
        }
        result.close();
        assertThatThrownBy(result::volume).isInstanceOf(IllegalStateException.class);
        assertThat(NativeKernel.liveHandles()).isEqualTo(before);
    }

    @Test
    void invalidDimensionsAreRejectedBeforeAllocating() {
        int before = NativeKernel.liveHandles();
        for (double bad : new double[] {0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThatThrownBy(() -> Solid.rectanglePrism(0, 0, 0, bad, 40, 5))
                    .isInstanceOf(IllegalArgumentException.class);
            try (Solid plate = Solid.rectanglePrism(0, 0, 0, 60, 40, 5)) {
                assertThatThrownBy(() -> plate.cutThroughCircle(10, 10, bad))
                        .isInstanceOf(IllegalArgumentException.class);
            }
        }
        assertThat(NativeKernel.liveHandles()).isEqualTo(before);
    }

    @Test
    void nativeFailuresBecomeJavaDiagnostics() {
        GeometryException error = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> NativeKernel.volume(Long.MAX_VALUE), GeometryException.class);
        assertThat(error.getMessage().contains("volume: unknown or closed solid handle"))
                .isTrue();
        error = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> NativeKernel.rectanglePrism(0, 0, 0, -1, 40, 5), GeometryException.class);
        assertThat(error.getMessage().contains("rectangle extrusion")).isTrue();
        int before = NativeKernel.liveHandles();
        try (Solid plate = Solid.rectanglePrism(0, 0, 0, 60, 40, 5)) {
            error = org.assertj.core.api.Assertions.catchThrowableOfType(
                    () -> plate.cutThroughCircle(30, 20, 1000), GeometryException.class);
            assertThat(error.getMessage().contains("exactly one solid")).isTrue();
            assertThat(plate.volume()).isCloseTo(12000, org.assertj.core.data.Offset.offset(VOLUME_TOLERANCE_MM3));
        }
        assertThat(NativeKernel.liveHandles()).isEqualTo(before);
    }

    @Test
    void concurrentDocumentsReleaseIndependentNativeOwners() throws Exception {
        int before = NativeKernel.liveHandles();
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(4)) {
            var jobs = new java.util.ArrayList<java.util.concurrent.Callable<Void>>();
            for (int worker = 0; worker < 4; worker++) {
                jobs.add(() -> {
                    for (int revision = 0; revision < 10; revision++) {
                        double thickness = 5 + revision;
                        try (Solid plate = Solid.rectanglePrism(0, 0, 0, 60, 40, thickness);
                                Solid drilled = plate.cutThroughCircle(10, 10, 4)) {
                            assertThat(drilled.volume())
                                    .isCloseTo(
                                            (2400 - 4 * Math.PI) * thickness,
                                            org.assertj.core.data.Offset.offset(VOLUME_TOLERANCE_MM3));
                        }
                    }
                    return null;
                });
            }
            for (var future : executor.invokeAll(jobs)) {
                future.get();
            }
        }
        assertThat(NativeKernel.liveHandles()).isEqualTo(before);
    }

    private static void assertBounds(Bounds expected, Bounds actual) {
        assertThat(new double[] {
                    actual.minX(), actual.minY(), actual.minZ(), actual.maxX(), actual.maxY(), actual.maxZ()
                })
                .usingComparatorWithPrecision(BOUNDS_TOLERANCE_MM)
                .containsExactly(new double[] {
                    expected.minX(), expected.minY(), expected.minZ(), expected.maxX(), expected.maxY(), expected.maxZ()
                });
    }
}
