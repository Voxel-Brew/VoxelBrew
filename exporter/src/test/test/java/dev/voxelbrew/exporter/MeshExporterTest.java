package dev.voxelbrew.exporter;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.exporter.FileExportDialog;
import dev.voxelbrew.engine.mesh.MeshData;
import dev.voxelbrew.engine.mesh.MeshOptions;
import dev.voxelbrew.engine.model.BodyInstance;
import dev.voxelbrew.engine.model.Transform;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MeshExporterTest {
    @TempDir
    Path directory;

    @Test
    void rejectsEmptyMultipleAndUnknownOptionBeforeCreatingOutput() {
        var exporter = new BinaryStlExporter();
        var target = directory.resolve("invalid.stl");
        var base = new MeshExportDialog.Settings(
                ExportSelection.design(null),
                MeshOptions.DEFAULT,
                Map.of(),
                new FileExportDialog.Destination(target, false));

        assertThatThrownBy(() -> exporter.writeFile(view(List.of()), base))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> exporter.writeFile(view(List.of(instance("one"), instance("two"))), base))
                .isInstanceOf(IllegalArgumentException.class);
        var unknownOption = new MeshExportDialog.Settings(
                base.selection(), base.mesh(), Map.of("missing", "value"), base.destination());
        assertThatThrownBy(() -> exporter.writeFile(view(List.of(instance("one"))), unknownOption))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(Files.exists(target)).isFalse();
    }

    private static BodyInstance instance(String id) {
        return new BodyInstance(id, "body", Transform.IDENTITY);
    }

    private static ModelView view(List<BodyInstance> instances) {
        return new ModelView() {
            @Override
            public List<Body> bodies() {
                return List.of(new Body("body", "Body"));
            }

            @Override
            public List<BodyInstance> instances() {
                return instances;
            }

            @Override
            public MeshData tessellate(String bodyId, MeshOptions options) {
                throw new AssertionError("Invalid settings must fail before tessellation");
            }
        };
    }
}
