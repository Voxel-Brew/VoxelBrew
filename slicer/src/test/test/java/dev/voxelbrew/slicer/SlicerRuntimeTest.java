package dev.voxelbrew.slicer;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.engine.printing.Cancellation;
import dev.voxelbrew.exporter.printing.*;
import dev.voxelbrew.slicer.api.SlicerRuntime;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SlicerRuntimeTest {
    @TempDir
    Path directory;

    @Test
    void runtimeDiscoversBuiltinsAndIndependentApiOnlyProvider() throws Exception {
        Files.copy(Path.of(System.getProperty("voxelbrew.test.plugin")), directory.resolve("provider.jar"));
        var runtime = SlicerRuntime.load();
        assertThat(runtime).isInstanceOf(DefaultSlicerRuntime.class);
        assertThat(runtime.slicing()).isNotNull();
        assertThat(runtime.defaultSlicerExecutable()).isNotNull();
        try (var catalog = runtime.slicers().open(directory)) {
            assertThat(catalog.require("prusa-slicer")).isNotNull();
            var external = catalog.require("probe-slicer");
            assertThat(external.probe(Path.of("unused"), new Cancellation()).actualVersion())
                    .isEqualTo("fixture");
            assertThat(catalog.diagnostics().isEmpty()).isTrue();
        }
    }
}
