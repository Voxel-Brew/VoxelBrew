package dev.voxelbrew.exporter;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class MeshExportOptionTest {
    @Test
    void invalidProviderValuesFailBeforeWriting() {
        var count = new MeshExportOption("count", "Count", MeshExportOption.Type.INTEGER, "2", List.of());
        assertThrows(IllegalArgumentException.class, () -> count.validate("2.5"));
        var scale = new MeshExportOption("scale", "Scale", MeshExportOption.Type.DECIMAL, "1", List.of());
        assertThrows(IllegalArgumentException.class, () -> scale.validate("NaN"));
        assertThrows(IllegalArgumentException.class, () -> scale.validate("Infinity"));
        var choice = new MeshExportOption("mode", "Mode", MeshExportOption.Type.CHOICE, "a", List.of("a", "b"));
        assertThrows(IllegalArgumentException.class, () -> choice.validate("c"));
        assertDoesNotThrow(() -> choice.validate("b"));
    }

    @Test
    void formatRejectsInvalidDefinitionsAndCopiesProviderOptions() {
        var mode = new MeshExportOption("mode", "Mode", MeshExportOption.Type.CHOICE, "fast", List.of("fast"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new MeshExportOption("Bad Key", "Mode", MeshExportOption.Type.STRING, "x", List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new MeshExportOption("enabled", "Enabled", MeshExportOption.Type.BOOLEAN, "yes", List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new MeshExportOption("mode", "Mode", MeshExportOption.Type.CHOICE, "slow", List.of("fast")));
        assertThrows(
                IllegalArgumentException.class,
                () -> new MeshFormat("mesh", "Mesh", List.of("stl"), false, List.of(mode, mode)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new MeshFormat("mesh", "Mesh", List.of("bad.ext"), false, List.of()));

        var extensions = new java.util.ArrayList<>(List.of("stl"));
        var options = new java.util.ArrayList<>(List.of(mode));
        var format = new MeshFormat("mesh", "Mesh", extensions, false, options);
        extensions.add("obj");
        options.clear();
        assertEquals(List.of("stl"), format.extensions());
        assertEquals(List.of(mode), format.options());
    }
}
