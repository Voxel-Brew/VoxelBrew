package dev.voxelbrew.gui;

import static org.assertj.core.api.Assertions.*;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SlicerWorkspaceTest {
    @Test
    void appliedChangesReplacePreviouslySavedFieldsAndPreserveUnrecognizedPreferences() {
        var field = new AtomicReference<>("2");
        var workspace = new SlicerWorkspace();
        workspace.add("walls", field::get, field::set);
        workspace.initialize();
        var document = new Object();
        var saved = Map.of("walls", "4", "future-setting", "preserved");
        workspace.restore(document, saved, workspace.pending(document));
        field.set("6");
        workspace.restore(document, saved, Map.of());
        assertThat(workspace.capture()).isEqualTo(Map.of("walls", "6", "future-setting", "preserved"));
    }

    @Test
    void draftsPersistAcrossRefreshSaveAndReopenWithoutLeakingIntoAnotherDocument() {
        var field = new AtomicReference<>("default");
        var workspace = new SlicerWorkspace();
        workspace.add("job", field::get, field::set);
        workspace.initialize();
        var first = new Object();
        workspace.restore(first, Map.of(), workspace.pending(first));
        assertThat(workspace.capture()).isEqualTo(Map.of());
        field.set("custom.gcode");
        var pending = workspace.pending(first);
        field.set("generated.gcode");
        workspace.restore(first, Map.of(), pending);
        assertThat(field.get()).isEqualTo("custom.gcode");
        var saved = workspace.capture();
        workspace.restore(first, saved, Map.of());
        assertThat(workspace.capture()).isEqualTo(saved);
        var second = new Object();
        workspace.restore(second, Map.of(), workspace.pending(second));
        assertThat(field.get()).isEqualTo("default");
        assertThat(workspace.capture()).isEqualTo(Map.of());
        var reopened = new Object();
        workspace.restore(reopened, saved, workspace.pending(reopened));
        assertThat(field.get()).isEqualTo("custom.gcode");
    }
}
