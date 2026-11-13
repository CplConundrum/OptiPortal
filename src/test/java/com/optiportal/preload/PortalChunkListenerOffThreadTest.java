package com.optiportal.preload;

import com.optiportal.cache.CacheManager;
import com.optiportal.model.PortalEntry;
import com.optiportal.model.WarmStrategy;
import com.optiportal.support.FakePluginConfig;
import com.optiportal.support.FakeStorageBackend;
import com.optiportal.support.RecordingScheduledExecutor;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Off-thread pass (B): autoRegisterPortalDeviceZone publishes the zone index
 * (entryCache / reverseIndex) SYNCHRONOUSLY on the world thread -- the same
 * chunk event's promoteColdZones and H4 ownership registration run right after
 * autoRegisterPortalDevice returns and must see the new index. Only storage
 * persistence is off-thread (executor).
 */
class PortalChunkListenerOffThreadTest {

    private static long packChunk(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    private static FakePluginConfig cfg() {
        try {
            return new FakePluginConfig(Files.createTempDirectory("opti-chunk-listener-test").toFile());
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    private final RecordingScheduledExecutor executor = new RecordingScheduledExecutor();

    /** B1: new zone -> entryCache entry with PREDICTIVE strategy, reverse index
     *  over the full footprint, and a persisted storage entry. */
    @Test
    void bookkeepingRegistersNewZone() {
        FakeStorageBackend storage = new FakeStorageBackend();
        PortalChunkListener listener = newListener(storage, new ArrayList<>());
        UUID dest = UUID.randomUUID();
        assertTrue(listener.autoRegisterPortalDeviceZone("world-a", 3, 4, dest, true),
                "new zone -> created=true");

        PortalEntry entry = listener.entryCacheLookup("portaldevice:world-a:3:4");
        assertNotNull(entry, "entryCache entry created");
        assertEquals(WarmStrategy.PREDICTIVE, entry.getStrategy());
        assertEquals(PortalEntry.EntryType.MANUAL, entry.getType());
        assertEquals(dest, entry.getDestinationWorldUuid());

        int radius = cfg().getDefaultWarmRadius();
        assertEquals("portaldevice:world-a:3:4",
                listener.reverseIndexLookup("world-a", packChunk(3 + radius, 4 + radius)),
                "reverse index covers the full footprint corner (radius, radius)");
        assertEquals("portaldevice:world-a:3:4",
                listener.reverseIndexLookup("world-a", packChunk(3, 4)),
                "reverse index covers the centre chunk");

        executor.runAllExecuted(); // persistence is the off-thread part
        Optional<PortalEntry> stored = storage.loadById("portaldevice:world-a:3:4");
        assertTrue(stored != null && stored.isPresent(),
                "storage.save ran off-thread for the new entry");
        assertEquals(dest, stored.get().getDestinationWorldUuid(),
                "persisted entry carries the destination UUID");
    }

    /** B2: existing entry with a missing destination UUID -> backfilled and
     *  re-persisted on the second pass. */
    @Test
    void bookkeepingBackfillsMissingDestinationUuid() {
        FakeStorageBackend storage = new FakeStorageBackend();
        PortalChunkListener listener = newListener(storage, new ArrayList<>());
        // First pass: destination UUID not known yet (entry created with null dest)
        assertTrue(listener.autoRegisterPortalDeviceZone("world-b", 1, 2, null, true),
                "first pass creates the entry");
        assertNull(listener.entryCacheLookup("portaldevice:world-b:1:2").getDestinationWorldUuid());

        // Second pass: UUID now known -> existing-entry backfill (created=false)
        UUID dest = UUID.randomUUID();
        assertFalse(listener.autoRegisterPortalDeviceZone("world-b", 1, 2, dest, true),
                "second pass hits the existing-entry backfill path");
        assertEquals(dest,
                listener.entryCacheLookup("portaldevice:world-b:1:2").getDestinationWorldUuid(),
                "existing entry backfilled with the destination UUID");
    }

    /** B3: world that cannot unload -> markNoDecay exempted; unloadable world
     *  -> not exempted. */
    @Test
    void bookkeepingExemptsEternalWorldsFromDecay() {
        FakeStorageBackend storage = new FakeStorageBackend();
        ArrayList<String> noDecayCalls = new ArrayList<>();
        PortalChunkListener listener = newListener(storage, noDecayCalls);
        listener.autoRegisterPortalDeviceZone("eternal", 5, 6, null, false);
        assertEquals(List.of("portaldevice:eternal:5:6"), noDecayCalls,
                "non-unloadable world -> markNoDecay");
        listener.autoRegisterPortalDeviceZone("normal", 7, 8, null, true);
        assertEquals(List.of("portaldevice:eternal:5:6"), noDecayCalls,
                "unloadable world -> no markNoDecay");
    }

    private PortalChunkListener newListener(FakeStorageBackend storage,
                                            List<String> noDecayCalls) {
        CacheManager cacheManager = new CacheManager(cfg(), null, executor, null) {
            @Override
            public void markNoDecay(String zoneId) {
                noDecayCalls.add(zoneId);
            }
        };
        return new PortalChunkListener(storage, cacheManager, null, cfg(), executor);
    }
}
