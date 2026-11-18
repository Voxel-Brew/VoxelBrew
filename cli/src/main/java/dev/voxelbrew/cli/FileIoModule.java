package dev.voxelbrew.cli;

import dev.voxelbrew.api.exporter.*;
import dev.voxelbrew.api.importer.*;
import dev.voxelbrew.api.slicer.SlicerCatalog;
import dev.voxelbrew.api.slicer.SlicerRuntime;
import dev.voxelbrew.api.slicer.Slicing;
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

    @lombok.NonNull
    private final SlicerRuntime slicerRuntime;

    public FileIoModule() {
        this(
                dev.voxelbrew.util.services.Services.load(ImportRuntime.class),
                dev.voxelbrew.exporter.DocumentWriter::save,
                dev.voxelbrew.util.services.Services.load(SlicerRuntime.class));
    }

    private FileIoModule(ImportRuntime imports, DocumentSave sink, SlicerRuntime slicerRuntime) {
        this(imports.documents(), sink, imports.gcode(), imports, slicerRuntime);
    }

    public FileIoModule(DocumentSource source, DocumentSave sink, GcodeSource gcode) {
        this(
                source,
                sink,
                gcode,
                dev.voxelbrew.util.services.Services.load(ImportRuntime.class),
                dev.voxelbrew.util.services.Services.load(SlicerRuntime.class));
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

    SlicerCatalog.Factory slicers() {
        return slicerRuntime.slicers();
    }

    dev.voxelbrew.api.slicer.PrinterProfileCatalog printers() {
        return slicerRuntime.printers(
                dev.voxelbrew.util.services.Services.load(dev.voxelbrew.util.config.ApplicationPaths.class)
                        .printers());
    }

    Slicing slicing() {
        return slicerRuntime.slicing();
    }

    java.nio.file.Path slicerExecutable() {
        return slicerRuntime.defaultSlicerExecutable();
    }
}
