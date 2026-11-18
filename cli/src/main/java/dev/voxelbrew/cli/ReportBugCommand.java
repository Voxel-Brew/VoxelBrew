package dev.voxelbrew.cli;

import dev.voxelbrew.util.BugReport;
import picocli.CommandLine.Command;

@Command(
        name = "report-bug",
        mixinStandardHelpOptions = true,
        description = "Open the VoxelBrew bug report form in your web browser")
final class ReportBugCommand implements Runnable {
    ReportBugCommand() {}

    @Override
    public void run() {
        BugReport.open();
    }
}
