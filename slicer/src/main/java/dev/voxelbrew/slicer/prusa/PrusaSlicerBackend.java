package dev.voxelbrew.slicer.prusa;

import dev.voxelbrew.api.model.printing.*;
import dev.voxelbrew.api.slicer.SlicerException;
import dev.voxelbrew.engine.printing.*;
import dev.voxelbrew.exporter.printing.PrusaConfiguration;
import dev.voxelbrew.slicer.internal.ProcessRunner;
import dev.voxelbrew.slicer.internal.ProcessSlicer;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class PrusaSlicerBackend implements ProcessSlicer {
    private static final Set<Capability> CAPABILITIES = Set.of(
            Capability.AUTOMATIC_SUPPORTS,
            Capability.BUILD_PLATE_SUPPORTS,
            Capability.SUPPORT_VOLUMES,
            Capability.BRIM,
            Capability.RAFT);

    private final Map<String, String> configuration;

    public static Path defaultExecutable() {
        String configured = System.getProperty("voxelbrew.slicer", System.getenv("VOXELBREW_SLICER"));
        if (configured != null && !configured.isBlank()) return Path.of(configured);
        if (System.getProperty("os.name").startsWith("Windows")) {
            Path local = Path.of(
                    ".tools", "PrusaSlicer-2.7.2", "PrusaSlicer-2.7.2+win64-202402291307", "prusa-slicer-console.exe");
            if (Files.isRegularFile(local)) return local.toAbsolutePath();
            return Path.of("prusa-slicer-console.exe");
        }
        return Path.of("prusa-slicer");
    }

    public PrusaSlicerBackend() {
        this(Map.of());
    }

    private PrusaSlicerBackend(Map<String, String> configuration) {
        this.configuration = Map.copyOf(configuration);
    }

    @Override
    public Map<String, String> configurationOptions() {
        return Map.of("executable", "", "profile", "");
    }

    @Override
    public ProcessSlicer configure(Map<String, String> options) {
        if (!configurationOptions().keySet().containsAll(options.keySet()))
            throw new IllegalArgumentException("Unknown PrusaSlicer option");
        var merged = new TreeMap<>(configuration);
        merged.putAll(options);
        return new PrusaSlicerBackend(merged);
    }

    @Override
    public Path executable() {
        String value = configuration.getOrDefault("executable", "");
        return value.isBlank() ? defaultExecutable() : Path.of(value);
    }

    @Override
    public Descriptor descriptor() {
        return new Descriptor("prusa-slicer", "PrusaSlicer", CAPABILITIES, Set.of("gcode"));
    }

    @Override
    public Probe probe(Path executable, Cancellation cancellation) throws IOException, SlicerException {
        String profile = configuration.getOrDefault("profile", "");
        if (!profile.isBlank() && !Files.isReadable(Path.of(profile)))
            throw new SlicerException.Configuration("Cannot read PrusaSlicer profile: " + profile);
        String help = ProcessRunner.run(List.of(executable.toString(), "--help"), null, cancellation, line -> {});
        var version = help.lines()
                .map(String::strip)
                .filter(line -> line.matches("PrusaSlicer-\\d+\\.\\d+.*"))
                .findFirst()
                .orElseThrow(
                        () -> new SlicerException.Configuration("Executable did not report a PrusaSlicer version"));
        for (var flag : List.of(
                "--export-gcode", "--dont-arrange", "--no-ensure-on-bed", "--load", "--datadir", "--merge", "--output"))
            if (!help.contains(flag))
                throw new SlicerException.Configuration("Slicer is missing required capability " + flag);
        return new Probe(version, CAPABILITIES);
    }

    @Override
    public InputFormat inputFormat() {
        return new InputFormat("prusa-project", "3mf", Map.of("include-support-solids", "true"));
    }

    @Override
    public RunResult slice(Request request) throws IOException, SlicerException {
        var profile = request.model().profile();
        if (!Set.of("PLA", "PETG").contains(profile.material().id()))
            throw new SlicerException.UnsupportedInput(
                    "Unsupported material profile: " + profile.material().id());
        if ((!profile.printer().firmware().equals("marlin2")
                        && !profile.printer().firmware().equals("vendor"))
                || !profile.printer().outputFormat().equals("gcode"))
            throw new SlicerException.UnsupportedInput(
                    "Unsupported printer/output pairing; this backend requires Marlin 2 or vendor hardware with generic text G-code");
        Path project = request.input(), config = request.workingDirectory().resolve("process.ini");
        Files.writeString(config, PrusaConfiguration.ini(request.model()), StandardCharsets.UTF_8);
        var arguments = new ArrayList<String>();
        arguments.add(request.executable().toString());
        String baseProfile = configuration.getOrDefault("profile", "");
        if (!baseProfile.isBlank())
            arguments.addAll(
                    List.of("--load", Path.of(baseProfile).toAbsolutePath().toString()));
        arguments.addAll(List.of(
                "--datadir",
                request.workingDirectory().resolve("configuration").toString(),
                "--load",
                config.toString(),
                "--dont-arrange",
                "--no-ensure-on-bed",
                "--merge",
                "--export-gcode",
                "--output",
                request.output().toString(),
                project.toString()));
        ProcessRunner.run(arguments, request.workingDirectory(), request.cancellation(), request.diagnostics());
        return new RunResult(PrusaConfiguration.settings(request.model()));
    }
}
