package dev.voxelbrew.exporter;

/** Editable binary document exporter; retains schema and overwrite protections. */
public final class VoxelBrewExporter extends FileExporter {
    public VoxelBrewExporter() {
        this((document, path, overwrite, window) -> DocumentWriter.save(document, path, overwrite));
    }

    VoxelBrewExporter(Writer writer) {
        super("vxb", "VoxelBrew design (*.vxb)", writer);
    }
}
