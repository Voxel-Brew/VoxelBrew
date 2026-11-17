package dev.voxelbrew.gui;

import static org.assertj.core.api.Assertions.*;

import java.util.Arrays;
import java.util.HashSet;
import org.junit.jupiter.api.Test;

class UiTestShardTest {
    @Test
    void everyRobotMethodBelongsToExactlyOneNonemptyShard() {
        assertThat(Arrays.asList(WorkbenchUiTest.class
                                .getAnnotation(org.junit.jupiter.api.extension.ExtendWith.class)
                                .value())
                        .contains(UiTestShard.class))
                .isTrue();
        var methods = Arrays.stream(WorkbenchUiTest.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Test.class))
                .map(method -> method.getName())
                .toList();
        assertThat(methods.isEmpty()).isFalse();
        for (int count : new int[] {4, 5}) {
            var assigned = new HashSet<String>();
            int minimum = Integer.MAX_VALUE, maximum = 0;
            for (int shard = 1; shard <= count; shard++) {
                int selectedShard = shard;
                var selected = methods.stream()
                        .filter(name -> UiTestShard.shardFor(name, count) == selectedShard)
                        .toList();
                assertThat(selected.isEmpty())
                        .as("Empty shard " + shard + "/" + count)
                        .isFalse();
                minimum = Math.min(minimum, selected.size());
                maximum = Math.max(maximum, selected.size());
                for (String name : selected)
                    assertThat(assigned.add(name)).as("Duplicate test " + name).isTrue();
            }
            assertThat(assigned).isEqualTo(new HashSet<>(methods));
            assertThat(maximum - minimum <= 1)
                    .as("Shard sizes must differ by at most one test")
                    .isTrue();
        }
        for (String name : methods) assertThat(UiTestShard.shardFor(name, 1)).isEqualTo(1);
        assertThatThrownBy(() -> UiTestShard.shardFor("test", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UiTestShard.shardFor("missingTest", 5)).isInstanceOf(IllegalArgumentException.class);
    }
}
