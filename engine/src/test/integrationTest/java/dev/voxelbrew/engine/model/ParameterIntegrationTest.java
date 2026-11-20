package dev.voxelbrew.engine.model;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.model.CadDocument;
import dev.voxelbrew.api.model.Feature;
import dev.voxelbrew.api.model.parameters.*;
import dev.voxelbrew.api.model.sketch.*;
import dev.voxelbrew.engine.parameters.*;
import dev.voxelbrew.engine.persistence.DocumentCodec;
import dev.voxelbrew.engine.sketch.*;
import dev.voxelbrew.exporter.DocumentWriter;
import dev.voxelbrew.importer.DocumentReader;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ParameterIntegrationTest {
    @TempDir
    Path directory;

    @Test
    void sharedPlanesSignedExtrusionsAndSplitOffsetsFollowConfigurations() {
        var s = new ModelSession(CadDocument.empty());
        var plane = new Feature.Plane("raised", 10);
        s.add(new Feature.Sketch("plate", "profile", plane, new Feature.Rectangle(0, 0, 60, 40)));
        s.add(new Feature.Sketch("circle", "profile", plane, new Feature.Circle(10, 10, 4)));
        s.add(new Feature.Extrude("solid", new Feature.ProfileRef("plate", "profile"), -5));
        s.add(new Feature.SplitBody(
                "split", new Feature.BodyRef("solid", "body"), new Feature.SplitPlane(1, 0, 0, 30)));
        s.setParameters(ParameterSet.empty()
                .put("elevation", ParameterUnit.MM, "10", "")
                .put("thickness", ParameterUnit.MM, "5", "")
                .put("division", ParameterUnit.MM, "30", "")
                .addConfiguration("Raised", false)
                .override("elevation", "Raised", "20")
                .override("thickness", "Raised", "8")
                .override("division", "Raised", "20"));
        s.bindExpression("circle", "offsetZ", "elevation");
        s.bindExpression("solid", "distance", "-thickness");
        s.bindExpression("split", "offset", "division");
        s.selectConfiguration("Raised");
        try (var e = s.evaluate()) {
            e.requireCompleteDocument();
            assertThat(e.bodies().stream().mapToDouble(b -> b.solid().volume()).sum())
                    .isCloseTo(19200, org.assertj.core.data.Offset.offset(.01));
            var bounds = e.bodies().getFirst().solid().bounds();
            assertThat(bounds.minZ()).isCloseTo(12, org.assertj.core.data.Offset.offset(.001));
            assertThat(bounds.maxZ()).isCloseTo(20, org.assertj.core.data.Offset.offset(.001));
            assertThat(bounds.maxX()).isCloseTo(20, org.assertj.core.data.Offset.offset(.001));
            for (var f : e.resolvedFeatures()) {
                if (f instanceof Feature.Sketch sketch) {
                    assertThat(sketch.plane().offsetZ()).isEqualTo(20);
                }
            }
        }
        s.editDimension("plate", "offsetZ", 30);
        assertThat(s.document().parameters().bindings().stream()
                        .noneMatch(b -> b.target().field().equals("offsetZ")))
                .isTrue();
        try (var e = s.evaluate()) {
            assertThat(e.bodies().getFirst().solid().bounds().maxZ())
                    .isCloseTo(30, org.assertj.core.data.Offset.offset(.001));
        }
    }

    @Test
    void legacyPositionBindingsSurviveSketchConversionAndNumericEditsDetachAliases() {
        for (var profile :
                List.<Feature.Profile>of(new Feature.Rectangle(10, 20, 60, 40), new Feature.Circle(10, 20, 4))) {
            boolean rectangle = profile instanceof Feature.Rectangle;
            var s = new ModelSession(CadDocument.empty());
            var original = new Feature.Sketch("sketch", "profile", new Feature.Plane("origin-xy", 0), profile);
            s.add(original);
            s.add(new Feature.Extrude("solid", new Feature.ProfileRef("sketch", "profile"), 5));
            s.setParameters(ParameterSet.empty()
                    .put("position", ParameterUnit.MM, "10", "")
                    .put("size", ParameterUnit.MM, rectangle ? "60" : "4", "")
                    .addConfiguration("Moved", false)
                    .override("position", "Moved", "-15"));
            s.bindExpression("sketch", rectangle ? "x" : "centerX", "position");
            s.bindExpression("sketch", rectangle ? "y" : "centerY", "position * 2");
            String dimension = rectangle ? "width" : "diameter";
            s.bindExpression("sketch", dimension, "size");
            var converted = FeatureDimensions.convert(original, s.document().parameters());
            s.replace(
                    new Feature.Sketch(
                            original.id(),
                            original.profileId(),
                            original.plane(),
                            new Feature.Constrained(converted.geometry())),
                    converted.parameters());
            s.selectConfiguration("Moved");
            try (var e = s.evaluate()) {
                var bounds = e.requireCompleteDocument().getFirst().solid().bounds();
                assertThat(bounds.minX()).isCloseTo(rectangle ? -15 : -17, org.assertj.core.data.Offset.offset(.001));
                assertThat(bounds.minY()).isCloseTo(rectangle ? -30 : -32, org.assertj.core.data.Offset.offset(.001));
            }
            s.editDimension("sketch", dimension, rectangle ? 70 : 6);
            assertThat(s.document().parameters().binding(new ParameterSet.Target("sketch", "constraint:" + dimension)))
                    .isEqualTo("");
            try (var e = s.evaluate()) {
                assertThat(e.requireCompleteDocument().getFirst().solid().volume())
                        .isCloseTo(rectangle ? 14000 : Math.PI * 9 * 5, org.assertj.core.data.Offset.offset(.01));
            }
        }
    }

    @Test
    void configurationRebuildsReferencePartAndPersistsStableIds() throws Exception {
        var s = new ModelSession(CadDocument.empty());
        s.add(new Feature.Sketch(
                "plate", "profile", new Feature.Plane("origin-xy", 0), new Feature.Rectangle(0, 0, 60, 40)));
        s.add(new Feature.Extrude("thickness", new Feature.ProfileRef("plate", "profile"), 5));
        s.add(new Feature.Sketch("hole", "profile", new Feature.Plane("origin-xy", 0), new Feature.Circle(10, 10, 4)));
        s.add(new Feature.ThroughCut(
                "cut", new Feature.ProfileRef("hole", "profile"), new Feature.BodyRef("thickness", "body")));
        s.setParameters(ParameterSet.empty()
                .put("width", ParameterUnit.MM, "60", "")
                .put("height", ParameterUnit.MM, "40", "")
                .put("thickness", ParameterUnit.MM, "5", "")
                .put("holeDiameter", ParameterUnit.MM, "4", "")
                .addConfiguration("Thick", false)
                .override("thickness", "Thick", "8"));
        for (var binding : Map.of(
                        "plate/width",
                        "width",
                        "plate/height",
                        "height",
                        "thickness/distance",
                        "thickness",
                        "hole/centerX",
                        "width/6",
                        "hole/centerY",
                        "height/4",
                        "hole/diameter",
                        "holeDiameter")
                .entrySet()) {
            var target = binding.getKey().split("/");
            s.bindExpression(target[0], target[1], binding.getValue());
        }
        try (var e = s.evaluate()) {
            assertThat(e.bodies().getFirst().solid().volume())
                    .isCloseTo((2400 - Math.PI * 4) * 5, org.assertj.core.data.Offset.offset(1e-6));
        }
        var ids = s.document().features().stream().map(Feature::id).toList();
        String before = DocumentCodec.fingerprint(s.document());
        s.selectConfiguration("Thick");
        assertThat(DocumentCodec.fingerprint(s.document())).isNotEqualTo(before);
        var file = directory.resolve("parameter versions.vxb");
        DocumentWriter.save(s.document(), file, false);
        assertThat(DocumentReader.load(file)).isEqualTo(s.document());
        try (var e = new ModelSession(DocumentReader.load(file)).evaluate()) {
            e.requireCompleteDocument();
            var body = e.bodies().getFirst();
            assertThat(body.id()).isEqualTo("thickness.body");
            assertThat(body.solid().volume()).isCloseTo(19099.469035085127, org.assertj.core.data.Offset.offset(.01));
            assertThat(body.solid().contains(10, 10, 4)).isFalse();
            assertThat(body.solid().bounds().maxZ()).isCloseTo(8, org.assertj.core.data.Offset.offset(.001));
            assertThat(e.resolvedFeatures().stream().map(Feature::id).toList()).isEqualTo(ids);
        }
        s.setCursor(3);
        try (var e = s.evaluate()) {
            assertThat(e.bodies().getFirst().solid().volume())
                    .isCloseTo(19200, org.assertj.core.data.Offset.offset(.01));
        }
        assertThat(s.undo()).isTrue();
        assertThat(s.document().cursor()).isEqualTo(3);
        try (var e = s.evaluate()) {
            assertThat(e.bodies().getFirst().solid().volume())
                    .isCloseTo(12000, org.assertj.core.data.Offset.offset(.01));
        }
    }

    @Test
    void relatedSketchDimensionsResolveSimultaneouslyAndFailedVariantsStayRepairable() {
        var g = Sketches.rectangle("r", 0, 0, 40, 40, true);
        g = g.add(SketchConstraints.create(
                g,
                "square",
                SketchGeometry.Type.EQUAL,
                List.of(g.entity("r-bottom").ref(), g.entity("r-right").ref()),
                0));
        var s = new ModelSession(CadDocument.empty());
        s.add(new Feature.Sketch("square", "profile", new Feature.Plane("origin-xy", 0), new Feature.Constrained(g)));
        s.add(new Feature.Extrude("solid", new Feature.ProfileRef("square", "profile"), 2));
        s.setParameters(ParameterSet.empty()
                .put("size", ParameterUnit.MM, "40", "")
                .addConfiguration("Large", false)
                .override("size", "Large", "80")
                .addConfiguration("Invalid", false)
                .override("size", "Invalid", "0"));
        s.bindExpression("square", "constraint:width", "size");
        s.bindExpression("square", "constraint:height", "size");
        s.selectConfiguration("Large");
        try (var e = s.evaluate()) {
            assertThat(e.bodies().getFirst().solid().volume())
                    .isCloseTo(12800, org.assertj.core.data.Offset.offset(.01));
        }
        s.selectConfiguration("Invalid");
        try (var e = s.evaluate()) {
            assertThat(e.valid()).isFalse();
            assertThatThrownBy(e::bodies).isInstanceOf(IllegalStateException.class);
            assertThat(e.diagnostics().getLast().state()).isEqualTo(Evaluation.State.BLOCKED);
        }
        s.undo();
        try (var e = s.evaluate()) {
            assertThat(e.bodies().getFirst().solid().volume())
                    .isCloseTo(12800, org.assertj.core.data.Offset.offset(.01));
        }
    }
}
