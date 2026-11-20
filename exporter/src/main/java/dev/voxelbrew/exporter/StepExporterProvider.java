package dev.voxelbrew.exporter;

import dev.voxelbrew.api.exporter.*;
import dev.voxelbrew.api.i18n.TranslationService;
import dev.voxelbrew.util.services.Services;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

public final class StepExporterProvider implements ExporterProvider<FileExportDialog.Destination> {
    private final TranslationService translations;

    public StepExporterProvider() {
        this(Services.load(TranslationService.class));
    }

    @SuppressFBWarnings(
            value = "EI_EXPOSE_REP2",
            justification = "The injected translation service is intentionally shared.")
    public StepExporterProvider(@lombok.NonNull TranslationService translations) {
        this.translations = translations;
    }

    @Override
    public ExporterDescriptor descriptor() {
        return new ExporterDescriptor(
                "step",
                translations.translate("export.step.name").orElseThrow(),
                dev.voxelbrew.util.update.BuildVersion.current());
    }

    @Override
    public Exporter<FileExportDialog.Destination> create() {
        return new StepExporter(translations);
    }
}
