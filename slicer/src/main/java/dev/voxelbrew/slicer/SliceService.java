package dev.voxelbrew.slicer;

import dev.voxelbrew.api.model.printing.*;
import dev.voxelbrew.api.slicer.*;
import dev.voxelbrew.engine.printing.*;
import dev.voxelbrew.importer.GcodeReader;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

/** Application-side validation and publication for evaluated plate jobs. */
@Slf4j
@UtilityClass
public class SliceService {
    private static final Object PUBLICATION_LOCK = new Object();

    public record Result(GcodeJob job, PrintArtifact artifact) {}

    public static final class OutdatedException extends IllegalStateException {
        @Serial
        private static final long serialVersionUID = 1L;

        public OutdatedException() {
            super("The document changed while slicing");
        }
    }

    private record StagedFile(Path target, Path scratch, Path backup) {
        static StagedFile prepare(Path target, PrintArtifact artifact, boolean overwrite) throws IOException {
            Path filename = target.getFileName();
            Path parent = target.getParent();
            if (filename == null
                    || parent == null
                    || !filename.toString().toLowerCase(Locale.ROOT).endsWith("." + artifact.format())) {
                throw new IllegalArgumentException("Artifact requires a ." + artifact.format() + " destination");
            }
            boolean exists = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
            if (exists && (!overwrite || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS))) {
                throw new FileAlreadyExistsException(
                        target.toString(), null, overwrite ? "Expected a regular file" : "Explicit overwrite required");
            }
            Path scratch = Files.createTempFile(parent, ".voxelbrew-slice-", ".tmp");
            Path backup = null;
            try {
                if (exists) {
                    backup = Files.createTempFile(parent, ".voxelbrew-slice-backup-", ".tmp");
                    Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                }
                Files.write(scratch, artifact.bytes());
                try (var channel = FileChannel.open(scratch, StandardOpenOption.WRITE)) {
                    channel.force(true);
                }
                return new StagedFile(target, scratch, backup);
            } catch (IOException | RuntimeException failure) {
                new StagedFile(target, scratch, backup).cleanup();
                throw failure;
            }
        }

        private static void delete(Path path) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException failure) {
                log.warn("Could not remove temporary slicer output {}", path, failure);
            }
        }

        void publish(boolean overwrite) throws IOException {
            if (overwrite && backup != null) {
                Files.move(scratch, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.createLink(target, scratch);
            }
        }

        void restore() throws IOException {
            if (backup != null) {
                Files.move(backup, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.deleteIfExists(target);
            }
        }

        void cleanup() {
            delete(scratch);
            if (backup != null) delete(backup);
        }
    }

    public static Optional<Result> slice(
            SlicerProvider provider,
            ManufacturingModel model,
            SlicerWindow window,
            Cancellation cancellation,
            Consumer<String> diagnostics)
            throws Exception {
        return slice(provider, model, new PlateFilaments(List.of(), Map.of()), window, cancellation, diagnostics);
    }

    public static Optional<Result> slice(
            SlicerProvider provider,
            ManufacturingModel model,
            PlateFilaments filaments,
            SlicerWindow window,
            Cancellation cancellation,
            Consumer<String> diagnostics)
            throws Exception {
        cancellation.check();
        var validation = PrintValidation.validate(model);
        if (!validation.valid()) {
            throw new SlicerException.UnsupportedInput("Print setup is invalid: " + validation.diagnostics());
        }
        var id = provider.descriptor().id();
        if (!id.equals(model.setup().backendId())) {
            throw new SlicerException.UnsupportedInput("Selected slicer does not match print setup");
        }
        var setup = model.setup();
        var target = setup.workspaceSettings().get(PrintTarget.PRINTER_ID);
        var input = new ManufacturingModel(
                model.documentId(),
                model.revision(),
                setup.withWorkspaceSettings(filaments.metadata()).withPrintTarget(target),
                model.profile(),
                model.meshes());
        var artifact = provider.create().slice(input, window, cancellation, diagnostics);
        cancellation.check();
        if (artifact.isEmpty()) return Optional.empty();
        var result = inspect(input, id, artifact.get());
        cancellation.check();
        return Optional.of(result);
    }

    public static Map<String, String> provenance(ManufacturingModel model, String providerId) {
        var profile = model.profile().printer();
        var target = model.setup().workspaceSettings().getOrDefault(PrintTarget.PRINTER_ID, "");
        return Map.of(
                "document", model.documentId(),
                "revision", model.revision(),
                "backend", providerId,
                "printer", profile.id(),
                "nozzle_mm", Double.toString(profile.nozzleMm()),
                "target_kind", target.isBlank() ? "profile" : "printer",
                "target_id", target.isBlank() ? model.setup().printerId() : target,
                "target_profile", model.setup().printerId());
    }

    public static Result inspect(ManufacturingModel model, String providerId, PrintArtifact artifact)
            throws IOException, SlicerException.InvalidArtifact {
        for (var entry : provenance(model, providerId).entrySet()) {
            if (!entry.getValue().equals(artifact.metadata().get(entry.getKey()))) {
                throw new SlicerException.InvalidArtifact(
                        "Slicer artifact does not match plate provenance: " + entry.getKey());
            }
        }
        if (artifact.metadata().getOrDefault("backend_version", "").isBlank()) {
            throw new SlicerException.InvalidArtifact("Slicer artifact lacks the actual backend version");
        }
        String required = model.profile().printer().outputFormat();
        boolean bambu = model.profile().printer().id().startsWith("bambu-lab-");
        if (bambu
                && required.equals("gcode")
                && artifact.metadata().get("target_kind").equals("printer")) {
            required = "gcode.3mf";
        }
        boolean packageCompatible =
                required.equals("gcode") && artifact.format().equals("gcode.3mf") && bambu;
        if (!artifact.format().equals(required) && !packageCompatible) {
            throw new SlicerException.InvalidArtifact(
                    "Slicer artifact format does not match printer format: " + required);
        }
        byte[] content = switch (artifact.format()) {
            case "gcode" -> artifact.bytes();
            case "gcode.3mf" -> unpack(artifact.bytes());
            default ->
                throw new SlicerException.InvalidArtifact("No inspector for artifact format " + artifact.format());
        };
        GcodeJob job;
        try {
            StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(content));
            job = GcodeReader.read(new ByteArrayInputStream(content));
        } catch (IOException invalid) {
            throw new SlicerException.InvalidArtifact("Cannot read the artifact's G-code", invalid);
        }
        if (job.modelLayers().isEmpty()) {
            throw new SlicerException.InvalidArtifact("Slicer artifact has no identifiable model deposition layers");
        }
        return new Result(job, artifact);
    }

    private static byte[] unpack(byte[] bytes) throws IOException, SlicerException.InvalidArtifact {
        Path packageFile = Files.createTempFile("voxelbrew-inspect-", ".gcode.3mf");
        try {
            Files.write(packageFile, bytes);
            try (var zip = new ZipFile(packageFile.toFile())) {
                var entry = zip.getEntry("Metadata/plate_1.gcode");
                if (entry == null || entry.isDirectory()) {
                    throw new SlicerException.InvalidArtifact("Slicer package lacks plate 1 G-code");
                }
                int limit = 64 * 1024 * 1024;
                if (entry.getSize() < 1 || entry.getSize() > limit) {
                    throw new SlicerException.InvalidArtifact("Invalid packaged G-code size");
                }
                try (var input = zip.getInputStream(entry)) {
                    var content = input.readNBytes(limit + 1);
                    if (content.length != entry.getSize()) {
                        throw new SlicerException.InvalidArtifact("Invalid packaged G-code size");
                    }
                    return content;
                }
            }
        } catch (ZipException invalid) {
            throw new SlicerException.InvalidArtifact("Cannot read the sliced 3MF package", invalid);
        } finally {
            StagedFile.delete(packageFile);
        }
    }

    /** Publishes inspected artifacts and attaches them within the caller's revision and cancellation guard. */
    public static void publish(Map<Path, PrintArtifact> artifacts, boolean overwrite, Cancellation.Publication attach)
            throws IOException {
        synchronized (PUBLICATION_LOCK) {
            var files = new LinkedHashMap<Path, StagedFile>();
            var published = new ArrayList<StagedFile>();
            try {
                for (var entry : artifacts.entrySet()) {
                    var target = entry.getKey().toAbsolutePath().normalize();
                    if (files.containsKey(target)) {
                        throw new IllegalArgumentException("Duplicate artifact destination: " + target);
                    }
                    files.put(target, StagedFile.prepare(target, entry.getValue(), overwrite));
                }
                for (var file : files.values()) {
                    file.publish(overwrite);
                    published.add(file);
                }
                attach.run();
            } catch (IOException | RuntimeException failure) {
                for (var file : published.reversed()) {
                    try {
                        file.restore();
                    } catch (IOException rollback) {
                        failure.addSuppressed(rollback);
                    }
                }
                throw failure;
            } finally {
                files.values().forEach(StagedFile::cleanup);
            }
        }
    }

    public static void publish(
            Map<Path, PrintArtifact> artifacts,
            boolean overwrite,
            Cancellation cancellation,
            Cancellation.Publication checkRevision,
            Cancellation.Publication attach)
            throws IOException {
        synchronized (PUBLICATION_LOCK) {
            cancellation.publish(() -> {
                checkRevision.run();
                publish(artifacts, overwrite, attach);
            });
        }
    }

    public static void save(PrintArtifact artifact, Path destination, boolean overwrite) throws IOException {
        publish(Map.of(destination, artifact), overwrite, () -> {});
    }
}
