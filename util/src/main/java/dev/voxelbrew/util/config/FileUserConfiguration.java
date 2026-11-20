package dev.voxelbrew.util.config;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import lombok.NonNull;

/** UTF-8 properties with cross-process read/merge/write locking and atomic publication. */
@lombok.extern.slf4j.Slf4j
public final class FileUserConfiguration implements UserConfiguration {
    private static final Object[] LOCKS = new Object[32];

    static {
        Arrays.setAll(LOCKS, ignored -> new Object());
    }

    private final Path file;
    private final Path parent;

    private static void checkKey(String key) {
        if (key.isBlank()) throw new IllegalArgumentException("Configuration key cannot be blank");
    }

    public FileUserConfiguration() {
        this(dev.voxelbrew.util.services.Services.load(ApplicationPaths.class));
    }

    public FileUserConfiguration(@NonNull ApplicationPaths paths) {
        this(paths.configuration());
    }

    public FileUserConfiguration(@NonNull Path file) {
        this.file = file.toAbsolutePath().normalize();
        parent = this.file.getParent();
        if (parent == null) throw new IllegalArgumentException("Expected a file path");
    }

    public Path path() {
        return file;
    }

    /** Provider settings in a namespace directory, migrating older layouts on first access. */
    public FileUserConfiguration section(String namespace, String id) throws IOException {
        if (namespace == null
                || !namespace.matches("[a-z0-9][a-z0-9-]*")
                || id == null
                || !id.matches("[a-z0-9][a-z0-9.-]*"))
            throw new IllegalArgumentException("Invalid configuration section");
        var target =
                new FileUserConfiguration(file.resolveSibling(namespace + "s").resolve(id + ".properties"));
        var previous = new FileUserConfiguration(file.resolveSibling("config-" + namespace + "-" + id + ".properties"));
        previous.migrateTo(target);
        String prefix = namespace + "." + id + ".";
        if (read().keySet().stream().anyMatch(key -> key.startsWith(prefix))) {
            try {
                change(legacy -> {
                    var keys = legacy.stringPropertyNames().stream()
                            .filter(key -> key.startsWith(prefix))
                            .toList();
                    try {
                        // Publish the destination before removing anything; existing destination values win.
                        target.change(values -> keys.forEach(
                                key -> values.putIfAbsent(key.substring(prefix.length()), legacy.getProperty(key))));
                    } catch (IOException failure) {
                        throw new java.io.UncheckedIOException(failure);
                    }
                    keys.forEach(legacy::remove);
                });
            } catch (java.io.UncheckedIOException failure) {
                throw failure.getCause();
            }
        }
        return target;
    }

    @SuppressWarnings("try")
    private void migrateTo(FileUserConfiguration target) throws IOException {
        synchronized (LOCKS[Math.floorMod(file.hashCode(), LOCKS.length)]) {
            if (!Files.exists(file)) return;
            Path lockFile = file.resolveSibling(file.getFileName() + ".lock");
            try (var channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    var lock = channel.lock()) {
                if (!Files.exists(file)) return;
                var previous = load();
                target.change(values -> previous.forEach(values::putIfAbsent));
                Files.delete(file);
            }
        }
    }

    public Map<String, String> read() throws IOException {
        Properties properties = load();
        var result = new TreeMap<String, String>();
        properties.stringPropertyNames().forEach(key -> result.put(key, properties.getProperty(key)));
        return Collections.unmodifiableMap(result);
    }

    public String get(String key, String fallback) throws IOException {
        return read().getOrDefault(key, fallback);
    }

    /** Location suggested for new documents; empty values use ~/VoxelBrew. */
    public Path defaultSaveFolder() throws IOException {
        String value = get("save.folder", "").strip();
        Path home = Path.of(System.getProperty("user.home"));
        if (value.isEmpty()) return home.resolve("VoxelBrew").toAbsolutePath().normalize();
        if (value.equals("~")) return home.toAbsolutePath().normalize();
        if (value.startsWith("~/") || value.startsWith("~\\"))
            return home.resolve(value.substring(2)).toAbsolutePath().normalize();
        return Path.of(value).toAbsolutePath().normalize();
    }

    public void set(String key, String value) throws IOException {
        update(Map.of(key, value), Set.of());
    }

    public void remove(String key) throws IOException {
        update(Map.of(), Set.of(key));
    }

    public void update(Map<String, String> values, Set<String> removed) throws IOException {
        log.debug("Updating configuration {} ({} keys set, {} removed)", file, values.size(), removed.size());
        var snapshot = Map.copyOf(values);
        var removals = Set.copyOf(removed);
        snapshot.keySet().forEach(FileUserConfiguration::checkKey);
        removals.forEach(FileUserConfiguration::checkKey);
        change(properties -> {
            removals.forEach(properties::remove);
            properties.putAll(snapshot);
        });
    }

    /** Used by migrations so an already configured value always wins. */
    public void setIfAbsent(String key, @NonNull String value) throws IOException {
        checkKey(key);

        change(properties -> properties.putIfAbsent(key, value));
    }

    private Properties load() throws IOException {
        var properties = new Properties();
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (NoSuchFileException missing) {
            // The default configuration is empty until a preference is changed.
        } catch (IllegalArgumentException malformed) {
            throw new IOException("Malformed configuration: " + file, malformed);
        }
        return properties;
    }

    @SuppressWarnings("try")
    private void change(java.util.function.Consumer<Properties> operation) throws IOException {
        synchronized (LOCKS[Math.floorMod(file.hashCode(), LOCKS.length)]) {
            Files.createDirectories(parent);
            Path lockFile = file.resolveSibling(file.getFileName() + ".lock");
            try (var channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    var lock = channel.lock()) {
                var properties = load();
                operation.accept(properties);
                var text = new StringWriter();
                properties.store(text, "VoxelBrew user configuration");
                Path temporary = Files.createTempFile(parent, ".config-", ".tmp");
                try {
                    try (var output = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                        var bytes = ByteBuffer.wrap(text.toString().getBytes(StandardCharsets.UTF_8));
                        while (bytes.hasRemaining()) output.write(bytes);
                        output.force(true);
                    }
                    Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } finally {
                    Files.deleteIfExists(temporary);
                }
            }
        }
    }
}
