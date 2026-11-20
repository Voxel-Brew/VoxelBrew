package dev.voxelbrew.engine.printing;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.model.*;
import dev.voxelbrew.api.model.printing.ManufacturingModel;
import dev.voxelbrew.api.model.printing.PrintProfile;
import dev.voxelbrew.api.model.printing.PrintSetup;
import dev.voxelbrew.api.model.printing.PrintTarget;
import dev.voxelbrew.api.model.printing.ProcessSettings;
import dev.voxelbrew.api.model.printing.SupportVolume;
import dev.voxelbrew.engine.model.*;
import dev.voxelbrew.engine.persistence.DocumentCodec;
import java.util.List;
import org.junit.jupiter.api.Test;

class PrintPlatesTest {
    @Test
    void perPlateSliceTargetsSurviveBinaryAndJsonRoundTrips() throws Exception {
        var setup = PrintSetup.empty().withPrintTarget("workshop").addPlate("second");
        var first = setup.plates().getFirst().id();
        var second = setup.plates().get(1).id();
        var lines = List.of("G21", "G1 X1 E1");
        var connected = PrintTarget.attach(null, lines, setup);
        var profileOnly = PrintTarget.attach(null, lines, setup.withPrintTarget(null));
        var document = new CadDocument("doc", List.of(), 0, setup)
                .withGcode(java.util.Map.of(first, lines, second, lines))
                .withPrintArtifacts(java.util.Map.of(first, connected, second, profileOnly));
        assertThat(DocumentCodec.decode(DocumentCodec.encode(document))).isEqualTo(document);
        assertThat(DocumentCodec.decodeJson(DocumentCodec.encodeJson(document))).isEqualTo(document);
        assertThat(connected.metadata().get("target_kind")).isEqualTo("printer");
        assertThat(profileOnly.metadata().get("target_kind")).isEqualTo("profile");
    }

    @Test
    void disablingSupportsPersistsAndPreservesDefinitionsAcrossPlateEdits() throws Exception {
        var process = new ProcessSettings(0.2, 2, 20, true, true, 45, 0.2, 3, 0, 2);
        var enabled = PrintSetup.empty()
                .withProfile("prusa-slicer", "generic-marlin-200-0.4", "PLA", process)
                .withInstances(List.of(new BodyInstance("part", "body", Transform.IDENTITY)))
                .withSupports(
                        List.of(new SupportVolume("manual", "part", SupportVolume.Role.ENFORCER, 0, 0, 0, 5, 5, 5)))
                .addPlate("spare")
                .selectPlate("plate-1");
        var disabled = enabled.withSupportsDisabled(true);
        var document = new CadDocument("doc", List.of(), 0, disabled);
        assertThat(DocumentCodec.decode(DocumentCodec.encode(document))).isEqualTo(document);
        assertThat(DocumentCodec.decodeJson(DocumentCodec.encodeJson(document))).isEqualTo(document);
        assertThat(disabled.withSupportsDisabled(false)).isEqualTo(enabled);
        assertThat(disabled.renamePlate("plate-1", "Main")
                        .selectPlate("spare")
                        .removeActivePlate()
                        .withWorkspaceSettings(java.util.Map.of())
                        .supportsDisabled())
                .isTrue();
        assertThat(disabled.moveInstance("part", "spare").selectPlate("spare").supportsDisabled())
                .isTrue();
        var model =
                new ManufacturingModel("doc", "revision", disabled, PrintProfile.resolve(disabled), java.util.Map.of());
        assertThat(model.setup().supports().isEmpty()).isTrue();
        assertThat(model.profile().process().automaticSupports()).isFalse();
        assertThat(model.profile().process().supportsOnBedOnly()).isFalse();
        assertThat(model.profile().process().raftLayers()).isEqualTo(0);
        assertThat(disabled.supports()).isEqualTo(enabled.supports());
        assertThat(disabled.process()).isEqualTo(process);
        assertThat(PrintSetup.empty().supportsDisabled()).isFalse();
    }

    @Test
    void allSlicerSettingsRoundTripAndSurvivePlateAndProfileEdits() throws Exception {
        var settings = java.util.Map.of(
                "print-output",
                "parts/custom.gcode",
                "print-overwrite",
                "true",
                "print-layer-height",
                "0.25",
                "print-support-width",
                "2 + 3",
                "print-rotation-space",
                "LOCAL");
        var process = new ProcessSettings(0.25, 4, 35, true, true, 55, 0.3, 4, 6, 2);
        var setup = PrintSetup.empty()
                .withWorkspaceSettings(settings)
                .withProfile("prusa-slicer", "generic-marlin-200-0.4", "PETG", process)
                .withInstances(List.of(new BodyInstance("part", "body", Transform.translation(12, 34, 5))))
                .withSupports(List.of(new SupportVolume("block", "part", SupportVolume.Role.BLOCKER, 1, 2, 3, 4, 5, 6)))
                .renamePlate("plate-1", "Main")
                .addPlate("spare")
                .selectPlate("plate-1");
        assertThat(setup.workspaceSettings()).isEqualTo(settings);
        var doc = new CadDocument("doc", List.of(), 0, setup);
        assertThat(DocumentCodec.decode(DocumentCodec.encode(doc))).isEqualTo(doc);
        assertThat(DocumentCodec.decodeJson(DocumentCodec.encodeJson(doc))).isEqualTo(doc);
        assertThat(setup.moveInstance("part", "spare")
                        .selectPlate("spare")
                        .removeActivePlate()
                        .workspaceSettings())
                .isEqualTo(settings);
        assertThatThrownBy(() -> setup.workspaceSettings().put("bad", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void namedPlatesPersistRemainUndoableAndSurvivePlacementEdits() throws Exception {
        var instance = new BodyInstance("part", "body", Transform.translation(10, 20, 0));
        var initial = PrintSetup.empty().withInstances(List.of(instance));
        var session = new ModelSession(new CadDocument("doc", List.of(), 0, initial));
        session.setPrintSetup(initial.renamePlate("plate-1", "  Zażółć / Assembly  "));
        var named = session.document().printSetup();
        assertThat(named.activePlate().name()).isEqualTo("Zażółć / Assembly");
        assertThat(named.version()).isEqualTo(3);
        assertThat(named.instances()).isEqualTo(initial.instances());
        assertThat(named.activePlateId()).isEqualTo("plate-1");
        assertThat(DocumentCodec.decode(DocumentCodec.encode(session.document())))
                .isEqualTo(session.document());
        assertThat(DocumentCodec.decodeJson(DocumentCodec.encodeJson(session.document())))
                .isEqualTo(session.document());
        assertThat(named.renamePlate("plate-1", "Zażółć / Assembly")).isSameAs(named);
        session.undo();
        assertThat(session.document().printSetup()).isEqualTo(initial);
        assertThat(session.canUndo()).isFalse();
        session.redo();
        assertThat(session.document().printSetup()).isEqualTo(named);
        var edited = named.withInstances(List.of(instance))
                .withSupports(List.of())
                .withProfile(named.backendId(), named.printerId(), "PETG", named.process())
                .addPlate("second")
                .renamePlate("second", "Spare parts")
                .selectPlate("plate-1")
                .moveInstance("part", "second");
        assertThat(edited.activePlate().name()).isEqualTo("Zażółć / Assembly");
        assertThat(edited.selectPlate("second").activePlate().name()).isEqualTo("Spare parts");
        assertThat(edited.selectPlate("second")
                        .removeActivePlate()
                        .activePlate()
                        .name())
                .isEqualTo("Zażółć / Assembly");
        assertThat(edited.selectPlate("second").instances()).isEqualTo(List.of(instance));
        var multi = new CadDocument("doc", List.of(), 0, edited);
        assertThat(DocumentCodec.decode(DocumentCodec.encode(multi))).isEqualTo(multi);
        for (String invalid : List.of("", "  ", "x".repeat(129), "bad\nname")) {
            assertThatThrownBy(() -> named.renamePlate("plate-1", invalid))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> named.renamePlate("missing", "Name")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void platesRoundTripAndExposeOnlySelectedContents() throws Exception {
        var first = new BodyInstance("first", "body", Transform.translation(10, 20, 0));
        var support = new SupportVolume("support", "first", SupportVolume.Role.SOLID_SUPPORT, 0, 0, 0, 2, 2, 5);
        var setup = PrintSetup.empty().withInstances(List.of(first)).withSupports(List.of(support));
        var legacy = new CadDocument("doc", List.of(), 0, setup);
        assertThat(DocumentCodec.decode(DocumentCodec.encode(legacy))).isEqualTo(legacy);
        setup = setup.addPlate("second");
        assertThat(setup.instances().isEmpty()).isTrue();
        assertThat(setup.supports().isEmpty()).isTrue();
        assertThat(setup.selectPlate("plate-1").instances()).isEqualTo(List.of(first));
        var second = new BodyInstance("second", "body", Transform.translation(50, 50, 0));
        setup = setup.withInstances(List.of(second));
        var document = new CadDocument("doc", List.of(), 0, setup);
        assertThat(DocumentCodec.decode(DocumentCodec.encode(document))).isEqualTo(document);
        assertThat(DocumentCodec.decodeJson(DocumentCodec.encodeJson(document))).isEqualTo(document);
        assertThat(document.printSetup().instances()).isEqualTo(List.of(second));
        assertThat(document.printSetup().selectPlate("plate-1").instances()).isEqualTo(List.of(first));
        assertThatThrownBy(() -> PrintSetup.empty().removeActivePlate()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PrintSetup.empty().selectPlate("absent")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PrintSetup.empty().addPlate("plate-1")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void movingObjectsCarriesSupportsAndPlateChangesAreUndoable() {
        var instance = new BodyInstance("first", "body", Transform.translation(10, 20, 0));
        var support = new SupportVolume("support", "first", SupportVolume.Role.BLOCKER, 0, 0, 0, 2, 2, 5);
        var initial = PrintSetup.empty()
                .withInstances(List.of(instance))
                .withSupports(List.of(support))
                .addPlate("second")
                .selectPlate("plate-1");
        var session = new ModelSession(new CadDocument("doc", List.of(), 0, initial));
        session.setPrintSetup(initial.moveInstance("first", "second"));
        assertThat(session.document().printSetup().instances().isEmpty()).isTrue();
        assertThat(session.document().printSetup().supports().isEmpty()).isTrue();
        var target = session.document().printSetup().selectPlate("second");
        assertThat(target.instances()).isEqualTo(List.of(instance));
        assertThat(target.supports()).isEqualTo(List.of(support));
        session.undo();
        assertThat(session.document().printSetup()).isEqualTo(initial);
        session.redo();
        assertThat(session.document().printSetup().instances().isEmpty()).isTrue();
        assertThat(target.removeActivePlate().activePlateId()).isEqualTo("plate-1");
    }
}
