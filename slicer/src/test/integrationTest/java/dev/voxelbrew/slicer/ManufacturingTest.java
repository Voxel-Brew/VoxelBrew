package dev.voxelbrew.slicer;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.model.BodyInstance;
import dev.voxelbrew.api.model.Transform;
import dev.voxelbrew.api.model.mesh.*;
import dev.voxelbrew.api.model.printing.*;
import dev.voxelbrew.engine.printing.*;
import dev.voxelbrew.exporter.ExportSelection;
import dev.voxelbrew.exporter.ModelView;
import dev.voxelbrew.exporter.ModelViews;
import dev.voxelbrew.exporter.printing.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Element;

@SuppressWarnings("deprecation")
class ManufacturingTest {
    @TempDir
    Path directory;

    @Test
    void completeExportAdapterPreservesProfilesSupportsAndDetachedLifetime() throws Exception {
        var session = PrintingTest.reference();
        session.setPrintSetup(session.document()
                .printSetup()
                .withNozzle(0.6)
                .withSupports(List.of(
                        new SupportVolume("block", "part", SupportVolume.Role.BLOCKER, 0, 0, 0, 4, 4, 4),
                        new SupportVolume("pillar", "part", SupportVolume.Role.SOLID_SUPPORT, 15, 5, 0, 4, 4, 7.8))));
        ModelView view;
        try (var evaluation = session.evaluate()) {
            view = dev.voxelbrew.exporter.ExportSelections.view(
                    new ExportSelection(ExportSelection.Space.BUILD_PLATE, ExportSelection.Contents.MODEL, null, null),
                    session.document(),
                    evaluation,
                    MeshOptions.DEFAULT);
        }
        // Export after the evaluation closes: the build-plate adapter owns detached meshes.
        try (var registry = new dev.voxelbrew.api.exporter.ServiceLoaderExporterProviderRegistry(directory)) {
            for (boolean includeSolids : List.of(false, true)) {
                var output = directory.resolve("project-" + includeSolids + ".3mf");
                FormatTestSupport.write(
                        registry,
                        "prusa-project",
                        view,
                        MeshOptions.DEFAULT,
                        Map.of("include-support-solids", Boolean.toString(includeSolids)),
                        output,
                        false);
                try (var zip = new ZipFile(output.toFile())) {
                    var xml = DocumentBuilderFactory.newInstance()
                            .newDocumentBuilder()
                            .parse(zip.getInputStream(zip.getEntry("Metadata/Slic3r_PE_model.config")));
                    assertThat(xml.getElementsByTagName("volume").getLength()).isEqualTo(includeSolids ? 3 : 2);
                    var metadata = xml.getElementsByTagName("metadata");
                    var values = new ArrayList<String>();
                    for (int i = 0; i < metadata.getLength(); i++) {
                        values.add(((Element) metadata.item(i)).getAttribute("value"));
                    }
                    assertThat(values.contains("part/thickness.body")).isTrue();
                    assertThat(values.contains("part/block")).isTrue();
                    assertThat(values.contains("SupportBlocker")).isTrue();
                    assertThat(values.contains("part/pillar")).isEqualTo(includeSolids);
                    var config = new String(
                            zip.getInputStream(zip.getEntry("Metadata/Slic3r_PE.config"))
                                    .readAllBytes(),
                            java.nio.charset.StandardCharsets.UTF_8);
                    assertThat(config.contains("; filament_type = PLA\n")).isTrue();
                    assertThat(config.contains("; nozzle_diameter = 0.6\n")).isTrue();
                }
            }
        }
    }

    @Test
    void projectExportRejectsFilteredViewsAndPreservesExistingDestination() throws Exception {
        var model = dev.voxelbrew.engine.printing.ManufacturingModels.evaluate(
                PrintingTest.reference().document(), MeshOptions.DEFAULT);
        var view = ModelViews.from(model);
        var output = directory.resolve("original.3mf");
        Files.writeString(output, "original project");
        try (var registry = new dev.voxelbrew.api.exporter.ServiceLoaderExporterProviderRegistry(directory)) {
            for (var filtered : List.of(
                    ModelViews.select(view, "thickness.body"),
                    ModelViews.selectInstance(view, "part"),
                    ModelViews.from(model, ExportSelection.Contents.ALL))) {
                var failure = org.assertj.core.api.Assertions.catchThrowableOfType(
                        () -> FormatTestSupport.write(
                                registry, "prusa-project", filtered, MeshOptions.DEFAULT, Map.of(), output, true),
                        java.io.IOException.class);
                assertThat(failure.getMessage().contains("complete build-plate setup"))
                        .isTrue();
                assertThat(Files.readString(output)).isEqualTo("original project");
            }
        }
        try (var entries = Files.list(directory)) {
            assertThat(entries.toList()).isEqualTo(List.of(output));
        }
    }

    @Test
    void preflightReportsRotatedBedBoundsExcludedZonesOverlapHeightAndBrokenAttachments() {
        var s = PrintingTest.reference();
        var base = dev.voxelbrew.engine.printing.ManufacturingModels.evaluate(s.document(), MeshOptions.DEFAULT);
        var printer = base.profile().printer();
        var profile = new PrintProfile(
                new PrintProfile.Printer(
                        printer.id(),
                        printer.name(),
                        .4,
                        printer.bed(),
                        List.of(List.of(
                                new PrintProfile.Point(10, 10),
                                new PrintProfile.Point(25, 10),
                                new PrintProfile.Point(25, 25),
                                new PrintProfile.Point(10, 25))),
                        200,
                        "marlin2",
                        "gcode"),
                base.profile().material(),
                base.profile().process());
        var setup = base.setup()
                .withInstances(List.of(
                        new BodyInstance(
                                "rotated",
                                "thickness.body",
                                Transform.translation(20, 20, 0).compose(Transform.rotation(0, 0, 90))),
                        new BodyInstance("tall", "thickness.body", Transform.translation(20, 20, 198)),
                        new BodyInstance("overlap", "thickness.body", Transform.translation(0, 20, 0))))
                .withSupports(
                        List.of(new SupportVolume("lost", "missing", SupportVolume.Role.BLOCKER, 0, 0, 0, 3, 3, 3)));
        var model = new ManufacturingModel("fixture", "rev", setup, profile, base.meshes());
        var report = PrintValidation.validate(model);
        assertThat(report.meshes().get("tall")).isSameAs(report.meshes().get("rotated"));
        assertThat(PrintValidation.validate(model)).isEqualTo(report);
        for (var expected : List.of(
                "rotated:OUTSIDE_BED",
                "rotated:EXCLUDED_ZONE",
                "rotated:OVERLAP",
                "tall:OVER_HEIGHT",
                "lost:MISSING_INSTANCE")) {
            assertThat(report.diagnostics().stream().anyMatch(d -> (d.objectId() + ":" + d.code()).equals(expected)))
                    .as(report.toString())
                    .isTrue();
        }
    }

    @Test
    void meshPreflightNamesOpenAndDegenerateObjectsAndBlocksExport() throws Exception {
        var mesh = MeshGeometry.box(
                new SupportVolume("box", "part", SupportVolume.Role.SOLID_SUPPORT, 0, 0, 0, 10, 10, 10));
        var open = new MeshData(
                mesh.vertices(), Arrays.copyOf(mesh.triangles(), 33), Arrays.copyOf(mesh.faceIndices(), 11));
        var triangles = mesh.triangles();
        triangles[0] = triangles[1];
        var degenerate = new MeshData(mesh.vertices(), triangles, mesh.faceIndices());
        var setup = PrintSetup.empty()
                .withInstances(List.of(
                        new BodyInstance("open", "open", Transform.IDENTITY),
                        new BodyInstance("degenerate", "degenerate", Transform.translation(20, 0, 0))));
        var model = new ManufacturingModel(
                "mesh", "rev", setup, PrintProfile.resolve(setup), Map.of("open", open, "degenerate", degenerate));
        var report = PrintValidation.validate(model);
        assertThat(report.meshes().get("open").boundaryEdges()).isEqualTo(3);
        assertThat(report.meshes().get("degenerate").degenerateTriangles()).isEqualTo(1);
        try (var registry = new dev.voxelbrew.api.exporter.ServiceLoaderExporterProviderRegistry(directory)) {
            Path file = directory.resolve("bad.stl");
            Files.writeString(file, "original");
            assertThatThrownBy(() -> FormatTestSupport.write(
                            registry,
                            "stl",
                            ModelViews.selectInstance(ModelViews.from(model), "open"),
                            MeshOptions.DEFAULT,
                            Map.of(),
                            file,
                            true))
                    .isInstanceOf(java.io.IOException.class);
            assertThat(Files.readString(file)).isEqualTo("original");
        }
    }

    @Test
    void layOnEachPlanarFaceAndSupportExchangeApplyPlacementExactlyOnce() throws Exception {
        var session = PrintingTest.reference();
        var original =
                dev.voxelbrew.engine.printing.ManufacturingModels.evaluate(session.document(), MeshOptions.DEFAULT);
        var mesh = original.meshes().get("thickness.body");
        int planar = 0;
        for (int face : Arrays.stream(mesh.faceIndices()).distinct().toArray()) {
            try {
                var placed = MeshGeometry.transform(
                        mesh,
                        MeshGeometry.layOnFace(
                                mesh, face, Transform.translation(80, 80, 0).compose(Transform.rotation(0, 0, 90))));
                for (int t = 0; t < placed.triangleCount(); t++) {
                    if (placed.faceIndex(t) == face) {
                        for (int c = 0; c < 3; c++) {
                            assertThat(placed.coordinate(placed.vertexIndex(t, c), 2))
                                    .isCloseTo(0, org.assertj.core.data.Offset.offset(.001));
                        }
                    }
                }
                assertThat(MeshGeometry.bounds(placed).minZ()).isCloseTo(0, org.assertj.core.data.Offset.offset(.001));
                planar++;
            } catch (IllegalArgumentException curved) {
                assertThat(curved.getMessage().contains("not planar")).isTrue();
            }
        }
        assertThat(planar).isEqualTo(6);
        session.setPrintSetup(session.document()
                .printSetup()
                .withSupports(List.of(
                        new SupportVolume("pillar", "part", SupportVolume.Role.SOLID_SUPPORT, 15, 5, 0, 4, 4, 7.8))));
        var model = dev.voxelbrew.engine.printing.ManufacturingModels.evaluate(session.document(), MeshOptions.DEFAULT);
        assertThat(ModelViews.from(model, ExportSelection.Contents.MODEL)
                        .bodies()
                        .size())
                .isEqualTo(1);
        assertThat(ModelViews.from(model, ExportSelection.Contents.SUPPORTS)
                        .bodies()
                        .size())
                .isEqualTo(1);
        try (var registry = new dev.voxelbrew.api.exporter.ServiceLoaderExporterProviderRegistry(directory)) {
            Path file = directory.resolve("supports.stl");
            FormatTestSupport.write(
                    registry,
                    "stl",
                    ModelViews.from(model, ExportSelection.Contents.SUPPORTS),
                    MeshOptions.DEFAULT,
                    Map.of(),
                    file,
                    false);
            var data = ByteBuffer.wrap(Files.readAllBytes(file)).order(ByteOrder.LITTLE_ENDIAN);
            data.position(80);
            assertThat(data.getInt()).isEqualTo(12);
            double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
            for (int t = 0; t < 12; t++) {
                data.position(data.position() + 12);
                for (int c = 0; c < 3; c++) {
                    double x = data.getFloat(), y = data.getFloat(), z = data.getFloat();
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    assertThat(y >= 35 && y <= 39).isTrue();
                    assertThat(z >= 0 && z <= 7.801).isTrue();
                }
                data.getShort();
            }
            assertThat(minX).isCloseTo(35, org.assertj.core.data.Offset.offset(.001));
            assertThat(maxX).isCloseTo(39, org.assertj.core.data.Offset.offset(.001));
            Path packageFile = directory.resolve("assembly.3mf");
            FormatTestSupport.write(
                    registry,
                    "3mf",
                    ModelViews.from(model, ExportSelection.Contents.ALL),
                    MeshOptions.DEFAULT,
                    Map.of(),
                    packageFile,
                    false);
            try (var zip = new ZipFile(packageFile.toFile())) {
                var factory = DocumentBuilderFactory.newInstance();
                factory.setNamespaceAware(true);
                var xml = factory.newDocumentBuilder().parse(zip.getInputStream(zip.getEntry("3D/3dmodel.model")));
                var objects = xml.getElementsByTagNameNS("*", "object");
                assertThat(objects.getLength()).isEqualTo(2);
                assertThat(((Element) objects.item(1)).getAttribute("type")).isEqualTo("solidsupport");
                var items = xml.getElementsByTagNameNS("*", "item");
                assertThat(items.getLength()).isEqualTo(2);
                assertThat(((Element) items.item(1)).getAttribute("transform"))
                        .isEqualTo(((Element) items.item(0)).getAttribute("transform"));
            }
        }
    }
}
