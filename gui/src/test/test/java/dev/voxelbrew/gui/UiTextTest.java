package dev.voxelbrew.gui;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.i18n.TranslationService;
import dev.voxelbrew.api.model.printing.*;
import dev.voxelbrew.api.model.sketch.*;
import dev.voxelbrew.engine.printing.*;
import dev.voxelbrew.engine.sketch.*;
import dev.voxelbrew.exporter.ExportSelection;
import dev.voxelbrew.util.i18n.TranslationCatalogs;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UiTextTest {
    @TempDir
    Path directory;

    private static void choices(TranslationService t, String prefix, Enum<?>[] values) {
        for (var value : values) {
            assertThat(t.tryTranslate(prefix + value.name()).orElseThrow().isBlank())
                    .isFalse();
        }
    }

    @Test
    void aboutMessagesFormatVersionsAndPolishLegalHelpWithoutAWindow() {
        var en = TranslationCatalogs.forLocale(Locale.ENGLISH, directory);
        var pl = TranslationCatalogs.forLocale(Locale.forLanguageTag("pl"), directory);
        assertThat(en.tryTranslate("about.version", "1.2.3").orElseThrow()).isEqualTo("Version 1.2.3");
        assertThat(pl.tryTranslate("about.version", "1.2.3").orElseThrow()).isEqualTo("Wersja 1.2.3");
        assertThat(en.tryTranslate("about.available", "1.1.0").orElseThrow()).isEqualTo("New version available: 1.1.0");
        assertThat(pl.tryTranslate("about.available", "1.1.0").orElseThrow()).isEqualTo("Dostępna nowa wersja: 1.1.0");
        assertThat(pl.tryTranslate("about.updates").orElseThrow()).isEqualTo("Otwórz aktualizator…");
        assertThat(pl.tryTranslate("help.about-license").orElseThrow().contains("licencję"))
                .isTrue();
    }

    @Test
    void aboutLegalResourcesContainTheBundledLicenseAndNativeNotices() throws Exception {
        for (var entry : Map.of(
                        "LICENSE", "GNU AFFERO GENERAL PUBLIC LICENSE", "THIRD_PARTY_NOTICES.md", "Open CASCADE")
                .entrySet()) {
            try (var stream = AboutDialog.class.getResourceAsStream("legal/" + entry.getKey())) {
                assertThat(stream).as(entry.getKey()).isNotNull();
                assertThat(new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                                .contains(entry.getValue()))
                        .as(entry.getKey())
                        .isTrue();
            }
        }
    }

    @Test
    void everyGuiMessageAndEnumChoiceExistsInBothCatalogs() throws Exception {
        for (var locale : List.of(Locale.ENGLISH, Locale.forLanguageTag("pl"))) {
            var t = TranslationCatalogs.forLocale(locale, directory);
            try (var sources = Files.list(Path.of("src/main/java/dev/voxelbrew/gui"))) {
                for (var file :
                        sources.filter(p -> p.toString().endsWith(".java")).toList()) {
                    var matcher = Pattern.compile("translations\\s*\\.translate\\(\\s*\"([^\"]+)\"\\s*[,)]")
                            .matcher(Files.readString(file));
                    while (matcher.find()) {
                        assertThat(t.tryTranslate(matcher.group(1))
                                        .orElseThrow()
                                        .isBlank())
                                .as(file + ": " + matcher.group(1))
                                .isFalse();
                    }
                }
            }
            choices(t, "constraint.", SketchGeometry.Type.values());
            choices(t, "constraint.hint.", SketchGeometry.Type.values());
            choices(t, "tool.", SketchTools.Tool.values());
            choices(t, "tool.hint.", SketchTools.Tool.values());
            choices(t, "print.role.", GcodeJob.Role.values());
            choices(t, "print.support.", SupportVolume.Role.values());
            choices(t, "print.state.", PrintController.State.values());
            choices(t, "export.space.", ExportSelection.Space.values());
            choices(t, "export.contents.", ExportSelection.Contents.values());
            for (var style : Rendering.Style.values()) {
                String name = style.name().toLowerCase(Locale.ROOT);
                assertThat(t.tryTranslate("rendering." + name).orElseThrow().isBlank())
                        .isFalse();
                assertThat(t.tryTranslate("help.rendering-" + name)
                                .orElseThrow()
                                .isBlank())
                        .isFalse();
            }
        }
    }

    @Test
    void compositeViewNamesTranslateWithoutChangingNavigationIdentifiers() {
        var pl = TranslationCatalogs.forLocale(Locale.forLanguageTag("pl"), directory);
        assertThat(UiText.view(pl, "Top Front Right")).isEqualTo("Góra Przód Prawo");
        assertThat(UiText.view(TranslationCatalogs.forLocale(Locale.ENGLISH, directory), "Top Front Right"))
                .isEqualTo("Top Front Right");
    }

    @Test
    void actionDescriptionsCoverButtonFactoriesAndDynamicSketchActions() throws Exception {
        var ids = new java.util.HashSet<String>();
        try (var sources = Files.list(Path.of("src/main/java/dev/voxelbrew/gui"))) {
            for (var file : sources.filter(p -> p.toString().endsWith(".java")).toList()) {
                var matcher = Pattern.compile(
                                "button\\(\\s*(?:translations\\s*\\.translate\\(\\s*\"[^\"]+\"\\s*\\)\\s*\\.orElseThrow\\(\\)|\"[^\"]*\"),\\s*\"([^\"]+)\"\\s*,")
                        .matcher(Files.readString(file));
                while (matcher.find()) ids.add(matcher.group(1));
            }
        }
        assertThat(ids.size() > 50)
                .as("Audit the actual production action factories")
                .isTrue();
        ids.addAll(List.of(
                "support",
                "timeline-first",
                "timeline-previous",
                "timeline-play",
                "timeline-pause",
                "timeline-next",
                "timeline-last",
                "history-cursor"));
        for (var locale : List.of(Locale.ENGLISH, Locale.forLanguageTag("pl"))) {
            var translations = TranslationCatalogs.forLocale(locale, directory);
            for (var id : ids) {
                assertThat(UiHelp.description(translations, id).isBlank())
                        .as(id)
                        .isFalse();
            }
            for (var draw : SketchEditor.Draw.values()) {
                if (draw != SketchEditor.Draw.ADVANCED) {
                    assertThat(UiHelp.description(
                                            translations,
                                            "sketch-tool-" + draw.name().toLowerCase(Locale.ROOT))
                                    .isBlank())
                            .isFalse();
                }
            }
            for (var type : SketchGeometry.Type.values()) {
                if (!type.dimension()) {
                    var id = type.name().toLowerCase(Locale.ROOT);
                    assertThat(UiHelp.description(translations, "constraint-" + id)
                                    .isBlank())
                            .isFalse();
                    assertThat(UiHelp.description(translations, "constraint-menu-" + id)
                                    .isBlank())
                            .isFalse();
                }
            }
            assertThat(UiHelp.description(translations, "history-52").isBlank()).isFalse();
            assertThat(UiHelp.description(translations, "apply-plugin-width").isBlank())
                    .isFalse();
        }
    }
}
