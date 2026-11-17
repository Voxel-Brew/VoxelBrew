package dev.voxelbrew.engine.parameters;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.model.parameters.Expressions;
import dev.voxelbrew.api.model.parameters.ParameterSet;
import dev.voxelbrew.api.model.parameters.ParameterUnit;
import java.util.List;
import org.junit.jupiter.api.Test;

class FormulaExpressionsTest {
    @Test
    void dottedNamesValidateEvaluateAndRenameAsWholeReferences() {
        for (var name : List.of("x", "x.fo", "x.bo", "Plate.width", "a_b", "a.."))
            assertThatCode(() -> ParameterSet.requireName(name)).doesNotThrowAnyException();
        for (var name : List.of("_x", ".x", "x1", "1x", "vb.width", "vb.", "a-b", "é"))
            assertThatThrownBy(() -> ParameterSet.requireName(name)).isInstanceOf(IllegalArgumentException.class);
        var p = ParameterSet.empty()
                .put("x.fo", ParameterUnit.MM, "10", "")
                .put("x.bo", ParameterUnit.MM, "=$x.fo * 2", "");
        assertThat(p.value("=$x.fo + x.bo", ParameterUnit.MM)).isEqualTo(30);
        var renamed = p.rename("x.fo", "y.width");
        assertThat(renamed.value("=$y.width + x.bo", ParameterUnit.MM)).isEqualTo(30);
        assertThat(Expressions.rename("$x.foobar + $x.fo + 1e-3 + \"x.fo\"", "x.fo", "y.width"))
                .isEqualTo("$x.foobar + $y.width + 1e-3 + \"x.fo\"");
    }

    @Test
    void literalFieldsRemainUsableWhenRepairingBrokenParameters() {
        var broken = ParameterSet.empty().put("width", ParameterUnit.MM, "=$missing", "");
        assertThat(broken.text("repair.vxb")).isEqualTo("repair.vxb");
        assertThat(broken.text("=\"repair\"+\".vxb\"")).isEqualTo("repair.vxb");
        assertThat(broken.value("=2+3", ParameterUnit.MM)).isEqualTo(5);
        assertThatThrownBy(() -> broken.text("=\"Width: \"+$width")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(broken::validate).isInstanceOf(IllegalArgumentException.class);
        assertThat(broken.text(Expressions.literalText("=literal"))).isEqualTo("=literal");
    }

    @Test
    void excelPrefixesReferencesAndTextConcatenationRespectPrecedenceAndUnits() {
        var p = ParameterSet.empty()
                .put("width", ParameterUnit.MM, "=60", "")
                .put("half", ParameterUnit.MM, "=$width/2", "");
        assertThat(p.value("=1+2/3", ParameterUnit.NUMBER))
                .isCloseTo(1 + 2.0 / 3, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(p.value("=$width/2", ParameterUnit.MM)).isEqualTo(30);
        assertThat(p.value("=3 cm + $half", ParameterUnit.MM)).isEqualTo(60);
        assertThat(p.text("=\"Width: \" + 5")).isEqualTo("Width: 5");
        assertThat(p.text("=\"Width: \" + $width")).isEqualTo("Width: 60");
        assertThat(p.text("=\"Half: \" + ($width / 2)")).isEqualTo("Half: 30");
        assertThat(p.text("=1+2+\" mm\"")).isEqualTo("3 mm");
        assertThat(p.text("=\"Width: \"+1+2")).isEqualTo("Width: 12");
        assertThat(p.text("=\"Say \"\"hello\"\"\"")).isEqualTo("Say \"hello\"");
        assertThat(p.text("$width + 5")).isEqualTo("$width + 5");
        assertThat(p.text("=\"=literal\"")).isEqualTo("=literal");
    }

    @Test
    void formulaFailuresAreBoundedAndDoNotCoerceTextIntoNumbers() {
        var p = ParameterSet.empty();
        for (var input : List.of(
                "=",
                "=$",
                "=$missing",
                "=1/0",
                "=1e309",
                "=sqrt(-1)",
                "=1+",
                "==2",
                "=\"unclosed",
                "=\"x\"*2",
                "=sin(\"x\")")) assertThatThrownBy(() -> p.text(input)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> p.value("=\"5\"", ParameterUnit.NUMBER)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> p.value("=2 mm", ParameterUnit.DEG)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> p.text("=" + "(".repeat(40) + "1" + ")".repeat(40)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> p.text("=\"" + "a".repeat(513) + "\"")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void renamingPreservesQuotedTextAndVariantsTrackExplicitReferences() {
        var target = new ParameterSet.Target("sketch", "text:label:content");
        var p = ParameterSet.empty()
                .put("width", ParameterUnit.MM, "5", "")
                .addConfiguration("Wide", false)
                .override("width", "Wide", "8")
                .bind(target, "=\"width $width \" + $width")
                .rename("width", "size");
        p.validate();
        assertThat(p.binding(target)).isEqualTo("=\"width $width \" + $size");
        assertThat(p.text(p.binding(target))).isEqualTo("width $width 5");
        assertThat(p.select("Wide").text(p.binding(target))).isEqualTo("width $width 8");
        assertThatThrownBy(() -> p.remove("size")).isInstanceOf(IllegalArgumentException.class);
        assertThat(Expressions.rename("1e3 + $eValue", "eValue", "renamed")).isEqualTo("1e3 + $renamed");
    }
}
