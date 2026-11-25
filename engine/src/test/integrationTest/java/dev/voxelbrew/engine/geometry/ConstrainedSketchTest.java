package dev.voxelbrew.engine.geometry;

import static dev.voxelbrew.api.model.sketch.SketchGeometry.*;
import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.model.*;
import dev.voxelbrew.api.model.mesh.MeshOptions;
import dev.voxelbrew.api.model.mesh.MeshValidation;
import dev.voxelbrew.api.model.sketch.*;
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
    void sketchFilletKeepsPlateDimensionsAndExtrudesAfterRadiusEdit() throws Exception {
        var rectangle = Sketches.rectangle("plate", 0, 0, 60, 40, true);
        var top = rectangle.entity("plate-top").ref();
        var right = rectangle.entity("plate-right").ref();
        assertThatThrownBy(() -> SketchFillet.round(rectangle, "corner", top, right, 40))
                .isInstanceOf(IllegalArgumentException.class);
        var rounded = SketchFillet.round(rectangle, "corner", right, top, 5);
        var session = new ModelSession(CadDocument.empty());
        session.add(sketch(rounded));
        session.add(new Feature.Extrude("plate-body", new Feature.ProfileRef("sketch", "profile"), 2));
        assertThat(volume(session)).isCloseTo((2400 - 25 * (1 - Math.PI / 4)) * 2, within(0.001));
        var file = directory.resolve("rounded.vxb");
        DocumentWriter.save(session.document(), file, false);
        var reopened = new ModelSession(DocumentReader.load(file));
        reopened.editDimension("sketch", "corner-radius", 8);
        assertThat(volume(reopened)).isCloseTo((2400 - 64 * (1 - Math.PI / 4)) * 2, within(0.001));
    }

    @Test
    void joinAndCutMultipleRegionsUpdateThePlacedBodyAfterReopen() throws Exception {
        var session = new ModelSession(CadDocument.empty());
        session.add(sketch(Sketches.rectangle("base", 0, 0, 40, 30, true)));
        var base = new Feature.Extrude("base-extrusion", new Feature.ProfileRef("sketch", "profile"), 5);
        session.add(base);
        var target = new Feature.BodyRef(base.id(), "body");
        var instance = new BodyInstance("placed", "base-extrusion.body", Transform.translation(20, 10, 0));
        session.setPrintSetup(session.document().printSetup().withInstances(List.of(instance)));
        var first = Sketches.entity("dot1", Kind.CIRCLE, false, 10, 10, 2);
        var second = Sketches.entity("dot2", Kind.CIRCLE, false, 20, 10, 2);
        session.add(new Feature.Sketch(
                "mark",
                "dots",
                new Feature.Plane("top", 5),
                new Feature.Constrained(
                        new SketchGeometry(List.of(first, second), List.of(), List.of(first.id(), second.id())))));
        var extrusion = new Feature.Extrude("mark-extrusion", new Feature.ProfileRef("mark", "dots"), 2);
        session.add(new BodyExtrusion(extrusion, BodyExtrusion.Operation.JOIN, target).feature());
        assertThat(volume(session)).isCloseTo(6000 + 16 * Math.PI, within(.001));
        session.replace(new BodyExtrusion(extrusion, BodyExtrusion.Operation.CUT, target).withDistance(-1));
        var file = directory.resolve("engraved.vxb");
        DocumentWriter.save(session.document(), file, false);
        var reopened = new ModelSession(DocumentReader.load(file));
        reopened.editDimension(extrusion.id(), "distance", -2);
        assertThat(volume(reopened)).isCloseTo(6000 - 16 * Math.PI, within(.001));
        var model = dev.voxelbrew.engine.printing.ManufacturingModels.evaluate(
                reopened.document(), dev.voxelbrew.api.model.mesh.MeshOptions.DEFAULT);
        assertThat(model.meshes()).containsOnlyKeys(instance.bodyId());
        assertThat(model.instances()).containsExactly(instance);
        assertThat(model.placedMesh(instance).triangleCount()).isGreaterThan(12);
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
    void longBezierPrismKeepsItsChordToleranceWithoutSubdividingTheStraightSweep() {
        // Uniformly spaced x poles give the analytic wall y = 6t(1-t), t = x/12.
        var spline = Sketches.entity("curve", Kind.SPLINE, false, 0, 0, 4, 2, 8, 2, 12, 0);
        var chord = Sketches.entity("chord", Kind.LINE, false, 12, 0, 0, 0);
        var profile = SketchProfiles.profile(
                new SketchGeometry(List.of(spline, chord), List.of(), List.of("curve", "chord")));
        try (var solid = Solid.profilePrism(profile, 0, 600)) {
            for (var options : List.of(MeshOptions.DEFAULT, new MeshOptions(.005, 2))) {
                var mesh = solid.tessellate(options);
                MeshValidation.requireClosed(mesh);
                assertThat(java.util.Arrays.stream(mesh.faceIndices())
                                .distinct()
                                .count())
                        .isEqualTo(4);
                assertThat(mesh.triangleCount()).isLessThan(1500);
                for (int i = 0; i < mesh.triangleCount(); i++) {
                    int a = mesh.vertexIndex(i, 0), b = mesh.vertexIndex(i, 1), c = mesh.vertexIndex(i, 2);
                    double z = mesh.coordinate(a, 2);
                    if (z == mesh.coordinate(b, 2) && z == mesh.coordinate(c, 2)) continue;
                    double y = (mesh.coordinate(a, 1) + mesh.coordinate(b, 1) + mesh.coordinate(c, 1)) / 3;
                    if (y == 0) continue;
                    double t = (mesh.coordinate(a, 0) + mesh.coordinate(b, 0) + mesh.coordinate(c, 0)) / 36;
                    // The vertical error exceeds the normal error by at most sqrt(1 + 0.5^2).
                    assertThat(y).isCloseTo(6 * t * (1 - t), within(options.chordToleranceMm() * Math.sqrt(1.25)));
                }
            }
        }
    }

    @Test
    void constrainedProfileThroughCutTracksFutureThicknessAndPreservesSource() {
        var g = Sketches.rectangle("slot", 20, 10, 10, 5, true);
        var profile = SketchProfiles.profile(g);
        for (double thickness : new double[] {5, 8}) {
            try (var source = Solid.rectanglePrism(0, 0, 0, 60, 40, thickness);
                    var cut = source.cutThroughProfile(profile)) {
                assertThat(source.volume()).isCloseTo(2400 * thickness, org.assertj.core.data.Offset.offset(1e-6));
                assertThat(cut.volume()).isCloseTo((2400 - 50) * thickness, org.assertj.core.data.Offset.offset(1e-6));
                assertThat(cut.contains(25, 12, thickness / 2)).isFalse();
            }
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
