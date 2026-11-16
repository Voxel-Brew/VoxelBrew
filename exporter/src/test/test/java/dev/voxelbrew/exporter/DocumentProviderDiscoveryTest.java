package dev.voxelbrew.exporter;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.model.CadDocument;
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
                assertEquals(id, registry.require(id).descriptor().id());
                assertEquals(
                        dev.voxelbrew.util.update.BuildVersion.current(),
                        registry.require(id).descriptor().version());
                assertNotNull(registry.require(id).create());
            }
        }
    }

    @Test
    void newlyInstalledApiOnlyJarIsDiscoveredByANewRegistry() throws Exception {
        var document = CadDocument.empty();
        try (var registry = new dev.voxelbrew.api.exporter.ServiceLoaderExporterProviderRegistry(directory)) {
            assertTrue(registry.descriptors().stream()
                    .noneMatch(descriptor -> descriptor.id().equals("document-probe")));
        }
        Files.copy(Path.of(System.getProperty("voxelbrew.test.plugin")), directory.resolve("provider.jar"));
        try (var registry = new dev.voxelbrew.api.exporter.ServiceLoaderExporterProviderRegistry(directory)) {
            var provider = registry.require("document-probe");
            assertEquals("Document probe", provider.descriptor().name());
            assertEquals("1.0", provider.descriptor().version());
            assertNotSame(getClass().getClassLoader(), provider.getClass().getClassLoader());
            Path target = directory.resolve("report.txt");
            provider.create().export(document, dev.voxelbrew.util.testing.FileExportWindows.destination(target, false));
            assertEquals("document=" + document.id(), Files.readString(target));
            assertSame(provider, registry.require("document-probe"));
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
            assertEquals("vxb", registry.require("vxb").descriptor().id());
            assertEquals("json", registry.require("json").descriptor().id());
        } finally {
            logger.removeHandler(handler);
        }
        assertTrue(warnings.stream()
                .anyMatch(record -> record.getThrown() != null
                        && record.getThrown().toString().contains("missing.Provider")));
    }
}
