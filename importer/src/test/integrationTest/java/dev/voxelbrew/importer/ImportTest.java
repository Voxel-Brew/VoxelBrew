package dev.voxelbrew.importer;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.importer.*;
import dev.voxelbrew.engine.model.*;
import dev.voxelbrew.engine.persistence.DocumentCodec;
import dev.voxelbrew.util.testing.FileImportWindows;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImportTest {
    @TempDir
    Path directory;

    private static ImportWindow values(Path source, Map<String, String> options) {
        return new ImportWindow() {
            @Override
            public <R> Optional<R> show(ImportDialog<R> dialog) {
                var supplied = new LinkedHashMap<>(options);
                supplied.put("source", source.toString());
                var ids = new HashSet<String>();
                collect(dialog.layout(), ids);
                if (!ids.containsAll(supplied.keySet())) throw new IllegalArgumentException("Unknown import option");
                return Optional.of(dialog.buildSettings(supplied));
            }
        };
    }

    private static void collect(dev.voxelbrew.api.vui.Component component, Set<String> ids) {
        if (component instanceof dev.voxelbrew.api.vui.Input input) ids.add(input.id());
        else if (component instanceof dev.voxelbrew.api.vui.HorizontalLayout row)
            row.children().forEach(child -> collect(child, ids));
        else if (component instanceof dev.voxelbrew.api.vui.VerticalLayout column)
            column.children().forEach(child -> collect(child, ids));
    }

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
        try (var registry = new ServiceLoaderImporterProviderRegistry(plugins)) {
            assertThat(registry.descriptors().stream()
                            .map(ImporterDescriptor::id)
                            .toList())
                    .containsExactly("failing", "json", "plate", "vxb");
            var document = registry.require("plate")
                    .create()
                    .importFrom(values(source, Map.of("scale", "2")))
                    .orElseThrow();
            try (var evaluation = Evaluator.evaluate(document)) {
                assertThat(evaluation.valid()).isTrue();
                assertThat(evaluation.bodies().getFirst().solid().volume())
                        .isCloseTo(76800.0, org.assertj.core.data.Offset.offset(.001));
            }
            assertThat(document.id()).isEqualTo("imported-plate");
            assertThat(document.features()).hasSize(2);
            assertThatThrownBy(
                            () -> registry.require("plate").create().importFrom(values(source, Map.of("scale", "NaN"))))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> registry.require("plate").create().importFrom(values(source, Map.of("typo", "1"))))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> registry.require("failing").create().importFrom(values(source, Map.of())))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("Deliberate importer failure");
        }
        assertThat(Files.readString(source)).isEqualTo("60,40,4");
        Files.delete(jar);
    }

    @Test
    void builtinsUseRegistryAndRejectOversizedInputs() throws Exception {
        Path source = directory.resolve("document.vxb");
        var document = CadDocument.empty();
        Files.write(source, DocumentCodec.encode(document));
        try (var registry = new ServiceLoaderImporterProviderRegistry(directory.resolve("no plugins"))) {
            assertThat(registry.require("vxb").create().importFrom(FileImportWindows.source(source)))
                    .contains(document);
            assertThatThrownBy(() -> registry.require("gcode")).isInstanceOf(IllegalArgumentException.class);
            try (var oversized = new RandomAccessFile(source.toFile(), "rw")) {
                oversized.setLength(ImportInput.MAX_INPUT_BYTES + 1);
            }
            assertThatThrownBy(() -> registry.require("vxb").create().importFrom(FileImportWindows.source(source)))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("256 MiB");
        }
    }
}
