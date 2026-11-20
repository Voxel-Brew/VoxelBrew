package dev.voxelbrew.exporter;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.model.*;
import dev.voxelbrew.api.model.mesh.*;
import dev.voxelbrew.engine.geometry.Solid;
import dev.voxelbrew.engine.model.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AdditionalFormatsTest {
    @TempDir
    Path directory;

    @Test
    void allFourFormatsAreDiscoveredAndObjPreservesMultiplePlacedInstances() throws Exception {
        try (var registry = new dev.voxelbrew.api.exporter.ServiceLoaderExporterProviderRegistry(directory);
                var evaluation = Evaluator.evaluate(ExportTest.reference())) {
            assertThat(registry.require("stl")).isNotNull();
            assertThat(registry.require("3mf")).isNotNull();
            assertThat(registry.require("obj")).isNotNull();
            assertThat(registry.require("step")).isNotNull();
            String body = evaluation.bodies().getFirst().id();
            var view = ModelViews.placed(
                    evaluation,
                    List.of(
                            new BodyInstance("first object", body, Transform.translation(25, -7, 3)),
                            new BodyInstance("second object", body, Transform.translation(100, 20, 0))));
            Path target = directory.resolve("placed.obj");
            FormatTestSupport.write(registry, "obj", view, MeshOptions.DEFAULT, Map.of(), target, false);
            var points = new ArrayList<Double>();
            var indices = new ArrayList<Integer>();
            int objects = 0;
            for (String line : Files.readAllLines(target)) {
                String[] fields = line.split("\\s+");
                if (fields[0].equals("o")) objects++;
                else if (fields[0].equals("v")) {
                    assertThat(fields.length).isEqualTo(4);
                    for (int i = 1; i < 4; i++) points.add(Double.parseDouble(fields[i]));
                } else if (fields[0].equals("f")) {
                    assertThat(fields.length).isEqualTo(4);
                    for (int i = 1; i < 4; i++) {
                        int index = Integer.parseInt(fields[i]);
                        assertThat(index > 0 && index <= points.size() / 3).isTrue();
                        indices.add(index - 1);
                    }
                }
            }
            assertThat(objects).isEqualTo(2);
            var mesh = new MeshData(
                    points.stream().mapToDouble(Double::doubleValue).toArray(),
                    indices.stream().mapToInt(Integer::intValue).toArray(),
                    new int[indices.size() / 3]);
            MeshValidation.requireClosed(mesh);
            double[] min = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
            double[] max = {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
            double volume = 0;
            for (int v = 0; v < mesh.vertexCount(); v++) {
                for (int axis = 0; axis < 3; axis++) {
                    min[axis] = Math.min(min[axis], mesh.coordinate(v, axis));
                    max[axis] = Math.max(max[axis], mesh.coordinate(v, axis));
                }
            }
            for (int triangle = 0; triangle < mesh.triangleCount(); triangle++) {
                int a = mesh.vertexIndex(triangle, 0),
                        b = mesh.vertexIndex(triangle, 1),
                        c = mesh.vertexIndex(triangle, 2);
                double ax = mesh.coordinate(a, 0), ay = mesh.coordinate(a, 1), az = mesh.coordinate(a, 2);
                double bx = mesh.coordinate(b, 0), by = mesh.coordinate(b, 1), bz = mesh.coordinate(b, 2);
                double cx = mesh.coordinate(c, 0), cy = mesh.coordinate(c, 1), cz = mesh.coordinate(c, 2);
                volume += (ax * (by * cz - bz * cy) + ay * (bz * cx - bx * cz) + az * (bx * cy - by * cx)) / 6;
            }
            assertThat(min).usingComparatorWithPrecision(.001).containsExactly(new double[] {25, -7, 0});
            assertThat(max).usingComparatorWithPrecision(.001).containsExactly(new double[] {160, 60, 11});
            assertThat(volume).isCloseTo(2 * 19099.469035085127, org.assertj.core.data.Offset.offset(1.0));
            byte[] original = Files.readAllBytes(target);
            assertThatThrownBy(() -> FormatTestSupport.write(
                            registry, "obj", view, MeshOptions.DEFAULT, Map.of(), target, false))
                    .isInstanceOf(java.io.IOException.class);
            assertThat(Files.readAllBytes(target)).containsExactly(original);
        }
    }

    @Test
    void stepRoundTripsExactReferenceThroughSeparateReaderAndPreservesDestinationOnFailure() throws Exception {
        var document = ExportTest.reference();
        Path target = directory.resolve("exact reference.step");
        try (var registry = new dev.voxelbrew.api.exporter.ServiceLoaderExporterProviderRegistry(directory)) {
            registry.require("step")
                    .create()
                    .export(document, dev.voxelbrew.util.testing.FileExportWindows.destination(target, false));
        }
        var metrics = inspectStep(target);
        assertThat(metrics.getProperty("solids")).isEqualTo("1");
        assertThat(Double.parseDouble(metrics.getProperty("volume")))
                .isCloseTo(19099.469035085127, org.assertj.core.data.Offset.offset(.01));
        assertThat(Arrays.stream(metrics.getProperty("bounds").split(","))
                        .mapToDouble(Double::parseDouble)
                        .toArray())
                .usingComparatorWithPrecision(.001)
                .containsExactly(new double[] {0, 0, 0, 60, 40, 8});
        assertThat(metrics.getProperty("holeContainsMaterial")).isEqualTo("0");
        assertThat(document.parameters().builtins().fileVersion()).isEqualTo(0);
        String text = Files.readString(target);
        assertThat(text.contains("ADVANCED_FACE")).isTrue();
        assertThat(text.contains("CYLINDRICAL_SURFACE")).isTrue();
        byte[] previous = Files.readAllBytes(target);
        var rollback = new CadDocument(document.id(), document.features(), document.cursor() - 1);
        assertThatThrownBy(() -> StepDocumentWriter.save(rollback, target, true, directory))
                .isInstanceOf(IllegalStateException.class);
        assertThat(Files.readAllBytes(target)).containsExactly(previous);
        assertThatThrownBy(() -> StepDocumentWriter.save(document, target, false, directory))
                .isInstanceOf(java.io.IOException.class);
        assertThat(Files.readAllBytes(target)).containsExactly(previous);
    }

    @Test
    @SuppressWarnings("try")
    void stepRetainsTwoExactSolidsAndRejectsClosedHandles() throws Exception {
        try (var a = Solid.rectanglePrism(0, 0, 0, 2, 3, 4);
                var b = Solid.rectanglePrism(10, 0, 0, 3, 4, 5)) {
            Path target = directory.resolve("two.step");
            Files.write(target, Solid.encodeStep(List.of(a, b)));
            var metrics = inspectStep(target);
            assertThat(metrics.getProperty("solids")).isEqualTo("2");
            assertThat(Double.parseDouble(metrics.getProperty("volume")))
                    .isCloseTo(84, org.assertj.core.data.Offset.offset(.001));
            a.close();
            assertThatThrownBy(() -> Solid.encodeStep(List.of(a, b))).isInstanceOf(IllegalStateException.class);
        }
    }

    private Properties inspectStep(Path source) throws Exception {
        Path transcript = directory.resolve(source.getFileName() + ".reader.txt");
        var builder = new ProcessBuilder(System.getProperty("voxelbrew.test.stepReader"), source.toString())
                .redirectErrorStream(true)
                .redirectOutput(transcript.toFile());
        builder.environment()
                .put(
                        "PATH",
                        System.getProperty("voxelbrew.native.path")
                                + java.io.File.pathSeparator
                                + builder.environment().getOrDefault("PATH", ""));
        var process = builder.start();
        try {
            assertThat(process.waitFor(30, TimeUnit.SECONDS))
                    .as("STEP reader timed out")
                    .isTrue();
            assertThat(process.exitValue()).as(Files.readString(transcript)).isEqualTo(0);
        } finally {
            process.destroyForcibly();
        }
        var properties = new Properties();
        try (var reader = Files.newBufferedReader(transcript)) {
            properties.load(reader);
        }
        return properties;
    }
}
