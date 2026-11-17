package dev.voxelbrew.exporter;

import dev.voxelbrew.api.model.BodyInstance;
import dev.voxelbrew.api.model.mesh.MeshData;
import dev.voxelbrew.api.model.mesh.MeshOptions;
import java.util.List;
import lombok.NonNull;

/**
 * Read-only exporter input. Valid for the synchronous export call; returned meshes are detached.
 */
public interface ModelView {
    enum Role {
        MODEL,
        SOLID_SUPPORT
    }

    record Body(
            @NonNull String id,
            @NonNull String name,
            @NonNull Role role) {
        public Body(String id, String name) {
            this(id, name, Role.MODEL);
        }

        public Body {}
    }

    List<Body> bodies();

    List<BodyInstance> instances();

    MeshData tessellate(String bodyId, MeshOptions options);
}
