package dev.voxelbrew.engine.model;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.exporter.DocumentWriter;
import dev.voxelbrew.importer.DocumentReader;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SplitBodyTest {
    @TempDir
    Path directory;

    @Test
    void splitOutputsPersistReplayAndConserveExactVolume() throws Exception {
        var session = EditableEngineTest.reference();
        session.editDimension("thickness", "distance", 8);
        session.add(new Feature.SplitBody(
                "split", new Feature.BodyRef("cut", "body"), new Feature.SplitPlane(1, 0, 0, 30)));
        var file = directory.resolve("split.vxb");
        DocumentWriter.save(session.document(), file, false);
        session = new ModelSession(DocumentReader.load(file));
        try (var evaluated = session.evaluate()) {
            var bodies = evaluated.requireCompleteDocument();
            assertThat(bodies.stream().map(Evaluation.Body::id).toList())
                    .isEqualTo(List.of("split.negative", "split.positive"));
            assertThat(bodies.stream().mapToDouble(b -> b.solid().volume()).sum())
                    .isCloseTo(19099.469035085127, org.assertj.core.data.Offset.offset(0.01));
            assertThat(bodies.get(0).solid().bounds().maxX()).isCloseTo(30, org.assertj.core.data.Offset.offset(0.001));
            assertThat(bodies.get(1).solid().bounds().minX()).isCloseTo(30, org.assertj.core.data.Offset.offset(0.001));
            assertThat(bodies.get(0).solid().contains(10, 10, 4)).isFalse();
        }
        session.editDimension("split", "offset", 20);
        try (var evaluated = session.evaluate()) {
            assertThat(evaluated.bodies().getFirst().solid().bounds().maxX())
                    .isCloseTo(20, org.assertj.core.data.Offset.offset(0.001));
        }
        assertThat(session.undo()).isTrue();
        session.setCursor(4);
        try (var evaluated = session.evaluate()) {
            assertThat(evaluated.bodies().size()).isEqualTo(1);
            assertThat(evaluated.bodies().getFirst().id()).isEqualTo("thickness.body");
        }
        session.setCursor(5);
        assertThat(session.redo()).isTrue();
        try (var evaluated = session.evaluate()) {
            assertThat(evaluated.bodies().getFirst().solid().bounds().maxX())
                    .isCloseTo(20, org.assertj.core.data.Offset.offset(0.001));
        }
    }

    @Test
    void missedPlaneIsRepairableAndNamedOutputCanFeedAnotherFeature() {
        var session = EditableEngineTest.reference();
        session.add(new Feature.SplitBody(
                "split", new Feature.BodyRef("cut", "body"), new Feature.SplitPlane(1, 0, 0, 100)));
        session.add(new Feature.SplitBody(
                "quarter", new Feature.BodyRef("split", "positive"), new Feature.SplitPlane(0, 1, 0, 20)));
        try (var failed = session.evaluate()) {
            assertThat(failed.diagnostics().get(4).state()).isEqualTo(Evaluation.State.FAILED);
            assertThat(failed.diagnostics().get(4).message().contains("does not intersect"))
                    .isTrue();
            assertThat(failed.diagnostics().get(5).state()).isEqualTo(Evaluation.State.BLOCKED);
            assertThatThrownBy(failed::requireCompleteDocument).isInstanceOf(IllegalStateException.class);
        }
        session.editDimension("split", "offset", 30);
        try (var result = session.evaluate()) {
            assertThat(result.requireCompleteDocument().size()).isEqualTo(3);
            assertThat(result.bodies().stream()
                            .mapToDouble(b -> b.solid().volume())
                            .sum())
                    .isCloseTo((60 * 40 - 4 * Math.PI) * 5, org.assertj.core.data.Offset.offset(0.01));
        }
    }
}
