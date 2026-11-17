package dev.voxelbrew.gui;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.engine.model.Evaluator;
import dev.voxelbrew.engine.model.Feature;
import dev.voxelbrew.importer.DocumentReader;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreparedDocumentsTest {
    @TempDir
    Path directory;

    @Test
    void savedInputsReopenWithEditableProfilesAndExactPlateGeometry() throws Exception {
        for (int offset : new int[] {0, 12}) {
            var document = DocumentReader.load(PreparedDocuments.copy("rectangle-" + offset + ".vxb", directory));
            assertThat(document.cursor()).isEqualTo(1);
            assertThat(document.features().getFirst()).isInstanceOf(Feature.Sketch.class);
            var sketch = (Feature.Sketch) document.features().getFirst();
            assertThat(sketch.plane().offsetZ()).isEqualTo(offset);
            assertThat(sketch.profile()).isInstanceOf(Feature.Constrained.class);
            assertThat(((Feature.Constrained) sketch.profile()).geometry().entities())
                    .hasSize(4);
        }
        var plate = DocumentReader.load(PreparedDocuments.copy("plate-5mm.vxb", directory));
        assertThat(plate.cursor()).isEqualTo(2);
        try (var evaluation = Evaluator.evaluate(plate)) {
            assertThat(evaluation.bodies().size()).isEqualTo(1);
            var solid = evaluation.bodies().getFirst().solid();
            assertThat(solid.volume()).isCloseTo(12000, org.assertj.core.data.Offset.offset(.01));
            assertThat(solid.bounds().maxX()).isCloseTo(60, org.assertj.core.data.Offset.offset(.001));
            assertThat(solid.bounds().maxY()).isCloseTo(40, org.assertj.core.data.Offset.offset(.001));
            assertThat(solid.bounds().maxZ()).isCloseTo(5, org.assertj.core.data.Offset.offset(.001));
        }
    }
}
