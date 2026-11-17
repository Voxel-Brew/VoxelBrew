package dev.voxelbrew.slicer;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.engine.mesh.MeshOptions;
import dev.voxelbrew.engine.persistence.DocumentCodec;
import dev.voxelbrew.engine.printing.*;
import dev.voxelbrew.slicer.orca.OrcaSlicerBackend;
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
        var backend = new OrcaSlicerBackend()
                .configure(Map.of(
                        "executable",
                        fixtures.resolve("orca/orca-slicer.exe").toString(),
                        "machine",
                        fixtures.resolve("machine.json").toString(),
                        "process",
                        fixtures.resolve("process.json").toString(),
                        "filament",
                        fixtures.resolve("filament.json").toString()));
        var session = PrintingTest.reference();
        var catalog = new DefaultPrinterProfileCatalog(new PrinterResources(directory.resolve("printers"))).load();
        var setup = session.document().printSetup().withPrinter(catalog.require("bambu-lab-a1"));
        session.setPrintSetup(setup.withProfile("orca", setup.printerId(), setup.materialId(), setup.process()));
        var model = ManufacturingModels.evaluate(session.document(), MeshOptions.DEFAULT);
        var result = SliceService.slice(
                backend,
                session.document(),
                model,
                null,
                directory.resolve("plate.gcode"),
                false,
                new Cancellation(),
                System.out::println,
                dev.voxelbrew.slicer.api.Slicing.DIRECT);
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
