package dev.voxelbrew.cli;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.model.CadDocument;
import dev.voxelbrew.util.di.ApplicationModule;
import dev.voxelbrew.util.update.UpdateServiceFactory;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class DependencyInjectionTest {
    @TempDir
    Path directory;

    private static CommandLine quiet(CommandLine command) {
        return command.setOut(new PrintWriter(new StringWriter())).setErr(new PrintWriter(new StringWriter()));
    }

    @Test
    void printerCommandsListAndEmbedHardwareWithoutNativeEvaluation() throws Exception {
        var document = new AtomicReference<>(CadDocument.empty());
        var files = new FileIoModule(path -> document.get(), (value, path, overwrite) -> document.set(value), path -> {
            throw new IOException("No G-code needed");
        });
        var graph = graph("en", files, () -> {
            throw new AssertionError("No update lookup");
        });
        var listing = new StringWriter();
        var command = quiet(graph.commandLine()).setOut(new PrintWriter(listing));
        assertThat(command.execute("slicer", "profile", "list")).isEqualTo(0);
        assertThat(listing.toString().contains("bambu-lab-a1-mini")).isTrue();
        assertThat(quiet(graph.commandLine())
                        .execute("slicer", "process", "set", "virtual.vxb", "--printer-profile", "bambu-lab-a1-mini"))
                .isEqualTo(0);
        var saved = document.get().printSetup();
        assertThat(saved.version()).isEqualTo(4);
        assertThat(saved.printerSnapshot().printer().usableHeight()).isEqualTo(180);
        assertThat(quiet(graph.commandLine()).execute("slicer", "process", "set", "virtual.vxb", "--material", "PETG"))
                .isEqualTo(0);
        assertThat(document.get().printSetup().printerSnapshot()).isEqualTo(saved.printerSnapshot());
        assertThat(quiet(graph.commandLine())
                        .execute("slicer", "process", "set", "virtual.vxb", "--printer-profile", "missing-printer"))
                .isEqualTo(1);
        assertThat(document.get().printSetup().printerSnapshot()).isEqualTo(saved.printerSnapshot());
    }

    @Test
    void injectedPortsReachNestedCommandsAndParsedArgumentsNeverLeakToTheNextParser() throws Exception {
        var document = new AtomicReference<>(CadDocument.empty());
        var overwrites = new ArrayList<Boolean>();
        var reads = new AtomicInteger();
        var files = new FileIoModule(
                path -> {
                    reads.incrementAndGet();
                    return document.get();
                },
                (value, path, overwrite) -> {
                    document.set(value);
                    overwrites.add(overwrite);
                },
                path -> {
                    throw new IOException("Injected G-code reader");
                });
        var graph = graph("en", files, () -> {
            throw new AssertionError("Unexpected update lookup");
        });
        var first = quiet(graph.commandLine());
        Path destination = directory.resolve("virtual design.vxb");
        assertThat(first.execute("document", "new", destination.toString(), "--overwrite"))
                .isEqualTo(0);
        var second = quiet(graph.commandLine());
        assertThat(second.execute("document", "new", destination.toString())).isEqualTo(0);
        assertThat(overwrites).isEqualTo(List.of(true, false));
        assertThat(Files.exists(destination))
                .as("Injected sink must replace filesystem I/O")
                .isFalse();
        assertThat(second.getSubcommands()
                                .get("document")
                                .getSubcommands()
                                .get("new")
                                .getCommand()
                        != first.getSubcommands()
                                .get("document")
                                .getSubcommands()
                                .get("new")
                                .getCommand())
                .isTrue();
        assertThat(quiet(graph.commandLine()).execute("designer", "parameter", "list", destination.toString()))
                .isEqualTo(0);
        assertThat(reads.get()).isEqualTo(1);
        assertThat(quiet(graph.commandLine()).execute("slicer", "job", "inspect", "injected.gcode"))
                .isEqualTo(1);
        assertThat(first.getFactory()).isSameAs(graph.commands());
        assertThat(first.getSubcommands()
                        .get("designer")
                        .getSubcommands()
                        .get("sketch")
                        .getSubcommands()
                        .get("draw")
                        .getFactory())
                .isSameAs(first.getFactory());
    }

    @Test
    void configurationAndUpdateFactoriesAreScopedToIndependentApplicationGraphs() throws Exception {
        var updates = new AtomicInteger();
        var polish = graph("pl", new FileIoModule(), () -> {
            updates.incrementAndGet();
            throw new IOException("Offline test updater");
        });
        var english = graph("en", new FileIoModule(), () -> {
            throw new AssertionError("Other graph's updater was called");
        });
        assertThat(polish.translations().locale().getLanguage()).isEqualTo("pl");
        assertThat(english.translations().locale().getLanguage()).isEqualTo("en");
        assertThat(polish.translations()).isSameAs(polish.translations());
        assertThat(english.translations()).isNotSameAs(polish.translations());
        assertThat(quiet(polish.commandLine()).execute("settings", "update", "--help"))
                .isEqualTo(0);
        assertThat(updates.get())
                .as("Help and graph construction must not create the updater")
                .isEqualTo(0);
        assertThat(quiet(polish.commandLine()).execute("settings", "update", "check"))
                .isEqualTo(1);
        assertThat(updates.get()).isEqualTo(1);
        assertThat(quiet(english.commandLine()).execute("--help")).isEqualTo(0);
    }

    private CliComponent graph(String language, FileIoModule files, UpdateServiceFactory updates) throws IOException {
        var config = new dev.voxelbrew.util.config.FileUserConfiguration(directory.resolve(language + ".properties"));
        config.set("language", language);
        return CliComponent.factory().create(new ApplicationModule(config, updates), files);
    }
}
