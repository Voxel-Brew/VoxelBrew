package dev.voxelbrew.slicer.internal;

import dev.voxelbrew.api.model.printing.Cancellation;
import dev.voxelbrew.api.model.printing.ManufacturingModel;
import dev.voxelbrew.api.slicer.*;
import dev.voxelbrew.slicer.SlicerInstallation;
import dev.voxelbrew.slicer.orca.BundledOrcaPresets;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipInputStream;
import lombok.experimental.UtilityClass;

/** Installation identities are stable across app updates and independent of the user's configuration. */
@UtilityClass
public class BundledSlicers {
    private static final String PLATFORM = platform(System.getProperty("os.name"), System.getProperty("os.arch"));
    private static final String RESOURCES = "/dev/voxelbrew/slicer/bundled/" + PLATFORM + "/";
    private static final List<Bundle> BUNDLES = List.of(
            new Bundle("35c9a5d1-6c00-44cc-9e4b-2caa6b02ff8b", "prusa-slicer", "PrusaSlicer", "2.7.2"),
            new Bundle("f5893e13-b7d8-45bc-92c4-73ff47be535f", "orca", "OrcaSlicer", "2.5.0"));

    public static boolean reserved(UUID id) {
        return BUNDLES.stream().anyMatch(bundle -> bundle.id().equals(id));
    }

    public static List<SlicerInstallation> installations() {
        if (PLATFORM.isEmpty()) return List.of();
        return BUNDLES.stream()
                .filter(bundle -> BundledSlicers.class.getResource(bundle.descriptor()) != null)
                .map(bundle -> new SlicerInstallation(
                        bundle.id(), bundle.provider(), bundle.name(), bundle.version(), "", Map.of()))
                .toList();
    }

    static String platform(String os, String architecture) {
        boolean x64 = Set.of("amd64", "x86_64").contains(architecture);
        if (os.startsWith("Mac") && (x64 || Set.of("arm64", "aarch64").contains(architecture))) return "macos";
        if (x64 && os.startsWith("Windows")) return "windows";
        if (x64 && os.equals("Linux")) return "linux";
        return "";
    }

    public static SlicerWindow window(UUID id) {
        return new Window(BUNDLES.stream()
                .filter(bundle -> bundle.id().equals(id))
                .findFirst()
                .orElseThrow());
    }

    public static final class Window implements SlicerWindow {
        private final Bundle bundle;

        private Window(Bundle bundle) {
            this.bundle = bundle;
        }

        @Override
        public <T> Optional<T> show(SlicerDialog<T> dialog) {
            throw new IllegalStateException("Bundled slicer settings are managed by VoxelBrew");
        }

        ProcessSlicer configure(
                ProcessSlicer process, ManufacturingModel model, Path scratch, Cancellation cancellation)
                throws IOException, SlicerException {
            if (!bundle.provider().equals(process.descriptor().id()))
                throw new SlicerException.Configuration("Selected bundle belongs to another slicer provider");
            var properties = new Properties();
            try (var descriptor = BundledSlicers.class.getResourceAsStream(bundle.descriptor())) {
                if (descriptor == null)
                    throw new SlicerException.Configuration("Bundled " + bundle.name() + " is unavailable");
                properties.load(descriptor);
            }
            String archive = properties.getProperty("archive", "");
            if (!archive.equals(bundle.provider() + ".zip") && !archive.equals(bundle.provider() + ".tar.gz"))
                throw new SlicerException.Configuration("Invalid archive descriptor for bundled " + bundle.name());
            Path executable = inside(scratch, properties.getProperty("executable", ""));
            try (var payload = BundledSlicers.class.getResourceAsStream(RESOURCES + archive)) {
                if (payload == null)
                    throw new SlicerException.Configuration("Bundled " + bundle.name() + " archive is missing");
                if (archive.endsWith(".zip")) {
                    unzip(payload, scratch, cancellation);
                } else {
                    Path file = copy(payload, scratch.resolve(archive), cancellation);
                    ProcessRunner.run(
                            List.of("tar", "-xf", file.toString(), "-C", scratch.toString()),
                            scratch,
                            cancellation,
                            line -> {});
                    Files.delete(file);
                }
            }
            if (!Files.isExecutable(executable))
                throw new SlicerException.Configuration("Bundled " + bundle.name() + " executable is unavailable");
            cancellation.check();
            Map<String, String> options = new LinkedHashMap<>();
            options.put("executable", executable.toString());
            if (bundle.provider().equals("orca"))
                options.putAll(BundledOrcaPresets.prepare(scratch, model.profile(), cancellation));
            return process.configure(options);
        }
    }

    private static void unzip(InputStream payload, Path destination, Cancellation cancellation)
            throws IOException, SlicerException.Configuration {
        try (var zip = new ZipInputStream(new BufferedInputStream(payload))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                cancellation.check();
                Path target = inside(destination, entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    copy(zip, target, cancellation);
                }
            }
        }
    }

    private static Path inside(Path directory, String name) throws SlicerException.Configuration {
        Path path = directory.resolve(name).normalize();
        if (!path.startsWith(directory) || path.equals(directory))
            throw new SlicerException.Configuration("Invalid slicer bundle path");
        return path;
    }

    private static Path copy(InputStream input, Path target, Cancellation cancellation) throws IOException {
        try (var output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
            byte[] buffer = new byte[128 * 1024];
            for (int count; (count = input.read(buffer)) != -1; ) {
                cancellation.check();
                output.write(buffer, 0, count);
            }
        }
        return target;
    }

    private record Bundle(UUID id, String provider, String name, String version) {
        private Bundle(String id, String provider, String name, String version) {
            this(UUID.fromString(id), provider, name, version);
        }

        private String descriptor() {
            return RESOURCES + provider + ".properties";
        }
    }
}
