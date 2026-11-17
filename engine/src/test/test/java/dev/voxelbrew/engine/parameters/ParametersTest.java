package dev.voxelbrew.engine.parameters;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.model.*;
import dev.voxelbrew.api.model.parameters.Expressions;
import dev.voxelbrew.api.model.parameters.ParameterSet;
import dev.voxelbrew.api.model.parameters.ParameterUnit;
import dev.voxelbrew.engine.model.*;
import java.util.*;
import org.junit.jupiter.api.Test;

@SuppressWarnings("deprecation")
class ParametersTest {
    @Test
    void arithmeticUnitsAndFunctionsKeepDimensions() {
        var p = ParameterSet.empty()
                .put("width", ParameterUnit.MM, "2 in", "")
                .put("height", ParameterUnit.CM, "width / 2", "")
                .put("diagonal", ParameterUnit.MM, "sqrt(width^2 + height^2)", "");
        p.validate();
        assertThat(p.values().get("width").getValue().doubleValue())
                .isCloseTo(50.8, org.assertj.core.data.Offset.offset(1e-10));
        assertThat(p.values().get("height").getValue().doubleValue())
                .isCloseTo(25.4, org.assertj.core.data.Offset.offset(1e-10));
        assertThat(p.value("diagonal", ParameterUnit.MM))
                .isCloseTo(Math.hypot(50.8, 25.4), org.assertj.core.data.Offset.offset(1e-10));
        assertThat(p.value("2 + 3 * 4", ParameterUnit.NUMBER)).isEqualTo(14);
        assertThat(p.value("-2^2", ParameterUnit.NUMBER)).isEqualTo(-4);
        assertThat(p.value("2^3^2", ParameterUnit.NUMBER)).isEqualTo(512);
        assertThat(p.value("sin(30 deg)", ParameterUnit.NUMBER))
                .isCloseTo(.5, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(p.value("pi / 2 rad", ParameterUnit.DEG)).isCloseTo(90, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(p.value("max(3 mm,8 mm)", ParameterUnit.MM)).isEqualTo(8);
        assertThat(p.value("width + 2", ParameterUnit.CM)).isCloseTo(70.8, org.assertj.core.data.Offset.offset(1e-10));
        assertThat(p.value("max(width,10)", ParameterUnit.CM))
                .isCloseTo(100, org.assertj.core.data.Offset.offset(1e-10));
        assertThat(Expressions.rename("1e3 + eValue", "eValue", "renamed")).isEqualTo("1e3 + renamed");
        assertThatThrownBy(() -> p.value("width + 3 deg", ParameterUnit.MM))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> p.value("width", ParameterUnit.DEG)).isInstanceOf(IllegalArgumentException.class);
        for (String nonfinite : List.of("NaN", "Infinity", "-Infinity")) {
            assertThat(org.assertj.core.api.Assertions.catchThrowableOfType(
                                    () -> p.value(nonfinite, ParameterUnit.MM), IllegalArgumentException.class)
                            .getMessage()
                            .contains("finite"))
                    .isTrue();
            assertThatThrownBy(() -> ParameterSet.requireName(nonfinite)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void variantsInheritOverrideCloneAndRenameWithoutLosingReferences() {
        var p = ParameterSet.empty()
                .put("width", ParameterUnit.MM, "60", "plate")
                .put("half", ParameterUnit.MM, "width/2", "")
                .addConfiguration("Large", false)
                .override("width", "Large", "80")
                .bind(new ParameterSet.Target("plate", "width"), "width");
        var id = p.parameter("width").id();
        assertThat(p.values().get("half").getValue().doubleValue()).isEqualTo(30);
        p = p.select("Large");
        assertThat(p.values().get("half").getValue().doubleValue()).isEqualTo(40);
        p = p.addConfiguration("Copy", true).override("width", "Large", "100");
        assertThat(p.select("Copy").values().get("width").getValue().doubleValue())
                .isEqualTo(80);
        p = p.rename("width", "plateWidth");
        p.validate();
        assertThat(p.parameter("plateWidth").id()).isEqualTo(id);
        assertThat(p.parameter("half").expression()).isEqualTo("plateWidth/2");
        assertThat(p.bindings().getFirst().expression()).isEqualTo("plateWidth");
        assertThat(p.values().get("half").getValue().doubleValue()).isEqualTo(50);
        var finalSet = p;
        assertThatThrownBy(() -> finalSet.remove("plateWidth")).isInstanceOf(IllegalArgumentException.class);
        assertThat(p.removeConfiguration("Large").activeName()).isEqualTo("Default");
    }

    @Test
    void cyclesMissingNamesAndBadInactiveOverridesAreDiagnosed() {
        for (String expression : List.of(
                "missing+1", "1/0", "sqrt(-1)", "1e999", "sin(2 mm)", "2 +", "(".repeat(40) + "1" + ")".repeat(40))) {
            var p = ParameterSet.empty().put("width", ParameterUnit.MM, expression, "");
            assertThatThrownBy(p::validate).isInstanceOf(IllegalArgumentException.class);
        }
        var cycle = ParameterSet.empty().put("a", ParameterUnit.MM, "b", "").put("b", ParameterUnit.MM, "a", "");
        assertThat(org.assertj.core.api.Assertions.catchThrowableOfType(cycle::validate, IllegalArgumentException.class)
                        .getMessage()
                        .contains("Cyclic"))
                .isTrue();
        var inactive = ParameterSet.empty()
                .put("width", ParameterUnit.MM, "60", "")
                .addConfiguration("Broken", false)
                .override("width", "Broken", "missing");
        assertThat(org.assertj.core.api.Assertions.catchThrowableOfType(
                                inactive::validate, IllegalArgumentException.class)
                        .getMessage()
                        .contains("Broken"))
                .isTrue();
        assertThatThrownBy(() -> ParameterSet.empty().put("mm", ParameterUnit.MM, "5", ""))
                .isInstanceOf(IllegalArgumentException.class);
        var deep = ParameterSet.empty();
        for (int i = 0; i < 34; i++)
            deep = deep.put("p" + "_".repeat(i), ParameterUnit.MM, i == 33 ? "1" : "p" + "_".repeat(i + 1), "");
        assertThat(org.assertj.core.api.Assertions.catchThrowableOfType(deep::validate, IllegalArgumentException.class)
                        .getMessage()
                        .contains("depth exceeds 32"))
                .isTrue();
    }

    @Test
    void parameterTransactionsFollowUndoButNeverMoveRollbackCursor() {
        var session = new ModelSession(CadDocument.empty());
        session.add(new Feature.Sketch(
                "plate", "profile", new Feature.Plane("origin-xy", 0), new Feature.Rectangle(0, 0, 60, 40)));
        var p = ParameterSet.empty()
                .put("width", ParameterUnit.MM, "60", "")
                .addConfiguration("Large", false)
                .override("width", "Large", "80");
        session.setParameters(p);
        session.bindExpression("plate", "width", "width");
        var beforeBadUnit = session.snapshot();
        assertThatThrownBy(() -> session.setParameters(p.put("width", ParameterUnit.DEG, "60", "")
                        .bind(new ParameterSet.Target("plate", "width"), "width")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(session.snapshot()).isEqualTo(beforeBadUnit);
        session.setCursor(0);
        long before = session.revision();
        session.selectConfiguration("Large");
        assertThat(session.document().cursor()).isEqualTo(0);
        assertThat(session.revision() > before).isTrue();
        assertThat(session.undo()).isTrue();
        assertThat(session.document().parameters().activeName()).isEqualTo("Default");
        assertThat(session.document().cursor()).isEqualTo(0);
        assertThat(session.redo()).isTrue();
        assertThat(session.document().parameters().activeName()).isEqualTo("Large");
        session.editDimension("plate", "width", 70);
        assertThat(session.document().parameters().binding(new ParameterSet.Target("plate", "width")))
                .isEqualTo("");
        assertThat(session.undo()).isTrue();
        assertThat(session.document().parameters().bindings().getFirst().expression())
                .isEqualTo("width");
    }
}
