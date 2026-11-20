package dev.voxelbrew.util.concurrent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/** FIFO task queue with independent cancellation and lifecycle over a shared executor. */
@RequiredArgsConstructor
public final class SerialExecutor extends AbstractExecutorService {
    @NonNull
    private final Executor executor;

    private final Queue<Runnable> pending = new ArrayDeque<>();
    private final CompletableFuture<Void> termination = new CompletableFuture<>();
    private boolean scheduled;
    private boolean shutdown;
    private Thread running;

    @Override
    public synchronized void execute(@NonNull Runnable command) {
        if (shutdown) throw new RejectedExecutionException("Task queue is shut down");
        pending.add(command);
        if (!scheduled) {
            try {
                schedule();
            } catch (RejectedExecutionException failure) {
                pending.remove();
                throw failure;
            }
        }
    }

    private void schedule() {
        scheduled = true;
        try {
            executor.execute(this::runNext);
        } catch (RejectedExecutionException failure) {
            scheduled = false;
            throw failure;
        }
    }

    private void runNext() {
        Runnable command;
        synchronized (this) {
            command = pending.poll();
            if (command != null) running = Thread.currentThread();
        }
        try {
            if (command != null) command.run();
        } finally {
            boolean terminated;
            synchronized (this) {
                running = null;
                scheduled = false;
                if (!pending.isEmpty()) schedule();
                terminated = isTerminated();
            }
            if (terminated) termination.complete(null);
        }
    }

    @Override
    public void shutdown() {
        boolean terminated;
        synchronized (this) {
            shutdown = true;
            terminated = isTerminated();
        }
        if (terminated) termination.complete(null);
    }

    @Override
    public List<Runnable> shutdownNow() {
        List<Runnable> abandoned;
        boolean terminated;
        synchronized (this) {
            shutdown = true;
            abandoned = new ArrayList<>(pending);
            pending.clear();
            if (running != null) running.interrupt();
            terminated = isTerminated();
        }
        if (terminated) termination.complete(null);
        return abandoned;
    }

    @Override
    public synchronized boolean isShutdown() {
        return shutdown;
    }

    @Override
    public synchronized boolean isTerminated() {
        return shutdown && !scheduled;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        try {
            termination.get(timeout, unit);
            return true;
        } catch (java.util.concurrent.TimeoutException ignored) {
            return false;
        } catch (java.util.concurrent.ExecutionException failure) {
            throw new IllegalStateException(failure.getCause());
        }
    }

    public CompletionStage<Void> whenTerminated() {
        return termination.minimalCompletionStage();
    }
}
