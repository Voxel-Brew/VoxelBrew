package dev.voxelbrew.gui;

import static org.junit.jupiter.api.Assertions.*;

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
        assertEquals(3000, Workbench.loadNotificationDuration(configuration));
        configuration.set("export.notification.duration-ms", "750");
        assertEquals(750, Workbench.loadNotificationDuration(configuration));
    }

    @Test
    void rejectsInvalidValuesAndFallsBackForInvalidSavedPreference() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> Workbench.parseNotificationDuration("0"));
        assertThrows(IllegalArgumentException.class, () -> Workbench.parseNotificationDuration("1.5"));
        var configuration = new UserConfiguration(directory.resolve("settings.properties"));
        configuration.set("export.notification.duration-ms", "invalid");
        assertEquals(3000, Workbench.loadNotificationDuration(configuration));
    }
}
