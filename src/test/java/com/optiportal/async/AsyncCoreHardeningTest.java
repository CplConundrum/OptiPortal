package com.optiportal.async;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.optiportal.support.RecordingScheduledExecutor;

/**
 * Stage-2 improvement pass — async-core hardening tests:
 *
 * <ul>
 *   <li>A1 — a queued task whose supplier throws synchronously at dispatch time must
 *       complete the waiting caller's future exceptionally (previously it hung forever).</li>
 *   <li>A2 — the drain processor must re-apply the critical-load admission gate: while the
 *       TPS monitor reports critical load, non-CRITICAL queued work stays queued.</li>
 *   <li>A4 — the error handler is the SINGLE owner of circuit-breaker failure accounting
 *       (each handled error records exactly one failure; WorldThreadBridge's lambda-catch
 *       path no longer double-records).</li>
 *   <li>A6 — {@code LoadStats} carries the authoritative TPS from WorldTpsMonitor
 *       (-1.0 when no monitor is attached).</li>
 * </ul>
 *
 * The TPS monitor is subclassed to force the critical state — the engine's World sampling
 * machinery is out of scope for these tests (A3/A5 are verified by construction:
 * {@code ConcurrentHashMap.compute/computeIfPresent} atomicity and the widened clamp).
 */
class AsyncCoreHardeningTest {

    private RecordingScheduledExecutor executor;
    private AsyncMetrics metrics;
    private CircuitBreaker circuitBreaker;
    private AsyncErrorHandler errorHandler;

    @BeforeEach
    void setUp() {
        executor = new RecordingScheduledExecutor();
        metrics = new AsyncMetrics();
        circuitBreaker = new CircuitBreaker();
        errorHandler = new AsyncErrorHandler(metrics, circuitBreaker, executor);
    }

    /** A TPS monitor that permanently reports critical load at 5.0 TPS. */
    private WorldTpsMonitor criticalMonitor() {
        return new WorldTpsMonitor(null, executor) {
            @Override
            public boolean isCriticallyLoaded() {
                return true;
            }

            @Override
            public double getCurrentTps() {
                return 5.0;
            }
        };
    }

    private void runProcessQueuedTasks(AsyncLoadBalancer lb) throws Exception {
        Method m = AsyncLoadBalancer.class.getDeclaredMethod("processQueuedTasks");
        m.setAccessible(true);
        m.invoke(lb);
    }

    @Test
    void queuedTaskWithSyncThrowCompletesCallerFutureExceptionally() throws Exception {
        // The monitor is critical at scheduleLoad time (forcing the QUEUE path for
        // non-CRITICAL work) and recovered before the drain tick, so the queued task
        // is actually dispatched and the throwing supplier is reached.
        final boolean[] critical = {true};
        WorldTpsMonitor monitor = new WorldTpsMonitor(null, executor) {
            @Override
            public boolean isCriticallyLoaded() {
                return critical[0];
            }

            @Override
            public double getCurrentTps() {
                return critical[0] ? 5.0 : 20.0;
            }
        };
        AsyncLoadBalancer lb = new AsyncLoadBalancer(executor, metrics, errorHandler, monitor);
        CompletableFuture<Void> future = lb.scheduleLoad(() -> {
            throw new IllegalStateException("sync throw at dispatch");
        }, AsyncMetrics.AsyncTaskPriority.NORMAL);

        assertFalse(future.isDone(), "Queued task must not complete before the drain processor runs");
        critical[0] = false; // server recovers before the drain tick
        runProcessQueuedTasks(lb);

        // A1: without the try/catch in queueOperation's lambda the caller's future is never
        // completed (executeOperation's catch swallows the sync throw) — this assertion
        // is the discriminating check.
        assertTrue(future.isCompletedExceptionally(),
                "A1: queued caller's future must complete exceptionally on a sync supplier throw");
        try {
            future.join();
            throw new AssertionError("join() should have thrown");
        } catch (CompletionException ce) {
            assertTrue(ce.getCause() instanceof IllegalStateException,
                    "A1: the caller must see the supplier's original exception");
        }
    }

    @Test
    void drainHonoursCriticalAdmissionGate() throws Exception {
        AsyncLoadBalancer lb =
                new AsyncLoadBalancer(executor, metrics, errorHandler, criticalMonitor());
        AtomicBoolean criticalRan = new AtomicBoolean();
        AtomicBoolean normalRan = new AtomicBoolean();
        lb.scheduleLoad(() -> {
            criticalRan.set(true);
            return CompletableFuture.completedFuture(null);
        }, AsyncMetrics.AsyncTaskPriority.CRITICAL);
        lb.scheduleLoad(() -> {
            normalRan.set(true);
            return CompletableFuture.completedFuture(null);
        }, AsyncMetrics.AsyncTaskPriority.NORMAL);

        runProcessQueuedTasks(lb);

        assertTrue(criticalRan.get(),
                "CRITICAL work must still drain while the server is critically loaded");
        assertFalse(normalRan.get(),
                "A2: non-CRITICAL work must stay queued while the TPS monitor reports critical load");
    }

    @Test
    void errorHandlerOwnsBreakerAccounting() {
        // A4: the error handler is the single owner of breaker accounting.
        // Semantics per handler (verified in AsyncErrorHandler):
        //   overload  -> circuitBreaker.open() directly (emergency state; no failure count)
        //   timeout   -> exactly one recordFailure
        //   generic   -> exactly one recordFailure
        int before = circuitBreaker.getStats().failureCount;

        errorHandler.handleWorldThreadError(new WorldThreadBridge.WorldThreadOverloadException(), "op");
        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState(),
                "A4: overload must open the breaker directly");
        assertEquals(before, circuitBreaker.getStats().failureCount,
                "A4: overload must not double-count into the failure counter");

        errorHandler.handleWorldThreadError(new TimeoutException("tick stall"), "op");
        assertEquals(before + 1, circuitBreaker.getStats().failureCount,
                "A4: timeout error must record exactly one breaker failure");

        // Note: the breaker is OPEN after the overload open() — recordFailure in OPEN
        // state is a no-op on the count, so reset state first for the counting checks.
        // (recordSuccess/HALF_OPEN reset is not exposed; use a fresh breaker instead.)
        CircuitBreaker fresh = new CircuitBreaker();
        AsyncErrorHandler freshHandler = new AsyncErrorHandler(metrics, fresh, executor);
        freshHandler.handleWorldThreadError(new TimeoutException("tick stall"), "op");
        assertEquals(1, fresh.getStats().failureCount,
                "A4: timeout error must record exactly one breaker failure");
        freshHandler.handleWorldThreadError(new IllegalStateException("boom"), "op");
        assertEquals(2, fresh.getStats().failureCount,
                "A4: generic error must record exactly one breaker failure");
    }

    @Test
    void loadStatsCarriesAuthoritativeTps() {
        // A6: LoadStats surfaces the authoritative TPS from the monitor.
        AsyncLoadBalancer withMonitor =
                new AsyncLoadBalancer(executor, metrics, errorHandler, criticalMonitor());
        assertEquals(5.0, withMonitor.getLoadStats().currentTps, 1e-9,
                "A6: LoadStats must carry the monitor's authoritative TPS");

        AsyncLoadBalancer withoutMonitor =
                new AsyncLoadBalancer(executor, metrics, errorHandler);
        assertEquals(-1.0, withoutMonitor.getLoadStats().currentTps, 1e-9,
                "A6: -1.0 marks the absent TPS monitor");
    }
}
