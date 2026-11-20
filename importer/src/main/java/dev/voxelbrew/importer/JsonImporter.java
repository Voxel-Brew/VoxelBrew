package dev.voxelbrew.importer;

import dev.voxelbrew.api.i18n.TranslationService;
import dev.voxelbrew.api.importer.*;
import dev.voxelbrew.util.services.Services;
import java.io.*;

public final class JsonImporter extends FileImporter {
    public JsonImporter() {
        this(Services.load(TranslationService.class));
    }

    public JsonImporter(@lombok.NonNull TranslationService translations) {
        super(translations.translate("import.json.title").orElseThrow(), "json");
    }

    @Override
    protected dev.voxelbrew.api.model.CadDocument read(InputStream input) throws IOException {
        return dev.voxelbrew.engine.persistence.DocumentCodec.decodeJson(java.nio.charset.StandardCharsets.UTF_8
                .newDecoder()
                .decode(java.nio.ByteBuffer.wrap(input.readAllBytes()))
                .toString());
    }
}
