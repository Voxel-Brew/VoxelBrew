package dev.voxelbrew.gui;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.slicer.PrinterProfileCatalog;
import dev.voxelbrew.api.slicer.SlicerRuntime;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PrinterVisibilityTest {
    @TempDir
    Path directory;

    @Test
    void visibilityPersistsCanBeRestoredAndPreservesOtherPreferences() throws Exception {
        var configuration = new dev.voxelbrew.util.config.FileUserConfiguration(directory.resolve("config.properties"));
        configuration.set("language", "pl");
        var visibility = new PrinterVisibility(configuration);
        var catalog = SlicerRuntime.load().printers(directory.resolve("printers"));
        catalog.exportDefaults(Set.of("bambu-lab-a1-mini"));
        var entries = catalog.load().entries();
        assertThat(visibility.hidden().isEmpty()).isTrue();
        var visible = entries.stream()
                .filter(PrinterProfileCatalog.Entry::bundled)
                .map(e -> e.definition().printer().id())
                .collect(Collectors.toSet());
        visible.remove("bambu-lab-a1-mini");
        visibility.saveVisible(entries, visible);
        var reopened = new PrinterVisibility(new dev.voxelbrew.util.config.FileUserConfiguration(configuration.path()));
        assertThat(reopened.hidden()).isEqualTo(Set.of("bambu-lab-a1-mini"));
        assertThat(configuration.get("language", "")).isEqualTo("pl");
        assertThat(catalog.load().require("bambu-lab-a1-mini")).isNotNull();
        visibility.saveVisible(entries, Set.of());
        assertThat(reopened.hidden().size()).isEqualTo(62);
        assertThat(reopened.hidden().contains("custom-bambu-lab-a1-mini")).isFalse();
        visible.add("bambu-lab-a1-mini");
        reopened.saveVisible(entries, visible);
        assertThat(visibility.hidden().isEmpty()).isTrue();
    }
}
