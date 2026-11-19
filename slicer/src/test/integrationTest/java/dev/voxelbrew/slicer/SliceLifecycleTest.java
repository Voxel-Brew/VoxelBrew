package dev.voxelbrew.slicer;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.model.mesh.MeshOptions;
import dev.voxelbrew.api.model.printing.*;
import dev.voxelbrew.engine.printing.ManufacturingModels;
import dev.voxelbrew.slicer.cura.CuraSlicerProvider;
import dev.voxelbrew.slicer.prusa.PrusaSlicerProvider;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SliceLifecycleTest {
    @TempDir
    Path directory;

    @Test
    void cancellationKillsRealSlicerAndPreservesDestination() throws Exception {
        var model = ManufacturingModels.evaluate(PrintingTest.reference().document(), MeshOptions.DEFAULT);
        Path output = directory.resolve("preserved job.gcode");
        Files.writeString(output, "original");
        var cancellation = new Cancellation();
        assertThatThrownBy(() -> SlicerTestSupport.slice(
                        new PrusaSlicerProvider(), model, Map.of(), output, true, cancellation, line -> {
                            if (line.contains("Processing triangulated mesh")) cancellation.cancel();
                        }))
                .isInstanceOf(CancellationException.class);
        assertThat(output).hasContent("original");
    }

    @Test
    void rejectsUnsupportedCapabilitiesBeforeProbingExecutable() throws Exception {
        var session = PrintingTest.reference();
        var setup = session.document().printSetup();
        var supports = new ProcessSettings(0.2, 2, 20, true, false, 45, 0.2, 3, 0, 0);
        session.setPrintSetup(setup.withProfile("cura", setup.printerId(), setup.materialId(), supports));
        var model = ManufacturingModels.evaluate(session.document(), MeshOptions.DEFAULT);
        assertThatThrownBy(() -> SliceService.slice(
                        new CuraSlicerProvider(),
                        model,
                        SlicerTestSupport.window(Map.of("definition", "unused.json")),
                        new Cancellation(),
                        line -> {}))
                .isInstanceOf(dev.voxelbrew.api.slicer.SlicerException.UnsupportedInput.class)
                .hasMessageContaining("capabilities");
    }
}
