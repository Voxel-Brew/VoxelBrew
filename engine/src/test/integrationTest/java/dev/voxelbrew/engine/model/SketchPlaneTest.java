package dev.voxelbrew.engine.model;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.model.CadDocument;
import dev.voxelbrew.api.model.FaceSupport;
import dev.voxelbrew.api.model.Feature;
import dev.voxelbrew.api.model.Transform;
import dev.voxelbrew.api.model.mesh.MeshOptions;
import dev.voxelbrew.engine.geometry.Solid;
import dev.voxelbrew.exporter.DocumentWriter;
import dev.voxelbrew.importer.DocumentReader;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SketchPlaneTest {
    @TempDir
    Path directory;

    private static ModelSession plate(Feature.Plane plane, double distance) {
        var session = new ModelSession(CadDocument.empty());
        session.add(new Feature.Sketch("plate", "profile", plane, new Feature.Rectangle(0, 0, 60, 40)));
        session.add(new Feature.Extrude("thickness", new Feature.ProfileRef("plate", "profile"), distance));
        return session;
    }

    @Test
    void everyOriginPlaneSupportsSignedExtrusionThroughCutsAndPersistence() throws Exception {
        for (var plane : List.of(
                Feature.Plane.origin("XY"),
                Feature.Plane.origin("XZ"),
                Feature.Plane.origin("YZ"),
                new Feature.Plane(
                        "tilted", 4, Transform.translation(11, -5, 7).compose(Transform.rotation(17, 33, 47)), null)))
            for (double distance : new double[] {5, -8}) {
                var session = plate(plane, distance);
                session.add(new Feature.Sketch("hole", "hole.profile", plane, new Feature.Circle(10, 10, 4)));
                session.add(new Feature.ThroughCut(
                        "cut",
                        new Feature.ProfileRef("hole", "hole.profile"),
                        new Feature.BodyRef("thickness", "body")));
                var file = directory.resolve(plane.id() + distance + ".vxb");
                DocumentWriter.save(session.document(), file, false);
                var reopened = new ModelSession(DocumentReader.load(file));
                assertThat(reopened.document()).isEqualTo(session.document());
                try (var result = reopened.evaluate()) {
                    assertThat(result.valid())
                            .as(result.diagnostics().toString())
                            .isTrue();
                    var solid = result.bodies().getFirst().solid();
                    assertThat(solid.volume())
                            .isCloseTo(
                                    (2400 - Math.PI * 4) * Math.abs(distance),
                                    org.assertj.core.data.Offset.offset(.01));
                    var inside = plane.placement().apply(30, 20, distance / 2);
                    var hole = plane.placement().apply(10, 10, distance / 2);
                    assertThat(solid.contains(inside[0], inside[1], inside[2])).isTrue();
                    assertThat(solid.contains(hole[0], hole[1], hole[2])).isFalse();
                    var bounds = solid.bounds();
                    double[] low = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
                    double[] high = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
                    for (double x : new double[] {0, 60})
                        for (double y : new double[] {0, 40})
                            for (double z : new double[] {0, distance}) {
                                var p = plane.placement().apply(x, y, z);
                                for (int a = 0; a < 3; a++) {
                                    low[a] = Math.min(low[a], p[a]);
                                    high[a] = Math.max(high[a], p[a]);
                                }
                            }
                    assertThat(new double[] {bounds.minX(), bounds.minY(), bounds.minZ()})
                            .usingComparatorWithPrecision(.001)
                            .containsExactly(low);
                    assertThat(new double[] {bounds.maxX(), bounds.maxY(), bounds.maxZ()})
                            .usingComparatorWithPrecision(.001)
                            .containsExactly(high);
                }
            }
    }

    @Test
    void attachmentFollowsThicknessAfterReopenAndCanBeRepairedWithOneUndo() throws Exception {
        var session = plate(Feature.Plane.origin("XY"), 5);
        Feature.Plane attachment;
        try (var result = session.evaluate()) {
            var body = result.bodies().getFirst();
            var faces = body.solid().planarFaces();
            int top = faces.stream()
                    .filter(f -> f.nz() > .99)
                    .findFirst()
                    .orElseThrow()
                    .faceIndex();
            attachment = FaceSupport.attach(body.output(), faces, top);
        }
        session.add(new Feature.Sketch("attached", "attached.profile", attachment, new Feature.Rectangle(5, 5, 10, 8)));
        session.add(new Feature.Extrude("boss", new Feature.ProfileRef("attached", "attached.profile"), 3));
        var file = directory.resolve("attached sketch.vxb");
        DocumentWriter.save(session.document(), file, false);
        session = new ModelSession(DocumentReader.load(file));
        session.editDimension("thickness", "distance", 8);
        try (var result = session.evaluate()) {
            assertThat(result.valid()).as(result.diagnostics().toString()).isTrue();
            var boss = result.bodies().stream()
                    .filter(b -> b.id().equals("boss.body"))
                    .findFirst()
                    .orElseThrow()
                    .solid();
            assertThat(boss.bounds().minZ()).isCloseTo(8, org.assertj.core.data.Offset.offset(.001));
            assertThat(boss.bounds().maxZ()).isCloseTo(11, org.assertj.core.data.Offset.offset(.001));
            assertThat(boss.volume()).isCloseTo(240, org.assertj.core.data.Offset.offset(.001));
        }
        var old = (Feature.Sketch) session.document().features().get(2);
        var broken = new FaceSupport(new Feature.BodyRef("missing", "body"), 0, 0, 1, 0, 1);
        session.replace(new Feature.Sketch(
                old.id(),
                old.profileId(),
                new Feature.Plane(attachment.id(), 0, attachment.frame(), broken),
                old.profile()));
        try (var result = session.evaluate()) {
            assertThat(result.valid()).isFalse();
        }
        var invalid = session.document();
        session.replace(old);
        try (var result = session.evaluate()) {
            assertThat(result.valid()).isTrue();
        }
        session.undo();
        assertThat(session.document()).isEqualTo(invalid);
        session.redo();
        try (var result = session.evaluate()) {
            assertThat(result.valid()).isTrue();
        }
    }

    @Test
    void exactPlanarFacesMatchMeshFacesAndTransformsKeepSourceImmutable() {
        var frame = Transform.translation(3, -7, 11).compose(Transform.rotation(21, 33, 47));
        try (var source = Solid.circlePrism(0, 0, 0, 20, 7);
                var moved = source.transformed(frame)) {
            assertThat(source.bounds().minZ()).isCloseTo(0, org.assertj.core.data.Offset.offset(.001));
            assertThat(source.bounds().maxZ()).isCloseTo(7, org.assertj.core.data.Offset.offset(.001));
            assertThat(moved.volume()).isCloseTo(source.volume(), org.assertj.core.data.Offset.offset(.001));
            var faces = moved.planarFaces();
            assertThat(faces.size())
                    .as("A cylinder side must not be offered as a sketch plane")
                    .isEqualTo(2);
            var mesh = moved.tessellate(MeshOptions.DEFAULT);
            for (var face : faces) {
                int triangles = 0;
                for (int t = 0; t < mesh.triangleCount(); t++)
                    if (mesh.faceIndex(t) == face.faceIndex()) {
                        triangles++;
                        for (int c = 0; c < 3; c++) {
                            int v = mesh.vertexIndex(t, c);
                            assertThat(face.nx() * mesh.coordinate(v, 0)
                                            + face.ny() * mesh.coordinate(v, 1)
                                            + face.nz() * mesh.coordinate(v, 2))
                                    .isCloseTo(face.offset(), org.assertj.core.data.Offset.offset(1e-7));
                        }
                    }
                assertThat(triangles > 0).isTrue();
                var p = face.frame().apply(2, 3, 0);
                assertThat(face.nx() * p[0] + face.ny() * p[1] + face.nz() * p[2])
                        .isCloseTo(face.offset(), org.assertj.core.data.Offset.offset(1e-9));
            }
            try (var restored = moved.transformed(frame.inverse())) {
                assertThat(restored.volume()).isCloseTo(source.volume(), org.assertj.core.data.Offset.offset(.001));
                assertThat(restored.bounds().maxX())
                        .isCloseTo(source.bounds().maxX(), org.assertj.core.data.Offset.offset(.001));
                assertThat(restored.bounds().maxZ())
                        .isCloseTo(source.bounds().maxZ(), org.assertj.core.data.Offset.offset(.001));
            }
        }
    }
}
