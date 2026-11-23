package dev.voxelbrew.gui;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SketchRotationAnglesTest {
    @Test
    void snapsNearEachRequestedDirectionAcrossTheWholeCircle() {
        for (double degrees : new double[] {0, 30, 45, 60, 90, 120, 135, 150, 180}) {
            for (int sign : new int[] {-1, 1}) {
                for (double offset : new double[] {-2, 2}) {
                    double snapped = SketchRotationAngles.snap(sign * (degrees + offset));
                    assertThat(snapped).isEqualTo(degrees == 0 ? 0 : sign * degrees);
                }
            }
        }
    }

    @Test
    void keepsFreeAnglesOutsideToleranceAndWrapsAtHalfATurn() {
        assertThat(SketchRotationAngles.snap(23.5)).isEqualTo(23.5);
        assertThat(SketchRotationAngles.snap(190)).isEqualTo(-170);
        assertThat(SketchRotationAngles.snap(-226)).isEqualTo(135);
    }
}
