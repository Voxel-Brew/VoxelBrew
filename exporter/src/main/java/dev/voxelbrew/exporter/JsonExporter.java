package dev.voxelbrew.exporter;

import dev.voxelbrew.api.i18n.TranslationService;
import dev.voxelbrew.util.services.Services;

/** Human-readable export of the complete persisted document schema. */
public final class JsonExporter extends FileExporter {
    public JsonExporter() {
        this(Services.load(TranslationService.class));
    }

    public JsonExporter(@lombok.NonNull TranslationService translations) {
        super(
                "json",
                translations.translate("export.json.title").orElseThrow(),
                (document, path, overwrite, window) -> JsonDocumentWriter.save(document, path, overwrite));
    }
}
