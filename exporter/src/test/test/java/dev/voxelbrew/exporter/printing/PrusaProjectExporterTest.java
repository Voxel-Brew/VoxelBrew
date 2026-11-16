package dev.voxelbrew.exporter.printing;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.model.BodyInstance;
import dev.voxelbrew.engine.model.Transform;
import dev.voxelbrew.engine.printing.ManufacturingModel;
import dev.voxelbrew.engine.printing.MeshGeometry;
import dev.voxelbrew.engine.printing.PrintProfile;
import dev.voxelbrew.engine.printing.PrintSetup;
import dev.voxelbrew.engine.printing.SupportVolume;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PrusaProjectExporterTest {
    @Test
    void configurationEnablesExplicitEnforcersWithoutAutomaticSupports() throws Exception {
        var instance = new BodyInstance("part", "body", Transform.IDENTITY);
        var enforcer = new SupportVolume("required", "part", SupportVolume.Role.ENFORCER, 0, 0, 0, 2, 2, 3);
        var setup = PrintSetup.empty().withInstances(List.of(instance)).withSupports(List.of(enforcer));
        var model = model(setup);

        var settings = PrusaConfiguration.settings(model);
        assertEquals("1", settings.get("support_material"));
        assertEquals("0", settings.get("support_material_auto"));
        assertTrue(PrusaConfiguration.ini(model).contains("support_material = 1\n"));
        assertThrows(UnsupportedOperationException.class, () -> settings.put("support_material", "0"));
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
        assertEquals(
                List.of("ModelPart", "SupportBlocker", "SupportEnforcer", "ModelPart"),
                volumes.stream().map(PrusaProjectExporter.Volume::type).toList());
        assertEquals(
                List.of("part/body", "part/block", "part/enforce", "part/solid"),
                volumes.stream().map(PrusaProjectExporter.Volume::name).toList());
        assertTrue(volumes.stream().allMatch(volume -> volume.mesh().triangleCount() > 0));
    }

    private static ManufacturingModel model(PrintSetup setup) {
        var shape = new SupportVolume("shape", "part", SupportVolume.Role.SOLID_SUPPORT, 0, 0, 0, 5, 5, 5);
        return new ManufacturingModel(
                "doc", "revision", setup, PrintProfile.resolve(setup), Map.of("body", MeshGeometry.box(shape)));
    }
}
