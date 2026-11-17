package dev.voxelbrew.importer;

/** Application reader ports, separate from plugin discovery. */
public interface ImportRuntime {
    DocumentSource documents();

    GcodeSource gcode();

    static ImportRuntime load() {
        return java.util.ServiceLoader.load(ImportRuntime.class)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No import runtime installed"));
    }
}
