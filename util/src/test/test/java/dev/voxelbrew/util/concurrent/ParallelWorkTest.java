package dev.voxelbrew.util.concurrent;

import static org.assertj.core.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

@SuppressWarnings("deprecation")
class ParallelWorkTest {
    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS))
                    .as("worker rendezvous timed out")
                    .isTrue();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    @Test
    void workersOverlapStayBoundedVisitEachIndexAndAllowNestedWork() throws Exception {
        try (var executor = Executors.newFixedThreadPool(3);
                var caller = Executors.newSingleThreadExecutor()) {
            var pool = new ParallelWork.WorkPool(3, executor);
            var started = new CountDownLatch(3);
            var release = new CountDownLatch(1);
            var active = new AtomicInteger();
            var peak = new AtomicInteger();
            var visits = new AtomicIntegerArray(48);
            var result = caller.submit(() -> pool.forEach(48, index -> {
                int count = active.incrementAndGet();
                peak.accumulateAndGet(count, Math::max);
                started.countDown();
                await(release);
                pool.forEach(2, ignored -> visits.incrementAndGet(index));
                active.decrementAndGet();
            }));
            try {
                await(started);
                assertThat(active.get()).isEqualTo(3);
            } finally {
                release.countDown();
            }
            result.get(10, TimeUnit.SECONDS);
            assertThat(peak.get()).isEqualTo(3);
            for (int i = 0; i < 48; i++) assertThat(visits.get(i)).isEqualTo(2);
        }
        assertThat(ParallelWork.map(List.of(3, 2, 1), n -> n * n)).isEqualTo(List.of(9, 4, 1));
    }

    @Test
    void failureDoesNotReturnWhileAnotherWorkerStillOwnsResources() throws Exception {
        try (var executor = Executors.newFixedThreadPool(2);
                var caller = Executors.newSingleThreadExecutor()) {
            var pool = new ParallelWork.WorkPool(2, executor);
            var started = new CountDownLatch(2);
            var failed = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var exception = new IllegalStateException("meshing failed");
            var result = caller.submit(() -> pool.forEach(2, index -> {
                started.countDown();
                await(started);
                if (index == 0) {
                    failed.countDown();
                    throw exception;
                }
                await(release);
            }));
            try {
                await(failed);
                assertThatThrownBy(() -> result.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            } finally {
                release.countDown();
            }
            assertThat(org.assertj.core.api.Assertions.catchThrowableOfType(
                                    () -> result.get(10, TimeUnit.SECONDS), ExecutionException.class)
                            .getCause())
                    .isSameAs(exception);
        }
    }

    @Test
    void interruptionDrainsInFlightWorkAndPreservesInterruptStatus() throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            var pool = new ParallelWork.WorkPool(2, executor);
            var started = new CountDownLatch(2);
            var release = new CountDownLatch(1);
            var returned = new CountDownLatch(1);
            var failure = new AtomicReference<Throwable>();
            var interrupted = new AtomicBoolean();
            var caller = Thread.ofPlatform().start(() -> {
                try {
                    pool.forEach(20, index -> {
                        started.countDown();
                        await(release);
                    });
                } catch (Throwable problem) {
                    failure.set(problem);
                    interrupted.set(Thread.currentThread().isInterrupted());
                } finally {
                    returned.countDown();
                }
            });
            try {
                await(started);
                caller.interrupt();
                assertThat(returned.await(100, TimeUnit.MILLISECONDS)).isFalse();
            } finally {
                release.countDown();
            }
            assertThat(caller.join(java.time.Duration.ofSeconds(10))).isTrue();
            assertThat(failure.get()).isInstanceOf(CancellationException.class);
            assertThat(interrupted.get()).isTrue();
        }
    }

    @Test
    void serialModeHasNoWorkersAndRejectsInvalidBudgets() {
        try (var executor = Executors.newFixedThreadPool(1)) {
            var pool = new ParallelWork.WorkPool(1, executor);
            var caller = Thread.currentThread();
            pool.forEach(10, index -> assertThat(Thread.currentThread()).isSameAs(caller));
        }
        assertThatThrownBy(() -> new ParallelWork.WorkPool(0, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ParallelWork.WorkPool(33, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
