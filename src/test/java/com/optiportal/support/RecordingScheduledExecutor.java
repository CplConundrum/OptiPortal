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
    private volatile boolean shutdown;

    public List<Runnable> getScheduledRunnables() {
        return scheduledRunnables;
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
        return noOpFuture();
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
        scheduledRunnables.add(command);
        return noOpFuture();
    }

    // ---- ExecutorService ----------------------------------------------------

    @Override public void execute(Runnable command) {}

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
    public <T> Future<T> submit(Runnable task, T result) { return noOpFuture(); }

    @Override
    public Future<?> submit(Runnable task) { return noOpFuture(); }

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
}
