package dev.voxelbrew.gui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.voxelbrew.api.model.parameters.ParameterSet;
import dev.voxelbrew.api.model.parameters.ParameterUnit;
import org.junit.jupiter.api.Test;

class DecimalInputTest {
    @Test
    void parsesEitherDecimalSeparatorInPlainFields() {
        assertThat(DecimalInput.parse(" -0,25e1 ")).isEqualTo(-2.5);
        assertThat(DecimalInput.parse(".25")).isEqualTo(0.25);
        assertThatThrownBy(() -> DecimalInput.parse("1,2,3")).isInstanceOf(NumberFormatException.class);
    }

    @Test
    void evaluatesDecimalCommasWithUnitsAndNestedArithmetic() {
        var parameters = ParameterSet.empty().put("width", ParameterUnit.MM, "5", "");
        String expression = DecimalInput.expression("=($width + 1,5 cm) * sqrt(2,5e1) + ,5 mm");
        assertThat(parameters.value(expression, ParameterUnit.MM)).isEqualTo(100.5);
    }

    @Test
    void preservesLegacyArgumentCommasAndSupportsSemicolonsWithDecimalCommas() {
        var parameters = ParameterSet.empty();
        assertThat(DecimalInput.expression("=max(1,2) + min(3, 4.5)")).isEqualTo("=max(1,2) + min(3, 4.5)");
        String expression = DecimalInput.expression("=max(min(1,2) + 1,5; max(0,5; 2,5) + 0,25)");
        assertThat(parameters.value(expression, ParameterUnit.NUMBER)).isEqualTo(2.75);
    }

    @Test
    void preservesQuotedPunctuationAndDoubledQuotes() {
        String expression = DecimalInput.expression("=\"Say \"\"1,5; 2,5\"\": \" + 2,5");
        assertThat(ParameterSet.empty().text(expression)).isEqualTo("Say \"1,5; 2,5\": 2.5");
    }
}
