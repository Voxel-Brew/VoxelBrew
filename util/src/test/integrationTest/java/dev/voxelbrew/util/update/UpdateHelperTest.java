package dev.voxelbrew.util.update;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.*;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class UpdateHelperTest {
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void retriesDirectoryMoveWhenWindowsReleasesSharingLock(@TempDir Path directory) throws Exception {
        Path installed = Files.createDirectory(directory.resolve("VoxelBrew"));
        Path backup = directory.resolve("backup");
        Files.writeString(installed.resolve("version"), "old");
        Process holder = new ProcessBuilder("cmd.exe", "/d", "/q")
                .directory(installed.toFile())
                .start();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                assertThatThrownBy(() -> Files.move(installed, backup, StandardCopyOption.ATOMIC_MOVE))
                        .isExactlyInstanceOf(FileSystemException.class);
                var move = executor.submit(() -> {
                    UpdateHelper.move(installed, backup);
                    return null;
                });
                assertThatThrownBy(() -> move.get(500, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                holder.getOutputStream().close();
                assertThat(holder.waitFor(5, TimeUnit.SECONDS)).isTrue();
                move.get(5, TimeUnit.SECONDS);
                assertThat(backup.resolve("version")).hasContent("old");
            } finally {
                holder.destroyForcibly().waitFor();
            }
        }
    }
}
