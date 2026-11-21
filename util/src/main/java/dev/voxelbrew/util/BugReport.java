package dev.voxelbrew.util;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;

/** Opens the public issue form without collecting or submitting any data. */
public final class BugReport {
    public static final String URL = "https://github.com/Voxel-Brew/VoxelBrew/issues/new?template=bug_report.md";

    public static void open() {
        try {
            Desktop.getDesktop().browse(URI.create(URL));
        } catch (IOException | RuntimeException failure) {
            throw new IllegalStateException("Could not open the browser. Report a bug at " + URL, failure);
        }
    }

    private BugReport() {}
}
