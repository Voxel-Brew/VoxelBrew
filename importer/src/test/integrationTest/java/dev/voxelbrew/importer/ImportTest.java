package dev.voxelbrew.importer;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.engine.model.*;
import dev.voxelbrew.engine.persistence.DocumentCodec;
import dev.voxelbrew.importer.api.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@SuppressWarnings("deprecation")
class ImportTest {
    @TempDir
    Path directory;

    @Test
    void independentProviderUsesApiOnlyAndReturnsEditableCad() throws Exception {
        Path plugins = Files.createDirectories(directory.resolve("trusted plugins with spaces"));
        Path jar = Files.copy(
                Path.of(System.getProperty("voxelbrew.test.plugin")), plugins.resolve("external importer.jar"));
        try (var archive = new java.util.zip.ZipFile(jar.toFile())) {
            assertThat(archive.stream().noneMatch(e -> e.getName().startsWith("dev/voxelbrew/")))
                    .as("Host APIs are not bundled")
                    .isTrue();
        }
        Path source = directory.resolve("source plate.vbplate");
        Files.writeString(source, "60,40,4");
        try (var registry = new ImporterRegistry(plugins)) {
            assertThat(registry.descriptors().stream()
                            .map(ImporterDescriptor::id)
                            .toList())
                    .isEqualTo(List.of("failing", "json", "plate", "vxb"));
            String diagnostics = String.join("\n", registry.diagnostics());
            assertThat(diagnostics.contains("Duplicate importer ID: plate"))
                    .as(diagnostics)
                    .isTrue();
            assertThat(diagnostics.contains("Incompatible importer future"))
                    .as(diagnostics)
                    .isTrue();
            assertThat(diagnostics.contains("Broken descriptor"))
                    .as(diagnostics)
                    .isTrue();
            var document = ((ImportResult.Document) ImportService.read(registry, "plate", source, Map.of("scale", "2")))
                    .document();
            try (var evaluation = Evaluator.evaluate(document)) {
                assertThat(evaluation.valid()).isTrue();
                assertThat(evaluation.bodies().getFirst().solid().volume())
                        .isCloseTo(76800, org.assertj.core.data.Offset.offset(.001));
            }
            assertThat(document.id()).isEqualTo("imported-plate");
            assertThat(document.features().size()).isEqualTo(2);
            assertThatThrownBy(() -> ImportService.read(registry, "plate", source, Map.of("scale", "NaN")))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> ImportService.read(registry, "plate", source, Map.of("typo", "1")))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(org.assertj.core.api.Assertions.catchThrowableOfType(
                                    () -> ImportService.read(registry, "failing", source, Map.of()), IOException.class)
                            .getMessage()
                            .contains("Deliberate importer failure"))
                    .isTrue();
        }
        assertThat(Files.readString(source)).isEqualTo("60,40,4");
        // Windows also verifies the registry released its provider JAR.
        Files.delete(jar);
    }

    @Test
    void builtinsUseTheSameRegistryAndRejectOversizedInputs() throws Exception {
        Path source = directory.resolve("document.vxb");
        var document = CadDocument.empty();
        Files.write(source, DocumentCodec.encode(document));
        try (var registry = new ImporterRegistry(directory.resolve("no plugins"))) {
            assertThat(((ImportResult.Document) ImportService.read(registry, "vxb", source, Map.of())).document())
                    .isEqualTo(document);
            assertThatThrownBy(() -> registry.require("gcode")).isInstanceOf(IllegalArgumentException.class);
            try (var oversized = new RandomAccessFile(source.toFile(), "rw")) {
                oversized.setLength(ImportService.MAX_INPUT_BYTES + 1);
            }
            assertThat(org.assertj.core.api.Assertions.catchThrowableOfType(
                                    () -> ImportService.read(registry, "vxb", source, Map.of()), IOException.class)
                            .getMessage()
                            .contains("256 MiB"))
                    .isTrue();
        }
    }
}
