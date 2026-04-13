package com.optiportal.teleport;

import com.optiportal.model.PortalEntry;
import com.optiportal.preload.ChunkPreloader;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for PortalSpatialIndex correctness.
 *
 * Goal: prove that spatial narrowing does not change candidate correctness
 * compared to a full linear scan.
 */
class PortalSpatialIndexTest {

    // -------------------------------------------------------------------------
    // Test cases
    // -------------------------------------------------------------------------

    /**
     * A portal sitting just inside chunk N should still be returned when querying
     * from chunk N+1 (within activation radius), i.e. the chunk-boundary crossing
     * does not silently drop the portal.
     */
    @Test
    void nearbyPlainPortalCandidates_includePortalsAcrossChunkBoundaries() {
        // Hytale uses 32-block chunks: x=31 → chunk 0, x=33 → chunk 1.
        // Portal is in chunk 0.  Query origin is in chunk 1, radiusChunks=1.
        // The search loop must look at chunk 0 and find the portal.
        PortalEntry portal = plainPortal("edge-portal", "world-a", 31.0, 64.0, 16.0);

        PortalSpatialIndex idx = new PortalSpatialIndex(List.of(portal));

        double qx = 33.0, qz = 16.0;
        int radiusChunks = 1;
        List<PortalEntry> candidates = gatherPlainCandidates(idx, qx, qz, radiusChunks);

        assertTrue(candidates.stream().anyMatch(p -> p.getId().equals("edge-portal")),
                "Portal near chunk boundary must be found when querying from adjacent chunk");
    }

    /**
     * A portal with a per-zone activationDistanceHorizontal larger than the global
     * activation distance must not be dropped.  The getMaxHorizontalActivationDistance()
     * value should be used to inflate the query radius before building candidates.
     */
    @Test
    void nearbyPlainPortalCandidates_respectLargePerZoneHorizontalOverrides() {
        double globalActivation = 48.0;
        double perZoneOverride  = 200.0;   // intentionally much larger than global

        PortalEntry widePortal = plainPortal("wide-zone", "world-a", 96.0, 64.0, 96.0);
        widePortal.setActivationDistanceHorizontal(perZoneOverride);

        PortalSpatialIndex idx = new PortalSpatialIndex(List.of(widePortal));

        assertEquals(perZoneOverride, idx.getMaxHorizontalActivationDistance(), 0.001,
                "Index must track the largest per-zone horizontal override");

        // With the correct (inflated) radius, the portal should be reachable from origin
        int radiusChunks = chunkRadius(idx.getMaxHorizontalActivationDistance());
        List<PortalEntry> candidates = gatherPlainCandidates(idx, 0.0, 0.0, radiusChunks);

        assertTrue(candidates.stream().anyMatch(p -> p.getId().equals("wide-zone")),
                "Portal with large per-zone horizontal override must be a candidate when radius is inflated accordingly");
    }

    /**
     * Portal-device candidates must only come from the world whose index was queried.
     * Two worlds, two separate indexes — querying world-A must not return world-B devices.
     */
    @Test
    void nearbyPortalDeviceCandidates_areWorldScoped() {
        PortalEntry devA1 = devicePortal("portaldevice:a1", "world-a", 10.0, 64.0, 10.0);
        PortalEntry devA2 = devicePortal("portaldevice:a2", "world-a", 20.0, 64.0, 10.0);
        PortalEntry devB1 = devicePortal("portaldevice:b1", "world-b", 10.0, 64.0, 10.0);

        // Build one index per world (mirrors what TeleportInterceptor does)
        PortalSpatialIndex idxA = new PortalSpatialIndex(List.of(devA1, devA2));
        PortalSpatialIndex idxB = new PortalSpatialIndex(List.of(devB1));

        List<PortalEntry> candidatesFromA = gatherDeviceCandidates(idxA, 10.0, 10.0, 2);
        List<PortalEntry> candidatesFromB = gatherDeviceCandidates(idxB, 10.0, 10.0, 2);

        // World-A index contains world-A devices only
        assertTrue(candidatesFromA.stream().anyMatch(p -> p.getId().equals("portaldevice:a1")));
        assertTrue(candidatesFromA.stream().anyMatch(p -> p.getId().equals("portaldevice:a2")));
        assertFalse(candidatesFromA.stream().anyMatch(p -> p.getId().equals("portaldevice:b1")),
                "World-B device must not appear in world-A index");

        // World-B index contains only world-B devices
        assertTrue(candidatesFromB.stream().anyMatch(p -> p.getId().equals("portaldevice:b1")));
        assertFalse(candidatesFromB.stream().anyMatch(p -> p.getId().equals("portaldevice:a1")),
                "World-A device must not appear in world-B index");
    }

    /**
     * When a single portal is clearly the nearest to a query point, the indexed
     * candidate set (gathered using the same chunk-radius logic as TeleportInterceptor)
     * contains it — and a nearest-select over those candidates matches a direct
     * linear-scan over the full portal list.
     */
    @Test
    void nearestPortalSelection_matchesOldLinearBehavior() {
        PortalEntry near = plainPortal("near", "world-a",  10.0, 64.0,  10.0);
        PortalEntry mid  = plainPortal("mid",  "world-a", 100.0, 64.0, 100.0);
        PortalEntry far  = plainPortal("far",  "world-a", 200.0, 64.0, 200.0);
        List<PortalEntry> all = List.of(near, mid, far);

        PortalSpatialIndex idx = new PortalSpatialIndex(all);

        double qx = 15.0, qz = 15.0;

        // Linear scan: find nearest by Euclidean distance in the XZ plane
        PortalEntry linearNearest = all.stream()
                .min(Comparator.comparingDouble(p -> xzDist2(p, qx, qz)))
                .orElseThrow();

        // Index scan: gather all candidates within a generous chunk radius that covers all portals
        int radiusChunks = 8; // 8 × 32 = 256 blocks, covers all three portals
        List<PortalEntry> candidates = gatherPlainCandidates(idx, qx, qz, radiusChunks);

        PortalEntry indexNearest = candidates.stream()
                .min(Comparator.comparingDouble(p -> xzDist2(p, qx, qz)))
                .orElseThrow();

        assertEquals(linearNearest.getId(), indexNearest.getId(),
                "Indexed nearest-portal selection must agree with a direct linear scan");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Gather plain-portal candidates in a square chunk neighbourhood around (qx, qz). */
    private List<PortalEntry> gatherPlainCandidates(PortalSpatialIndex idx,
                                                     double qx, double qz,
                                                     int radiusChunks) {
        int cx = ChunkPreloader.toChunkCoord(qx);
        int cz = ChunkPreloader.toChunkCoord(qz);
        List<PortalEntry> result = new ArrayList<>();
        for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
            for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
                result.addAll(idx.getPlainPortalsInChunk(ChunkPreloader.packChunk(cx + dx, cz + dz)));
            }
        }
        return result;
    }

    /** Gather portal-device candidates in a square chunk neighbourhood around (qx, qz). */
    private List<PortalEntry> gatherDeviceCandidates(PortalSpatialIndex idx,
                                                      double qx, double qz,
                                                      int radiusChunks) {
        int cx = ChunkPreloader.toChunkCoord(qx);
        int cz = ChunkPreloader.toChunkCoord(qz);
        List<PortalEntry> result = new ArrayList<>();
        for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
            for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
                result.addAll(idx.getPortalDevicesInChunk(ChunkPreloader.packChunk(cx + dx, cz + dz)));
            }
        }
        return result;
    }

    /** Convert a block-space radius to the minimum enclosing chunk radius. */
    private int chunkRadius(double radiusBlocks) {
        return (int) Math.ceil(radiusBlocks / 32.0);
    }

    private double xzDist2(PortalEntry p, double qx, double qz) {
        double dx = p.getX() - qx;
        double dz = p.getZ() - qz;
        return dx * dx + dz * dz;
    }

    /** Build a plain portal (non-device) PortalEntry. */
    private PortalEntry plainPortal(String id, String world, double x, double y, double z) {
        return new PortalEntry(id, world, x, y, z, 0.0);
    }

    /** Build a portal-device PortalEntry (ID must start with "portaldevice:"). */
    private PortalEntry devicePortal(String id, String world, double x, double y, double z) {
        if (!id.startsWith("portaldevice:")) {
            throw new IllegalArgumentException("Device portal id must start with 'portaldevice:'");
        }
        return new PortalEntry(id, world, x, y, z, 0.0);
    }
}
