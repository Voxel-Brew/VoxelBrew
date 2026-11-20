package dev.voxelbrew.slicer.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.voxelbrew.api.model.printing.Cancellation;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExecutableDiscoveryTest {
    @TempDir
    Path directory;

    private static Path executable(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, "This is not a runnable program");
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
        }
        return path;
    }

    @Test
    void findsMultipleVersionsAndDeduplicatesPathsWithoutExecuting() throws IOException {
        var bin = Files.createDirectory(directory.resolve("bin"));
        var path = executable(bin.resolve("slicer.exe"));
        var root = Files.createDirectory(directory.resolve("programs"));
        var version1 = executable(root.resolve("Slicer 1/slicer.exe"));
        var version2 = executable(root.resolve("Slicer 2/slicer.exe"));
        executable(root.resolve("Unrelated/slicer.exe"));
        Files.createDirectories(root.resolve("Slicer directory/slicer.exe"));
        assertThat(ExecutableDiscovery.scan(
                        List.of(bin, bin.resolve(".")),
                        List.of(root),
                        "slicer.exe",
                        Path.of("Contents/MacOS/Slicer"),
                        Set.of("slicer"),
                        false,
                        List.of(path),
                        new Cancellation()))
                .containsExactly(path, version1, version2);
    }

    @Test
    void honorsCancellationEvenWithoutCandidates() {
        var cancellation = new Cancellation();
        cancellation.cancel();
        assertThatThrownBy(() -> ExecutableDiscovery.scan(
                        List.of(),
                        List.of(),
                        "slicer",
                        Path.of("Contents/MacOS/Slicer"),
                        Set.of("slicer"),
                        false,
                        List.of(),
                        cancellation))
                .isInstanceOf(CancellationException.class);
    }
}
