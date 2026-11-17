package dev.voxelbrew.gui;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.util.config.UserConfiguration;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppearanceTest {
    @TempDir
    Path directory;

    @Test
    void migratesExistingPreferenceOnceAndPreservesUnrelatedConfiguration() throws Exception {
        var config = new UserConfiguration(directory.resolve("config.properties"));
        config.set("language", "pl");
        var appearance = new Appearance(config, () -> "DARK");
        assertThat(appearance.load()).isEqualTo(Appearance.Choice.DARK);
        assertThat(config.get("appearance", "")).isEqualTo("DARK");
        appearance.save(Appearance.Choice.LIGHT);
        var reopened = new Appearance(new UserConfiguration(config.path()), () -> {
            fail("Legacy preference should not be consulted again");
            return null;
        });
        assertThat(reopened.load()).isEqualTo(Appearance.Choice.LIGHT);
        assertThat(config.get("language", "")).isEqualTo("pl");
    }

    @Test
    void missingOrInvalidPreferenceUsesSystemAndAllowsRepair() throws Exception {
        var config = new UserConfiguration(directory.resolve("config.properties"));
        var appearance = new Appearance(config, () -> null);
        assertThat(appearance.load()).isEqualTo(Appearance.Choice.SYSTEM);
        config.set("appearance", "UNKNOWN");
        assertThat(appearance.load()).isEqualTo(Appearance.Choice.SYSTEM);
        appearance.save(Appearance.Choice.DARK);
        assertThat(appearance.load()).isEqualTo(Appearance.Choice.DARK);
    }
}
