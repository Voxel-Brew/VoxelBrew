package dev.voxelbrew.slicer;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.model.printing.*;
import dev.voxelbrew.engine.persistence.DocumentCodec;
import dev.voxelbrew.engine.printing.*;
import dev.voxelbrew.printer.DefaultPrinterProfileCatalog;
import dev.voxelbrew.printer.ProfileHardware;
import dev.voxelbrew.slicer.orca.OrcaSlicerProvider;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

@EnabledIfEnvironmentVariable(named = "VOXELBREW_ORCA_TEST", matches = ".+")
class OrcaSlicerIntegrationTest {
    @TempDir
    Path directory;

    @Test
    void realOrcaPackagePreservesPreviewAndDocumentBytes() throws Exception {
        Path fixtures = Path.of(System.getenv("VOXELBREW_ORCA_TEST"));
        var options = Map.of(
                "executable",
                fixtures.resolve("orca/orca-slicer.exe").toString(),
                "machine",
                fixtures.resolve("machine.json").toString(),
                "process",
                fixtures.resolve("process.json").toString(),
                "filament",
                fixtures.resolve("filament.json").toString());
        var session = PrintingTest.reference();
        var catalog = new DefaultPrinterProfileCatalog(directory.resolve("profiles")).load();
        var setup =
                session.document().printSetup().withPrinter(ProfileHardware.resolve(catalog.require("bambu-lab-a1")));
        session.setPrintSetup(setup.withProfile("orca", setup.printerId(), setup.materialId(), setup.process()));
        var model = PrintingTest.twoFilamentModel(session, true);
        session.setBodyStyle("other.body", new dev.voxelbrew.api.model.BodyStyle("PLA", "#55BB22"));
        var palette = PlateFilaments.from(session.document(), model);
        var result = SliceService.slice(
                        new OrcaSlicerProvider(),
                        model,
                        palette,
                        SlicerTestSupport.window(options),
                        new Cancellation(),
                        System.out::println)
                .orElseThrow();
        assertThat(PlateFilaments.read(result.artifact().metadata())).isEqualTo(palette);
        assertThat(result.job().lines())
                .anyMatch(line ->
                        line.startsWith("; filament_colour") && line.contains("#55BB22") && line.contains("#AA0033"));
        var usage = dev.voxelbrew.importer.FilamentUsage.fromLines(result.job().lines());
        assertThat(usage.filaments())
                .extracting(dev.voxelbrew.importer.FilamentUsage.Filament::slot)
                .containsExactly(1, 2);
        assertThat(result.artifact()).isNotNull();
        assertThat(result.job().modelLayers().isEmpty()).isFalse();
        assertThat(result.artifact().metadata().get("model")).isEqualTo("Bambu Lab A1");
        Path packageFile = directory.resolve("plate.gcode.3mf");
        Files.write(packageFile, result.artifact().bytes());
        try (var zip = new ZipFile(packageFile.toFile())) {
            assertThat(zip.getEntry("Metadata/plate_1.gcode")).isNotNull();
        }
        var document = session.document()
                .withGcode(Map.of(setup.activePlateId(), result.job().lines()))
                .withPrintArtifacts(Map.of(setup.activePlateId(), result.artifact()));
        var reopened = DocumentCodec.decode(DocumentCodec.encode(document));
        assertThat(reopened.printArtifacts().get(setup.activePlateId()).bytes())
                .containsExactly(result.artifact().bytes());
    }
}
