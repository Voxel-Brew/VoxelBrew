package dev.voxelbrew.gui;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.model.printing.Cancellation;
import dev.voxelbrew.api.slicer.SlicerBackend;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SlicerAvailabilityTest {
    @Test
    void reportsActualVersionInsteadOfAdvertisedVersion() throws Exception {
        var result = SlicerAvailability.check(backend("Installed 3.4.5", false));
        assertThat(result.working()).isTrue();
        assertThat(result.detail()).isEqualTo("Installed 3.4.5");
    }

    @Test
    void rejectsFailedProbeAndMissingVersion() {
        assertThatThrownBy(() -> SlicerAvailability.check(backend("3.4.5", true)))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> SlicerAvailability.check(backend("", false))).isInstanceOf(IOException.class);
    }

    private SlicerBackend backend(String version, boolean fails) {
        return new SlicerBackend() {
            public Descriptor descriptor() {
                return new Descriptor("test", "Test", API_VERSION, "advertised", Set.of(), Set.of("gcode"));
            }

            public Path executable() {
                return Path.of("configured-executable");
            }

            public Probe probe(Path executable, Cancellation cancellation) throws IOException {
                assertThat(executable).isEqualTo(executable());
                if (fails) throw new IOException("Executable is unavailable");
                return new Probe(version, Set.of());
            }

            public RunResult slice(Request request) {
                throw new AssertionError("Availability must not slice");
            }
        };
    }
}
