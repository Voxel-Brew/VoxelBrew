package dev.voxelbrew.slicer.internal;

import dev.voxelbrew.api.model.printing.Cancellation;
import dev.voxelbrew.api.slicer.SlicerException;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.function.IntConsumer;

/** A checksum-pinned vendor distribution, shared by the desktop and command-line installers. */
public record SlicerDownload(String provider, URI url, String sha256, long size, String executable) {
    public static Optional<SlicerDownload> find(String provider) {
        if (!Set.of("prusa-slicer", "orca").contains(provider)) return Optional.empty();
        String platform = BundledSlicers.platform(System.getProperty("os.name"), System.getProperty("os.arch"));
        String resource = "/dev/voxelbrew/slicer/downloads/" + platform + "/" + provider + ".properties";
        try (var input = SlicerDownload.class.getResourceAsStream(resource)) {
            if (input == null) return Optional.empty();
            var metadata = new Properties();
            metadata.load(input);
            return Optional.of(new SlicerDownload(
                    provider,
                    URI.create(metadata.getProperty("url")),
                    metadata.getProperty("sha256"),
                    Long.parseLong(metadata.getProperty("size")),
                    metadata.getProperty("executable")));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    // Leave room for vendor paths and Win32 process creation in long user profile directories.
    private String cacheKey() {
        return sha256.substring(0, 16);
    }

    public Path installedExecutable(Path directory) {
        return directory.resolve(provider).resolve(cacheKey()).resolve(executable);
    }

    public void install(Path directory, Cancellation cancellation, IntConsumer progress)
            throws IOException, SlicerException {
        Path parent = directory.resolve(provider);
        Path destination = parent.resolve(cacheKey());
        if (Files.isExecutable(installedExecutable(directory))) return;
        cancellation.check();
        Files.createDirectories(parent);
        Path stage = Files.createTempDirectory(parent, ".install-");
        try {
            Path archive = stage.resolve("download");
            var connection = url.toURL().openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            try (var input = connection.getInputStream()) {
                verifyAndCopy(input, archive, cancellation, progress);
            }
            if (executable.endsWith(".exe")) {
                try (var payload = Files.newInputStream(archive)) {
                    BundledSlicers.unzip(payload, stage, cancellation);
                }
            } else {
                Files.setPosixFilePermissions(
                        archive, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
                ProcessRunner.run(
                        List.of(archive.toAbsolutePath().toString(), "--appimage-extract"),
                        stage,
                        cancellation,
                        ignored -> {});
            }
            Files.delete(archive);
            if (!Files.isExecutable(stage.resolve(executable))) {
                throw new IOException("Missing executable for " + provider);
            }
            cancellation.check();
            try {
                Files.move(stage, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException failure) {
                if (!Files.isExecutable(installedExecutable(directory))) throw failure;
            }
        } finally {
            if (Files.exists(stage)) {
                try (var paths = Files.walk(stage)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
                }
            }
        }
    }

    void verifyAndCopy(InputStream input, Path target, Cancellation cancellation, IntConsumer progress)
            throws IOException {
        var hash = digest();
        long received = 0;
        int lastPercent = -1;
        try (var output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
            byte[] buffer = new byte[128 * 1024];
            for (int count; (count = input.read(buffer)) != -1; ) {
                cancellation.check();
                received += count;
                if (received > size) throw new IOException("Unexpected download size for " + provider);
                hash.update(buffer, 0, count);
                output.write(buffer, 0, count);
                int percent = (int) (100 * received / size);
                if (percent != lastPercent) {
                    lastPercent = percent;
                    progress.accept(percent);
                }
            }
        }
        if (received != size || !HexFormat.of().formatHex(hash.digest()).equals(sha256)) {
            throw new IOException("Download checksum mismatch for " + provider);
        }
    }
}
