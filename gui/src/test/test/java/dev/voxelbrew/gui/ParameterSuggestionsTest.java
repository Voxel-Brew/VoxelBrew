package dev.voxelbrew.gui;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.model.parameters.ParameterSet;
import dev.voxelbrew.api.model.parameters.ParameterUnit;
import java.util.List;
import org.junit.jupiter.api.Test;

class ParameterSuggestionsTest {
    @Test
    void numericFieldsExcludeStringParametersForEmptyAndPartialReferences() {
        var parameters = ParameterSet.empty().put("width", ParameterUnit.MM, "10", "");
        assertThat(ParameterSuggestions.matches(parameters, "", FormulaField.ExpressionType.NUMBER))
                .isEqualTo(List.of("vb.project.version", "width"));
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
                .isEqualTo(List.of(
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
                        "vb.time",
                        "vb.version",
                        "x.bo",
                        "x.fo",
                        "y.fo"));
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
