package dev.voxelbrew.gui;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class MiddleGestureTest {
    @Test
    void clickWithSmallJitterDoesNotPan() {
        var gesture = new MiddleGesture();
        gesture.press(100, 100);
        assertThat(gesture.drag(103, 104)).isNull();
        assertThat(gesture.release(103, 104)).isTrue();
        assertThat(gesture.release(103, 104)).isFalse();
    }

    @Test
    void crossingThresholdPansFromPressAndCannotBecomeClickAgain() {
        var gesture = new MiddleGesture();
        gesture.press(100, 100);
        assertThat(gesture.drag(102, 102)).isNull();
        assertThat(gesture.drag(106, 100)).containsExactly(new double[] {6, 0});
        assertThat(gesture.drag(100, 100)).containsExactly(new double[] {-6, 0});
        assertThat(gesture.release(100, 100)).isFalse();
        gesture.press(100, 100);
        assertThat(gesture.release(100, 100)).isTrue();
    }

    @Test
    void releaseOutsideThresholdAndCancelledGesturesNeverOpen() {
        var gesture = new MiddleGesture();
        gesture.press(100, 100);
        assertThat(gesture.release(120, 100)).isFalse();
        gesture.press(100, 100);
        gesture.cancel();
        assertThat(gesture.drag(120, 100)).isNull();
        assertThat(gesture.release(100, 100)).isFalse();
    }
}
