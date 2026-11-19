package dev.voxelbrew.slicer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SlicerInstallationStoreTest {
    @TempDir
    Path directory;

    @Test
    void reservesBundledIdentityEvenWhenItsFilesAreUnavailable() {
        var bundledId = UUID.fromString("35c9a5d1-6c00-44cc-9e4b-2caa6b02ff8b");
        var replacement = new SlicerInstallation(bundledId, "prusa-slicer", "Replacement", "1", "", Map.of());
        assertThat(replacement.bundled()).isTrue();
        var store = new SlicerInstallationStore(directory.resolve("config.properties"));
        assertThatThrownBy(() -> store.save(replacement))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("read-only");
        assertThatThrownBy(() -> replacement.executionWindow(Map.of("executable", "replacement.exe")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("read-only");
    }

    @Test
    void preservesIndependentVerifiedInstallationsWhenAReplacementFails() throws Exception {
        var configuration = directory.resolve("config.properties");
        var store = new SlicerInstallationStore(configuration);
        var first = new SlicerInstallation(
                UUID.randomUUID(),
                "prusa-slicer",
                "Prusa A",
                "PrusaSlicer-2.7.2",
                "",
                Map.of("executable", "prusa-a", "profile", "a.ini"));
        var second = new SlicerInstallation(
                UUID.randomUUID(), "prusa-slicer", "Prusa B", "PrusaSlicer-2.8.1", "", Map.of("executable", "prusa-b"));
        store.save(first);
        store.save(second);

        var reopened = new SlicerInstallationStore(configuration);
        assertThat(reopened.load())
                .filteredOn(installation -> !installation.bundled())
                .containsExactly(first, second);
        assertThat(reopened.require(second.id().toString()).version()).isEqualTo("PrusaSlicer-2.8.1");

        var failed = new SlicerInstallation(
                first.id(),
                first.providerId(),
                first.name(),
                "",
                "Executable unavailable",
                Map.of("executable", "missing"));
        assertThatThrownBy(() -> reopened.save(failed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must pass");
        assertThat(reopened.load())
                .filteredOn(installation -> !installation.bundled())
                .containsExactly(first, second);

        var newFailed = new SlicerInstallation(
                UUID.randomUUID(), first.providerId(), "Broken", "", failed.failure(), failed.settings());
        assertThatThrownBy(() -> reopened.save(newFailed)).isInstanceOf(IllegalArgumentException.class);
        assertThat(reopened.load())
                .filteredOn(installation -> !installation.bundled())
                .containsExactly(first, second);
    }
}
