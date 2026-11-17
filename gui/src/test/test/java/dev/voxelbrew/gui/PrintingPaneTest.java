package dev.voxelbrew.gui;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PrintingPaneTest {
    @Test
    void jobDestinationKeepsProjectDirectoryAndReplacesOnlyLastExtension() {
        assertThat(PrintingPane.jobDestination(Path.of("parts", "my.part.vxb"), "boo", 1, 1))
                .isEqualTo(Path.of("parts", "my.part-1-boo.gcode"));
        assertThat(PrintingPane.jobDestination(Path.of("part"), "Plate 1", 1, 1))
                .isEqualTo(Path.of("part-1-Plate 1.gcode"));
    }

    @Test
    void plateNumberWidthFollowsTotalPlateCount() {
        for (int count : new int[] {3, 9, 10, 33, 99, 100, 999, 1000, 9999, 10000}) {
            String number = "0".repeat(Integer.toString(count).length() - 1) + "3";
            assertThat(PrintingPane.jobDestination(Path.of("Foo.vxb"), "boo", 3, count))
                    .isEqualTo(Path.of("Foo-" + number + "-boo.gcode"));
        }
    }

    @Test
    void escapesUnsafeCharactersWithoutLosingUnicodeOrCreatingDirectories() {
        assertThat(PrintingPane.jobDestination(Path.of("Foo.vxb"), "zażółć <>:\"/\\|?*%. ", 3, 33))
                .isEqualTo(Path.of("Foo-03-zażółć %3C%3E%3A%22%2F%5C%7C%3F%2A%25%2E%20.gcode"));
        assertThat(PrintingPane.jobDestination(Path.of("Foo.vxb"), "..", 1, 1))
                .isEqualTo(Path.of("Foo-1-%2E%2E.gcode"));
        assertThat(PrintingPane.jobDestination(Path.of("Foo.vxb"), "%2F", 1, 1))
                .isNotEqualTo(PrintingPane.jobDestination(Path.of("Foo.vxb"), "/", 1, 1));
    }
}
