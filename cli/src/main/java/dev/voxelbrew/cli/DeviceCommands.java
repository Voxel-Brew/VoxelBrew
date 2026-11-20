package dev.voxelbrew.cli;

import static dev.voxelbrew.cli.WorkspaceEdits.*;

import dev.voxelbrew.api.model.printing.PrintArtifact;
import dev.voxelbrew.api.printer.*;
import dev.voxelbrew.api.printer.driver.*;
import dev.voxelbrew.importer.DocumentSource;
import dev.voxelbrew.printer.ProfileHardware;
import dev.voxelbrew.util.config.ApplicationPaths;
import java.nio.file.*;
import java.util.*;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;

@lombok.RequiredArgsConstructor
final class DeviceCommands {
    private static final Object[] OPERATION_LOCKS = new Object[64];

    static {
        Arrays.setAll(OPERATION_LOCKS, index -> new Object());
    }

    private final PrinterStore runtime;

    private final java.util.function.Supplier<DriverRegistry> driverRegistries;

    private final DocumentSource documents;

    private final DocumentSave writer;
    private final dev.voxelbrew.api.printer.PrinterProfileCatalog printers;

    private static Object operationLock(Printer printer) {
        var endpoints = printer.driverConfiguration().properties().values().stream()
                .filter(java.net.URI.class::isInstance)
                .map(Object::toString)
                .sorted()
                .toList();
        return OPERATION_LOCKS[
                Math.floorMod(
                        Objects.hash(printer.driverId(), endpoints.isEmpty() ? printer.id() : endpoints),
                        OPERATION_LOCKS.length)];
    }

    static DriverStatus readStatus(Driver driver, Printer printer) throws Exception {
        synchronized (operationLock(printer)) {
            return driver.readPrinterStatus(printer.driverConfiguration());
        }
    }

    static Driver.PrintResult submitArtifact(Driver driver, Printer printer, PrintArtifact artifact) throws Exception {
        var errors = printer.validateArtifact(artifact);
        if (!errors.isEmpty()) return new Driver.PrintResult.Failure(errors);
        synchronized (operationLock(printer)) {
            return driver.print(printer.driverConfiguration(), artifact);
        }
    }

    private static void applySettings(Map<String, String> settings, String[] entries, boolean files)
            throws java.io.IOException {
        if (entries == null) return;
        for (var entry : entries) {
            int equal = entry.indexOf('=');
            if (equal <= 0) throw new IllegalArgumentException("Settings must use key=value");
            String key = entry.substring(0, equal), value = entry.substring(equal + 1);
            settings.put(key, files ? Files.readString(Path.of(value)).stripTrailing() : value);
        }
    }

    private static <T> T fallback(CommandSpec s, String name, T fallback) {
        T value = value(s, name);
        return value == null ? fallback : value;
    }

    void install(CommandLine root) {
        for (String action : List.of("show", "set", "clear")) {
            var target = edit(root, "slicer target " + action, s -> {
                Path file = s.positionalParameters().getFirst().getValue();
                var document = documents.load(file);
                var setup = document.printSetup();
                if (action.equals("show")) {
                    String id = setup.workspaceSettings()
                            .getOrDefault(dev.voxelbrew.api.model.printing.PrintTarget.PRINTER_ID, "");
                    String name = runtime.load().devices().stream()
                            .filter(p -> p.id().equals(id))
                            .map(Printer::name)
                            .findFirst()
                            .orElse(id.isBlank() ? "No device" : "Unavailable device");
                    s.commandLine().getOut().println(name + "\tprofile=" + setup.printerId());
                    return 0;
                }
                if (action.equals("clear")) setup = setup.withPrintTarget(null);
                else {
                    String name = value(s, "--device"), profile = value(s, "--profile");
                    if ((name == null) == (profile == null))
                        throw new IllegalArgumentException("Choose --device or --profile");
                    if (name != null) {
                        var device = find(name);
                        setup = setup.withPrinter(ProfileHardware.resolve(device.profile()))
                                .withPrintTarget(device.id());
                    } else
                        setup = setup.withPrintTarget(null)
                                .withPrinter(
                                        ProfileHardware.resolve(printers.load().require(profile)));
                }
                var session = new dev.voxelbrew.engine.model.ModelSession(document);
                session.setPrintSetup(setup);
                writer.save(session.document(), file, true);
                return 0;
            });
            if (action.equals("set")) {
                option(target, "--device", String.class, false, null, "Saved printer name");
                option(target, "--profile", String.class, false, null, "Standalone printer profile ID");
            }
        }
        for (String action : List.of("list", "show", "add", "set", "remove", "test")) {
            var spec = command(root, "printer device " + action, s -> device(s, action), false);
            if (!action.equals("list")) option(spec, "--device", String.class, true, null, "Saved printer name");
            if (action.equals("add") || action.equals("set")) {
                option(spec, "--name", String.class, false, null, "New display name");
                option(spec, "--driver", String.class, action.equals("add"), null, "Installed printer driver ID");
                option(spec, "--profile", String.class, action.equals("add"), null, "Printer profile ID");
                option(spec, "--setting", String[].class, false, null, "Driver setting key=value (repeatable)");
                option(
                        spec,
                        "--setting-file",
                        String[].class,
                        false,
                        null,
                        "Driver setting key=path; UTF-8 file (repeatable)");
            }
        }
        for (String action : List.of("status", "print")) {
            boolean send = action.equals("print");
            var spec = command(root, "printer " + action, s -> control(s, action), send);
            option(spec, "--device", String.class, true, null, "Saved printer name");
            if (send) option(spec, "--plate", String.class, false, null, "Plate ID; defaults to active plate");
            if (action.equals("status"))
                option(spec, "--watch", boolean.class, false, "false", "Poll status until interrupted");
        }
    }

    private Printer find(String name) throws Exception {
        return runtime.load().devices().stream()
                .filter(p -> p.name().equalsIgnoreCase(name.strip()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown printer: " + name));
    }

    private int device(CommandSpec s, String action) throws Exception {
        if (action.equals("list")) {
            snapshot(s)
                    .devices()
                    .forEach(p -> s.commandLine()
                            .getOut()
                            .println(p.name() + "\t" + p.driverId() + "\t"
                                    + p.profile().id()));
            return 0;
        }
        Printer old = action.equals("add") ? null : find(value(s, "--device"));
        switch (action) {
            case "show" ->
                s.commandLine()
                        .getOut()
                        .println(old.name() + "\t" + old.driverId() + "\t"
                                + old.profile().id());
            case "remove" -> runtime.remove(old.id());
            case "test" -> {
                try (var registry = registry()) {
                    var driver = registry.find(old.driverId())
                            .orElseThrow(() -> new IllegalArgumentException("Missing printer driver " + old.driverId()
                                    + ". Install its plugin or change the printer's --driver setting."));
                    var status = readStatus(driver, old);
                    s.commandLine().getOut().println(status);
                    if (status.state() == DriverStatus.State.ERROR) return 1;
                }
            }
            case "add", "set" -> {
                String driverId = fallback(s, "--driver", old == null ? null : old.driverId());
                try (var registry = registry()) {
                    var driver = registry.find(driverId)
                            .orElseThrow(() -> new IllegalArgumentException("Missing printer driver " + driverId
                                    + ". Install its plugin or change the printer's --driver setting."));
                    var settings = new LinkedHashMap<String, String>();
                    if (old != null && old.driverId().equals(driverId))
                        settings.putAll(old.driverConfiguration().toInput());
                    applySettings(settings, value(s, "--setting"), false);
                    applySettings(settings, value(s, "--setting-file"), true);
                    var errors = driver.gui().validate(settings);
                    if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("; ", errors.values()));
                    String profileId = fallback(
                            s, "--profile", old == null ? null : old.profile().id());
                    var profile = old != null && value(s, "--profile") == null
                            ? old.profile()
                            : printers.load().require(profileId);
                    var next = new Printer(
                            old == null ? UUID.randomUUID().toString() : old.id(),
                            fallback(s, "--name", old == null ? value(s, "--device") : old.name()),
                            driverId,
                            driver.gui().buildConfiguration(settings),
                            profile);
                    runtime.save(next);
                }
            }
            default -> throw new IllegalArgumentException(action);
        }
        return 0;
    }

    private PrinterStore.Snapshot snapshot(CommandSpec spec) throws Exception {
        var snapshot = runtime.load();
        snapshot.diagnostics().forEach(spec.commandLine().getErr()::println);
        return snapshot;
    }

    private DriverRegistry registry() throws java.io.IOException {
        var registry = driverRegistries.get();
        try {
            return registry.withDirectory(dev.voxelbrew.util.services.Services.load(ApplicationPaths.class)
                    .plugins());
        } catch (java.io.IOException | RuntimeException failure) {
            try {
                registry.close();
            } catch (java.io.IOException close) {
                failure.addSuppressed(close);
            }
            throw failure;
        }
    }

    private int control(CommandSpec s, String action) throws Exception {
        var device = find(value(s, "--device"));
        try (var registry = registry()) {
            var driver = registry.find(device.driverId())
                    .orElseThrow(() -> new IllegalArgumentException("Missing printer driver " + device.driverId()
                            + ". Install its plugin or change the printer's --driver setting."));
            if (action.equals("print")) {
                Path file = s.positionalParameters().getFirst().getValue();
                var document = documents.load(file);
                String plate = fallback(s, "--plate", document.printSetup().activePlateId());
                var artifact = document.printArtifacts().get(plate);
                var result = submitArtifact(driver, device, artifact);
                if (result instanceof Driver.PrintResult.Failure failure) {
                    failure.messages().forEach(s.commandLine().getErr()::println);
                    return 1;
                }
                s.commandLine().getOut().println("Print start submitted");
            } else {
                do {
                    var status = readStatus(driver, device);
                    s.commandLine().getOut().println(status);
                    s.commandLine().getOut().flush();
                    if (status.state() == DriverStatus.State.ERROR) return 1;
                    if (!(Boolean) value(s, "--watch")) break;
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        return 130;
                    }
                } while (!Thread.currentThread().isInterrupted());
            }
        }
        return 0;
    }
}
