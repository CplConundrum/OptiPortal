package com.optiportal.async;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Retry policy for async operations with exponential backoff.
 * 
 * This component provides retry logic for async operations that may fail
 * due to temporary conditions, implementing exponential backoff and
 * maximum retry limits.
 */
public class RetryPolicy {
    
    private static final Logger LOG = Logger.getLogger("OptiPortal");
    
    // Configuration
    private static final int MAX_RETRIES = 3;
    private static final long INITIAL_DELAY_MS = 1000;
    private static final double BACKOFF_MULTIPLIER = 2.0;
    private static final long MAX_DELAY_MS = 10000;
    
    protected final ScheduledExecutorService executor;
    
    public RetryPolicy() {
        this.executor = null; // Use default scheduling
    }
    
    public RetryPolicy(ScheduledExecutorService executor) {
        this.executor = executor;
    }
    
    /**
     * Execute an operation with retry logic.
     * 
     * @param operation The operation to execute
     * @param operationType Type of operation for logging
     * @param operationId ID of operation for logging
     * @param <T> Return type
     * @return CompletableFuture with the result
     */
    public <T> CompletableFuture<T> executeWithRetry(Supplier<CompletableFuture<T>> operation,
                                                    String operationType,
                                                    String operationId) {
        return executeWithRetry(operation, operationType, operationId, 0);
    }
    
    /**
     * Execute an operation with retry logic and attempt count.
     * 
     * @param operation The operation to execute
     * @param operationType Type of operation for logging
     * @param operationId ID of operation for logging
     * @param attempt Current attempt number
     * @param <T> Return type
     * @return CompletableFuture with the result
     */
    private <T> CompletableFuture<T> executeWithRetry(Supplier<CompletableFuture<T>> operation,
                                                     String operationType,
                                                     String operationId,
                                                     int attempt) {
        if (attempt >= MAX_RETRIES) {
            LOG.warning("Max retries exceeded for " + operationType + ":" + operationId);
            CompletableFuture<T> future = new CompletableFuture<>();
            future.completeExceptionally(new RetryExhaustedException(
                "Max retries (" + MAX_RETRIES + ") exceeded for " + operationType + ":" + operationId));
            return future;
        }
        
        if (attempt == 0) {
            LOG.fine("Executing " + operationType + ":" + operationId + " (attempt 1)");
        } else {
            LOG.info("Retrying " + operationType + ":" + operationId + " (attempt " + (attempt + 1) + ")");
        }
        
        try {
            CompletableFuture<T> future = operation.get();
            
            return future.handle((result, ex) -> {
                if (ex == null) {
                    // Success
                    if (attempt > 0) {
                        LOG.info("Operation " + operationType + ":" + operationId + 
                                " succeeded on attempt " + (attempt + 1));
                    }
                    return CompletableFuture.completedFuture(result);
                } else {
                    // Failure, schedule retry
                    LOG.warning("Operation " + operationType + ":" + operationId + 
                               " failed on attempt " + (attempt + 1) + ": " + ex.getMessage());
                   
                    long delayMs = calculateDelay(attempt);
                    return scheduleRetry(operation, operationType, operationId, attempt + 1, delayMs);
                }
            }).thenCompose(f -> f);
            
        } catch (Exception e) {
            LOG.warning("Exception executing " + operationType + ":" + operationId + 
                       " on attempt " + (attempt + 1) + ": " + e.getMessage());
           
            long delayMs = calculateDelay(attempt);
            return scheduleRetry(operation, operationType, operationId, attempt + 1, delayMs);
        }
    }
    
    /**
     * Schedule a retry operation.
     * 
     * @param operation The operation to retry
     * @param operationType Type of operation
     * @param operationId ID of operation
     * @param attempt Next attempt number
     * @param delayMs Delay before retry
     * @param <T> Return type
     * @return CompletableFuture for the retry
     */
    private <T> CompletableFuture<T> scheduleRetry(Supplier<CompletableFuture<T>> operation,
                                                  String operationType,
                                                  String operationId,
                                                  int attempt,
                                                  long delayMs) {
        CompletableFuture<T> future = new CompletableFuture<>();
        
        if (executor != null) {
            executor.schedule(() -> {
                executeWithRetry(operation, operationType, operationId, attempt)
                    .whenComplete((result, ex) -> {
                        if (ex == null) {
                            future.complete(result);
                        } else {
                            future.completeExceptionally(ex);
                        }
                    });
            }, delayMs, TimeUnit.MILLISECONDS);
        } else {
            // Fallback to simple delay
            try {
                Thread.sleep(delayMs);
                executeWithRetry(operation, operationType, operationId, attempt)
                    .whenComplete((result, ex) -> {
                        if (ex == null) {
                            future.complete(result);
                        } else {
                            future.completeExceptionally(ex);
                        }
                    });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                future.completeExceptionally(e);
            }
        }
        
        return future;
    }
    
    /**
     * Calculate delay for next retry using exponential backoff.
     * 
     * @param attempt Current attempt number
     * @return Delay in milliseconds
     */
    private long calculateDelay(int attempt) {
        long delay = (long) (INITIAL_DELAY_MS * Math.pow(BACKOFF_MULTIPLIER, attempt));
        return Math.min(delay, MAX_DELAY_MS);
    }
    
    /**
     * Exception thrown when retry attempts are exhausted.
     */
    public static class RetryExhaustedException extends RuntimeException {
        public RetryExhaustedException(String message) {
            super(message);
        }
    }
    
    /**
     * Builder for creating custom retry policies.
     */
    public static class Builder {
        private int maxRetries = MAX_RETRIES;
        private long initialDelayMs = INITIAL_DELAY_MS;
        private double backoffMultiplier = BACKOFF_MULTIPLIER;
        private long maxDelayMs = MAX_DELAY_MS;
        private ScheduledExecutorService executor;
        
        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }
        
        public Builder initialDelay(long initialDelayMs) {
            this.initialDelayMs = initialDelayMs;
            return this;
        }
        
        public Builder backoffMultiplier(double backoffMultiplier) {
            this.backoffMultiplier = backoffMultiplier;
            return this;
        }
        
        public Builder maxDelay(long maxDelayMs) {
            this.maxDelayMs = maxDelayMs;
            return this;
        }
        
        public Builder executor(ScheduledExecutorService executor) {
            this.executor = executor;
            return this;
        }
        
        public RetryPolicy build() {
            return new CustomRetryPolicy(maxRetries, initialDelayMs, backoffMultiplier, maxDelayMs, executor);
        }
    }
    
    /**
     * Custom retry policy implementation.
     */
    private static class CustomRetryPolicy extends RetryPolicy {
        private final int maxRetries;
        private final long initialDelayMs;
        private final double backoffMultiplier;
        private final long maxDelayMs;
        
        public CustomRetryPolicy(int maxRetries, long initialDelayMs, double backoffMultiplier, 
                               long maxDelayMs, ScheduledExecutorService executor) {
            super(executor);
            this.maxRetries = maxRetries;
            this.initialDelayMs = initialDelayMs;
            this.backoffMultiplier = backoffMultiplier;
            this.maxDelayMs = maxDelayMs;
        }
        
        public <T> CompletableFuture<T> executeWithRetry(Supplier<CompletableFuture<T>> operation,
                                                        String operationType,
                                                        String operationId) {
            return executeWithRetry(operation, operationType, operationId, 0);
        }
        
        // Remove the @Override annotation since this is not overriding a method from superclass
        private <T> CompletableFuture<T> executeWithRetry(Supplier<CompletableFuture<T>> operation,
                                                         String operationType,
                                                         String operationId,
                                                         int attempt) {
            if (attempt >= maxRetries) {
                LOG.warning("Max retries exceeded for " + operationType + ":" + operationId);
                CompletableFuture<T> future = new CompletableFuture<>();
                future.completeExceptionally(new RetryExhaustedException(
                    "Max retries (" + maxRetries + ") exceeded for " + operationType + ":" + operationId));
                return future;
            }
            
            if (attempt == 0) {
            LOG.fine("Executing " + operationType + ":" + operationId + " (attempt 1)");
        } else {
            LOG.info("Retrying " + operationType + ":" + operationId + " (attempt " + (attempt + 1) + ")");
        }
            
            try {
                CompletableFuture<T> future = operation.get();
                
                return future.handle((result, ex) -> {
                    if (ex == null) {
                        if (attempt > 0) {
                            LOG.info("Operation " + operationType + ":" + operationId + 
                                    " succeeded on attempt " + (attempt + 1));
                        }
                        return CompletableFuture.completedFuture(result);
                    } else {
                        LOG.warning("Operation " + operationType + ":" + operationId + 
                                   " failed on attempt " + (attempt + 1) + ": " + ex.getMessage());
                        
                        long delayMs = calculateCustomDelay(attempt);
                        return scheduleCustomRetry(operation, operationType, operationId, attempt + 1, delayMs);
                    }
                }).thenCompose(f -> f);
                
            } catch (Exception e) {
                LOG.warning("Exception executing " + operationType + ":" + operationId + 
                           " on attempt " + (attempt + 1) + ": " + e.getMessage());
                
                long delayMs = calculateCustomDelay(attempt);
                return scheduleCustomRetry(operation, operationType, operationId, attempt + 1, delayMs);
            }
        }
        
        private long calculateCustomDelay(int attempt) {
            long delay = (long) (initialDelayMs * Math.pow(backoffMultiplier, attempt));
            return Math.min(delay, maxDelayMs);
        }
        
        private <T> CompletableFuture<T> scheduleCustomRetry(Supplier<CompletableFuture<T>> operation,
                                                            String operationType,
                                                            String operationId,
                                                            int attempt,
                                                            long delayMs) {
            CompletableFuture<T> future = new CompletableFuture<>();
            
            // Fix: Use this.executor instead of just executor to reference the non-static field
            if (this.executor != null) {
                this.executor.schedule(() -> {
                    executeWithRetry(operation, operationType, operationId, attempt)
                        .whenComplete((result, ex) -> {
                            if (ex == null) {
                                future.complete(result);
                            } else {
                                future.completeExceptionally(ex);
                            }
                        });
                }, delayMs, TimeUnit.MILLISECONDS);
            } else {
                try {
                    Thread.sleep(delayMs);
                    executeWithRetry(operation, operationType, operationId, attempt)
                        .whenComplete((result, ex) -> {
                            if (ex == null) {
                                future.complete(result);
                            } else {
                                future.completeExceptionally(ex);
                            }
                        });
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    future.completeExceptionally(e);
                }
            }
            
            return future;
        }
    }
}