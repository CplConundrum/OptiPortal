package com.optiportal.async;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Logger;

import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.modules.entity.teleport.TeleportRecord;
import com.hypixel.hytale.server.core.modules.physics.component.Velocity;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;

/**
 * Bridge component for safe async access to world thread operations.
 *
 * This component provides a non-blocking interface for operations that need
 * to access the world thread, implementing proper error handling, circuit breaking,
 * and batching to minimize world thread impact.
 */
public class WorldThreadBridge {
    
    private static final Logger LOG = Logger.getLogger("OptiPortal");
    
    private final ScheduledExecutorService executor;
    private final AsyncErrorHandler errorHandler;
    private final AsyncMetrics metrics;
    private final CircuitBreaker circuitBreaker;
    
    /** Optional TPS monitor for load-aware logging and world-state guards. Null = disabled. */
    private final WorldTpsMonitor tpsMonitor;
    
    // Operation queues for batching
    private final ConcurrentHashMap<World, Queue<BatchedOperation>> operationQueues;
    
    /**
     * Batched operation wrapper containing work items and completion future.
     * Processed one batch at a time to ensure proper exception handling.
     */
    private static final class BatchedOperation {
        private final List<Runnable> workItems;
        private final CompletableFuture<Void> completionFuture;
        private int nextIndex;
        private boolean inFlight;
        private boolean completed;
        
        BatchedOperation(List<Runnable> workItems, CompletableFuture<Void> completionFuture) {
            this.workItems = List.copyOf(workItems);
            this.completionFuture = completionFuture;
        }

        synchronized List<Runnable> claimNextSlice(int batchSize) {
            if (inFlight || completed || nextIndex >= workItems.size()) {
                return null;
            }
            int endIndex = Math.min(nextIndex + batchSize, workItems.size());
            inFlight = true;
            return new ArrayList<>(workItems.subList(nextIndex, endIndex));
        }

        synchronized boolean finishSuccess(int completedCount) {
            if (!inFlight || completed) {
                return completed;
            }
            nextIndex += completedCount;
            inFlight = false;
            if (nextIndex >= workItems.size()) {
                completed = true;
                completionFuture.complete(null);
                return true;
            }
            return false;
        }

        synchronized void finishFailure(Throwable t) {
            if (completed) {
                return;
            }
            inFlight = false;
            completed = true;
            completionFuture.completeExceptionally(t);
        }

        synchronized boolean isCompleted() {
            return completed;
        }

        void fail(Throwable t) {
            finishFailure(t);
        }
    }
    
    // Batch processing configuration
    private static final int BATCH_SIZE = 10;
    private static final int BATCH_TIMEOUT_MS = 50;
    
    public WorldThreadBridge(ScheduledExecutorService executor,
                            AsyncErrorHandler errorHandler,
                            AsyncMetrics metrics) {
        this(executor, errorHandler, metrics, null);
    }

    public WorldThreadBridge(ScheduledExecutorService executor,
                            AsyncErrorHandler errorHandler,
                            AsyncMetrics metrics,
                            WorldTpsMonitor tpsMonitor) {
        this(executor, errorHandler, metrics, tpsMonitor, errorHandler.getCircuitBreaker());
    }

    public WorldThreadBridge(ScheduledExecutorService executor,
                            AsyncErrorHandler errorHandler,
                            AsyncMetrics metrics,
                            WorldTpsMonitor tpsMonitor,
                            CircuitBreaker circuitBreaker) {
        this.executor = executor;
        this.errorHandler = errorHandler;
        this.metrics = metrics;
        this.tpsMonitor = tpsMonitor;
        this.circuitBreaker = circuitBreaker;
        this.operationQueues = new ConcurrentHashMap<>();

        // Start batch processor
        startBatchProcessor();
    }
    
    /**
     * Safely execute an operation on the world thread with proper async handling.
     *
     * @param world The world to execute on
     * @param operation The operation to execute
     * @param <T> Return type
     * @return CompletableFuture with the result
     */
    public <T> CompletableFuture<T> executeOnWorldThread(World world, Supplier<T> operation) {
        CompletableFuture<T> future = new CompletableFuture<>();
        
        // Check circuit breaker before scheduling
        if (circuitBreaker.isOpen()) {
            future.completeExceptionally(new WorldThreadOverloadException());
            return future;
        }
        
        // Log under-load condition at FINE to avoid spam in production logs
        if (tpsMonitor != null && tpsMonitor.isServerUnderLoad()) {
            LOG.fine("[OptiPortal] WorldThreadBridge: executing on world thread under load (TPS="
                    + String.format("%.1f", tpsMonitor.getCurrentTps()) + "): " + "executeOnWorldThread");
        }
        
        long startTime = System.currentTimeMillis();

        // E1: world.execute() itself throws SkipSentryException (extends RuntimeException)
        // synchronously when World.acceptingTasks == false (world shutting down).
        // That throw happens before the lambda runs, so the try-catch INSIDE the lambda
        // cannot catch it. Wrap the world.execute() call in its own try-catch so the
        // circuit breaker records the failure and the future is completed immediately
        // rather than hanging for the 5-second orTimeout.
        try {
            world.execute(() -> {
                try {
                    T result = operation.get();
                    long duration = System.currentTimeMillis() - startTime;

                    circuitBreaker.recordSuccess();
                    metrics.recordWorldThreadExecution("executeOnWorldThread", duration);
                    future.complete(result);

                } catch (Exception e) {
                    long duration = System.currentTimeMillis() - startTime;

                    circuitBreaker.recordFailure();
                    metrics.recordWorldThreadError("executeOnWorldThread", duration);
                    errorHandler.handleWorldThreadError(e, "executeOnWorldThread");
                    future.completeExceptionally(e);
                }
            });
        } catch (Exception e) {
            // world.execute() rejected the task — world is shutting down.
            long duration = System.currentTimeMillis() - startTime;
            circuitBreaker.recordFailure();
            metrics.recordWorldThreadError("executeOnWorldThread", duration);
            future.completeExceptionally(e);
        }
        
        // Add timeout protection
        future.orTimeout(5, TimeUnit.SECONDS);
        
        return future;
    }
    
    /**
     * Batch multiple operations to minimize world thread context switching.
     *
     * @param world The world to execute on
     * @param operations List of operations to batch
     * @return CompletableFuture that completes when all operations are done
     */
    public CompletableFuture<Void> executeBatched(World world, List<Runnable> operations) {
        if (operations.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        
        // Add to queue for batched processing
        Queue<BatchedOperation> queue = operationQueues.computeIfAbsent(world, k -> new ConcurrentLinkedQueue<>());
        CompletableFuture<Void> future = new CompletableFuture<>();
        queue.add(new BatchedOperation(operations, future));
        return future;
    }
    
    /**
     * Get player position without blocking world thread.
     *
     * @param world The world the player is in
     * @param playerRef The player reference
     * @return CompletableFuture with player position
     */
    public CompletableFuture<PlayerPosition> getPlayerPositionAsync(World world, PlayerRef playerRef) {
        if (world == null || playerRef == null) {
            return CompletableFuture.completedFuture(null);
        }
        
        return executeOnWorldThread(world, () -> {
            Transform transform = playerRef.getTransform();
            if (transform == null || transform.getPosition() == null) {
                return null;
            }
      
            return new PlayerPosition(
                playerRef.getWorldUuid(),
                transform.getPosition().x,
                transform.getPosition().y,
                transform.getPosition().z
            );
        });
    }
    
    /**
     * Asynchronously retrieve the speed of a player's entity.
     *
     * Uses the Velocity ECS component (com.hypixel.hytale.server.core.modules.physics.component.Velocity).
     * The full access path has been verified against HytaleServer.jar:
     *
     *   world.getEntityStore().getRefFromUUID(uuid)  →  Ref<EntityStore>
     *   entityRef.isValid()                          →  boolean (stale-ref guard)
     *   entityRef.getStore()                         →  Store<EntityStore>
     *   store.getComponent(entityRef, Velocity.getComponentType())  →  Velocity
     *   velocity.getSpeed()                          →  double
     *
     * NOTE: Ref<EntityStore> has no .get() method. Component access goes through
     * Store.getComponent(Ref, ComponentType), not through the Ref directly.
     * Store.assertThread() is present, so this must run on the world thread.
     *
     * Returns 0.0 on any access failure (component not present, stale ref, etc.).
     * A return of 0.0 is treated as "unknown speed" by callers — no radius boost is applied.
     *
     * @param world     The world the player is currently in
     * @param playerRef The player reference
     * @return CompletableFuture<Double> completing with the player's current speed,
     *         or 0.0 on failure
     */
    public CompletableFuture<Double> getPlayerSpeedAsync(World world,
            com.hypixel.hytale.server.core.universe.PlayerRef playerRef) {
        if (world == null || playerRef == null) {
            return CompletableFuture.completedFuture(0.0);
        }

        return executeOnWorldThread(world, () -> {
            try {
                // Step 1: get the entity ref for this player UUID
                com.hypixel.hytale.component.Ref<
                    com.hypixel.hytale.server.core.universe.world.storage.EntityStore> entityRef =
                        world.getEntityStore().getRefFromUUID(playerRef.getUuid());

                if (entityRef == null || !entityRef.isValid()) {
                    return 0.0;
                }

                // Step 2: get the Store<EntityStore> from the Ref
                // Ref has no .get() — component lookup goes through Store.getComponent(ref, type)
                com.hypixel.hytale.component.Store<
                    com.hypixel.hytale.server.core.universe.world.storage.EntityStore> store =
                        entityRef.getStore();

                if (store == null) {
                    return 0.0;
                }

                // Step 3: look up the Velocity component for this entity
                // Store.getComponent(Ref<ECS_TYPE>, ComponentType<ECS_TYPE, T>) → T
                Velocity vel =
                        store.getComponent(entityRef,
                            Velocity.getComponentType());

                if (vel == null) {
                    // Entity exists but has no Velocity component — treat as stationary
                    return 0.0;
                }

                return vel.getSpeed();

            } catch (Exception e) {
                LOG.fine("[OptiPortal] getPlayerSpeedAsync: error reading Velocity: " + e.getMessage());
                return 0.0;
            }
        });
    }
    
    /**
     * Get teleport record with async error handling.
     *
     * @param world     The world the player is currently in (required for world-thread dispatch)
     * @param playerRef The player reference
     * @return CompletableFuture with teleport record entry
     */
    public CompletableFuture<TeleportRecord.Entry> getTeleportRecordAsync(World world, PlayerRef playerRef) {
        if (world == null || playerRef == null) {
            return CompletableFuture.completedFuture(null);
        }

        return executeOnWorldThread(world, () -> {
            TeleportRecord record = playerRef.getComponent(TeleportRecord.getComponentType());
            if (record == null) {
                return null;
            }
      
            return record.getLastTeleport();
        });
    }
    
    /**
     * Load chunk with proper async semantics and error recovery.
     *
     * @param world The world to load chunk in
     * @param cx Chunk X coordinate
     * @param cz Chunk Z coordinate
     * @param nonTicking Whether to load as non-ticking
     * @return CompletableFuture with the loaded chunk
     */
    public CompletableFuture<WorldChunk> getChunkAsync(World world, int cx, int cz, boolean nonTicking) {
        if (world == null) {
            return CompletableFuture.completedFuture(null);
        }
        
        // Best-effort guard: these engine-owned state flags are plain fields in
        // current decompiled sources, so treat them as advisory rather than as a
        // synchronization boundary. A stale "active" read can still fall through
        // to engine rejection/error handling during shutdown or world transition.
        if (!world.isTicking() || world.isPaused()) {
            LOG.info("[OptiPortal] WorldThreadBridge: skipping chunk load in non-active world '"
                    + world.getName() + "' (ticking=" + world.isTicking()
                    + ", paused=" + world.isPaused() + ")");
            return CompletableFuture.completedFuture(null);
        }
        
        long startTime = System.currentTimeMillis();
        
        CompletableFuture<WorldChunk> future = nonTicking
                ? world.getNonTickingChunkAsync(ChunkUtil.indexChunk(cx, cz))
                : world.getChunkAsync(ChunkUtil.indexChunk(cx, cz));
        
        return future.whenComplete((chunk, ex) -> {
            long duration = System.currentTimeMillis() - startTime;

            if (ex != null) {
                metrics.recordChunkLoadError(cx, cz, duration);
                if (ex instanceof Exception) {
                    errorHandler.handleChunkLoadError((Exception) ex, world, cx, cz);
                } else if (ex instanceof Error) {
                    LOG.log(java.util.logging.Level.SEVERE, ex,
                            () -> "[OptiPortal] Severe error loading chunk ("
                            + world.getName() + ", " + cx + ", " + cz + ")");
                }
            } else {
                metrics.recordChunkLoadSuccess(cx, cz, duration);
            }
        });
    }
    
    /**
     * Start the batch processor to handle queued operations.
     */
    private void startBatchProcessor() {
        executor.scheduleAtFixedRate(() -> {
            for (java.util.Map.Entry<World, Queue<BatchedOperation>> entry : operationQueues.entrySet()) {
                processBatch(entry.getKey(), entry.getValue());
            }
        }, 0, BATCH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }
    
    /**
     * Process a batch of operations for a world.
     *
     * @param world The world to process for
     * @param queue The operation queue
     */
    private void processBatch(World world, Queue<BatchedOperation> queue) {
        if (queue.isEmpty()) {
            operationQueues.remove(world, queue); // evict dead entry; two-arg remove avoids TOCTOU
            return;
        }

        // Peek current batch so large batches can be processed in slices across ticks.
        BatchedOperation batch = queue.peek();
        if (batch == null || batch.isCompleted()) {
            if (batch != null) {
                queue.remove(batch);
            }
            return;
        }

        List<Runnable> slice = batch.claimNextSlice(BATCH_SIZE);
        if (slice == null || slice.isEmpty()) {
            return;
        }

        // Execute batch on world thread.
        // world.execute() throws SkipSentryException (RuntimeException) if the world is
        // shutting down. Catch it here so the scheduleAtFixedRate task does NOT propagate
        // the exception — scheduleAtFixedRate silently cancels itself on any uncaught
        // exception, which would permanently kill the batch processor for all worlds.
        try {
            world.execute(() -> {
                try {
                    for (Runnable work : slice) {
                        work.run();
                    }
                    if (batch.finishSuccess(slice.size())) {
                        queue.remove(batch);
                    }
                } catch (Exception e) {
                    errorHandler.handleWorldThreadError(e, "batchedOperation");
                    // Exception during batch execution - fail this batch only.
                    batch.finishFailure(e);
                    queue.remove(batch);
                }
            });
        } catch (Exception e) {
            LOG.fine(() -> "[OptiPortal] processBatch: world.execute() rejected for '"
                    + world.getName() + "' (shutting down) — completing queued batches exceptionally");
            // Rejection during dispatch - fail every queued batch for this world, not just
            // the currently visible slice, so no futures are orphaned.
            failQueuedBatches(queue, e);
            operationQueues.remove(world, queue);
        }
    }

    private void failQueuedBatches(Queue<BatchedOperation> queue, Throwable failure) {
        BatchedOperation queued;
        while ((queued = queue.poll()) != null) {
            queued.fail(failure);
        }
    }
    
    /**
     * Get current circuit breaker state.
     * 
     * @return true if circuit breaker is open
     */
    public boolean isCircuitBreakerOpen() {
        return circuitBreaker.isOpen();
    }
    
    /**
     * Reset circuit breaker manually.
     */
    public void resetCircuitBreaker() {
        circuitBreaker.reset();
    }
    
    /**
     * Player position data class.
     */
    public static class PlayerPosition {
        public final UUID worldUuid;
        public final double x;
        public final double y;
        public final double z;
        
        public PlayerPosition(UUID worldUuid, double x, double y, double z) {
            this.worldUuid = worldUuid;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }
    
    /**
     * Exception thrown when world thread is overloaded.
     */
    public static class WorldThreadOverloadException extends RuntimeException {
        public WorldThreadOverloadException() {
            super("World thread is overloaded, please try again later");
        }
    }
}
