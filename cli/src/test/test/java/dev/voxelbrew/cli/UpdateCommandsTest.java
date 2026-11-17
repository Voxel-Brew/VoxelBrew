package dev.voxelbrew.cli;

import static org.assertj.core.api.Assertions.*;

import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;

class UpdateCommandsTest {
    @Test
    void updateAndCheckHelpDoNotAttemptNetworkAccess() {
        for (String[] args : new String[][] {
            {"settings", "update", "--help"}, {"settings", "update", "check", "--help"}, {"settings", "update", "-h"}
        }) {
            var output = new StringWriter();
            var command = VoxelBrew.commandLine().setOut(new PrintWriter(output));
            assertThat(command.execute(args)).isEqualTo(0);
            assertThat(output.toString().contains("--version=<version>")).isTrue();
            assertThat(output.toString().contains("--force")).isTrue();
            assertThat(output.toString().contains("requires gh")).isFalse();
        }
    }

    @Test
    void releaseVersionIsAnArgumentRatherThanTheStandardVersionHelpFlag() {
        var root = VoxelBrew.commandLine();
        var parsed = root.parseArgs("settings", "update", "check", "--version", "1.2.3", "--force")
                .subcommand()
                .subcommand()
                .subcommand();
        assertThat(parsed.isVersionHelpRequested()).isFalse();
        assertThat(parsed.matchedOptionValue("--version", "")).isEqualTo("1.2.3");
        assertThat(parsed.matchedOptionValue("--force", false)).isEqualTo(Boolean.TRUE);
    }
}
