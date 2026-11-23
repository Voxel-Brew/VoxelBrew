package dev.voxelbrew.engine.parameters;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.model.parameters.ParameterSet;
import dev.voxelbrew.api.model.parameters.ParameterUnit;
import org.junit.jupiter.api.Test;

class BuiltinParametersTest {
    @Test
    void slicerDimensionsFollowProfileEditsUndoAndVxbRoundTrip() throws Exception {
        var session = new dev.voxelbrew.engine.model.ModelSession(dev.voxelbrew.api.model.CadDocument.empty());
        var base = session.document().printSetup();
        var process = new dev.voxelbrew.api.model.printing.ProcessSettings(0.15, 3, 35, false, false, 45, 0.2, 2, 4, 0);
        session.setParameters(session.document()
                .parameters()
                .put("thickness", ParameterUnit.MM, "2 * $vb.slicer.nozzle_diameter + $vb.slicer.layer_height", ""));
        var original = session.document();
        session.setPrintSetup(base.withProfile(base.backendId(), base.printerId(), base.materialId(), process)
                .withNozzle(0.6));
        var restored = dev.voxelbrew.engine.persistence.DocumentCodec.decode(
                dev.voxelbrew.engine.persistence.DocumentCodec.encode(session.document()));
        assertThat(restored.parameters().value("thickness", ParameterUnit.MM)).isCloseTo(1.35, within(1e-9));
        assertThat(restored.parameters().values()).containsKeys("vb.slicer.nozzle_diameter", "vb.slicer.layer_height");
        assertThat(original.parameters().value("thickness", ParameterUnit.MM)).isEqualTo(1.0);
        assertThat(session.undo()).isTrue();
        assertThat(session.document().parameters().value("thickness", ParameterUnit.MM))
                .isEqualTo(1.0);
    }

    @Test
    void reservedMetadataWorksInFormulasAndSurvivesEdits() {
        var p = ParameterSet.empty();
        assertThat(p.allParameters()).extracting(ParameterSet.Parameter::name).doesNotHaveDuplicates();
        assertThat(p.text("=$vb.project.name")).isEqualTo("Untitled");
        assertThat(p.text("=$vb.project.path")).isEqualTo("");
        assertThat(p.text("=$vb.project.folder")).isEqualTo("");
        assertThat(p.text("=$vb.project.filename")).isEqualTo("");
        assertThat(p.allParameters().stream().allMatch(v -> !v.comment().isBlank()))
                .isTrue();
        assertThat(p.text("=$vb.version")).isEqualTo(dev.voxelbrew.util.Version.version());
        java.time.OffsetDateTime.parse(p.text("=$vb.date_time"));
        p.builtins().fileVersion(3);
        var edited = p.put("count", ParameterUnit.NUMBER, "$vb.project.version + 1", "");
        assertThat(edited.value("count", ParameterUnit.NUMBER)).isEqualTo(4);
        assertThat(edited.builtins().fileVersion()).isEqualTo(3);
        assertThatThrownBy(() -> p.put("vb.custom", ParameterUnit.MM, "1", ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> p.remove("vb.version")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> p.override("vb.version", "", "1")).isInstanceOf(IllegalArgumentException.class);
    }
}
