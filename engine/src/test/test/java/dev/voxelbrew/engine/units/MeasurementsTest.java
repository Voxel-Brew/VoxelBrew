package dev.voxelbrew.engine.units;

import static dev.voxelbrew.engine.units.Measurements.*;
import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.engine.mesh.MeshOptions;
import dev.voxelbrew.engine.parameters.*;
import dev.voxelbrew.engine.printing.*;
import java.util.Map;
import javax.measure.MetricPrefix;
import javax.measure.Quantity;
import javax.measure.quantity.*;
import org.junit.jupiter.api.Test;

class MeasurementsTest {
    @Test
    void standardQuantitiesInteroperateAcrossMetricImperialAndDerivedUnits() {
        Quantity<Length> length = quantity(20, CENTIMETRE).add(quantity(1, INCH));
        assertThat(length.to(MILLIMETRE).getValue().doubleValue())
                .isCloseTo(225.4, org.assertj.core.data.Offset.offset(1e-10));
        Quantity<Area> area = quantity(1, INCH).multiply(quantity(1, INCH)).asType(Area.class);
        assertThat(value(area, SQUARE_MILLIMETRE)).isCloseTo(645.16, org.assertj.core.data.Offset.offset(1e-10));
        assertThat(value(quantity(1, CUBIC_CENTIMETRE), CUBIC_MILLIMETRE))
                .isCloseTo(1000, org.assertj.core.data.Offset.offset(1e-9));
        Quantity<Speed> speed = quantity(1, FOOT).divide(quantity(1, SECOND)).asType(Speed.class);
        assertThat(value(speed, MILLIMETRES_PER_SECOND)).isCloseTo(304.8, org.assertj.core.data.Offset.offset(1e-10));
        assertThat(value(quantity(1, HOUR), SECOND)).isEqualTo(3600);
        assertThat(value(quantity(1, KILOGRAM), GRAM)).isEqualTo(1000);
        assertThat(value(quantity(20, PERCENT), ONE)).isCloseTo(.2, org.assertj.core.data.Offset.offset(1e-12));
        assertThatThrownBy(() -> value(length, SECOND)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> quantity(Double.NaN, METRE)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cadKeepsAngleKindWhileJsr385OwnsConversionAndAlgebra() {
        assertThat(value(quantity(180, DEGREE), RADIAN)).isCloseTo(Math.PI, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(degrees(Math.PI / 2)).isCloseTo(90, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(sameKind(DEGREE, ONE)).isFalse();
        assertThatThrownBy(() -> value(quantity(1, RADIAN), ONE)).isInstanceOf(IllegalArgumentException.class);
        var p = ParameterSet.empty()
                .put("angle", ParameterUnit.DEG, "90", "")
                .put("width", ParameterUnit.MM, "2 in", "");
        Quantity<Angle> angle = p.values().get("angle").asType(Angle.class);
        assertThat(angle.to(RADIAN).getValue().doubleValue())
                .isCloseTo(Math.PI / 2, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(p.value("angle / (180 deg)", ParameterUnit.NUMBER))
                .isCloseTo(.5, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(p.value("sqrt(angle^2)", ParameterUnit.DEG))
                .isCloseTo(90, org.assertj.core.data.Offset.offset(1e-12));
        assertThatThrownBy(() -> p.value("angle + width", ParameterUnit.MM))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> p.value("sqrt(width)", ParameterUnit.MM)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> p.value("width^8 * width", ParameterUnit.MM))
                .isInstanceOf(IllegalArgumentException.class);
        Map<String, Quantity<?>> foreign = Map.of("distance", quantity(2, METRE), "scale", quantity(50, PERCENT));
        assertThat(value(Expressions.evaluate("distance * scale", ParameterUnit.MM, foreign::get), MILLIMETRE))
                .isCloseTo(1000, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(ParameterUnit.CM.displayValue(p.values().get("width")))
                .isCloseTo(5.08, org.assertj.core.data.Offset.offset(1e-12));
    }

    @Test
    void typedPrintSettingsConvertOffsetsPercentagesAndLengthAtTheBoundary() {
        var material = new PrintProfile.Material(
                "PLA", quantity(.175, CENTIMETRE), quantity(483.15, KELVIN), quantity(333.15, KELVIN));
        assertThat(material.filamentDiameter()).isCloseTo(1.75, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(material.nozzleTemperature()).isCloseTo(210, org.assertj.core.data.Offset.offset(1e-10));
        assertThat(value(material.bedTemperatureQuantity(), KELVIN))
                .isCloseTo(333.15, org.assertj.core.data.Offset.offset(1e-10));
        assertThat(value(quantity(0, CELSIUS), KELVIN)).isCloseTo(273.15, org.assertj.core.data.Offset.offset(1e-10));
        var process = new ProcessSettings(
                quantity(200, MetricPrefix.MICRO(METRE)),
                2,
                quantity(.2, ONE),
                false,
                false,
                quantity(Math.PI / 4, RADIAN),
                quantity(.02, CENTIMETRE),
                3,
                quantity(.5, CENTIMETRE),
                0);
        assertThat(process.layerHeight()).isCloseTo(.2, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(process.infillPercent()).isCloseTo(20, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(process.supportThreshold()).isCloseTo(45, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(process.brimWidth()).isCloseTo(5, org.assertj.core.data.Offset.offset(1e-12));
        var mesh = new MeshOptions(quantity(20, MetricPrefix.MICRO(METRE)), quantity(Math.PI / 36, RADIAN));
        assertThat(mesh.chordToleranceMm())
                .isCloseTo(MeshOptions.DEFAULT.chordToleranceMm(), org.assertj.core.data.Offset.offset(1e-12));
        assertThat(value(mesh.angularTolerance(), DEGREE)).isCloseTo(5, org.assertj.core.data.Offset.offset(1e-12));
    }

    @Test
    void unitCatalogueKeepsLegacyNamesAndDoesNotReserveAdditionalParameterNames() {
        assertThat(ParameterUnit.MM.name()).isEqualTo("MM");
        assertThat(ParameterUnit.parse("in")).isSameAs(ParameterUnit.IN);
        for (String name : new String[] {"s", "kg", "K", "mmSquared", "degC"}) {
            var p = ParameterSet.empty().put(name, ParameterUnit.MM, "2", "");
            p.validate();
            assertThat(p.value(name, ParameterUnit.MM)).isEqualTo(2);
        }
    }
}
