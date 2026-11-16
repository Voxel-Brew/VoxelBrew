package dev.voxelbrew.exporter;

/** Human-readable export of the complete persisted document schema. */
public final class JsonExporter extends FileExporter {
    public JsonExporter() {
        super(
                "json",
                "VoxelBrew JSON (*.json)",
                (document, path, overwrite, window) -> JsonDocumentWriter.save(document, path, overwrite));
    }
}
