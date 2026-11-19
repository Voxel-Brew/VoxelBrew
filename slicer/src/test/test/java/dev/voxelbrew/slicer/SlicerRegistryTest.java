package dev.voxelbrew.slicer;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.slicer.*;
import java.nio.file.*;
import java.util.ServiceLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SlicerRegistryTest {
    @TempDir
    Path directory;

    @Test
    void serviceDiscoversBuiltInsAndIndependentProviderAndReleasesTheJar() throws Exception {
        Path jar = Files.copy(Path.of(System.getProperty("voxelbrew.test.plugin")), directory.resolve("plugin.jar"));
        var registry =
                ServiceLoader.load(SlicerProviderRegistry.class).findFirst().orElseThrow();
        try (registry) {
            registry.withDirectory(directory);
            assertThat(registry.descriptors())
                    .extracting(SlicerDescriptor::id)
                    .containsExactly("cura", "orca", "probe-slicer", "prusa-slicer");
            var provider = registry.require("probe-slicer");
            assertThat(provider.create()).isNotSameAs(provider.create());
        }
        assertThatThrownBy(registry::descriptors).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> registry.require("probe-slicer")).isInstanceOf(IllegalStateException.class);
        Files.delete(jar);
    }

    @Test
    void brokenAndDuplicateSuppliedProvidersDoNotHideValidProviders() throws Exception {
        SlicerProvider duplicate = new SlicerProvider() {
            @Override
            public SlicerDescriptor descriptor() {
                return new SlicerDescriptor("cura", "Duplicate", "1");
            }

            @Override
            public Slicer create() {
                throw new AssertionError("Discovery created a slicer");
            }
        };
        SlicerProvider broken = new SlicerProvider() {
            @Override
            public SlicerDescriptor descriptor() {
                throw new IllegalArgumentException("Broken");
            }

            @Override
            public Slicer create() {
                throw new AssertionError("Discovery created a slicer");
            }
        };
        try (var registry = new SlicerRegistry(java.util.List.of(duplicate, broken))) {
            assertThat(registry.require("cura")).isSameAs(duplicate);
            assertThat(registry.descriptors())
                    .extracting(SlicerDescriptor::id)
                    .containsExactly("cura", "orca", "prusa-slicer");
        }
    }
}
