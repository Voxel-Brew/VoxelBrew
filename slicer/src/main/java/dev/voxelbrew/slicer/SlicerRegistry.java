package dev.voxelbrew.slicer;

import dev.voxelbrew.api.slicer.*;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import lombok.extern.slf4j.Slf4j;

/** Discovers classpath and installed slicers and owns their plugin class loaders. */
@Slf4j
public final class SlicerRegistry implements SlicerProviderRegistry {
    private record Entry(SlicerDescriptor descriptor, SlicerProvider provider) {}

    private final ClassLoader parent = SlicerRegistry.class.getClassLoader();
    private final Map<String, Entry> builtIns = new LinkedHashMap<>();
    private final List<URLClassLoader> loaders = new ArrayList<>();
    private Map<String, Entry> entries;
    private boolean closed;

    public SlicerRegistry() {
        this(List.of());
    }

    SlicerRegistry(Collection<SlicerProvider> supplied) {
        for (var provider : supplied) {
            try {
                register(builtIns, provider);
            } catch (RuntimeException | LinkageError | AssertionError failure) {
                log.warn("Supplied slicer failed to register", failure);
            }
        }
        discover(builtIns, parent, false);
        entries = Map.copyOf(builtIns);
    }

    @Override
    public synchronized SlicerProviderRegistry withDirectory(Path directory) throws IOException {
        requireOpen();
        Objects.requireNonNull(directory, "directory");
        var discovered = new LinkedHashMap<>(builtIns);
        if (Files.exists(directory)) {
            var urls = new ArrayList<URL>();
            try (var paths = Files.list(directory)) {
                for (var jar : paths.filter(path -> Files.isRegularFile(path)
                                && java.util.Objects.toString(path.getFileName(), "")
                                        .toLowerCase(Locale.ROOT)
                                        .endsWith(".jar"))
                        .sorted()
                        .toList()) {
                    urls.add(jar.toUri().toURL());
                }
            }
            if (!urls.isEmpty()) {
                var loader = new URLClassLoader(urls.toArray(URL[]::new), parent);
                // Keep earlier loaders alive for operations already created from them.
                loaders.add(loader);
                discover(discovered, loader, true);
            }
        }
        entries = Map.copyOf(discovered);
        return this;
    }

    private static void discover(Map<String, Entry> entries, ClassLoader loader, boolean installedOnly) {
        var iterator = ServiceLoader.load(SlicerProvider.class, loader).stream().iterator();
        int failures = 0;
        while (true) {
            try {
                if (!iterator.hasNext()) return;
                var candidate = iterator.next();
                if (!installedOnly || candidate.type().getClassLoader() == loader) register(entries, candidate.get());
                failures = 0;
            } catch (ServiceConfigurationError | LinkageError | RuntimeException | AssertionError failure) {
                log.warn("Slicer provider failed to load", failure);
                if (++failures >= 100) return;
            }
        }
    }

    private static void register(Map<String, Entry> entries, SlicerProvider provider) {
        var descriptor = Objects.requireNonNull(provider.descriptor(), "descriptor");
        if (entries.putIfAbsent(descriptor.id(), new Entry(descriptor, provider)) != null)
            log.warn("Duplicate slicer ID: {}", descriptor.id());
        else log.info("Loaded slicer {} ({}) version {}", descriptor.name(), descriptor.id(), descriptor.version());
    }

    @Override
    public synchronized List<SlicerDescriptor> descriptors() {
        requireOpen();
        return entries.values().stream()
                .map(Entry::descriptor)
                .sorted(Comparator.comparing(SlicerDescriptor::id))
                .toList();
    }

    @Override
    public synchronized SlicerProvider require(String id) {
        requireOpen();
        var entry = entries.get(id);
        if (entry == null)
            throw new IllegalArgumentException(
                    "Unknown slicer '" + id + "'. Available: " + new TreeSet<>(entries.keySet()));
        return entry.provider();
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("Slicer registry is closed");
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
        loaders.clear();
        entries = Map.of();
        if (failure != null) throw failure;
    }
}
