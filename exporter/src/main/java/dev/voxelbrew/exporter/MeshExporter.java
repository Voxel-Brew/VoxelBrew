package dev.voxelbrew.exporter;

import dev.voxelbrew.api.exporter.*;
import dev.voxelbrew.engine.mesh.MeshOptions;
import dev.voxelbrew.engine.model.*;
import dev.voxelbrew.engine.persistence.DocumentCodec;
import dev.voxelbrew.util.config.PluginDirectorySource;
import java.io.*;
import java.nio.file.Path;
import java.util.*;

/** Implementation helper for built-in mesh formats; all entry points use Exporter. */
public abstract class MeshExporter implements Exporter<MeshExportDialog.Settings> {
    private final Path plugins;

    protected MeshExporter() {
        this(dev.voxelbrew.util.config.ApplicationPaths.plugins());
    }

    protected MeshExporter(Path plugins) {
        this.plugins = Objects.requireNonNull(plugins);
    }

    public abstract MeshFormat format();

    public abstract void write(ModelView view, MeshOptions mesh, Map<String, String> options, OutputStream output)
            throws IOException;

    @Override
    public final Optional<MeshExportDialog.Settings> export(CadDocument document, ExportWindow window)
            throws IOException {
        var requested = window.show(new MeshExportDialog(format()));
        if (requested.isEmpty()) return Optional.empty();
        publish(
                document,
                requested.get(),
                window instanceof PluginDirectorySource source ? source.pluginDirectory() : plugins);
        return requested;
    }

    private void publish(CadDocument document, MeshExportDialog.Settings requested, Path pluginDirectory)
            throws IOException {
        // 3MF is a plate handoff whenever the user has prepared a slicer plate.
        var selection = requested.selection();
        boolean preparedPlate = document.printSetup().plates().stream()
                .anyMatch(p -> !p.instances().isEmpty());
        if (format().extensions().contains("3mf") && preparedPlate)
            selection = new ExportSelection(
                    ExportSelection.Space.BUILD_PLATE,
                    selection.contents(),
                    selection.space() == ExportSelection.Space.DESIGN ? null : selection.body(),
                    selection.instance());
        var settings = new MeshExportDialog.Settings(
                selection, requested.mesh(), requested.options(), requested.destination(), requested.snapshot());
        if (settings.snapshot() != null) {
            var model = settings.snapshot();
            if (!document.id().equals(model.documentId())
                    || !DocumentCodec.fingerprint(document).equals(model.revision()))
                throw new IllegalArgumentException("Manufacturing snapshot does not match the document");
            if (settings.selection().space() != ExportSelection.Space.BUILD_PLATE)
                throw new IllegalArgumentException("Manufacturing snapshots require BUILD_PLATE space");
            var view = ModelViews.from(model, settings.selection().contents());
            if (settings.selection().body() != null)
                view = ModelViews.select(view, settings.selection().body());
            if (settings.selection().instance() != null)
                view = ModelViews.selectInstance(view, settings.selection().instance());
            writeFile(view, settings, document.printSetup());
        } else {
            try (var evaluation = Evaluator.evaluate(document, pluginDirectory)) {
                writeFile(
                        ExportSelections.view(settings.selection(), document, evaluation, settings.mesh()),
                        settings,
                        document.printSetup());
            }
        }
    }

    /** Serialization helper for format-level tests; application callers use export(). */
    public final void writeFile(ModelView view, MeshExportDialog.Settings settings) throws IOException {
        writeFile(
                view,
                settings,
                view instanceof ManufacturingView manufacturing
                        ? manufacturing.manufacturingModel().setup()
                        : null);
    }

    protected void write(
            ModelView view,
            MeshOptions mesh,
            Map<String, String> options,
            OutputStream output,
            dev.voxelbrew.engine.printing.PrintSetup setup)
            throws IOException {
        write(view, mesh, options, output);
    }

    private void writeFile(
            ModelView view, MeshExportDialog.Settings settings, dev.voxelbrew.engine.printing.PrintSetup setup)
            throws IOException {
        if (view.instances().isEmpty()) throw new IllegalArgumentException("Nothing to export");
        if (!format().multipleInstances() && view.instances().size() != 1)
            throw new IllegalArgumentException("Exporter requires one selected body/instance");
        var values = new LinkedHashMap<String, String>();
        for (var option : format().options()) values.put(option.key(), option.defaultValue());
        for (var option : settings.options().entrySet()) {
            var spec = format().options().stream()
                    .filter(o -> o.key().equals(option.getKey()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown option '" + option.getKey() + "'"));
            spec.validate(option.getValue());
            values.put(option.getKey(), option.getValue());
        }
        AtomicExport.write(
                settings.destination(), stream -> write(view, settings.mesh(), Map.copyOf(values), stream, setup));
    }
}
