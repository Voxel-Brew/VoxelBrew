package dev.voxelbrew.exporter;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.exporter.*;
import dev.voxelbrew.api.model.CadDocument;
import dev.voxelbrew.api.model.Feature;
import dev.voxelbrew.api.model.mesh.MeshOptions;
import dev.voxelbrew.engine.printing.ManufacturingModels;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UnifiedExportTest {
    @TempDir
    Path directory;

    private static ExportWindow window(Path target, boolean overwrite) {
        return new ExportWindow() {
            @Override
            public <R> Optional<R> show(ExportDialog<R> dialog) {
                return Optional.of(dialog.buildSettings(
                        Map.of("destination", target.toString(), "overwrite", Boolean.toString(overwrite))));
            }
        };
    }

    @Test
    void prusaProjectUsesSlicerProcessAndPlacementEvenWhenDesignSpaceWasRequested() throws Exception {
        var base = ExportTest.reference();
        var setup = dev.voxelbrew.api.model.printing.PrintSetup.empty()
                .withProfile(
                        "prusa-slicer",
                        "generic-marlin-200-0.4",
                        "PETG",
                        new dev.voxelbrew.api.model.printing.ProcessSettings(0.25, 4, 35, true, true, 55, 0.3, 4, 6, 2))
                .withInstances(List.of(new dev.voxelbrew.api.model.BodyInstance(
                        "placed", "thickness.body", dev.voxelbrew.api.model.Transform.translation(25, 35, 0))));
        var document = new CadDocument(base.id(), base.features(), base.cursor(), setup);
        Path target = directory.resolve("native-project.3mf");
        var parent = window(target, false);
        try (var registry = new dev.voxelbrew.api.exporter.ServiceLoaderExporterProviderRegistry(directory)) {
            registry.require("prusa-project").create().export(document, parent);
        }
        try (var zip = new java.util.zip.ZipFile(target.toFile())) {
            String config;
            try (var input = zip.getInputStream(zip.getEntry("Metadata/Slic3r_PE.config"))) {
                config = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            for (String setting : List.of(
                    "layer_height = 0.25",
                    "perimeters = 4",
                    "fill_density = 35.0%",
                    "support_material = 1",
                    "support_material_buildplate_only = 1",
                    "support_material_threshold = 55.0",
                    "support_material_contact_distance = 0.3",
                    "support_material_interface_layers = 4",
                    "brim_width = 6.0",
                    "raft_layers = 2",
                    "filament_type = PETG")) {
                assertThat(config.contains("; " + setting + "\n")).as(setting).isTrue();
            }
            try (var input = zip.getInputStream(zip.getEntry("3D/3dmodel.model"))) {
                var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
                factory.setNamespaceAware(true);
                var vertices = factory.newDocumentBuilder().parse(input).getElementsByTagNameNS("*", "vertex");
                double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
                for (int i = 0; i < vertices.getLength(); i++) {
                    var vertex = (org.w3c.dom.Element) vertices.item(i);
                    minX = Math.min(minX, Double.parseDouble(vertex.getAttribute("x")));
                    minY = Math.min(minY, Double.parseDouble(vertex.getAttribute("y")));
                }
                assertThat(minX).isCloseTo(25, org.assertj.core.data.Offset.offset(0.001));
                assertThat(minY).isCloseTo(35, org.assertj.core.data.Offset.offset(0.001));
            }
        }
    }

    @Test
    void threeMfPrefersSlicerPlateAndCarriesSettingsWhileObjKeepsDesignSpace() throws Exception {
        var base = ExportTest.reference();
        var setup = dev.voxelbrew.api.model.printing.PrintSetup.empty()
                .withInstances(List.of(new dev.voxelbrew.api.model.BodyInstance(
                        "placed", "thickness.body", dev.voxelbrew.api.model.Transform.translation(25, 35, 7))))
                .withWorkspaceSettings(Map.of("print-output", "custom.gcode"));
        var document = new CadDocument(base.id(), base.features(), base.cursor(), setup);
        Path target = directory.resolve("plate.3mf");
        var parent = window(target, false);
        new ThreeMfExporter(directory).export(document, parent);
        try (var zip = new java.util.zip.ZipFile(target.toFile());
                var input = zip.getInputStream(zip.getEntry("3D/3dmodel.model"))) {
            var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            var xml = factory.newDocumentBuilder().parse(input);
            var item = (org.w3c.dom.Element)
                    xml.getElementsByTagNameNS("*", "item").item(0);
            assertThat(item.getAttribute("transform")).isEqualTo("1.0 0.0 0.0 0.0 1.0 0.0 0.0 0.0 1.0 25.0 35.0 7.0");
            var metadata = xml.getElementsByTagNameNS("*", "metadata").item(0).getTextContent();
            var proto = dev.voxelbrew.engine.persistence.proto.DocumentProto.PrintSetup.newBuilder();
            com.google.protobuf.util.JsonFormat.parser().merge(metadata, proto);
            assertThat(proto.getWorkspaceSettingsOrThrow("print-output")).isEqualTo("custom.gcode");
            assertThat(proto.getInstances(0).getTransform(3)).isEqualTo(25);
        }
        Path obj = directory.resolve("design.obj");
        var objParent = window(obj, false);
        try (var registry = new dev.voxelbrew.api.exporter.ServiceLoaderExporterProviderRegistry(directory)) {
            registry.require("obj").create().export(document, objParent);
        }
        var xs = Files.readAllLines(obj).stream()
                .filter(line -> line.startsWith("v "))
                .mapToDouble(line -> Double.parseDouble(line.split("\\s+")[1]))
                .summaryStatistics();
        assertThat(xs.getMin()).isCloseTo(0, org.assertj.core.data.Offset.offset(0.001));
        assertThat(xs.getMax()).isCloseTo(60, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void threeMfRejectsBodiesMissingFromTheActivePlate() {
        var base = ExportTest.reference();
        var setup = dev.voxelbrew.api.model.printing.PrintSetup.empty()
                .withInstances(List.of(new dev.voxelbrew.api.model.BodyInstance(
                        "placed", "thickness.body", dev.voxelbrew.api.model.Transform.IDENTITY)));
        var features = new ArrayList<>(base.features());
        features.add(new Feature.Sketch(
                "other-sketch",
                "other.profile",
                new Feature.Plane("origin-xy", 0),
                new Feature.Rectangle(70, 0, 10, 10)));
        features.add(new Feature.Extrude("unplaced", new Feature.ProfileRef("other-sketch", "other.profile"), 8));
        var document = new CadDocument(base.id(), features, features.size(), setup);
        Path target = directory.resolve("unplaced.3mf");
        ExportWindow parent = new ExportWindow() {
            @Override
            public <R> Optional<R> show(ExportDialog<R> dialog) {
                return Optional.of(dialog.buildSettings(Map.of(
                        "destination", target.toString(),
                        "space", "DESIGN",
                        "body", "unplaced.body")));
            }
        };
        var exporter = new ThreeMfExporter(directory);
        assertThatThrownBy(() -> exporter.export(document, parent))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown body unplaced.body");
        assertThat(target).doesNotExist();
    }

    @Test
    void builtInGeometryUsesTheSameProviderContractAndRejectsRollback() throws Exception {
        var document = ExportTest.reference();
        try (var registry = new dev.voxelbrew.api.exporter.ServiceLoaderExporterProviderRegistry(directory)) {
            for (String id : List.of("stl", "3mf", "obj", "step")) {
                Path target = directory.resolve("unified." + id);
                var parent = window(target, true);
                var exporter = registry.require(id).create();
                var accepted = exporter.export(document, parent).orElseThrow();
                var destination = accepted instanceof FileExportDialog.Destination file
                        ? file
                        : ((MeshExportDialog.Settings) accepted).destination();
                assertThat(destination.path()).isEqualTo(target);
                byte[] original = Files.readAllBytes(target);
                assertThat(original.length > 100).isTrue();
                var rollback = new CadDocument(document.id(), document.features(), document.cursor() - 1);
                assertThatThrownBy(() -> exporter.export(rollback, parent)).isInstanceOf(Exception.class);
                assertThat(Files.readAllBytes(target)).containsExactly(original);
            }
        }
    }

    @Test
    void cancelledMeshDialogDoesNotScheduleOrEvaluateAnything() throws Exception {
        try (var registry = new dev.voxelbrew.api.exporter.ServiceLoaderExporterProviderRegistry(directory)) {
            for (String id : List.of("stl", "3mf", "obj", "prusa-project")) {
                var exporter = registry.require(id).create();
                ExportWindow parent = new ExportWindow() {
                    @Override
                    public <R> Optional<R> show(ExportDialog<R> dialog) {
                        return Optional.empty();
                    }
                };
                assertThat(exporter.export(CadDocument.empty(), parent)).isEqualTo(Optional.empty());
            }
        }
    }

    @Test
    void snapshotFromAnotherDocumentIsRejectedBeforeWriting() throws Exception {
        var source = ExportTest.reference();
        var snapshot = ManufacturingModels.evaluate(source, MeshOptions.DEFAULT);
        var target = directory.resolve("mismatched.stl");
        var settings = new MeshExportDialog.Settings(
                new ExportSelection(ExportSelection.Space.BUILD_PLATE, ExportSelection.Contents.MODEL, null, null),
                MeshOptions.DEFAULT,
                Map.of(),
                new FileExportDialog.Destination(target, false),
                snapshot);
        ExportWindow window = new ExportWindow() {
            @Override
            @SuppressWarnings("unchecked")
            public <R> Optional<R> show(ExportDialog<R> dialog) {
                if (!(dialog instanceof MeshExportDialog)) throw new AssertionError("Unexpected dialog");
                return (Optional<R>) Optional.of(settings);
            }
        };

        assertThatThrownBy(() -> new BinaryStlExporter().export(CadDocument.empty(), window))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(Files.exists(target)).isFalse();
    }
}
