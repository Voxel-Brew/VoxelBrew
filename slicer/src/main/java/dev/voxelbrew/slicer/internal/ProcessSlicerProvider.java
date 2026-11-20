package dev.voxelbrew.slicer.internal;

import dev.voxelbrew.api.model.mesh.MeshOptions;
import dev.voxelbrew.api.model.printing.*;
import dev.voxelbrew.api.model.vui.*;
import dev.voxelbrew.api.slicer.*;
import dev.voxelbrew.exporter.*;
import dev.voxelbrew.exporter.printing.PrusaProjectExporter;
import dev.voxelbrew.importer.GcodeReader;
import dev.voxelbrew.slicer.SliceService;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public abstract class ProcessSlicerProvider implements SlicerProvider {
    private final Supplier<ProcessSlicer> processes;

    @RequiredArgsConstructor
    private static final class SettingsDialog implements SlicerDialog<Map<String, String>> {
        private static final List<String> FIELD_ORDER =
                List.of("executable", "definition", "machine", "process", "filament", "profile", "settings");
        private final ProcessSlicer process;

        @Override
        public String title() {
            return process.descriptor().name();
        }

        @Override
        public Layout layout() {
            var options = process.configurationOptions();
            return new VerticalLayout(FIELD_ORDER.stream()
                    .filter(options::containsKey)
                    .map(id -> {
                        String extension = switch (id) {
                            case "definition", "machine", "process", "filament" -> "json";
                            case "profile" -> "ini";
                            case "settings" -> "properties";
                            default -> "*";
                        };
                        String value = options.get(id);
                        return (Component) new FileSource(id, extension, value.isBlank() ? null : Path.of(value));
                    })
                    .toList());
        }

        @Override
        public Map<String, String> validate(Map<String, String> values) {
            var errors = new LinkedHashMap<String, String>();
            for (var key : process.configurationOptions().keySet()) {
                String value = values.getOrDefault(key, "");
                if (value.isBlank()
                        && Set.of("definition", "machine", "process", "filament")
                                .contains(key)) {
                    errors.put(key, "Enter a path");
                } else if (!value.isBlank()) {
                    try {
                        Path.of(value);
                    } catch (InvalidPathException invalid) {
                        errors.put(key, invalid.getReason());
                    }
                }
            }
            return errors;
        }

        @Override
        public Map<String, String> buildSettings(Map<String, String> values) {
            return Map.copyOf(values);
        }
    }

    private record Scratch(Path directory) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            List<Path> paths;
            try (var walk = Files.walk(directory)) {
                paths = walk.sorted(Comparator.reverseOrder()).toList();
            }
            for (var path : paths) Files.deleteIfExists(path);
        }
    }

    private static PrintArtifact run(
            ProcessSlicer process, ManufacturingModel model, Cancellation cancellation, Consumer<String> diagnostics)
            throws IOException, SlicerException {
        var descriptor = process.descriptor();
        if (!descriptor.outputFormats().contains(model.profile().printer().outputFormat()))
            throw new SlicerException.UnsupportedInput("Slicer cannot produce required printer format "
                    + model.profile().printer().outputFormat());
        var required = EnumSet.noneOf(ProcessSlicer.Capability.class);
        var settings = model.profile().process();
        if (settings.automaticSupports()) required.add(ProcessSlicer.Capability.AUTOMATIC_SUPPORTS);
        if (settings.supportsOnBedOnly()) required.add(ProcessSlicer.Capability.BUILD_PLATE_SUPPORTS);
        if (settings.brimWidth() > 0) required.add(ProcessSlicer.Capability.BRIM);
        if (settings.raftLayers() > 0) required.add(ProcessSlicer.Capability.RAFT);
        if (model.setup().supports().stream().anyMatch(s -> s.role() != SupportVolume.Role.SOLID_SUPPORT))
            required.add(ProcessSlicer.Capability.SUPPORT_VOLUMES);
        if (!descriptor.capabilities().containsAll(required))
            throw new SlicerException.UnsupportedInput(
                    "Slicer does not support requested process capabilities: " + required);
        Path command = command(process);
        var probe = process.probe(command, cancellation);
        if (!probe.capabilities().containsAll(required))
            throw new SlicerException.UnsupportedInput(
                    "Executable does not support requested process capabilities: " + required);
        diagnostics.accept("Backend: " + probe.actualVersion());
        try (var scratch = new Scratch(Files.createTempDirectory("voxelbrew-slice-"))) {
            var format = process.inputFormat();
            Path input = scratch.directory().resolve("input." + format.extension());
            Path raw = scratch.directory().resolve("sliced.gcode");
            MeshExporter serializer = switch (format.exporterId()) {
                case "stl" -> new BinaryStlExporter();
                case "3mf" -> new ThreeMfExporter();
                case "prusa-project" -> new PrusaProjectExporter();
                default -> throw new IllegalStateException("Unsupported slicer input format " + format.exporterId());
            };
            serializer.writeFile(
                    ModelViews.from(model, ExportSelection.Contents.MODEL),
                    new MeshExportDialog.Settings(
                            new ExportSelection(
                                    ExportSelection.Space.BUILD_PLATE, ExportSelection.Contents.MODEL, null, null),
                            MeshOptions.DEFAULT,
                            format.options(),
                            new dev.voxelbrew.api.exporter.FileExportDialog.Destination(input, false),
                            model));
            cancellation.check();
            var run = process.slice(new ProcessSlicer.Request(
                    model, input, command, scratch.directory(), raw, cancellation, diagnostics));
            cancellation.check();
            long limit = run.artifact() == null ? 48L * 1024 * 1024 : 64L * 1024 * 1024;
            try {
                long size = Files.size(raw);
                if (size == 0 || size > limit) throw new SlicerException.InvalidArtifact("Invalid slicer output size");
            } catch (NoSuchFileException missing) {
                throw new SlicerException.InvalidArtifact("Slicer did not produce G-code output", missing);
            }
            var job = GcodeReader.read(raw);
            if (job.modelLayers().isEmpty())
                throw new SlicerException.InvalidArtifact("Slicer output has no identifiable model deposition layers");
            var metadata = new LinkedHashMap<>(SliceService.provenance(model, descriptor.id()));
            metadata.put("backend_version", probe.actualVersion());
            metadata.put("material", model.profile().material().id());
            metadata.put("firmware", model.profile().printer().firmware());
            metadata.put(
                    "format", run.artifact() == null ? "gcode" : run.artifact().format());
            metadata.put("units", "millimeter");
            metadata.put("model_layers", Integer.toString(job.modelLayers().size()));
            metadata.put("hardware_verified", "false");
            new TreeMap<>(run.resolvedSettings()).forEach((key, value) -> metadata.put("process." + key, value));
            if (run.artifact() != null) {
                run.artifact().metadata().forEach(metadata::putIfAbsent);
                cancellation.check();
                return new PrintArtifact(
                        run.artifact().format(),
                        run.artifact().base64(),
                        run.artifact().sha256(),
                        metadata);
            }
            var report = new StringBuilder();
            metadata.forEach((key, value) -> report.append("; VOXELBREW ")
                    .append(key)
                    .append(" = ")
                    .append(value.replace("\r", "\\r").replace("\n", "\\n"))
                    .append('\n'));
            report.append(Files.readString(raw)).append("\n; VOXELBREW complete = true\n");
            cancellation.check();
            return PrintArtifact.of(
                    "gcode", report.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), metadata);
        }
    }

    private static Path command(ProcessSlicer process) {
        Path executable = process.executable();
        return executable.getParent() != null || Files.exists(executable) ? executable.toAbsolutePath() : executable;
    }

    @Override
    public final SlicerDescriptor descriptor() {
        var descriptor = processes.get().descriptor();
        return new SlicerDescriptor(
                descriptor.id(), descriptor.name(), dev.voxelbrew.util.update.BuildVersion.current());
    }

    @Override
    public final Optional<SlicerDialog<?>> settingsDialog() {
        return Optional.of(new SettingsDialog(processes.get()));
    }

    @Override
    public final String checkConfiguration(SlicerWindow window, Cancellation cancellation)
            throws IOException, SlicerException {
        cancellation.check();
        var process = processes.get();
        var settings =
                window.show(new SettingsDialog(process)).orElseThrow(java.util.concurrent.CancellationException::new);
        if (process.configurationOptions().containsKey("executable")
                && settings.getOrDefault("executable", "").isBlank())
            throw new SlicerException.Configuration("Choose a slicer executable");
        var configured = process.configure(settings);
        return configured.probe(command(configured), cancellation).actualVersion();
    }

    @Override
    public final Slicer create() {
        var process = processes.get();
        return (model, window, cancellation, diagnostics) -> {
            cancellation.check();
            if (window instanceof BundledSlicers.Window bundled) {
                try (var scratch = new Scratch(Files.createTempDirectory("voxelbrew-bundled-slicer-"))) {
                    var configured = bundled.configure(process, model, scratch.directory(), cancellation);
                    return Optional.of(run(configured, model, cancellation, diagnostics));
                }
            }
            var settings = window.show(new SettingsDialog(process));
            cancellation.check();
            if (settings.isEmpty()) return Optional.empty();
            return Optional.of(run(process.configure(settings.get()), model, cancellation, diagnostics));
        };
    }
}
