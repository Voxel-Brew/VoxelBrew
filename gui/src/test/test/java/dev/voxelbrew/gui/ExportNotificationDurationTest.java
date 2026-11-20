package dev.voxelbrew.gui;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExportNotificationDurationTest {
    @TempDir
    Path directory;

    @Test
    void defaultsToThreeSecondsAndLoadsConfiguredMilliseconds() throws Exception {
        var configuration = new dev.voxelbrew.util.preferences.UserConfigServiceImpl(directory, "settings.properties");
        assertThat(Workbench.loadNotificationDuration(configuration)).isEqualTo(3000);
        configuration.saveUserConfig("export.notification.duration-ms", "750");
        assertThat(Workbench.loadNotificationDuration(configuration)).isEqualTo(750);
    }

    @Test
    void rejectsInvalidValuesAndFallsBackForInvalidSavedPreference() throws Exception {
        assertThatThrownBy(() -> Workbench.parseNotificationDuration("0")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Workbench.parseNotificationDuration("1.5"))
                .isInstanceOf(IllegalArgumentException.class);
        var configuration = new dev.voxelbrew.util.preferences.UserConfigServiceImpl(directory, "settings.properties");
        configuration.saveUserConfig("export.notification.duration-ms", "invalid");
        assertThat(Workbench.loadNotificationDuration(configuration)).isEqualTo(3000);
    }
}
