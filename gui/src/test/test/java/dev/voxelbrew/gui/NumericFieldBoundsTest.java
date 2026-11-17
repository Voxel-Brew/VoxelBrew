package dev.voxelbrew.gui;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class NumericFieldBoundsTest {
    @Test
    void inclusiveAndExclusiveBoundsHandleBothEndpoints() {
        for (boolean minInclusive : new boolean[] {false, true}) {
            for (boolean maxInclusive : new boolean[] {false, true}) {
                var bounds = new NumericFieldBounds(5, minInclusive, 100, maxInclusive);
                assertThat(bounds.accepts(5)).isEqualTo(minInclusive);
                assertThat(bounds.accepts(100)).isEqualTo(maxInclusive);
                assertThat(bounds.accepts(10)).isTrue();
                assertThat(bounds.accepts(4)).isFalse();
                assertThat(bounds.accepts(101)).isFalse();
                assertThat(bounds.accepts(Double.NaN)).isFalse();
                assertThat(bounds.accepts(Double.POSITIVE_INFINITY)).isFalse();
            }
        }
    }

    @Test
    void optionalBoundsShowOnlyTheirFiniteLimits() {
        var minimum = new NumericFieldBounds(0, false, Double.POSITIVE_INFINITY, false);
        assertThat(minimum.minimumHint()).isEqualTo("0 <");
        assertThat(minimum.maximumHint()).isEqualTo("");
        assertThat(minimum.accepts(1e10)).isTrue();
        var maximum = new NumericFieldBounds(Double.NEGATIVE_INFINITY, false, 100, true);
        assertThat(maximum.minimumHint()).isEqualTo("");
        assertThat(maximum.maximumHint()).isEqualTo("≤ 100");
        assertThat(maximum.accepts(-1e10)).isTrue();
        assertThat(new NumericFieldBounds(0.05, true, 0.3, true).minimumHint()).isEqualTo("0.05 ≤");
        assertThat(NumericFieldBounds.UNBOUNDED.minimumHint()).isEqualTo("");
        assertThat(NumericFieldBounds.UNBOUNDED.maximumHint()).isEqualTo("");
    }

    @Test
    void rejectsEmptyAndInvalidIntervals() {
        assertThatThrownBy(() -> new NumericFieldBounds(10, true, 5, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NumericFieldBounds(5, false, 5, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NumericFieldBounds(Double.NaN, true, 5, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new NumericFieldBounds(5, true, 5, true).accepts(5)).isTrue();
    }
}
