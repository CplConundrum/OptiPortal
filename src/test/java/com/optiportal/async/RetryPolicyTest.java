package com.optiportal.async;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class RetryPolicyTest {

    @Test
    void noExecutorFallbackRetriesWithoutCallerOwnedScheduler() throws Exception {
        RetryPolicy policy = new RetryPolicy.Builder()
                .maxRetries(2)
                .initialDelay(1)
                .maxDelay(1)
                .build();
        AtomicInteger attempts = new AtomicInteger();

        CompletableFuture<String> result = policy.executeWithRetry(() -> {
            if (attempts.incrementAndGet() == 1) {
                return failedFuture(new IllegalStateException("try again"));
            }
            return CompletableFuture.completedFuture("ok");
        }, "test", "no-executor");

        assertEquals("ok", result.get(2, TimeUnit.SECONDS));
        assertEquals(2, attempts.get());
    }

    @Test
    void exhaustedRetriesCompleteExceptionally() throws Exception {
        RetryPolicy policy = new RetryPolicy.Builder()
                .maxRetries(2)
                .initialDelay(1)
                .maxDelay(1)
                .build();
        AtomicInteger attempts = new AtomicInteger();

        CompletableFuture<String> result = policy.executeWithRetry(() -> {
            attempts.incrementAndGet();
            return failedFuture(new IllegalStateException("still failing"));
        }, "test", "exhausted");

        try {
            result.get(2, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            assertInstanceOf(RetryPolicy.RetryExhaustedException.class, e.getCause());
        }
        assertEquals(2, attempts.get());
    }

    private static <T> CompletableFuture<T> failedFuture(Throwable throwable) {
        CompletableFuture<T> future = new CompletableFuture<>();
        future.completeExceptionally(throwable);
        return future;
    }
}
