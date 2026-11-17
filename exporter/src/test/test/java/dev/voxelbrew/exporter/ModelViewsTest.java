package dev.voxelbrew.exporter;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.model.BodyInstance;
import dev.voxelbrew.api.model.CadDocument;
import dev.voxelbrew.api.model.Transform;
import dev.voxelbrew.api.model.mesh.MeshData;
import dev.voxelbrew.api.model.mesh.MeshOptions;
import dev.voxelbrew.api.model.printing.ManufacturingModel;
import dev.voxelbrew.api.model.printing.MeshGeometry;
import dev.voxelbrew.api.model.printing.PrintProfile;
import dev.voxelbrew.api.model.printing.PrintSetup;
import dev.voxelbrew.api.model.printing.SupportVolume;
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
        assertThat(body.bodies().stream().map(ModelView.Body::id).toList()).isEqualTo(List.of("a"));
        assertThat(body.instances().stream().map(BodyInstance::id).toList()).isEqualTo(List.of("a1", "a2"));
        assertThatThrownBy(() -> body.tessellate("b", MeshOptions.DEFAULT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(body.tessellate("a", MeshOptions.DEFAULT)).isNull();

        var instance = ModelViews.selectInstance(source, "a2");
        assertThat(instance.bodies().stream().map(ModelView.Body::id).toList()).isEqualTo(List.of("a"));
        assertThat(instance.instances().stream().map(BodyInstance::id).toList()).isEqualTo(List.of("a2"));
        assertThatThrownBy(() -> instance.tessellate("b", MeshOptions.DEFAULT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(instance.tessellate("a", MeshOptions.DEFAULT)).isNull();
        assertThat(tessellations.get()).isEqualTo(2);

        assertThatThrownBy(() -> ModelViews.select(source, "missing")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ModelViews.selectInstance(source, "missing"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exportSelectionRejectsConflictingOrDesignSpaceOnlyRequests() {
        var document = CadDocument.empty();
        assertThatThrownBy(() -> ExportSelections.view(
                        new ExportSelection(
                                ExportSelection.Space.BUILD_PLATE, ExportSelection.Contents.MODEL, "body", "instance"),
                        document,
                        null,
                        MeshOptions.DEFAULT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExportSelections.view(
                        new ExportSelection(
                                ExportSelection.Space.DESIGN, ExportSelection.Contents.SUPPORTS, null, null),
                        document,
                        null,
                        MeshOptions.DEFAULT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ExportSelection.design("body"))
                .isEqualTo(new ExportSelection(
                        ExportSelection.Space.DESIGN, ExportSelection.Contents.MODEL, "body", null));
    }

    @Test
    void detachedManufacturingViewSelectsModelAndSolidSupports() {
        var instance = new BodyInstance("placed", "body", Transform.translation(10, 20, 0));
        var support = new SupportVolume("manual", "placed", SupportVolume.Role.SOLID_SUPPORT, 0, 0, 0, 2, 2, 5);
        var setup = PrintSetup.empty().withInstances(List.of(instance)).withSupports(List.of(support));
        var model = new ManufacturingModel(
                "doc", "revision", setup, PrintProfile.resolve(setup), Map.of("body", MeshGeometry.box(support)));

        var modelOnly = ModelViews.from(model, ExportSelection.Contents.MODEL);
        assertThat(modelOnly.bodies().stream().map(ModelView.Body::id).toList()).isEqualTo(List.of("body"));
        assertThat(((ManufacturingView) modelOnly).manufacturingModel()).isSameAs(model);

        var supports = ModelViews.from(model, ExportSelection.Contents.SUPPORTS);
        assertThat(supports.bodies().stream().map(ModelView.Body::id).toList()).isEqualTo(List.of("support.manual"));
        assertThat(supports.bodies().getFirst().role()).isEqualTo(ModelView.Role.SOLID_SUPPORT);
        assertThat(supports.instances().getFirst().transform()).isEqualTo(instance.transform());
        assertThat(supports.tessellate("support.manual", MeshOptions.DEFAULT).triangleCount() > 0)
                .isTrue();
        assertThatThrownBy(() -> supports.tessellate("body", MeshOptions.DEFAULT))
                .isInstanceOf(IllegalArgumentException.class);

        var all = ModelViews.from(model, ExportSelection.Contents.ALL);
        assertThat(all.bodies().size()).isEqualTo(2);
        assertThat(all.instances().size()).isEqualTo(2);
    }
}
