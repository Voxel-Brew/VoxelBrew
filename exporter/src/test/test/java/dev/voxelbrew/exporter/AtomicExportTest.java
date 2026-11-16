package dev.voxelbrew.exporter;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.api.exporter.FileExportDialog;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AtomicExportTest {
    @TempDir
    Path directory;

    @Test
    void createRequiresExplicitConsentBeforeReplacingOutput() throws Exception {
        Path target = directory.resolve("output.txt");
        AtomicExport.write(
                new FileExportDialog.Destination(target, false),
                output -> output.write("first".getBytes(StandardCharsets.UTF_8)));
        assertEquals("first", Files.readString(target));

        assertThrows(
                IOException.class,
                () -> AtomicExport.write(new FileExportDialog.Destination(target, false), output -> output.write('x')));
        assertEquals("first", Files.readString(target));

        AtomicExport.write(
                new FileExportDialog.Destination(target, true),
                output -> output.write("second".getBytes(StandardCharsets.UTF_8)));
        assertEquals("second", Files.readString(target));
        assertOnlyTargetRemains(target);
    }

    @Test
    void failedSerializationPreservesExistingOutputAndRemovesTemporaryFile() throws Exception {
        Path target = directory.resolve("preserved.txt");
        Files.writeString(target, "original");

        var ioFailure = assertThrows(
                IOException.class,
                () -> AtomicExport.write(new FileExportDialog.Destination(target, true), output -> {
                    output.write('x');
                    throw new IOException("writer failed");
                }));
        assertEquals("writer failed", ioFailure.getMessage());
        assertEquals("original", Files.readString(target));
        assertOnlyTargetRemains(target);

        var runtimeFailure = assertThrows(
                IOException.class,
                () -> AtomicExport.write(new FileExportDialog.Destination(target, true), output -> {
                    output.write('x');
                    throw new IllegalStateException("invalid contents");
                }));
        assertInstanceOf(IllegalStateException.class, runtimeFailure.getCause());
        assertEquals("original", Files.readString(target));
        assertOnlyTargetRemains(target);
    }

    @Test
    void competingCreateCannotReplaceWinner() throws Exception {
        Path target = directory.resolve("race.txt");
        assertThrows(
                java.nio.file.FileAlreadyExistsException.class,
                () -> AtomicExport.write(new FileExportDialog.Destination(target, false), output -> {
                    output.write("loser".getBytes(StandardCharsets.UTF_8));
                    Files.writeString(target, "winner");
                }));
        assertEquals("winner", Files.readString(target));
        assertOnlyTargetRemains(target);
    }

    private void assertOnlyTargetRemains(Path target) throws IOException {
        try (var entries = Files.list(directory)) {
            assertEquals(List.of(target), entries.toList());
        }
    }
}
