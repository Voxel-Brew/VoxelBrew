package dev.voxelbrew.importer;

/** Application reader ports, separate from plugin discovery. */
public interface ImportRuntime {
    static ImportRuntime load() {
        return java.util.ServiceLoader.load(ImportRuntime.class)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No import runtime installed"));
    }

    DocumentSource documents();

    GcodeSource gcode();
}
