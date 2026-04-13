package com.optiportal.async;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Retry policy for async operations with exponential backoff.
 *
 * Instances are immutable after construction. Use the no-arg constructor for
 * default settings, the single-executor constructor when a shared pool is
 * available, or {@link Builder} for full customisation.
 *
 * Default values: maxRetries=3, initialDelay=1s, backoffMultiplier=2.0, maxDelay=10s.
 */
public class RetryPolicy {

    private static final Logger LOG = Logger.getLogger("OptiPortal");

    private static final int    DEFAULT_MAX_RETRIES        = 3;
    private static final long   DEFAULT_INITIAL_DELAY_MS   = 1000;
    private static final double DEFAULT_BACKOFF_MULTIPLIER = 2.0;
    private static final long   DEFAULT_MAX_DELAY_MS       = 10000;

    private final int    maxRetries;
    private final long   initialDelayMs;
    private final double backoffMultiplier;
    private final long   maxDelayMs;
    // Visible to Builder (same top-level class scope).
    final ScheduledExecutorService executor;

    /** Default settings, no shared executor. */
    public RetryPolicy() {
        this(DEFAULT_MAX_RETRIES, DEFAULT_INITIAL_DELAY_MS, DEFAULT_BACKOFF_MULTIPLIER,
             DEFAULT_MAX_DELAY_MS, null);
    }

    /** Default settings with a shared executor for scheduling retries. */
    public RetryPolicy(ScheduledExecutorService executor) {
        this(DEFAULT_MAX_RETRIES, DEFAULT_INITIAL_DELAY_MS, DEFAULT_BACKOFF_MULTIPLIER,
             DEFAULT_MAX_DELAY_MS, executor);
    }

    /** Full constructor - called by Builder. */
    private RetryPolicy(int maxRetries, long initialDelayMs, double backoffMultiplier,
                        long maxDelayMs, ScheduledExecutorService executor) {
        this.maxRetries        = maxRetries;
        this.initialDelayMs    = initialDelayMs;
        this.backoffMultiplier = backoffMultiplier;
        this.maxDelayMs        = maxDelayMs;
        this.executor          = executor;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Execute an operation with retry logic.
     *
     * @param operation     Supplier producing a new CompletableFuture each call
     * @param operationType Label used in log messages
     * @param operationId   Identifier used in log messages
     * @param <T>           Result type
     * @return CompletableFuture that resolves when the operation succeeds, or
     *         fails with {@link RetryExhaustedException} when retries run out
     */
    public <T> CompletableFuture<T> executeWithRetry(Supplier<CompletableFuture<T>> operation,
                                                     String operationType,
                                                     String operationId) {
        return executeWithRetry(operation, operationType, operationId, 0);
    }

    // -------------------------------------------------------------------------
    // Internal retry loop
    // -------------------------------------------------------------------------

    private <T> CompletableFuture<T> executeWithRetry(Supplier<CompletableFuture<T>> operation,
                                                      String operationType,
                                                      String operationId,
                                                      int attempt) {
        if (attempt >= maxRetries) {
            LOG.warning(() -> "Max retries exceeded for " + operationType + ":" + operationId);
            CompletableFuture<T> failed = new CompletableFuture<>();
            failed.completeExceptionally(new RetryExhaustedException(
                "Max retries (" + maxRetries + ") exceeded for " + operationType + ":" + operationId));
            return failed;
        }

        if (attempt == 0) {
            LOG.fine(() -> "Executing " + operationType + ":" + operationId + " (attempt 1)");
        } else {
            LOG.info(() -> "Retrying " + operationType + ":" + operationId + " (attempt " + (attempt + 1) + ")");
        }

        try {
            return operation.get().handle((result, ex) -> {
                if (ex == null) {
                    if (attempt > 0) {
                        LOG.info(() -> "Operation " + operationType + ":" + operationId
                                + " succeeded on attempt " + (attempt + 1));
                    }
                    return CompletableFuture.completedFuture(result);
                }
                LOG.warning(() -> "Operation " + operationType + ":" + operationId
                        + " failed on attempt " + (attempt + 1) + ": " + ex.getMessage());
                return scheduleRetry(operation, operationType, operationId,
                        attempt + 1, calculateDelay(attempt));
            }).thenCompose(f -> f);
        } catch (Exception e) {
            LOG.warning(() -> "Exception executing " + operationType + ":" + operationId
                    + " on attempt " + (attempt + 1) + ": " + e.getMessage());
            return scheduleRetry(operation, operationType, operationId,
                    attempt + 1, calculateDelay(attempt));
        }
    }

    private <T> CompletableFuture<T> scheduleRetry(Supplier<CompletableFuture<T>> operation,
                                                   String operationType,
                                                   String operationId,
                                                   int attempt,
                                                   long delayMs) {
        CompletableFuture<T> future = new CompletableFuture<>();
        var delayedExecutor = executor != null
                ? CompletableFuture.delayedExecutor(delayMs, TimeUnit.MILLISECONDS, executor)
                : CompletableFuture.delayedExecutor(delayMs, TimeUnit.MILLISECONDS);

        CompletableFuture.runAsync(() ->
                executeWithRetry(operation, operationType, operationId, attempt)
                    .whenComplete((result, ex) -> complete(future, result, ex)),
                delayedExecutor)
            .whenComplete((ignored, ex) -> {
                if (ex != null) future.completeExceptionally(ex);
            });

        return future;
    }

    private static <T> void complete(CompletableFuture<T> future, T result, Throwable ex) {
        if (ex == null) future.complete(result);
        else future.completeExceptionally(ex);
    }

    private long calculateDelay(int attempt) {
        return Math.min((long) (initialDelayMs * Math.pow(backoffMultiplier, attempt)), maxDelayMs);
    }

    // -------------------------------------------------------------------------
    // Nested types
    // -------------------------------------------------------------------------

    /** Exception thrown when all retry attempts are exhausted. */
    public static class RetryExhaustedException extends RuntimeException {
        public RetryExhaustedException(String message) {
            super(message);
        }
    }

    /** Fluent builder for custom retry settings. */
    public static class Builder {
        private int    maxRetries        = DEFAULT_MAX_RETRIES;
        private long   initialDelayMs    = DEFAULT_INITIAL_DELAY_MS;
        private double backoffMultiplier = DEFAULT_BACKOFF_MULTIPLIER;
        private long   maxDelayMs        = DEFAULT_MAX_DELAY_MS;
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
            return new RetryPolicy(maxRetries, initialDelayMs, backoffMultiplier, maxDelayMs, executor);
        }
    }
}
