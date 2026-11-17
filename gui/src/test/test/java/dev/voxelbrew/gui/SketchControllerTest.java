package dev.voxelbrew.gui;

import static dev.voxelbrew.engine.sketch.SketchGeometry.*;
import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.engine.sketch.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SketchControllerTest {
    @Test
    void textFormulaDraftsRebuildAndKeepBindingsInUndoHistory() throws Exception {
        var queue = new ConcurrentLinkedQueue<Runnable>();
        var text = SketchText.create("text", "Width: 5", "SansSerif", 0, 0, 0, 5, 0, 0, "", false);
        var geometry = new SketchGeometry(List.of(text), List.of(), List.of("text"));
        var parameters = dev.voxelbrew.engine.parameters.ParameterSet.empty()
                .put("width", dev.voxelbrew.engine.parameters.ParameterUnit.MM, "5", "")
                .bind(
                        new dev.voxelbrew.engine.parameters.ParameterSet.Target("sketch", "text:text:content"),
                        "=\"Width: \"+$width");
        try (var controller = new SketchController(geometry, parameters, "sketch", queue::add)) {
            await(controller, queue);
            controller.edit(
                    controller.definition(),
                    parameters.put("width", dev.voxelbrew.engine.parameters.ParameterUnit.MM, "8", ""));
            await(controller, queue);
            assertThat(controller.canFinish()).isTrue();
            assertThat(controller.definition().entity("text").text().content()).isEqualTo("Width: 8");
            controller.undo();
            await(controller, queue);
            assertThat(controller.definition().entity("text").text().content()).isEqualTo("Width: 5");
            controller.redo();
            await(controller, queue);
            assertThat(controller.definition().entity("text").text().content()).isEqualTo("Width: 8");
            controller.edit(SketchGeometry.empty());
            await(controller, queue);
            assertThat(controller.parameters().bindings().isEmpty()).isTrue();
            controller.undo();
            await(controller, queue);
            assertThat(controller.parameters().bindings()).isEqualTo(parameters.bindings());
        }
    }

    private static void await(SketchController controller, ConcurrentLinkedQueue<Runnable> queue) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (controller.busy() && System.nanoTime() < deadline) {
            Runnable event;
            while ((event = queue.poll()) != null) event.run();
            Thread.sleep(5);
        }
        assertThat(controller.busy()).as("Sketch worker completed").isFalse();
    }

    @Test
    void parameterChangesAndBindingsShareLocalHistoryAndSolveOnTheWorker() throws Exception {
        var queue = new ConcurrentLinkedQueue<Runnable>();
        var p = dev.voxelbrew.engine.parameters.ParameterSet.empty()
                .put("width", dev.voxelbrew.engine.parameters.ParameterUnit.MM, "60", "")
                .bind(new dev.voxelbrew.engine.parameters.ParameterSet.Target("sketch", "constraint:width"), "width");
        try (var controller =
                new SketchController(Sketches.rectangle("r", 0, 0, 60, 40, true), p, "sketch", queue::add)) {
            await(controller, queue);
            controller.edit(
                    controller.definition(),
                    p.put("width", dev.voxelbrew.engine.parameters.ParameterUnit.MM, "80", ""));
            await(controller, queue);
            assertThat(controller.canFinish()).isTrue();
            assertThat(controller
                            .definition()
                            .entity("r-bottom")
                            .at(0)
                            .distance(controller.definition().entity("r-bottom").at(1)))
                    .isCloseTo(80, org.assertj.core.data.Offset.offset(1e-5));
            controller.undo();
            await(controller, queue);
            assertThat(controller.parameters().parameter("width").expression()).isEqualTo("60");
            controller.redo();
            await(controller, queue);
            assertThat(controller.parameters().parameter("width").expression()).isEqualTo("80");
            controller.edit(
                    controller.definition(),
                    controller
                            .parameters()
                            .put("width", dev.voxelbrew.engine.parameters.ParameterUnit.MM, "missing", ""));
            await(controller, queue);
            assertThat(controller.canFinish()).isFalse();
            controller.undo();
            await(controller, queue);
            assertThat(controller.canFinish()).isTrue();
        }
    }

    @Test
    void coalescesRequestsAndDiscardsOlderResults() throws Exception {
        var queue = new ConcurrentLinkedQueue<Runnable>();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var a = new SketchGeometry(
                List.of(Sketches.entity("circle", Kind.CIRCLE, false, 0, 0, 5)), List.of(), List.of("circle"));
        try (var controller = new SketchController(a, queue::add, g -> {
            if (calls.getAndIncrement() == 0) {
                entered.countDown();
                try {
                    assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException e) {
                    throw new CancellationException();
                }
            }
            return SketchSolver.solve(g);
        })) {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            controller.edit(a.replace(Sketches.entity("circle", Kind.CIRCLE, false, 0, 0, 6)));
            var latest = a.replace(Sketches.entity("circle", Kind.CIRCLE, false, 0, 0, 7));
            controller.edit(latest);
            release.countDown();
            await(controller, queue);
            assertThat(calls.get()).isEqualTo(2);
            assertThat(controller.definition()).isEqualTo(latest);
            assertThat(controller.canFinish()).isTrue();
            controller.undo();
            await(controller, queue);
            assertThat(controller.definition().entity("circle").value(2)).isEqualTo(6);
            controller.redo();
            await(controller, queue);
            assertThat(controller.definition()).isEqualTo(latest);
        } finally {
            release.countDown();
        }
    }

    @Test
    void invalidConstraintsStayEditableAndUndoRestoresTheValidSketch() throws Exception {
        var queue = new ConcurrentLinkedQueue<Runnable>();
        var original = Sketches.rectangle("r", 0, 0, 60, 40, true);
        try (var controller = new SketchController(original, queue::add)) {
            await(controller, queue);
            assertThat(controller.canUndo()).isFalse();
            var conflict = SketchConstraints.create(
                    original,
                    "bad",
                    Type.LENGTH,
                    List.of(original.entities().getFirst().ref()),
                    80);
            controller.edit(original.add(conflict));
            await(controller, queue);
            assertThat(controller.canFinish()).isFalse();
            assertThat(controller.definition().constraints().contains(conflict)).isTrue();
            controller.undo();
            await(controller, queue);
            assertThat(controller.canFinish()).isTrue();
            assertThat(controller.definition()).isEqualTo(original);
            controller.redo();
            await(controller, queue);
            assertThat(controller.canFinish()).isFalse();
        }
    }

    @Test
    void dragPreviewCancelsWithoutHistoryAndReleaseCommitsOnlyOnce() throws Exception {
        var queue = new ConcurrentLinkedQueue<Runnable>();
        var original = Sketches.rectangle("r", 0, 0, 60, 40, false);
        try (var controller = new SketchController(original, queue::add)) {
            await(controller, queue);
            var point = original.entities().getFirst().ref(Part.START);
            controller.drag(point, new Vec(4, 5), false);
            await(controller, queue);
            assertThat(controller.definition()).isEqualTo(original);
            assertThat(controller.canUndo()).isFalse();
            assertThat(controller.canFinish()).isFalse();
            controller.cancelDrag();
            await(controller, queue);
            assertThat(controller.displayed()).isEqualTo(original);
            controller.drag(point, new Vec(4, 5), true);
            await(controller, queue);
            assertThat(controller.canUndo()).isTrue();
            assertThat(controller.canFinish()).isTrue();
            assertThat(controller.definition().point(point).x())
                    .isCloseTo(4, org.assertj.core.data.Offset.offset(1e-6));
            controller.undo();
            await(controller, queue);
            assertThat(controller.definition()).isEqualTo(original);
            assertThat(controller.canUndo()).isFalse();
        }
    }

    @Test
    void fixedPointDragDoesNotChangeHistoryOrLeaveAFailedDraft() throws Exception {
        var queue = new ConcurrentLinkedQueue<Runnable>();
        var original = Sketches.rectangle("r", 0, 0, 60, 40, true);
        try (var controller = new SketchController(original, queue::add)) {
            await(controller, queue);
            controller.drag(original.entities().getFirst().ref(Part.START), new Vec(4, 5), true);
            await(controller, queue);
            assertThat(controller.definition()).isEqualTo(original);
            assertThat(controller.canUndo()).isFalse();
            assertThat(controller.canFinish()).isTrue();
            assertThat(controller.interactionError().isEmpty()).isFalse();
        }
    }

    @Test
    void closingRejectsQueuedCompletions() throws Exception {
        var queue = new ConcurrentLinkedQueue<Runnable>();
        var controller = new SketchController(SketchGeometry.empty(), queue::add);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (queue.isEmpty() && System.nanoTime() < deadline) Thread.sleep(5);
        controller.close();
        Runnable event;
        while ((event = queue.poll()) != null) event.run();
        assertThat(controller.canFinish()).isFalse();
        assertThat(controller.result()).isNull();
    }
}
