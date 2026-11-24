package dev.voxelbrew.engine.model;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.model.CadDocument;
import dev.voxelbrew.api.model.Feature;
import dev.voxelbrew.engine.model.BodyPattern.Axis;
import dev.voxelbrew.engine.model.BodyPattern.Kind;
import dev.voxelbrew.exporter.DocumentWriter;
import dev.voxelbrew.importer.DocumentReader;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BodyPatternTest {
    @Test
    void patternsReplayAsIndependentSolidsAndCanUseEarlierCopies(@TempDir Path directory) throws Exception {
        var session = new ModelSession(CadDocument.empty());
        session.add(
                new Feature.Sketch("sketch", "profile", Feature.Plane.origin("XY"), new Feature.Rectangle(2, 4, 2, 4)));
        session.add(new Feature.Extrude("base", new Feature.ProfileRef("sketch", "profile"), 6));
        var source = new Feature.BodyRef("base", "body");
        session.add(new BodyPattern(Kind.RECTANGULAR, source, Axis.Y, 2, 2, 10, -12).feature("grid"));
        session.add(new BodyPattern(Kind.CIRCULAR, source, Axis.Z, 4, 1, 360, 0).feature("ring"));
        var copy = new Feature.BodyRef("grid", "copy-1-1");
        session.add(new BodyPattern(Kind.MIRROR, copy, Axis.X, 2, 1, 3, 0).feature("mirror"));
        var file = directory.resolve("patterns.vxb");
        DocumentWriter.save(session.document(), file, false);
        var reopened = new ModelSession(DocumentReader.load(file));
        try (var evaluated = reopened.evaluate()) {
            var bodies = evaluated.requireCompleteDocument();
            assertThat(bodies).hasSize(8);
            for (var body : bodies) assertThat(body.solid().volume()).isCloseTo(48, within(1e-6));
            assertThat(bodies.get(0).solid().contains(3, 6, 3)).isTrue();
            assertThat(bodies.get(3).solid().contains(13, 6, -9)).isTrue();
            assertThat(bodies.get(4).solid().contains(-6, 3, 3)).isTrue();
            assertThat(bodies.get(6).solid().contains(6, -3, 3)).isTrue();
            assertThat(bodies.get(7).solid().contains(-7, 6, -9)).isTrue();
        }
        reopened.replace(new BodyPattern(Kind.CIRCULAR, source, Axis.Z, 3, 1, -90, 0).feature("ring"));
        try (var evaluated = reopened.evaluate()) {
            var bodies = evaluated.requireCompleteDocument();
            assertThat(bodies).hasSize(7);
            assertThat(bodies.get(5).solid().contains(6, -3, 3)).isTrue();
        }
        assertThat(reopened.undo()).isTrue();
        try (var evaluated = reopened.evaluate()) {
            assertThat(evaluated.requireCompleteDocument()).hasSize(8);
        }
    }
}
