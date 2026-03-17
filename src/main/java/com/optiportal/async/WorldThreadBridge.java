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

import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.modules.entity.teleport.TeleportRecord;
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
    
    // Operation queues for batching
    private final ConcurrentHashMap<World, Queue<Runnable>> operationQueues;
    
    // Batch processing configuration
    private static final int BATCH_SIZE = 10;
    private static final int BATCH_TIMEOUT_MS = 50;
    
    public WorldThreadBridge(ScheduledExecutorService executor, 
                            AsyncErrorHandler errorHandler,
                            AsyncMetrics metrics) {
        this.executor = executor;
        this.errorHandler = errorHandler;
        this.metrics = metrics;
        this.circuitBreaker = new CircuitBreaker();
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
        
        long startTime = System.currentTimeMillis();
        
        // Schedule on world thread with timeout protection
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
        Queue<Runnable> queue = operationQueues.computeIfAbsent(world, k -> new ConcurrentLinkedQueue<>());
        queue.addAll(operations);
        
        // Return a future that completes when the batch is processed
        CompletableFuture<Void> future = new CompletableFuture<>();
        
        // Store future for completion when batch is processed
        queue.add(() -> future.complete(null));
        
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
        
        long startTime = System.currentTimeMillis();
        
        CompletableFuture<WorldChunk> future = nonTicking
                ? world.getNonTickingChunkAsync(cx, cz)
                : world.getChunkAsync(cx, cz);
        
        return future.whenComplete((chunk, ex) -> {
            long duration = System.currentTimeMillis() - startTime;
            
            if (ex != null) {
                metrics.recordChunkLoadError(cx, cz, duration);
                if (ex instanceof Exception) {
                    errorHandler.handleChunkLoadError((Exception) ex, cx, cz);
                } else {
                    errorHandler.handleChunkLoadError(new Exception("Chunk load failed", ex), cx, cz);
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
            operationQueues.forEach(this::processBatch);
        }, 0, BATCH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }
    
    /**
     * Process a batch of operations for a world.
     * 
     * @param world The world to process for
     * @param queue The operation queue
     */
    private void processBatch(World world, Queue<Runnable> queue) {
        if (queue.isEmpty()) {
            return;
        }
        
        List<Runnable> batch = new ArrayList<>();
        for (int i = 0; i < BATCH_SIZE && !queue.isEmpty(); i++) {
            Runnable operation = queue.poll();
            if (operation != null) {
                batch.add(operation);
            }
        }
        
        if (batch.isEmpty()) {
            return;
        }
        
        // Execute batch on world thread
        world.execute(() -> {
            for (Runnable operation : batch) {
                try {
                    operation.run();
                } catch (Exception e) {
                    errorHandler.handleWorldThreadError(e, "batchedOperation");
                }
            }
        });
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