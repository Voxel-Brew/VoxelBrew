package dev.voxelbrew.util;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class UuidGeneratorTest {
    @Test
    void startsAtOneAndResumesWithoutReusingIds() {
        var ids = new UuidGenerator();
        assertThat(ids.next()).isEqualTo(1);
        assertThat(ids.next()).isEqualTo(2);
        assertThat(new UuidGenerator(ids.nextValue()).next()).isEqualTo(3);
    }

    @Test
    void concurrentCallsAreUnique() {
        var ids = new UuidGenerator();
        assertThat(java.util.stream.IntStream.range(0, 10000)
                        .parallel()
                        .mapToLong(i -> ids.next())
                        .distinct()
                        .count())
                .isEqualTo(10000);
    }

    @Test
    void invalidAndExhaustedSequencesFail() {
        assertThatThrownBy(() -> new UuidGenerator(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UuidGenerator(Long.MAX_VALUE).next()).isInstanceOf(IllegalStateException.class);
    }
}
