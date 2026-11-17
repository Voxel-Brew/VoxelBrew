package dev.voxelbrew.exporter;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class MeshExportOptionTest {
    @Test
    void invalidProviderValuesFailBeforeWriting() {
        var count = new MeshExportOption("count", "Count", MeshExportOption.Type.INTEGER, "2", List.of());
        assertThatThrownBy(() -> count.validate("2.5")).isInstanceOf(IllegalArgumentException.class);
        var scale = new MeshExportOption("scale", "Scale", MeshExportOption.Type.DECIMAL, "1", List.of());
        assertThatThrownBy(() -> scale.validate("NaN")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> scale.validate("Infinity")).isInstanceOf(IllegalArgumentException.class);
        var choice = new MeshExportOption("mode", "Mode", MeshExportOption.Type.CHOICE, "a", List.of("a", "b"));
        assertThatThrownBy(() -> choice.validate("c")).isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> choice.validate("b")).doesNotThrowAnyException();
    }

    @Test
    void formatRejectsInvalidDefinitionsAndCopiesProviderOptions() {
        var mode = new MeshExportOption("mode", "Mode", MeshExportOption.Type.CHOICE, "fast", List.of("fast"));
        assertThatThrownBy(() -> new MeshExportOption("Bad Key", "Mode", MeshExportOption.Type.STRING, "x", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                        new MeshExportOption("enabled", "Enabled", MeshExportOption.Type.BOOLEAN, "yes", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                        new MeshExportOption("mode", "Mode", MeshExportOption.Type.CHOICE, "slow", List.of("fast")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MeshFormat("mesh", "Mesh", List.of("stl"), false, List.of(mode, mode)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MeshFormat("mesh", "Mesh", List.of("bad.ext"), false, List.of()))
                .isInstanceOf(IllegalArgumentException.class);

        var extensions = new java.util.ArrayList<>(List.of("stl"));
        var options = new java.util.ArrayList<>(List.of(mode));
        var format = new MeshFormat("mesh", "Mesh", extensions, false, options);
        extensions.add("obj");
        options.clear();
        assertThat(format.extensions()).isEqualTo(List.of("stl"));
        assertThat(format.options()).isEqualTo(List.of(mode));
    }
}
