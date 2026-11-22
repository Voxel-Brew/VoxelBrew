package dev.voxelbrew.slicer.internal;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.model.printing.Cancellation;
import java.io.*;
import java.nio.file.*;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SlicerDownloadTest {
    @TempDir
    Path directory;

    @Test
    void rejectsIncorrectBytesAndAllowsRetryAfterCancellationOrChecksumFailure() throws Exception {
        Path source = directory.resolve("upstream");
        Files.write(source, new byte[] {1, 2, 3});
        String hash = "a".repeat(64);
        var download = new SlicerDownload("orca", source.toUri(), hash, 3, "orca-slicer.exe");
        Path cache = directory.resolve("cache");
        assertThatThrownBy(() -> download.install(cache, new Cancellation(), ignored -> {}))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("checksum");
        var cancellation = new Cancellation();
        cancellation.cancel();
        assertThatThrownBy(() -> download.install(cache, cancellation, ignored -> {}))
                .isInstanceOf(CancellationException.class);
        assertThat(cache.resolve("orca")).isEmptyDirectory();
        var target = download.installedExecutable(cache);
        Files.createDirectories(target.getParent());
        Files.copy(
                Path.of(
                        System.getProperty("java.home"),
                        "bin",
                        System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java"),
                target,
                StandardCopyOption.COPY_ATTRIBUTES);
        Files.delete(source);
        assertThatCode(() -> download.install(cache, new Cancellation(), ignored -> {}))
                .doesNotThrowAnyException();
    }
}
