package dev.voxelbrew.util.services;

import static org.assertj.core.api.Assertions.*;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ServiceConfigurationError;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServicesTest {
    @TempDir
    Path directory;

    public interface Example {}

    public static final class First implements Example {
        public First() {}
    }

    public static final class Second implements Example {
        public Second() {}
    }

    private URLClassLoader providers(String... names) throws Exception {
        var descriptor = directory.resolve("META-INF/services/" + Example.class.getName());
        Files.createDirectories(descriptor.getParent());
        Files.writeString(descriptor, String.join("\n", names));
        return new URLClassLoader(
                new java.net.URL[] {directory.toUri().toURL()}, getClass().getClassLoader());
    }

    @Test
    void discoversOneProviderWithoutCachingInstances() throws Exception {
        try (var loader = providers(First.class.getName())) {
            var first = Services.load(Example.class, loader);
            assertThat(first).isInstanceOf(First.class);
            assertThat(Services.load(Example.class, loader)).isNotSameAs(first);
        }
    }

    @Test
    void missingProviderIdentifiesService() throws Exception {
        try (var loader = providers()) {
            assertThatThrownBy(() -> Services.load(Example.class, loader))
                    .isInstanceOf(ServiceConfigurationError.class)
                    .hasMessageContaining(Example.class.getName())
                    .hasMessageContaining("found []");
        }
    }

    @Test
    void ambiguousProvidersIdentifyEveryCandidate() throws Exception {
        try (var loader = providers(First.class.getName(), Second.class.getName())) {
            assertThatThrownBy(() -> Services.load(Example.class, loader))
                    .isInstanceOf(ServiceConfigurationError.class)
                    .hasMessageContaining(Example.class.getName())
                    .hasMessageContaining(First.class.getName())
                    .hasMessageContaining(Second.class.getName());
        }
    }

    @Test
    void usesContextClassLoader() throws Exception {
        var thread = Thread.currentThread();
        var previous = thread.getContextClassLoader();
        try (var loader = providers(First.class.getName())) {
            thread.setContextClassLoader(loader);
            assertThat(Services.load(Example.class)).isInstanceOf(First.class);
        } finally {
            thread.setContextClassLoader(previous);
        }
    }
}
