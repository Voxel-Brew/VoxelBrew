package dev.voxelbrew.cli;

import dev.voxelbrew.api.exporter.*;
import dev.voxelbrew.api.importer.*;
import dev.voxelbrew.api.slicer.SlicerProviderRegistry;
import dev.voxelbrew.importer.DocumentSource;
import dev.voxelbrew.importer.GcodeSource;
import dev.voxelbrew.importer.ImportRuntime;

@lombok.RequiredArgsConstructor(access = lombok.AccessLevel.PRIVATE)
public final class FileIoModule {
    @lombok.NonNull
    private final DocumentSource source;

    @lombok.NonNull
    private final DocumentSave sink;

    @lombok.NonNull
    private final GcodeSource gcode;

    @lombok.NonNull
    private final ImportRuntime imports;

    public FileIoModule() {
        this(
                dev.voxelbrew.util.services.Services.load(ImportRuntime.class),
                dev.voxelbrew.exporter.DocumentWriter::save);
    }

    private FileIoModule(ImportRuntime imports, DocumentSave sink) {
        this(imports.documents(), sink, imports.gcode(), imports);
    }

    public FileIoModule(DocumentSource source, DocumentSave sink, GcodeSource gcode) {
        this(source, sink, gcode, dev.voxelbrew.util.services.Services.load(ImportRuntime.class));
    }

    DocumentSource source() {
        return source;
    }

    DocumentSave sink() {
        return sink;
    }

    GcodeSource gcode() {
        return gcode;
    }

    java.util.function.Supplier<ExporterProviderRegistry> exporters() {
        return () -> dev.voxelbrew.util.services.Services.load(ExporterProviderRegistry.class);
    }

    java.util.function.Supplier<ImporterProviderRegistry> importers() {
        return () -> dev.voxelbrew.util.services.Services.load(ImporterProviderRegistry.class);
    }

    java.util.function.Supplier<SlicerProviderRegistry> slicers() {
        return () -> dev.voxelbrew.util.services.Services.load(SlicerProviderRegistry.class);
    }
}
