package com.optiportal.teleport;

import com.optiportal.support.FakePluginConfig;
import com.optiportal.support.FakeStorageBackend;
import com.optiportal.support.RecordingScheduledExecutor;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.modules.entity.teleport.TeleportRecord;
import com.hypixel.hytale.server.core.universe.world.World;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Off-thread poll pass (A + C1): pollTeleportRecords reads TeleportRecord via
 * the concurrent-safe getComponentConcurrent (zero per-player world tasks),
 * batches transform capture to one world task per world per poll, and runs
 * jump detection + position bookkeeping off-thread. C1: the staggered
 * proximity path carries only the transform capture on the world thread.
 */
class TeleportInterceptorOffThreadPollTest {

    private final UUID uuid = UUID.randomUUID();

    private static FakePluginConfig cfg() {
        try {
            return new FakePluginConfig(Files.createTempDirectory("opti-poll-test").toFile());
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    private final TestablePollInterceptor interceptor = new TestablePollInterceptor(
            cfg(), new FakeStorageBackend(), new RecordingScheduledExecutor());

    private void seedPlayer() {
        interceptor.playerRefs.put(uuid, new PlayerRef(null, uuid, "name", "Display", null, null));
    }

    /** A1: fresh record + live world -> lastSeen updated on the scheduler thread
     *  (no world task involved), exactly one snapshot dispatch for the poll world. */
    @Test
    void pollUpdatesLastSeenWithoutWorldTasks() {
        seedPlayer();
        long ts = System.nanoTime() - 100_000_000L; // 0.1s old — inside the 3s window
        interceptor.stubbedRecord = new TeleportRecord();
        interceptor.stubbedRecord.setLastTeleport(new TeleportRecord.Entry(null, null, ts));
        interceptor.pollTeleportRecords();
        assertEquals(Long.valueOf(ts), interceptor.lastSeenTeleportNanosFor(uuid),
                "lastSeen must be updated off-thread by the poll itself (no world task)");
        assertEquals(1, interceptor.dispatchCalls,
                "one transform-snapshot dispatch for the single poll world");
    }

    /** A2: two players in one world -> pollWorlds dedupes to a single dispatch
     *  (O(worlds) world tasks per poll, not O(players)). */
    @Test
    void pollDedupesSnapshotDispatchPerWorld() {
        seedPlayer();
        UUID other = UUID.randomUUID();
        interceptor.playerRefs.put(other, new PlayerRef(null, other, "n2", "D2", null, null));
        interceptor.pollTeleportRecords();
        assertEquals(1, interceptor.dispatchCalls,
                "2 players in 1 sentinel world = 1 dispatch (deduped)");
    }

    /** A3: world gone -> stale ref + per-player maps cleaned (no crash). */
    @Test
    void pollCleansUpPlayersWithNoWorld() {
        seedPlayer();
        interceptor.worldAlive = false;
        interceptor.pollTeleportRecords();
        assertTrue(interceptor.playerRefs.isEmpty(), "stale playerRef removed");
        assertNull(interceptor.lastKnownPositionFor(uuid), "stale position removed");
    }

    /** A4: stale-cycle snapshots are skipped; current-cycle snapshots update
     *  lastKnownPosition. */
    @Test
    void snapshotProcessingSkipsStaleCycles() {
        seedPlayer();
        interceptor.transformSnaps.put(uuid,
                new TeleportInterceptor.TransformSnap(10, 20, 30, "w", 1L));
        interceptor.processPositionSnapshots(2L);
        assertNull(interceptor.lastKnownPositionFor(uuid),
                "stale-cycle snapshot must not update lastKnownPosition");

        interceptor.transformSnaps.put(uuid,
                new TeleportInterceptor.TransformSnap(12, 22, 32, "w", 2L));
        interceptor.processPositionSnapshots(2L);
        assertArrayEquals(new double[]{12, 22, 32}, interceptor.lastKnownPositionFor(uuid));
    }

    /** C1: post-capture proximity work runs off-thread; unchanged position
     *  within the skip window does not re-check. */
    @Test
    void proximityPostCaptureRunsOffThreadAndHonoursSkipCheck() {
        UUID u2 = UUID.randomUUID();
        interceptor.playerRefs.put(u2, new PlayerRef(null, u2, "n3", "D3", null, null));

        interceptor.onProximityTransformCaptured(u2, "w", 1, 2, 3);
        interceptor.executor.runAllExecuted();
        assertEquals(1, interceptor.proximityCalls.size(),
                "first capture at a new position must run the proximity check");

        interceptor.onProximityTransformCaptured(u2, "w", 1, 2, 3);
        interceptor.executor.runAllExecuted();
        assertEquals(1, interceptor.proximityCalls.size(),
                "unchanged position must be skipped by the skip check (no re-check)");
    }

    /** Testable subclass: engine-free overrides of the poll seams. The null
     *  World sentinel keeps the pollWorlds dedupe observable without an engine
     *  World instance (fakes cannot construct one). */
    static class TestablePollInterceptor extends TeleportInterceptor {
        final RecordingScheduledExecutor executor;
        final List<String> proximityCalls = new ArrayList<>();
        TeleportRecord stubbedRecord;
        boolean worldAlive = true;
        int dispatchCalls;

        TestablePollInterceptor(FakePluginConfig config, FakeStorageBackend storage,
                                RecordingScheduledExecutor executor) {
            super(null, config, null, null, storage, null, null, null, null, executor);
            this.executor = executor;
        }

        @Override
        protected boolean ensureTrackedWorld(PlayerRef pRef) {
            if (!worldAlive) return false;
            pollWorlds.add(null); // sentinel world — dedupe still observable
            return true;
        }

        @Override
        protected String trackedWorldNameFor(PlayerRef pRef) {
            return worldAlive ? "w-test" : null;
        }

        @Override
        protected TeleportRecord readTeleportRecordConcurrent(PlayerRef pRef) {
            return stubbedRecord;
        }

        @Override
        protected void dispatchTransformSnapshot(World world, long cycle) {
            dispatchCalls++;
        }

        @Override
        protected void performProximityCheck(UUID playerUuid, String worldName,
                                              double cx, double cy, double cz) {
            proximityCalls.add(playerUuid + "@" + worldName);
        }
    }
}
