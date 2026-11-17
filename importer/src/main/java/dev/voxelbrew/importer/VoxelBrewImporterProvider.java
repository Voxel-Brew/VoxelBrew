package dev.voxelbrew.importer;

import dev.voxelbrew.api.importer.*;

public final class VoxelBrewImporterProvider implements ImporterProvider {
    public VoxelBrewImporterProvider() {}

    @Override
    public ImporterDescriptor descriptor() {
        return new ImporterDescriptor("vxb", "VoxelBrew document", dev.voxelbrew.util.update.BuildVersion.current());
    }

    @Override
    public Importer create() {
        return new VoxelBrewImporter();
    }
}
