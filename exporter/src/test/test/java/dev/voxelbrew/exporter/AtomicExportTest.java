package dev.voxelbrew.exporter;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.exporter.FileExportDialog;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@SuppressWarnings("deprecation")
class AtomicExportTest {
    @TempDir
    Path directory;

    @Test
    void createRequiresExplicitConsentBeforeReplacingOutput() throws Exception {
        Path target = directory.resolve("output.txt");
        AtomicExport.write(
                new FileExportDialog.Destination(target, false),
                output -> output.write("first".getBytes(StandardCharsets.UTF_8)));
        assertThat(Files.readString(target)).isEqualTo("first");

        assertThatThrownBy(() -> AtomicExport.write(
                        new FileExportDialog.Destination(target, false), output -> output.write('x')))
                .isInstanceOf(IOException.class);
        assertThat(Files.readString(target)).isEqualTo("first");

        AtomicExport.write(
                new FileExportDialog.Destination(target, true),
                output -> output.write("second".getBytes(StandardCharsets.UTF_8)));
        assertThat(Files.readString(target)).isEqualTo("second");
        assertOnlyTargetRemains(target);
    }

    @Test
    void failedSerializationPreservesExistingOutputAndRemovesTemporaryFile() throws Exception {
        Path target = directory.resolve("preserved.txt");
        Files.writeString(target, "original");

        var ioFailure = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> AtomicExport.write(new FileExportDialog.Destination(target, true), output -> {
                    output.write('x');
                    throw new IOException("writer failed");
                }),
                IOException.class);
        assertThat(ioFailure.getMessage()).isEqualTo("writer failed");
        assertThat(Files.readString(target)).isEqualTo("original");
        assertOnlyTargetRemains(target);

        var runtimeFailure = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> AtomicExport.write(new FileExportDialog.Destination(target, true), output -> {
                    output.write('x');
                    throw new IllegalStateException("invalid contents");
                }),
                IOException.class);
        assertThat(runtimeFailure.getCause()).isInstanceOf(IllegalStateException.class);
        assertThat(Files.readString(target)).isEqualTo("original");
        assertOnlyTargetRemains(target);
    }

    @Test
    void competingCreateCannotReplaceWinner() throws Exception {
        Path target = directory.resolve("race.txt");
        assertThatThrownBy(() -> AtomicExport.write(new FileExportDialog.Destination(target, false), output -> {
                    output.write("loser".getBytes(StandardCharsets.UTF_8));
                    Files.writeString(target, "winner");
                }))
                .isInstanceOf(java.nio.file.FileAlreadyExistsException.class);
        assertThat(Files.readString(target)).isEqualTo("winner");
        assertOnlyTargetRemains(target);
    }

    private void assertOnlyTargetRemains(Path target) throws IOException {
        try (var entries = Files.list(directory)) {
            assertThat(entries.toList()).isEqualTo(List.of(target));
        }
    }
}
