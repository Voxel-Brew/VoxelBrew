package dev.voxelbrew.exporter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MeshExportDialogTest {
    @Test
    void validationReportsIndependentFieldErrors() {
        var format = new MeshFormat(
                "mesh",
                "Mesh",
                List.of("stl"),
                false,
                List.of(new MeshExportOption(
                        "mode", "Mode", MeshExportOption.Type.CHOICE, "fine", List.of("fine", "fast"))));
        var dialog = new MeshExportDialog(format);
        var values = Map.of(
                "destination", "",
                "space", "unknown",
                "chord-tolerance", "0",
                "angular-tolerance", "181",
                "option.mode", "unknown");

        var errors = dialog.validate(values);

        assertEquals(5, errors.size());
        assertTrue(errors.keySet()
                .containsAll(List.of("destination", "space", "chord-tolerance", "angular-tolerance", "option.mode")));
        assertThrows(IllegalArgumentException.class, () -> dialog.buildSettings(values));
    }

    @Test
    void acceptedSettingsUseDefaultsAndRejectUnknownOptions() {
        var format = new MeshFormat(
                "mesh",
                "Mesh",
                List.of("stl"),
                true,
                List.of(new MeshExportOption(
                        "mode", "Mode", MeshExportOption.Type.CHOICE, "fast", List.of("fast", "fine"))));
        var dialog = new MeshExportDialog(format);
        var path = Path.of("part.stl");
        var settings = dialog.buildSettings(Map.of("destination", path.toString()));

        assertEquals(ExportSelection.design(null), settings.selection());
        assertEquals(path, settings.destination().path());
        assertFalse(settings.destination().overwrite());
        assertEquals(Map.of("mode", "fast"), settings.options());
        assertThrows(
                UnsupportedOperationException.class, () -> settings.options().put("mode", "fine"));

        var errors = dialog.validate(Map.of("destination", path.toString(), "option.unknown", "x"));
        assertEquals("Unknown exporter option: option.unknown", errors.get("option.unknown"));
        assertThrows(
                IllegalArgumentException.class,
                () -> dialog.buildSettings(Map.of("destination", path.toString(), "option.unknown", "x")));
        assertTrue(dialog.validate(Map.of("destination", path.toString(), "overwrite", "yes"))
                .containsKey("overwrite"));
    }
}
