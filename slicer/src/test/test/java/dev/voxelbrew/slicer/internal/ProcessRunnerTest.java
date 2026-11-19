package dev.voxelbrew.slicer.internal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.voxelbrew.api.model.printing.Cancellation;
import dev.voxelbrew.api.slicer.SlicerException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProcessRunnerTest {
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
