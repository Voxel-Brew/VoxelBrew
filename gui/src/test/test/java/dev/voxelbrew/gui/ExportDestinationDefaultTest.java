package dev.voxelbrew.gui;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExportDestinationDefaultTest {
    @Test
    void usesProjectFolderForExportAndHomeForUntitled() {
        var folder = Path.of("designs");

        assertThat(ExportDialog.destinationForFormat(
                        Workbench.exportSuggestion(folder, Path.of("projects", "chair.vxb")), "stl"))
                .isEqualTo(Path.of("projects", "chair.stl"));
        assertThat(ExportDialog.destinationForFormat(Workbench.exportSuggestion(folder, null), "txt"))
                .isEqualTo(folder.resolve("Untitled.txt"));
    }

    @Test
    void addsTheFullSuffixAndPreservesExistingSuffixes(@TempDir Path folder) {
        assertThat(ExportDialog.withExtension(folder.resolve("project"), "vxb"))
                .isEqualTo(folder.resolve("project.vxb"));
        assertThat(ExportDialog.withExtension(folder.resolve("project.VXB"), "vxb"))
                .isEqualTo(folder.resolve("project.VXB"));
        assertThat(ExportDialog.withExtension(folder.resolve("plate.v2"), "gcode.3mf"))
                .isEqualTo(folder.resolve("plate.v2.gcode.3mf"));
        var packagePath = folder.resolve("plate.GCODE.3MF");
        assertThat(ExportDialog.withExtension(packagePath, "gcode.3mf")).isEqualTo(packagePath);
        assertThat(ExportDialog.destinationForFormat(packagePath, "gcode.3mf")).isEqualTo(packagePath);
        assertThat(ExportDialog.withExtension(folder, "json")).isEqualTo(folder);
    }
}
