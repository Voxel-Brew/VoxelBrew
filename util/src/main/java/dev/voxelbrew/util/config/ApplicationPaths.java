package dev.voxelbrew.util.config;

import java.nio.file.Path;

/** User-local application files, independent of documents and installation directories. */
public interface ApplicationPaths {
    Path home();

    Path configuration();

    default Path plugins() {
        return home().resolve("plugins");
    }

    default Path profiles() {
        return home().resolve("profiles");
    }

    default Path printers() {
        return home().resolve("printers");
    }

    default Path logs() {
        return home().resolve("log");
    }
}
