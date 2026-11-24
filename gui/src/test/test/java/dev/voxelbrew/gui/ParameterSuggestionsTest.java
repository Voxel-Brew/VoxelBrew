package dev.voxelbrew.gui;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.model.parameters.ParameterSet;
import dev.voxelbrew.api.model.parameters.ParameterUnit;
import java.util.List;
import org.junit.jupiter.api.Test;

class ParameterSuggestionsTest {
    @Test
    void quoteStateUsesTheExactInsertionBoundary() {
        assertThat(ParameterSuggestions.insideQuotedString("=\"\"", 2)).isTrue();
        assertThat(ParameterSuggestions.insideQuotedString("=\"abc\"", 1)).isFalse();
        assertThat(ParameterSuggestions.insideQuotedString("=\"abc\"", 6)).isFalse();
        assertThat(ParameterSuggestions.insideQuotedString("=\"a\"\"b\"", 4)).isTrue();
    }

    @Test
    void freezesOnlyReferencingSketchTextAtTheActiveConfigurationValue() {
        var translations = dev.voxelbrew.util.services.Services.load(dev.voxelbrew.api.i18n.TranslationService.class);
        var target = new ParameterSet.Target("sketch-20", "text:text-4:content");
        var other = new ParameterSet.Target("sketch-20", "text:text-5:content");
        String formula = "=\"Wymiar: \"\"\" + $przeniesienie + \" mm\"\"\"";
        var set = ParameterSet.empty()
                .put("przeniesienie", ParameterUnit.CM, "5", "")
                .addConfiguration("Variant", false)
                .override("przeniesienie", "Variant", "7")
                .select("Variant")
                .bind(target, formula)
                .bind(other, "=\"$przeniesienie\"");
        var frozen = ParameterDialog.freezeSketchText(set, "przeniesienie");
        var result = ParameterDialog.removeParameter(frozen, "przeniesienie", translations);
        assertThat(result.parameters()).isEmpty();
        assertThat(result.text(result.binding(target))).isEqualTo("Wymiar: \"70 mm\"");
        assertThat(result.select("Default").text(result.binding(target))).isEqualTo("Wymiar: \"70 mm\"");
        assertThat(result.binding(other)).isEqualTo(set.binding(other));
        assertThat(set.binding(target)).isEqualTo(formula);

        var dimension = new ParameterSet.Target("extrude-1", "distance");
        var mixed = set.bind(dimension, "=$przeniesienie");
        assertThatThrownBy(() -> ParameterDialog.removeParameter(
                        ParameterDialog.freezeSketchText(mixed, "przeniesienie"), "przeniesienie", translations))
                .hasMessageContaining(dimension.toString());
    }

    @Test
    void removalIdentifiesTheSketchTextFormulaAndSucceedsOnceItIsEdited() {
        var translations = dev.voxelbrew.util.services.Services.load(dev.voxelbrew.api.i18n.TranslationService.class);
        var target = new ParameterSet.Target("sketch-20", "text:text-4:content");
        String formula = "=\"Wymiar: \" + $przeniesienie +\" mm\"";
        var set = ParameterSet.empty()
                .put("przeniesienie", ParameterUnit.CM, "5", "")
                .bind(target, formula);
        assertThatThrownBy(() -> ParameterDialog.removeParameter(set, "przeniesienie", translations))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(translations.translate("parameters.removeReferenced", "przeniesienie", target, formula));

        var repaired = set.bind(target, "=\"$przeniesienie\"");
        assertThat(ParameterDialog.removeParameter(repaired, "przeniesienie", translations)
                        .parameters())
                .isEmpty();
    }

    @Test
    void desktopResourcesSupplyTheVersionUsedBySketchParameters() throws Exception {
        var guiResource = ParameterDialog.class.getResource("workbench.css");
        var metadata = guiResource.toURI().resolve("../../../META-INF/voxelbrew/application.properties");
        var properties = new java.util.Properties();
        try (var input = metadata.toURL().openStream()) {
            properties.load(input);
        }
        assertThat(properties.getProperty("version"))
                .isEqualTo(dev.voxelbrew.util.update.BuildVersion.current())
                .isEqualTo(ParameterSet.empty().text("=$vb.version"));
    }

    @Test
    void numericFieldsExcludeStringParametersForEmptyAndPartialReferences() {
        var parameters = ParameterSet.empty().put("width", ParameterUnit.MM, "10", "");
        assertThat(ParameterSuggestions.matches(parameters, "", FormulaField.ExpressionType.NUMBER))
                .contains("vb.project.version", "vb.slicer.nozzle_diameter", "vb.slicer.layer_height", "width")
                .doesNotContain("vb.version", "vb.project.name");
        assertThat(ParameterSuggestions.matches(parameters, "vb.project.", FormulaField.ExpressionType.NUMBER))
                .isEqualTo(List.of("vb.project.version"));
        assertThat(ParameterSuggestions.matches(parameters, "vb.os.", FormulaField.ExpressionType.NUMBER))
                .isEqualTo(List.of());
        assertThat(ParameterSuggestions.matches(parameters, "vb.project.", FormulaField.ExpressionType.STRING)
                        .contains("vb.project.name"))
                .isTrue();
    }

    @Test
    void completesNumericBuiltinInsideArithmetic() {
        var parameters = ParameterSet.empty();
        String formula = "=$vb.project.ver+1";
        var reference = ParameterSuggestions.reference(formula, formula.indexOf('+'));
        assertThat(reference).isNotNull();
        assertThat(ParameterSuggestions.matches(parameters, reference.prefix()))
                .isEqualTo(List.of("vb.project.version"));
        String completed =
                formula.substring(0, reference.start()) + "$vb.project.version" + formula.substring(reference.end());
        assertThat(parameters.value(completed, ParameterUnit.NUMBER)).isEqualTo(1);
    }

    @Test
    void completesBuiltinsInStringFormulasWithoutUserParameters() {
        var parameters = ParameterSet.empty();
        String formula = "=\"D:\\repos\\VoxelBrew\\build\\Untitled-1\"+$vb.project.version";
        var reference = ParameterSuggestions.reference(formula, formula.length());
        assertThat(reference).isNotNull();
        assertThat(ParameterSuggestions.matches(parameters, reference.prefix()))
                .isEqualTo(List.of("vb.project.version"));
        assertThat(ParameterSuggestions.matches(parameters, "vb.project."))
                .isEqualTo(List.of(
                        "vb.project.filename",
                        "vb.project.folder",
                        "vb.project.name",
                        "vb.project.path",
                        "vb.project.version"));
        assertThat(ParameterSuggestions.matches(parameters, "").contains("vb.version"))
                .isTrue();
        assertThat(ParameterSuggestions.matches(parameters, "VB.")).isEqualTo(List.of());
    }

    @Test
    void dottedReferencesCompleteAndTableGroupsByFirstPrefix() {
        assertThat(ParameterSuggestions.reference("=$x.fo", 5))
                .isEqualTo(new ParameterSuggestions.Reference(1, 6, "x.f"));
        var p = ParameterSet.empty()
                .put("x.fo", ParameterUnit.MM, "1", "")
                .put("y.fo", ParameterUnit.MM, "2", "")
                .put("x.bo", ParameterUnit.MM, "3", "")
                .put("width", ParameterUnit.MM, "4", "");
        assertThat(ParameterSuggestions.matches(p, "x.")).isEqualTo(List.of("x.bo", "x.fo"));
        assertThat(ParameterDialog.groupedParameters(p).stream()
                        .map(ParameterSet.Parameter::name)
                        .toList())
                .containsSubsequence(
                        "width",
                        "vb.date",
                        "vb.date_time",
                        "vb.os.arch",
                        "vb.os.name",
                        "vb.os.version",
                        "vb.project.filename",
                        "vb.project.folder",
                        "vb.project.name",
                        "vb.project.path",
                        "vb.project.version",
                        "vb.slicer.layer_height",
                        "vb.slicer.nozzle_diameter",
                        "vb.time",
                        "vb.version",
                        "x.bo",
                        "x.fo",
                        "y.fo");
    }

    @Test
    void findsOnlyTheReferenceAtTheCaretAndReplacesItsEntireToken() {
        assertThat(ParameterSuggestions.reference("=$", 2)).isEqualTo(new ParameterSuggestions.Reference(1, 2, ""));
        assertThat(ParameterSuggestions.reference("=($fooBar/2)+$width", 6))
                .isEqualTo(new ParameterSuggestions.Reference(2, 9, "foo"));
        assertThat(ParameterSuggestions.reference("=($fooBar/2)+$width", 19))
                .isEqualTo(new ParameterSuggestions.Reference(13, 19, "width"));
        assertThat(ParameterSuggestions.reference("=$foo/2", 7)).isNull();
        assertThat(ParameterSuggestions.reference("=$1bad", 6)).isNull();
        assertThat(ParameterSuggestions.reference("=foo", 4)).isNull();
        assertThat(ParameterSuggestions.reference("=$foo", -1)).isNull();
        assertThat(ParameterSuggestions.reference("=$foo", 6)).isNull();
    }

    @Test
    void ignoresQuotedTextIncludingEscapedQuotesButFindsFollowingReferences() {
        assertThat(ParameterSuggestions.reference("=\"price $foo\"", 12)).isNull();
        assertThat(ParameterSuggestions.reference("=\"say \"\"$foo", 12)).isNull();
        String text = "=\"say \"\"hello\"\"\" + $foo";
        assertThat(ParameterSuggestions.reference(text, text.length()).prefix()).isEqualTo("foo");
        assertThat(ParameterSuggestions.reference("$", 1).prefix()).isEqualTo("");
    }

    @Test
    void filtersByCaseSensitivePrefixRatherThanSubstringAndUsesCurrentNames() {
        var parameters = ParameterSet.empty()
                .put("food", ParameterUnit.MM, "20", "")
                .put("foo", ParameterUnit.MM, "10", "")
                .put("Foo", ParameterUnit.MM, "10", "")
                .put("afoo", ParameterUnit.MM, "30", "");
        assertThat(ParameterSuggestions.matches(parameters, "foo")).isEqualTo(List.of("foo", "food"));
        var allMatches = ParameterSuggestions.matches(parameters, "");
        assertThat(allMatches.stream().filter(name -> !name.startsWith("vb.")).toList())
                .isEqualTo(List.of("Foo", "afoo", "foo", "food"));
        assertThat(allMatches.contains("vb.project.version")).isTrue();
        assertThat(ParameterSuggestions.matches(parameters, "missing")).isEqualTo(List.of());
        assertThat(ParameterSuggestions.matches(parameters.put("foot", ParameterUnit.MM, "40", ""), "foo"))
                .isEqualTo(List.of("foo", "food", "foot"));
    }
}
