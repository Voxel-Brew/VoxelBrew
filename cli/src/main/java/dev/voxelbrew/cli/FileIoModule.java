package dev.voxelbrew.cli;

import dagger.Module;
import dagger.Provides;
import dev.voxelbrew.api.exporter.*;
import dev.voxelbrew.api.importer.*;
import dev.voxelbrew.importer.DocumentSource;
import dev.voxelbrew.importer.GcodeSource;
import dev.voxelbrew.importer.ImportRuntime;
import dev.voxelbrew.slicer.api.SlicerCatalog;
import dev.voxelbrew.slicer.api.SlicerRuntime;
import dev.voxelbrew.slicer.api.Slicing;
import javax.inject.Singleton;

@Module
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
        this(ImportRuntime.load(), dev.voxelbrew.exporter.DocumentWriter::save, SlicerRuntime.load());
    }

    private FileIoModule(ImportRuntime imports, DocumentSave sink, SlicerRuntime slicerRuntime) {
        this(imports.documents(), sink, imports.gcode(), imports, slicerRuntime);
    }

    public FileIoModule(DocumentSource source, DocumentSave sink, GcodeSource gcode) {
        this(source, sink, gcode, ImportRuntime.load(), SlicerRuntime.load());
    }

    @Provides
    @Singleton
    DocumentSource source() {
        return source;
    }

    @Provides
    @Singleton
    DocumentSave sink() {
        return sink;
    }

    @Provides
    @Singleton
    GcodeSource gcode() {
        return gcode;
    }

    @Provides
    java.util.function.Supplier<ExporterProviderRegistry> exporters() {
        return () -> java.util.ServiceLoader.load(ExporterProviderRegistry.class)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No ExporterProviderRegistry service found"));
    }

    @Provides
    java.util.function.Supplier<ImporterProviderRegistry> importers() {
        return () -> java.util.ServiceLoader.load(ImporterProviderRegistry.class)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No ImporterProviderRegistry service found"));
    }

    @Provides
    SlicerCatalog.Factory slicers() {
        return slicerRuntime.slicers();
    }

    @Provides
    dev.voxelbrew.slicer.api.PrinterProfileCatalog printers() {
        return slicerRuntime.printers(dev.voxelbrew.util.config.ApplicationPaths.printers());
    }

    @Provides
    Slicing slicing() {
        return slicerRuntime.slicing();
    }

    @Provides
    @javax.inject.Named("slicerExecutable")
    java.nio.file.Path slicerExecutable() {
        return slicerRuntime.defaultSlicerExecutable();
    }
}
