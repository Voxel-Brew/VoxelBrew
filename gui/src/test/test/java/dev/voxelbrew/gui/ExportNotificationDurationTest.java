package dev.voxelbrew.gui;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.util.config.UserConfiguration;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExportNotificationDurationTest {
    @TempDir
    Path directory;

    @Test
    void defaultsToThreeSecondsAndLoadsConfiguredMilliseconds() throws Exception {
        var configuration = new UserConfiguration(directory.resolve("settings.properties"));
        assertThat(Workbench.loadNotificationDuration(configuration)).isEqualTo(3000);
        configuration.set("export.notification.duration-ms", "750");
        assertThat(Workbench.loadNotificationDuration(configuration)).isEqualTo(750);
    }

    @Test
    void rejectsInvalidValuesAndFallsBackForInvalidSavedPreference() throws Exception {
        assertThatThrownBy(() -> Workbench.parseNotificationDuration("0")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Workbench.parseNotificationDuration("1.5"))
                .isInstanceOf(IllegalArgumentException.class);
        var configuration = new UserConfiguration(directory.resolve("settings.properties"));
        configuration.set("export.notification.duration-ms", "invalid");
        assertThat(Workbench.loadNotificationDuration(configuration)).isEqualTo(3000);
    }
}
