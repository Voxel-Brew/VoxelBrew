package consumer;

import dev.voxelbrew.api.model.printing.*;
import dev.voxelbrew.api.slicer.*;
import java.io.IOException;
import java.util.Optional;
import java.util.function.Consumer;

/** Independent API-only discovery fixture. */
public final class ProbeSlicer implements SlicerProvider {
    private static final class ProbeOperation implements Slicer {
        @Override
        public Optional<PrintArtifact> slice(
                ManufacturingModel model, SlicerWindow window, Cancellation cancellation, Consumer<String> diagnostics)
                throws IOException {
            cancellation.check();
            throw new IOException("Fixture slicer does not produce jobs");
        }
    }

    @Override
    public SlicerDescriptor descriptor() {
        return new SlicerDescriptor("probe-slicer", "API fixture slicer", "1");
    }

    @Override
    public Slicer create() {
        return new ProbeOperation();
    }
}
