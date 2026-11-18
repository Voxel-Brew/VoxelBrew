package dev.voxelbrew.util.config;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/** User preferences and provider settings; implementations preserve unknown keys. */
public interface UserConfiguration {
    static UserConfiguration user() {
        return dev.voxelbrew.util.services.Services.load(UserConfiguration.class);
    }

    Path path();

    UserConfiguration section(String namespace, String id) throws IOException;

    Map<String, String> read() throws IOException;

    String get(String key, String fallback) throws IOException;

    Path defaultSaveFolder() throws IOException;

    void set(String key, String value) throws IOException;

    void remove(String key) throws IOException;

    void update(Map<String, String> values, Set<String> removed) throws IOException;

    void setIfAbsent(String key, String value) throws IOException;
}
