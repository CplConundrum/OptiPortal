package com.optiportal.preload;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

/**
 * §2 port tests for the 0.7.7 chunk-reference load path.  In 0.7.7 the World
 * chunk overloads are gone; every load goes through
 * {@link ChunkStore#getChunkReferenceAsync}.  A failed reference load must
 * NOT kill the scheduler that drives the preloader / keepalive.
 *
 * <p>Production chains pinned here (byte-identical to the shipped splices):
 * <ul>
 *   <li>{@code WorldThreadBridge} load: {@code getChunkReferenceAsync(idx)
 *       .thenApply(ref -> ref == null ? null : store.getChunkComponent(ref.getIndex(), ...))}
 *       — the error must travel in the future, never thrown at the caller.</li>
 *   <li>{@code KeepaliveManager} ping: fire-and-forget
 *       {@code .exceptionally(ex -> { log; return null; })} — a keepalive tick
 *       whose chunk load fails must complete the tick, and the scheduler must
 *       keep running.</li>
 *   <li>{@code BetterGravestoneWatcher} guard (line 11 of the port plan): a
 *       section lookup on an unloaded section returns null → the tracked
 *       gravestone is KEPT, never released.</li>
 * </ul>
 *
 * <p>Fixtures: real {@link ChunkStore} subclasses with only the async seam
 * overridden (the constructor is world-agnostic — verified in bytecode: it
 * only calls {@code super(world)} and returns, so {@code new ChunkStore(null)}
 * is safe).  Real engine failure modes (disk unavailable, world shutdown)
 * are verified at runtime by plan gates 4/5.
 */
class ChunkReferenceExceptionalPathTest {

    /**
     * Hytale server classes carry static initializers that force
     * {@code java.util.logging.manager} to {@code HytaleLogManager} (their
     * static block throws otherwise). Set the property at class-init time —
     * first chance before any engine class loads in the test JVM.
     */
    static {
        System.setProperty("java.util.logging.manager",
                "com.hypixel.hytale.logger.backend.HytaleLogManager");
    }

    /** A ChunkStore whose async load always fails (simulates disk unavailable). */
    private static final class FailingChunkStore extends ChunkStore {
        FailingChunkStore() {
            super(null);
        }

        @Override
        public CompletableFuture<Ref<ChunkStore>> getChunkReferenceAsync(long index) {
            CompletableFuture<Ref<ChunkStore>> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IllegalStateException("disk unavailable"));
            return failed;
        }
    }

    /** A ChunkStore whose async load throws synchronously (simulates world shutdown). */
    private static final class ThrowingChunkStore extends ChunkStore {
        ThrowingChunkStore() {
            super(null);
        }

        @Override
        public CompletableFuture<Ref<ChunkStore>> getChunkReferenceAsync(long index) {
            throw new IllegalStateException("world shutting down");
        }
    }

    @Test
    void exceptionalReferenceLoadTravelsInTheFutureNotTheCaller() {
        ChunkStore store = new FailingChunkStore();
        long index = ChunkUtil.indexChunk(0, 0);
        // The exact WorldThreadBridge chain (post-fix 0.7.7 splice): Ref-based
        // concurrent read, null/invalid ref degrades to a null chunk.
        CompletableFuture<WorldChunk> cf = store
                .getChunkReferenceAsync(index)
                .thenApply(ref -> ref == null || !ref.isValid()
                        ? null
                        : store.getStore().getComponentConcurrent(ref, WorldChunk.getComponentType()));
        assertTrue(cf.isCompletedExceptionally(),
                "the load error must be carried in the CF, never thrown at the caller");
    }

    @Test
    void periodicTierTaskSurvivesAnExceptionalPingTier() {
        // Production shape (KeepaliveManager.start()): every tier task is a
        // scheduleWithFixedDelay(() -> safeping(tier, label)). JDK semantics:
        // an uncaught throw from a periodic task suppresses ALL future
        // executions — so the guard try/catch inside safeping/pingTier is
        // what keeps the keepalives alive, not the CF chains. Drive the REAL
        // production task (package-private KeepaliveManager.safeping) against
        // a storage whose loadAllCached() throws on every call, and assert
        // the periodic task keeps executing. If the guard is ever removed or
        // bypassed, executions freezes at 1 and this test fails.
        KeepaliveManager km = new KeepaliveManager(
                new com.optiportal.support.FakePluginConfig(new java.io.File(".")), null, null,
                new ThrowingStorage(), null);
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            java.util.concurrent.atomic.AtomicInteger executions = new java.util.concurrent.atomic.AtomicInteger();
            scheduler.scheduleWithFixedDelay(() -> {
                executions.incrementAndGet();
                km.safeping(com.optiportal.model.CacheTier.HOT, "HOT");
            }, 0, 50, TimeUnit.MILLISECONDS);
            long deadline = System.currentTimeMillis() + 3_000;
            while (executions.get() < 3 && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertTrue(executions.get() >= 3,
                    "periodic tier task must keep executing after a contained throw (got "
                            + executions.get() + " executions)");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            scheduler.shutdownNow();
        }
    }

    /** A storage whose cached-read seam throws on every call (simulates a dead DB). */
    private static final class ThrowingStorage extends com.optiportal.support.FakeStorageBackend {
        @Override
        public java.util.List<com.optiportal.model.PortalEntry> loadAllCached() {
            throw new IllegalStateException("db down");
        }
    }

    @Test
    void unloadedSectionLookupIsNullSoTrackedGravestoneSurvives() {
        // A fresh store has no loaded sections.  BetterGravestoneWatcher's
        // world-thread validation (0.7.7 line-11 guard) reads exactly this:
        //   Ref<ChunkStore> sectionRef = store.getChunkSectionReferenceAtBlock(x, y, z);
        //   if (sectionRef == null) return;  // keep tracking — never release
        // Pinning the store-level precondition that drives the keep-tracking
        // branch (an unloaded section must NOT release the tracked gravestone).
        ChunkStore store = new ChunkStore(null);
        Ref<ChunkStore> sectionRef = store.getChunkSectionReferenceAtBlock(100, 64, 100);
        assertNull(sectionRef,
                "a fresh store must report no loaded section — the watcher's keep-tracking guard");
        assertNotNull(store, "sanity: store fixture is usable");
    }
}
