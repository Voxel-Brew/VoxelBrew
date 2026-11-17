package dev.voxelbrew.exporter;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.model.CadDocument;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.jar.*;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentProviderDiscoveryTest {
    @TempDir
    Path directory;

    @Test
    void builtInProvidersAreLoadedFromServiceRegistrations() throws Exception {
        try (var registry = new dev.voxelbrew.api.exporter.ServiceLoaderExporterProviderRegistry(directory)) {
            for (String id : java.util.List.of(
                    "vxb", "json", "gcode", "gcode-package", "step", "stl", "3mf", "obj", "prusa-project")) {
                assertThat(registry.require(id).descriptor().id()).isEqualTo(id);
                assertThat(registry.require(id).descriptor().version())
                        .isEqualTo(dev.voxelbrew.util.update.BuildVersion.current());
                assertThat(registry.require(id).create()).isNotNull();
            }
        }
    }

    @Test
    void newlyInstalledApiOnlyJarIsDiscoveredByANewRegistry() throws Exception {
        var document = CadDocument.empty();
        try (var registry = new dev.voxelbrew.api.exporter.ServiceLoaderExporterProviderRegistry(directory)) {
            assertThat(registry.descriptors().stream()
                            .noneMatch(descriptor -> descriptor.id().equals("document-probe")))
                    .isTrue();
        }
        Files.copy(Path.of(System.getProperty("voxelbrew.test.plugin")), directory.resolve("provider.jar"));
        try (var registry = new dev.voxelbrew.api.exporter.ServiceLoaderExporterProviderRegistry(directory)) {
            var provider = registry.require("document-probe");
            assertThat(provider.descriptor().name()).isEqualTo("Document probe");
            assertThat(provider.descriptor().version()).isEqualTo("1.0");
            assertThat(provider.getClass().getClassLoader())
                    .isNotSameAs(getClass().getClassLoader());
            Path target = directory.resolve("report.txt");
            provider.create().export(document, dev.voxelbrew.util.testing.FileExportWindows.destination(target, false));
            assertThat(Files.readString(target)).isEqualTo("document=" + document.id());
            assertThat(registry.require("document-probe")).isSameAs(provider);
        }
        // Closing the registry releases the JAR for replacement on Windows.
        Files.delete(directory.resolve("provider.jar"));
    }

    @Test
    void brokenProvidersCannotReplaceBuiltinsOrHideHealthyExporters() throws Exception {
        Path jar = directory.resolve("broken.jar");
        try (var out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("META-INF/services/dev.voxelbrew.api.exporter.ExporterProvider"));
            out.write(("missing.Provider\ndev.voxelbrew.exporter.VoxelBrewExporterProvider\n")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.closeEntry();
        }
        var warnings = new ArrayList<LogRecord>();
        var logger = Logger.getLogger("dev.voxelbrew.api.exporter.ServiceLoaderExporterProviderRegistry");
        var handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                warnings.add(record);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        logger.addHandler(handler);
        try (var registry = new dev.voxelbrew.api.exporter.ServiceLoaderExporterProviderRegistry(directory)) {
            assertThat(registry.require("vxb").descriptor().id()).isEqualTo("vxb");
            assertThat(registry.require("json").descriptor().id()).isEqualTo("json");
        } finally {
            logger.removeHandler(handler);
        }
        assertThat(warnings.stream()
                        .anyMatch(record -> record.getThrown() != null
                                && record.getThrown().toString().contains("missing.Provider")))
                .isTrue();
    }
}
