package com.optiportal.cache;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import com.optiportal.config.PluginConfig;
import com.optiportal.metrics.MetricsCollector;
import com.optiportal.model.CacheTier;
import com.optiportal.preload.WorldRegistry;

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
    private final WorldRegistry worldRegistry;
    private final MetricsCollector metricsCollector;

    // chunkKey (world:cx:cz) → set of zone IDs that own it
    private final Map<String, Set<String>> chunkOwnership = new ConcurrentHashMap<>();

    // zone ID → current tier
    private final Map<String, CacheTier> zoneTiers = new ConcurrentHashMap<>();

    // zone ID → time (ms) when tier was last promoted to HOT or WARM
    private final Map<String, Long> tierTimestamps = new ConcurrentHashMap<>();

    // Earliest ms timestamp at which any zone is next due for decay.
    // Long.MAX_VALUE when no HOT/WARM zones exist. Used to skip decayTiers() early.
    private final AtomicLong earliestDecayMs = new AtomicLong(Long.MAX_VALUE);

    public CacheManager(PluginConfig config, WalManager walManager,
                        ScheduledExecutorService executor, WorldRegistry worldRegistry) {
        this.config = config;
        this.walManager = walManager;
        this.executor = executor;
        this.worldRegistry = worldRegistry;
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
            Set<String> owners = entry.getValue();
            owners.remove(zoneId);
            if (owners.isEmpty()) {
                // Last owner released — remove keep-alive pin
                tryReleaseKeepLoaded(entry.getKey());
                return true; // remove from map
            }
            return false;
        });
    }

    /**
     * Set a zone's tier. HOT and WARM record a timestamp for decay scheduling.
     */
    public void setZoneTier(String zoneId, CacheTier tier) {
        zoneTiers.put(zoneId, tier);
        if (tier == CacheTier.HOT || tier == CacheTier.WARM) {
            long now = System.currentTimeMillis();
            tierTimestamps.put(zoneId, now);
            long decayMs = (tier == CacheTier.HOT)
                    ? config.getHotDecaySeconds() * 1000L
                    : config.getWarmDecayMinutes() * 60_000L;
            earliestDecayMs.accumulateAndGet(now + decayMs, Math::min);
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
        if (now < earliestDecayMs.get()) {
            return; // Nothing due yet — skip the full map iteration
        }

        long hotMs  = config.getHotDecaySeconds()  * 1000L;
        long warmMs = config.getWarmDecayMinutes() * 60_000L;
        long nextEarliest = Long.MAX_VALUE;

        for (Map.Entry<String, CacheTier> entry : zoneTiers.entrySet()) {
            String zoneId = entry.getKey();
            CacheTier tier = entry.getValue();
            Long ts = tierTimestamps.get(zoneId);
            if (ts == null) continue;

            long age = now - ts;
            if (tier == CacheTier.HOT && age >= hotMs) {
                zoneTiers.put(zoneId, CacheTier.WARM);
                tierTimestamps.put(zoneId, now); // reset clock for WARM→COLD
                nextEarliest = Math.min(nextEarliest, now + warmMs);
                LOG.fine(() -> "[OptiPortal] Tier decay: " + zoneId + " HOT → WARM");
            } else if (tier == CacheTier.WARM && age >= warmMs) {
                zoneTiers.put(zoneId, CacheTier.COLD);
                tierTimestamps.remove(zoneId);
                deregisterAllChunks(zoneId);
                LOG.fine(() -> "[OptiPortal] Tier decay: " + zoneId + " WARM → COLD");
            } else {
                // Not yet due — contribute to next earliest
                long decayMs = (tier == CacheTier.HOT) ? hotMs : warmMs;
                nextEarliest = Math.min(nextEarliest, ts + decayMs);
            }
        }

        earliestDecayMs.set(nextEarliest);
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
        if (zoneId == null) return;
        chunkOwnership
                .computeIfAbsent(chunkKey(world, cx, cz), k -> ConcurrentHashMap.newKeySet())
                .add(zoneId);
    }

    /**
     * Register ownership and pin the chunk in memory via addKeepLoaded().
     * Call this overload when you have the WorldChunk reference from the load future.
     * The plain registerOwnership(zoneId, world, cx, cz) remains for callers that
     * don't have the chunk reference (e.g. dedup path in ChunkPreloader).
     */
    public void registerOwnership(String zoneId, String world, int cx, int cz,
                                  com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk chunk) {
        if (zoneId == null) return;
        String key = chunkKey(world, cx, cz);
        Set<String> owners = chunkOwnership.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet());
        boolean wasEmpty = owners.isEmpty();
        owners.add(zoneId);
        // Only pin on first owner — prevents double-increment
        if (wasEmpty && chunk != null) {
            chunk.addKeepLoaded();
            LOG.fine("[OptiPortal] addKeepLoaded: " + key);
        }
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

    /**
     * Called by ChunkOwnershipAuditor when a chunk has been evicted by the engine
     * without OptiPortal receiving notification. Cleans up ownership and downgrades
     * the tier of all zones that owned this chunk.
     *
     * Tier downgrade logic:
     *   HOT  → WARM  (zone was recently active, keep WARM for potential re-load)
     *   WARM → COLD  (zone hasn't been visited recently, let it go COLD)
     *   COLD → COLD  (already cold, no change)
     *
     * @param worldName World name
     * @param cx        Chunk X coordinate
     * @param cz        Chunk Z coordinate
     */
    public void onChunkEvicted(String worldName, int cx, int cz) {
        String key = chunkKey(worldName, cx, cz);
        Set<String> owners = chunkOwnership.remove(key);

        if (owners == null || owners.isEmpty()) {
            return; // Already cleaned up or unknown
        }

        for (String zoneId : owners) {
            CacheTier current = zoneTiers.getOrDefault(zoneId, CacheTier.COLD);
            CacheTier downgraded;
            switch (current) {
                case HOT:
                    downgraded = CacheTier.WARM;
                    break;
                case WARM:
                    downgraded = CacheTier.COLD;
                    break;
                default:
                    downgraded = current; // COLD or UNVISITED — no change
                    break;
            }
            if (downgraded != current) {
                zoneTiers.put(zoneId, downgraded);
                if (downgraded == CacheTier.COLD) {
                    tierTimestamps.remove(zoneId);
                } else {
                    // Reset timestamp for WARM so it gets a fresh decay window
                    tierTimestamps.put(zoneId, System.currentTimeMillis());
                }
                LOG.info("[OptiPortal] onChunkEvicted: zone '" + zoneId
                        + "' downgraded " + current + " → " + downgraded
                        + " (chunk evicted: " + key + ")");
            }
        }

        LOG.fine("[OptiPortal] onChunkEvicted: removed ownership for " + key
                + " (had " + owners.size() + " owners)");
    }

    /**
     * Batch form of onChunkEvicted — processes all confirmed evictions for one world
     * in a single pass, amortising ownership-map removes and tier-downgrade logic.
     *
     * @param worldName World name
     * @param evictions List of {cx, cz} int[2] arrays (same objects from the audit list)
     */
    public void onChunksEvicted(String worldName, java.util.List<int[]> evictions) {
        if (evictions.isEmpty()) return;

        // Collect all affected zones across all evicted chunks in one pass
        java.util.Map<String, CacheTier> downgrades = new java.util.HashMap<>();
        for (int[] coord : evictions) {
            String key = chunkKey(worldName, coord[0], coord[1]);
            Set<String> owners = chunkOwnership.remove(key);
            if (owners == null || owners.isEmpty()) continue;
            for (String zoneId : owners) {
                downgrades.merge(zoneId,
                        zoneTiers.getOrDefault(zoneId, CacheTier.COLD),
                        (a, b) -> a.ordinal() < b.ordinal() ? a : b);
            }
        }

        // Apply tier downgrades in one pass over the affected zones
        long now = System.currentTimeMillis();
        for (Map.Entry<String, CacheTier> entry : downgrades.entrySet()) {
            String zoneId = entry.getKey();
            CacheTier current = entry.getValue();
            CacheTier downgraded;
            switch (current) {
                case HOT:  downgraded = CacheTier.WARM; break;
                case WARM: downgraded = CacheTier.COLD; break;
                default:   downgraded = current; break;
            }
            if (downgraded != current) {
                zoneTiers.put(zoneId, downgraded);
                if (downgraded == CacheTier.COLD) {
                    tierTimestamps.remove(zoneId);
                } else {
                    tierTimestamps.put(zoneId, now);
                }
                LOG.info(() -> "[OptiPortal] onChunksEvicted: zone '" + zoneId
                        + "' downgraded " + current + " → " + downgraded);
            }
        }

        LOG.fine(() -> "[OptiPortal] onChunksEvicted: batch removed " + evictions.size()
                + " chunks from '" + worldName + "'");
    }

    /**
     * Pack chunk coordinates into a long index matching World.getChunkIfLoaded(long).
     * Formula: low 32 bits = cx, high 32 bits = cz (unsigned).
     * Verified against IWorldChunksAsync.getChunkAsync(int,int) internal packing.
     */
    private static long packChunkIndex(int cx, int cz) {
        return ((long)(cx & 0xFFFFFFFF)) | ((long)(cz & 0xFFFFFFFF) << 32);
    }

    /**
     * Parse "worldName:cx:cz" back to a String[] of { worldName, cx, cz }.
     * Returns null if the key is malformed.
     */
    private static String[] parseChunkKey(String key) {
        // Format: world:cx:cz — world name may contain colons (unlikely but possible).
        // Strategy: find the last two colons.
        int lastColon = key.lastIndexOf(':');
        if (lastColon < 0) return null;
        int secondLastColon = key.lastIndexOf(':', lastColon - 1);
        if (secondLastColon < 0) return null;
        String worldName = key.substring(0, secondLastColon);
        String cxStr = key.substring(secondLastColon + 1, lastColon);
        String czStr = key.substring(lastColon + 1);
        return new String[]{ worldName, cxStr, czStr };
    }

    /**
     * Look up the WorldChunk for a given chunkKey and call removeKeepLoaded() if found.
     * Safe to call from any thread (addKeepLoaded/removeKeepLoaded are AtomicInteger).
     * If the chunk is not currently loaded, the engine has already evicted it — no action needed.
     */
    private void tryReleaseKeepLoaded(String chunkKey) {
        String[] parts = parseChunkKey(chunkKey);
        if (parts == null) {
            LOG.warning("[OptiPortal] CacheManager: malformed chunkKey: " + chunkKey);
            return;
        }
        String worldName = parts[0];
        int cx, cz;
        try {
            cx = Integer.parseInt(parts[1]);
            cz = Integer.parseInt(parts[2]);
        } catch (NumberFormatException e) {
            LOG.warning("[OptiPortal] CacheManager: could not parse coords from key: " + chunkKey);
            return;
        }
        com.hypixel.hytale.server.core.universe.world.World world = worldRegistry.getWorld(worldName);
        if (world == null) {
            // World unloaded — engine already cleaned up chunks, nothing to do
            return;
        }
        long index = packChunkIndex(cx, cz);
        com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk chunk =
                world.getChunkIfLoaded(index);
        if (chunk != null) {
            chunk.removeKeepLoaded();
            LOG.fine("[OptiPortal] removeKeepLoaded: " + chunkKey);
        }
        // null means engine already evicted — no action needed
    }

    private String chunkKey(String world, long cx, long cz) {
        return world + ":" + cx + ":" + cz;
    }
}