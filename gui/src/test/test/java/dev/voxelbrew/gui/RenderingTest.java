package dev.voxelbrew.gui;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RenderingTest {
    @TempDir
    Path directory;

    @Test
    void remembersStyleWithoutChangingOtherPreferencesAndRecoversFromUnknownValues() throws Exception {
        var configuration = new dev.voxelbrew.util.config.FileUserConfiguration(directory.resolve("config.properties"));
        configuration.set("language", "pl");
        var rendering = new Rendering(configuration);
        assertThat(rendering.load()).isEqualTo(Rendering.Style.SHADED);
        rendering.save(Rendering.Style.TECHNICAL);
        assertThat(new Rendering(configuration).load()).isEqualTo(Rendering.Style.TECHNICAL);
        assertThat(configuration.get("language", "")).isEqualTo("pl");
        configuration.set("rendering.style", "UNSUPPORTED");
        assertThat(rendering.load()).isEqualTo(Rendering.Style.SHADED);
        rendering.save(Rendering.Style.TECHNICAL);
        assertThat(rendering.load()).isEqualTo(Rendering.Style.TECHNICAL);
    }
}
