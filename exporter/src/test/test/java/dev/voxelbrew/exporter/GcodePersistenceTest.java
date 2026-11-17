package dev.voxelbrew.exporter;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.exporter.*;
import dev.voxelbrew.engine.model.*;
import dev.voxelbrew.engine.persistence.DocumentCodec;
import dev.voxelbrew.importer.*;
import dev.voxelbrew.importer.api.ImportResult;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GcodePersistenceTest {
    @Test
    void packagedJobRoundTripsAndExportsExactBytes() throws Exception {
        byte[] bytes = {0x50, 0x4b, 1, 2, 3};
        var base = CadDocument.empty();
        var plate = base.printSetup().activePlateId();
        var artifact =
                dev.voxelbrew.engine.printing.PrintArtifact.of("gcode.3mf", bytes, Map.of("model", "Bambu Lab A1"));
        var document = base.withGcode(Map.of(plate, LINES)).withPrintArtifacts(Map.of(plate, artifact));
        try (var exports = new dev.voxelbrew.api.exporter.ServiceLoaderExporterProviderRegistry(directory);
                var imports = new ImporterRegistry(directory)) {
            for (String format : List.of("vxb", "json")) {
                Path file = directory.resolve("packaged." + format);
                exports.require(format)
                        .create()
                        .export(document, dev.voxelbrew.util.testing.FileExportWindows.destination(file, false));
                var loaded = ((ImportResult.Document) ImportService.read(imports, format, file, Map.of())).document();
                assertThat(loaded.printArtifacts()).isEqualTo(document.printArtifacts());
                Path output = directory.resolve(format + ".gcode.3mf");
                exports.require("gcode-package")
                        .create()
                        .export(loaded, dev.voxelbrew.util.testing.FileExportWindows.destination(output, false));
                assertThat(Files.readAllBytes(output)).containsExactly(bytes);
            }
        }
    }

    @TempDir
    Path directory;

    private static final List<String> LINES = List.of(
            "; UTF-8: żółć",
            "",
            "G21",
            "G90",
            "M82",
            "G1 Z0.2",
            ";TYPE:Perimeter",
            "G1 X10 Y10 E1 F1200",
            "M104 S0",
            "");

    @Test
    void providersRoundTripEveryLineAndExportTheActivePlate() throws Exception {
        var base = CadDocument.empty();
        var document =
                base.withGcode(Map.of(base.printSetup().activePlateId(), LINES, "plate-2", List.of("; other plate")));
        assertThat(DocumentCodec.fingerprint(document)).isEqualTo(DocumentCodec.fingerprint(base));
        try (var exports = new dev.voxelbrew.api.exporter.ServiceLoaderExporterProviderRegistry(directory);
                var imports = new ImporterRegistry(directory)) {
            for (String format : List.of("vxb", "json")) {
                Path file = directory.resolve("part." + format);
                exports.require(format)
                        .create()
                        .export(document, dev.voxelbrew.util.testing.FileExportWindows.destination(file, false));
                var loaded = ((ImportResult.Document) ImportService.read(imports, format, file, Map.of())).document();
                assertThat(loaded.gcode()).isEqualTo(document.gcode());
                Path output = directory.resolve(format + ".gcode");
                exports.require("gcode")
                        .create()
                        .export(loaded, dev.voxelbrew.util.testing.FileExportWindows.destination(output, false));
                assertThat(Files.readAllLines(output)).isEqualTo(LINES);
                var inspected = new DefaultImportRuntime().gcode().read(output);
                assertThat(inspected.lines()).isEqualTo(LINES);
                assertThat(inspected.modelLayers()).isEqualTo(List.of(0.2));
                assertThat(new DefaultImportRuntime().gcode().readLines(LINES)).isEqualTo(inspected);
            }
        }
    }

    @Test
    void missingJobAndOverwriteProtectionPreserveDestination() throws Exception {
        var base = CadDocument.empty();
        var document = base.withGcode(Map.of(base.printSetup().activePlateId(), LINES));
        Path output = directory.resolve("existing.gcode");
        Files.writeString(output, "preserved");
        var exporter = new GcodeExporterProvider().create();
        assertThatThrownBy(() ->
                        exporter.export(base, dev.voxelbrew.util.testing.FileExportWindows.destination(output, true)))
                .isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(() -> exporter.export(
                        document, dev.voxelbrew.util.testing.FileExportWindows.destination(output, false)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(Files.readString(output)).isEqualTo("preserved");
        exporter.export(document, dev.voxelbrew.util.testing.FileExportWindows.destination(output, true));
        assertThat(Files.readAllLines(output)).isEqualTo(LINES);
    }
}
