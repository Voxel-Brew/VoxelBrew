package dev.voxelbrew.exporter.printing;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.model.BodyInstance;
import dev.voxelbrew.api.model.Transform;
import dev.voxelbrew.api.model.printing.ManufacturingModel;
import dev.voxelbrew.api.model.printing.MeshGeometry;
import dev.voxelbrew.api.model.printing.PrintProfile;
import dev.voxelbrew.api.model.printing.PrintSetup;
import dev.voxelbrew.api.model.printing.SupportVolume;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PrusaProjectExporterTest {
    private static ManufacturingModel model(PrintSetup setup) {
        var shape = new SupportVolume("shape", "part", SupportVolume.Role.SOLID_SUPPORT, 0, 0, 0, 5, 5, 5);
        return new ManufacturingModel(
                "doc", "revision", setup, PrintProfile.resolve(setup), Map.of("body", MeshGeometry.box(shape)));
    }

    @Test
    void configurationEnablesExplicitEnforcersWithoutAutomaticSupports() throws Exception {
        var instance = new BodyInstance("part", "body", Transform.IDENTITY);
        var enforcer = new SupportVolume("required", "part", SupportVolume.Role.ENFORCER, 0, 0, 0, 2, 2, 3);
        var setup = PrintSetup.empty().withInstances(List.of(instance)).withSupports(List.of(enforcer));
        var model = model(setup);

        var settings = PrusaConfiguration.settings(model);
        assertThat(settings.get("support_material")).isEqualTo("1");
        assertThat(settings.get("support_material_auto")).isEqualTo("0");
        assertThat(PrusaConfiguration.ini(model).contains("support_material = 1\n"))
                .isTrue();
        assertThatThrownBy(() -> settings.put("support_material", "0"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void volumeRolesFollowDocumentSupports() {
        var instance = new BodyInstance("part", "body", Transform.translation(10, 20, 0));
        var supports = List.of(
                new SupportVolume("block", "part", SupportVolume.Role.BLOCKER, 0, 0, 0, 2, 2, 3),
                new SupportVolume("enforce", "part", SupportVolume.Role.ENFORCER, 0, 0, 0, 2, 2, 3),
                new SupportVolume("solid", "part", SupportVolume.Role.SOLID_SUPPORT, 0, 0, 0, 2, 2, 3));
        var setup = PrintSetup.empty().withInstances(List.of(instance)).withSupports(supports);

        var volumes = PrusaProjectExporter.volumes(model(setup), instance);
        assertThat(volumes.stream().map(PrusaProjectExporter.Volume::type).toList())
                .isEqualTo(List.of("ModelPart", "SupportBlocker", "SupportEnforcer", "ModelPart"));
        assertThat(volumes.stream().map(PrusaProjectExporter.Volume::name).toList())
                .isEqualTo(List.of("part/body", "part/block", "part/enforce", "part/solid"));
        assertThat(volumes.stream().allMatch(volume -> volume.mesh().triangleCount() > 0))
                .isTrue();
    }
}
