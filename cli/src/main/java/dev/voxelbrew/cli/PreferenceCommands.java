package dev.voxelbrew.cli;

import static dev.voxelbrew.cli.WorkspaceEdits.*;

import dev.voxelbrew.api.printer.PrinterProfileCatalog;
import dev.voxelbrew.api.slicer.*;
import dev.voxelbrew.api.userconfig.UserConfigService;
import dev.voxelbrew.slicer.SlicerInstallationStore;
import dev.voxelbrew.util.preferences.*;
import java.nio.file.Path;
import java.util.*;
import picocli.CommandLine;

@lombok.RequiredArgsConstructor
final class PreferenceCommands {
    private final UserConfigService configuration;
    private final PrinterProfileCatalog printers;
    private final java.util.function.Supplier<SlicerProviderRegistry> slicers;

    void install(CommandLine root) {
        for (String action : List.of("show", "set", "reset")) {
            var spec = command(
                    root,
                    "settings " + action,
                    s -> {
                        if (action.equals("show"))
                            s.commandLine().getOut().println(new TreeMap<>(configuration.readUserConfig()));
                        else if (action.equals("set"))
                            configuration.saveUserConfig(value(s, "--key"), value(s, "--value"));
                        else configuration.remove(value(s, "--key"));
                        return 0;
                    },
                    false);
            if (!action.equals("show"))
                option(
                        spec,
                        "--key",
                        String.class,
                        true,
                        null,
                        "Preference key (language, appearance, rendering.style)");
            if (action.equals("set")) option(spec, "--value", String.class, true, null, "Preference value");
        }
        command(
                root,
                "slicer profile path",
                s -> {
                    s.commandLine().getOut().println(printers.directory());
                    return 0;
                },
                false);
        var export = command(
                root,
                "slicer profile export",
                s -> {
                    s.commandLine()
                            .getOut()
                            .println("Exported " + printers.exportDefaults(Set.of((String[]) value(s, "--id")))
                                    + " profiles");
                    return 0;
                },
                false);
        option(export, "--id", String[].class, true, null, "Repeat for selected bundled profile IDs");
        for (String action : List.of("show", "reload")) {
            var spec = command(
                    root,
                    "slicer profile " + action,
                    s -> {
                        var catalog = printers.load();
                        catalog.diagnostics().forEach(s.commandLine().getErr()::println);
                        if (action.equals("show")) s.commandLine().getOut().println(catalog.require(value(s, "--id")));
                        else catalog.entries().forEach(s.commandLine().getOut()::println);
                        return 0;
                    },
                    false);
            if (action.equals("show")) option(spec, "--id", String.class, true, null, "Profile ID");
        }
        var visibility = command(
                root,
                "slicer profile visibility",
                s -> {
                    String operation = value(s, "--operation");
                    var hidden = new TreeSet<>(Arrays.asList(configuration
                            .readUserConfig("printers.hiddenDefaults")
                            .orElse("")
                            .split(",")));
                    hidden.remove("");
                    var defaults = printers.load().entries().stream()
                            .filter(PrinterProfileCatalog.Entry::bundled)
                            .map(e -> e.definition().printer().id())
                            .collect(java.util.stream.Collectors.toSet());
                    switch (operation) {
                        case "show" -> {
                            s.commandLine().getOut().println("hidden=" + hidden);
                            return 0;
                        }
                        case "select-all" -> hidden.removeAll(defaults);
                        case "deselect-all" -> hidden.addAll(defaults);
                        case "invert" ->
                            defaults.forEach(id -> {
                                if (!hidden.remove(id)) hidden.add(id);
                            });
                        case "hide", "unhide" -> {
                            String id = value(s, "--id");
                            if (!defaults.contains(id))
                                throw new IllegalArgumentException("Expected a bundled profile ID");
                            if (operation.equals("hide")) hidden.add(id);
                            else hidden.remove(id);
                        }
                        default -> throw new IllegalArgumentException("Unknown visibility operation");
                    }
                    configuration.saveUserConfig("printers.hiddenDefaults", String.join(",", hidden));
                    return 0;
                },
                false);
        option(
                visibility,
                "--operation",
                String.class,
                false,
                "show",
                "show, hide, unhide, select-all, deselect-all, invert");
        option(visibility, "--id", String.class, false, null, "Bundled profile ID for hide/unhide");
        for (String action : List.of("describe", "configure", "discover")) {
            var spec = command(
                    root,
                    "slicer backend " + action,
                    s -> {
                        try (var catalog = slicers.get()) {
                            catalog.withDirectory(value(s, "--plugin-dir"));
                            String id = value(s, "--id");
                            var backend = catalog.require(id);
                            if (action.equals("discover")) {
                                backend.discoverExecutables(new dev.voxelbrew.api.model.printing.Cancellation())
                                        .forEach(s.commandLine().getOut()::println);
                                return 0;
                            }
                            var store = new SlicerInstallationStore(configuration);
                            if (action.equals("configure")) {
                                var overrides = new LinkedHashMap<String, String>();
                                for (String setting : (String[]) value(s, "--option")) {
                                    int equals = setting.indexOf('=');
                                    if (equals < 1)
                                        throw new IllegalArgumentException("Unknown provider option: " + setting);
                                    overrides.put(setting.substring(0, equals), setting.substring(equals + 1));
                                }
                                var dialog = backend.settingsDialog()
                                        .orElseThrow(() -> new IllegalArgumentException(
                                                "Slicer has no persistent settings: " + id));
                                new CliSlicerWindow(store, id, overrides).show(dialog);
                            }
                            s.commandLine().getOut().println(backend.descriptor());
                            store.providerDefaults(id)
                                    .forEach((key, val) ->
                                            s.commandLine().getOut().println(key + "=" + val));
                        }
                        return 0;
                    },
                    false);
            option(spec, "--id", String.class, true, null, "Backend ID");
            option(
                    spec,
                    "--plugin-dir",
                    Path.class,
                    false,
                    dev.voxelbrew.util.services.Services.load(dev.voxelbrew.api.userconfig.UserConfigService.class)
                            .pluginsFolder()
                            .toString(),
                    "Trusted plugins directory");
            if (action.equals("configure"))
                option(spec, "--option", String[].class, true, null, "Repeat provider key=value");
        }
    }
}
