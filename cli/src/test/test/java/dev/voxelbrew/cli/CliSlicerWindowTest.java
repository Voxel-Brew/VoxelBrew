package dev.voxelbrew.cli;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.model.vui.*;
import dev.voxelbrew.api.slicer.SlicerDialog;
import dev.voxelbrew.slicer.SlicerInstallationStore;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliSlicerWindowTest {
    private static final SlicerDialog<Map<String, String>> DIALOG = new SlicerDialog<>() {
        @Override
        public String title() {
            return "Fixture";
        }

        @Override
        public Layout layout() {
            return new VerticalLayout(
                    new TextField("executable", "Executable", "dialog-default"),
                    new HorizontalLayout(new TextField("profile", "Profile", "draft")));
        }

        @Override
        public Map<String, String> validate(Map<String, String> values) {
            return values.getOrDefault("executable", "").isBlank()
                    ? Map.of("executable", "Enter an executable")
                    : Map.of();
        }

        @Override
        public Map<String, String> buildSettings(Map<String, String> values) {
            return Map.copyOf(values);
        }
    };

    @TempDir
    Path directory;

    @Test
    void resolvesInvocationThenSavedThenDeclaredDefaultsAndPersistsPerProvider() throws Exception {
        var store = new SlicerInstallationStore(
                new dev.voxelbrew.util.preferences.UserConfigServiceImpl(directory, "config.properties"));
        store.saveProviderDefaults("one", Map.of("executable", "saved", "ignored-input", "ignored"));
        assertThat(new CliSlicerWindow(store, "one", Map.of()).show(DIALOG))
                .contains(Map.of("executable", "saved", "profile", "draft"));
        assertThat(new CliSlicerWindow(store, "one", Map.of("executable", "invocation")).show(DIALOG))
                .contains(Map.of("executable", "invocation", "profile", "draft"));
        assertThat(store.providerDefaults("one")).containsEntry("executable", "invocation");
        assertThat(new CliSlicerWindow(store, "two", Map.of()).show(DIALOG))
                .contains(Map.of("executable", "dialog-default", "profile", "draft"));
    }

    @Test
    void rejectedInputsDoNotReplaceAcceptedDefaults() throws Exception {
        var store = new SlicerInstallationStore(
                new dev.voxelbrew.util.preferences.UserConfigServiceImpl(directory, "config.properties"));
        store.saveProviderDefaults("one", Map.of("executable", "saved"));
        assertThatThrownBy(() -> new CliSlicerWindow(store, "one", Map.of("executable", "")).show(DIALOG))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Enter an executable");
        assertThatThrownBy(() -> new CliSlicerWindow(store, "one", Map.of("typo", "value")).show(DIALOG))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("typo");
        assertThat(store.providerDefaults("one")).containsExactlyEntriesOf(Map.of("executable", "saved"));
    }
}
