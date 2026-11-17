package dev.voxelbrew.engine.printing;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.model.*;
import dev.voxelbrew.api.model.printing.PrintSetup;
import dev.voxelbrew.engine.model.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ModelSessionGcodeTest {
    @Test
    void plateSelectionRetainsJobsButPlacementEditsAndRedoDiscardThem() {
        var setup = PrintSetup.empty().addPlate("second").selectPlate("plate-1");
        var jobs = Map.of("plate-1", List.of("G90"), "second", List.of("G21"));
        var session = new ModelSession(new CadDocument("doc", List.of(), 0, setup).withGcode(jobs));
        session.setPrintSetup(setup.selectPlate("second"));
        assertThat(session.document().gcode()).isEqualTo(jobs);
        assertThat(session.document().activeGcode()).isEqualTo(List.of("G21"));
        session.setPrintSetup(session.document()
                .printSetup()
                .withInstances(List.of(new BodyInstance("part", "body", Transform.translation(10, 20, 0)))));
        assertThat(session.document().gcode().isEmpty()).isTrue();
        assertThat(session.undo()).isTrue();
        session.setGcode(jobs);
        assertThat(session.redo()).isTrue();
        assertThat(session.document().gcode().isEmpty()).isTrue();
    }

    @Test
    void modelAndPrintEditsInvalidateAllJobsAndUndoDoesNotResurrectThem() {
        var base = CadDocument.empty();
        var document = base.withGcode(Map.of(base.printSetup().activePlateId(), List.of("G90")));
        var session = new ModelSession(document);
        assertThat(session.document().activeGcode()).isEqualTo(List.of("G90"));
        session.add(new Feature.Sketch(
                "plate", "profile", new Feature.Plane("xy", 0), new Feature.Rectangle(0, 0, 20, 20)));
        assertThat(session.document().gcode().isEmpty()).isTrue();
        session.undo();
        assertThat(session.document().gcode().isEmpty()).isTrue();
        session.setGcode(document.gcode());
        session.setPrintSetup(session.document().printSetup().addPlate("second"));
        assertThat(session.document().gcode().isEmpty()).isTrue();
        session.undo();
        assertThat(session.document().gcode().isEmpty()).isTrue();
        assertThatThrownBy(() -> base.withGcode(Map.of("plate-1", List.of("G90\nG21"))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
