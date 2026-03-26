package com.optiportal.preload;

/**
 * Thrown by loadChunks / loadChunksAsync when a load is intentionally aborted
 * due to resource pressure (JVM heap ≥ 80% or chunk count at ceiling).
 *
 * Using a failed future rather than completedFuture(null) ensures that
 * thenRun / thenRunAsync callbacks — which would otherwise promote the zone
 * to HOT — do not fire when the load never happened (U1 fix).
 *
 * Callers that need to distinguish an abort from a genuine load failure can
 * catch this specific type in an .exceptionally() handler. All other callers
 * can let the exception propagate silently via an unobserved failed future.
 */
public class ChunkLoadAbortedException extends RuntimeException {

    public ChunkLoadAbortedException(String reason) {
        super(reason, null, true, false); // suppress stack trace — this is flow control, not an error
    }
}
