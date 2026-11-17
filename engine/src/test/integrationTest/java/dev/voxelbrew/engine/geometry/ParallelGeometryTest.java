package dev.voxelbrew.engine.geometry;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.engine.mesh.*;
import dev.voxelbrew.engine.model.*;
import dev.voxelbrew.engine.performance.ComputeBenchmark;
import dev.voxelbrew.engine.printing.*;
import dev.voxelbrew.util.concurrent.CpuWork;
import java.util.*;
import org.junit.jupiter.api.Test;

class ParallelGeometryTest {
    @Test
    void nativeBatchesPreserveMeshOrderFaceMappingMeasurementsAndOwnership() {
        int before = NativeKernel.liveHandles();
        var document = ComputeBenchmark.document(8, 4);
        ManufacturingModel detached;
        try (var evaluation = Evaluator.evaluate(document)) {
            var bodies = evaluation.requireCompleteDocument();
            var serial = bodies.stream()
                    .map(b -> b.solid().tessellate(MeshOptions.DEFAULT))
                    .toList();
            var volumes = bodies.stream().map(b -> b.solid().volume()).toList();
            detached =
                    dev.voxelbrew.engine.printing.ManufacturingModels.from(document, evaluation, MeshOptions.DEFAULT);
            assertThat(new ArrayList<>(detached.meshes().keySet()))
                    .isEqualTo(bodies.stream().map(Evaluation.Body::id).toList());
            for (int repeat = 0; repeat < 3; repeat++) {
                var meshes = CpuWork.map(bodies, b -> b.solid().tessellate(MeshOptions.DEFAULT));
                for (int i = 0; i < bodies.size(); i++) {
                    assertThat(meshes.get(i).vertices())
                            .containsExactly(serial.get(i).vertices());
                    assertThat(meshes.get(i).triangles())
                            .containsExactly(serial.get(i).triangles());
                    assertThat(meshes.get(i).faceIndices())
                            .containsExactly(serial.get(i).faceIndices());
                    assertThat(bodies.get(i).solid().volume()).isEqualTo(volumes.get(i));
                    assertThat(MeshValidation.inspect(meshes.get(i)).closed()).isTrue();
                }
            }
            assertThatThrownBy(() -> CpuWork.map(bodies, body -> {
                        var mesh = body.solid().tessellate(MeshOptions.DEFAULT);
                        if (body.id().equals(bodies.getFirst().id()))
                            throw new IllegalStateException("injected batch failure");
                        return mesh;
                    }))
                    .isInstanceOf(IllegalStateException.class);
        }
        assertThat(NativeKernel.liveHandles()).isEqualTo(before);
        assertThat(PrintValidation.validate(detached).valid()).isTrue();
    }
}
