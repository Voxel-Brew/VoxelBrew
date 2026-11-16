package dev.voxelbrew.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ExportDestinationDefaultTest {
    @Test
    void usesDefaultSaveFolderAndProjectNameForExport() {
        var folder = Path.of("designs");

        assertEquals(
                folder.resolve("chair.stl"),
                ExportDialog.destinationForFormat(
                        Workbench.exportSuggestion(folder, Path.of("projects", "chair.vxb")), "stl"));
        assertEquals(
                folder.resolve("Untitled.txt"),
                ExportDialog.destinationForFormat(Workbench.exportSuggestion(folder, null), "txt"));
    }
}
