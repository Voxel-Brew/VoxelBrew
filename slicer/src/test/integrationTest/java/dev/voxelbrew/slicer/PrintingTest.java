package dev.voxelbrew.slicer;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.model.*;
import dev.voxelbrew.api.model.mesh.*;
import dev.voxelbrew.api.model.printing.*;
import dev.voxelbrew.engine.model.*;
import dev.voxelbrew.engine.persistence.DocumentCodec;
import dev.voxelbrew.engine.printing.*;
import dev.voxelbrew.exporter.DocumentWriter;
import dev.voxelbrew.exporter.printing.*;
import dev.voxelbrew.importer.DocumentReader;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PrintingTest {
    @TempDir
    Path directory;

    static ModelSession reference() {
        var s = new ModelSession(CadDocument.empty());
        var xy = new Feature.Plane("origin-xy", 0);
        s.add(new Feature.Sketch("plate", "plate.profile", xy, new Feature.Rectangle(0, 0, 60, 40)));
        s.add(new Feature.Extrude("thickness", new Feature.ProfileRef("plate", "plate.profile"), 8));
        s.add(new Feature.Sketch("hole", "hole.profile", xy, new Feature.Circle(10, 10, 4)));
        s.add(new Feature.ThroughCut(
                "cut", new Feature.ProfileRef("hole", "hole.profile"), new Feature.BodyRef("thickness", "body")));
        s.setPrintSetup(s.document()
                .printSetup()
                .withInstances(List.of(new BodyInstance("part", "thickness.body", Transform.translation(20, 30, 0)))));
        return s;
    }

    static ManufacturingModel twoFilamentModel(ModelSession session, boolean reverse) {
        var original = ManufacturingModels.evaluate(session.document(), MeshOptions.DEFAULT);
        var left = original.instances().getFirst();
        var right = new BodyInstance("copy", "other.body", Transform.translation(100, 30, 0));
        session.setBodyStyle(left.bodyId(), new BodyStyle("PLA", "#AA0033"));
        session.setBodyStyle(right.bodyId(), new BodyStyle("PETG", "#55BB22"));
        var setup = original.setup().withInstances(reverse ? List.of(right, left) : List.of(left, right));
        var mesh = original.mesh(left.bodyId());
        return new ManufacturingModel(
                original.documentId(),
                original.revision(),
                setup,
                original.profile(),
                Map.of(left.bodyId(), mesh, right.bodyId(), mesh));
    }

    @Test
    void prusaUsesTheSameFilamentForEachBodyAfterReordering() throws Exception {
        var session = reference();
        PlateFilaments previous = null;
        for (boolean reverse : List.of(false, true)) {
            var model = twoFilamentModel(session, reverse);
            var palette = PlateFilaments.from(session.document(), model);
            if (previous != null) assertThat(palette).isEqualTo(previous);
            previous = palette;
            var result = SliceService.slice(
                            new dev.voxelbrew.slicer.prusa.PrusaSlicerProvider(),
                            model,
                            palette,
                            SlicerTestSupport.window(Map.of()),
                            new Cancellation(),
                            System.out::println)
                    .orElseThrow();
            assertThat(PlateFilaments.read(result.artifact().metadata())).isEqualTo(palette);
            assertThat(result.job().lines())
                    .contains("; filament_colour = #55BB22;#AA0033", "; filament_type = PETG;PLA");
            int tool = -1;
            var deposits = new HashSet<Integer>();
            for (String line : result.job().lines()) {
                if (line.startsWith("T0 ")) tool = 0;
                if (line.startsWith("T1 ")) tool = 1;
                if (line.startsWith("G1 X") && line.contains(" E") && tool >= 0) {
                    double x = Double.parseDouble(line.substring(4, line.indexOf(' ', 4)));
                    if (x >= 20 && x <= 80 || x >= 100 && x <= 160) {
                        assertThat(tool).as(line).isEqualTo(x >= 100 ? 0 : 1);
                        deposits.add(tool);
                    }
                }
            }
            assertThat(deposits).containsExactlyInAnyOrder(0, 1);
        }
    }

    @Test
    void disabledSupportsExcludeManualAutomaticAndRaftOutputWithoutDeletingSettings() throws Exception {
        var session = reference();
        var setup = session.document().printSetup();
        var process = new ProcessSettings(0.2, 2, 20, true, true, 45, 0.2, 3, 0, 2);
        setup = setup.withProfile(setup.backendId(), setup.printerId(), setup.materialId(), process)
                .withSupports(List.of(
                        new SupportVolume("enforce", "part", SupportVolume.Role.ENFORCER, 0, 0, 0, 5, 5, 8),
                        new SupportVolume("pillar", "part", SupportVolume.Role.SOLID_SUPPORT, 65, 0, 0, 5, 5, 8)))
                .withSupportsDisabled(true);
        session.setPrintSetup(setup);
        var model = ManufacturingModels.evaluate(session.document(), MeshOptions.DEFAULT);
        assertThat(model.setup().supports().isEmpty()).isTrue();
        assertThat(PrusaConfiguration.settings(model).get("support_material")).isEqualTo("0");
        assertThat(PrusaConfiguration.settings(model).get("raft_layers")).isEqualTo("0");
        assertThat(PrusaProjectExporter.volumes(model, model.instances().getFirst())
                        .size())
                .isEqualTo(1);
        var result = SlicerTestSupport.slice(
                new dev.voxelbrew.slicer.prusa.PrusaSlicerProvider(),
                model,
                Map.of(),
                directory.resolve("supports-off.gcode"),
                false,
                new Cancellation(),
                System.out::println);
        assertThat(result.job().segments().stream()
                        .anyMatch(segment -> segment.deposits()
                                && (segment.role() == GcodeJob.Role.SUPPORT
                                        || segment.role() == GcodeJob.Role.INTERFACE)))
                .isFalse();
        assertThat(result.job().modelLayers().getFirst()).isCloseTo(0.2, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(session.document().printSetup().supports().size()).isEqualTo(2);
        assertThat(session.document()
                        .printSetup()
                        .withSupportsDisabled(false)
                        .process()
                        .automaticSupports())
                .isTrue();
    }

    @Test
    void realSlicerProducesFortyLayersAndPreservesPlacementAndHole() throws Exception {
        var session = reference();
        var model = dev.voxelbrew.engine.printing.ManufacturingModels.evaluate(session.document(), MeshOptions.DEFAULT);
        var output = directory.resolve("real reference job.gcode");
        var result = SlicerTestSupport.slice(
                new dev.voxelbrew.slicer.prusa.PrusaSlicerProvider(),
                model,
                Map.of(),
                output,
                false,
                new Cancellation(),
                System.out::println);
        var job = result.job();
        assertThat(job.modelLayers().size()).isEqualTo(40);
        assertThat(job.modelLayers().getFirst()).isCloseTo(0.2, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(job.modelLayers().getLast()).isCloseTo(8, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(job.report().get("material")).isEqualTo("PLA");
        assertThat(job.report().get("revision")).isEqualTo(model.revision());
        assertThat(job.filamentMm().orElseThrow() > 0).isTrue();
        assertThat(job.estimatedTime().isPresent()).isTrue();
        var layer = job.segments().stream()
                .filter(s -> s.deposits() && s.role().model() && Math.abs(s.z1() - 4) < 1e-6)
                .toList();
        assertThat(layer.isEmpty()).isFalse();
        for (var segment : layer) {
            assertThat(segment.x1() >= 19.999 && segment.x1() <= 80.001)
                    .as(segment.toString())
                    .isTrue();
            assertThat(segment.y1() >= 29.999 && segment.y1() <= 70.001)
                    .as(segment.toString())
                    .isTrue();
            double dx = segment.x1() - segment.x0(), dy = segment.y1() - segment.y0();
            double t = Math.max(
                    0, Math.min(1, ((30 - segment.x0()) * dx + (40 - segment.y0()) * dy) / (dx * dx + dy * dy)));
            double distance = Math.hypot(segment.x0() + t * dx - 30, segment.y0() + t * dy - 40);
            assertThat(distance - segment.width() / 2 >= 1.95)
                    .as("deposited bead enters hole: " + segment)
                    .isTrue();
        }
    }

    @Test
    void selectedNozzleIsUsedByRealSlicer() throws Exception {
        var session = reference();
        session.setPrintSetup(session.document().printSetup().withNozzle(0.6));
        var model = ManufacturingModels.evaluate(session.document(), MeshOptions.DEFAULT);
        var output = directory.resolve("0.6-mm-nozzle.gcode");
        var result = SlicerTestSupport.slice(
                new dev.voxelbrew.slicer.prusa.PrusaSlicerProvider(),
                model,
                Map.of(),
                output,
                false,
                new Cancellation(),
                System.out::println);
        assertThat(result.job().report().get("nozzle_mm")).isEqualTo("0.6");
        assertThat(result.job().modelLayers().isEmpty()).isFalse();
        assertThat(Files.size(output) > 0).isTrue();
    }

    @Test
    void placementsAndModifiersPersistAndTrackEditsWithoutChangingCadCoordinates() throws Exception {
        var session = reference();
        var first = session.document().printSetup().instances().getFirst();
        var second = new BodyInstance(
                "copy", first.bodyId(), Transform.translation(150, 30, 0).compose(Transform.rotation(0, 0, 90)));
        var setup = session.document()
                .printSetup()
                .withInstances(List.of(first, second))
                .withSupports(
                        List.of(new SupportVolume("block", "part", SupportVolume.Role.BLOCKER, 0, 0, 0, 10, 10, 8)));
        session.setPrintSetup(setup);
        Path file = directory.resolve("placements.vxb");
        DocumentWriter.save(session.document(), file, false);
        var reopened = new ModelSession(DocumentReader.load(file));
        assertThat(reopened.document()).isEqualTo(session.document());
        reopened.editDimension("thickness", "distance", 5);
        assertThat(reopened.document().printSetup()).isEqualTo(setup);
        try (var evaluation = reopened.evaluate()) {
            var bounds = evaluation.bodies().getFirst().solid().bounds();
            assertThat(bounds.minX()).isCloseTo(0, org.assertj.core.data.Offset.offset(0.001));
            assertThat(bounds.maxX()).isCloseTo(60, org.assertj.core.data.Offset.offset(0.001));
        }
        var model =
                dev.voxelbrew.engine.printing.ManufacturingModels.evaluate(reopened.document(), MeshOptions.DEFAULT);
        assertThat(PrintValidation.validate(model).valid())
                .as(PrintValidation.validate(model).toString())
                .isTrue();
        var before = DocumentCodec.fingerprint(reopened.document());
        reopened.setPrintSetup(setup.withInstances(List.of(first)));
        assertThat(DocumentCodec.fingerprint(reopened.document())).isNotEqualTo(before);
        assertThat(reopened.undo()).isTrue();
        assertThat(reopened.document().printSetup()).isEqualTo(setup);
    }
}
