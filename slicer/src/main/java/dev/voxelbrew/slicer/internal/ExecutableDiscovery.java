package dev.voxelbrew.slicer.internal;

import dev.voxelbrew.api.model.printing.Cancellation;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import lombok.experimental.UtilityClass;

/** Looks for installed commands and portable builds without running them. */
@UtilityClass
public class ExecutableDiscovery {
    public static List<Path> find(
            String command,
            String windowsCommand,
            Path macCommand,
            Set<String> prefixes,
            boolean portable,
            List<Path> additional,
            Cancellation cancellation) {
        String os = System.getProperty("os.name");
        var path = new ArrayList<Path>();
        var roots = new ArrayList<Path>();
        var home = Path.of(System.getProperty("user.home"));
        for (String entry : System.getenv().getOrDefault("PATH", "").split(Pattern.quote(File.pathSeparator))) {
            addDirectory(path, entry);
        }
        boolean windows = os.startsWith("Windows");
        if (windows) {
            addDirectory(roots, System.getenv("ProgramFiles"));
            addDirectory(roots, System.getenv("ProgramFiles(x86)"));
            String local = System.getenv("LOCALAPPDATA");
            if (local != null && !local.isBlank()) addDirectory(roots, local + File.separator + "Programs");
        } else {
            Path systemRoot = Path.of(File.separator);
            path.addAll(List.of(
                    systemRoot.resolve("usr/local/bin"), systemRoot.resolve("usr/bin"), home.resolve(".local/bin")));
            roots.add(systemRoot.resolve(os.startsWith("Mac") ? "Applications" : "opt"));
            roots.add(home.resolve("Applications"));
        }
        return scan(
                path,
                roots,
                windows ? windowsCommand : command,
                macCommand,
                prefixes,
                portable && os.equals("Linux"),
                additional,
                cancellation);
    }

    static List<Path> scan(
            List<Path> path,
            List<Path> roots,
            String command,
            Path macCommand,
            Set<String> prefixes,
            boolean portable,
            List<Path> additional,
            Cancellation cancellation) {
        var results = new LinkedHashMap<Path, Path>();
        for (Path candidate : additional) addCandidate(results, candidate, cancellation);
        for (Path directory : path) addCandidate(results, directory.resolve(command), cancellation);
        for (Path root : roots) {
            cancellation.check();
            for (Path entry : installations(root, prefixes, cancellation)) {
                if (Files.isDirectory(entry)) {
                    addCandidate(results, entry.resolve(command), cancellation);
                    addCandidate(results, entry.resolve("bin").resolve(command), cancellation);
                    addCandidate(results, entry.resolve(macCommand), cancellation);
                    if (!name(entry).endsWith(".app")) {
                        for (Path nested : installations(entry, prefixes, cancellation)) {
                            addCandidate(results, nested.resolve(command), cancellation);
                            addCandidate(results, nested.resolve(macCommand), cancellation);
                        }
                    }
                } else if (portable && name(entry).endsWith(".appimage")) {
                    addCandidate(results, entry, cancellation);
                }
            }
        }
        cancellation.check();
        return List.copyOf(results.values());
    }

    private static List<Path> installations(Path root, Set<String> prefixes, Cancellation cancellation) {
        if (!Files.isDirectory(root)) return List.of();
        try (var entries = Files.list(root)) {
            return entries.filter(entry -> {
                        cancellation.check();
                        return prefixes.stream().anyMatch(prefix -> name(entry).startsWith(prefix));
                    })
                    .sorted()
                    .toList();
        } catch (IOException | UncheckedIOException ignored) {
            return List.of();
        }
    }

    private static String name(Path path) {
        return Objects.toString(path.getFileName(), "").toLowerCase(Locale.ROOT);
    }

    private static void addCandidate(Map<Path, Path> results, Path path, Cancellation cancellation) {
        cancellation.check();
        if (!Files.isRegularFile(path) || !Files.isExecutable(path)) return;
        try {
            results.putIfAbsent(path.toRealPath(), path.toAbsolutePath().normalize());
        } catch (IOException ignored) {
            // The candidate may disappear during discovery.
        }
    }

    private static void addDirectory(List<Path> directories, String entry) {
        if (entry == null || entry.isBlank()) return;
        try {
            directories.add(Path.of(entry.replace("\"", "")));
        } catch (InvalidPathException ignored) {
            // A malformed environment entry does not hide other installations.
        }
    }
}
