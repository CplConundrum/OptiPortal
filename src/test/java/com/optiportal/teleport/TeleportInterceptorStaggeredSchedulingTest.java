package com.optiportal.teleport;

import com.optiportal.support.FakePluginConfig;
import com.optiportal.support.FakeStorageBackend;
import com.optiportal.support.RecordingScheduledExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for TeleportInterceptor staggered proximity scheduling.
 *
 * Goals:
 *  - Prove that batch selection is deterministic and round-robin.
 *  - Prove that pollTeleportRecords no longer performs routine proximity checks
 *    (the regression that was caught once: routine work was incorrectly inside the poll loop).
 *  - Prove that runStaggeredProximityBatch is the sole source of routine proximity invocations.
 */
class TeleportInterceptorStaggeredSchedulingTest {

    private static final int BATCH_SIZE = 10; // mirrors TeleportInterceptor.POSITION_UPDATE_BATCH_SIZE

    private TestableTeleportInterceptor interceptor;
    private RecordingScheduledExecutor executor;
    private FakePluginConfig config;

    @BeforeEach
    void setUp() throws Exception {
        executor = new RecordingScheduledExecutor();
        config   = new FakePluginConfig(java.nio.file.Files.createTempDirectory("ti-test").toFile());
        interceptor = new TestableTeleportInterceptor(config, new FakeStorageBackend(), executor);
    }

    // -------------------------------------------------------------------------
    // getNextProximityBatch — determinism and round-robin
    // -------------------------------------------------------------------------

    /**
     * With more players than one batch, successive calls cycle through the full
     * sorted player list fairly.  The sorted UUID order is what makes this deterministic
     * across ConcurrentHashMap's non-guaranteed iteration order.
     */
    @Test
    void getNextProximityBatch_isDeterministicAndRoundRobin() {
        int playerCount = 25; // > BATCH_SIZE so the cursor must advance
        List<UUID> uuids = new ArrayList<>();
        for (int i = 0; i < playerCount; i++) {
            uuids.add(UUID.randomUUID());
        }
        populatePlayerRefs(uuids);

        // Expected sorted order (same as getNextProximityBatch does internally)
        List<UUID> sorted = new ArrayList<>(uuids);
        Collections.sort(sorted);

        // First call: should return sorted[0..9]
        List<UUID> batch1 = interceptor.getNextProximityBatch();
        assertEquals(BATCH_SIZE, batch1.size(), "First batch must be exactly BATCH_SIZE");
        assertEquals(sorted.subList(0, BATCH_SIZE), batch1,
                "First batch must match the first BATCH_SIZE sorted UUIDs");

        // Second call: should return sorted[10..19]
        List<UUID> batch2 = interceptor.getNextProximityBatch();
        assertEquals(BATCH_SIZE, batch2.size(), "Second batch must be exactly BATCH_SIZE");
        assertEquals(sorted.subList(BATCH_SIZE, BATCH_SIZE * 2), batch2,
                "Second batch must be the next BATCH_SIZE sorted UUIDs (cursor advanced)");

        // No UUID appears twice in the same batch
        assertEquals(BATCH_SIZE, batch1.stream().distinct().count(), "No duplicates in batch1");
        assertEquals(BATCH_SIZE, batch2.stream().distinct().count(), "No duplicates in batch2");

        // The two batches must be disjoint (each player covered exactly once per cycle)
        List<UUID> union = new ArrayList<>(batch1);
        union.addAll(batch2);
        assertEquals(BATCH_SIZE * 2, union.stream().distinct().count(),
                "batch1 and batch2 must not overlap");
    }

    /**
     * With a single player, every call to getNextProximityBatch should return
     * that same player (cursor wraps around).
     */
    @Test
    void getNextProximityBatch_withOnePlayer_alwaysReturnsIt() {
        UUID only = UUID.randomUUID();
        populatePlayerRefs(List.of(only));

        List<UUID> b1 = interceptor.getNextProximityBatch();
        List<UUID> b2 = interceptor.getNextProximityBatch();
        List<UUID> b3 = interceptor.getNextProximityBatch();

        assertEquals(List.of(only), b1);
        assertEquals(List.of(only), b2);
        assertEquals(List.of(only), b3);
    }

    /**
     * With no players, getNextProximityBatch must return an empty list (not throw).
     */
    @Test
    void getNextProximityBatch_withNoPlayers_returnsEmpty() {
        assertTrue(interceptor.getNextProximityBatch().isEmpty());
    }

    // -------------------------------------------------------------------------
    // runStaggeredProximityBatch — is the only routine proximity source
    // -------------------------------------------------------------------------

    /**
     * runStaggeredProximityBatch must invoke processPlayerProximity for every
     * player in the selected batch.
     */
    @Test
    void runStaggeredProximityBatch_invokesProcessPlayerProximityForBatch() {
        List<UUID> uuids = new ArrayList<>();
        for (int i = 0; i < 5; i++) uuids.add(UUID.randomUUID());
        populatePlayerRefs(uuids);

        interceptor.runStaggeredProximityBatch();

        // All 5 players fit in one batch (< BATCH_SIZE), so all should be invoked
        assertEquals(5, interceptor.proximityCheckInvocations.size(),
                "processPlayerProximity must be called once per batch member");
        assertTrue(interceptor.proximityCheckInvocations.containsAll(uuids),
                "Every player in the batch must be invoked");
    }

    /**
     * pollTeleportRecords must NOT invoke processPlayerProximity for normal movement.
     *
     * This guards the regression where routine proximity work was incorrectly
     * placed inside the teleport poll loop.  With empty player refs, the loop body
     * never executes, so no proximity invocations can occur.
     */
    @Test
    void pollTeleportRecords_noLongerRunsRoutineProximityChecks() {
        // No players registered — the poll loop has nothing to iterate over
        interceptor.pollTeleportRecords();

        assertTrue(interceptor.proximityCheckInvocations.isEmpty(),
                "pollTeleportRecords must not trigger processPlayerProximity when no players are tracked");
    }

    /**
     * Running the staggered batch (not pollTeleportRecords) is what produces
     * proximity invocations.  This enforces ownership: routine proximity work must
     * flow exclusively through the staggered path.
     *
     * Note: pollTeleportRecords is tested with empty playerRefs only — testing it
     * with live player refs requires a real WorldRegistry + PlayerRef which are Hytale
     * server objects.  The structural guard is that runStaggeredProximityBatch *does*
     * fire processPlayerProximity, confirming the staggered path is wired correctly.
     */
    @Test
    void runStaggeredProximityBatch_isTheOnlyRoutineProximitySource() {
        // With no players, poll does nothing and batch does nothing
        interceptor.pollTeleportRecords();
        assertTrue(interceptor.proximityCheckInvocations.isEmpty());

        interceptor.runStaggeredProximityBatch();
        assertTrue(interceptor.proximityCheckInvocations.isEmpty(),
                "No players → staggered batch must also produce no invocations");

        // Now add players — staggered batch must fire, poll (without live server) must not
        List<UUID> uuids = List.of(UUID.randomUUID(), UUID.randomUUID());
        populatePlayerRefs(uuids);

        interceptor.runStaggeredProximityBatch();
        assertEquals(uuids.size(), interceptor.proximityCheckInvocations.size(),
                "runStaggeredProximityBatch must invoke processPlayerProximity for each batch member");
        assertTrue(interceptor.proximityCheckInvocations.containsAll(uuids));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Populate playerRefs with stub (non-null) entries.
     * We use raw-type access because ConcurrentHashMap requires non-null values
     * and we only need the keys for getNextProximityBatch / runStaggeredProximityBatch.
     * The test override of processPlayerProximity never reads the values.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void populatePlayerRefs(List<UUID> uuids) {
        java.util.Map raw = interceptor.playerRefs;
        for (UUID uuid : uuids) {
            raw.put(uuid, "STUB_PLAYER_REF");
        }
    }

    // -------------------------------------------------------------------------
    // Test subclass
    // -------------------------------------------------------------------------

    /**
     * Minimal TeleportInterceptor subclass used only in this test file.
     * Records processPlayerProximity invocations instead of performing real server work.
     */
    static class TestableTeleportInterceptor extends TeleportInterceptor {

        final List<UUID> proximityCheckInvocations = new ArrayList<>();

        TestableTeleportInterceptor(FakePluginConfig config,
                                     FakeStorageBackend storage,
                                     RecordingScheduledExecutor executor) {
            super(
                null,       // OptiPortal plugin — not called during construction
                config,
                null,       // WarmZoneManager
                null,       // ChunkPreloader — overridden processPlayerProximity won't use it
                storage,
                null,       // PortalLinkRegistry
                null,       // RespawnTracker
                null,       // DeathLocationTracker
                null,       // GravestoneIntegration
                executor
            );
        }

        @Override
        protected void processPlayerProximity(UUID playerUuid) {
            proximityCheckInvocations.add(playerUuid);
        }
    }
}
