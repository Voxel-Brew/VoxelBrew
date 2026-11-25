package dev.voxelbrew.cli;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.model.*;
import dev.voxelbrew.engine.model.*;
import dev.voxelbrew.engine.persistence.DocumentCodec;
import dev.voxelbrew.exporter.DocumentWriter;
import dev.voxelbrew.importer.DocumentReader;
import java.io.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** CLI/service integration in-process; real OCCT and atomic document codecs, no child JVM. */
class WorkspaceIntegrationTest {
    @TempDir
    Path directory;

    private String output;

    private int run(String... args) {
        var text = new StringWriter();
        int result = VoxelBrew.commandLine()
                .setOut(new PrintWriter(text))
                .setErr(new PrintWriter(text))
                .execute(args);
        output = text.toString();
        return result;
    }

    private Path part(String name) throws Exception {
        var session = new ModelSession(CadDocument.empty());
        session.add(new Feature.Sketch(
                "outline", "outline.profile", Feature.Plane.origin("XY"), new Feature.Rectangle(0, 0, 60, 40)));
        session.add(new Feature.Extrude("solid", new Feature.ProfileRef("outline", "outline.profile"), 8));
        Path file = directory.resolve(name + ".vxb");
        DocumentWriter.save(session.document(), file, false);
        return file;
    }

    @Test
    void patternsCreateIndependentCopiesAndReplaceInPlace() throws Exception {
        var file = part("patterns").toString();
        for (var kind : java.util.List.of("rectangular", "circular", "mirror")) {
            assertThat(run("designer", "solid", "pattern", kind, file, "--id", kind, "--body-feature", "solid"))
                    .as(output)
                    .isZero();
        }
        assertThat(run(
                        "designer",
                        "solid",
                        "pattern",
                        "rectangular",
                        file,
                        "--replace",
                        "--id",
                        "rectangular",
                        "--body-feature",
                        "solid",
                        "--count",
                        "3",
                        "--step",
                        "2 cm"))
                .as(output)
                .isZero();
        var doc = DocumentReader.load(Path.of(file));
        assertThat(doc.features()).hasSize(5);
        assertThat(BodyPattern.from(doc.features().get(2)).firstStep()).isEqualTo(20);
        try (var result = new ModelSession(doc).evaluate()) {
            assertThat(result.requireCompleteDocument()).hasSize(7);
        }
        byte[] original = Files.readAllBytes(Path.of(file));
        assertThat(run("designer", "solid", "pattern", "mirror", file, "--id", "bad", "--body-feature", "missing"))
                .isNotZero();
        assertThat(Files.readAllBytes(Path.of(file))).containsExactly(original);
    }

    @Test
    void editsJoinedExtrusionToBlindCutWithoutMovingItsHistoryEntry() throws Exception {
        var file = part("blind").toString();
        assertThat(run(
                        "designer",
                        "sketch",
                        "circle",
                        file,
                        "--id",
                        "hole",
                        "--center-x",
                        "10",
                        "--center-y",
                        "10",
                        "--diameter",
                        "4"))
                .as(output)
                .isZero();
        assertThat(run(
                        "designer",
                        "solid",
                        "extrude",
                        file,
                        "--id",
                        "operation",
                        "--sketch",
                        "hole",
                        "--distance",
                        "12",
                        "--operation",
                        "join",
                        "--body-feature",
                        "solid"))
                .as(output)
                .isZero();
        assertThat(run(
                        "designer",
                        "solid",
                        "extrude",
                        file,
                        "--replace",
                        "--id",
                        "operation",
                        "--sketch",
                        "hole",
                        "--distance",
                        "3",
                        "--operation",
                        "cut",
                        "--body-feature",
                        "solid"))
                .as(output)
                .isZero();
        var doc = DocumentReader.load(Path.of(file));
        assertThat(doc.features()).hasSize(4);
        try (var result = new ModelSession(doc).evaluate()) {
            var body = result.requireCompleteDocument().getFirst().solid();
            assertThat(body.contains(10, 10, 2)).isFalse();
            assertThat(body.contains(10, 10, 4)).isTrue();
        }
    }

    @Test
    void inwardExtrusionUsesTheSupportingFaceAndPreservesItsTargetOnReopen() throws Exception {
        var file = part("face");
        var session = new ModelSession(DocumentReader.load(file));
        try (var evaluated = session.evaluate()) {
            var body = evaluated.requireCompleteDocument().getFirst();
            var faces = body.solid().planarFaces();
            var top = faces.stream().filter(f -> f.nz() > .99).findFirst().orElseThrow();
            var plane = FaceSupport.attach(
                    body.output(), faces, top.faceIndex(), session.document().uuidGenerator());
            var local = plane.frame().inverse().apply(20, 20, 8);
            session.add(new Feature.Sketch("on-face", "p", plane, new Feature.Circle(local[0], local[1], 4)));
        }
        DocumentWriter.save(session.document(), file, true);
        assertThat(run(
                        "designer",
                        "solid",
                        "extrude",
                        file.toString(),
                        "--id",
                        "pocket",
                        "--sketch",
                        "on-face",
                        "--distance",
                        "-2",
                        "--cut-inward"))
                .as(output)
                .isZero();
        var doc = DocumentReader.load(file);
        var cut = BodyExtrusion.from(doc.features().getLast());
        assertThat(cut.operation()).isEqualTo(BodyExtrusion.Operation.CUT);
        assertThat(cut.target()).isEqualTo(new Feature.BodyRef("solid", "body"));
        try (var evaluated = new ModelSession(doc).evaluate()) {
            var body = evaluated.requireCompleteDocument().getFirst().solid();
            assertThat(body.contains(20, 20, 7)).isFalse();
            assertThat(body.contains(20, 20, 5)).isTrue();
        }
    }

    @Test
    void regionPicksAndEnclosedSelectionSurviveReopen() throws Exception {
        var file = directory.resolve("regions.vxb");
        var session = new ModelSession(CadDocument.empty());
        var circles = java.util.List.of("outer", "inner", "center");
        var entities = circles.stream()
                .map(id -> dev.voxelbrew.engine.sketch.Sketches.entity(
                        id,
                        dev.voxelbrew.api.model.sketch.SketchGeometry.Kind.CIRCLE,
                        false,
                        0,
                        0,
                        10 - circles.indexOf(id) * 3))
                .toList();
        session.add(new Feature.Sketch(
                "rings",
                "profile",
                Feature.Plane.origin("XY"),
                new Feature.Constrained(
                        new dev.voxelbrew.api.model.sketch.SketchGeometry(entities, java.util.List.of(), circles))));
        session.renameFeature("rings", "Concentric rings");
        DocumentWriter.save(session.document(), file, false);
        assertThat(run("designer", "sketch", "catalog", file.toString()))
                .as(output)
                .isZero();
        assertThat(output).contains("rings\tprofile\tConcentric rings");
        assertThat(run("designer", "sketch", "regions", file.toString(), "--sketch", "rings", "--at", "9,0"))
                .as(output)
                .isZero();
        String outer = output.strip();
        assertThat(run("designer", "sketch", "regions", file.toString(), "--sketch", "rings", "--at", "6,0"))
                .as(output)
                .isZero();
        String inner = output.strip();
        assertThat(run(
                        "designer",
                        "solid",
                        "extrude",
                        file.toString(),
                        "--id",
                        "ring",
                        "--sketch",
                        "rings",
                        "--distance",
                        "2",
                        "--region",
                        outer,
                        "--region",
                        inner))
                .as(output)
                .isZero();
        try (var result = new ModelSession(DocumentReader.load(file)).evaluate()) {
            assertThat(result.requireCompleteDocument().getFirst().solid().contains(0, 0, 1))
                    .isFalse();
        }
        assertThat(run(
                        "designer",
                        "solid",
                        "extrude",
                        file.toString(),
                        "--replace",
                        "--id",
                        "ring",
                        "--sketch",
                        "rings",
                        "--distance",
                        "2",
                        "--region",
                        outer,
                        "--include-enclosed"))
                .as(output)
                .isZero();
        try (var result = new ModelSession(DocumentReader.load(file)).evaluate()) {
            assertThat(result.requireCompleteDocument().getFirst().solid().contains(0, 0, 1))
                    .isTrue();
        }
    }

    @Test
    void cutAcceptsRepeatedBodyTargets() throws Exception {
        var file = part("cut-pair");
        var session = new ModelSession(DocumentReader.load(file));
        session.add(new Feature.Extrude("second", new Feature.ProfileRef("outline", "outline.profile"), -4));
        session.add(
                new Feature.Sketch("hole", "hole.profile", Feature.Plane.origin("XY"), new Feature.Circle(10, 10, 2)));
        DocumentWriter.save(session.document(), file, true);
        assertThat(run(
                        "--document",
                        file.toString(),
                        "designer",
                        "solid",
                        "cut",
                        "--id",
                        "holes",
                        "--sketch",
                        "hole",
                        "--body-feature",
                        "solid",
                        "--body-feature",
                        "second"))
                .as(output)
                .isZero();
        try (var result = new ModelSession(DocumentReader.load(file)).evaluate()) {
            var bodies = result.requireCompleteDocument();
            assertThat(bodies).hasSize(2);
            assertThat(bodies.getFirst().solid().contains(10, 10, 4)).isFalse();
            assertThat(bodies.getLast().solid().contains(10, 10, -2)).isFalse();
        }
    }

    @Test
    void finishingCommandsRebuildPersistedSolidsAndKeepTheirExpressions() throws Exception {
        for (String operation : new String[] {"fillet", "chamfer", "shell"}) {
            Path file = part(operation);
            String selection = operation.equals("shell") ? "--face" : "--edges";
            assertThat(run(
                            "--document",
                            file.toString(),
                            "designer",
                            "solid",
                            operation,
                            "--id",
                            "finish",
                            "--body-feature",
                            "solid",
                            "--size",
                            "1 mm",
                            selection,
                            "1"))
                    .as(output)
                    .isEqualTo(0);
            var document = DocumentReader.load(file);
            assertThat(document.features().size()).isEqualTo(3);
            assertThat(document.parameters().bindings().stream()
                            .anyMatch(b -> b.target().featureId().equals("finish")
                                    && b.expression().equals("1 mm")))
                    .isTrue();
            try (var evaluation = new ModelSession(document).evaluate()) {
                assertThat(evaluation.valid()).isTrue();
                double volume =
                        evaluation.requireCompleteDocument().getFirst().solid().volume();
                assertThat(volume > 0 && volume < 19200)
                        .as(operation + " volume=" + volume)
                        .isTrue();
            }
        }
    }

    @Test
    void constructionPreviewIsTransientAndAppliedDatumSurvivesReopen() throws Exception {
        Path file = part("construction");
        byte[] before = Files.readAllBytes(file);
        assertThat(run(
                        "--document",
                        file.toString(),
                        "designer",
                        "construction",
                        "preview",
                        "--id",
                        "datum",
                        "--tool",
                        "offset-plane",
                        "--input",
                        "origin_plane:::0",
                        "--value",
                        "12"))
                .as(output)
                .isEqualTo(0);
        assertThat(Files.readAllBytes(file)).containsExactly(before);
        assertThat(run(
                        "--document",
                        file.toString(),
                        "designer",
                        "construction",
                        "add",
                        "--id",
                        "datum",
                        "--tool",
                        "offset-plane",
                        "--input",
                        "origin_plane:::0",
                        "--value",
                        "12"))
                .as(output)
                .isEqualTo(0);
        var document = DocumentReader.load(file);
        assertThat(document.features().getLast()).isInstanceOf(ConstructionFeature.class);
        try (var evaluation = new ModelSession(document).evaluate()) {
            assertThat(evaluation.valid()).isTrue();
            assertThat(evaluation.construction().containsKey("datum")).isTrue();
        }
    }

    @Test
    void staleRevisionAndInvalidExpressionLeaveTheOriginalBytesUntouched() throws Exception {
        Path file = part("protected");
        byte[] before = Files.readAllBytes(file);
        String revision = DocumentCodec.fingerprint(DocumentReader.load(file));
        assertThat(run(
                        "--document",
                        file.toString(),
                        "designer",
                        "body",
                        "rename",
                        "--id",
                        "solid.body",
                        "--name",
                        "Changed",
                        "--revision",
                        revision + "stale"))
                .isEqualTo(1);
        assertThat(Files.readAllBytes(file)).containsExactly(before);
        assertThat(run(
                        "--document",
                        file.toString(),
                        "designer",
                        "solid",
                        "shell",
                        "--id",
                        "bad",
                        "--body-feature",
                        "solid",
                        "--face",
                        "1",
                        "--size",
                        "missing_parameter"))
                .isEqualTo(1);
        assertThat(Files.readAllBytes(file)).containsExactly(before);
    }

    @Test
    void placementRotationPreservesClearanceAndInvalidatesStoredJobs() throws Exception {
        Path file = part("rotation");
        assertThat(run(
                        "--document",
                        file.toString(),
                        "slicer",
                        "placement",
                        "add",
                        "--instance",
                        "first",
                        "--body",
                        "solid.body",
                        "--x",
                        "30",
                        "--y",
                        "30",
                        "--z",
                        "5"))
                .as(output)
                .isEqualTo(0);
        var before = DocumentReader.load(file);
        DocumentWriter.save(before.withGcode(java.util.Map.of("plate-1", java.util.List.of("G1 X1"))), file, true);
        assertThat(run(
                        "--document",
                        file.toString(),
                        "slicer",
                        "placement",
                        "rotate",
                        "--instance",
                        "first",
                        "--axis",
                        "x",
                        "--space",
                        "local",
                        "--angle",
                        "90 deg"))
                .as(output)
                .isEqualTo(0);
        var document = DocumentReader.load(file);
        assertThat(document.gcode().isEmpty()).isTrue();
        var model = dev.voxelbrew.engine.printing.ManufacturingModels.evaluate(
                document, dev.voxelbrew.api.model.mesh.MeshOptions.DEFAULT);
        var bounds = dev.voxelbrew.api.model.printing.MeshGeometry.bounds(
                model.placedMesh(model.instances().getFirst()));
        assertThat(bounds.minZ()).isCloseTo(5, org.assertj.core.data.Offset.offset(0.001));
        assertThat(bounds.maxZ() - bounds.minZ()).isCloseTo(40, org.assertj.core.data.Offset.offset(0.001));
    }
}
