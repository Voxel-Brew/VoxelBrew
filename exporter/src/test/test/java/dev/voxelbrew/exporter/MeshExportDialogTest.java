package dev.voxelbrew.exporter;

import static org.assertj.core.api.Assertions.*;

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

        assertThat(errors.size()).isEqualTo(5);
        assertThat(errors.keySet()
                        .containsAll(
                                List.of("destination", "space", "chord-tolerance", "angular-tolerance", "option.mode")))
                .isTrue();
        assertThatThrownBy(() -> dialog.buildSettings(values)).isInstanceOf(IllegalArgumentException.class);
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

        assertThat(settings.selection()).isEqualTo(ExportSelection.design(null));
        assertThat(settings.destination().path()).isEqualTo(path);
        assertThat(settings.destination().overwrite()).isFalse();
        assertThat(settings.options()).isEqualTo(Map.of("mode", "fast"));
        assertThatThrownBy(() -> settings.options().put("mode", "fine"))
                .isInstanceOf(UnsupportedOperationException.class);

        var errors = dialog.validate(Map.of("destination", path.toString(), "option.unknown", "x"));
        assertThat(errors.get("option.unknown")).isEqualTo("Unknown exporter option: option.unknown");
        assertThatThrownBy(() -> dialog.buildSettings(Map.of("destination", path.toString(), "option.unknown", "x")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(dialog.validate(Map.of("destination", path.toString(), "overwrite", "yes"))
                        .containsKey("overwrite"))
                .isTrue();
    }
}
