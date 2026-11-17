package dev.voxelbrew.gui;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpTokenStoreTest {
    @TempDir
    Path directory;

    @Test
    void secretSurvivesRestartAndCanBeRotated() throws Exception {
        var file = directory.resolve("mcp-token");
        String token = "a".repeat(43);
        assertThat(McpTokenStore.load(file, () -> token)).isEqualTo(token);
        assertThat(McpTokenStore.load(file, () -> "b".repeat(43))).isEqualTo(token);
        McpTokenStore.save(file, "c".repeat(43));
        assertThat(McpTokenStore.load(file, () -> token)).isEqualTo("c".repeat(43));
        if (Files.getFileStore(file).supportsFileAttributeView("posix"))
            assertThat(Files.getPosixFilePermissions(file))
                    .isEqualTo(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
    }
}
