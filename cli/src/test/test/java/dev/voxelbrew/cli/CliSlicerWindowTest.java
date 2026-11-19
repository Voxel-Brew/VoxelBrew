package dev.voxelbrew.cli;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.model.vui.*;
import dev.voxelbrew.api.slicer.SlicerDialog;
import dev.voxelbrew.util.config.FileUserConfiguration;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliSlicerWindowTest {
    @TempDir
    Path directory;

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

    @Test
    void resolvesInvocationThenSavedThenDeclaredDefaultsAndPersistsPerProvider() throws Exception {
        var configuration = new FileUserConfiguration(directory.resolve("config.properties"));
        var saved = configuration.section("slicer", "one");
        saved.update(Map.of("executable", "saved", "ignored-input", "ignored"), Set.of());
        assertThat(new CliSlicerWindow(configuration, "one", Map.of()).show(DIALOG))
                .contains(Map.of("executable", "saved", "profile", "draft"));
        assertThat(new CliSlicerWindow(configuration, "one", Map.of("executable", "invocation")).show(DIALOG))
                .contains(Map.of("executable", "invocation", "profile", "draft"));
        assertThat(saved.get("executable", "")).isEqualTo("invocation");
        assertThat(new CliSlicerWindow(configuration, "two", Map.of()).show(DIALOG))
                .contains(Map.of("executable", "dialog-default", "profile", "draft"));
    }

    @Test
    void rejectedInputsDoNotReplaceAcceptedDefaults() throws Exception {
        var configuration = new FileUserConfiguration(directory.resolve("config.properties"));
        var saved = configuration.section("slicer", "one");
        saved.update(Map.of("executable", "saved"), Set.of());
        assertThatThrownBy(() -> new CliSlicerWindow(configuration, "one", Map.of("executable", "")).show(DIALOG))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Enter an executable");
        assertThatThrownBy(() -> new CliSlicerWindow(configuration, "one", Map.of("typo", "value")).show(DIALOG))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("typo");
        assertThat(saved.read()).containsExactlyEntriesOf(Map.of("executable", "saved"));
    }
}
