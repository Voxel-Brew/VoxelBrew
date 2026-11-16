package dev.voxelbrew.cli;

import dev.voxelbrew.api.exporter.ExportDialog;
import dev.voxelbrew.api.exporter.ExportWindow;
import dev.voxelbrew.api.exporter.FileExportDialog;
import dev.voxelbrew.api.exporter.component.*;
import dev.voxelbrew.util.config.PluginDirectorySource;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Resolves exporter inputs from namespaced CLI arguments and optional terminal prompts. */
final class CliExportWindow implements ExportWindow, PluginDirectorySource {
    private final String exporterId;
    private final Path pluginDirectory;
    private final Map<String, String> supplied;
    private final String[] arguments;
    private final PrintWriter out;
    private final PrintWriter err;
    private final BufferedReader in;
    private final boolean promptForMissing;
    private final boolean failOnMissing;
    private final boolean meshFlagsProvided;
    private Map<String, String> values = Map.of();

    CliExportWindow(
            String exporterId,
            Path pluginDirectory,
            Map<String, String> supplied,
            String[] arguments,
            PrintWriter out,
            PrintWriter err,
            BufferedReader in,
            boolean promptForMissing,
            boolean failOnMissing,
            boolean meshFlagsProvided) {
        this.exporterId = Objects.requireNonNull(exporterId);
        this.pluginDirectory = Objects.requireNonNull(pluginDirectory);
        this.supplied = Map.copyOf(supplied);
        this.arguments = arguments.clone();
        this.out = Objects.requireNonNull(out);
        this.err = Objects.requireNonNull(err);
        this.in = Objects.requireNonNull(in);
        this.promptForMissing = promptForMissing;
        this.failOnMissing = failOnMissing;
        this.meshFlagsProvided = meshFlagsProvided;
    }

    Map<String, String> values() {
        return values;
    }

    @Override
    public Path pluginDirectory() {
        return pluginDirectory;
    }

    @Override
    public <R> Optional<R> show(ExportDialog<R> dialog) {
        if (meshFlagsProvided && dialog instanceof FileExportDialog)
            throw new IllegalArgumentException("This exporter requires the complete design without mesh options");
        var inputs = new LinkedHashMap<String, ExportDialogInput>();
        collect(dialog.layout(), inputs);
        var resolved = new LinkedHashMap<>(supplied);
        var explicit = new HashSet<>(supplied.keySet());
        var namespaced = new HashSet<String>();
        String prefix = "--export." + exporterId + ".";
        for (int index = 0; index < arguments.length; index++) {
            String argument = arguments[index];
            if (!argument.startsWith(prefix)) throw new IllegalArgumentException("Unknown export input: " + argument);
            int separator = argument.indexOf('=');
            String id = argument.substring(prefix.length(), separator < 0 ? argument.length() : separator);
            if (!inputs.containsKey(id)) throw new IllegalArgumentException("Unknown export input: " + id);
            if (!namespaced.add(id)) throw new IllegalArgumentException("Duplicate export input: " + id);
            if (separator < 0 && index + 1 >= arguments.length)
                throw new IllegalArgumentException("Missing value for export input: " + id);
            String value = separator < 0 ? arguments[++index] : argument.substring(separator + 1);
            resolved.put(id, value);
            explicit.add(id);
        }
        for (var input : inputs.values())
            if (!resolved.containsKey(input.id()) && defaultValue(input) != null)
                resolved.put(input.id(), defaultValue(input));
        var initialErrors = dialog.validate(resolved);
        boolean invalidExplicit = initialErrors.keySet().stream().anyMatch(explicit::contains);
        if (invalidExplicit || failOnMissing || !promptForMissing) {
            var failure = validationFailure(inputs, resolved, initialErrors, prefix);
            if (failure != null) throw failure;
        }
        if (promptForMissing) {
            for (var input : inputs.values()) {
                if (explicit.contains(input.id())) continue;
                if (!prompt(dialog, input, resolved)) return Optional.empty();
            }
            while (true) {
                var errors = dialog.validate(resolved);
                if (errors.isEmpty()) break;
                if (errors.keySet().stream().anyMatch(id -> explicit.contains(id) || !inputs.containsKey(id)))
                    throw validationFailure(inputs, resolved, errors, prefix);
                for (var input : inputs.values()) {
                    var message = errors.get(input.id());
                    if (message == null) continue;
                    err.println(label(input) + "[" + input.id() + "]: " + message);
                    if (!prompt(dialog, input, resolved)) return Optional.empty();
                }
            }
        }
        values = Map.copyOf(resolved);
        return Optional.of(dialog.buildSettings(values));
    }

    private <R> boolean prompt(ExportDialog<R> dialog, ExportDialogInput input, Map<String, String> resolved) {
        String fallback = defaultValue(input);
        while (true) {
            out.print(label(input) + "[" + input.id() + "]: ");
            out.flush();
            String answer;
            try {
                answer = in.readLine();
            } catch (IOException failure) {
                throw new java.io.UncheckedIOException(failure);
            }
            if (answer == null) return false;
            resolved.put(input.id(), answer.isEmpty() && fallback != null ? fallback : answer);
            String message = dialog.validate(resolved).get(input.id());
            if (message == null && answer.isEmpty() && fallback == null) message = "A value is required";
            if (message == null) return true;
            err.println(label(input) + "[" + input.id() + "]: " + message);
        }
    }

    private static IllegalArgumentException validationFailure(
            Map<String, ExportDialogInput> inputs,
            Map<String, String> resolved,
            Map<String, String> errors,
            String prefix) {
        var messages = new ArrayList<String>();
        boolean invalid = false;
        for (var input : inputs.values()) {
            String name = prefix + input.id() + " (" + label(input) + ")";
            if (errors.containsKey(input.id())) {
                messages.add(name + ": " + errors.get(input.id()));
                invalid = true;
            } else if (!resolved.containsKey(input.id())) {
                messages.add(name);
            }
        }
        for (var error : errors.entrySet()) {
            if (!inputs.containsKey(error.getKey())) {
                messages.add(prefix + error.getKey() + ": " + error.getValue());
                invalid = true;
            }
        }
        if (messages.isEmpty()) return null;
        return new IllegalArgumentException(
                (invalid ? "Invalid export inputs: " : "Missing export inputs: ") + String.join(", ", messages));
    }

    private static void collect(ExportDialogComponent component, Map<String, ExportDialogInput> inputs) {
        if (component instanceof ExportDialogInput input) {
            if (inputs.putIfAbsent(input.id(), input) != null)
                throw new IllegalArgumentException("Duplicate export input: " + input.id());
        } else if (component instanceof HorizontalLayout horizontal) {
            horizontal.children().forEach(child -> collect(child, inputs));
        } else if (component instanceof VerticalLayout vertical) {
            vertical.children().forEach(child -> collect(child, inputs));
        }
    }

    private static String label(ExportDialogInput input) {
        if (input instanceof TextField field) return field.label();
        if (input instanceof TextArea area) return area.label();
        if (input instanceof CheckBox box) return box.label();
        if (input instanceof ComboBox box) return box.label();
        if (input instanceof RadioButtonGroup group) return group.label();
        if (input instanceof Slider slider) return slider.label();
        return "Destination";
    }

    private static String defaultValue(ExportDialogInput input) {
        if (input instanceof TextField field) return field.value();
        if (input instanceof TextArea area) return area.value();
        if (input instanceof CheckBox box) return Boolean.toString(box.selected());
        if (input instanceof ComboBox box) return box.selected();
        if (input instanceof RadioButtonGroup group) return group.selected();
        if (input instanceof Slider slider) return Double.toString(slider.value());
        if (input instanceof FileDestination destination && destination.defaultPath() != null)
            return destination.defaultPath().toString();
        return null;
    }
}
