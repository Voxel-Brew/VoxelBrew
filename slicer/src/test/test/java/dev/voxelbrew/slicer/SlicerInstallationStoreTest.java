package dev.voxelbrew.slicer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.voxelbrew.util.preferences.UserConfigServiceImpl;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SlicerInstallationStoreTest {
    @TempDir
    Path directory;

    @Test
    void providerDefaultsPreserveOlderSettingsAndPreferCurrentValues() throws IOException {
        var configuration = directory.resolve("settings.properties");
        var previous = directory.resolve("config-slicer-cura.properties");
        String preferences =
                "slicer.cura.executable=old\nslicer.cura.profile=machine.ini\nslicer.prusa-slicer.executable=prusa\n";
        String previousDefaults = "executable=previous\ndefinition=machine.json\n";
        Files.writeString(configuration, preferences);
        Files.writeString(previous, previousDefaults);
        var store = new SlicerInstallationStore(new UserConfigServiceImpl(directory, "settings.properties"));

        assertThat(store.providerDefaults("cura"))
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of("executable", "previous", "profile", "machine.ini", "definition", "machine.json"));
        assertThat(directory.resolve("slicers")).doesNotExist();

        store.saveProviderDefaults("cura", Map.of("executable", "current"));
        assertThat(store.providerDefaults("cura"))
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of("executable", "current", "profile", "machine.ini", "definition", "machine.json"));
        assertThat(store.providerDefaults("prusa-slicer")).containsExactlyEntriesOf(Map.of("executable", "prusa"));
        assertThat(Files.readString(configuration)).isEqualTo(preferences);
        assertThat(Files.readString(previous)).isEqualTo(previousDefaults);
    }

    @Test
    void rejectsInvalidProviderIdsWithoutCreatingStorage() {
        var store = new SlicerInstallationStore(new UserConfigServiceImpl(directory, "config.properties"));
        assertThatThrownBy(() -> store.providerDefaults("../outside")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.saveProviderDefaults("../outside", Map.of("executable", "value")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(directory.resolve("slicers")).doesNotExist();
    }

    @Test
    void reservesBundledIdentityEvenWhenItsFilesAreUnavailable() {
        var bundledId = UUID.fromString("35c9a5d1-6c00-44cc-9e4b-2caa6b02ff8b");
        var replacement = new SlicerInstallation(bundledId, "prusa-slicer", "Replacement", "1", "", Map.of());
        assertThat(replacement.bundled()).isTrue();
        var store = new SlicerInstallationStore(new UserConfigServiceImpl(directory, "config.properties"));
        assertThatThrownBy(() -> store.save(replacement))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("read-only");
        assertThatThrownBy(() -> store.remove(bundledId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("read-only");
        assertThatThrownBy(() -> replacement.executionWindow(Map.of("executable", "replacement.exe")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("read-only");
    }

    @Test
    void keepsTheDefaultUntilItIsChangedOrRemoved() throws IOException {
        var store = new SlicerInstallationStore(new UserConfigServiceImpl(directory, "config.properties"));
        assertThat(store.defaultInstallation(List.of())).isEmpty();
        var first = new SlicerInstallation(UUID.randomUUID(), "cura", "C slicer", "1", "", Map.of());
        var second = new SlicerInstallation(UUID.randomUUID(), "cura", "A slicer", "1", "", Map.of());
        var third = new SlicerInstallation(UUID.randomUUID(), "cura", "B slicer", "1", "", Map.of());

        store.save(first);
        store.save(second);
        store.save(third);
        var reopened = new SlicerInstallationStore(new UserConfigServiceImpl(directory, "config.properties"));
        var custom = reopened.load().stream()
                .filter(installation -> !installation.bundled())
                .toList();
        assertThat(reopened.defaultInstallation(custom)).contains(first);
        assertThat(reopened.defaultInstallation(List.of(second, third))).contains(second);

        reopened.setDefaultInstallation(third.id());
        assertThat(store.defaultInstallation(custom)).contains(third);
        reopened.remove(third.id());
        var remaining = reopened.load().stream()
                .filter(installation -> !installation.bundled())
                .toList();
        assertThat(reopened.defaultInstallation(remaining)).contains(second);
        assertThatThrownBy(() -> reopened.setDefaultInstallation(third.id()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(reopened.defaultInstallation(remaining.reversed())).contains(second);
    }

    @Test
    void preservesIndependentVerifiedInstallationsWhenAReplacementFails() throws Exception {
        var configuration = new UserConfigServiceImpl(directory, "config.properties");
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

        reopened.remove(first.id());
        reopened.remove(first.id());
        assertThatThrownBy(() -> reopened.require(first.id().toString())).isInstanceOf(IllegalArgumentException.class);
        assertThat(reopened.load())
                .filteredOn(installation -> !installation.bundled())
                .containsExactly(second);
    }
}
