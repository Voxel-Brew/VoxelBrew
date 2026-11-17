package dev.voxelbrew.gui;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.slicer.api.SlicerRuntime;
import dev.voxelbrew.util.config.UserConfiguration;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SlicerSettingsTest {
    @TempDir
    Path directory;

    @Test
    void savesProvidersSeparatelyAndAppliesUpdatesWithoutChangingCapturedBackend() throws Exception {
        var configuration = new UserConfiguration(directory.resolve("settings.properties"));
        configuration.set("external.setting", "preserved");
        try (var catalog = SlicerRuntime.load().slicers().open(directory)) {
            var settings = new SlicerSettings(catalog, configuration);
            settings.save(Map.of(
                    "prusa-slicer",
                    Map.of("executable", "first-prusa"),
                    "cura",
                    Map.of(
                            "executable",
                            "cura-engine",
                            "definition",
                            "machine.json",
                            "settings",
                            "process.properties")));
            var captured = settings.backend("prusa-slicer");
            settings.save(Map.of("prusa-slicer", Map.of("executable", "second-prusa")));
            assertThat(captured.executable()).isEqualTo(Path.of("first-prusa"));
            assertThat(settings.backend("prusa-slicer").executable()).isEqualTo(Path.of("second-prusa"));
            assertThat(settings.backend("cura").executable()).isEqualTo(Path.of("cura-engine"));
            assertThat(settings.options("cura").get("definition")).isEqualTo("machine.json");
            assertThat(configuration.get("external.setting", "")).isEqualTo("preserved");
            assertThat(configuration.read()).isEqualTo(Map.of("external.setting", "preserved"));
            assertThat(new UserConfiguration(directory.resolve("slicers/prusa-slicer.properties"))
                            .get("executable", ""))
                    .isEqualTo("second-prusa");
            assertThat(new UserConfiguration(directory.resolve("slicers/cura.properties")).get("executable", ""))
                    .isEqualTo("cura-engine");
            assertThat(new SlicerSettings(catalog, configuration).options("cura"))
                    .isEqualTo(settings.options("cura"));
            var before = configuration.read();
            assertThatThrownBy(() -> settings.save(Map.of("cura", Map.of("unknown-option", "bad"))))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(configuration.read()).isEqualTo(before);
        }
    }
}
