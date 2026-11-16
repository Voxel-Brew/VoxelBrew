package dev.voxelbrew.exporter;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.mesh.MeshData;
import dev.voxelbrew.engine.mesh.MeshOptions;
import dev.voxelbrew.engine.model.BodyInstance;
import dev.voxelbrew.engine.model.CadDocument;
import dev.voxelbrew.engine.model.Transform;
import dev.voxelbrew.engine.printing.ManufacturingModel;
import dev.voxelbrew.engine.printing.MeshGeometry;
import dev.voxelbrew.engine.printing.PrintProfile;
import dev.voxelbrew.engine.printing.PrintSetup;
import dev.voxelbrew.engine.printing.SupportVolume;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ModelViewsTest {
    @Test
    void bodyAndInstanceSelectionKeepOnlyTheRequestedGeometry() {
        var tessellations = new AtomicInteger();
        ModelView source = new ModelView() {
            @Override
            public List<Body> bodies() {
                return List.of(new Body("a", "Alpha"), new Body("b", "Beta"));
            }

            @Override
            public List<BodyInstance> instances() {
                return List.of(
                        new BodyInstance("a1", "a", Transform.IDENTITY),
                        new BodyInstance("a2", "a", Transform.IDENTITY),
                        new BodyInstance("b1", "b", Transform.IDENTITY));
            }

            @Override
            public MeshData tessellate(String bodyId, MeshOptions options) {
                tessellations.incrementAndGet();
                return null;
            }
        };

        var body = ModelViews.select(source, "a");
        assertEquals(
                List.of("a"), body.bodies().stream().map(ModelView.Body::id).toList());
        assertEquals(
                List.of("a1", "a2"),
                body.instances().stream().map(BodyInstance::id).toList());
        assertThrows(IllegalArgumentException.class, () -> body.tessellate("b", MeshOptions.DEFAULT));
        assertNull(body.tessellate("a", MeshOptions.DEFAULT));

        var instance = ModelViews.selectInstance(source, "a2");
        assertEquals(
                List.of("a"), instance.bodies().stream().map(ModelView.Body::id).toList());
        assertEquals(
                List.of("a2"),
                instance.instances().stream().map(BodyInstance::id).toList());
        assertThrows(IllegalArgumentException.class, () -> instance.tessellate("b", MeshOptions.DEFAULT));
        assertNull(instance.tessellate("a", MeshOptions.DEFAULT));
        assertEquals(2, tessellations.get());

        assertThrows(IllegalArgumentException.class, () -> ModelViews.select(source, "missing"));
        assertThrows(IllegalArgumentException.class, () -> ModelViews.selectInstance(source, "missing"));
    }

    @Test
    void exportSelectionRejectsConflictingOrDesignSpaceOnlyRequests() {
        var document = CadDocument.empty();
        assertThrows(
                IllegalArgumentException.class,
                () -> ExportSelections.view(
                        new ExportSelection(
                                ExportSelection.Space.BUILD_PLATE, ExportSelection.Contents.MODEL, "body", "instance"),
                        document,
                        null,
                        MeshOptions.DEFAULT));
        assertThrows(
                IllegalArgumentException.class,
                () -> ExportSelections.view(
                        new ExportSelection(
                                ExportSelection.Space.DESIGN, ExportSelection.Contents.SUPPORTS, null, null),
                        document,
                        null,
                        MeshOptions.DEFAULT));
        assertEquals(
                new ExportSelection(ExportSelection.Space.DESIGN, ExportSelection.Contents.MODEL, "body", null),
                ExportSelection.design("body"));
    }

    @Test
    void detachedManufacturingViewSelectsModelAndSolidSupports() {
        var instance = new BodyInstance("placed", "body", Transform.translation(10, 20, 0));
        var support = new SupportVolume("manual", "placed", SupportVolume.Role.SOLID_SUPPORT, 0, 0, 0, 2, 2, 5);
        var setup = PrintSetup.empty().withInstances(List.of(instance)).withSupports(List.of(support));
        var model = new ManufacturingModel(
                "doc", "revision", setup, PrintProfile.resolve(setup), Map.of("body", MeshGeometry.box(support)));

        var modelOnly = ModelViews.from(model, ExportSelection.Contents.MODEL);
        assertEquals(
                List.of("body"),
                modelOnly.bodies().stream().map(ModelView.Body::id).toList());
        assertSame(model, ((ManufacturingView) modelOnly).manufacturingModel());

        var supports = ModelViews.from(model, ExportSelection.Contents.SUPPORTS);
        assertEquals(
                List.of("support.manual"),
                supports.bodies().stream().map(ModelView.Body::id).toList());
        assertEquals(ModelView.Role.SOLID_SUPPORT, supports.bodies().getFirst().role());
        assertEquals(instance.transform(), supports.instances().getFirst().transform());
        assertTrue(supports.tessellate("support.manual", MeshOptions.DEFAULT).triangleCount() > 0);
        assertThrows(IllegalArgumentException.class, () -> supports.tessellate("body", MeshOptions.DEFAULT));

        var all = ModelViews.from(model, ExportSelection.Contents.ALL);
        assertEquals(2, all.bodies().size());
        assertEquals(2, all.instances().size());
    }
}
