package dev.voxelbrew.engine.parameters;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.model.parameters.ParameterSet;
import dev.voxelbrew.api.model.parameters.ParameterUnit;
import org.junit.jupiter.api.Test;

class BuiltinParametersTest {
    @Test
    void reservedMetadataWorksInFormulasAndSurvivesEdits() {
        var p = ParameterSet.empty();
        assertThat(p.allParameters().size()).isEqualTo(12);
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
