package dev.voxelbrew.importer;

import dev.voxelbrew.api.importer.*;
import java.io.*;

public final class VoxelBrewImporter extends FileImporter {
    public VoxelBrewImporter() {
        super("VoxelBrew document", "vxb");
    }

    @Override
    protected dev.voxelbrew.engine.model.CadDocument read(InputStream input) throws IOException {
        return dev.voxelbrew.engine.persistence.DocumentCodec.decode(
                input.readNBytes(dev.voxelbrew.engine.persistence.DocumentCodec.MAX_ENCODED_BYTES + 1));
    }
}
