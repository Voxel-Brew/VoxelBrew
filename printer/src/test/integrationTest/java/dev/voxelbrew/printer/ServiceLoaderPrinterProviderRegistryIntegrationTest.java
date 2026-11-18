package dev.voxelbrew.printer;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.printer.driver.DriverRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServiceLoaderPrinterProviderRegistryIntegrationTest {
    @TempDir
    Path directory;

    @Test
    void discoversInstalledProviderAndReleasesItsJarOnClose() throws Exception {
        Path source = directory.resolve("src/plugin/InstalledProvider.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package plugin;
                public final class InstalledProvider implements dev.voxelbrew.api.printer.driver.Driver {
                    @Override public String id() { return "installed"; }
                    @Override public String name() { return "Installed"; }
                    @Override public PrintResult print(dev.voxelbrew.api.printer.driver.DriverConfiguration c, dev.voxelbrew.api.model.printing.PrintArtifact g) { throw new UnsupportedOperationException(); }
                    @Override public dev.voxelbrew.api.printer.driver.DriverStatus readPrinterStatus(dev.voxelbrew.api.printer.driver.DriverConfiguration c) { throw new UnsupportedOperationException(); }
                    @Override public dev.voxelbrew.api.printer.driver.DriverGui gui() { throw new UnsupportedOperationException(); }
                }
                """);
        Path classes = Files.createDirectories(directory.resolve("classes"));
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("Tests require a JDK").isNotNull();
        assertThat(compiler.run(
                        null,
                        null,
                        null,
                        "-classpath",
                        System.getProperty("java.class.path"),
                        "-d",
                        classes.toString(),
                        source.toString()))
                .isZero();

        Path plugins = Files.createDirectories(directory.resolve("plugins"));
        Path jar = plugins.resolve("installed.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("plugin/InstalledProvider.class"));
            Files.copy(classes.resolve("plugin/InstalledProvider.class"), output);
            output.closeEntry();
            output.putNextEntry(new JarEntry("META-INF/services/dev.voxelbrew.api.printer.driver.Driver"));
            output.write("plugin.InstalledProvider\n".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }

        try (var registry = java.util.ServiceLoader.load(DriverRegistry.class)
                .findFirst()
                .orElseThrow()
                .withDirectory(plugins)) {
            var provider = registry.find("installed").orElseThrow();
            assertThat(provider.id()).isEqualTo("installed");
            assertThat(provider.name()).isEqualTo("Installed");
            assertThat(provider.getClass().getClassLoader())
                    .isNotSameAs(getClass().getClassLoader());
            assertThatThrownBy(provider::gui).isInstanceOf(UnsupportedOperationException.class);
        }
        Files.delete(jar);
        assertThat(jar).doesNotExist();
    }
}
