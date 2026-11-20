package dev.voxelbrew.util.logging;

import dev.voxelbrew.api.userconfig.UserConfigException;
import dev.voxelbrew.api.userconfig.UserConfigService;
import dev.voxelbrew.util.services.Services;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.logging.*;
import lombok.extern.slf4j.Slf4j;

/** Configures only VoxelBrew's logger hierarchy; never resets a host application's logging. */
@Slf4j
public final class Logging {
    private static final Logger APPLICATION = Logger.getLogger("dev.voxelbrew");

    private static final Object LOCK = new Object();

    private static boolean initialized;

    private static Thread.UncaughtExceptionHandler previousUncaught;

    private static Thread.UncaughtExceptionHandler applicationUncaught;

    public static void initialize(String component) {
        synchronized (LOCK) {
            if (initialized) return;
            try {
                var configuration = Services.load(UserConfigService.class);
                configure(configuration, configuration.logsFolder(), component);
            } catch (IOException | UncheckedIOException | IllegalArgumentException | UserConfigException failure) {
                install(Level.WARNING, console(Level.WARNING));
                log.warn("Cannot configure file logging; using console diagnostics", failure);
            }
            initialized = true;
            previousUncaught = Thread.getDefaultUncaughtExceptionHandler();
            var previous = previousUncaught;
            applicationUncaught = (thread, failure) -> {
                log.error("Uncaught exception on thread {}", thread.getName(), failure);
                if (previous != null) previous.uncaughtException(thread, failure);
            };
            Thread.setDefaultUncaughtExceptionHandler(applicationUncaught);
            log.info(
                    "Starting {} version {} on {} / Java {}",
                    component,
                    dev.voxelbrew.util.Version.version(),
                    System.getProperty("os.name"),
                    System.getProperty("java.version"));
        }
    }

    public static void configure(UserConfigService configuration, Path directory, String component) throws IOException {
        synchronized (LOCK) {
            if (!component.matches("[a-zA-Z0-9_-]+")) throw new IllegalArgumentException("Invalid logging component");
            Map<String, String> options = configuration.readUserConfig();
            Level level = level(options.getOrDefault("logging.level", "INFO"));
            Level consoleLevel = level(options.getOrDefault("logging.console.level", "WARNING"));
            long bytes = Long.parseLong(options.getOrDefault("logging.file.limit", "20971520"));
            int count = Integer.parseInt(options.getOrDefault("logging.file.count", "10"));
            if (bytes < 1024 || count < 1 || count > 20)
                throw new IllegalArgumentException("Invalid log rotation limits");
            var file = new RollingAppLog(directory, bytes, count);
            file.setFormatter(new AppLogFormatter(component));
            file.setLevel(level);
            var console = console(consoleLevel);
            install(level.intValue() < consoleLevel.intValue() ? level : consoleLevel, file, console);
        }
    }

    private static ConsoleHandler console(Level level) {
        var handler = new ConsoleHandler();
        handler.setLevel(level);
        try {
            // ConsoleHandler otherwise uses native stderr encoding on Windows, even for pipes.
            handler.setEncoding(java.nio.charset.StandardCharsets.UTF_8.name());
        } catch (java.io.UnsupportedEncodingException impossible) {
            throw new AssertionError("Java must support UTF-8", impossible);
        }
        return handler;
    }

    private static void install(Level level, Handler... handlers) {
        for (Handler handler : APPLICATION.getHandlers()) {
            APPLICATION.removeHandler(handler);
            handler.close();
        }
        APPLICATION.setUseParentHandlers(false);
        APPLICATION.setLevel(level);
        for (var handler : handlers) APPLICATION.addHandler(handler);
    }

    private static Level level(String value) {
        String name = value.toUpperCase(Locale.ROOT);
        return switch (name) {
            case "ERROR" -> Level.SEVERE;
            case "WARN" -> Level.WARNING;
            case "DEBUG" -> Level.FINE;
            case "TRACE" -> Level.FINEST;
            default -> Level.parse(name);
        };
    }

    public static void close() {
        synchronized (LOCK) {
            if (initialized) log.info("Application logging stopped");
            if (applicationUncaught != null && Thread.getDefaultUncaughtExceptionHandler() == applicationUncaught)
                Thread.setDefaultUncaughtExceptionHandler(previousUncaught);
            applicationUncaught = null;
            previousUncaught = null;
            for (Handler handler : APPLICATION.getHandlers()) {
                APPLICATION.removeHandler(handler);
                handler.close();
            }
            APPLICATION.setUseParentHandlers(true);
            APPLICATION.setLevel(null);
            initialized = false;
        }
    }

    private Logging() {}
}
