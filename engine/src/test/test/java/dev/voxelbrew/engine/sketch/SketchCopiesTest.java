package dev.voxelbrew.engine.sketch;

import static dev.voxelbrew.api.model.sketch.SketchGeometry.*;
import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.model.sketch.SketchGeometry;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SketchCopiesTest {
    private final Entity arc = entity("arc", Kind.ARC, false, 2, 1, 3, 0, Math.PI / 2);
    private final Entity line = entity("guide", Kind.LINE, true, 0, 1, 4, 1);
    private final SketchGeometry source = new SketchGeometry(List.of(arc, line), List.of(), List.of("arc"));
    private final AtomicInteger sequence = new AtomicInteger();
    private final SketchCopies copies =
            new SketchCopies(source, Set.of("arc", "guide"), () -> "c" + sequence.incrementAndGet());

    @Test
    void nestedCopiesFollowResizingAndMovingTheOriginalAfterReopening() throws Exception {
        var rectangle = copies.rectangular(2, 1, 10, 0);
        var circular = new SketchCopies(rectangle, Set.of("c1"), () -> "turned").circular(new Vec(0, 0), 2, 180);
        var mirrored = new SketchCopies(circular, Set.of("turned"), () -> "reflected").mirror(line);
        var session = new dev.voxelbrew.engine.model.ModelSession(dev.voxelbrew.api.model.CadDocument.empty());
        session.add(new dev.voxelbrew.api.model.Feature.Sketch(
                "sketch",
                "profile",
                new dev.voxelbrew.api.model.Feature.Plane("origin-xy", 0),
                new dev.voxelbrew.api.model.Feature.Constrained(mirrored)));
        var reopened = dev.voxelbrew.engine.persistence.DocumentCodec.decode(
                dev.voxelbrew.engine.persistence.DocumentCodec.encode(session.document()));
        var sketch =
                (dev.voxelbrew.api.model.Feature.Sketch) reopened.features().getFirst();
        var geometry = ((dev.voxelbrew.api.model.Feature.Constrained) sketch.profile())
                .geometry()
                .replace(arc.withValues(List.of(5., 3., 7., 0., Math.PI / 2)))
                .replace(line.withValues(List.of(0., 4., 8., 4.)));
        var result = SketchSolver.solve(geometry);
        var solved = result.requireSolved();
        assertThat(result.degreesOfFreedom())
                .isEqualTo(arc.values().size() + line.values().size());
        assertThat(solved.entity("c1").at(0).distance(new Vec(22, 3))).isLessThan(1e-8);
        assertThat(solved.entity("turned").at(0).distance(new Vec(-22, -3))).isLessThan(1e-8);
        assertThat(solved.entity("reflected").at(0).distance(new Vec(-22, 11))).isLessThan(1e-8);
        assertThat(Sketches.removeEntity(solved, "arc").entities())
                .extracting(Entity::id)
                .containsExactly("guide", "c2");
        var constrained = solved.add(SketchConstraints.create(solved, "radius", Type.RADIUS, List.of(arc.ref()), 9));
        assertThat(SketchSolver.solve(constrained)
                        .requireSolved()
                        .entity("reflected")
                        .value(2))
                .isCloseTo(9., within(1e-6));
    }

    @Test
    void mirroredTextTracksNewGlyphsUnderTheSameEntityId() {
        var label =
                dev.voxelbrew.api.model.sketch.SketchText.create("text", "L", "SansSerif", 0, 1, 2, 4, 0, 0, "", false);
        var geometry = new SketchGeometry(List.of(label), List.of(), List.of("text"));
        var mirror = new SketchCopies(geometry, Set.of("text"), () -> "copy").mirror(new Vec(0, 0), new Vec(1, 0));
        var updated = dev.voxelbrew.api.model.sketch.SketchText.create(
                "text", "LONG", "SansSerif", 0, 1, 2, 8, 0, 0, "", false);
        var result = SketchSolver.solve(mirror.replace(updated)).requireSolved();
        var original = result.curves(updated);
        var instance = result.curves(result.entity("copy"));
        assertThat(instance).hasSameSizeAs(original);
        for (int i = 0; i < original.size(); i++) {
            var p = original.get(i).at(.4);
            assertThat(instance.get(i).at(.4).distance(new Vec(p.x(), -p.y()))).isLessThan(1e-8);
        }
    }

    @Test
    void rectangularUsesSignedSpacingAndPreservesProfileAndConstructionMembership() {
        var result = copies.rectangular(2, 3, -5, 10);
        assertThat(result.entities()).hasSize(12);
        assertThat(result.boundary()).hasSize(6);
        assertThat(result.entities().get(10).at(0)).isEqualTo(new Vec(0, 21));
        assertThat(result.entities().getLast().construction()).isTrue();
        assertThatThrownBy(() -> copies.rectangular(Integer.MAX_VALUE, 2, 2, 2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void circularRotatesAroundOffsetCenterWithoutDuplicatingOriginalAtFullTurn() {
        var center = new Vec(2, 1);
        var full = copies.circular(center, 4, 360);
        assertThat(full.entities()).hasSize(8);
        assertThat(full.entities().get(2).at(0).distance(new Vec(2, 4))).isLessThan(1e-9);
        assertThat(full.entities().get(6).at(0).distance(new Vec(2, -2))).isLessThan(1e-9);
        var partial = copies.circular(center, 3, -90);
        assertThat(partial.entities().get(4).at(0).distance(new Vec(2, -2))).isLessThan(1e-9);
    }

    @Test
    void mirrorAboutOffsetDiagonalReversesArcSweepAndReflectsBothEndpoints() {
        var mirrored = copies.mirror(new Vec(0, 1), new Vec(1, 2)).entities().get(2);
        assertThat(mirrored.value(4)).isEqualTo(-arc.value(4));
        for (double t : List.of(0., .5, 1.)) {
            var p = arc.at(t);
            assertThat(mirrored.at(t).distance(new Vec(p.y() - 1, p.x() + 1))).isLessThan(1e-9);
        }
    }
}
