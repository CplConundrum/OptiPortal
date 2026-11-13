package com.optiportal.support;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * ScheduledExecutorService that records submitted tasks but never actually executes them.
 * Use this in tests that need to construct objects that schedule tasks during construction
 * (like TeleportInterceptor) without those tasks firing in the background.
 */
public class RecordingScheduledExecutor implements ScheduledExecutorService {

    private final List<Runnable> scheduledRunnables = new ArrayList<>();
    private final List<Runnable> executedRunnables = new ArrayList<>();

    /** One recorded scheduled task: kind, timing, and cancellation state (reviewer battery needs both). */
    public static final class ScheduledRecord {
        public final Runnable command;
        public final String kind; // "fixedRate" | "fixedDelay"
        public final long initialDelay;
        public final long period;

        final java.util.concurrent.atomic.AtomicBoolean cancelled =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        ScheduledRecord(Runnable command, String kind, long initialDelay, long period) {
            this.command = command;
            this.kind = kind;
            this.initialDelay = initialDelay;
            this.period = period;
        }

        public boolean isCancelled() {
            return cancelled.get();
        }
    }

    /** Per-task records: scheduling kind, timing, and cancellation state. */
    public final java.util.List<ScheduledRecord> scheduledRecords = new java.util.ArrayList<>();
    private volatile boolean shutdown;

    public List<Runnable> getScheduledRunnables() {
        return scheduledRunnables;
    }

    /**
     * Runnables handed to {@link #execute}/{@link #submit}. They are recorded but NOT
     * run (same no-background-firing guarantee as scheduled tasks) so tests stay
     * deterministic; tests drive them manually via {@link #runExecutedOnce()} or
     * {@link #runAllExecuted()}.
     */
    public List<Runnable> getExecutedRunnables() {
        return executedRunnables;
    }

    public void runExecutedOnce() {
        if (!executedRunnables.isEmpty()) {
            executedRunnables.remove(0).run();
        }
    }

    public void runAllExecuted() {
        for (Runnable r : new ArrayList<>(executedRunnables)) {
            r.run();
        }
    }

    // ---- ScheduledExecutorService -------------------------------------------

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        scheduledRunnables.add(command);
        return noOpFuture();
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
        return noOpFuture();
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
        scheduledRunnables.add(command);
        ScheduledRecord record = new ScheduledRecord(command, "fixedRate", initialDelay, period);
        scheduledRecords.add(record);
        return futureFor(record);
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
        scheduledRunnables.add(command);
        ScheduledRecord record = new ScheduledRecord(command, "fixedDelay", initialDelay, delay);
        scheduledRecords.add(record);
        return futureFor(record);
    }

    // ---- ExecutorService ----------------------------------------------------

    @Override public void execute(Runnable command) { executedRunnables.add(command); }

    @Override public void shutdown() { shutdown = true; }

    @Override
    public List<Runnable> shutdownNow() { shutdown = true; return List.of(); }

    @Override public boolean isShutdown() { return shutdown; }
    @Override public boolean isTerminated() { return shutdown; }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }

    @Override
    public <T> Future<T> submit(Callable<T> task) { return noOpFuture(); }

    @Override
    public <T> Future<T> submit(Runnable task, T result) { executedRunnables.add(task); return noOpFuture(); }

    @Override
    public Future<?> submit(Runnable task) { executedRunnables.add(task); return noOpFuture(); }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) { return List.of(); }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) { return List.of(); }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks) { return null; }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) { return null; }

    // ---- helpers ------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static <V> ScheduledFuture<V> noOpFuture() {
        return (ScheduledFuture<V>) NoOpScheduledFuture.INSTANCE;
    }

    /** Shared no-op future returned for all scheduled tasks. */
    private static final class NoOpScheduledFuture<V> implements ScheduledFuture<V> {
        static final NoOpScheduledFuture<?> INSTANCE = new NoOpScheduledFuture<>();

        @Override public long getDelay(TimeUnit unit) { return 0; }
        @Override public int compareTo(Delayed o) { return 0; }
        @Override public boolean cancel(boolean mayInterruptIfRunning) { return true; }
        @Override public boolean isCancelled() { return false; }
        @Override public boolean isDone() { return true; }
        @Override public V get() { return null; }
        @Override public V get(long timeout, TimeUnit unit) { return null; }
    }

    private ScheduledFuture<?> futureFor(ScheduledRecord record) {
        return new RecordingFuture<>(record);
    }

    /** Per-task future that records cancellation (the shared no-op covers one-shot schedules only). */
    private static final class RecordingFuture<V> implements ScheduledFuture<V> {
        private final ScheduledRecord record;

        RecordingFuture(ScheduledRecord record) {
            this.record = record;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            record.cancelled.set(true);
            return true;
        }

        @Override
        public boolean isCancelled() {
            return record.isCancelled();
        }

        @Override
        public boolean isDone() {
            return true;
        }

        @Override
        public V get() {
            return null;
        }

        @Override
        public V get(long timeout, TimeUnit unit) {
            return null;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return 0;
        }

        @Override
        public int compareTo(Delayed o) {
            return 0;
        }
}
}
