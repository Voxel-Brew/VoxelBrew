package dev.voxelbrew.gui;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.util.i18n.TranslationCatalogs;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KeyboardShortcutsTest {
    @TempDir
    Path directory;

    private static String match(KeyCode code, boolean shift, boolean control, boolean alt) {
        return KeyboardShortcuts.match(
                KeyboardShortcuts.Scope.DESIGN,
                new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, control, alt, false),
                null);
    }

    @Test
    void finishSketchLetterPreservesTypingAndModifiedEnter() {
        var binding = KeyboardShortcuts.BINDINGS.stream()
                .filter(candidate -> candidate.id().equals("constraint-finish"))
                .findFirst()
                .orElseThrow();
        var letter = new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.K, false, false, false, false);
        assertThat(binding.matches(letter, false)).isTrue();
        assertThat(binding.matches(letter, true)).isFalse();
        assertThat(KeyboardShortcuts.letter("constraint-finish").orElseThrow()).isEqualTo("K");
        boolean mac = System.getProperty("os.name").startsWith("Mac");
        var enter = new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, !mac, false, mac);
        assertThat(binding.matches(enter, true)).isTrue();
        assertThat(binding.matches(
                        new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.K, false, true, true, false), true))
                .isFalse();
    }

    @Test
    void operationsMenuAllowsActionsWithoutKeyboardBindings() {
        for (var id : List.of("fillet", "chamfer", "shell")) {
            assertThat(KeyboardShortcuts.menuAccelerator(id).isEmpty()).as(id).isTrue();
            assertThat(KeyboardShortcuts.menuAccelerator("menu-" + id).isEmpty())
                    .as(id)
                    .isTrue();
        }
        for (var id : List.of("extrude", "cut", "split-body")) {
            assertThat(KeyboardShortcuts.menuAccelerator(id).orElseThrow()).isEqualTo(KeyboardShortcuts.primary(id));
        }
    }

    @Test
    void menuAcceleratorsPreserveEveryRegisteredShortcutAndMirrorPrefix() {
        for (var binding : KeyboardShortcuts.BINDINGS) {
            assertThat(KeyboardShortcuts.menuAccelerator(binding.id()).orElseThrow())
                    .isEqualTo(binding.keys().getFirst());
            assertThat(KeyboardShortcuts.menuAccelerator("menu-" + binding.id()).orElseThrow())
                    .isEqualTo(binding.keys().getFirst());
        }
    }

    @Test
    void futureMenuActionsCanOmitShortcutsWhileRequiredLookupsRemainStrict() {
        assertThat(KeyboardShortcuts.menuAccelerator("unbound-action").isEmpty())
                .isTrue();
        assertThatThrownBy(() -> KeyboardShortcuts.primary("unbound-action")).isInstanceOf(RuntimeException.class);
    }

    @Test
    void modelingKeysRequireExactModifiersAndDoNotHijackCopyOrAltGr() {
        assertThat(match(KeyCode.K, false, false, false)).isEqualTo("constrained-sketch");
        assertThat(match(KeyCode.R, false, false, false)).isNull();
        assertThat(match(KeyCode.C, false, false, false)).isEqualTo("construction-menu");
        assertThat(match(KeyCode.O, false, false, false)).isEqualTo("operations-menu");
        assertThat(match(KeyCode.O, false, true, false)).isNull();
        assertThat(match(KeyCode.C, false, true, false)).isNull();
        assertThat(match(KeyCode.C, false, true, true)).isNull();
        assertThat(match(KeyCode.C, true, false, false)).isNull();
        assertThat(match(KeyCode.DIGIT1, false, false, false)).isEqualTo("view-top");
        assertThat(match(KeyCode.DIGIT1, false, true, false)).isNull();
    }

    @Test
    void workspaceNumberBindingCoversFutureTabs() {
        boolean mac = System.getProperty("os.name").startsWith("Mac");
        for (var code : List.of(KeyCode.DIGIT1, KeyCode.DIGIT3, KeyCode.DIGIT4, KeyCode.DIGIT9)) {
            var event = new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, !mac, false, mac);
            assertThat(KeyboardShortcuts.match(KeyboardShortcuts.Scope.DOCUMENT, event, null))
                    .isEqualTo("workspace-number");
        }
    }

    @Test
    void rectangleAndCircleShortcutsExistOnlyInsideTheSketchEditor() {
        for (var entry : java.util.Map.of(KeyCode.R, "sketch-tool-rectangle", KeyCode.C, "sketch-tool-circle")
                .entrySet()) {
            assertThat(KeyboardShortcuts.match(
                            KeyboardShortcuts.Scope.SKETCH,
                            new KeyEvent(KeyEvent.KEY_PRESSED, "", "", entry.getKey(), false, false, false, false),
                            null))
                    .isEqualTo(entry.getValue());
        }
    }

    @Test
    void catalogsDescribeEveryBindingAndHaveNoConflictsWithinActiveScopes() {
        for (var scope : KeyboardShortcuts.Scope.values()) {
            var combinations = new HashSet<>();
            for (var binding : KeyboardShortcuts.BINDINGS) {
                if (binding.scope() != scope
                        && !(scope == KeyboardShortcuts.Scope.DESIGN
                                && binding.scope() == KeyboardShortcuts.Scope.DOCUMENT)) {
                    continue;
                }
                for (var key : binding.keys()) {
                    assertThat(combinations.add(key)).as(binding.id()).isTrue();
                }
                for (var language : List.of(Locale.ENGLISH, Locale.forLanguageTag("pl"))) {
                    var translations = TranslationCatalogs.forLocale(language, directory);
                    assertThat(UiHelp.description(translations, binding.id()).isBlank())
                            .isFalse();
                    assertThat(translations
                                    .tryTranslate("shortcuts.section." + scope.name())
                                    .orElseThrow()
                                    .isBlank())
                            .isFalse();
                }
            }
        }
    }
}
