package dev.voxelbrew.gui;

import java.math.BigDecimal;

/** Optional inclusive or exclusive limits in the field's declared display unit. */
record NumericFieldBounds(double min, boolean minInclusive, double max, boolean maxInclusive) {
    static final NumericFieldBounds UNBOUNDED =
            new NumericFieldBounds(Double.NEGATIVE_INFINITY, false, Double.POSITIVE_INFINITY, false);

    private static String format(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    NumericFieldBounds {
        if (Double.isNaN(min)
                || Double.isNaN(max)
                || min == Double.POSITIVE_INFINITY
                || max == Double.NEGATIVE_INFINITY
                || min > max
                || (min == max && (!minInclusive || !maxInclusive))) {
            throw new IllegalArgumentException("Bounds must describe a nonempty numeric interval");
        }
    }

    boolean accepts(double value) {
        return Double.isFinite(value)
                && (minInclusive ? value >= min : value > min)
                && (maxInclusive ? value <= max : value < max);
    }

    String minimumHint() {
        if (!Double.isFinite(min)) return "";
        return format(min) + (minInclusive ? " ≤" : " <");
    }

    String maximumHint() {
        if (!Double.isFinite(max)) return "";
        return (maxInclusive ? "≤ " : "< ") + format(max);
    }
}
