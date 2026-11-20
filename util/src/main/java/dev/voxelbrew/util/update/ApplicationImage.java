package dev.voxelbrew.util.update;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

/** A complete jpackage installation. Development classpaths are deliberately not update targets. */
public record ApplicationImage(
        Path root, Component component, String platform, ReleaseVersion version, String engineJar, String mainJar) {
    public static final String MARKER = "voxelbrew-install.properties";

    @RequiredArgsConstructor
    public enum Component {
        CLI("VoxelBrewCLI", "dev/voxelbrew/cli/VoxelBrew.class"),
        GUI("VoxelBrew", "dev/voxelbrew/gui/Launcher.class");
        private final String applicationName, entry;

        public String applicationName() {
            return applicationName;
        }

        public String imageName(String platform) {
            return applicationName + (platform.startsWith("macos-") ? ".app" : "");
        }

        public String asset(ReleaseVersion version, String platform) {
            return applicationName + "-" + version + "-" + platform + ".zip";
        }
    }

    @RequiredArgsConstructor(access = AccessLevel.PACKAGE)
    public static final class Lease implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;

        @Override
        public void close() throws IOException {
            try {
                if (lock != null && lock.isValid()) lock.release();
            } finally {
                if (channel != null) channel.close();
            }
        }
    }

    public static String currentPlatform() {
        String arch = System.getProperty("os.arch");
        if (!Set.of("amd64", "x86_64").contains(arch)) {
            throw new IllegalArgumentException("Self-update is not qualified for architecture " + arch);
        }
        String os = System.getProperty("os.name");
        if (os.startsWith("Windows")) return "windows-x64";
        if (os.startsWith("Mac")) return "macos-x64";
        if (os.startsWith("Linux")) return "linux-x64";
        throw new IllegalArgumentException("Unsupported update platform: " + os);
    }

    public static Optional<ApplicationImage> detect(Component component) throws IOException {
        try {
            Path jar = Path.of(BuildVersion.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI());
            if (!Files.isRegularFile(jar)) return Optional.empty();
            Path app = jar.getParent();
            if (app == null || !Files.isRegularFile(app.resolve(MARKER))) return Optional.empty();
            String platform = currentPlatform();
            Path root = app.getParent();
            if (root != null && !platform.startsWith("windows-")) root = root.getParent();
            if (root == null) return Optional.empty();
            var image = open(root, component, platform);
            if (!Path.of(System.getProperty("java.home"))
                    .toRealPath()
                    .equals(image.runtime().toRealPath())) {
                return Optional.empty();
            }
            if (!image.version.toString().equals(BuildVersion.current())) {
                throw new IOException("Installation/build version mismatch");
            }
            return Optional.of(image);
        } catch (java.net.URISyntaxException failure) {
            throw new IOException("Cannot locate installation", failure);
        }
    }

    public static ApplicationImage open(Path directory, Component component, String platform) throws IOException {
        if (!Set.of("windows-x64", "linux-x64", "macos-x64").contains(platform)) {
            throw new IOException("Unqualified platform: " + platform);
        }
        Path root = directory.toAbsolutePath().normalize();
        if (!java.util.Objects.toString(root.getFileName(), "").equals(component.imageName(platform))
                || Files.isSymbolicLink(root)) {
            throw new IOException("Expected an unlinked " + component.imageName(platform) + " app directory");
        }
        var temporary = new ApplicationImage(root, component, platform, null, "", "");
        Properties p = read(temporary.appDirectory().resolve(MARKER));
        try {
            if (!"1".equals(p.getProperty("format"))
                    || !component.name().equals(p.getProperty("component"))
                    || !platform.equals(p.getProperty("platform"))) {
                throw new IOException("Installation identity does not match selected application/platform");
            }
            String engine = fileName(p.getProperty("engineJar")), main = fileName(p.getProperty("mainJar"));
            var result = new ApplicationImage(
                    root, component, platform, ReleaseVersion.parse(p.getProperty("version")), engine, main);
            result.validateLayout();
            result.validateModules(p);
            result.validateEntryPoints();
            return result;
        } catch (IllegalArgumentException failure) {
            throw new IOException("Invalid installation metadata", failure);
        }
    }

    static Properties read(Path file) throws IOException {
        var p = new Properties();
        try (var input = Files.newInputStream(file)) {
            p.load(input);
        }
        return p;
    }

    private static String fileName(String name) throws IOException {
        if (name == null || !name.matches("[A-Za-z0-9._-]+\\.jar")) {
            throw new IOException("Invalid application JAR name");
        }
        return name;
    }

    /** Hold for the entire process lifetime; a directory swap needs the exclusive counterpart. */
    public static Lease running(Component component) throws IOException {
        var image = detect(component);
        if (image.isEmpty()) return new Lease(null, null);
        try {
            return lock(image.get().runningLock(), true);
        } catch (AccessDeniedException readOnly) {
            // Read-only installations remain runnable; updating still requires a writable parent.
            return new Lease(null, null);
        }
    }

    static Lease lock(Path file, boolean shared) throws IOException {
        if (Files.isSymbolicLink(file)) throw new IOException("Linked installation lock is not allowed");
        var channel =
                FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        try {
            var lock = channel.tryLock(0, Long.MAX_VALUE, shared);
            if (lock == null) {
                throw new IOException("Installation is busy; close other instances or wait for its update");
            }
            return new Lease(channel, lock);
        } catch (IOException | OverlappingFileLockException failure) {
            channel.close();
            throw new IOException("Installation is busy; close other instances or wait for its update", failure);
        }
    }

    public Path appDirectory() {
        return platformPath("app", "Contents/app", "lib/app");
    }

    public Path runtime() {
        return platformPath("runtime", "Contents/runtime/Contents/Home", "lib/runtime");
    }

    public Path launcher() {
        String name = component.applicationName;
        return platformPath(name + ".exe", "Contents/MacOS/" + name, "bin/" + name);
    }

    private Path platformPath(String windows, String mac, String linux) {
        if (platform.startsWith("windows-")) return root.resolve(windows);
        return root.resolve(platform.startsWith("macos-") ? mac : linux);
    }

    public Path runningLock() {
        return root.resolveSibling("." + root.getFileName() + ".running.lock");
    }

    public Path updateLock() {
        return root.resolveSibling("." + root.getFileName() + ".update.lock");
    }

    /** Format-1 images predating util kept the standalone helper in engine. */
    public String updaterJar() throws IOException {
        return fileName(read(appDirectory().resolve(MARKER)).getProperty("utilJar", engineJar));
    }

    private void validateLayout() throws IOException {
        for (Path file : List.of(
                launcher(),
                runtime().resolve("bin/java" + (platform.startsWith("windows-") ? ".exe" : "")),
                appDirectory().resolve(engineJar),
                appDirectory().resolve(mainJar))) {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    || !file.toRealPath().startsWith(root.toRealPath())) {
                throw new IOException("Incomplete or linked app image: " + file);
            }
        }
        if (!Files.isDirectory(appDirectory().resolve("native"))) {
            throw new IOException("App image has no native libraries");
        }
    }

    private void validateModules(Properties p) throws IOException {
        for (String module : List.of(
                "util", "import", "export", "import-api", "export-api", "slicer-api", "model-api", "printer-api")) {
            if (!p.containsKey(module + "Jar")) continue; // Older format-1 installations.
            Path utility = appDirectory().resolve(fileName(p.getProperty(module + "Jar")));
            if (!Files.isRegularFile(utility, LinkOption.NOFOLLOW_LINKS)
                    || !utility.toRealPath().startsWith(root.toRealPath())) {
                throw new IOException("Incomplete or linked " + module + " JAR: " + utility);
            }
            try (var jar = new JarFile(utility.toFile())) {
                var entry = jar.getJarEntry("voxelbrew-" + module + "-version.properties");
                if (entry == null) throw new IOException(module + " has no build identity");
                var build = new Properties();
                try (var in = jar.getInputStream(entry)) {
                    build.load(in);
                }
                String expectedVersion = module.endsWith("-api")
                        ? p.getProperty(module + "Version", version.toString())
                        : version.toString();
                ReleaseVersion.parse(expectedVersion);
                if (!expectedVersion.equals(build.getProperty("version"))) {
                    throw new IOException(module + "/installation version mismatch");
                }
            }
        }
    }

    private void validateEntryPoints() throws IOException {
        try (var jar = new JarFile(appDirectory().resolve(engineJar).toFile())) {
            var entry = jar.getJarEntry("voxelbrew-engine-version.properties");
            if (entry == null) throw new IOException("Engine has no build identity");
            var build = new Properties();
            try (var in = jar.getInputStream(entry)) {
                build.load(in);
            }
            if (!version.toString().equals(build.getProperty("version"))) {
                throw new IOException("Engine/installation version mismatch");
            }
        }
        try (var jar = new JarFile(appDirectory().resolve(mainJar).toFile())) {
            if (jar.getJarEntry(component.entry) == null) throw new IOException("Wrong application entry point");
        }
    }
}
