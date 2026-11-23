package dev.voxelbrew.slicer.internal;

import dev.voxelbrew.api.model.printing.Cancellation;
import dev.voxelbrew.api.model.printing.ManufacturingModel;
import dev.voxelbrew.api.slicer.SlicerException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Internal process configuration and invocation for built-in slicers. */
public interface ProcessSlicer {
    enum Capability {
        AUTOMATIC_SUPPORTS,
        BUILD_PLATE_SUPPORTS,
        SUPPORT_VOLUMES,
        BRIM,
        RAFT
    }

    record Descriptor(String id, String name, Set<Capability> capabilities, Set<String> outputFormats) {
        public Descriptor {
            capabilities = Set.copyOf(capabilities);
            outputFormats = Set.copyOf(outputFormats);
        }
    }

    record Probe(String actualVersion, Set<Capability> capabilities) {
        public Probe {
            capabilities = Set.copyOf(capabilities);
        }
    }

    record Request(
            ManufacturingModel model,
            Path input,
            Path executable,
            Path workingDirectory,
            Path output,
            Cancellation cancellation,
            Consumer<String> diagnostics) {
        public Path instanceFile(dev.voxelbrew.api.model.BodyInstance instance, int index) throws IOException {
            var view = dev.voxelbrew.exporter.ModelViews.selectInstance(
                    dev.voxelbrew.exporter.ModelViews.from(model), instance.id());
            var file = workingDirectory.resolve("object-" + index + ".stl");
            var exporter = new dev.voxelbrew.exporter.BinaryStlExporter();
            try (var out = java.nio.file.Files.newOutputStream(file)) {
                exporter.write(view, dev.voxelbrew.api.model.mesh.MeshOptions.DEFAULT, Map.of(), out);
            }
            return file;
        }
    }

    record RunResult(Map<String, String> resolvedSettings, dev.voxelbrew.api.model.printing.PrintArtifact artifact) {
        public RunResult(Map<String, String> resolvedSettings) {
            this(resolvedSettings, null);
        }

        public RunResult {
            resolvedSettings = Map.copyOf(resolvedSettings);
        }
    }

    /** Private input serialization used by this process. */
    record InputFormat(String exporterId, String extension, Map<String, String> options) {
        public InputFormat(String exporterId, String extension) {
            this(exporterId, extension, Map.of());
        }

        public InputFormat {
            options = Map.copyOf(options);
            if (exporterId == null
                    || !exporterId.matches("[a-z0-9][a-z0-9.-]*")
                    || extension == null
                    || !extension.matches("[a-zA-Z0-9]+")) {
                throw new IllegalArgumentException("Invalid slicer input format");
            }
        }
    }

    Descriptor descriptor();

    /** Provider-owned option names and defaults; hosts can render these like file-I/O options. */
    default Map<String, String> configurationOptions() {
        return Map.of();
    }

    /** Return a new configured instance; never mutate a provider used by an active job. */
    default ProcessSlicer configure(Map<String, String> options) {
        if (!options.isEmpty()) throw new IllegalArgumentException("Unsupported slicer options: " + options.keySet());
        return this;
    }

    /** Executable used when the invocation supplies no explicit override. */
    default Path executable() {
        throw new IllegalStateException(
                "Configure an executable for " + descriptor().id());
    }

    default InputFormat inputFormat() {
        return new InputFormat("stl", "stl");
    }

    Probe probe(Path executable, Cancellation cancellation) throws IOException, SlicerException;

    RunResult slice(Request request) throws IOException, SlicerException;
}
