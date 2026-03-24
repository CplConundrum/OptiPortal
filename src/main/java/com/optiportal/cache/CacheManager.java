package com.optiportal.cache;

import java.io.File;
import java.io.FileReader;
import java.io.Reader;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hypixel.hytale.math.util.ChunkUtil;
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

    // world name → packed chunk index → set of zone IDs that own it
    // Packed key: low 32 bits = cx, high 32 bits = cz (see packChunkIndex)
    private final ConcurrentHashMap<String, ConcurrentHashMap<Long, Set<String>>> chunkOwnership =
            new ConcurrentHashMap<>();

    // Reverse index: zone ID → world name → set of packed chunk indices owned by this zone.
    // Maintained in sync with chunkOwnership. Enables O(zone-size) deregisterAllChunks
    // instead of O(all chunks on the server).
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Set<Long>>> zoneToChunks =
            new ConcurrentHashMap<>();

    // zone ID → current tier
    private final Map<String, CacheTier> zoneTiers = new ConcurrentHashMap<>();

    // zone ID → time (ms) when tier was last promoted to HOT or WARM
    private final Map<String, Long> tierTimestamps = new ConcurrentHashMap<>();

    // Earliest ms timestamp at which any zone is next due for decay.
    // Long.MAX_VALUE when no HOT/WARM zones exist. Used to skip decayTiers() early.
    private final AtomicLong earliestDecayMs = new AtomicLong(Long.MAX_VALUE);

    // Zone IDs in this set are never subject to tier decay (e.g., zones in eternal worlds).
    // CopyOnWriteArraySet: written rarely (zone registration), read on every decayTiers() call.
    private final java.util.Set<String> noDecayZones = java.util.concurrent.ConcurrentHashMap.newKeySet();

    // Zone IDs in this set may decay HOT→WARM normally but are held at WARM minimum — never go COLD.
    // Used for WARM-strategy zones that should always stay loaded but should still reflect active/idle state.
    private final java.util.Set<String> warmFloorZones = java.util.concurrent.ConcurrentHashMap.newKeySet();

    // Persistence fields for P11
    private final File registryFile;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public CacheManager(PluginConfig config, WalManager walManager,
                        ScheduledExecutorService executor, WorldRegistry worldRegistry,
                        File registryFile, MetricsCollector metricsCollector) {
        this.config = config;
        this.walManager = walManager;
        this.executor = executor;
        this.worldRegistry = worldRegistry;
        this.registryFile = registryFile;
        this.metricsCollector = metricsCollector;
        // Run tier decay check every 10 seconds
        executor.scheduleAtFixedRate(this::decayTiers, 10, 10, TimeUnit.SECONDS);
    }

    /** Backwards-compat constructor for existing callers without registryFile */
    public CacheManager(PluginConfig config, WalManager walManager,
                        ScheduledExecutorService executor, WorldRegistry worldRegistry) {
        this(config, walManager, executor, worldRegistry, null, new MetricsCollector());
    }

    public void loadRegistry() {
        if (registryFile == null || !registryFile.exists()) return;
        try (Reader reader = new FileReader(registryFile)) {
            RegistrySnapshot snapshot = GSON.fromJson(reader, RegistrySnapshot.class);
            if (snapshot == null) return;

            long now = System.currentTimeMillis();
            long hotMs  = config.getHotDecaySeconds()  * 1000L;
            long warmMs = config.getWarmDecayMinutes() * 60_000L;
            long nextEarliest = Long.MAX_VALUE;

            if (snapshot.tiers != null) {
                for (Map.Entry<String, String> e : snapshot.tiers.entrySet()) {
                    CacheTier tier;
                    try { tier = CacheTier.valueOf(e.getValue()); }
                    catch (IllegalArgumentException ignored) { continue; }

                    Long savedTs = snapshot.timestamps != null ? snapshot.timestamps.get(e.getKey()) : null;

                    if (tier == CacheTier.HOT || tier == CacheTier.WARM) {
                        if (savedTs == null) {
                            // No timestamp — treat as freshly promoted
                            savedTs = now;
                        }
                        long age = now - savedTs;
                        long decayMs = (tier == CacheTier.HOT) ? hotMs : warmMs;

                        if (age >= decayMs) {
                            // Zone expired while server was down — demote to COLD
                            LOG.info("[OptiPortal] loadRegistry: zone '" + e.getKey()
                                    + "' expired during downtime (" + tier + ") — marking COLD");
                            zoneTiers.put(e.getKey(), CacheTier.COLD);
                            continue;
                        }

                        // Restore with remaining TTL
                        zoneTiers.put(e.getKey(), tier);
                        tierTimestamps.put(e.getKey(), savedTs);
                        nextEarliest = Math.min(nextEarliest, savedTs + decayMs);
                        LOG.info("[OptiPortal] loadRegistry: restored zone '" + e.getKey()
                                + "' as " + tier + " (" + ((decayMs - age) / 1000) + "s remaining)");
                    } else if (tier == CacheTier.COLD) {
                        // Restore explicitly-saved COLD zones
                        zoneTiers.put(e.getKey(), CacheTier.COLD);
                    }
                }
            }

            earliestDecayMs.set(nextEarliest);
            LOG.info("[OptiPortal] CacheManager: registry loaded.");
        } catch (Exception e) {
            LOG.warning("[OptiPortal] CacheManager: failed to load registry: " + e.getMessage());
        }
    }

    /**
     * Mark a zone as exempt from tier decay. Call this for zones in eternal worlds
     * (WorldConfig.canUnloadChunks() == false). Effect is immediate — the zone's
     * current tier is preserved indefinitely until explicitly unmarked.
     */
    public void markNoDecay(String zoneId) {
        noDecayZones.add(zoneId);
    }

    /**
     * Remove the no-decay exemption for a zone. After calling this, the zone resumes
     * normal HOT→WARM→COLD decay from the next decayTiers() cycle.
     */
    public void unmarkNoDecay(String zoneId) {
        noDecayZones.remove(zoneId);
    }

    /** Returns true if the given zone is exempt from tier decay. */
    public boolean isNoDecay(String zoneId) {
        return noDecayZones.contains(zoneId);
    }

    /**
     * Mark a zone as having a WARM floor.
     * HOT→WARM decay proceeds normally, but WARM→COLD is blocked — the WARM timer
     * simply resets so the zone stays at WARM indefinitely.
     * Used for WARM-strategy zones that must stay loaded but should still reflect idle state.
     */
    public void markWarmFloor(String zoneId) {
        warmFloorZones.add(zoneId);
    }

    public void unmarkWarmFloor(String zoneId) {
        warmFloorZones.remove(zoneId);
    }

    public void saveRegistry() {
        if (registryFile == null) return;
        try {
            // Snapshot both maps under no lock — ConcurrentHashMap iteration is safe
            Map<String, String> tierSnapshot = new HashMap<>();
            for (Map.Entry<String, CacheTier> e : zoneTiers.entrySet()) {
                tierSnapshot.put(e.getKey(), e.getValue().name());
            }
            Map<String, Long> tsSnapshot = new HashMap<>(tierTimestamps);

            RegistrySnapshot snapshot = new RegistrySnapshot(tierSnapshot, tsSnapshot);
            String json = GSON.toJson(snapshot);

            walManager.writeAtomic(registryFile, json);
            LOG.info("[OptiPortal] CacheManager: saved registry (" + tierSnapshot.size() + " zones)");
        } catch (Exception e) {
            LOG.warning("[OptiPortal] CacheManager: failed to save registry: " + e.getMessage());
        }
    }

    /**
     * Register a zone as owning a set of chunks.
     * Deduplicates automatically — chunks already owned by other zones are co-owned.
     * @return number of NEW chunks loaded (not already owned by any zone)
     */
    public int registerZoneChunks(String zoneId, String world, Set<long[]> chunkCoords) {
        ConcurrentHashMap<Long, Set<String>> worldMap =
                chunkOwnership.computeIfAbsent(world, w -> new ConcurrentHashMap<>());
        int newChunks = 0;
        for (long[] coord : chunkCoords) {
            long key = packChunkIndex((int) coord[0], (int) coord[1]);
            Set<String> owners = worldMap.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet());
            if (owners.isEmpty()) newChunks++;
            owners.add(zoneId);
            recordReverseOwnership(zoneId, world, key);
        }
        int deduped = chunkCoords.size() - newChunks;
        if (deduped > 0) metricsCollector.recordChunksDeduped(deduped);
        return newChunks;
    }

    /**
     * Release all chunk ownership for a zone.
     * Chunks with no remaining owners have their keep-loaded pin removed.
     */
    public void releaseZoneChunks(String zoneId) {
        ConcurrentHashMap<String, Set<Long>> worldChunks = zoneToChunks.remove(zoneId);
        if (worldChunks == null) return;
        worldChunks.forEach((worldName, keys) -> {
            ConcurrentHashMap<Long, Set<String>> worldMap = chunkOwnership.get(worldName);
            if (worldMap == null) return;
            for (Long key : keys) {
                Set<String> owners = worldMap.get(key);
                if (owners != null) {
                    owners.remove(zoneId);
                    if (owners.isEmpty()) {
                        worldMap.remove(key, owners);
                        tryReleaseKeepLoaded(worldName, (int)(long) key, (int)(key >>> 32));
                    }
                }
            }
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

    /** Returns a count of tracked zones per cache tier. */
    public java.util.Map<CacheTier, Integer> getTierCounts() {
        java.util.Map<CacheTier, Integer> counts = new java.util.EnumMap<>(CacheTier.class);
        for (CacheTier t : CacheTier.values()) counts.put(t, 0);
        for (CacheTier t : zoneTiers.values()) counts.merge(t, 1, (a, b) -> a + b);
        return counts;
    }

    /**
     * Remove a zone from its cache tier and update tier counts.
     * Used by zone deletion to fully clean up cache state.
     */
    public void removeTierEntry(String zoneId) {
        CacheTier oldTier = zoneTiers.remove(zoneId);
        if (oldTier != null) {
            tierTimestamps.remove(zoneId);
            noDecayZones.remove(zoneId);
            warmFloorZones.remove(zoneId);
            LOG.fine(() -> "[OptiPortal] Removed tier entry: " + zoneId + " (was " + oldTier + ")");
        }
    }

    /**
     * Get the age in milliseconds of a zone's current tier.
     * Returns 0 if the zone is not tracked or has no timestamp.
     */
    public long getZoneTierAgeMs(String zoneId) {
        Long ts = tierTimestamps.get(zoneId);
        if (ts == null) return 0;
        return System.currentTimeMillis() - ts;
    }

    /**
     * Get the number of owned chunks for a specific zone.
     *
     * @param zoneId Zone ID
     * @return Number of owned chunks
     */
    public int getOwnedChunkCount(String zoneId) {
        ConcurrentHashMap<String, Set<Long>> worldChunks = zoneToChunks.get(zoneId);
        if (worldChunks == null) return 0;
        int count = 0;
        for (Set<Long> chunks : worldChunks.values()) {
            count += chunks.size();
        }
        return count;
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
            // Skip no-decay zones — their chunks never unload, decay is meaningless
            if (noDecayZones.contains(zoneId)) continue;
            Long ts = tierTimestamps.get(zoneId);
            if (ts == null) continue;

            long age = now - ts;
            if (tier == CacheTier.HOT && age >= hotMs) {
                zoneTiers.put(zoneId, CacheTier.WARM);
                tierTimestamps.put(zoneId, now); // reset clock for WARM→COLD
                nextEarliest = Math.min(nextEarliest, now + warmMs);
                LOG.fine(() -> "[OptiPortal] Tier decay: " + zoneId + " HOT → WARM");
            } else if (tier == CacheTier.WARM && age >= warmMs) {
                if (warmFloorZones.contains(zoneId)) {
                    // WARM-floor zone: reset the timer to keep it at WARM indefinitely
                    tierTimestamps.put(zoneId, now);
                    nextEarliest = Math.min(nextEarliest, now + warmMs);
                } else {
                    zoneTiers.put(zoneId, CacheTier.COLD);
                    tierTimestamps.remove(zoneId);
                    deregisterAllChunks(zoneId);
                    LOG.fine(() -> "[OptiPortal] Tier decay: " + zoneId + " WARM → COLD");
                }
            } else {
                // Not yet due — contribute to next earliest
                long decayMs = (tier == CacheTier.HOT) ? hotMs : warmMs;
                nextEarliest = Math.min(nextEarliest, ts + decayMs);
            }
        }

        earliestDecayMs.set(nextEarliest);
    }

    public MetricsCollector getMetricsCollector() {
        return metricsCollector;
    }

    /**
     * Returns the set of packed chunk keys owned by OptiPortal for a given world.
     * Each key encodes {cx, cz}: cx = (int) key, cz = (int)(key >>> 32).
     * Used by ChunkOwnershipAuditor to cross-reference against the engine ChunkStore.
     */
    public java.util.Set<Long> getOwnedChunkKeys(String worldName) {
        ConcurrentHashMap<Long, Set<String>> worldMap = chunkOwnership.get(worldName);
        if (worldMap == null) return java.util.Collections.emptySet();
        return java.util.Collections.unmodifiableSet(worldMap.keySet());
    }

    public int getTotalOwnedChunks() {
        return chunkOwnership.values().stream()
                .mapToInt(ConcurrentHashMap::size).sum();
    }

    public int getTotalSharedChunks() {
        return (int) chunkOwnership.values().stream()
                .flatMap(m -> m.values().stream())
                .filter(s -> s.size() > 1).count();
    }

    /**
     * Returns true if this chunk is already loaded by any zone.
     * Used by ChunkPreloader to skip redundant load calls.
     */
    public boolean isChunkOwned(String world, int cx, int cz) {
        ConcurrentHashMap<Long, Set<String>> worldMap = chunkOwnership.get(world);
        if (worldMap == null) {
            metricsCollector.recordCacheMiss();
            return false;
        }
        Set<String> owners = worldMap.get(packChunkIndex(cx, cz));
        boolean owned = owners != null && !owners.isEmpty();
        if (owned) metricsCollector.recordCacheHit();
        else metricsCollector.recordCacheMiss();
        return owned;
    }

    /**
     * Register a zone as an owner of a chunk.
     * Called after a chunk future completes successfully.
     */
    public void registerOwnership(String zoneId, String world, int cx, int cz) {
        if (zoneId == null) return;
        long key = packChunkIndex(cx, cz);
        chunkOwnership.computeIfAbsent(world, w -> new ConcurrentHashMap<>())
                      .computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet())
                      .add(zoneId);
        recordReverseOwnership(zoneId, world, key);
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
        long key = packChunkIndex(cx, cz);
        // Use compute() so the "is this the first owner?" check and add are atomic —
        // prevents two concurrent callers from both observing isEmpty()=true and both
        // calling addKeepLoaded(), which would over-count the keepLoaded ref.
        boolean[] isFirst = {false};
        chunkOwnership
                .computeIfAbsent(world, w -> new ConcurrentHashMap<>())
                .compute(key, (k, existingSet) -> {
                    if (existingSet == null) {
                        existingSet = ConcurrentHashMap.newKeySet();
                        isFirst[0] = true;
                    }
                    existingSet.add(zoneId);
                    return existingSet;
                });
        recordReverseOwnership(zoneId, world, key);
        // Only pin on first owner — prevents double-increment
        if (isFirst[0] && chunk != null) {
            chunk.addKeepLoaded();
            LOG.fine("[OptiPortal] addKeepLoaded: " + world + ":" + cx + ":" + cz);
        }
    }

    /**
     * Remove a zone's ownership of all its chunks by coord range.
     * Chunks with no remaining owners are removed from the registry.
     */
    public void deregisterOwnership(String zoneId, String world, int cx, int cz, int radius) {
        ConcurrentHashMap<Long, Set<String>> worldMap = chunkOwnership.get(world);
        if (worldMap == null) return;
        Set<Long> zoneKeys = zoneToChunks.getOrDefault(zoneId, new ConcurrentHashMap<>())
                                         .get(world);
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                long key = packChunkIndex(cx + dx, cz + dz);
                Set<String> owners = worldMap.get(key);
                if (owners != null) {
                    owners.remove(zoneId);
                    if (owners.isEmpty()) {
                        worldMap.remove(key, owners);
                        tryReleaseKeepLoaded(world, cx + dx, cz + dz);
                    }
                }
                if (zoneKeys != null) zoneKeys.remove(key);
            }
        }
    }

    /**
     * Remove a zone from all chunk ownership sets it appears in.
     * Used by tier decay when a zone goes COLD — O(chunks owned by zone) via reverse index.
     */
    public void deregisterAllChunks(String zoneId) {
        ConcurrentHashMap<String, Set<Long>> worldChunks = zoneToChunks.remove(zoneId);
        if (worldChunks == null) {
            LOG.info("[OptiPortal] Deregistered ownership for zone: " + zoneId + " (no entries)");
            return;
        }
        worldChunks.forEach((worldName, keys) -> {
            ConcurrentHashMap<Long, Set<String>> worldMap = chunkOwnership.get(worldName);
            if (worldMap == null) return;
            for (Long key : keys) {
                Set<String> owners = worldMap.get(key);
                if (owners != null) {
                    owners.remove(zoneId);
                    if (owners.isEmpty()) {
                        worldMap.remove(key, owners);
                        tryReleaseKeepLoaded(worldName, (int)(long) key, (int)(key >>> 32));
                    }
                }
            }
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
     */
    public void onChunkEvicted(String worldName, int cx, int cz) {
        ConcurrentHashMap<Long, Set<String>> worldMap = chunkOwnership.get(worldName);
        if (worldMap == null) return;

        long key = packChunkIndex(cx, cz);
        Set<String> owners = worldMap.remove(key);
        if (owners == null || owners.isEmpty()) return;

        for (String zoneId : owners) {
            // Remove from reverse index
            ConcurrentHashMap<String, Set<Long>> zoneWorldMap = zoneToChunks.get(zoneId);
            if (zoneWorldMap != null) {
                Set<Long> zoneKeys = zoneWorldMap.get(worldName);
                if (zoneKeys != null) zoneKeys.remove(key);
            }
            // noDecay zones keep their tier regardless of eviction
            if (noDecayZones.contains(zoneId)) continue;
            // Tier downgrade — warmFloor zones cannot drop below WARM
            CacheTier current = zoneTiers.getOrDefault(zoneId, CacheTier.COLD);
            CacheTier downgraded;
            switch (current) {
                case HOT:  downgraded = CacheTier.WARM; break;
                case WARM: downgraded = warmFloorZones.contains(zoneId) ? CacheTier.WARM : CacheTier.COLD; break;
                default:   downgraded = current; break;
            }
            if (downgraded != current) {
                zoneTiers.put(zoneId, downgraded);
                if (downgraded == CacheTier.COLD) {
                    tierTimestamps.remove(zoneId);
                } else {
                    tierTimestamps.put(zoneId, System.currentTimeMillis());
                }
                LOG.info("[OptiPortal] onChunkEvicted: zone '" + zoneId
                        + "' downgraded " + current + " → " + downgraded
                        + " (chunk evicted: " + worldName + ":" + cx + ":" + cz + ")");
            }
        }

        LOG.fine("[OptiPortal] onChunkEvicted: removed ownership for "
                + worldName + ":" + cx + ":" + cz + " (had " + owners.size() + " owners)");
    }

    /**
     * Batch form of onChunkEvicted — processes all confirmed evictions for one world
     * in a single pass, amortising ownership-map removes and tier-downgrade logic.
     */
    public void onChunksEvicted(String worldName, java.util.List<int[]> evictions) {
        if (evictions.isEmpty()) return;

        ConcurrentHashMap<Long, Set<String>> worldMap = chunkOwnership.get(worldName);
        if (worldMap == null) return;

        java.util.Map<String, CacheTier> downgrades = new java.util.HashMap<>();
        for (int[] coord : evictions) {
            long key = packChunkIndex(coord[0], coord[1]);
            Set<String> owners = worldMap.remove(key);
            if (owners == null || owners.isEmpty()) continue;
            for (String zoneId : owners) {
                // Remove from reverse index
                ConcurrentHashMap<String, Set<Long>> zoneWorldMap = zoneToChunks.get(zoneId);
                if (zoneWorldMap != null) {
                    Set<Long> zoneKeys = zoneWorldMap.get(worldName);
                    if (zoneKeys != null) zoneKeys.remove(key);
                }
                downgrades.merge(zoneId,
                        zoneTiers.getOrDefault(zoneId, CacheTier.COLD),
                        (a, b) -> a.ordinal() < b.ordinal() ? a : b);
            }
        }

        long now = System.currentTimeMillis();
        for (Map.Entry<String, CacheTier> entry : downgrades.entrySet()) {
            String zoneId = entry.getKey();
            CacheTier current = entry.getValue();
            // noDecay zones keep their tier regardless of eviction
            if (noDecayZones.contains(zoneId)) continue;
            CacheTier downgraded;
            switch (current) {
                case HOT:  downgraded = CacheTier.WARM; break;
                case WARM: downgraded = warmFloorZones.contains(zoneId) ? CacheTier.WARM : CacheTier.COLD; break;
                default:   downgraded = current; break;
            }
            if (downgraded != current) {
                zoneTiers.put(zoneId, downgraded);
                if (downgraded == CacheTier.COLD) tierTimestamps.remove(zoneId);
                else tierTimestamps.put(zoneId, now);
                LOG.info(() -> "[OptiPortal] onChunksEvicted: zone '" + zoneId
                        + "' downgraded " + current + " → " + downgraded);
            }
        }

        LOG.fine(() -> "[OptiPortal] onChunksEvicted: batch removed " + evictions.size()
                + " chunks from '" + worldName + "'");
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Record ownership in the reverse index (zoneToChunks).
     * Called whenever a zone is added as an owner of a chunk.
     */
    private void recordReverseOwnership(String zoneId, String worldName, long packedKey) {
        zoneToChunks.computeIfAbsent(zoneId, z -> new ConcurrentHashMap<>())
                    .computeIfAbsent(worldName, w -> ConcurrentHashMap.newKeySet())
                    .add(packedKey);
    }

    /**
     * Pack chunk coordinates into a long index.
     * Formula: low 32 bits = cx, high 32 bits = cz (unsigned).
     * Unpack: cx = (int) key,  cz = (int)(key >>> 32)
     */
    private static long packChunkIndex(int cx, int cz) {
        return ((long)(cx & 0xFFFFFFFF)) | ((long)(cz & 0xFFFFFFFF) << 32);
    }

    /**
     * Call removeKeepLoaded() on the chunk at the given coords if it is still loaded.
     * Safe to call from any thread — addKeepLoaded/removeKeepLoaded are AtomicInteger ops.
     */
    private void tryReleaseKeepLoaded(String worldName, int cx, int cz) {
        com.hypixel.hytale.server.core.universe.world.World world = worldRegistry.getWorld(worldName);
        if (world == null) return; // world unloaded — engine already cleaned up chunks
        // Use the engine's native index format, not CacheManager's pack format.
        // Use getChunkIfInMemory so non-ticking (warm) chunks are also reached.
        long engineIndex = ChunkUtil.indexChunk(cx, cz);
        com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk chunk =
                world.getChunkIfInMemory(engineIndex);
        if (chunk != null) {
            chunk.removeKeepLoaded();
            LOG.fine(() -> "[OptiPortal] removeKeepLoaded: " + worldName + ":" + cx + ":" + cz);
        }
    }

    /** JSON-serialisable snapshot of tier state for persistence. */
    private static final class RegistrySnapshot {
        Map<String, String> tiers;
        Map<String, Long> timestamps;

        RegistrySnapshot(Map<String, String> tiers, Map<String, Long> timestamps) {
            this.tiers = tiers;
            this.timestamps = timestamps;
        }
    }
}
