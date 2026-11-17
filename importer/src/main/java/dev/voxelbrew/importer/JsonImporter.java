package dev.voxelbrew.importer;

import dev.voxelbrew.api.importer.*;
import java.io.*;

public final class JsonImporter extends FileImporter {
    public JsonImporter() {
        super("VoxelBrew JSON document", "json");
    }

    @Override
    protected dev.voxelbrew.api.model.CadDocument read(InputStream input) throws IOException {
        return dev.voxelbrew.engine.persistence.DocumentCodec.decodeJson(java.nio.charset.StandardCharsets.UTF_8
                .newDecoder()
                .decode(java.nio.ByteBuffer.wrap(input.readAllBytes()))
                .toString());
    }
}
