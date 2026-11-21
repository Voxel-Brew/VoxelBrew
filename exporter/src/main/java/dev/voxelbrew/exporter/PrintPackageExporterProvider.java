package dev.voxelbrew.exporter;

import dev.voxelbrew.api.exporter.*;
import dev.voxelbrew.api.i18n.TranslationService;
import dev.voxelbrew.util.services.Services;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

public final class PrintPackageExporterProvider implements ExporterProvider<FileExportDialog.Destination> {
    private final TranslationService translations;

    public PrintPackageExporterProvider() {
        this(Services.load(TranslationService.class));
    }

    @SuppressFBWarnings(
            value = "EI_EXPOSE_REP2",
            justification = "The injected translation service is intentionally shared.")
    public PrintPackageExporterProvider(@lombok.NonNull TranslationService translations) {
        this.translations = translations;
    }

    @Override
    public ExporterDescriptor descriptor() {
        return new ExporterDescriptor(
                "gcode-package",
                translations.translate("export.printpackage.name"),
                dev.voxelbrew.util.update.BuildVersion.current());
    }

    @Override
    public Exporter<FileExportDialog.Destination> create() {
        return new FileExporter(
                "gcode.3mf", translations.translate("export.package.title"), (document, path, overwrite, window) -> {
                    var artifact =
                            document.printArtifacts().get(document.printSetup().activePlateId());
                    if (artifact == null || !artifact.format().equals("gcode.3mf")) {
                        throw new java.io.IOException(translations.translate("export.package.missing"));
                    }
                    AtomicExport.write(
                            new FileExportDialog.Destination(path, overwrite),
                            output -> output.write(artifact.bytes()));
                }) {};
    }
}
