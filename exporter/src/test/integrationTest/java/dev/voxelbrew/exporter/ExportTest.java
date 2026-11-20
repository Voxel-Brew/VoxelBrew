package dev.voxelbrew.exporter;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.exporter.ExporterDescriptor;
import dev.voxelbrew.api.model.*;
import dev.voxelbrew.api.model.mesh.*;
import dev.voxelbrew.engine.geometry.Solid;
import dev.voxelbrew.engine.model.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipFile;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Element;

class ExportTest {
    @TempDir
    Path directory;

    static CadDocument reference() {
        return new CadDocument(
                "reference",
                List.of(
                        new Feature.Sketch(
                                "plate",
                                "plate.profile",
                                new Feature.Plane("origin-xy", 0),
                                new Feature.Rectangle(0, 0, 60, 40)),
                        new Feature.Extrude("thickness", new Feature.ProfileRef("plate", "plate.profile"), 8),
                        new Feature.Sketch(
                                "hole",
                                "hole.profile",
                                new Feature.Plane("origin-xy", 0),
                                new Feature.Circle(10, 10, 4)),
                        new Feature.ThroughCut(
                                "cut",
                                new Feature.ProfileRef("hole", "hole.profile"),
                                new Feature.BodyRef("thickness", "body"))),
                4);
    }

    private static List<double[][]> triangles(MeshData mesh) {
        var result = new ArrayList<double[][]>();
        for (int t = 0; t < mesh.triangleCount(); t++) {
            double[][] points = new double[3][3];
            for (int c = 0; c < 3; c++)
                for (int a = 0; a < 3; a++) points[c][a] = mesh.coordinate(mesh.vertexIndex(t, c), a);
            result.add(points);
        }
        return result;
    }

    /** Reader-side checks deliberately do not call production preflight or geometry exporters. */
    private static void verifyTriangles(List<double[][]> triangles, double dx, double dy, double dz) {
        double[] min = {1e99, 1e99, 1e99}, max = {-1e99, -1e99, -1e99};
        double volume = 0;
        int holeSegments = 0;
        Map<String, Integer> directed = new HashMap<>();
        Map<String, Integer> undirected = new HashMap<>();
        for (var world : triangles) {
            double[][] p = new double[3][3];
            for (int c = 0; c < 3; c++)
                for (int a = 0; a < 3; a++) {
                    assertThat(Double.isFinite(world[c][a])).isTrue();
                    p[c][a] = world[c][a] - new double[] {dx, dy, dz}[a];
                    min[a] = Math.min(min[a], p[c][a]);
                    max[a] = Math.max(max[a], p[c][a]);
                }
            double[] cross = cross(p);
            assertThat(dot(cross, cross) > 1e-20).isTrue();
            volume += dot(p[0], new double[] {
                        p[1][1] * p[2][2] - p[1][2] * p[2][1],
                        p[1][2] * p[2][0] - p[1][0] * p[2][2],
                        p[1][0] * p[2][1] - p[1][1] * p[2][0]
                    })
                    / 6;
            var section = new ArrayList<double[]>();
            for (int c = 0; c < 3; c++) {
                var a = p[c];
                var b = p[(c + 1) % 3];
                String ka = key(a), kb = key(b);
                String edge = ka.compareTo(kb) < 0 ? ka + "/" + kb : kb + "/" + ka;
                undirected.merge(edge, 1, Integer::sum);
                directed.merge(edge, ka.compareTo(kb) < 0 ? 1 : -1, Integer::sum);
                if ((a[2] < 4 && b[2] > 4) || (a[2] > 4 && b[2] < 4)) {
                    double f = (4 - a[2]) / (b[2] - a[2]);
                    section.add(new double[] {a[0] + f * (b[0] - a[0]), a[1] + f * (b[1] - a[1])});
                }
            }
            if (section.size() == 2 && section.stream().allMatch(q -> Math.hypot(q[0] - 10, q[1] - 10) < 3)) {
                holeSegments++;
                for (double fraction : new double[] {0, 0.5, 1}) {
                    double x = section.get(0)[0] * (1 - fraction) + section.get(1)[0] * fraction,
                            y = section.get(0)[1] * (1 - fraction) + section.get(1)[1] * fraction;
                    assertThat(Math.hypot(x - 10, y - 10)).isCloseTo(2, org.assertj.core.data.Offset.offset(0.021));
                }
            }
        }
        assertThat(min).usingComparatorWithPrecision(0.001).containsExactly(new double[] {0, 0, 0});
        assertThat(max).usingComparatorWithPrecision(0.001).containsExactly(new double[] {60, 40, 8});
        assertThat(volume).isCloseTo(19099.469035085127, org.assertj.core.data.Offset.offset(0.5));
        assertThat(holeSegments >= 16).isTrue();
        assertThat(undirected.values().stream().allMatch(n -> n == 2)).isTrue();
        assertThat(directed.values().stream().allMatch(n -> n == 0)).isTrue();
    }

    private static String key(double[] p) {
        return Math.round(p[0] * 1e6) + "," + Math.round(p[1] * 1e6) + "," + Math.round(p[2] * 1e6);
    }

    private static double[] cross(double[][] p) {
        double[] a = {p[1][0] - p[0][0], p[1][1] - p[0][1], p[1][2] - p[0][2]},
                b = {p[2][0] - p[0][0], p[2][1] - p[0][1], p[2][2] - p[0][2]};
        return new double[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    @Test
    void tessellationIsDetachedClosedAndDoesNotModifySource() {
        MeshData detached;
        try (var plate = Solid.rectanglePrism(0, 0, 0, 60, 40, 8);
                var cut = plate.cutThroughCircle(10, 10, 4)) {
            double volume = cut.volume();
            var bounds = cut.bounds();
            detached = cut.tessellate(MeshOptions.DEFAULT);
            assertThat(cut.volume()).isCloseTo(volume, org.assertj.core.data.Offset.offset(1e-10));
            assertThat(cut.bounds()).isEqualTo(bounds);
            assertThat(plate.volume()).isCloseTo(19200, org.assertj.core.data.Offset.offset(1e-9));
            var coarse = cut.tessellate(new MeshOptions(0.1, 20));
            assertThat(detached.triangleCount() > coarse.triangleCount()).isTrue();
        }
        MeshValidation.requireClosed(detached);
        assertThat(Arrays.stream(detached.faceIndices()).distinct().count() >= 7)
                .isTrue();
        var original = detached.coordinate(0, 0);
        var vertices = detached.vertices();
        vertices[0] = 999;
        assertThat(detached.coordinate(0, 0)).isEqualTo(original);
        verifyTriangles(triangles(detached), 0, 0, 0);
    }

    @Test
    void stlIsIndependentlyReadAndPlacementAppliedExactlyOnce() throws Exception {
        try (var evaluation = Evaluator.evaluate(reference());
                var registry = new dev.voxelbrew.api.exporter.ServiceLoaderExporterProviderRegistry(directory)) {
            var view = ModelViews.placed(
                    evaluation,
                    List.of(new BodyInstance("translated", "thickness.body", Transform.translation(25, -7, 3))));
            var destination = directory.resolve("reference.stl");
            FormatTestSupport.write(registry, "stl", view, MeshOptions.DEFAULT, Map.of(), destination, false);
            byte[] bytes = Files.readAllBytes(destination);
            var buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            buffer.position(80);
            int count = buffer.getInt();
            assertThat(bytes.length).isEqualTo(84 + 50 * count);
            var triangles = new ArrayList<double[][]>();
            for (int i = 0; i < count; i++) {
                double[] normal = {buffer.getFloat(), buffer.getFloat(), buffer.getFloat()};
                var points = new double[3][3];
                for (var point : points) for (int axis = 0; axis < 3; axis++) point[axis] = buffer.getFloat();
                assertThat(buffer.getShort()).isZero();
                var cross = cross(points);
                assertThat(dot(normal, cross) > 0)
                        .as("outward normal agrees with winding")
                        .isTrue();
                triangles.add(points);
            }
            verifyTriangles(triangles, 25, -7, 3);
        }
    }

    @Test
    void threeMfPackageHasSharedResourcesUnitsAndIndependentInstanceTransforms() throws Exception {
        try (var evaluation = Evaluator.evaluate(reference());
                var registry = new dev.voxelbrew.api.exporter.ServiceLoaderExporterProviderRegistry(directory)) {
            var view = ModelViews.placed(
                    evaluation,
                    List.of(
                            new BodyInstance("first", "thickness.body", Transform.IDENTITY),
                            new BodyInstance(
                                    "second",
                                    "thickness.body",
                                    new Transform(0, -1, 0, 100, 1, 0, 0, 20, 0, 0, 1, 3))));
            Path destination = directory.resolve("reference.3mf");
            FormatTestSupport.write(registry, "3mf", view, MeshOptions.DEFAULT, Map.of(), destination, false);
            try (var zip = new ZipFile(destination.toFile())) {
                assertThat(zip.getEntry("[Content_Types].xml")).isNotNull();
                assertThat(zip.getEntry("_rels/.rels")).isNotNull();
                var factory = DocumentBuilderFactory.newInstance();
                factory.setNamespaceAware(true);
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                var builder = factory.newDocumentBuilder();
                var rels = builder.parse(zip.getInputStream(zip.getEntry("_rels/.rels")));
                var relationship = (Element)
                        rels.getElementsByTagNameNS("*", "Relationship").item(0);
                assertThat(relationship.getAttribute("Target")).isEqualTo("/3D/3dmodel.model");
                var model = builder.parse(zip.getInputStream(zip.getEntry("3D/3dmodel.model")));
                assertThat(model.getDocumentElement().getAttribute("unit")).isEqualTo("millimeter");
                assertThat(model.getElementsByTagNameNS("*", "object").getLength())
                        .isEqualTo(1);
                assertThat(((Element) model.getElementsByTagNameNS("*", "object")
                                        .item(0))
                                .getAttribute("name"))
                        .isEqualTo("thickness.body");
                var vertexNodes = model.getElementsByTagNameNS("*", "vertex");
                var points = new ArrayList<double[]>();
                for (int i = 0; i < vertexNodes.getLength(); i++) {
                    var v = (Element) vertexNodes.item(i);
                    points.add(new double[] {
                        Double.parseDouble(v.getAttribute("x")),
                        Double.parseDouble(v.getAttribute("y")),
                        Double.parseDouble(v.getAttribute("z"))
                    });
                }
                var triangleNodes = model.getElementsByTagNameNS("*", "triangle");
                var triangles = new ArrayList<double[][]>();
                for (int i = 0; i < triangleNodes.getLength(); i++) {
                    var t = (Element) triangleNodes.item(i);
                    triangles.add(new double[][] {
                        points.get(Integer.parseInt(t.getAttribute("v1"))),
                        points.get(Integer.parseInt(t.getAttribute("v2"))),
                        points.get(Integer.parseInt(t.getAttribute("v3")))
                    });
                }
                verifyTriangles(triangles, 0, 0, 0);
                var items = model.getElementsByTagNameNS("*", "item");
                assertThat(items.getLength()).isEqualTo(2);
                for (int i = 0; i < items.getLength(); i++)
                    assertThat(((Element) items.item(i)).getAttribute("objectid"))
                            .isEqualTo("1");
                double[] m = Arrays.stream(((Element) items.item(1))
                                .getAttribute("transform")
                                .split(" "))
                        .mapToDouble(Double::parseDouble)
                        .toArray();
                assertThat(m.length).isEqualTo(12);
                double[] min = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY},
                        max = {-1e99, -1e99, -1e99};
                for (var p : points)
                    for (int axis = 0; axis < 3; axis++) {
                        double value = p[0] * m[axis] + p[1] * m[3 + axis] + p[2] * m[6 + axis] + m[9 + axis];
                        min[axis] = Math.min(min[axis], value);
                        max[axis] = Math.max(max[axis], value);
                    }
                assertThat(min).usingComparatorWithPrecision(0.001).containsExactly(new double[] {60, 20, 3});
                assertThat(max).usingComparatorWithPrecision(0.001).containsExactly(new double[] {100, 80, 11});
            }
        }
    }

    @Test
    void independentConsumerLoadsAndFailuresPreserveDestination() throws Exception {
        Path plugins = Files.createDirectory(directory.resolve("plugins with spaces"));
        Files.copy(Path.of(System.getProperty("voxelbrew.test.plugin")), plugins.resolve("external provider.jar"));
        try (var registry = new dev.voxelbrew.api.exporter.ServiceLoaderExporterProviderRegistry(plugins)) {
            assertThat(registry.descriptors().stream()
                            .map(ExporterDescriptor::id)
                            .toList())
                    .isEqualTo(List.of(
                            "3mf",
                            "document-probe",
                            "failing",
                            "gcode",
                            "gcode-package",
                            "json",
                            "obj",
                            "prusa-project",
                            "step",
                            "stl",
                            "vxb"));
            var doc = reference();
            Path target = directory.resolve("report.txt");
            registry.require("document-probe")
                    .create()
                    .export(doc, dev.voxelbrew.util.testing.FileExportWindows.destination(target, false));
            assertThat(Files.readString(target)).isEqualTo("document=" + doc.id());
            byte[] expected = Files.readAllBytes(target);
            assertThatThrownBy(() -> registry.require("failing")
                            .create()
                            .export(doc, dev.voxelbrew.util.testing.FileExportWindows.destination(target, true)))
                    .isInstanceOf(java.io.IOException.class);
            assertThat(Files.readAllBytes(target)).containsExactly(expected);
            assertThatThrownBy(() -> registry.require("document-probe")
                            .create()
                            .export(doc, dev.voxelbrew.util.testing.FileExportWindows.destination(target, false)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(Files.readAllBytes(target)).containsExactly(expected);
        }
    }

    @Test
    void incompleteDocumentsAreRejected() {
        var doc = reference();
        try (var evaluation = Evaluator.evaluate(new CadDocument(doc.id(), doc.features(), 3))) {
            assertThatThrownBy(() -> ModelViews.from(evaluation)).isInstanceOf(IllegalStateException.class);
        }
        try (var evaluation = Evaluator.evaluate(new CadDocument(
                "bad", List.of(new Feature.Extrude("bad", new Feature.ProfileRef("missing", "p"), 8)), 1))) {
            assertThatThrownBy(() -> ModelViews.from(evaluation)).isInstanceOf(IllegalStateException.class);
        }
    }
}
