package com.optiportal.async;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

/**
 * Handles async operation errors with proper recovery mechanisms.
 * 
 * This component provides comprehensive error handling for async operations,
 * including retry logic, circuit breaker management, and error reporting.
 */
public class AsyncErrorHandler {
    
    private static final Logger LOG = Logger.getLogger("OptiPortal");
    
    private final AsyncMetrics metrics;
    private final CircuitBreaker circuitBreaker;
    private final ScheduledExecutorService executor;
    private final RetryPolicy retryPolicy;
    
    public AsyncErrorHandler(AsyncMetrics metrics,
                           CircuitBreaker circuitBreaker,
                           ScheduledExecutorService executor) {
        this.metrics = metrics;
        this.circuitBreaker = circuitBreaker;
        this.executor = executor;
        this.retryPolicy = new RetryPolicy(executor);
    }
    
    /**
     * Handle world thread errors with appropriate recovery actions.
     * 
     * @param e The exception that occurred
     * @param operation The operation that failed
     */
    public void handleWorldThreadError(Exception e, String operation) {
        metrics.recordWorldThreadError(operation, 0);
        
        if (e instanceof WorldThreadBridge.WorldThreadOverloadException) {
            handleWorldThreadOverload(operation);
        } else if (e instanceof java.util.concurrent.TimeoutException) {
            handleWorldThreadTimeout(operation);
        } else {
            handleGenericWorldThreadError(e, operation);
        }
    }
    
    /**
     * Handle chunk loading errors with retry logic.
     * 
     * @param e The exception that occurred
     * @param cx Chunk X coordinate
     * @param cz Chunk Z coordinate
     */
    public void handleChunkLoadError(Exception e,
                                     com.hypixel.hytale.server.core.universe.world.World world,
                                     int cx, int cz) {
        metrics.recordChunkLoadError(cx, cz, 0);

        String chunkKey = cx + ":" + cz;
        if (isEngineChunkBackoff(e)) {
            LOG.fine("[OptiPortal] Chunk load retry skipped (engine backoff): " + chunkKey);
            return;
        }

        retryPolicy.executeWithRetry(() -> {
            LOG.fine("[OptiPortal] Retrying chunk load for " + chunkKey);
            return retryChunkLoad(world, cx, cz);
        }, "chunkLoad", chunkKey);
    }
    
    /**
     * Handle position update errors.
     * 
     * @param e The exception that occurred
     * @param playerId The player ID
     */
    public void handlePositionUpdateError(Exception e, UUID playerId) {
        metrics.recordPositionUpdateError();
        
        LOG.warning("Position update error for player " + playerId + ": " + e.getMessage());
        
        // Don't retry position updates as they're time-sensitive
        // Just log and continue
    }
    
    /**
     * Handle keepalive operation errors.
     * 
     * @param e The exception that occurred
     * @param tier The cache tier that failed
     */
    public void handleKeepaliveError(Exception e, String tier) {
        metrics.recordKeepaliveError(tier);
        
        LOG.warning("Keepalive error for tier " + tier + ": " + e.getMessage());
        
        // Keepalive errors are non-critical, just log and continue
    }
    
    /**
     * Handle world thread overload scenarios.
     * 
     * @param operation The operation that failed
     */
    private void handleWorldThreadOverload(String operation) {
        LOG.warning("World thread overload detected for operation: " + operation);
        
        // Implement circuit breaker behavior
        circuitBreaker.open();
        
        // Schedule recovery attempt
        executor.schedule(() -> {
            if (circuitBreaker.canAttemptReset()) {
                circuitBreaker.attemptReset();
                LOG.info("World thread circuit breaker reset attempt");
            }
        }, 30, TimeUnit.SECONDS);
    }
    
    /**
     * Handle world thread timeout scenarios.
     * 
     * @param operation The operation that failed
     */
    private void handleWorldThreadTimeout(String operation) {
        LOG.warning("World thread timeout for operation: " + operation);
        
        // Record timeout but don't open circuit breaker immediately
        // Allow some timeouts before opening
        circuitBreaker.recordFailure();
    }
    
    /**
     * Handle generic world thread errors.
     * 
     * @param e The exception that occurred
     * @param operation The operation that failed
     */
    private void handleGenericWorldThreadError(Exception e, String operation) {
        LOG.log(java.util.logging.Level.SEVERE, e, () -> "World thread error for operation " + operation);
        
        circuitBreaker.recordFailure();
    }
    
    /**
     * Retry chunk load operation.
     *
     * @param world The world containing the chunk
     * @param cx    Chunk X coordinate
     * @param cz    Chunk Z coordinate
     * @return CompletableFuture that completes with the loaded chunk, or null on skip
     */
    private CompletableFuture<com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk>
            retryChunkLoad(com.hypixel.hytale.server.core.universe.world.World world, int cx, int cz) {

        // Guard: circuit breaker open — do not hammer the world thread further
        if (circuitBreaker.isOpen()) {
            LOG.fine("[OptiPortal] retryChunkLoad skipped (circuit open): " + cx + "," + cz);
            return CompletableFuture.completedFuture(null);
        }

        // Guard: world null or not ticking
        if (world == null) {
            LOG.fine("[OptiPortal] retryChunkLoad skipped (world null): " + cx + "," + cz);
            return CompletableFuture.completedFuture(null);
        }

        // Guard: engine-level backoff for this chunk
        // The second parameter is a backoff cap (MAX_FAILURE_BACKOFF_NANOS), not a timestamp.
        // This matches Hytale's server implementation where backoff is capped at a fixed value.
        long chunkIndex = ((long)(cx & 0xFFFFFFFF)) | ((long)(cz & 0xFFFFFFFF) << 32);
        try {
            if (world.getChunkStore().isChunkOnBackoff(chunkIndex, ChunkStore.MAX_FAILURE_BACKOFF_NANOS)) {
                LOG.fine("[OptiPortal] retryChunkLoad skipped (engine backoff): " + cx + "," + cz);
                return CompletableFuture.completedFuture(null);
            }
        } catch (Exception e) {
            // isChunkOnBackoff is a read — should not throw, but guard defensively
            LOG.fine("[OptiPortal] retryChunkLoad: isChunkOnBackoff threw: " + e.getMessage());
        }

        LOG.info("[OptiPortal] Retrying chunk load: " + cx + "," + cz);
        return world.getChunkAsync(chunkIndex)
                .handle((chunk, ex) -> {
                    if (ex != null) {
                        if (isEngineChunkBackoff(ex)) {
                            LOG.fine("[OptiPortal] Retry stopped (engine backoff): " + cx + "," + cz);
                            return null;
                        }
                        throw new CompletionException(ex);
                    }
                    if (chunk != null) {
                        circuitBreaker.recordSuccess();
                        LOG.fine("[OptiPortal] Retry succeeded: " + cx + "," + cz);
                    }
                    return chunk;
                });
    }

    static boolean isEngineChunkBackoff(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.startsWith("Chunk failure backoff")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
    
    /** Expose the circuit breaker for status reporting. */
    public CircuitBreaker getCircuitBreaker() {
        return circuitBreaker;
    }

    /**
     * Get current error statistics.
     *
     * @return Error statistics
     */
    public ErrorStats getErrorStats() {
        return metrics.getErrorStats();
    }
    
    /**
     * Error statistics data class.
     */
    public static class ErrorStats {
        public final long worldThreadErrors;
        public final long chunkLoadErrors;
        public final long positionUpdateErrors;
        public final long keepaliveErrors;
        public final double errorRate;
        
        public ErrorStats(long worldThreadErrors, long chunkLoadErrors, 
                         long positionUpdateErrors, long keepaliveErrors, double errorRate) {
            this.worldThreadErrors = worldThreadErrors;
            this.chunkLoadErrors = chunkLoadErrors;
            this.positionUpdateErrors = positionUpdateErrors;
            this.keepaliveErrors = keepaliveErrors;
            this.errorRate = errorRate;
        }
    }
}
