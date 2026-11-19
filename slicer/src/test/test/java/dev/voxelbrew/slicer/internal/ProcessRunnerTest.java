package dev.voxelbrew.slicer.internal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.voxelbrew.api.model.printing.Cancellation;
import dev.voxelbrew.api.slicer.SlicerException;
import dev.voxelbrew.slicer.SlicerInputs;
import dev.voxelbrew.slicer.prusa.PrusaSlicerProvider;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProcessRunnerTest {
    @Test
    void rejectsAnExecutableThatRunsButIsNotPrusaSlicer() {
        var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var window = SlicerInputs.window(Map.of("executable", java), Map.of());
        assertThatThrownBy(() -> new PrusaSlicerProvider().checkConfiguration(window, new Cancellation()))
                .isInstanceOf(SlicerException.Configuration.class)
                .hasMessageContaining("did not report a PrusaSlicer version");
    }

    @Test
    void nonzeroProcessExitIsAnExecutionFailure() {
        var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        assertThatThrownBy(() -> ProcessRunner.run(
                        List.of(java, "--voxelbrew-invalid-fixture-option"), null, new Cancellation(), ignored -> {}))
                .isInstanceOf(SlicerException.Execution.class)
                .hasMessageContaining("Slicer exited with")
                .hasMessageContaining("--voxelbrew-invalid-fixture-option");
    }
}
