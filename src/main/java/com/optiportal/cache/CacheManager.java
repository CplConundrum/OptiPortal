package com.optiportal.cache;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import com.optiportal.config.PluginConfig;
import com.optiportal.metrics.MetricsCollector;
import com.optiportal.model.CacheTier;

/**
 * Central chunk registry and cache tier manager.
 * Tracks all loaded chunks and their zone owners for deduplication.
 * Manages HOT → WARM → COLD tier transitions:
 *   HOT  → WARM after 30 seconds
 *   WARM → COLD after 30 minutes
 */
public class CacheManager {


    private static final Logger LOG = Logger.getLogger("OptiPortal");
    private final PluginConfig config;
    private final WalManager walManager;
    private final ScheduledExecutorService executor;
    private final MetricsCollector metricsCollector;

    // chunkKey (world:cx:cz) → set of zone IDs that own it
    private final Map<String, Set<String>> chunkOwnership = new ConcurrentHashMap<>();

    // zone ID → current tier
    private final Map<String, CacheTier> zoneTiers = new ConcurrentHashMap<>();

    // zone ID → time (ms) when tier was last promoted to HOT or WARM
    private final Map<String, Long> tierTimestamps = new ConcurrentHashMap<>();

    public CacheManager(PluginConfig config, WalManager walManager, ScheduledExecutorService executor) {
        this.config = config;
        this.walManager = walManager;
        this.executor = executor;
        this.metricsCollector = new MetricsCollector();
        // Run tier decay check every 10 seconds
        executor.scheduleAtFixedRate(this::decayTiers, 10, 10, TimeUnit.SECONDS);
    }

    public void loadRegistry() {
        // TODO: Load persisted registry from disk on startup
    }

    public void saveRegistry() {
        // TODO: Persist registry to disk on shutdown/snapshot
    }

    /**
     * Register a zone as owning a set of chunks.
     * Deduplicates automatically - chunks already owned by other zones are co-owned.
     * @return number of NEW chunks loaded (not already owned by any zone)
     */
    public int registerZoneChunks(String zoneId, String world, Set<long[]> chunkCoords) {
        int newChunks = 0;
        for (long[] coord : chunkCoords) {
            String key = chunkKey(world, coord[0], coord[1]);
            Set<String> owners = chunkOwnership.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet());
            if (owners.isEmpty()) newChunks++;
            owners.add(zoneId);
        }
        // Record metrics
        metricsCollector.recordChunksDeduped(newChunks);
        return newChunks;
    }

    /**
     * Release all chunk ownership for a zone.
     * Chunks with no remaining owners are unloaded.
     */
    public void releaseZoneChunks(String zoneId) {
        chunkOwnership.entrySet().removeIf(entry -> {
            entry.getValue().remove(zoneId);
            return entry.getValue().isEmpty();
            // TODO: Actually unload chunks with no remaining owners via Hytale API
        });
    }

    /**
     * Set a zone's tier. HOT and WARM record a timestamp for decay scheduling.
     */
    public void setZoneTier(String zoneId, CacheTier tier) {
        zoneTiers.put(zoneId, tier);
        if (tier == CacheTier.HOT || tier == CacheTier.WARM) {
            tierTimestamps.put(zoneId, System.currentTimeMillis());
        } else {
            tierTimestamps.remove(zoneId);
        }
    }

    public CacheTier getZoneTier(String zoneId) {
        return zoneTiers.getOrDefault(zoneId, CacheTier.UNVISITED);
    }

    /**
     * Periodic decay: HOT → WARM after 30s, WARM → COLD after 30min.
     */
    private void decayTiers() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, CacheTier> entry : zoneTiers.entrySet()) {
            String zoneId = entry.getKey();
            CacheTier tier = entry.getValue();
            Long ts = tierTimestamps.get(zoneId);
            if (ts == null) continue;

            long age = now - ts;
            long hotMs  = config.getHotDecaySeconds()  * 1000L;
            long warmMs = config.getWarmDecayMinutes() * 60_000L;
            if (tier == CacheTier.HOT && age >= hotMs) {
                zoneTiers.put(zoneId, CacheTier.WARM);
                tierTimestamps.put(zoneId, now); // reset clock for WARM→COLD
                LOG.fine("[OptiPortal] Tier decay: " + zoneId + " HOT → WARM");
            } else if (tier == CacheTier.WARM && age >= warmMs) {
                zoneTiers.put(zoneId, CacheTier.COLD);
                tierTimestamps.remove(zoneId);
                deregisterAllChunks(zoneId);
                LOG.fine("[OptiPortal] Tier decay: " + zoneId + " WARM → COLD");
            }
        }
    }

    public Map<String, Set<String>> getChunkOwnership() {
        return Collections.unmodifiableMap(chunkOwnership);
    }
    
    public MetricsCollector getMetricsCollector() {
        return metricsCollector;
    }

    public int getTotalSharedChunks() {
        return (int) chunkOwnership.values().stream().filter(s -> s.size() > 1).count();
    }

    /**
     * Returns true if this chunk is already loaded by any zone.
     * Used by ChunkPreloader to skip redundant load calls.
     */
    public boolean isChunkOwned(String world, int cx, int cz) {
        Set<String> owners = chunkOwnership.get(chunkKey(world, cx, cz));
        boolean owned = owners != null && !owners.isEmpty();
        if (owned) {
            metricsCollector.recordCacheHit();
        } else {
            metricsCollector.recordCacheMiss();
        }
        return owned;
    }

    /**
     * Register a zone as an owner of a chunk.
     * Called after a chunk future completes successfully.
     */
    public void registerOwnership(String zoneId, String world, int cx, int cz) {
        chunkOwnership
                .computeIfAbsent(chunkKey(world, cx, cz), k -> ConcurrentHashMap.newKeySet())
                .add(zoneId);
    }

    /**
     * Remove a zone's ownership of all its chunks by coord range.
     * Chunks with no remaining owners are removed from the registry.
     */
    public void deregisterOwnership(String zoneId, String world, int cx, int cz, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                String key = chunkKey(world, cx + dx, cz + dz);
                Set<String> owners = chunkOwnership.get(key);
                if (owners != null) {
                    owners.remove(zoneId);
                    if (owners.isEmpty()) chunkOwnership.remove(key);
                }
            }
        }
    }

    /**
     * Remove a zone from all chunk ownership sets it appears in.
     * Used by tier decay when a zone goes COLD — no coords needed.
     */
    public void deregisterAllChunks(String zoneId) {
        chunkOwnership.forEach((key, owners) -> {
            owners.remove(zoneId);
            if (owners.isEmpty()) chunkOwnership.remove(key);
        });
        LOG.info("[OptiPortal] Deregistered ownership for zone: " + zoneId);
    }

    private String chunkKey(String world, long cx, long cz) {
        return world + ":" + cx + ":" + cz;
    }
}