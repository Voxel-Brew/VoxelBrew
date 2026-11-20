package dev.voxelbrew.engine.sketch;

import static dev.voxelbrew.api.model.sketch.SketchGeometry.*;
import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.model.sketch.SketchGeometry;
import java.util.*;
import org.junit.jupiter.api.Test;

class ParallelSketchTest {
    @Test
    void largeJacobianKeepsSerialGeometryRankAndConflictDiagnostics() {
        var entities = new ArrayList<Entity>();
        for (int i = 0; i < 48; i++) {
            entities.add(Sketches.entity("line" + i, Kind.LINE, false, 0, i * 3, 9, i * 3 + 1));
        }
        var geometry = new SketchGeometry(entities, List.of(), List.of());
        for (var entity : entities) {
            geometry = geometry.add(SketchConstraints.create(
                    geometry, entity.id() + "fixed", Type.FIX, List.of(entity.ref(Part.START)), 0));
            geometry = geometry.add(SketchConstraints.create(
                    geometry, entity.id() + "horizontal", Type.HORIZONTAL, List.of(entity.ref()), 0));
            geometry = geometry.add(
                    SketchConstraints.create(geometry, entity.id() + "length", Type.LENGTH, List.of(entity.ref()), 10));
        }
        var serial = SketchSolver.solve(geometry, false);
        var parallel = SketchSolver.solve(geometry);
        assertThat(serial.solved()).isTrue();
        assertThat(parallel).isEqualTo(serial);
        assertThat(parallel.degreesOfFreedom()).isEqualTo(0);
        var conflict = geometry.add(SketchConstraints.create(
                geometry, "conflict", Type.LENGTH, List.of(entities.getFirst().ref()), 20));
        assertThat(SketchSolver.solve(conflict)).isEqualTo(SketchSolver.solve(conflict, false));
    }
}
