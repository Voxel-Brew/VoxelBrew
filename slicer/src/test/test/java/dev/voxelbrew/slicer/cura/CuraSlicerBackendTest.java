package dev.voxelbrew.slicer.cura;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.engine.printing.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CuraSlicerBackendTest {
    @TempDir
    Path directory;

    @Test
    void curaSettingsRespectDocumentAndRejectUnsupportedInstructions() throws Exception {
        var setup = PrintSetup.empty();
        var model = new ManufacturingModel("document", "revision", setup, PrintProfile.resolve(setup), Map.of());
        Path file = directory.resolve("cura settings.properties");
        Files.writeString(file, "speed_print=35\nlayer_height=9\nsupport_enable=true\n");
        var backend = (CuraSlicerBackend) new CuraSlicerBackend().configure(Map.of("settings", file.toString()));
        var settings = backend.settings(model);
        assertThat(settings.get("speed_print")).isEqualTo("35");
        assertThat(settings.get("layer_height")).isEqualTo("0.2");
        assertThat(settings.get("support_enable")).isEqualTo("false");
        assertThat(settings.get("center_object")).isEqualTo("false");
        assertThatThrownBy(() -> settings.put("layer_height", "1")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> backend.slice(new dev.voxelbrew.slicer.api.SlicerBackend.Request(
                        model,
                        file,
                        Path.of("unused"),
                        directory,
                        directory.resolve("out.gcode"),
                        new Cancellation(),
                        line -> {})))
                .isInstanceOf(java.io.IOException.class);
    }
}
