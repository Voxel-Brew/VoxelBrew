package dev.voxelbrew.gui;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import dev.voxelbrew.api.exporter.ExporterProviderRegistry;
import dev.voxelbrew.slicer.SlicerRegistry;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WindowResourcesTest {
    @TempDir
    Path directory;

    @Test
    @org.junit.jupiter.api.parallel.ResourceLock(org.junit.jupiter.api.parallel.Resources.SYSTEM_PROPERTIES)
    void windowLoadsExportersFromDefaultUserPluginDirectory() throws Exception {
        String home = System.getProperty("user.home");
        String override = System.getProperty("voxelbrew.home");
        try {
            System.setProperty("user.home", directory.toString());
            System.clearProperty("voxelbrew.home");
            Path plugins = java.nio.file.Files.createDirectories(directory.resolve(".voxelbrew/plugins"));
            java.nio.file.Files.copy(
                    Path.of(System.getProperty("voxelbrew.test.plugin")), plugins.resolve("provider.jar"));
            var resources = new WindowResources(
                    dev.voxelbrew.util.config.ApplicationPaths.plugins(),
                    () -> java.util.ServiceLoader.load(ExporterProviderRegistry.class)
                            .findFirst()
                            .orElseThrow(),
                    SlicerRegistry::new);
            try {
                assertThat(resources
                                .exporters
                                .require("document-probe")
                                .descriptor()
                                .id())
                        .isEqualTo("document-probe");
            } finally {
                resources.close();
                resources.whenClosed().toCompletableFuture().get(10, TimeUnit.SECONDS);
            }
        } finally {
            System.setProperty("user.home", home);
            if (override == null) System.clearProperty("voxelbrew.home");
            else System.setProperty("voxelbrew.home", override);
        }
    }

    @Test
    void shutdownSignalsCompletionOnlyAfterCapturedWorkHasDrained() throws Exception {
        var resources = new WindowResources(
                directory,
                () -> java.util.ServiceLoader.load(ExporterProviderRegistry.class)
                        .findFirst()
                        .orElseThrow(),
                SlicerRegistry::new);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var disposal = resources.whenClosed().toCompletableFuture();
        try {
            var write = resources.files.submit(() -> {
                entered.countDown();
                assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                return "published";
            });
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            assertTimeoutPreemptively(
                    Duration.ofSeconds(2), resources::close, "Window close must not block the JavaFX thread");
            resources.close();
            assertThat(disposal.isDone())
                    .as("A draining writer still needs its plugin resources")
                    .isFalse();
            release.countDown();
            disposal.get(10, TimeUnit.SECONDS);
            assertThat(write.get()).isEqualTo("published");
            assertThat(List.of(
                                    resources.rebuild,
                                    resources.files,
                                    resources.slicing,
                                    resources.validation,
                                    resources.appearance)
                            .stream()
                            .allMatch(ExecutorService::isTerminated))
                    .isTrue();
        } finally {
            release.countDown();
            resources.close();
            disposal.get(10, TimeUnit.SECONDS);
        }
    }
}
