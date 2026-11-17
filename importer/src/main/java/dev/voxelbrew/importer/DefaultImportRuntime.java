package dev.voxelbrew.importer;

public final class DefaultImportRuntime implements ImportRuntime {
    @Override
    public DocumentSource documents() {
        return DocumentReader::load;
    }

    @Override
    public GcodeSource gcode() {
        return new GcodeSource() {
            @Override
            public dev.voxelbrew.engine.printing.GcodeJob read(java.nio.file.Path path) throws java.io.IOException {
                return GcodeReader.read(path);
            }

            @Override
            public dev.voxelbrew.engine.printing.GcodeJob readLines(java.util.List<String> lines)
                    throws java.io.IOException {
                return GcodeReader.read(new java.io.ByteArrayInputStream(
                        (String.join("\n", lines) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            }
        };
    }
}
