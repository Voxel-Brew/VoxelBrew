package dev.voxelbrew.importer;

import dev.voxelbrew.api.i18n.TranslationService;
import dev.voxelbrew.api.importer.*;
import dev.voxelbrew.util.services.Services;
import java.io.*;

public final class VoxelBrewImporter extends FileImporter {
    public VoxelBrewImporter() {
        this(Services.load(TranslationService.class));
    }

    public VoxelBrewImporter(@lombok.NonNull TranslationService translations) {
        super(translations.translate("import.vxb.title"), "vxb");
    }

    @Override
    protected dev.voxelbrew.api.model.CadDocument read(InputStream input) throws IOException {
        return dev.voxelbrew.engine.persistence.DocumentCodec.decode(
                input.readNBytes(dev.voxelbrew.engine.persistence.DocumentCodec.MAX_ENCODED_BYTES + 1));
    }
}
