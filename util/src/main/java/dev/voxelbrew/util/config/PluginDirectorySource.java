package dev.voxelbrew.util.config;

import java.nio.file.Path;

/** Application context for exports that evaluate document features from plugins. */
public interface PluginDirectorySource {
    Path pluginDirectory();
}
