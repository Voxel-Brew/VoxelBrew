package dev.voxelbrew.engine.geometry;

import static dev.voxelbrew.api.model.sketch.SketchGeometry.*;
import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.model.*;
import dev.voxelbrew.api.model.sketch.*;
import dev.voxelbrew.engine.model.*;
import dev.voxelbrew.engine.sketch.*;
import dev.voxelbrew.exporter.DocumentWriter;
import dev.voxelbrew.importer.DocumentReader;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SketchCurveTypesIntegrationTest {
    @TempDir
    Path directory;

    private SketchGeometry geometry(Entity... entities) {
        return new SketchGeometry(
                List.of(entities),
                List.of(),
                Arrays.stream(entities).map(Entity::id).toList());
    }

    @Test
    void straightAndArcSlotsMatchAnalyticAreaAndRetainShapeOnWidthEdits() {
        var options = SketchTools.Options.defaults();
        var g = SketchTools.add(
                SketchGeometry.empty(),
                SketchTools.Tool.SLOT_CENTER_TO_CENTER,
                "slot",
                List.of(new Vec(0, 0), new Vec(20, 0), new Vec(10, 2)),
                List.of(),
                options);
        try (var solid = Solid.profilePrism(SketchProfiles.profile(g), 0, 3)) {
            assertThat(solid.volume()).isCloseTo((80 + 4 * Math.PI) * 3, org.assertj.core.data.Offset.offset(1e-6));
        }
        g = g.add(SketchConstraints.create(g, "width", Type.RADIUS, List.of(new Ref("slot-end", Part.OBJECT)), 2));
        var edited = Sketches.editDimension(g, "width", 3);
        SketchConstraints.requireSatisfied(edited);
        try (var solid = Solid.profilePrism(SketchProfiles.profile(edited), 0, 3)) {
            assertThat(solid.volume() > 0).isTrue();
        }
        g = SketchTools.add(
                SketchGeometry.empty(),
                SketchTools.Tool.SLOT_ARC_CENTER,
                "slot",
                List.of(new Vec(0, 0), new Vec(10, 0), new Vec(0, 10), new Vec(12, 0)),
                List.of(),
                options);
        try (var solid = Solid.profilePrism(SketchProfiles.profile(g), 0, 3)) {
            assertThat(solid.volume())
                    .isCloseTo((20 * Math.PI + 4 * Math.PI) * 3, org.assertj.core.data.Offset.offset(1e-6));
        }
    }

    @Test
    void exactEllipseAndRationalConicVolumesMatchAnalyticSolutions() {
        int handles = NativeKernel.liveHandles();
        for (double angle : new double[] {0, .7}) {
            var e = Sketches.entity("ellipse", Kind.ELLIPSE, false, 3, 4, 10, 5, angle);
            try (var solid = Solid.profilePrism(SketchProfiles.profile(geometry(e)), 2, -3)) {
                assertThat(solid.volume()).isCloseTo(Math.PI * 50 * 3, org.assertj.core.data.Offset.offset(1e-6));
                assertThat(solid.contains(3, 4, 0)).isTrue();
            }
        }
        double weight = Math.sqrt(.5);
        var arc = Sketches.entity("conic", Kind.CONIC, false, 10, 0, 10, 10, 0, 10, weight / (1 + weight));
        var a = Sketches.entity("a", Kind.LINE, false, 0, 10, 0, 0);
        var b = Sketches.entity("b", Kind.LINE, false, 0, 0, 10, 0);
        try (var solid = Solid.profilePrism(SketchProfiles.profile(geometry(arc, a, b)), 0, 2)) {
            assertThat(solid.volume()).isCloseTo(Math.PI * 50, org.assertj.core.data.Offset.offset(1e-6));
        }
        assertThat(NativeKernel.liveHandles()).isEqualTo(handles);
    }

    @Test
    void exactControlAndFitSplinesProduceClosedKernelProfiles() {
        int handles = NativeKernel.liveHandles();
        for (var kind : List.of(Kind.CONTROL_SPLINE, Kind.FIT_SPLINE)) {
            var e = new Entity(
                    "spline",
                    kind,
                    List.of(0., 0., 0., 10., 10., 20., 20., 20., 30., 10., 30., 0.),
                    false,
                    kind == Kind.CONTROL_SPLINE ? 5 : 0,
                    true,
                    null);
            try (var solid = Solid.profilePrism(SketchProfiles.profile(geometry(e)), 0, 2)) {
                assertThat(solid.volume() > 100).isTrue();
                assertThat(solid.contains(15, 10, 1)).isTrue();
            }
        }
        assertThat(NativeKernel.liveHandles()).isEqualTo(handles);
    }

    @Test
    void mirrorTextAcrossEachAxisAndExtrudeAfterReopening() throws Exception {
        var source = SketchText.create("label", "LF", "SansSerif", 0, 0, 0, 10, 0, .1, "", false);
        for (var mirror :
                List.of(new TextMirror(true, false), new TextMirror(false, true), new TextMirror(true, true))) {
            var text = mirror.apply(source);
            var expectedGlyph = source.text().glyphs().get(mirror.horizontal() ? 1 : 0);
            var original = expectedGlyph.contours().getFirst().getFirst();
            var reflected =
                    text.text().glyphs().getFirst().contours().getFirst().getFirst();
            assertThat(reflected.value(0))
                    .isEqualTo(mirror.horizontal() ? expectedGlyph.advance() - original.value(0) : original.value(0));
            assertThat(reflected.value(1)).isEqualTo(mirror.vertical() ? -original.value(1) : original.value(1));
            var session = new ModelSession(CadDocument.empty());
            session.add(new Feature.Sketch(
                    "sketch", "profile", new Feature.Plane("origin-xy", 0), new Feature.Constrained(geometry(text))));
            session.add(new Feature.Extrude("extrude", new Feature.ProfileRef("sketch", "profile"), 2));
            var file = directory.resolve("mirror-" + mirror.horizontal() + "-" + mirror.vertical() + ".vxb");
            DocumentWriter.save(session.document(), file, false);
            var loaded = DocumentReader.load(file);
            assertThat(loaded).isEqualTo(session.document());
            var profile =
                    (Feature.Constrained) ((Feature.Sketch) loaded.features().getFirst()).profile();
            assertThat(TextMirror.of(profile.geometry().entity("label"))).isEqualTo(mirror);
            try (var result = new ModelSession(loaded).evaluate()) {
                result.requireCompleteDocument();
                assertThat(result.bodies()).hasSize(2);
            }
        }
    }

    @Test
    void rectangleWithTextExtrudesSelectedLetterStrokesAndCountersAfterReopening() throws Exception {
        var text = SketchText.create("label", "OB", "SansSerif", 0, 0, 0, 10, 0, .1, "", false);
        var rectangle = Sketches.rectangle("frame", -10, -10, 60, 40, false);
        var entities = new ArrayList<>(rectangle.entities());
        entities.add(text);
        var combined = geometry(entities.toArray(Entity[]::new));
        var faces = SketchProfiles.selectableRegions(combined);
        assertThat(faces).hasSize(6);
        String frame = SketchProfiles.selectableRegions(rectangle).getFirst().id();
        var letters = SketchProfiles.selectableRegions(geometry(text));
        var strokes =
                letters.stream().filter(r -> r.profile().loops().size() > 1).toList();
        var counters =
                letters.stream().filter(r -> r.profile().loops().size() == 1).toList();
        assertThat(strokes).hasSize(2);
        assertThat(counters).hasSize(3);
        double textVolume = 0;
        for (var stroke : strokes) {
            try (var solid = Solid.profilePrism(stroke.profile(), 0, 2)) {
                textVolume += solid.volume();
            }
        }
        var cutout = new ArrayList<>(List.of(frame));
        counters.forEach(r -> cutout.add(r.id()));
        var sketch = new Feature.Sketch("s", "p", new Feature.Plane("origin-xy", 0), new Feature.Constrained(combined));
        var selections = List.of(
                strokes.stream().map(SketchProfiles.Region::id).toList(),
                cutout,
                List.copyOf(SketchProfiles.includingEnclosedRegions(combined, List.of(frame))));
        var volumes = List.of(textVolume, 4800 - textVolume, 4800.0);
        var bodies = List.of(2, 4, 1);
        for (int i = 0; i < selections.size(); i++) {
            var session = new ModelSession(CadDocument.empty());
            session.add(sketch);
            String profile = SketchProfiles.selectedProfileId("p", selections.get(i));
            session.add(new Feature.Extrude("extrude", new Feature.ProfileRef("s", profile), 2));
            var file = directory.resolve("text-selection-" + i + ".vxb");
            DocumentWriter.save(session.document(), file, false);
            try (var result = new ModelSession(DocumentReader.load(file)).evaluate()) {
                result.requireCompleteDocument();
                assertThat(result.bodies()).hasSize(bodies.get(i));
                assertThat(result.bodies().stream()
                                .mapToDouble(b -> b.solid().volume())
                                .sum())
                        .isCloseTo(volumes.get(i), within(1e-5));
            }
        }
    }

    @Test
    void textOutlinesRoundTripAndExtrudeIntoIndependentBodiesWithHoles() throws Exception {
        int handles = NativeKernel.liveHandles();
        var text = SketchText.create("label", "OB", "SansSerif", 0, 0, 0, 10, 0, .1, "", false);
        var g = geometry(text);
        var edited = SketchText.create("label", "IB", "SansSerif", 0, 0, 0, 10, 0, .1, "", false);
        assertThat(geometry(edited).curves(edited).stream()
                        .map(Entity::id)
                        .filter(id -> id.contains("glyph-1-"))
                        .toList())
                .isEqualTo(g.curves(text).stream()
                        .map(Entity::id)
                        .filter(id -> id.contains("glyph-1-"))
                        .toList());
        var longId = SketchText.create("t".repeat(128), "OB", "SansSerif", 0, 0, 0, 10, 0, .1, "", false);
        assertThat(SketchProfiles.regions(geometry(longId)).size()).isEqualTo(2);
        var session = new ModelSession(CadDocument.empty());
        session.add(
                new Feature.Sketch("sketch", "profile", new Feature.Plane("origin-xy", 0), new Feature.Constrained(g)));
        session.add(new Feature.Extrude("extrude", new Feature.ProfileRef("sketch", "profile"), 2));
        var file = directory.resolve("text and curves.vxb");
        DocumentWriter.save(session.document(), file, false);
        assertThat(DocumentReader.load(file)).isEqualTo(session.document());
        try (var evaluation = new ModelSession(DocumentReader.load(file)).evaluate()) {
            evaluation.requireCompleteDocument();
            assertThat(evaluation.bodies().size()).isEqualTo(2);
            assertThat(evaluation.bodies().getFirst().id()).isEqualTo("extrude.body");
            assertThat(evaluation.bodies().stream()
                                    .mapToDouble(b -> b.solid().volume())
                                    .sum()
                            > 20)
                    .isTrue();
            assertThat(evaluation.bodies().stream()
                            .map(Evaluation.Body::id)
                            .distinct()
                            .count())
                    .isEqualTo(2);
        }
        var path = Sketches.entity("path", Kind.ARC, true, 0, 0, 30, 0, Math.PI);
        var onPath = SketchText.create("path-text", "CAD", "SansSerif", 0, 5, 1, 4, 0, 0, path.id(), false);
        var pathGeometry = new SketchGeometry(List.of(path, onPath), List.of(), List.of(onPath.id()));
        assertThat(SketchProfiles.regions(pathGeometry).size()).isEqualTo(3);
        var changed = pathGeometry.replace(path.withValues(List.of(0., 0., 40., 0., Math.PI)));
        assertThat(changed.curves(onPath)).isNotEqualTo(pathGeometry.curves(onPath));
        var shortPath = Sketches.entity("path", Kind.LINE, true, 0, 0, 5, 0);
        var oversized = SketchText.create("path-text", "W", "SansSerif", 0, 0, 0, 10, 0, 0, "path", false);
        var overflow = new SketchGeometry(List.of(shortPath, oversized), List.of(), List.of(oversized.id()));
        assertThatThrownBy(() -> overflow.curves(oversized)).isInstanceOf(IllegalArgumentException.class);
        assertThat(NativeKernel.liveHandles()).isEqualTo(handles);
    }
}
