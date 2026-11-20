package dev.voxelbrew.cli;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.model.CadDocument;
import dev.voxelbrew.api.model.printing.*;
import dev.voxelbrew.api.model.vui.*;
import dev.voxelbrew.api.printer.*;
import dev.voxelbrew.api.printer.driver.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class DeviceCommandsTest {
    final List<Printer> saved = new ArrayList<>();

    @TempDir
    Path directory;

    String output;
    int statusCalls;
    DriverStatus printerStatus = DriverStatus.unknown("offline");

    static Profile profile() {
        var p = PrintProfile.resolve(PrintSetup.empty()).printer();
        return new Profile(
                p.id(),
                p.name(),
                "Generic",
                p.bed(),
                p.excludedZones(),
                p.usableHeight(),
                p.nozzleMm(),
                1.75,
                1,
                p.firmware(),
                p.outputFormat(),
                true,
                null);
    }

    int run(String... args) {
        var root = new CommandLine(CommandLine.Model.CommandSpec.create().name("test"));
        var store = new PrinterStore() {
            @Override
            public Snapshot load() {
                return new Snapshot(saved, List.of());
            }

            @Override
            public void save(Printer p) {
                saved.removeIf(old -> old.id().equals(p.id()));
                saved.add(p);
            }

            @Override
            public void remove(String id) {
                saved.removeIf(p -> p.id().equals(id));
            }
        };
        var driver = new Driver() {
            @Override
            public String id() {
                return "external";
            }

            @Override
            public String name() {
                return "External";
            }

            @Override
            public DriverStatus readPrinterStatus(DriverConfiguration c) {
                statusCalls++;
                return printerStatus;
            }

            @Override
            public PrintResult print(DriverConfiguration c, PrintArtifact a) {
                return new PrintResult.Failure(Set.of("Rejected by driver"));
            }

            @Override
            public DriverGui gui() {
                return new DriverGui() {
                    @Override
                    public Layout layout() {
                        return new VerticalLayout(new PasswordField("token", "Token", ""));
                    }

                    @Override
                    public Map<String, String> validate(Map<String, String> values) {
                        return values.getOrDefault("token", "").isBlank()
                                ? Map.of("token", "Token required")
                                : Map.of();
                    }

                    @Override
                    public DriverConfiguration buildConfiguration(Map<String, String> values) {
                        return new DriverConfiguration(
                                Map.of("token", values.get("token").toCharArray()));
                    }
                };
            }
        };
        var catalog = new PrinterProfileCatalog() {
            @Override
            public Path directory() {
                return directory;
            }

            @Override
            public Result load() {
                return new Result(List.of(new Entry(profile(), null, "fixture", true)), List.of());
            }

            @Override
            public int exportDefaults(Set<String> ids) {
                return 0;
            }

            @Override
            public String fallbackImage() {
                return null;
            }

            @Override
            public Profile save(Profile p, Path image) {
                throw new UnsupportedOperationException();
            }
        };
        new DeviceCommands(
                        store,
                        () -> new DriverRegistry() {
                            @Override
                            public DriverRegistry withDirectory(Path plugins) {
                                return this;
                            }

                            @Override
                            @lombok.NonNull
                            public List<Driver> drivers() {
                                return List.of(driver);
                            }

                            @Override
                            @lombok.NonNull
                            public Optional<Driver> find(@lombok.NonNull String id) {
                                return id.equals(driver.id()) ? Optional.of(driver) : Optional.empty();
                            }

                            @Override
                            public void close() {}
                        },
                        file -> {
                            var doc = CadDocument.empty();
                            var lines = List.of("G1 X1");
                            return doc.withGcode(Map.of("plate-1", lines))
                                    .withPrintArtifacts(Map.of(
                                            "plate-1",
                                            PrintTarget.attach(
                                                    null,
                                                    lines,
                                                    doc.printSetup()
                                                            .withPrintTarget(
                                                                    saved.isEmpty()
                                                                            ? "id"
                                                                            : saved.getFirst()
                                                                                    .id()))));
                        },
                        (d, file, overwrite) -> {},
                        catalog)
                .install(root);
        var text = new StringWriter();
        root.setOut(new PrintWriter(text));
        root.setErr(new PrintWriter(text));
        root.setExecutionExceptionHandler((e, c, p) -> {
            c.getErr().println(e.getMessage());
            return 1;
        });
        int result = root.execute(args);
        output = text.toString();
        return result;
    }

    @Test
    void savesTypedSecretsAndEditsWithoutVendorKnowledge() throws Exception {
        var secret = directory.resolve("token");
        Files.writeString(secret, "sensitive\n");
        assertThat(run(
                        "printer",
                        "device",
                        "add",
                        "--device",
                        "Lab",
                        "--driver",
                        "external",
                        "--profile",
                        profile().id(),
                        "--setting-file",
                        "token=" + secret))
                .isZero();
        assertThat(saved.getFirst().driverConfiguration().requirePasswordProperty("token"))
                .isEqualTo("sensitive".toCharArray());
        var id = saved.getFirst().id();
        assertThat(run("printer", "device", "set", "--device", "Lab", "--name", "Renamed"))
                .isZero();
        assertThat(saved.getFirst().id()).isEqualTo(id);
        assertThat(run("printer", "device", "show", "--device", "Renamed")).isZero();
        assertThat(output).doesNotContain("sensitive");
        assertThat(run("printer", "status", "--device", "Renamed")).isZero();
        assertThat(statusCalls).isEqualTo(1);
        assertThat(run("printer", "print", "unused.vxb", "--device", "Renamed")).isEqualTo(1);
        assertThat(output).contains("Rejected by driver");
    }

    @Test
    void failedMonitoringReturnsNonzeroForStatusWatchAndConnectionTest() {
        saved.add(new Printer("id", "Lab", "external", new DriverConfiguration(Map.of()), profile()));
        printerStatus = new DriverStatus(
                DriverStatus.State.ERROR,
                "",
                OptionalDouble.empty(),
                OptionalDouble.empty(),
                OptionalDouble.empty(),
                OptionalDouble.empty(),
                "Cannot read printer status: connection failed");
        for (var arguments : List.of(
                new String[] {"printer", "status", "--device", "Lab"},
                new String[] {"printer", "status", "--device", "Lab", "--watch"},
                new String[] {"printer", "device", "test", "--device", "Lab"})) {
            assertThat(run(arguments)).isEqualTo(1);
            assertThat(output).contains("connection failed");
        }
    }

    @Test
    void invalidSettingsDoNotPersist() {
        assertThat(run(
                        "printer",
                        "device",
                        "add",
                        "--device",
                        "Lab",
                        "--driver",
                        "external",
                        "--profile",
                        profile().id()))
                .isEqualTo(1);
        assertThat(saved).isEmpty();
    }

    @Test
    void missingDriverHasActionableError() {
        saved.add(new Printer("id", "Lab", "missing", new DriverConfiguration(Map.of()), profile()));
        for (var arguments : List.of(
                new String[] {"printer", "status", "--device", "Lab"},
                new String[] {"printer", "print", "unused.vxb", "--device", "Lab"},
                new String[] {"printer", "device", "test", "--device", "Lab"},
                new String[] {"printer", "device", "set", "--device", "Lab", "--name", "Renamed"},
                new String[] {
                    "printer",
                    "device",
                    "add",
                    "--device",
                    "New",
                    "--driver",
                    "missing",
                    "--profile",
                    profile().id()
                })) {
            assertThat(run(arguments)).as("%s", String.join(" ", arguments)).isEqualTo(1);
            assertThat(output).contains("Missing printer driver missing", "Install its plugin", "--driver");
        }
        assertThat(statusCalls).isZero();
        assertThat(saved).hasSize(1);
        assertThat(saved.getFirst().name()).isEqualTo("Lab");
    }
}
