package dev.voxelbrew.printer;

import dev.voxelbrew.api.printer.driver.Driver;
import dev.voxelbrew.api.printer.driver.DriverRegistry;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.*;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

/**
 * ServiceLoader-based discovery with explicit plugin-loader ownership. Supplied drivers take
 * precedence over classpath drivers, followed by installed drivers; the first driver for an
 * ID wins and duplicates are logged. Broken drivers are logged and skipped so other integrations
 * remain usable. Discovery stops after 100 consecutive loading failures.
 *
 * <p>Installed drivers are loaded from regular, lowercase {@code .jar} files in the configured
 * directory, ordered by path. Lookups use the current snapshot; call {@link #withDirectory(Path)}
 * again to rediscover installed JARs. Previous loaders remain open until {@link #close()} so existing
 * printers can finish their operations. Synchronized registry methods do not make driver or
 * printer instances thread-safe.
 */
@Slf4j
class ServiceLoaderDriverRegistry implements DriverRegistry {
    private record Jar(Path path, long size, FileTime modified) {}

    private final ClassLoader parent;
    private final List<Driver> supplied;
    private final List<Driver> classpath = new ArrayList<>();
    private final Map<String, Driver> entries = new LinkedHashMap<>();
    private final List<URLClassLoader> loaders = new ArrayList<>();
    private List<Jar> loadedJars;
    private Path directory;
    private boolean closed;

    /** Discovers classpath drivers using this API's class loader; no plugin directory is scanned. */
    ServiceLoaderDriverRegistry() {
        this(ServiceLoaderDriverRegistry.class.getClassLoader(), List.of());
    }

    /**
     * Discovers services visible to a host-selected loader, also used as parent for plugin loaders.
     * @param loader non-null class loader providing the host's API and classpath integrations
     * @throws NullPointerException if the loader is null
     */
    ServiceLoaderDriverRegistry(ClassLoader loader) {
        this(loader, List.of());
    }

    /**
     * Discovers classpath drivers and installed JARs immediately.
     * @param directory plugin directory; missing directories are treated as empty
     * @throws IOException if an existing directory cannot be scanned or the path is not a directory
     */
    ServiceLoaderDriverRegistry(Path directory) throws IOException {
        this();
        withDirectory(directory);
    }

    /**
     * Discovers drivers with explicit host-supplied integrations registered first. The collection
     * is copied, while driver instances remain shared with the caller.
     * @param directory plugin directory; missing directories are treated as empty
     * @param supplied drivers in registration order, taking precedence for duplicate IDs
     * @throws IOException if the plugin directory cannot be scanned
     * @throws NullPointerException if the collection or any supplied driver is null
     */
    ServiceLoaderDriverRegistry(Path directory, Collection<Driver> supplied) throws IOException {
        this(ServiceLoaderDriverRegistry.class.getClassLoader(), supplied);
        withDirectory(directory);
    }

    private ServiceLoaderDriverRegistry(ClassLoader loader, Collection<Driver> supplied) {
        parent = Objects.requireNonNull(loader, "loader");
        this.supplied = List.copyOf(supplied);
        var iterator = ServiceLoader.load(Driver.class, parent).iterator();
        int failures = 0;
        while (true) {
            try {
                if (!iterator.hasNext()) break;
                var driver = iterator.next();
                if (driver.id() == null || driver.id().isBlank()) continue;
                classpath.add(driver);
                failures = 0;
                log.info("Loaded driver {}", driver.id());
            } catch (ServiceConfigurationError | LinkageError | RuntimeException | AssertionError failure) {
                log.warn("Driver load failed: ", failure);
                if (++failures >= 100) break;
            }
        }
        try {
            refresh();
        } catch (IOException failure) {
            throw new IllegalStateException("Could not initialize printer registry", failure);
        }
    }

    @Override
    public synchronized DriverRegistry withDirectory(Path directory) throws IOException {
        if (closed) throw new IllegalStateException("Printer registry is closed");
        this.directory = Objects.requireNonNull(directory, "directory");
        loadedJars = null;
        refresh();
        return this;
    }

    private synchronized void refresh() throws IOException {
        if (closed) throw new IllegalStateException("Printer registry is closed");
        var jars = new ArrayList<Jar>();
        if (directory != null && Files.exists(directory)) {
            if (!Files.isDirectory(directory)) throw new IOException("Plugin path is not a directory: " + directory);
            try (var paths = Files.list(directory)) {
                for (var jar : paths.filter(
                                p -> Files.isRegularFile(p) && p.toString().endsWith(".jar"))
                        .sorted()
                        .toList()) {
                    jars.add(new Jar(jar, Files.size(jar), Files.getLastModifiedTime(jar)));
                }
            }
        }
        if (jars.equals(loadedJars)) return;
        entries.clear();
        for (var driver : supplied) {
            try {
                register(driver);
            } catch (RuntimeException | LinkageError | AssertionError failure) {
                log.warn("Supplied printer failed", failure);
            }
        }
        for (var driver : classpath) {
            try {
                register(driver);
            } catch (RuntimeException | LinkageError | AssertionError failure) {
                log.warn("Driver registration failed", failure);
            }
        }
        if (directory != null) {
            var urls = new ArrayList<URL>();
            for (var jar : jars) urls.add(jar.path().toUri().toURL());
            var loader = new URLClassLoader(urls.toArray(URL[]::new), parent);
            loaders.add(loader);
            discoverPlugins(loader);
        }
        loadedJars = List.copyOf(jars);
    }

    private void discoverPlugins(URLClassLoader loader) {
        var iterator = ServiceLoader.load(Driver.class, loader).iterator();
        int failures = 0;
        while (true) {
            try {
                if (!iterator.hasNext()) break;
                var driver = iterator.next();
                if (driver.getClass().getClassLoader() != loader) continue;
                register(driver);
                log.info("Loaded driver {}", driver.id());
                failures = 0;
            } catch (ServiceConfigurationError | LinkageError | RuntimeException | AssertionError failure) {
                log.warn("Driver load failed", failure);
                if (++failures >= 100) {
                    log.warn("Stopped after 100 consecutive driver failures");
                    break;
                }
            }
        }
    }

    private void register(Driver driver) {
        var id = Objects.requireNonNull(driver.id(), "id");
        if (id.isBlank()) throw new IllegalArgumentException("Blank driver ID");
        if (entries.putIfAbsent(id, driver) != null) log.warn("Duplicate printer ID: {}", id);
    }

    @Override
    @NonNull
    public synchronized List<Driver> drivers() {
        if (closed) throw new IllegalStateException("Driver registry is closed");
        return entries.values().stream()
                .sorted(Comparator.comparing(Driver::id))
                .toList();
    }

    @Override
    @NonNull
    public synchronized Optional<Driver> find(@NonNull String id) {
        if (closed) throw new IllegalStateException("Driver registry is closed");
        return Optional.ofNullable(entries.get(id));
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        IOException failure = null;
        for (var loader : loaders) {
            try {
                loader.close();
            } catch (IOException error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
        }
        if (failure != null) throw failure;
    }
}
