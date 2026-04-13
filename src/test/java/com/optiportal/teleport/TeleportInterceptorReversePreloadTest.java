package com.optiportal.teleport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.optiportal.model.PortalEntry;
import com.optiportal.support.FakePluginConfig;
import com.optiportal.support.FakeStorageBackend;
import com.optiportal.support.RecordingScheduledExecutor;

class TeleportInterceptorReversePreloadTest {

    private TestableTeleportInterceptor interceptor;
    private FakePluginConfig config;
    private FakeStorageBackend storage;

    @BeforeEach
    void setUp() throws Exception {
        config = new FakePluginConfig(Files.createTempDirectory("reverse-preload-test").toFile());
        storage = new FakeStorageBackend();
    }

    @Test
    void reversePreloadOriginUsesLinearCandidateRadiusAndChoosesNearestPortal() {
        config.setActivationDistance(10.0);

        PortalEntry farther = portal("farther", "dest-world", 18.0, 0.0, 0.0);
        PortalEntry nearer = portal("nearer", "dest-world", 12.0, 0.0, 0.0);
        interceptor = newInterceptorWithPortals(farther, nearer);

        interceptor.reversePreloadOrigin(
                UUID.randomUUID(),
                null,
                "source-world",
                "dest-world",
                10.0, 64.0, 0.0,
                new double[]{0.0, 64.0, 0.0});

        assertEquals(20.0, interceptor.lastCandidateRadius, 0.0001,
                "Candidate lookup should receive linear max distance, not squared distance");
        assertEquals("nearer", interceptor.predictedZoneId,
                "Nearest portal should be selected for reverse preload");
        assertEquals("dest-world", interceptor.predictedWorldName);
    }

    @Test
    void reversePreloadOriginSkipsWhenNearestPortalIsOutsideMaxDistance() {
        config.setActivationDistance(10.0);

        PortalEntry tooFar = portal("too-far", "dest-world", 31.0, 0.0, 0.0);
        interceptor = newInterceptorWithPortals(tooFar);

        interceptor.reversePreloadOrigin(
                UUID.randomUUID(),
                null,
                "source-world",
                "dest-world",
                10.0, 64.0, 0.0,
                new double[]{0.0, 64.0, 0.0});

        assertEquals(20.0, interceptor.lastCandidateRadius, 0.0001);
        assertNull(interceptor.predictedZoneId, "No preload should fire for out-of-range candidate");
    }

    @Test
    void refreshPortalCacheRebuildsRealSpatialIndexFromUpdatedStorage() {
        config.setActivationDistance(10.0);
        interceptor = newInterceptorWithPortals();

        storage.save(portal("after-refresh", "dest-world", 12.0, 0.0, 0.0));
        interceptor.refreshPortalCache();

        interceptor.reversePreloadOrigin(
                UUID.randomUUID(),
                null,
                "source-world",
                "dest-world",
                10.0, 64.0, 0.0,
                new double[]{0.0, 64.0, 0.0});

        assertEquals("after-refresh", interceptor.predictedZoneId,
                "refreshPortalCache should publish updated storage data to the real spatial index");
    }

    private static PortalEntry portal(String id, String world, double x, double y, double z) {
        return new PortalEntry(id, world, x, y, z, 0);
    }

    private TestableTeleportInterceptor newInterceptorWithPortals(PortalEntry... portals) {
        storage.saveAll(List.of(portals));
        return new TestableTeleportInterceptor(config, storage, new RecordingScheduledExecutor());
    }

    static class TestableTeleportInterceptor extends TeleportInterceptor {

        double lastCandidateRadius = -1.0;
        String predictedZoneId;
        String predictedWorldName;

        TestableTeleportInterceptor(FakePluginConfig config,
                                    FakeStorageBackend storage,
                                    RecordingScheduledExecutor executor) {
            super(
                    null,
                    config,
                    null,
                    null,
                    storage,
                    null,
                    null,
                    null,
                    null,
                    executor
            );
        }

        @Override
        protected List<PortalEntry> getNearbyPlainPortalCandidates(String worldName, double x, double z, double radiusBlocks) {
            lastCandidateRadius = radiusBlocks;
            return super.getNearbyPlainPortalCandidates(worldName, x, z, radiusBlocks);
        }

        @Override
        protected void predictiveLoadWithRam(String zoneId, String worldName, int cx, int cz, int radius) {
            predictedZoneId = zoneId;
            predictedWorldName = worldName;
        }

        @Override
        protected void learnPortalHotspot(String sourceWorld, double sx, double sz, String destZoneId) {
            // No-op for this focused test.
        }
    }
}
