package dev.voxelbrew.cli;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.model.CadDocument;
import dev.voxelbrew.util.di.ApplicationModule;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class WorkspaceCommandsTest {
    @TempDir
    Path directory;

    @Test
    void disablingSupportsRetainsDefinitionsAndMovingAnInstanceMovesItsAttachments() {
        var owner =
                new dev.voxelbrew.api.model.BodyInstance("first", "body", dev.voxelbrew.api.model.Transform.IDENTITY);
        var support = new dev.voxelbrew.api.model.printing.SupportVolume(
                "support",
                "first",
                dev.voxelbrew.api.model.printing.SupportVolume.Role.SOLID_SUPPORT,
                0,
                0,
                0,
                5,
                5,
                5);
        var setup = dev.voxelbrew.api.model.printing.PrintSetup.empty()
                .withInstances(List.of(owner))
                .withSupports(List.of(support));
        var document = new AtomicReference<>(
                new CadDocument("doc", List.of(), 0, setup).withGcode(Map.of("plate-1", List.of("G1 X1"))));
        var saves = new ArrayList<CadDocument>();
        assertThat(parser(document, saves).execute("--document", "virtual.vxb", "slicer", "supports", "disable"))
                .isEqualTo(0);
        assertThat(document.get().printSetup().supportsDisabled()).isTrue();
        assertThat(document.get().printSetup().supports()).isEqualTo(List.of(support));
        assertThat(document.get().gcode().isEmpty()).isTrue();
        assertThat(parser(document, saves)
                        .execute("--document", "virtual.vxb", "slicer", "plate", "add", "--plate", "second"))
                .isEqualTo(0);
        assertThat(parser(document, saves)
                        .execute("--document", "virtual.vxb", "slicer", "plate", "select", "--plate", "plate-1"))
                .isEqualTo(0);
        assertThat(parser(document, saves)
                        .execute(
                                "--document",
                                "virtual.vxb",
                                "slicer",
                                "placement",
                                "move-to-plate",
                                "--instance",
                                "first",
                                "--plate",
                                "second"))
                .isEqualTo(0);
        assertThat(document.get().printSetup().instances().isEmpty()).isTrue();
        assertThat(document.get().printSetup().supports().isEmpty()).isTrue();
        var destination = document.get().printSetup().selectPlate("second");
        assertThat(destination.instances()).isEqualTo(List.of(owner));
        assertThat(destination.supports()).isEqualTo(List.of(support));
    }

    private int script(AtomicReference<CadDocument> document, List<CadDocument> saves, String json) throws Exception {
        Path script = directory.resolve("script.json");
        Files.writeString(script, json);
        return parser(document, saves).execute("--document", "session.vxb", "session", "--script", script.toString());
    }

    @Test
    void sketchFinishCreatesOneDocumentUndoStepAndCancelDiscardsLocalUndoRedo() throws Exception {
        var document = new AtomicReference<>(CadDocument.empty());
        var saves = new ArrayList<CadDocument>();
        assertThat(script(document, saves, """
                [["designer","sketch","begin"],
                 ["designer","sketch","new","--id","a"],
                 ["designer","sketch","new","--id","b"],
                 ["designer","sketch","finish"],["undo"],["document","save"]]
                """)).isEqualTo(0);
        assertThat(document.get().features().isEmpty()).isTrue();
        assertThat(saves.size()).isEqualTo(1);
        saves.clear();
        assertThat(script(document, saves, """
                [["designer","sketch","begin"],["designer","sketch","new","--id","a"],
                 ["undo"],["redo"],["designer","sketch","cancel"],["document","save"]]
                """)).isEqualTo(0);
        assertThat(document.get().features().isEmpty()).isTrue();
        assertThat(saves.size()).isEqualTo(1);
    }

    @Test
    void openDraftRejectsDocumentAndSolidOperationsWithoutPublishing() throws Exception {
        for (String command : List.of("[\"document\",\"save\"]", "[\"slicer\",\"supports\",\"disable\"]")) {
            var initial = CadDocument.empty();
            var document = new AtomicReference<>(initial);
            var saves = new ArrayList<CadDocument>();
            assertThat(script(document, saves, "[[\"designer\",\"sketch\",\"begin\"]," + command + "]"))
                    .isEqualTo(1);
            assertThat(saves.isEmpty()).isTrue();
            assertThat(document.get()).isEqualTo(initial);
        }
    }

    @Test
    void cursorChangesAreNotUndoEntriesAndUndoNeverRewindsIdsOrRestoresStaleJobs() throws Exception {
        var model = new dev.voxelbrew.engine.model.ModelSession(CadDocument.empty());
        model.add(new dev.voxelbrew.api.model.Feature.Sketch(
                "outline",
                "profile",
                dev.voxelbrew.api.model.Feature.Plane.origin("XY"),
                new dev.voxelbrew.api.model.Feature.Rectangle(0, 0, 10, 10)));
        var initial = model.document().withGcode(Map.of("plate-1", List.of("G1 X1")));
        long ids = initial.uuidGenerator().nextValue();
        var document = new AtomicReference<>(initial);
        var saves = new ArrayList<CadDocument>();
        assertThat(script(document, saves, """
                [["designer","parameter","set","--name","width","--unit","mm","--expression","10"],
                 ["designer","history","cursor","--position","0"],["undo"],["document","save"]]
                """)).isEqualTo(0);
        assertThat(document.get().cursor()).isEqualTo(0);
        assertThat(document.get().parameters().parameters().isEmpty()).isTrue();
        assertThat(document.get().gcode().isEmpty()).isTrue();
        assertThat(document.get().uuidGenerator().nextValue() > ids).isTrue();
    }

    @Test
    void redoIsDiscardedByANewEditAndFailedBatchDoesNotSave() throws Exception {
        var initial = CadDocument.empty();
        var document = new AtomicReference<>(initial);
        var saves = new ArrayList<CadDocument>();
        assertThat(script(document, saves, """
                [["slicer","plate","add","--plate","a"],["undo"],
                 ["slicer","plate","add","--plate","b"],["redo"],["document","save"]]
                """)).isEqualTo(1);
        assertThat(saves.isEmpty()).isTrue();
        assertThat(document.get()).isEqualTo(initial);
    }

    @Test
    void jsonOutputHasAStableEnvelopeAndFlatCommandsStayUnavailable() {
        var cli = parser(new AtomicReference<>(CadDocument.empty()), new ArrayList<>());
        var output = new StringWriter();
        cli.setOut(new PrintWriter(output));
        assertThat(cli.execute("--output-format", "json", "--document", "virtual.vxb", "slicer", "plate", "list"))
                .isEqualTo(0);
        var result = com.google.gson.JsonParser.parseString(output.toString()).getAsJsonObject();
        assertThat(result.get("exitCode").getAsInt()).isEqualTo(0);
        assertThat(result.getAsJsonArray("output").get(0).getAsString().contains("plate-1"))
                .isTrue();
    }

    private CommandLine parser(AtomicReference<CadDocument> document, List<CadDocument> saves) {
        var files = new FileIoModule(
                path -> document.get(),
                (next, path, overwrite) -> {
                    document.set(next);
                    saves.add(next);
                },
                path -> {
                    throw new IOException("No job");
                });
        return CliComponent.factory()
                .create(
                        new ApplicationModule(
                                new dev.voxelbrew.util.config.FileUserConfiguration(
                                        directory.resolve("config.properties")),
                                () -> {
                                    throw new AssertionError("No update lookup");
                                }),
                        files)
                .commandLine()
                .setOut(new PrintWriter(new StringWriter()))
                .setErr(new PrintWriter(new StringWriter()));
    }

    @Test
    void workspaceTreeRejectsFlatCommandsAndEveryLeafHasUsableHelp() {
        var cli = parser(new AtomicReference<>(CadDocument.empty()), new ArrayList<>());
        assertThat(cli.getSubcommands().keySet())
                .isEqualTo(Set.of("document", "designer", "slicer", "printer", "settings", "session"));
        assertThat(cli.execute("new", "obsolete.vxb")).isEqualTo(2);
        assertThat(cli.execute("designer", "inspect")).isEqualTo(2);
        verifyHelp(cli, new ArrayList<>());
    }

    private void verifyHelp(CommandLine command, List<String> path) {
        if (command.getSubcommands().isEmpty()) {
            var args = new ArrayList<>(path);
            args.add("--help");
            assertThat(parser(new AtomicReference<>(CadDocument.empty()), new ArrayList<>())
                            .execute(args.toArray(String[]::new)))
                    .as(path.toString())
                    .isEqualTo(0);
        } else
            command.getSubcommands().forEach((name, child) -> {
                var next = new ArrayList<>(path);
                next.add(name);
                verifyHelp(child, next);
            });
    }

    @Test
    void documentOptionAndPlateMutationsPreserveUnrelatedSetupAndProtectLastPlate() {
        var document = new AtomicReference<>(CadDocument.empty());
        var saves = new ArrayList<CadDocument>();
        assertThat(parser(document, saves)
                        .execute("--document", "part with spaces.vxb", "slicer", "plate", "add", "--plate", "second"))
                .isEqualTo(0);
        assertThat(document.get().printSetup().plates().size()).isEqualTo(2);
        assertThat(parser(document, saves)
                        .execute(
                                "--document",
                                "part with spaces.vxb",
                                "slicer",
                                "plate",
                                "rename",
                                "--plate",
                                "second",
                                "--name",
                                "Second plate"))
                .isEqualTo(0);
        assertThat(document.get().printSetup().activePlate().name()).isEqualTo("Second plate");
        assertThat(parser(document, saves)
                        .execute("slicer", "plate", "remove", "part with spaces.vxb", "--plate", "second"))
                .isEqualTo(0);
        var before = document.get();
        assertThat(parser(document, saves)
                        .execute(
                                "--document",
                                "part with spaces.vxb",
                                "slicer",
                                "plate",
                                "remove",
                                "--plate",
                                "plate-1"))
                .isEqualTo(1);
        assertThat(document.get()).isEqualTo(before);
        assertThat(parser(document, saves).execute("--document", "a.vxb", "document", "new", "b.vxb"))
                .isEqualTo(2);
    }

    @Test
    void batchSessionOnlyPublishesOnSaveAndUndoRestoresThePreviousDocument() throws Exception {
        var initial = CadDocument.empty();
        var document = new AtomicReference<>(initial);
        var saves = new ArrayList<CadDocument>();
        Path script = directory.resolve("session.json");
        Files.writeString(
                script, "[[\"slicer\",\"plate\",\"add\",\"--plate\",\"second\"],[\"undo\"],[\"document\",\"save\"]]");
        assertThat(parser(document, saves)
                        .execute("--document", "session.vxb", "session", "--script", script.toString()))
                .isEqualTo(0);
        assertThat(saves).isEqualTo(List.of(initial));
        saves.clear();
        Files.writeString(script, "[[\"document\",\"new\"],[\"unknown\"],[\"document\",\"save\"]]");
        assertThat(parser(document, saves)
                        .execute("--document", "session.vxb", "session", "--script", script.toString()))
                .isEqualTo(2);
        assertThat(saves.isEmpty()).isTrue();
    }
}
