package dev.voxelbrew.cli;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.engine.model.*;
import dev.voxelbrew.engine.printing.*;
import dev.voxelbrew.printer.api.*;
import dev.voxelbrew.slicer.api.PrinterProfileCatalog;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.DoubleConsumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** Tests command decisions against ports, without network, kernel, slicer or subprocesses. */
class DeviceCommandsTest {
    @TempDir
    Path directory;

    private final FakeRuntime runtime = new FakeRuntime();
    private final Printer printer = new Printer(
            "device-1",
            "Workshop",
            Printer.Kind.MOONRAKER,
            "profile-one",
            "http://printer.local",
            "",
            "",
            "TOP-SECRET");
    private CadDocument document = CadDocument.empty();
    private int writes;
    private String output;

    private static PrinterProfile profile() {
        var original = PrintProfile.resolve(PrintSetup.empty()).printer();
        return new PrinterProfile(
                new PrintProfile.Printer(
                        "profile-one",
                        "Test machine",
                        original.nozzleMm(),
                        original.bed(),
                        original.excludedZones(),
                        original.usableHeight(),
                        original.firmware(),
                        original.outputFormat()),
                "Test",
                1.75,
                1,
                true,
                false);
    }

    private PrinterProfileCatalog catalog() {
        return new PrinterProfileCatalog() {
            public Path directory() {
                return directory;
            }

            public Result load() {
                return new Result(List.of(new Entry(profile(), "", "test", true)), List.of());
            }

            public int exportDefaults(Set<String> ids) {
                throw new AssertionError("Unexpected export");
            }

            public String fallbackImage() {
                return "";
            }
        };
    }

    private int run(String... args) {
        var root = new CommandLine(CommandLine.Model.CommandSpec.create().name("test"));
        new DeviceCommands(
                        runtime,
                        path -> document,
                        (value, path, overwrite) -> {
                            document = value;
                            writes++;
                        },
                        catalog())
                .install(root);
        var text = new StringWriter();
        root.setOut(new PrintWriter(text));
        root.setErr(new PrintWriter(text));
        root.setExecutionExceptionHandler((failure, command, parsed) -> {
            command.getErr().println(failure.getMessage());
            return 1;
        });
        int result = root.execute(args);
        output = text.toString();
        return result;
    }

    private void storedJob() {
        runtime.devices.add(printer);
        var setup = PrintSetup.empty().withPrinter(profile()).withPrintTarget(printer.id());
        var session = new ModelSession(document);
        session.setPrintSetup(setup);
        var lines = List.of("G1 X1");
        var artifact = PrintTarget.attach(
                PrintArtifact.of("gcode", "G1 X1\n".getBytes(java.nio.charset.StandardCharsets.UTF_8), Map.of()),
                lines,
                setup);
        document =
                session.document().withGcode(Map.of("plate-1", lines)).withPrintArtifacts(Map.of("plate-1", artifact));
    }

    @Test
    void deviceListingAndDetailsNeverExposeSecretOrInternalIdentity() {
        runtime.devices.add(printer);
        assertThat(run("printer", "device", "list")).isEqualTo(0);
        assertThat(output.contains("Workshop")).isTrue();
        assertThat(output.contains(printer.secret())).isFalse();
        assertThat(output.contains(printer.id())).isFalse();
        assertThat(run("printer", "device", "show", "--device", " workshop ")).isEqualTo(0);
        assertThat(output.contains(printer.address())).isTrue();
        assertThat(output.contains(printer.secret())).isFalse();
        assertThat(output.contains(printer.id())).isFalse();
        assertThat(runtime.calls.isEmpty()).isTrue();
    }

    @Test
    void renamingPreservesIdentityProfileAndCredentials() {
        runtime.devices.add(printer);
        assertThat(run("printer", "device", "set", "--device", "WORKSHOP", "--name", "Renamed"))
                .isEqualTo(0);
        var saved = runtime.devices.getFirst();
        assertThat(saved.id()).isEqualTo(printer.id());
        assertThat(saved.name()).isEqualTo("Renamed");
        assertThat(saved.secret()).isEqualTo(printer.secret());
        assertThat(saved.profileId()).isEqualTo(printer.profileId());
    }

    @Test
    void secretFileIsReadButInvalidDeviceIsNeverSaved() throws Exception {
        Path secret = directory.resolve("secret.txt");
        Files.writeString(secret, "replacement\n");
        assertThat(run(
                        "printer",
                        "device",
                        "add",
                        "--device",
                        "New",
                        "--kind",
                        "MOONRAKER",
                        "--profile",
                        "profile-one",
                        "--address",
                        "http://machine.local",
                        "--secret-file",
                        secret.toString()))
                .isEqualTo(0);
        assertThat(runtime.devices.getFirst().secret()).isEqualTo("replacement");
        assertThat(output.contains("replacement")).isFalse();
        assertThat(run("printer", "device", "set", "--device", "New", "--address", "ftp://invalid"))
                .isEqualTo(1);
        assertThat(runtime.devices.getFirst().address()).isEqualTo("http://machine.local");
    }

    @Test
    void uploadDoesNotStartAndAlwaysRemovesItsTemporaryArtifact() {
        storedJob();
        assertThat(run("printer", "upload", "virtual.vxb", "--device", "Workshop"))
                .isEqualTo(0);
        assertThat(runtime.calls).isEqualTo(List.of("connect", "upload", "close"));
        assertThat(runtime.uploaded)
                .containsExactly(document.printArtifacts().get("plate-1").bytes());
        assertThat(Files.exists(runtime.temporary)).isFalse();
        assertThat(writes).isEqualTo(0);
    }

    @Test
    void printChecksReadinessBeforeUploadAndNeverRetriesAnUncertainStart() {
        storedJob();
        runtime.state = PrinterStatus.State.PRINTING;
        assertThat(run("printer", "print", "virtual.vxb", "--device", "Workshop"))
                .isEqualTo(1);
        assertThat(runtime.calls).isEqualTo(List.of("connect", "status", "close"));
        runtime.calls.clear();
        runtime.state = PrinterStatus.State.IDLE;
        runtime.failStart = true;
        assertThat(run("printer", "print", "virtual.vxb", "--device", "Workshop"))
                .isEqualTo(1);
        assertThat(runtime.calls).isEqualTo(List.of("connect", "status", "upload", "start", "close"));
        assertThat(Files.exists(runtime.temporary)).isFalse();
    }

    @Test
    void incompatibleOrMissingJobIsRejectedBeforeConnecting() {
        storedJob();
        document = document.withGcode(Map.of());
        assertThat(run("printer", "upload", "virtual.vxb", "--device", "Workshop"))
                .isEqualTo(1);
        assertThat(runtime.calls.isEmpty()).isTrue();
        storedJob();
        var artifact = document.printArtifacts().get("plate-1");
        var metadata = new HashMap<>(artifact.metadata());
        metadata.put("target_id", "other-device");
        document =
                document.withPrintArtifacts(Map.of("plate-1", PrintArtifact.of("gcode", artifact.bytes(), metadata)));
        assertThat(run("printer", "print", "virtual.vxb", "--device", "Workshop"))
                .isEqualTo(1);
        assertThat(runtime.calls.isEmpty()).isTrue();
    }

    @Test
    void failedUploadClosesConnectionDeletesTemporaryAndDoesNotStart() {
        storedJob();
        runtime.failUpload = true;
        assertThat(run("printer", "print", "virtual.vxb", "--device", "Workshop"))
                .isEqualTo(1);
        assertThat(runtime.calls).isEqualTo(List.of("connect", "status", "upload", "close"));
        assertThat(Files.exists(runtime.temporary)).isFalse();
    }

    @Test
    void controlsSendExactlyOneCommandAndCloseTheConnection() {
        runtime.devices.add(printer);
        for (String action : List.of("pause", "resume", "cancel")) {
            runtime.calls.clear();
            assertThat(run("printer", action, "--device", "Workshop")).isEqualTo(0);
            assertThat(runtime.calls).isEqualTo(List.of("connect", action.toUpperCase(Locale.ROOT), "close"));
        }
    }

    @Test
    void targetSelectionEmbedsHardwareAndClearRetainsItWithoutDeviceIdentity() {
        runtime.devices.add(printer);
        assertThat(run("slicer", "target", "set", "virtual.vxb", "--device", "Workshop"))
                .isEqualTo(0);
        assertThat(document.printSetup().workspaceSettings().get(PrintTarget.PRINTER_ID))
                .isEqualTo(printer.id());
        var hardware = document.printSetup().printerSnapshot();
        assertThat(hardware).isEqualTo(profile());
        assertThat(run("slicer", "target", "clear", "virtual.vxb")).isEqualTo(0);
        assertThat(document.printSetup().printerSnapshot()).isEqualTo(hardware);
        assertThat(document.printSetup().workspaceSettings().containsKey(PrintTarget.PRINTER_ID))
                .isFalse();
        int before = writes;
        assertThat(run("slicer", "target", "set", "virtual.vxb", "--device", "Workshop", "--profile", "profile-one"))
                .isEqualTo(1);
        assertThat(writes).isEqualTo(before);
    }

    private static final class FakeRuntime implements PrinterRuntime {
        final List<Printer> devices = new ArrayList<>();
        final List<String> calls = new ArrayList<>();
        PrinterStatus.State state = PrinterStatus.State.IDLE;
        boolean failUpload, failStart;
        Path temporary;
        byte[] uploaded;

        public List<Printer> devices(Path path) {
            return List.copyOf(devices);
        }

        public void save(Path path, Printer printer) {
            devices.removeIf(p -> p.id().equals(printer.id()));
            devices.add(printer);
        }

        public void remove(Path path, String id) {
            devices.removeIf(p -> p.id().equals(id));
        }

        public PrinterSession connect(Printer printer) {
            calls.add("connect");
            return new PrinterSession() {
                public PrinterStatus status() {
                    calls.add("status");
                    return new PrinterStatus(
                            state,
                            "",
                            OptionalDouble.empty(),
                            OptionalDouble.empty(),
                            OptionalDouble.empty(),
                            OptionalDouble.empty(),
                            "");
                }

                public Upload upload(Path file, DoubleConsumer progress) throws IOException {
                    calls.add("upload");
                    temporary = file;
                    uploaded = Files.readAllBytes(file);
                    if (failUpload) throw new IOException("Upload failed");
                    return new Upload("job.gcode", "checksum");
                }

                public void start(Upload upload) throws IOException {
                    calls.add("start");
                    if (failStart) throw new IOException("Start outcome unknown");
                }

                public void command(Command command) {
                    calls.add(command.name());
                }

                public void close() {
                    calls.add("close");
                }
            };
        }
    }
}
