package dev.voxelbrew.gui;

import static org.assertj.core.api.Assertions.assertThat;

import dev.voxelbrew.util.preferences.UserConfigServiceImpl;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppearanceTest {
    @TempDir
    Path directory;

    @Test
    void savesAppearanceAndPreservesUnrelatedConfiguration() {
        var config = new UserConfigServiceImpl(directory, "config.properties");
        config.saveUserConfig("language", "pl");
        var appearance = new Appearance(config);
        appearance.save(Appearance.Choice.LIGHT);
        var reopened = new Appearance(new UserConfigServiceImpl(config.homeFolder(), "config.properties"));
        assertThat(reopened.load()).isEqualTo(Appearance.Choice.LIGHT);
        assertThat(config.readUserConfig()).containsEntry("language", "pl");
    }

    @Test
    void missingOrInvalidPreferenceUsesSystemAndAllowsRepair() {
        var config = new UserConfigServiceImpl(directory, "config.properties");
        var appearance = new Appearance(config);
        assertThat(appearance.load()).isEqualTo(Appearance.Choice.SYSTEM);
        assertThat(config.homeFolder().resolve("config.properties")).doesNotExist();
        config.saveUserConfig("appearance", "UNKNOWN");
        assertThat(appearance.load()).isEqualTo(Appearance.Choice.SYSTEM);
        appearance.save(Appearance.Choice.DARK);
        assertThat(appearance.load()).isEqualTo(Appearance.Choice.DARK);
    }
}
