package com.optiportal.async;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.optiportal.support.RecordingScheduledExecutor;

class WorldThreadBridgeBatchingTest {

    private static final int BATCH_SIZE = 10; // mirrors WorldThreadBridge.BATCH_SIZE

    private RecordingScheduledExecutor executor;
    private WorldThreadBridge bridge;

    @BeforeEach
    void setUp() {
        executor = new RecordingScheduledExecutor();
        AsyncMetrics metrics = new AsyncMetrics();
        CircuitBreaker circuitBreaker = new CircuitBreaker();
        AsyncErrorHandler errorHandler = new AsyncErrorHandler(metrics, circuitBreaker, executor);
        bridge = new WorldThreadBridge(executor, errorHandler, metrics);
    }

    @Test
    void batchedOperationProcessesOneSlicePerTickAndCompletesAfterFinalSlice() throws Exception {
        AtomicInteger executed = new AtomicInteger();
        List<Runnable> operations = java.util.stream.IntStream.range(0, 25)
                .<Runnable>mapToObj(i -> executed::incrementAndGet)
                .toList();
        CompletableFuture<Void> future = new CompletableFuture<>();
        Object batch = newBatchedOperation(operations, future);

        List<Runnable> slice1 = claimNextSlice(batch, BATCH_SIZE);
        assertEquals(BATCH_SIZE, slice1.size(), "First slice should respect BATCH_SIZE");
        slice1.forEach(Runnable::run);
        assertFalse(finishSuccess(batch, slice1.size()), "Batch should not be complete after first slice");
        assertEquals(BATCH_SIZE, executed.get());
        assertFalse(future.isDone(), "Future should remain pending after first slice");

        List<Runnable> slice2 = claimNextSlice(batch, BATCH_SIZE);
        assertEquals(BATCH_SIZE, slice2.size(), "Second slice should also respect BATCH_SIZE");
        slice2.forEach(Runnable::run);
        assertFalse(finishSuccess(batch, slice2.size()), "Batch should not be complete after second slice");
        assertEquals(BATCH_SIZE * 2, executed.get());
        assertFalse(future.isDone(), "Future should remain pending after second slice");

        List<Runnable> slice3 = claimNextSlice(batch, BATCH_SIZE);
        assertEquals(5, slice3.size(), "Final slice should contain the remainder");
        slice3.forEach(Runnable::run);
        assertTrue(finishSuccess(batch, slice3.size()), "Batch should complete after final slice");
        assertEquals(25, executed.get());
        assertTrue(future.isDone() && !future.isCompletedExceptionally(),
                "Future should complete successfully after the last slice");
    }

    @Test
    void failQueuedBatchesCompletesEveryQueuedFutureExceptionally() throws Exception {
        CompletableFuture<Void> firstFuture = new CompletableFuture<>();
        CompletableFuture<Void> secondFuture = new CompletableFuture<>();
        Queue<Object> queue = new ConcurrentLinkedQueue<>();
        queue.add(newBatchedOperation(List.of(() -> {}), firstFuture));
        queue.add(newBatchedOperation(List.of(() -> {}), secondFuture));

        Method failQueuedBatches = WorldThreadBridge.class.getDeclaredMethod(
                "failQueuedBatches", Queue.class, Throwable.class);
        failQueuedBatches.setAccessible(true);
        failQueuedBatches.invoke(bridge, queue, new IllegalStateException("rejected"));

        assertTrue(firstFuture.isCompletedExceptionally(), "First queued batch should fail exceptionally");
        assertTrue(secondFuture.isCompletedExceptionally(), "Later queued batches should also fail exceptionally");
        assertTrue(queue.isEmpty(), "Failure drain should empty the queue");
    }

    private Object newBatchedOperation(List<Runnable> operations, CompletableFuture<Void> future) throws Exception {
        Constructor<?> ctor = Class.forName("com.optiportal.async.WorldThreadBridge$BatchedOperation")
                .getDeclaredConstructor(List.class, CompletableFuture.class);
        ctor.setAccessible(true);
        return ctor.newInstance(new ArrayList<>(operations), future);
    }

    @SuppressWarnings("unchecked")
    private static List<Runnable> claimNextSlice(Object batch, int batchSize) throws Exception {
        Method claimNextSlice = batch.getClass().getDeclaredMethod("claimNextSlice", int.class);
        claimNextSlice.setAccessible(true);
        return (List<Runnable>) claimNextSlice.invoke(batch, batchSize);
    }

    private static boolean finishSuccess(Object batch, int completedCount) throws Exception {
        Method finishSuccess = batch.getClass().getDeclaredMethod("finishSuccess", int.class);
        finishSuccess.setAccessible(true);
        return (boolean) finishSuccess.invoke(batch, completedCount);
    }
}
