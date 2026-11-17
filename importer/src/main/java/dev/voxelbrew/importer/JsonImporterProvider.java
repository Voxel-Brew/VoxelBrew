package dev.voxelbrew.importer;

import dev.voxelbrew.api.importer.*;

public final class JsonImporterProvider implements ImporterProvider {
    public JsonImporterProvider() {}

    @Override
    public ImporterDescriptor descriptor() {
        return new ImporterDescriptor(
                "json", "VoxelBrew JSON document", dev.voxelbrew.util.update.BuildVersion.current());
    }

    @Override
    public Importer create() {
        return new JsonImporter();
    }
}
