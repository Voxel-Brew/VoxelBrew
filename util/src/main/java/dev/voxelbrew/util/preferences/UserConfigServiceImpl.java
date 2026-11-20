package dev.voxelbrew.util.preferences;

import dev.voxelbrew.api.userconfig.UserConfigException;
import dev.voxelbrew.api.userconfig.UserConfigService;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import lombok.NonNull;

/** UTF-8 properties with cross-process read/merge/write locking. */
@lombok.extern.slf4j.Slf4j
public final class UserConfigServiceImpl implements UserConfigService {
    // FileChannel locks cannot overlap within one JVM. Blocking here also serializes readers.
    private static final Object FILE_IO_LOCK = new Object();

    private final Path home;

    private final Path file;

    private static Path configuredHome() {
        return Path.of(System.getProperty(
                "voxelbrew.home",
                Path.of(System.getProperty("user.home"), ".voxelbrew").toString()));
    }

    private static String configuredFileName() {
        String configured = System.getProperty("voxelbrew.config", "config.properties");
        Path path = Path.of(configured);
        Path fileName = path.getFileName();
        if (fileName != null
                && path.isAbsolute()
                && configuredHome().toAbsolutePath().normalize().equals(path.getParent())) {
            return fileName.toString();
        }
        return configured;
    }

    private static Path validatedHome(Path requestedHome) {
        Path home = requestedHome.toAbsolutePath().normalize();
        for (Path ancestor = home; ancestor != null; ancestor = ancestor.getParent()) {
            if (Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isDirectory(ancestor)) {
                    throw new IllegalArgumentException("Configuration home is not a directory: " + ancestor);
                }
                break;
            }
        }
        return home;
    }

    private static Path resolveFile(Path home, String fileName) {
        if (fileName.isBlank()
                || fileName.equals(".")
                || fileName.endsWith(".")
                || fileName.endsWith(" ")
                || fileName.contains("/")
                || fileName.contains("\\")
                || fileName.contains(":")) {
            throw new IllegalArgumentException("Expected a configuration file name within " + home + ": " + fileName);
        }
        Path file = home.resolve(fileName);
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Configuration path is not a regular file: " + file);
        }
        return file;
    }

    private static Map<String, String> asMap(Properties properties) {
        var result = new TreeMap<String, String>();
        properties.stringPropertyNames().forEach(key -> result.put(key, properties.getProperty(key)));
        return Collections.unmodifiableMap(result);
    }

    private static void checkKey(String key) {
        if (key.isBlank()) throw new IllegalArgumentException("Configuration key cannot be blank");
    }

    public UserConfigServiceImpl() {
        this(configuredHome(), configuredFileName());
    }

    public UserConfigServiceImpl(@NonNull Path home, @NonNull String fileName) {
        this.home = validatedHome(home);
        file = resolveFile(this.home, fileName);
    }

    @Override
    public Path homeFolder() {
        return home;
    }

    @Override
    public Optional<String> readUserConfig(String key) {
        checkKey(key);
        return Optional.ofNullable(readProperties().getProperty(key));
    }

    @Override
    public void saveUserConfig(Map<String, String> config) {
        update(config, Set.of());
    }

    @Override
    public void saveUserConfig(String key, String value) {
        if (value == null) remove(key);
        else update(Map.of(key, value), Set.of());
    }

    @Override
    public Map<String, String> readUserConfig() {
        return asMap(readProperties());
    }

    @Override
    public void remove(String key) {
        update(Map.of(), Set.of(key));
    }

    @Override
    public void update(Map<String, String> values, Set<String> removed) {
        log.debug("Updating configuration {} ({} keys set, {} removed)", file, values.size(), removed.size());
        var entries = Map.copyOf(values);
        var removals = Set.copyOf(removed);
        entries.keySet().forEach(UserConfigServiceImpl::checkKey);
        removals.forEach(UserConfigServiceImpl::checkKey);
        change(properties -> {
            removals.forEach(properties::remove);
            properties.putAll(entries);
        });
    }

    private Properties readProperties() {
        synchronized (FILE_IO_LOCK) {
            if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) return new Properties();
            try (var channel = openLockFile()) {
                channel.lock();
                return load();
            } catch (IOException failure) {
                throw new UserConfigException("Cannot read user configuration: " + file, failure);
            }
        }
    }

    private Properties load() throws IOException {
        var properties = new Properties();
        try (var reader = new InputStreamReader(
                Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS), StandardCharsets.UTF_8.newDecoder())) {
            properties.load(reader);
        } catch (NoSuchFileException missing) {
            // The default configuration is empty until a preference is changed.
        } catch (IllegalArgumentException malformed) {
            throw new IOException("Malformed configuration: " + file, malformed);
        }
        return properties;
    }

    private void change(java.util.function.Consumer<Properties> operation) {
        synchronized (FILE_IO_LOCK) {
            try {
                Files.createDirectories(home);
                try (var channel = openLockFile()) {
                    channel.lock();
                    var properties = load();
                    operation.accept(properties);
                    var text = new StringWriter();
                    properties.store(text, "VoxelBrew user configuration");
                    Path temporary = Files.createTempFile(home, ".config-", ".tmp");
                    try {
                        try (var output = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                            var bytes = ByteBuffer.wrap(text.toString().getBytes(StandardCharsets.UTF_8));
                            while (bytes.hasRemaining()) output.write(bytes);
                            output.force(true);
                        }
                        Files.move(
                                temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    } finally {
                        Files.deleteIfExists(temporary);
                    }
                }
            } catch (IOException failure) {
                throw new UserConfigException("Cannot save user configuration: " + file, failure);
            }
        }
    }

    private FileChannel openLockFile() throws IOException {
        Path lockFile = file.resolveSibling(file.getFileName() + ".lock");
        return FileChannel.open(
                lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
    }
}
