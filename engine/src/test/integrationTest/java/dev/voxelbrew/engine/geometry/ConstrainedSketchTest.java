package dev.voxelbrew.engine.geometry;

import static dev.voxelbrew.engine.sketch.SketchGeometry.*;
import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.engine.model.*;
import dev.voxelbrew.engine.sketch.*;
import dev.voxelbrew.exporter.DocumentWriter;
import dev.voxelbrew.importer.DocumentReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConstrainedSketchTest {
    @TempDir
    Path directory;

    private static Feature.Sketch sketch(SketchGeometry geometry) {
        return new Feature.Sketch(
                "sketch", "profile", new Feature.Plane("origin-xy", 0), new Feature.Constrained(geometry));
    }

    private static double volume(ModelSession session) {
        try (var evaluation = session.evaluate()) {
            evaluation.requireCompleteDocument();
            return evaluation.bodies().getFirst().solid().volume();
        }
    }

    @Test
    void dimensionedRectangleAndCircleHoleRoundTripAndRebuildWithStableIds() throws Exception {
        int handles = NativeKernel.liveHandles();
        var g = Sketches.rectangle("plate", 0, 0, 60, 40, true);
        var circle = Sketches.entity("hole", Kind.CIRCLE, false, 10, 10, 2);
        g = g.add(circle);
        var boundary = new ArrayList<>(g.boundary());
        boundary.add(circle.id());
        g = g.withBoundary(boundary);
        g = g.add(SketchConstraints.create(g, "hole-fixed", Type.FIX, List.of(circle.ref()), 0));
        var session = new ModelSession(CadDocument.empty());
        session.add(sketch(g));
        session.add(new Feature.Extrude("thickness", new Feature.ProfileRef("sketch", "profile"), 8));
        assertThat(volume(session)).isCloseTo(19099.469035085127, org.assertj.core.data.Offset.offset(0.01));
        var file = directory.resolve("constrained plate.vxb");
        DocumentWriter.save(session.document(), file, false);
        assertThat(DocumentReader.load(file)).isEqualTo(session.document());
        session = new ModelSession(DocumentReader.load(file));
        session.editDimension("sketch", "width", 80);
        assertThat(volume(session)).isCloseTo((80 * 40 - 4 * Math.PI) * 8, org.assertj.core.data.Offset.offset(0.01));
        assertThat(((Feature.Sketch) session.document().features().getFirst()).profileId())
                .isEqualTo("profile");
        assertThat(session.undo()).isTrue();
        assertThat(volume(session)).isCloseTo(19099.469035085127, org.assertj.core.data.Offset.offset(0.01));
        assertThat(session.redo()).isTrue();
        session.setCursor(1);
        try (var evaluation = session.evaluate()) {
            assertThat(evaluation.bodies().isEmpty()).isTrue();
            assertThatThrownBy(evaluation::requireCompleteDocument).isInstanceOf(IllegalStateException.class);
        }
        session.setCursor(2);
        assertThat(volume(session)).isCloseTo((80 * 40 - 4 * Math.PI) * 8, org.assertj.core.data.Offset.offset(0.01));
        assertThat(NativeKernel.liveHandles()).isEqualTo(handles);
    }

    @Test
    void signedArcAndExactBezierExtrusionsUseKernelCurvesRatherThanPolygons() {
        int handles = NativeKernel.liveHandles();
        for (double sweep : new double[] {Math.PI, -Math.PI}) {
            var arc = Sketches.entity("arc", Kind.ARC, false, 0, 0, 10, 0, sweep);
            var chord = Sketches.entity("chord", Kind.LINE, false, -10, 0, 10, 0);
            var g = new SketchGeometry(List.of(arc, chord), List.of(), List.of("arc", "chord"));
            try (var solid = Solid.profilePrism(SketchProfiles.profile(g), 12, -3)) {
                assertThat(solid.volume()).isCloseTo(Math.PI * 100 / 2 * 3, org.assertj.core.data.Offset.offset(0.001));
                assertThat(solid.bounds().minZ()).isCloseTo(9, org.assertj.core.data.Offset.offset(1e-7));
                assertThat(solid.bounds().maxZ()).isCloseTo(12, org.assertj.core.data.Offset.offset(1e-7));
                assertThat(solid.contains(0, Math.copySign(5, sweep), 10)).isTrue();
            }
        }
        var spline = Sketches.entity("spline", Kind.SPLINE, false, 0, 0, 0, 10, 10, 10, 10, 0);
        var chord = Sketches.entity("chord", Kind.LINE, false, 10, 0, 0, 0);
        var g = new SketchGeometry(List.of(spline, chord), List.of(), List.of("spline", "chord"));
        try (var solid = Solid.profilePrism(SketchProfiles.profile(g), 0, 2)) {
            assertThat(solid.volume()).isCloseTo(120, org.assertj.core.data.Offset.offset(1e-6));
            assertThat(solid.bounds().maxY()).isCloseTo(7.5, org.assertj.core.data.Offset.offset(1e-6));
            assertThat(solid.contains(5, 7, 1)).isTrue();
            assertThat(solid.contains(1, 7, 1)).isFalse();
        }
        assertThat(NativeKernel.liveHandles()).isEqualTo(handles);
    }

    @Test
    void constrainedProfileThroughCutTracksFutureThicknessAndPreservesSource() {
        var g = Sketches.rectangle("slot", 20, 10, 10, 5, true);
        var profile = SketchProfiles.profile(g);
        for (double thickness : new double[] {5, 8})
            try (var source = Solid.rectanglePrism(0, 0, 0, 60, 40, thickness);
                    var cut = source.cutThroughProfile(profile)) {
                assertThat(source.volume()).isCloseTo(2400 * thickness, org.assertj.core.data.Offset.offset(1e-6));
                assertThat(cut.volume()).isCloseTo((2400 - 50) * thickness, org.assertj.core.data.Offset.offset(1e-6));
                assertThat(cut.contains(25, 12, thickness / 2)).isFalse();
            }
    }

    @Test
    void invalidAndConflictingDefinitionsStayEditableAndNeverPublishGeometry() throws Exception {
        var g = Sketches.rectangle("plate", 0, 0, 60, 40, true);
        var wrong = SketchConstraints.create(
                g, "conflict", Type.LENGTH, List.of(g.entities().getFirst().ref()), 80);
        var broken = new CadDocument(
                "document",
                List.of(
                        sketch(g.add(wrong)),
                        new Feature.Extrude("extrude", new Feature.ProfileRef("sketch", "profile"), 5)),
                2);
        var file = directory.resolve("repairable constraints.vxb");
        DocumentWriter.save(broken, file, false);
        assertThat(DocumentReader.load(file)).isEqualTo(broken);
        try (var result = Evaluator.evaluate(DocumentReader.load(file))) {
            assertThatThrownBy(result::requireCompleteDocument).isInstanceOf(IllegalStateException.class);
            assertThat(result.diagnostics().stream()
                            .anyMatch(d -> d.featureId().equals("sketch")
                                    && d.message().contains("conflict")))
                    .isTrue();
        }
        var session = new ModelSession(broken);
        session.replace(sketch(g));
        assertThat(volume(session)).isCloseTo(12000, org.assertj.core.data.Offset.offset(1e-6));
        int handles = NativeKernel.liveHandles();
        var open = g.withBoundary(List.of(g.entities().getFirst().id()));
        assertThatThrownBy(() -> SketchProfiles.profile(open)).isInstanceOf(IllegalArgumentException.class);
        var other = Sketches.entity("separate", Kind.CIRCLE, false, 100, 100, 4);
        var disjoint = g.add(other)
                .withBoundary(List.of(
                        other.id(),
                        g.entities().get(0).id(),
                        g.entities().get(1).id(),
                        g.entities().get(2).id(),
                        g.entities().get(3).id()));
        assertThatThrownBy(() -> SketchProfiles.profile(disjoint)).isInstanceOf(IllegalArgumentException.class);
        assertThat(NativeKernel.liveHandles()).isEqualTo(handles);
    }
}
