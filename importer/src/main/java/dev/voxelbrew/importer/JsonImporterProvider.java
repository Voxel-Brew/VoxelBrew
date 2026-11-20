package dev.voxelbrew.importer;

import dev.voxelbrew.api.i18n.TranslationService;
import dev.voxelbrew.api.importer.*;
import dev.voxelbrew.util.services.Services;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

public final class JsonImporterProvider implements ImporterProvider {
    private final TranslationService translations;

    public JsonImporterProvider() {
        this(Services.load(TranslationService.class));
    }

    @SuppressFBWarnings(
            value = "EI_EXPOSE_REP2",
            justification = "The injected translation service is intentionally shared.")
    public JsonImporterProvider(@lombok.NonNull TranslationService translations) {
        this.translations = translations;
    }

    @Override
    public ImporterDescriptor descriptor() {
        return new ImporterDescriptor(
                "json",
                translations.translate("import.json.title").orElseThrow(),
                dev.voxelbrew.util.update.BuildVersion.current());
    }

    @Override
    public Importer create() {
        return new JsonImporter(translations);
    }
}
