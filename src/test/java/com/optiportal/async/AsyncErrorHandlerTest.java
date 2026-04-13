package com.optiportal.async;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.Test;

class AsyncErrorHandlerTest {

    @Test
    void detectsEngineChunkBackoffMessage() {
        assertTrue(AsyncErrorHandler.isEngineChunkBackoff(
                new IllegalStateException("Chunk failure backoff")));
    }

    @Test
    void detectsWrappedEngineChunkBackoffMessage() {
        assertTrue(AsyncErrorHandler.isEngineChunkBackoff(
                new CompletionException(new IllegalStateException("Chunk failure backoff: 10ms"))));
    }

    @Test
    void ignoresUnrelatedChunkLoadErrors() {
        assertFalse(AsyncErrorHandler.isEngineChunkBackoff(
                new IllegalStateException("Chunk load failed")));
    }
}
