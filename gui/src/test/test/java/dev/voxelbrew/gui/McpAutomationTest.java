package dev.voxelbrew.gui;

import static org.assertj.core.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class McpAutomationTest {
    @Test
    void liveEditsShareControllerUndoAndRejectConflictsAndDrafts() throws Exception {
        var callbacks = new LinkedBlockingQueue<Runnable>();
        try (var controller = new DocumentController(
                callbacks::add, doc -> new DocumentController.Build(List.of(), List.of(), true))) {
            var busy = new java.util.concurrent.atomic.AtomicBoolean();
            var automation = new McpAutomation(controller, busy::get, Runnable::run);
            var before = automation.call("document_inspect", Map.of()).get();
            var args = new HashMap<String, Object>();
            args.put("documentId", before.get("documentId"));
            args.put("revision", before.get("revision"));
            args.put("generation", before.get("generation"));
            args.putAll(Map.of("id", "plate", "plane", "XY", "x", 0, "y", 0, "width", 60, "height", 40));
            automation.call("sketch_rectangle", args).get();
            assertThat(controller.snapshot().document().features().size()).isEqualTo(1);
            assertThat(controller.dirty()).isTrue();
            assertThatThrownBy(() -> automation.call("sketch_rectangle", args).get())
                    .isInstanceOf(ExecutionException.class);
            var current = automation.call("document_inspect", Map.of()).get();
            var revision = Map.of(
                    "documentId",
                    current.get("documentId"),
                    "revision",
                    current.get("revision"),
                    "generation",
                    current.get("generation"));
            busy.set(true);
            assertThatThrownBy(() -> automation.call("history_undo", revision).get())
                    .isInstanceOf(ExecutionException.class);
            assertThat(controller.snapshot().document().features().size()).isEqualTo(1);
            busy.set(false);
            automation.call("history_undo", revision).get();
            assertThat(controller.snapshot().document().features().isEmpty()).isTrue();
        }
    }
}
