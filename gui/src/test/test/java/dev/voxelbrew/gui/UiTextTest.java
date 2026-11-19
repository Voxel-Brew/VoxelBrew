package dev.voxelbrew.gui;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.model.printing.*;
import dev.voxelbrew.api.model.sketch.*;
import dev.voxelbrew.engine.printing.*;
import dev.voxelbrew.engine.sketch.*;
import dev.voxelbrew.exporter.ExportSelection;
import dev.voxelbrew.util.i18n.Translations;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class UiTextTest {
    @Test
    void aboutMessagesFormatVersionsAndPolishLegalHelpWithoutAWindow() {
        var en = new Translations(Locale.ENGLISH);
        var pl = new Translations(Locale.forLanguageTag("pl"));
        assertThat(en.text("about.version", "1.2.3")).isEqualTo("Version 1.2.3");
        assertThat(pl.text("about.version", "1.2.3")).isEqualTo("Wersja 1.2.3");
        assertThat(en.text("about.available", "1.1.0")).isEqualTo("New version available: 1.1.0");
        assertThat(pl.text("about.available", "1.1.0")).isEqualTo("Dostępna nowa wersja: 1.1.0");
        assertThat(pl.text("about.updates")).isEqualTo("Otwórz aktualizator…");
        assertThat(pl.text("help.about-license").contains("licencję")).isTrue();
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
            var t = new Translations(locale);
            try (var sources = Files.list(Path.of("src/main/java/dev/voxelbrew/gui"))) {
                for (var file :
                        sources.filter(p -> p.toString().endsWith(".java")).toList()) {
                    var matcher = Pattern.compile("translations\\.text\\(\"([^\"]+)\"\\s*[,)]")
                            .matcher(Files.readString(file));
                    while (matcher.find())
                        assertThat(t.text(matcher.group(1)).isBlank())
                                .as(file + ": " + matcher.group(1))
                                .isFalse();
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
                assertThat(t.text("rendering." + name).isBlank()).isFalse();
                assertThat(t.text("help.rendering-" + name).isBlank()).isFalse();
            }
        }
    }

    @Test
    void compositeViewNamesTranslateWithoutChangingNavigationIdentifiers() {
        var pl = new Translations(Locale.forLanguageTag("pl"));
        assertThat(UiText.view(pl, "Top Front Right")).isEqualTo("Góra Przód Prawo");
        assertThat(UiText.view(new Translations(Locale.ENGLISH), "Top Front Right"))
                .isEqualTo("Top Front Right");
    }

    private static void choices(Translations t, String prefix, Enum<?>[] values) {
        for (var value : values)
            assertThat(t.text(prefix + value.name()).isBlank()).isFalse();
    }

    @Test
    void actionDescriptionsCoverButtonFactoriesAndDynamicSketchActions() throws Exception {
        var ids = new java.util.HashSet<String>();
        try (var sources = Files.list(Path.of("src/main/java/dev/voxelbrew/gui"))) {
            for (var file : sources.filter(p -> p.toString().endsWith(".java")).toList()) {
                var matcher = Pattern.compile(
                                "button\\(\\s*(?:translations\\.text\\(\"[^\"]+\"\\)|\"[^\"]*\"),\\s*\"([^\"]+)\"\\s*,")
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
            var translations = new Translations(locale);
            for (var id : ids)
                assertThat(UiHelp.description(translations, id).isBlank())
                        .as(id)
                        .isFalse();
            for (var draw : SketchEditor.Draw.values()) {
                if (draw != SketchEditor.Draw.ADVANCED)
                    assertThat(UiHelp.description(
                                            translations,
                                            "sketch-tool-" + draw.name().toLowerCase(Locale.ROOT))
                                    .isBlank())
                            .isFalse();
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
