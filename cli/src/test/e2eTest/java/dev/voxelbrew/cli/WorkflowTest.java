package dev.voxelbrew.cli;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.model.Feature;
import dev.voxelbrew.engine.persistence.proto.DocumentProto;
import dev.voxelbrew.exporter.DocumentWriter;
import dev.voxelbrew.importer.DocumentReader;
import dev.voxelbrew.util.Version;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Every command runs in a new JVM, proving that edits come from the saved definitions. */
class WorkflowTest {
    @TempDir
    Path directory;

    private static byte[] versioned(DocumentProto.Document document, String version) throws Exception {
        byte[] payload =
                document.toBuilder().setApplicationVersion(version).build().toByteArray();
        return ByteBuffer.allocate(44 + payload.length)
                .put(new byte[] {'J', 'C', 'A', 'D', 13, 37, 13, 37})
                .putInt(payload.length)
                .put(MessageDigest.getInstance("SHA-256").digest(payload))
                .put(payload)
                .array();
    }

    @Test
    void workspaceSessionCommitsSketchDraftAndPersistsFormulaBodyNameAndPlateUndo() throws Exception {
        Path file = directory.resolve("workspace session.vxb");
        run(0, "document", "new", file.toString());
        Path script = directory.resolve("commands.json");
        Files.writeString(script, """
                [
                  ["designer", "sketch", "begin"],
                  ["designer", "sketch", "new", "--id", "outline", "--plane", "xy"],
                  ["designer", "sketch", "draw", "--sketch", "outline", "--id", "rectangle", "--tool", "rectangle-two-point", "--points", "0,0,60,40"],
                  ["designer", "sketch", "finish"],
                  ["designer", "solid", "extrude", "--id", "thickness", "--sketch", "outline", "--distance", "8 mm"],
                  ["designer", "body", "rename", "--id", "thickness.body", "--name", "Session plate"],
                  ["slicer", "plate", "add", "--plate", "second"],
                  ["undo"],
                  ["document", "save"]
                ]
                """);
        run(0, "--document", file.toString(), "session", "--script", script.toString());
        var document = DocumentReader.load(file);
        assertThat(document.features().size()).isEqualTo(2);
        assertThat(document.printSetup().plates().size()).isEqualTo(1);
        assertThat(document.bodyName("thickness.body", "missing")).isEqualTo("Session plate");
        assertVolume(19200, run(0, "--document", file.toString(), "designer", "inspect"));
        run(
                0,
                "--document",
                file.toString(),
                "slicer",
                "placement",
                "add",
                "--instance",
                "first",
                "--body",
                "thickness.body",
                "--x",
                "20",
                "--y",
                "30");
        String executable = dev.voxelbrew.slicer.prusa.PrusaSlicerBackend.defaultExecutable()
                .toString();
        Files.writeString(
                script,
                new com.google.gson.Gson()
                        .toJson(java.util.List.of(
                                java.util.List.of("slicer", "job", "slice", "--background", "--slicer", executable),
                                java.util.List.of("slicer", "job", "wait"),
                                java.util.List.of("document", "save"))));
        assertThat(run(0, "--document", file.toString(), "session", "--script", script.toString())
                        .contains("Slice complete"))
                .isTrue();
        assertThat(DocumentReader.load(file).activeGcode().isEmpty()).isFalse();
        byte[] saved = Files.readAllBytes(file);
        Files.writeString(
                script,
                new com.google.gson.Gson()
                        .toJson(java.util.List.of(
                                java.util.List.of("slicer", "job", "slice", "--background", "--slicer", executable),
                                java.util.List.of(
                                        "designer",
                                        "feature",
                                        "set",
                                        "--feature",
                                        "thickness",
                                        "--field",
                                        "distance",
                                        "--value",
                                        "9"),
                                java.util.List.of("slicer", "job", "wait"),
                                java.util.List.of("document", "save"))));
        assertThat(run(1, "--document", file.toString(), "session", "--script", script.toString())
                        .contains("Slice outdated"))
                .isTrue();
        assertThat(Files.readAllBytes(file)).containsExactly(saved);
        Files.writeString(
                script,
                new com.google.gson.Gson()
                        .toJson(java.util.List.of(
                                java.util.List.of("slicer", "job", "slice", "--background", "--slicer", executable),
                                java.util.List.of("slicer", "job", "cancel"))));
        assertThat(run(0, "--document", file.toString(), "session", "--script", script.toString())
                        .contains("Slice cancelled"))
                .isTrue();
        run(0, "--document", file.toString(), "slicer", "plate", "add", "--plate", "empty");
        run(0, "--document", file.toString(), "slicer", "job", "slice", "--all", "--slicer", executable);
        var sliced = DocumentReader.load(file);
        assertThat(sliced.printSetup().activePlateId()).isEqualTo("empty");
        assertThat(sliced.gcode().get("plate-1").isEmpty()).isFalse();
        assertThat(sliced.gcode().containsKey("empty")).isFalse();
    }

    @Test
    void bodyMaterialsAndColorsPersistAcrossCommandsAndInvalidAssignmentsDoNotWrite() throws Exception {
        String file = directory.resolve("colored objects.vxb").toString();
        run(0, "document", "new", file);
        run(0, "designer", "sketch", "rectangle", file, "--id", "plate", "--width", "60", "--height", "40");
        run(0, "designer", "solid", "extrude", file, "--id", "solid", "--sketch", "plate", "--distance", "5");
        run(
                0,
                "designer",
                "body",
                "style",
                "set",
                file,
                "--body",
                "solid.body",
                "--material",
                "PETG",
                "--color",
                "#FF0000");
        assertThat(run(0, "designer", "body", "style", "set", file).contains("solid.body\tPETG\t#FF0000"))
                .isTrue();
        run(0, "designer", "feature", "set", file, "--feature", "solid", "--field", "distance", "--value", "8");
        assertThat(run(0, "designer", "body", "style", "set", file).contains("solid.body\tPETG\t#FF0000"))
                .isTrue();
        byte[] saved = Files.readAllBytes(Path.of(file));
        run(1, "designer", "body", "style", "set", file, "--body", "solid.body", "--color", "red");
        run(1, "designer", "body", "style", "set", file, "--body", "missing.body", "--material", "PLA");
        run(1, "designer", "body", "style", "set", file, "--material", "PLA");
        run(1, "designer", "body", "style", "set", file, "--body", "solid.body", "--reset", "--material", "PLA");
        assertThat(Files.readAllBytes(Path.of(file))).containsExactly(saved);
        run(
                0,
                "designer",
                "body",
                "style",
                "set",
                file,
                "--body",
                "solid.body",
                "--material",
                "PLA",
                "--color",
                "#0000ff");
        assertThat(run(0, "designer", "body", "style", "set", file).contains("solid.body\tPLA\t#0000FF"))
                .isTrue();
        run(0, "designer", "body", "style", "set", file, "--body", "solid.body", "--reset");
        assertThat(DocumentReader.load(Path.of(file)).bodyStyles().isEmpty()).isTrue();
        assertVolume(19200, run(0, "designer", "inspect", file));
    }

    @Test
    void externalImporterListsProvidersAndPublishesEditableDocumentsAcrossProcesses() throws Exception {
        Path plugins = Files.createDirectories(directory.resolve("import plugins with spaces"));
        Files.copy(Path.of(System.getProperty("voxelbrew.test.plugin")), plugins.resolve("provider.jar"));
        String listing = run(0, "document", "importers", "--plugin-dir", plugins.toString());
        assertThat(listing.contains("plate\tExternal plate")).as(listing).isTrue();
        assertThat(listing.contains("json\tVoxelBrew JSON document"))
                .as(listing)
                .isTrue();
        Path source = directory.resolve("source plate.vbplate"), output = directory.resolve("imported design.vxb");
        Files.writeString(source, "60,40,4");
        run(
                0,
                "document",
                "import",
                source.toString(),
                "--format",
                "plate",
                "--plugin-dir",
                plugins.toString(),
                "--option",
                "scale=2",
                "--output",
                output.toString());
        assertVolume(76800, run(0, "designer", "inspect", output.toString()));
        byte[] saved = Files.readAllBytes(output);
        run(
                1,
                "document",
                "import",
                source.toString(),
                "--format",
                "failing",
                "--plugin-dir",
                plugins.toString(),
                "--output",
                output.toString(),
                "--overwrite");
        assertThat(Files.readAllBytes(output)).containsExactly(saved);
        run(
                1,
                "document",
                "import",
                source.toString(),
                "--format",
                "plate",
                "--plugin-dir",
                plugins.toString(),
                "--output",
                output.toString());
        assertThat(Files.readAllBytes(output)).containsExactly(saved);
        String error = run(
                1,
                "document",
                "import",
                source.toString(),
                "--format",
                "plate",
                "--plugin-dir",
                plugins.toString(),
                "--output",
                source.toString(),
                "--overwrite");
        assertThat(error.contains("must differ from its source")).as(error).isTrue();
        assertThat(Files.readString(source)).isEqualTo("60,40,4");
        assertThat(run(
                                1,
                                "document",
                                "import",
                                source.toString(),
                                "--format",
                                "plate",
                                "--plugin-dir",
                                plugins.toString())
                        .contains("requires --output"))
                .isTrue();
    }

    @Test
    void olderApplicationFilesUpgradeAndFutureFilesAreRejectedWithoutWrites() throws Exception {
        Path file = directory.resolve("versioned design.vxb");
        run(0, "document", "new", file.toString());
        run(0, "designer", "sketch", "rectangle", file.toString(), "--id", "plate", "--width", "60", "--height", "40");
        byte[] saved = Files.readAllBytes(file);
        var message = DocumentProto.Document.parseFrom(Arrays.copyOfRange(saved, 44, saved.length));
        assertThat(message.getApplicationVersion()).isEqualTo(Version.version());
        byte[] older = versioned(message, "0.0.0");
        Files.write(file, older);
        assertThat(run(0, "designer", "history", "list", file.toString()).contains("plate"))
                .isTrue();
        assertThat(Files.readAllBytes(file)).containsExactly(older);
        run(
                0,
                "designer",
                "feature",
                "set",
                file.toString(),
                "--feature",
                "plate",
                "--field",
                "width",
                "--value",
                "61");
        byte[] upgraded = Files.readAllBytes(file);
        var result = DocumentProto.Document.parseFrom(Arrays.copyOfRange(upgraded, 44, upgraded.length));
        assertThat(result.getApplicationVersion()).isEqualTo(Version.version());
        assertThat(result.getDocumentId()).isEqualTo(message.getDocumentId());
        assertThat(result.getFeatures(0).getId()).isEqualTo("plate");
        assertThat(result.getFeatures(0).getSketch().getRectangle().getWidth()).isEqualTo(61);
        byte[] future = versioned(result, "65534.65534.65534");
        Files.write(file, future);
        String error = run(1, "designer", "history", "list", file.toString());
        assertThat(error.contains("VoxelBrew 65534.65534.65534")).as(error).isTrue();
        assertThat(error.contains("supported version " + Version.version()))
                .as(error)
                .isTrue();
        assertThat(run(1, "document", "new", file.toString(), "--overwrite").contains("Update VoxelBrew"))
                .isTrue();
        assertThat(Files.readAllBytes(file)).containsExactly(future);
    }

    private void assertVolume(double expected, String output) {
        var match = java.util.regex.Pattern.compile("volume=([0-9.eE+\\-]+)").matcher(output);
        assertThat(match.find()).as(output).isTrue();
        assertThat(Double.parseDouble(match.group(1))).isCloseTo(expected, org.assertj.core.data.Offset.offset(.01));
    }

    @Test
    void namedParametersAndVariantsPersistAcrossProcessesAndExport() throws Exception {
        String file = directory.resolve("parameter configurations.vxb").toString();
        run(0, "document", "new", file);
        run(0, "designer", "sketch", "rectangle", file, "--id", "plate", "--width", "60", "--height", "40");
        run(0, "designer", "solid", "extrude", file, "--id", "solid", "--sketch", "plate", "--distance", "5");
        run(0, "designer", "parameter", "set", file, "--name", "width", "--unit", "cm", "--expression", "6");
        run(0, "designer", "parameter", "set", file, "--name", "thickness", "--expression", "5");
        run(
                0,
                "designer",
                "parameter",
                "bind",
                file,
                "--feature",
                "plate",
                "--field",
                "width",
                "--expression",
                "width");
        run(
                0,
                "designer",
                "feature",
                "set",
                file,
                "--feature",
                "solid",
                "--field",
                "distance",
                "--expression",
                "thickness");
        run(0, "designer", "configuration", "add", file, "--name", "Large");
        run(
                0,
                "designer",
                "configuration",
                "set",
                file,
                "--name",
                "Large",
                "--parameter",
                "width",
                "--expression",
                "8");
        run(
                0,
                "designer",
                "configuration",
                "set",
                file,
                "--name",
                "Large",
                "--parameter",
                "thickness",
                "--expression",
                "8");
        run(0, "designer", "configuration", "activate", file, "--name", "Large");
        assertThat(run(0, "designer", "parameter", "list", file).contains("width\tcm\texpression=8"))
                .isTrue();
        assertVolume(25600, run(0, "designer", "inspect", file));
        var ids = DocumentReader.load(Path.of(file)).features().stream()
                .map(Feature::id)
                .toList();
        run(0, "designer", "sketch", "convert", file, "--sketch", "plate");
        assertVolume(25600, run(0, "designer", "inspect", file));
        run(0, "designer", "parameter", "rename", file, "--name", "width", "--to", "plateWidth");
        assertThat(run(0, "designer", "parameter", "list", file).contains("constraint:width\tplateWidth"))
                .isTrue();
        byte[] before = Files.readAllBytes(Path.of(file));
        run(1, "designer", "parameter", "set", file, "--name", "plateWidth", "--expression", "plateWidth/2");
        assertThat(Files.readAllBytes(Path.of(file))).containsExactly(before);
        run(1, "designer", "parameter", "remove", file, "--name", "plateWidth");
        assertThat(Files.readAllBytes(Path.of(file))).containsExactly(before);
        run(0, "designer", "configuration", "activate", file, "--name", "Default");
        assertVolume(12000, run(0, "designer", "inspect", file));
        run(0, "designer", "configuration", "activate", file, "--name", "Large");
        assertThat(DocumentReader.load(Path.of(file)).features().stream()
                        .map(Feature::id)
                        .toList())
                .isEqualTo(ids);
        Path output = directory.resolve("large variant.3mf");
        run(0, "document", "export", file, "--format", "3mf", "--output", output.toString());
        assertThat(Files.size(output) > 1000).isTrue();
    }

    @Test
    void newSketchTypesAndTextPersistAcrossFreshProcesses() throws Exception {
        String file = directory.resolve("new sketch types.vxb").toString();
        run(0, "document", "new", file);
        run(0, "designer", "sketch", "new", file, "--id", "ellipse");
        run(
                0,
                "designer",
                "sketch",
                "draw",
                file,
                "--sketch",
                "ellipse",
                "--id",
                "outline",
                "--tool",
                "ELLIPSE",
                "--points",
                "0,0,10,0,0,5");
        run(
                0,
                "designer",
                "sketch",
                "constrain",
                file,
                "--sketch",
                "ellipse",
                "--id",
                "major",
                "--type",
                "MAJOR_RADIUS",
                "--refs",
                "outline",
                "--value",
                "10");
        run(0, "designer", "solid", "extrude", file, "--id", "oval", "--sketch", "ellipse", "--distance", "2");
        assertThat(run(0, "designer", "inspect", file).contains("volume=314.159"))
                .isTrue();
        run(
                0,
                "designer",
                "feature",
                "set",
                file,
                "--feature",
                "ellipse",
                "--field",
                "constraint:major",
                "--value",
                "12");
        assertThat(run(0, "designer", "inspect", file).contains("volume=376.991"))
                .isTrue();
        run(0, "designer", "sketch", "new", file, "--id", "slot");
        run(
                0,
                "designer",
                "sketch",
                "draw",
                file,
                "--sketch",
                "slot",
                "--id",
                "slot-outline",
                "--tool",
                "SLOT_ARC_CENTER",
                "--points",
                "40,0,50,0,40,10,52,0");
        run(0, "designer", "solid", "extrude", file, "--id", "curved-slot", "--sketch", "slot", "--distance", "2");
        run(0, "designer", "sketch", "new", file, "--id", "spline");
        run(
                0,
                "designer",
                "sketch",
                "draw",
                file,
                "--sketch",
                "spline",
                "--id",
                "fit",
                "--tool",
                "FIT_SPLINE",
                "--points",
                "0,30,10,30,10,40,0,40",
                "--closed");
        run(0, "designer", "solid", "extrude", file, "--id", "spline-body", "--sketch", "spline", "--distance", "2");
        run(0, "designer", "sketch", "new", file, "--id", "label");
        run(
                0,
                "designer",
                "sketch",
                "draw",
                file,
                "--sketch",
                "label",
                "--id",
                "text",
                "--tool",
                "TEXT",
                "--points",
                "30,30",
                "--text",
                "OB",
                "--height",
                "8");
        run(0, "designer", "solid", "extrude", file, "--id", "letters", "--sketch", "label", "--distance", "2");
        assertThat(run(0, "designer", "inspect", file).contains("bodyCount=5")).isTrue();
        var before = DocumentReader.load(Path.of(file));
        run(0, "designer", "sketch", "text-edit", file, "--sketch", "label", "--id", "text", "--text", "OC");
        var after = DocumentReader.load(Path.of(file));
        assertThat(after.id()).isEqualTo(before.id());
        assertThat(after.features().stream().map(Feature::id).toList())
                .isEqualTo(before.features().stream().map(Feature::id).toList());
        var bytes = Files.readAllBytes(Path.of(file));
        run(
                1,
                "designer",
                "sketch",
                "draw",
                file,
                "--sketch",
                "label",
                "--id",
                "invalid",
                "--tool",
                "CONIC",
                "--points",
                "0,0,20,0,10,10",
                "--rho",
                "1");
        assertThat(Files.readAllBytes(Path.of(file))).containsExactly(bytes);
        run(
                0,
                "document",
                "export",
                file,
                "--format",
                "3mf",
                "--output",
                directory.resolve("new types.3mf").toString());
    }

    @Test
    void constrainedProfilesPersistAndRejectConflictsAcrossFreshProcesses() throws Exception {
        String file = directory.resolve("CLI constrained plate.vxb").toString();
        run(0, "document", "new", file);
        run(0, "designer", "sketch", "rectangle", file, "--id", "plate", "--width", "60", "--height", "40");
        var before = DocumentReader.load(Path.of(file));
        run(0, "designer", "solid", "extrude", file, "--id", "thickness", "--sketch", "plate", "--distance", "8");
        run(0, "designer", "sketch", "convert", file, "--sketch", "plate");
        var converted =
                (Feature.Sketch) DocumentReader.load(Path.of(file)).features().getFirst();
        assertThat(converted.profileId())
                .isEqualTo(((Feature.Sketch) before.features().getFirst()).profileId());
        run(
                0,
                "designer",
                "sketch",
                "entity",
                file,
                "--sketch",
                "plate",
                "--id",
                "hole",
                "--kind",
                "CIRCLE",
                "--values",
                "10,10,2");
        run(
                0,
                "designer",
                "sketch",
                "constrain",
                file,
                "--sketch",
                "plate",
                "--id",
                "hole-fixed",
                "--type",
                "FIX",
                "--refs",
                "hole");
        assertThat(run(0, "designer", "sketch", "list", file, "--sketch", "plate")
                        .contains("Fully constrained"))
                .isTrue();
        assertThat(run(0, "designer", "inspect", file, "--point", "10,10,4").contains("contains=false"))
                .isTrue();
        var session = new dev.voxelbrew.engine.model.ModelSession(DocumentReader.load(Path.of(file)));
        try (var result = session.evaluate()) {
            assertThat(result.requireCompleteDocument().getFirst().solid().volume())
                    .isCloseTo(19099.469035085127, org.assertj.core.data.Offset.offset(0.01));
        }
        byte[] original = Files.readAllBytes(Path.of(file));
        assertThat(run(
                                1,
                                "designer",
                                "sketch",
                                "constrain",
                                file,
                                "--sketch",
                                "plate",
                                "--id",
                                "bad-width",
                                "--type",
                                "LENGTH",
                                "--refs",
                                "rectangle-bottom",
                                "--value",
                                "80")
                        .contains("cannot satisfy"))
                .isTrue();
        assertThat(Files.readAllBytes(Path.of(file))).containsExactly(original);
        run(
                0,
                "designer",
                "feature",
                "set",
                file,
                "--feature",
                "plate",
                "--field",
                "constraint:width",
                "--value",
                "80");
        original = Files.readAllBytes(Path.of(file));
        run(
                1,
                "designer",
                "sketch",
                "move",
                file,
                "--sketch",
                "plate",
                "--point",
                "rectangle-bottom.start",
                "--x",
                "4",
                "--y",
                "5");
        assertThat(Files.readAllBytes(Path.of(file))).containsExactly(original);
        run(0, "designer", "sketch", "remove-constraint", file, "--sketch", "plate", "--id", "anchor");
        run(
                0,
                "designer",
                "sketch",
                "move",
                file,
                "--sketch",
                "plate",
                "--point",
                "rectangle-bottom.start",
                "--x",
                "4",
                "--y",
                "5");
        var profile = (Feature.Constrained)
                ((Feature.Sketch) DocumentReader.load(Path.of(file)).features().getFirst()).profile();
        assertThat(profile.geometry().entity("rectangle-bottom").at(0).x())
                .isCloseTo(4, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(profile.geometry().entity("rectangle-bottom").at(0).y())
                .isCloseTo(5, org.assertj.core.data.Offset.offset(1e-6));
        run(0, "designer", "sketch", "solve", file, "--sketch", "plate");
        run(
                0,
                "document",
                "export",
                file,
                "--format",
                "3mf",
                "--output",
                directory.resolve("constrained plate.3mf").toString());
    }

    @Test
    void polygonAndExactSplineSketchesAreAvailableHeadlessly() throws Exception {
        String polygon = directory.resolve("polygon.vxb").toString();
        run(0, "document", "new", polygon);
        run(0, "designer", "sketch", "new", polygon, "--id", "hex");
        run(
                0,
                "designer",
                "sketch",
                "polygon",
                polygon,
                "--sketch",
                "hex",
                "--id",
                "boundary",
                "--radius",
                "10",
                "--sides",
                "6");
        assertThat(run(0, "designer", "sketch", "list", polygon, "--sketch", "hex")
                        .contains("POLYGON"))
                .isTrue();
        run(0, "designer", "solid", "extrude", polygon, "--id", "body", "--sketch", "hex", "--distance", "3");
        try (var evaluation = dev.voxelbrew.engine.model.Evaluator.evaluate(DocumentReader.load(Path.of(polygon)))) {
            assertThat(evaluation.requireCompleteDocument().getFirst().solid().volume())
                    .isCloseTo(3 * Math.sqrt(3) / 2 * 100 * 3, org.assertj.core.data.Offset.offset(0.001));
        }
        String spline = directory.resolve("spline.vxb").toString();
        run(0, "document", "new", spline);
        run(0, "designer", "sketch", "new", spline, "--id", "curve");
        run(
                0,
                "designer",
                "sketch",
                "entity",
                spline,
                "--sketch",
                "curve",
                "--id",
                "spline",
                "--kind",
                "SPLINE",
                "--values",
                "0,0,0,10,10,10,10,0");
        run(
                0,
                "designer",
                "sketch",
                "entity",
                spline,
                "--sketch",
                "curve",
                "--id",
                "chord",
                "--kind",
                "LINE",
                "--values",
                "10,0,0,0");
        run(0, "designer", "sketch", "profile", spline, "--sketch", "curve", "--entities", "spline,chord");
        run(0, "designer", "solid", "extrude", spline, "--id", "body", "--sketch", "curve", "--distance", "2");
        try (var evaluation = dev.voxelbrew.engine.model.Evaluator.evaluate(DocumentReader.load(Path.of(spline)))) {
            assertThat(evaluation.requireCompleteDocument().getFirst().solid().volume())
                    .isCloseTo(120, org.assertj.core.data.Offset.offset(1e-6));
        }
    }

    @Test
    void customProviderSurvivesFreshProcessesMissingPluginAndPrinting() throws Exception {
        Path plugins = Files.createDirectory(directory.resolve("feature plugins"));
        Path jar = plugins.resolve("provider.jar"), hidden = directory.resolve("removed provider.jar");
        Files.copy(Path.of(System.getProperty("voxelbrew.test.plugin")), jar);
        String file = directory.resolve("custom spacer.vxb").toString();
        String plugin = plugins.toString();
        assertThat(run(0, "designer", "feature", "types", "--plugin-dir", plugin)
                        .contains("outerDiameter"))
                .isTrue();
        run(0, "document", "new", file);
        run(
                0,
                "designer",
                "feature",
                "add",
                file,
                "--id",
                "spacer",
                "--provider",
                "example.spacer",
                "--type",
                "spacer",
                "--parameter",
                "height=5",
                "--plugin-dir",
                plugin);
        run(
                0,
                "designer",
                "feature",
                "set",
                file,
                "--feature",
                "spacer",
                "--field",
                "height",
                "--value",
                "8",
                "--plugin-dir",
                plugin);
        var before = DocumentReader.load(Path.of(file));
        byte[] bytes = Files.readAllBytes(Path.of(file));
        assertThat(run(
                                1,
                                "designer",
                                "feature",
                                "set",
                                file,
                                "--feature",
                                "spacer",
                                "--field",
                                "innerDiameter",
                                "--value",
                                "30",
                                "--plugin-dir",
                                plugin)
                        .contains("innerDiameter"))
                .isTrue();
        assertThat(Files.readAllBytes(Path.of(file))).containsExactly(bytes);
        assertThat(run(0, "designer", "inspect", file, "--point", "0,0,4", "--plugin-dir", plugin)
                        .contains("contains=false"))
                .isTrue();
        Path stl = directory.resolve("spacer.stl");
        run(0, "document", "export", file, "--format", "stl", "--output", stl.toString(), "--plugin-dir", plugin);
        byte[] mesh = Files.readAllBytes(stl);
        assertThat(mesh.length > 84).isTrue();
        Files.move(jar, hidden);
        assertThat(run(1, "designer", "history", "list", file, "--plugin-dir", plugin)
                        .contains("Missing feature provider"))
                .isTrue();
        run(1, "designer", "history", "cursor", file, "--position", "1", "--plugin-dir", plugin);
        assertThat(DocumentReader.load(Path.of(file))).isEqualTo(before);
        run(
                1,
                "document",
                "export",
                file,
                "--format",
                "stl",
                "--output",
                stl.toString(),
                "--overwrite",
                "--plugin-dir",
                plugin);
        assertThat(Files.readAllBytes(stl)).containsExactly(mesh);
        Files.move(hidden, jar);
        assertThat(run(0, "designer", "inspect", file, "--plugin-dir", plugin).contains("spacer.body"))
                .isTrue();
        run(
                0,
                "slicer",
                "placement",
                "add",
                file,
                "--instance",
                "placed",
                "--body",
                "spacer.body",
                "--x",
                "30",
                "--y",
                "30",
                "--plugin-dir",
                plugin);
        run(0, "slicer", "validate", file, "--plugin-dir", plugin);
        String job = directory.resolve("spacer.gcode").toString();
        assertThat(run(
                                0,
                                "slicer",
                                "job",
                                "slice",
                                file,
                                "--output",
                                job,
                                "--slicer",
                                dev.voxelbrew.slicer.prusa.PrusaSlicerBackend.defaultExecutable()
                                        .toString(),
                                "--plugin-dir",
                                plugin)
                        .contains("model_layers=40"))
                .isTrue();
    }

    @Test
    void publicExportCommandsAndOverwriteGuard() throws Exception {
        Path file = directory.resolve("export input.vxb"),
                stl = directory.resolve("plate.stl"),
                threeMf = directory.resolve("plate.3mf");
        run(0, "document", "new", file.toString());
        run(0, "designer", "sketch", "rectangle", file.toString(), "--id", "plate", "--width", "60", "--height", "40");
        run(
                0,
                "designer",
                "solid",
                "extrude",
                file.toString(),
                "--id",
                "thickness",
                "--sketch",
                "plate",
                "--distance",
                "8");
        assertThat(run(0, "document", "exporters", "--plugin-dir", directory.toString())
                        .contains("3mf"))
                .isTrue();
        run(0, "document", "export", file.toString(), "--format", "stl", "--output", stl.toString());
        byte[] bytes = Files.readAllBytes(stl);
        assertThat(run(1, "document", "export", file.toString(), "--format", "stl", "--output", stl.toString())
                        .contains("overwrite"))
                .isTrue();
        assertThat(Files.readAllBytes(stl)).containsExactly(bytes);
        run(0, "document", "export", file.toString(), "--format", "3mf", "--output", threeMf.toString());
        try (var zip = new java.util.zip.ZipFile(threeMf.toFile())) {
            assertThat(zip.getEntry("3D/3dmodel.model")).isNotNull();
        }
        run(0, "designer", "history", "cursor", file.toString(), "--position", "1");
        assertThat(run(
                                1,
                                "document",
                                "export",
                                file.toString(),
                                "--format",
                                "stl",
                                "--output",
                                stl.toString(),
                                "--overwrite")
                        .contains("timeline"))
                .isTrue();
        assertThat(Files.readAllBytes(stl)).containsExactly(bytes);
    }

    @Test
    void referenceWorkflowAcrossFreshProcesses() throws Exception {
        Path file = directory.resolve("reference part.vxb");
        run(0, "document", "new", file.toString());
        run(0, "designer", "sketch", "rectangle", file.toString(), "--id", "plate", "--width", "60", "--height", "40");
        run(
                0,
                "designer",
                "solid",
                "extrude",
                file.toString(),
                "--id",
                "thickness",
                "--sketch",
                "plate",
                "--distance",
                "5");
        run(
                0,
                "designer",
                "sketch",
                "circle",
                file.toString(),
                "--id",
                "hole",
                "--center-x",
                "10",
                "--center-y",
                "10",
                "--diameter",
                "4");
        run(
                0,
                "designer",
                "solid",
                "cut",
                file.toString(),
                "--id",
                "cut",
                "--sketch",
                "hole",
                "--body-feature",
                "thickness");
        var before = DocumentReader.load(file);
        var ids = before.features().stream().map(Feature::id).toList();
        assertThat(ids).isEqualTo(Arrays.asList("plate", "thickness", "hole", "cut"));
        assertThat(run(0, "designer", "inspect", file.toString()).contains("volume=11937.168146928205"))
                .isTrue();
        run(
                0,
                "designer",
                "feature",
                "set",
                file.toString(),
                "--feature",
                "thickness",
                "--field",
                "distance",
                "--value",
                "8");
        var reopened = DocumentReader.load(file);
        assertThat(reopened.id()).isEqualTo(before.id());
        assertThat(reopened.features().stream().map(Feature::id).toList()).isEqualTo(ids);
        String measurements = run(0, "designer", "inspect", file.toString(), "--point", "10,10,4");
        assertThat(measurements.contains("volume=19099.469035085127"))
                .as(measurements)
                .isTrue();
        assertThat(measurements.contains(
                        "bounds=0.000000000,0.000000000,0.000000000,60.000000000,40.000000000,8.000000000"))
                .isTrue();
        assertThat(measurements.contains("contains=false")).isTrue();
        assertThat(run(0, "designer", "inspect", file.toString(), "--point", "13,10,4")
                        .contains("contains=true"))
                .isTrue();
        run(0, "designer", "history", "cursor", file.toString(), "--position", "3");
        assertThat(run(0, "designer", "inspect", file.toString()).contains("volume=19200.000000000000"))
                .isTrue();
        run(0, "designer", "history", "cursor", file.toString(), "--position", "4");
        byte[] good = Files.readAllBytes(file);
        String invalid = run(
                1,
                "designer",
                "feature",
                "set",
                file.toString(),
                "--feature",
                "hole",
                "--field",
                "diameter",
                "--value",
                "NaN");
        assertThat(invalid.contains("hole") && invalid.contains("diameter")).isTrue();
        assertThat(Files.readAllBytes(file)).containsExactly(good);
        String history = run(0, "designer", "history", "list", file.toString());
        assertThat(history.contains("profileId=hole.profile") && history.contains("distance=8.0"))
                .isTrue();
        Files.write(file, Arrays.copyOf(good, good.length - 3));
        assertThat(run(1, "designer", "inspect", file.toString()).toLowerCase().contains("truncated"))
                .isTrue();
        System.out.println(measurements);
    }

    @Test
    void referenceRepairIsAvailableThroughPublicCommands() throws Exception {
        Path file = directory.resolve("repair.vxb");
        run(0, "document", "new", file.toString());
        run(0, "designer", "sketch", "rectangle", file.toString(), "--id", "plate", "--width", "60", "--height", "40");
        run(
                0,
                "designer",
                "solid",
                "extrude",
                file.toString(),
                "--id",
                "thickness",
                "--sketch",
                "plate",
                "--distance",
                "8");
        var document = DocumentReader.load(file);
        var features = new ArrayList<>(document.features());
        features.set(1, new Feature.Extrude("thickness", new Feature.ProfileRef("missing", "profile"), 8));
        DocumentWriter.save(new dev.voxelbrew.api.model.CadDocument(document.id(), features, 2), file, true);
        assertThat(run(1, "designer", "history", "list", file.toString()).contains("FAILED"))
                .isTrue();
        run(0, "designer", "feature", "repair", file.toString(), "--feature", "thickness", "--sketch", "plate");
        assertThat(run(0, "designer", "inspect", file.toString()).contains("volume=19200.000000000000"))
                .isTrue();
        assertThat(DocumentReader.load(file).features().size()).isEqualTo(2);
    }

    @Test
    void splitPlacementSupportAndSlicingAcrossFreshProcesses() throws Exception {
        String file = directory.resolve("manufacturing fixture.vxb").toString();
        run(0, "document", "new", file);
        run(0, "designer", "sketch", "rectangle", file, "--id", "plate", "--width", "60", "--height", "40");
        run(0, "designer", "solid", "extrude", file, "--id", "thickness", "--sketch", "plate", "--distance", "8");
        run(
                0,
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
                "4");
        run(0, "designer", "solid", "cut", file, "--id", "cut", "--sketch", "hole", "--body-feature", "thickness");
        run(0, "designer", "solid", "split", file, "--id", "halves", "--body-feature", "cut", "--offset", "30");
        assertThat(run(0, "designer", "inspect", file).contains("halves.negative"))
                .isTrue();
        run(
                0,
                "document",
                "export",
                file,
                "--format",
                "stl",
                "--body",
                "halves.negative",
                "--output",
                directory.resolve("left.stl").toString());
        run(
                0,
                "document",
                "export",
                file,
                "--format",
                "stl",
                "--body",
                "halves.positive",
                "--output",
                directory.resolve("right.stl").toString());
        run(
                0,
                "slicer",
                "placement",
                "add",
                file,
                "--instance",
                "left",
                "--body",
                "halves.negative",
                "--x",
                "20",
                "--y",
                "30");
        run(
                0,
                "slicer",
                "placement",
                "add",
                file,
                "--instance",
                "right",
                "--body",
                "halves.positive",
                "--x",
                "30",
                "--y",
                "30");
        run(
                0,
                "slicer",
                "supports",
                "set",
                file,
                "--id",
                "blocker",
                "--instance",
                "left",
                "--role",
                "BLOCKER",
                "--width",
                "5",
                "--depth",
                "5",
                "--height",
                "8");
        run(0, "slicer", "process", "set", file, "--material", "PETG");
        assertThat(run(0, "slicer", "inspect", file).contains("PETG")).isTrue();
        assertThat(run(0, "slicer", "validate", file).contains("VALID")).isTrue();
        String output = directory.resolve("CLI real job.gcode").toString();
        String slicer = dev.voxelbrew.slicer.prusa.PrusaSlicerBackend.defaultExecutable()
                .toString();
        assertThat(run(0, "slicer", "job", "slice", file, "--output", output, "--slicer", slicer)
                        .contains("model_layers=40"))
                .isTrue();
        assertThat(run(0, "slicer", "job", "inspect", output).contains("material=PETG"))
                .isTrue();
        assertThat(DocumentReader.load(Path.of(file)).activeGcode()).isEqualTo(Files.readAllLines(Path.of(output)));
        Path exported = directory.resolve("stored-job.gcode");
        run(0, "document", "export", file, "--format", "gcode", "--output", exported.toString());
        assertThat(Files.readAllLines(exported)).isEqualTo(Files.readAllLines(Path.of(output)));
        byte[] original = Files.readAllBytes(Path.of(output));
        assertThat(run(1, "slicer", "job", "slice", file, "--output", output, "--slicer", slicer)
                        .contains("overwrite"))
                .isTrue();
        assertThat(Files.readAllBytes(Path.of(output))).containsExactly(original);
        run(0, "slicer", "placement", "remove", file, "--instance", "left");
        assertThat(DocumentReader.load(Path.of(file)).gcode().isEmpty()).isTrue();
        assertThat(run(
                                1,
                                "document",
                                "export",
                                file,
                                "--format",
                                "gcode",
                                "--output",
                                exported.toString(),
                                "--overwrite")
                        .contains("No G-code"))
                .isTrue();
        assertThat(run(1, "slicer", "validate", file).contains("MISSING_INSTANCE"))
                .isTrue();
        run(0, "slicer", "supports", "remove", file, "--id", "blocker");
        run(0, "slicer", "validate", file);
    }

    private String run(int expected, String... args) throws Exception {
        var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-Dvoxelbrew.native.path=" + System.getProperty("voxelbrew.native.path"));
        command.add("-Dvoxelbrew.home=" + directory.resolve("settings"));
        command.add("-cp");
        command.add(System.getProperty("voxelbrew.test.classpath"));
        command.add(VoxelBrew.class.getName());
        command.addAll(Arrays.asList(args));
        Path log = Files.createTempFile(directory, "command-", ".txt");
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("CLI timed out: " + command);
        }
        String output = Files.readString(log);
        assertThat(process.exitValue())
                .as(String.join(" ", args) + "\n" + output)
                .isEqualTo(expected);
        return output;
    }
}
