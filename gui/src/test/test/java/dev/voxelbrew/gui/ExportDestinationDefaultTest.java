package dev.voxelbrew.gui;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ExportDestinationDefaultTest {
    @Test
    void usesDefaultSaveFolderAndProjectNameForExport() {
        var folder = Path.of("designs");

        assertThat(ExportDialog.destinationForFormat(
                        Workbench.exportSuggestion(folder, Path.of("projects", "chair.vxb")), "stl"))
                .isEqualTo(folder.resolve("chair.stl"));
        assertThat(ExportDialog.destinationForFormat(Workbench.exportSuggestion(folder, null), "txt"))
                .isEqualTo(folder.resolve("Untitled.txt"));
    }
}
