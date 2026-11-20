package dev.voxelbrew.engine.model;

import static dev.voxelbrew.api.model.units.Measurements.*;
import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.model.CadDocument;
import dev.voxelbrew.api.model.Feature;
import dev.voxelbrew.api.model.Transform;
import dev.voxelbrew.api.model.mesh.MeshOptions;
import dev.voxelbrew.engine.geometry.Solid;
import dev.voxelbrew.engine.persistence.proto.DocumentProto;
import dev.voxelbrew.exporter.DocumentWriter;
import dev.voxelbrew.importer.DocumentReader;
import dev.voxelbrew.util.Version;
import java.nio.file.*;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MeasurementIntegrationTest {
    @TempDir
    Path directory;

    @Test
    void typedKernelInputsAndMeasurementsUseCanonicalMillimeters() {
        var zero = quantity(0, MILLIMETRE);
        try (var plate = Solid.rectanglePrism(
                        zero, zero, zero, quantity(6, CENTIMETRE), quantity(.04, METRE), quantity(.8, CENTIMETRE));
                var part = plate.cutThroughCircle(
                        quantity(1, CENTIMETRE), quantity(10, MILLIMETRE), quantity(.4, CENTIMETRE))) {
            assertThat(value(part.volumeQuantity(), CUBIC_CENTIMETRE))
                    .isCloseTo(19.099469035085127, org.assertj.core.data.Offset.offset(.00001));
            assertThat(value(part.bounds().width(), CENTIMETRE))
                    .isCloseTo(6, org.assertj.core.data.Offset.offset(1e-4));
            assertThat(value(part.bounds().height(), MILLIMETRE))
                    .isCloseTo(40, org.assertj.core.data.Offset.offset(.001));
            assertThat(value(part.bounds().depth(), MILLIMETRE))
                    .isCloseTo(8, org.assertj.core.data.Offset.offset(.001));
            assertThat(part.contains(quantity(1, CENTIMETRE), quantity(1, CENTIMETRE), quantity(.004, METRE)))
                    .isFalse();
            assertThat(part.tessellate(new MeshOptions(quantity(.002, CENTIMETRE), quantity(Math.PI / 36, RADIAN)))
                                    .triangleCount()
                            > 12)
                    .isTrue();
        }
        var session = new ModelSession(CadDocument.empty());
        var xy = new Feature.Plane("origin-xy", zero);
        session.add(new Feature.Sketch(
                "plate",
                "profile",
                xy,
                new Feature.Rectangle(zero, zero, quantity(6, CENTIMETRE), quantity(40, MILLIMETRE))));
        session.add(
                new Feature.Extrude("thickness", new Feature.ProfileRef("plate", "profile"), quantity(.5, CENTIMETRE)));
        session.add(new Feature.Sketch(
                "hole",
                "profile",
                xy,
                new Feature.Circle(quantity(1, CENTIMETRE), quantity(1, CENTIMETRE), quantity(.4, CENTIMETRE))));
        session.add(new Feature.ThroughCut(
                "cut", new Feature.ProfileRef("hole", "profile"), new Feature.BodyRef("thickness", "body")));
        session.editDimension("thickness", "distance", quantity(.8, CENTIMETRE));
        var before = session.snapshot();
        assertThatThrownBy(() -> session.editDimension("thickness", "distance", quantity(8, DEGREE)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(session.snapshot()).isEqualTo(before);
        try (var e = session.evaluate()) {
            assertThat(e.bodies().getFirst().solid().volume())
                    .isCloseTo(19099.469035085127, org.assertj.core.data.Offset.offset(.01));
        }
    }

    @Test
    void currentFixturePreservesMeasurementsWhenSavingWithCurrentWriterVersion() throws Exception {
        byte[] original;
        try (var resource = getClass().getResourceAsStream("/parameters/schema10-configurations.vxb")) {
            assertThat(resource).isNotNull();
            original = resource.readAllBytes();
        }
        var file = directory.resolve("old parameter versions.vxb");
        Files.write(file, original);
        var document = DocumentReader.load(file);
        assertThat(document.parameters().activeName()).isEqualTo("Large");
        var session = new ModelSession(document);
        try (var e = session.evaluate()) {
            assertThat(e.bodies().getFirst().solid().volume())
                    .isCloseTo(25600, org.assertj.core.data.Offset.offset(.02));
        }
        DocumentWriter.save(document, file, true);
        byte[] saved = Files.readAllBytes(file);
        var before = DocumentProto.Document.parseFrom(Arrays.copyOfRange(original, 44, original.length));
        var after = DocumentProto.Document.parseFrom(Arrays.copyOfRange(saved, 44, saved.length));
        var migrated = before.toBuilder()
                .setSchemaVersion(dev.voxelbrew.engine.persistence.DocumentCodec.SCHEMA_VERSION)
                .setApplicationVersion(Version.version())
                .setFileVersion(before.getFileVersion() + 1);
        for (var feature : migrated.getFeaturesBuilderList()) {
            if (feature.hasSketch()) {
                feature.getSketchBuilder()
                        .getPlaneBuilder()
                        .addAllFrame(Arrays.stream(Transform.IDENTITY.matrix())
                                .boxed()
                                .toList());
            }
        }
        assertThat(after).isEqualTo(migrated.build());
        assertThat(DocumentReader.load(file)).isEqualTo(document);
        session.selectConfiguration("Default");
        try (var e = session.evaluate()) {
            assertThat(e.bodies().getFirst().solid().volume())
                    .isCloseTo(12000, org.assertj.core.data.Offset.offset(.02));
        }
    }
}
