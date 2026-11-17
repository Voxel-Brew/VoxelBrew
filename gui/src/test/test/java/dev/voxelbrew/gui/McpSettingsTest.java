package dev.voxelbrew.gui;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.util.config.UserConfiguration;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpSettingsTest {
    @TempDir
    Path directory;

    @Test
    void configurationRoundTripsWithoutLosingOtherPreferences() throws Exception {
        var store = new UserConfiguration(directory.resolve("config.properties"));
        assertThat(McpSettings.load(store)).isEqualTo(new McpSettings(false, 0, 4, 30, 100));
        store.set("language", "pl");
        var settings = new McpSettings(true, 8042, 8, 15, 200);
        settings.save(store);
        assertThat(McpSettings.load(store)).isEqualTo(settings);
        assertThat(store.get("language", "en")).isEqualTo("pl");
    }

    @Test
    void invalidValuesDoNotReplaceSavedConfiguration() throws Exception {
        var store = new UserConfiguration(directory.resolve("config.properties"));
        new McpSettings(false, 8042, 4, 30, 100).save(store);
        assertThatThrownBy(() -> new McpSettings(true, 80, 4, 30, 100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new McpSettings(true, 8042, 0, 30, 100)).isInstanceOf(IllegalArgumentException.class);
        assertThat(McpSettings.load(store).port()).isEqualTo(8042);
        store.set("mcp.autoStart", "perhaps");
        assertThatThrownBy(() -> McpSettings.load(store)).isInstanceOf(IllegalArgumentException.class);
    }
}
