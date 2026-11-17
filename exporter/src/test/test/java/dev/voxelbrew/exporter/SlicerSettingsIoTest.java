package dev.voxelbrew.exporter;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.exporter.*;
import dev.voxelbrew.api.importer.ServiceLoaderImporterProviderRegistry;
import dev.voxelbrew.api.model.*;
import dev.voxelbrew.api.model.mesh.*;
import dev.voxelbrew.api.model.printing.*;
import dev.voxelbrew.engine.model.*;
import dev.voxelbrew.engine.persistence.proto.DocumentProto;
import dev.voxelbrew.engine.printing.*;
import dev.voxelbrew.importer.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SlicerSettingsIoTest {
    @TempDir
    Path directory;

    private PrintSetup setup() {
        return PrintSetup.empty()
                .withProfile(
                        "prusa-slicer",
                        "generic-marlin-200-0.4",
                        "PETG",
                        new ProcessSettings(0.25, 4, 35, true, true, 55, 0.3, 4, 6, 2))
                .withInstances(List.of(new BodyInstance("part", "body", Transform.translation(20, 30, 0))))
                .withSupports(List.of(new SupportVolume("block", "part", SupportVolume.Role.BLOCKER, 1, 2, 3, 4, 5, 6)))
                .renamePlate("plate-1", "Main")
                .addPlate("spare")
                .selectPlate("plate-1")
                .withWorkspaceSettings(Map.of(
                        "print-output",
                        "custom.gcode",
                        "print-overwrite",
                        "true",
                        "print-walls",
                        "5",
                        "print-support-width",
                        "2 + 3",
                        "print-rotation-space",
                        "LOCAL"));
    }

    @Test
    void bothDocumentProviderPairsPreserveAllAppliedSettingsAndDraftsWithoutModifyingInput() throws Exception {
        var expected = setup();
        var document = new CadDocument("settings", List.of(), 0, expected);
        try (var exporters = new dev.voxelbrew.api.exporter.ServiceLoaderExporterProviderRegistry(directory);
                var importers = new ServiceLoaderImporterProviderRegistry(directory)) {
            for (String format : List.of("vxb", "json")) {
                Path file = directory.resolve("settings." + format);
                exporters
                        .require(format)
                        .create()
                        .export(document, dev.voxelbrew.util.testing.FileExportWindows.destination(file, false));
                byte[] before = Files.readAllBytes(file);
                var imported = importers
                        .require(format)
                        .create()
                        .importFrom(dev.voxelbrew.util.testing.FileImportWindows.source(file))
                        .orElseThrow();
                assertThat(imported.printSetup()).isEqualTo(expected);
                assertThat(Files.readAllBytes(file)).containsExactly(before);
                Path copy = directory.resolve("copy-" + format + ".vxb");
                exporters
                        .require("vxb")
                        .create()
                        .export(imported, dev.voxelbrew.util.testing.FileExportWindows.destination(copy, false));
                assertThat(DocumentReader.load(copy).printSetup()).isEqualTo(expected);
            }
        }
    }

    @Test
    void directThreeMfFileWriterKeepsManufacturingMetadata() throws Exception {
        var setup = setup();
        var mesh = MeshGeometry.box(
                new SupportVolume("box", "part", SupportVolume.Role.SOLID_SUPPORT, 0, 0, 0, 10, 10, 10));
        var model = new ManufacturingModel(
                "settings", "revision", setup, PrintProfile.resolve(setup), Map.of("body", mesh));
        Path file = directory.resolve("direct.3mf");
        new ThreeMfExporter()
                .writeFile(
                        ModelViews.from(model),
                        new MeshExportDialog.Settings(
                                new ExportSelection(
                                        ExportSelection.Space.BUILD_PLATE, ExportSelection.Contents.MODEL, null, null),
                                MeshOptions.DEFAULT,
                                Map.of(),
                                new FileExportDialog.Destination(file, false)));
        try (var zip = new ZipFile(file.toFile());
                var input = zip.getInputStream(zip.getEntry("3D/3dmodel.model"))) {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            var xml = factory.newDocumentBuilder().parse(input);
            var metadata = xml.getElementsByTagNameNS("*", "metadata");
            assertThat(metadata.getLength()).isEqualTo(1);
            var saved = DocumentProto.PrintSetup.newBuilder();
            com.google.protobuf.util.JsonFormat.parser().merge(metadata.item(0).getTextContent(), saved);
            assertThat(saved.getWorkspaceSettingsMap()).isEqualTo(setup.workspaceSettings());
            assertThat(saved.getProcess().getWalls()).isEqualTo(4);
            assertThat(saved.getPlatesCount()).isEqualTo(2);
            assertThat(saved.getActivePlateId()).isEqualTo("plate-1");
        }
    }
}
