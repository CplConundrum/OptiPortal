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
import java.util.concurrent.locks.ReentrantReadWriteLock;

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

    // world name → packed chunk index → pin generation counter. Bumped every time the
    // keepalive recovery observes an evicted-but-owned chunk: pins acquired before that
    // observation belong to the evicted chunk instance and lapsed with it.
    private final ConcurrentHashMap<String, ConcurrentHashMap<Long, Long>> chunkPinGeneration =
            new ConcurrentHashMap<>();
    // Commit-2 (off-world-thread): per-chunk locks for off-thread pin tasks. Keyed
    // per world by the ENGINE chunk index (ChunkUtil.indexChunk space — the same key
    // every pin task already carries for its counter calls), distinct from the
    // pin-stamp bookkeeping space (packChunkIndex): the lock map only needs
    // one key per chunk, so any consistent key space works. A pin/reconcile/release
    // task locks its chunk around the check-owner -> check-stamp -> apply ->
    // write-stamp critical section; this per-chunk mutual exclusion replaces the
    // world-thread FIFO serialization the r4 design relied on. Batch tasks lock
    // chunks in ascending engine-index order (the lock-ordering protocol).
    private final ConcurrentHashMap<String, ConcurrentHashMap<Long, java.util.concurrent.locks.ReentrantLock>> chunkTaskLocks =
            new ConcurrentHashMap<>();
    // world name → packed chunk index → zone ID → the chunk pin generation at the time
    // this zone's keep-loaded pin was acquired (or re-acquired) on the live instance.
    // A zone whose stamp is missing or older than the chunk's current generation has no
    // live pin on the current instance and must be re-pinned by recovery; re-pinning a
    // zone whose stamp is current would double-count (its own registration already
    // pinned the reloaded instance).
    private final ConcurrentHashMap<String, ConcurrentHashMap<Long, ConcurrentHashMap<String, Long>>> ownerPinGeneration =
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

    // Per-zone generation counter for generation-safe expiry (tier 1, improvement pass).
    // Every promotion (setZoneTier HOT/WARM) and ownership re-registration bumps the
    // generation; a WARM→COLD expiry captures the generation at scan time and its
    // deferred cleanup (deregisterAllChunks) is skipped if the zone was re-promoted in
    // the meantime, so a stale expiry can never strip current ownership.
    private final java.util.concurrent.ConcurrentHashMap<String, Long> zoneGenerations =
            new java.util.concurrent.ConcurrentHashMap<>();

    // Zones that expired WARM→COLD during the most recent decay scan (package-private
    // so tests can assert the batch was collected and paced as one task, not N unbounded
    // parallel unload storms).
    volatile java.util.List<String> lastDecayedBatch = java.util.List.of();

    // Pacing between keep-loaded release tasks of one decay batch (ms). Bounded so a
    // multi-zone expiry cannot flood the world thread with unload work in one tick.
    int decayReleasePacingMs = 250;

    // Persistence fields for P11
    private final File registryFile;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    // Snapshot barrier for zoneTiers + tierTimestamps.
    //
    // This is not a conventional "readers vs writers" lock. Tier mutations take the
    // read side so normal concurrent promotions/demotions do not serialize on each
    // other. saveRegistry() takes the write side only long enough to block new tier
    // mutations while it copies both maps, preventing tier/timestamp skew in snapshots.
    private final ReentrantReadWriteLock tierSnapshotLock = new ReentrantReadWriteLock();

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
        // Empty bucket pruning is now done directly at mutation sites
    }

    /** Backwards-compat constructor for existing callers without registryFile */
    public CacheManager(PluginConfig config, WalManager walManager,
                        ScheduledExecutorService executor, WorldRegistry worldRegistry) {
        this(config, walManager, executor, worldRegistry, null, null);
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
     * Used for WARM-strategy zones that must stay loaded but should still reflect active/idle state.
     */
    public void markWarmFloor(String zoneId) {
        warmFloorZones.add(zoneId);
    }

    public void unmarkWarmFloor(String zoneId) {
        warmFloorZones.remove(zoneId);
    }

    public void saveRegistry() {
        if (registryFile == null) return;

        // Snapshot both maps atomically to prevent tier/timestamp skew.
        // zoneTiers and tierTimestamps must be consistent with each other.
        final Map<String, String> tierSnapshot;
        final Map<String, Long> tsSnapshot;
        tierSnapshotLock.writeLock().lock();
        try {
            tierSnapshot = new HashMap<>();
            for (Map.Entry<String, CacheTier> e : zoneTiers.entrySet()) {
                tierSnapshot.put(e.getKey(), e.getValue().name());
            }
            tsSnapshot = new HashMap<>(tierTimestamps);
        } finally {
            tierSnapshotLock.writeLock().unlock();
        }

        RegistrySnapshot snapshot = new RegistrySnapshot(tierSnapshot, tsSnapshot);
        String json = GSON.toJson(snapshot);
        try {
            walManager.writeAtomic(registryFile, json);
            LOG.info("[OptiPortal] CacheManager: saved registry (" + tierSnapshot.size() + " zones)");
        } catch (Exception e) {
            LOG.warning("[OptiPortal] CacheManager: saveRegistry failed: " + e.getMessage());
        }
    }

    /**
     * Register a zone as owning a set of chunks.
     * Deduplicates automatically — chunks already owned by other zones are co-owned.
     * @return number of NEW chunks loaded (not already owned by any zone)
     */
    public int registerZoneChunks(String zoneId, String world, Set<long[]> chunkCoords) {
        // Re-registration of ownership bumps the generation too: a pending WARM→COLD
        // cleanup that captured the older generation must not strip the fresh ownership.
        zoneGenerations.merge(zoneId, 1L, Long::sum);
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
                worldMap.computeIfPresent(key, (k, owners) -> {
                    boolean removed = owners.remove(zoneId);
                    if (removed) {
                        // P3: stamp-conditional release — the queued world task
                        // decrements only if this zone's pin is live on the current
                        // instance, and the task owns the stamp clear.
                        tryReleaseKeepLoaded(worldName, (int)(long) k, (int)(k >>> 32), zoneId);
                    }
                    return owners.isEmpty() ? null : owners;
                });
            }
            // Prune empty world bucket immediately
            if (worldMap.isEmpty()) {
                chunkOwnership.remove(worldName, worldMap);
            }
        });
    }

    /**
     * Set a zone's tier. HOT and WARM record a timestamp for decay scheduling.
     * Uses the read side of tierSnapshotLock as a snapshot barrier: concurrent tier
     * updates may proceed together, but saveRegistry() can briefly block new updates
     * while it copies zoneTiers and tierTimestamps consistently.
     */
    public void setZoneTier(String zoneId, CacheTier tier) {
        tierSnapshotLock.readLock().lock();
        try {
            // Bump the zone generation on every promotion BEFORE the tier write: any
            // WARM→COLD expiry that captured the previous generation must treat this
            // promotion as "re-promoted" and skip its deferred cleanup (generation-safe
            // expiry — a stale expiry can never strip current ownership).
            if (tier == CacheTier.HOT || tier == CacheTier.WARM) {
                zoneGenerations.merge(zoneId, 1L, Long::sum);
            }
            zoneTiers.put(zoneId, tier);
            if (tier == CacheTier.HOT || tier == CacheTier.WARM) {
                long now = System.currentTimeMillis();
                tierTimestamps.put(zoneId, now);
                long decayMs = (tier == CacheTier.HOT)
                        ? config.getHotDecaySeconds() * 1000L
                        : config.getWarmDecayMinutes() * 60_000L;
                earliestDecayMs.accumulateAndGet(now + decayMs, Math::min);
            } else {
                // COLD zones don't need a timestamp — remove stale one if present
                tierTimestamps.remove(zoneId);
            }
        } finally {
            tierSnapshotLock.readLock().unlock();
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
        tierSnapshotLock.readLock().lock();
        try {
            CacheTier oldTier = zoneTiers.remove(zoneId);
            if (oldTier != null) {
                tierTimestamps.remove(zoneId);
                noDecayZones.remove(zoneId);
                warmFloorZones.remove(zoneId);
                LOG.fine(() -> "[OptiPortal] Removed tier entry: " + zoneId + " (was " + oldTier + ")");
            }
        } finally {
            tierSnapshotLock.readLock().unlock();
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
        try {
            decayTiersInternal();
        } catch (Exception e) {
            LOG.warning("[OptiPortal] CacheManager: decayTiers error (scheduler preserved): " + e.getMessage());
        }
    }

    // Package-private (not private) so unit tests can drive a decay cycle
    // deterministically without waiting for the 10s scheduled task.
    void decayTiersInternal() {
        long now = System.currentTimeMillis();
        // The former fast path (`if (now < earliestDecayMs.get()) return;`) was removed:
        // setZoneTier() lowers earliestDecayMs concurrently (under the same read lock),
        // so a scan that early-returned on a stale deadline could indefinitely skip a
        // freshly promoted zone whose deadline was later overwritten by a subsequent
        // `.set(nextEarliest)` in this very loop. A full tier scan every 10 seconds is
        // cheap (O(zones)), so the deadline is now advisory only.
        long hotMs  = config.getHotDecaySeconds()  * 1000L;
        long warmMs = config.getWarmDecayMinutes() * 60_000L;
        long nextEarliest = Long.MAX_VALUE;
        // WARM→COLD expiries collected during this scan. Cleanup (deregisterAllChunks)
        // is batched into ONE paced task after the loop instead of N unbounded parallel
        // executor submissions — a multi-zone expiry must not flood the world thread with
        // unload work in a single tick (tier 1, improvement pass).
        java.util.List<String> expiredZones = new java.util.ArrayList<>();
        java.util.List<Long> expiredGens = new java.util.ArrayList<>();

        tierSnapshotLock.readLock().lock();
        try {
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
                        // Generation-safe expiry: capture the generation now; the deferred
                        // cleanup is skipped if the zone is re-promoted (or re-registered)
                        // before the paced task runs, so a stale expiry can never strip
                        // current ownership.
                        final long expGen = zoneGenerations.getOrDefault(zoneId, 0L);
                        expiredZones.add(zoneId);
                        expiredGens.add(expGen);
                        LOG.fine(() -> "[OptiPortal] Tier decay: " + zoneId + " WARM → COLD");
                    }
                } else {
                    // Not yet due — contribute to next earliest
                    long decayMs = (tier == CacheTier.HOT) ? hotMs : warmMs;
                    nextEarliest = Math.min(nextEarliest, ts + decayMs);
                }
            }
            earliestDecayMs.set(nextEarliest);
        } finally {
            tierSnapshotLock.readLock().unlock();
        }

        // Batched + paced keep-loaded release for everything that expired this scan.
        if (!expiredZones.isEmpty()) {
            final java.util.List<String> batch = java.util.List.copyOf(expiredZones);
            final java.util.List<Long> gens = java.util.List.copyOf(expiredGens);
            lastDecayedBatch = batch;
            executor.submit(() -> {
                for (int i = 0; i < batch.size(); i++) {
                    String z = batch.get(i);
                    if (zoneGenerations.getOrDefault(z, 0L) != gens.get(i)) {
                        LOG.info(() -> "[OptiPortal] Skipping stale WARM→COLD cleanup for "
                                + z + " (zone was re-promoted since the decay scan)");
                        continue;
                    }
                    if (i > 0) {
                        try {
                            Thread.sleep(decayReleasePacingMs);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                    deregisterAllChunks(z);   // non-blocking per-zone cleanup
                }
            });
        }
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

    /**
     * The zone IDs that currently own the given chunk in a world (live view; callers
     * must not mutate the returned set). Used by the ownership auditor's revalidation
     * and by async-keepalive recovery (2026-11-12 wiring).
     */
    public java.util.Set<String> getChunkOwners(String world, int cx, int cz) {
        ConcurrentHashMap<Long, Set<String>> worldMap = chunkOwnership.get(world);
        if (worldMap == null) return java.util.Collections.emptySet();
        Set<String> owners = worldMap.get(packChunkIndex(cx, cz));
        return owners == null ? java.util.Collections.emptySet() : owners;
    }

    /** Current generation counter for a zone (0 if unknown) - revalidation anchor for keepalive/auditor. */
    public long getZoneGeneration(String zoneId) {
        return zoneGenerations.getOrDefault(zoneId, 0L);
    }

    public int getTotalOwnedChunks() {
        int total = 0;
        for (java.util.concurrent.ConcurrentHashMap<Long, Set<String>> worldMap : chunkOwnership.values()) {
            total += worldMap.size();
        }
        return total;
    }

    public int getTotalSharedChunks() {
        int shared = 0;
        for (java.util.concurrent.ConcurrentHashMap<Long, Set<String>> worldMap : chunkOwnership.values()) {
            for (Set<String> owners : worldMap.values()) {
                if (owners.size() > 1) shared++;
            }
        }
        return shared;
    }

    /**
     * Returns true if this chunk is already loaded by any zone.
     * Used by ChunkPreloader to skip redundant load calls.
     */
    public boolean isChunkOwned(String world, int cx, int cz) {
        ConcurrentHashMap<Long, Set<String>> worldMap = chunkOwnership.get(world);
        if (worldMap == null) {
            return false;
        }
        Set<String> owners = worldMap.get(packChunkIndex(cx, cz));
        return owners != null && !owners.isEmpty();
    }

    /**
     * Register a zone as an owner of a chunk (dedup path — chunk already in ChunkStore).
     * Per-zone pinning: also dispatches addKeepLoaded() to the world thread so that this
     * zone holds an independent keepLoaded pin, not borrowed from the first-owner zone.
     */
    public void registerOwnership(String zoneId, String world, int cx, int cz) {
        if (zoneId == null) return;
        long key = packChunkIndex(cx, cz);
        boolean added = chunkOwnership.computeIfAbsent(world, w -> new ConcurrentHashMap<>())
                      .computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet())
                      .add(zoneId);
        recordReverseOwnership(zoneId, world, key);
        if (added) {
            // Execution-time stamping (instance-anchored): the zone task skips
            // chunks that already carry a live pin stamp and stamps the chunk
            // instance that actually received the pin, so a chunk evicted and
            // re-published between the presence check and the task's execution
            // cannot be left with a missing or stale stamp (r4 P1).
            addKeepLoadedBatchForZone(zoneId, world, java.util.List.of(ChunkUtil.indexChunk(cx, cz)));
        }
    }

    /**
     * 0.7.7: World no longer exposes getChunkIfInMemory — resolve the chunk
     * component through the ChunkStore. Uses the seq-validated concurrent read
     * (getComponentConcurrent): safe off-thread, null on invalid refs.
     */
    private static com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk chunkIfInMemory(
            com.hypixel.hytale.server.core.universe.world.World world, long engineIndex) {
        try {
            com.hypixel.hytale.server.core.universe.world.storage.ChunkStore store = world.getChunkStore();
            com.hypixel.hytale.component.Ref<com.hypixel.hytale.server.core.universe.world.storage.ChunkStore> ref =
                    store.getChunkReference(engineIndex);
            if (ref == null || !ref.isValid()) return null;
            return store.getStore().getComponentConcurrent(
                    ref,
                    com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk.getComponentType());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Commit-2 test seam: submits a pin task for the named world. Production
     * runs pin tasks on the plugin's shared {@link ScheduledExecutorService} —
     * the pin path performs ZERO world.execute() calls. The atomicity the r4
     * design obtained from world-thread FIFO is instead provided by the
     * per-chunk {@link java.util.concurrent.locks.ReentrantLock} held around
     * each task's critical section ({@link #chunkTaskLock}). Tests override
     * this to run tasks synchronously (immediate execution — the task still
     * takes its own chunk lock) or hold them (queued-task interleavings).
     *
     * @return true when the task was accepted (queued or run); false when the
     *         executor is shut down (or absent) and the task was dropped. P3
     *         callers that own state the task would have cleared (e.g. a pin
     *         stamp) use this to fall back to clearing it on the calling thread.
     */
    protected boolean submitPinTask(String worldName, Runnable task) {
        if (executor == null) return false;
        try {
            executor.execute(task);
            return true;
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // executor shut down (plugin stopping) — the task is a no-op
            return false;
        }
    }

    /** Per-chunk lock for the off-thread pin tasks (see {@link #chunkTaskLocks}). */
    private java.util.concurrent.locks.ReentrantLock chunkTaskLock(String worldName, long engineIndex) {
        return chunkTaskLocks
                .computeIfAbsent(worldName, w -> new ConcurrentHashMap<>())
                .computeIfAbsent(engineIndex, k -> new java.util.concurrent.locks.ReentrantLock());
    }

    /**
     * r4 test seam: applies one keep-loaded pin to the chunk instance currently
     * resident at engineIndex (if any). Returns true when a pin was applied.
     */
    protected boolean applyKeepLoadedPin(String worldName, long engineIndex) {
        com.hypixel.hytale.server.core.universe.world.World world = worldRegistry.getWorld(worldName);
        if (world == null) return false;
        com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk chunk =
                chunkIfInMemory(world, engineIndex);
        if (chunk == null) return false;
        chunk.addKeepLoaded();
        return true;
    }

    /**
     * r4 test seam: releases one keep-loaded pin from the chunk instance currently
     * resident at engineIndex (if any). Returns true when a pin was released.
     */
    protected boolean applyKeepLoadedRelease(String worldName, long engineIndex) {
        com.hypixel.hytale.server.core.universe.world.World world = worldRegistry.getWorld(worldName);
        if (world == null) return false;
        com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk chunk =
                chunkIfInMemory(world, engineIndex);
        if (chunk == null) return false;
        chunk.removeKeepLoaded();
        return true;
    }


    /**
     * Batch variant of addKeepLoadedAsync — submits a single executor task
     * (off-world-thread; null-zone legacy path — no stamps, no locks) that calls
     * addKeepLoaded() on multiple chunks. Reduces task submission overhead during
     * zone registration when many chunks are already loaded. The engine counter
     * (WorldChunk.addKeepLoaded, an AtomicInteger) is thread-safe, so the task
     * needs neither the world thread nor a chunk lock.
     */
    private void addKeepLoadedBatchAsync(String worldName, java.util.List<Long> engineIndexes) {
        com.hypixel.hytale.server.core.universe.world.World world = worldRegistry.getWorld(worldName);
        if (world == null) return;
        // Pre-filter to chunks that are actually in the store, before hitting the executor
        java.util.List<Long> present = new java.util.ArrayList<>(engineIndexes.size());
        for (long idx : engineIndexes) {
            if (world.getChunkStore().getChunkReference(idx) != null) {
                present.add(idx);
            }
        }
        if (present.isEmpty()) return;
        try {
            executor.execute(() -> {
                for (long idx : present) {
                    com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk chunk =
                            chunkIfInMemory(world, idx);
                    if (chunk != null) chunk.addKeepLoaded();
                }
            });
        } catch (Exception e) {
            // executor shut down (plugin stopping) — or world-level failure
        }
    }

    /**
     * r4 zone-aware pin dispatch (commit-2: off-world-thread, per-chunk-locked).
     * Queues a single executor task that applies this zone's keep-loaded pin to
     * every chunk in the batch that is present at execution time. The live-pin
     * stamp is recorded at execution time, against the chunk instance that
     * actually received the pin (instance-anchored): a chunk evicted and
     * re-published between the caller's presence check and this task's execution
     * cannot be left with a missing or stale stamp. Tasks are idempotent: chunks
     * that already carry a live stamp for this zone are skipped, so duplicate
     * queued tasks for the same zone+chunk apply exactly one pin. An
     * execution-time owner-liveness check skips chunks whose owner set no longer
     * contains this zone (the zone deregistered between submission and execution
     * — no orphan pins).
     */
    public void addKeepLoadedBatchForZone(String zoneId, String worldName, java.util.List<Long> engineIndexes) {
        if (zoneId == null || engineIndexes == null || engineIndexes.isEmpty()) {
            addKeepLoadedBatchAsync(worldName, engineIndexes);
            return;
        }
        final String zone = zoneId;
        // Commit-2: lock chunks in ascending engine-index order (the per-chunk
        // lock-ordering protocol for multi-chunk batch tasks).
        final java.util.List<Long> sorted = new java.util.ArrayList<>(engineIndexes);
        java.util.Collections.sort(sorted);
        submitPinTask(worldName, () -> {
            for (long packed : sorted) {
                // ChunkUtil.indexChunk layout: high 32 = cx, low 32 = cz (NOT the
                // packChunkIndex bookkeeping layout - the two differ for non-diagonal
                // chunks; a swapped unpack made the owner check query the wrong
                // chunk and skipped the pin).
                int cz = (int) (long) packed;
                int cx = (int) (packed >>> 32);
                java.util.concurrent.locks.ReentrantLock lock = chunkTaskLock(worldName, packed);
                lock.lock();
                try {
                    // Commit-2 owner-liveness check: skip an orphan pin when the zone
                    // deregistered between task submission and execution.
                    if (!getChunkOwners(worldName, cx, cz).contains(zone)) continue;
                    if (hasLivePin(zone, worldName, cx, cz)) continue;
                    if (applyKeepLoadedPin(worldName, packed)) {
                        recordPinAcquisition(zone, worldName, cx, cz);
                    }
                } finally {
                    lock.unlock();
                }
            }
        });
    }

    /**
     * r4 recovery reconciliation (commit-2: off-thread, per-chunk-locked).
     * Re-fetches the LIVE owner set for this chunk, re-registers each owner
     * (idempotent claim: an existing owner is not re-claimed), and queues a
     * single executor task that re-acquires exactly one pin for every owner
     * whose pin is not live on the reloaded instance (missing stamp, or a stamp
     * older than the current pin generation). The task re-checks the live-pin
     * stamp at execution time and skips zones whose pin was acquired in the
     * meantime, so queued duplicates (a registration task and this
     * reconciliation task racing on the same zone+chunk) apply exactly one pin.
     * An execution-time owner-liveness check skips owners that deregistered
     * between submission and execution (no orphan pins).
     *
     * <p>Called from the keepalive recovery callback on the ping-completing
     * thread; pin mutations and stamp writes happen on the executor thread under
     * the chunk's per-chunk lock, mutually exclusive with registration pin tasks
     * and the per-zone release path — the per-chunk lock replaces the r4
     * world-thread FIFO total ordering for same-chunk operations.
     */
    public void reconcileKeepLoadedPins(String worldName, int cx, int cz) {
        java.util.Set<String> liveOwners = getChunkOwners(worldName, cx, cz);
        if (liveOwners.isEmpty()) return;
        long idx = ChunkUtil.indexChunk(cx, cz);
        java.util.List<String> toRepin = new java.util.ArrayList<>();
        for (String zoneId : liveOwners) {
            int pinned = registerOwnershipBatch(zoneId, worldName, java.util.List.of(new int[]{cx, cz}));
            if (pinned == 0 && !hasLivePin(zoneId, worldName, cx, cz)) {
                toRepin.add(zoneId);
            }
        }
        if (toRepin.isEmpty()) return;
        final java.util.List<String> zones = toRepin;
        final long finalIdx = idx;
        submitPinTask(worldName, () -> {
            java.util.concurrent.locks.ReentrantLock lock = chunkTaskLock(worldName, finalIdx);
            lock.lock();
            try {
                for (String zoneId : zones) {
                    // Commit-2 owner-liveness check: skip a zone that deregistered
                    // between reconciliation submission and task execution.
                    if (!getChunkOwners(worldName, cx, cz).contains(zoneId)) continue;
                    if (hasLivePin(zoneId, worldName, cx, cz)) continue;
                    if (applyKeepLoadedPin(worldName, finalIdx)) {
                        recordPinAcquisition(zoneId, worldName, cx, cz);
                    }
                }
            } finally {
                lock.unlock();
            }
        });
    }

    /**
     * F3: re-request ONE keep-loaded pin for a chunk the engine evicted. The pin
     * request lapsed together with the eviction, and the reloaded chunk's
     * per-instance counter (engine WorldChunk.keepLoaded) starts at zero, so the
     * chunk must be re-pinned ONCE PER CURRENT OWNER to stay balanced with the
     * per-zone release path (deregistration decrements once per zone). Call AFTER
     * the chunk has been re-loaded: the pre-store filter in
     * addKeepLoadedBatchAsync skips absent chunks, so this is a no-op before the
     * reload lands.
     */

    /**
     * Zone-aware recovery re-pin: re-acquires this zone's keep-loaded pin on a
     * reloaded chunk instance. The zone task stamps the acquisition at execution
     * time on the chunk instance that actually received the pin, so a later
     * recovery cycle on the same instance does not re-pin the same zone (r4:
     * queued duplicate pin tasks for the same zone+chunk are idempotent).
     */
    public void reacquireKeepLoadedPin(String worldName, int cx, int cz, String zoneId) {
        addKeepLoadedBatchForZone(zoneId, worldName, java.util.List.of(ChunkUtil.indexChunk(cx, cz)));
    }

    // ---- F3 pin-generation reconciliation -------------------------------------
    // Engine evictions reset a chunk's per-instance keep-loaded counter; the mod-side
    // ownership map survives. Recovery must re-pin exactly the owners whose pins lapsed
    // with the evicted instance, and must NOT re-pin owners that registered on the
    // reloaded instance in the ping gap (they already hold live pins). The generation
    // stamp below is the discriminator: it is bumped at eviction observation time and
    // stamped on every pin acquisition, so "stamp current" == "pin is live on the
    // current instance".

    private long currentChunkPinGeneration(String world, long key) {
        ConcurrentHashMap<Long, Long> gen = chunkPinGeneration.get(world);
        return gen == null ? 0L : gen.getOrDefault(key, 0L);
    }

    /**
     * Records that this zone's keep-loaded pin was (re-)acquired on the chunk's current
     * instance. A null zone is a no-op (legacy callers).
     */
    public void recordPinAcquisition(String zoneId, String worldName, int cx, int cz) {
        if (zoneId == null) return;
        long key = packChunkIndex(cx, cz);
        ownerPinGeneration.computeIfAbsent(worldName, w -> new ConcurrentHashMap<>())
                .computeIfAbsent(key, k -> new ConcurrentHashMap<>())
                .put(zoneId, currentChunkPinGeneration(worldName, key));
    }

    /**
     * True when this zone's keep-loaded pin is live on the chunk's current instance
     * (stamped on or after the most recent eviction observation).
     */
    public boolean hasLivePin(String zoneId, String worldName, int cx, int cz) {
        if (zoneId == null) return false;
        long key = packChunkIndex(cx, cz);
        ConcurrentHashMap<Long, ConcurrentHashMap<String, Long>> worldStamps =
                ownerPinGeneration.get(worldName);
        ConcurrentHashMap<String, Long> stamps = worldStamps == null ? null : worldStamps.get(key);
        Long stamp = stamps == null ? null : stamps.get(zoneId);
        return stamp != null && stamp >= currentChunkPinGeneration(worldName, key);
    }

    /**
     * Records an eviction observation: the keepalive pass found this chunk missing from
     * the store while zones still own it. Bumps the chunk's pin generation so recovery
     * can distinguish pins that lapsed with the old instance from live pins acquired
     * by owners that register in the ping gap. Distinct from {@link #onChunksEvicted},
     * which is the auditor's ownership-strip path (it removes the mapping, it does not
     * observe engine eviction).
     */
    public void noteChunkEvicted(String worldName, int cx, int cz) {
        chunkPinGeneration.computeIfAbsent(worldName, w -> new ConcurrentHashMap<>())
                .merge(packChunkIndex(cx, cz), 1L, Long::sum);
    }

    /** Drops this zone's pin stamp (its pin was released or its ownership stripped). */
    private void removePinStamp(String worldName, long key, String zoneId) {
        ConcurrentHashMap<Long, ConcurrentHashMap<String, Long>> worldStamps =
                ownerPinGeneration.get(worldName);
        if (worldStamps == null) return;
        ConcurrentHashMap<String, Long> stamps = worldStamps.get(key);
        if (stamps != null) {
            stamps.remove(zoneId);
            if (stamps.isEmpty()) worldStamps.remove(key, stamps);
        }
    }

    /**
     * Whether this chunk is currently present in the store. Production check via the
     * concurrent chunk-component read; test seams may override this seam (the engine
     * pin dispatch silently skips requests for missing chunks, so callers use this to
     * know whether a queued pin request actually applies to a live instance).
     */
    protected boolean isChunkStorePresent(String worldName, long engineIndex) {
        com.hypixel.hytale.server.core.universe.world.World world =
                worldRegistry.getWorld(worldName);
        if (world == null) return false;
        return chunkIfInMemory(world, engineIndex) != null;
    }

    /**
     * Batch variant of registerOwnership — registers ownership for multiple chunks
     * in a single call, reducing world-thread task submissions. Used by ChunkPreloader
     * when claiming already-loaded chunks during warm/predictive loads.
     *
     * @param zoneId Zone ID to register as owner
     * @param world World name
     * @param chunkCoords List of chunk coordinates [cx, cz] to register
     * @return the number of NEW OWNERSHIP CLAIMS by this call (chunks for which the
     *         zone was a new owner) - 0 when the zone already owned every chunk in the
     *         batch. A claim is a queued pin request, not a confirmed engine
     *         acquisition: the dispatch is fire-and-forget and silently skips chunks
     *         that are not in the store, and a claim applies only to the chunk
     *         instance present when the zone task executes (execution-time
     *         instance anchoring). Callers recovering from an engine
     *         eviction must use {@link #reconcileKeepLoadedPins(String, int, int)} -
     *         it re-fetches the live owner set and re-pins only lapsed pins on the
     *         world thread; do not infer pin loss from the returned count alone.
     */
    public int registerOwnershipBatch(String zoneId, String world, java.util.List<int[]> chunkCoords) {
        if (zoneId == null || chunkCoords == null || chunkCoords.isEmpty()) return 0;

        ConcurrentHashMap<Long, Set<String>> worldMap =
                chunkOwnership.computeIfAbsent(world, w -> new ConcurrentHashMap<>());
        Set<Long> reverseKeys = zoneToChunks
                .computeIfAbsent(zoneId, z -> new ConcurrentHashMap<>())
                .computeIfAbsent(world, w -> ConcurrentHashMap.newKeySet());
        java.util.List<Long> engineIndexes = new java.util.ArrayList<>(chunkCoords.size());
        for (int[] coord : chunkCoords) {
            long key = packChunkIndex(coord[0], coord[1]);
            boolean added = worldMap
                    .computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet())
                    .add(zoneId);
            reverseKeys.add(key);
            if (added) {
                // The zone task stamps at execution time (instance-anchored) - a
                // check-time stamp could be stale (the chunk republished since) or
                // missing (absent at check time, present at dispatch time), which is
                // what left late-joining owners double-pinned (r4 P1).
                engineIndexes.add(ChunkUtil.indexChunk(coord[0], coord[1]));
            }
        }
        if (!engineIndexes.isEmpty()) {
            addKeepLoadedBatchForZone(zoneId, world, engineIndexes);
        }
        return engineIndexes.size();
    }

    /**
     * Register ownership and pin the chunk in memory via addKeepLoaded().
     * Call this overload when you have the WorldChunk reference from the load future.
     *
     * Per-zone pinning: each zone independently increments keepLoaded when it registers
     * and decrements it when it deregisters. This ensures that shared chunks (owned by
     * multiple zones) remain pinned as long as ANY owning zone is still HOT/WARM, and
     * eliminates the double-decrement race that the old single-pin model was exposed to
     * when two zones deregistered the same chunk concurrently.
     *
     * addKeepLoaded() is called if and only if this zoneId was NOT already in the owner
     * set for this chunk — prevents double-increment if the same zone re-registers a chunk
     * it already owns (e.g. a second predictiveLoad before the first decays).
     */
    public void registerOwnership(String zoneId, String world, int cx, int cz,
                                  com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk chunk) {
        if (zoneId == null) return;
        long key = packChunkIndex(cx, cz);
        // ConcurrentHashMap.newKeySet().add() is atomic; returns true only if the element
        // was newly inserted. Two concurrent callers with the same zoneId will both see
        // the same Set (computeIfAbsent returns the existing one), and exactly one of them
        // will get added=true — preventing double-addKeepLoaded for the same zone.
        boolean added = chunkOwnership
                .computeIfAbsent(world, w -> new ConcurrentHashMap<>())
                .computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet())
                .add(zoneId);
        recordReverseOwnership(zoneId, world, key);
        if (added) {
            // The chunk parameter being non-null means the chunk is in the store at
            // call time; the zone task still resolves the instance at execution time
            // (stamping is instance-anchored), so a re-published chunk is handled
            // correctly (r4 P1).
            addKeepLoadedBatchForZone(zoneId, world, java.util.List.of(ChunkUtil.indexChunk(cx, cz)));
            LOG.fine(() -> "[OptiPortal] addKeepLoaded: " + world + ":" + cx + ":" + cz + " (zone: " + zoneId + ")");
        }
    }

    /**
     * Remove a zone's ownership of all its chunks by coord range.
     * Per-zone pinning: releases this zone's keepLoaded pin for every chunk it owned,
     * regardless of whether other zones still own the chunk. The map entry for a chunk
     * is only removed when the last owner deregisters.
     */
    public void deregisterOwnership(String zoneId, String world, int cx, int cz, int radius) {
        ConcurrentHashMap<Long, Set<String>> worldMap = chunkOwnership.get(world);
        if (worldMap == null) return;
        Set<Long> zoneKeys = zoneToChunks.getOrDefault(zoneId, new ConcurrentHashMap<>())
                                         .get(world);
        for (int dx = -radius; dx <= radius; dx++) {
            // Capture effectively final copies of loop variables for lambda
            int finalDx = dx;
            for (int dz = -radius; dz <= radius; dz++) {
                int finalDz = dz;
                long key = packChunkIndex(cx + finalDx, cz + finalDz);
                worldMap.computeIfPresent(key, (k, owners) -> {
                    boolean removed = owners.remove(zoneId);
                    if (removed) {
                        // P3: stamp-conditional release — the queued world task
                        // decrements only if the zone's pin is live on the current
                        // instance, and the task owns the stamp clear.
                        tryReleaseKeepLoaded(world, cx + finalDx, cz + finalDz, zoneId);
                    }
                    return owners.isEmpty() ? null : owners;
                });
                if (zoneKeys != null) zoneKeys.remove(key);
            }
        }
        // Prune empty world bucket immediately
        if (worldMap.isEmpty()) {
            chunkOwnership.remove(world, worldMap);
        }
    }

    /**
     * Remove a zone from all chunk ownership sets it appears in.
     * Used by tier decay when a zone goes COLD — O(chunks owned by zone) via reverse index.
     *
     * Per-zone pinning: tryReleaseKeepLoaded is called for every chunk this zone owned,
     * not just those where it was the last owner. Each zone independently holds a keepLoaded
     * pin, so each deregistration must release exactly that zone's pin regardless of other
     * zones still owning the chunk.
     */
    public void deregisterAllChunks(String zoneId) {
        ConcurrentHashMap<String, Set<Long>> worldChunks = zoneToChunks.remove(zoneId);
        if (worldChunks == null) {
            LOG.info(() -> "[OptiPortal] Deregistered ownership for zone: " + zoneId + " (no entries)");
            return;
        }
        worldChunks.forEach((worldName, keys) -> {
            ConcurrentHashMap<Long, Set<String>> worldMap = chunkOwnership.get(worldName);
            if (worldMap == null) return;
            for (Long key : keys) {
                worldMap.computeIfPresent(key, (k, owners) -> {
                    boolean removed = owners.remove(zoneId);
                    if (removed) {
                        // P3: stamp-conditional release — the queued world task
                        // decrements only if the zone's pin is live on the current
                        // instance, and the task owns the stamp clear.
                        tryReleaseKeepLoaded(worldName, (int)(long) k, (int)(k >>> 32), zoneId);
                    }
                    return owners.isEmpty() ? null : owners;
                });
            }
            // Prune empty world bucket immediately
            if (worldMap.isEmpty()) {
                chunkOwnership.remove(worldName, worldMap);
            }
        });
        LOG.info("[OptiPortal] Deregistered ownership for zone: " + zoneId);
    }

    /**
     * Prunes empty world buckets from chunkOwnership to prevent accumulation
     * after repeated world churn.
     *
     * Note: This method is now only called during shutdown for safety.
     * Direct cleanup is performed at mutation sites (releaseZoneChunks, deregisterAllChunks, etc.)
     * to avoid unnecessary map iterations.
     */
    public void pruneEmptyWorldBuckets() {
        chunkOwnership.entrySet().removeIf(entry -> {
            boolean isEmpty = entry.getValue().isEmpty();
            if (isEmpty) {
                LOG.fine(() -> "[OptiPortal] Pruned empty world bucket: " + entry.getKey());
            }
            return isEmpty;
        });
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

        // Auditor-confirmed engine eviction = the same observation as a keepalive
        // ping finding the chunk absent: the instance's pins are gone. Bump the
        // pin generation so stale zone stamps for this chunk cannot feed the
        // live-pin guard when the chunk is reloaded.
        noteChunkEvicted(worldName, cx, cz);

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
            tierSnapshotLock.readLock().lock();
            try {
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
            } finally {
                tierSnapshotLock.readLock().unlock();
            }
        }

        // Prune empty world bucket immediately
        if (worldMap.isEmpty()) {
            chunkOwnership.remove(worldName, worldMap);
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
            // Same generation semantics as the single-coord path: an engine
            // eviction observed by the audit invalidates every stale zone stamp
            // for this chunk (the instance's pins are gone).
            noteChunkEvicted(worldName, coord[0], coord[1]);
            Set<String> owners = worldMap.remove(key);
            if (owners == null || owners.isEmpty()) continue;
            for (String zoneId : owners) {
                // Stripped owners no longer hold live pins on this chunk: drop their
                // stamps so the retention reference cannot feed a stale stamp into a
                // later recovery cycle.
                removePinStamp(worldName, key, zoneId);
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
        tierSnapshotLock.readLock().lock();
        try {
            // F5 pacing: collect (zone, generation) pairs and submit ONE task after
            // the loop - a multi-zone audit must not burst world-thread pin-release
            // work (one unpaced submit per zone).
            java.util.List<String> coldZones = new java.util.ArrayList<>();
            java.util.List<Long> coldGens = new java.util.ArrayList<>();
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
                    // F5: an audit-driven WARM -> COLD must go through the same generation-safe
                    // release path as a decay scan, or the zone's remaining (non-missing)
                    // ownership/pins would dangle forever. The tier+generation re-check skips
                    // the release if the zone was re-promoted before the task runs.
                    if (current == CacheTier.WARM && downgraded == CacheTier.COLD) {
                        coldZones.add(zoneId);
                        coldGens.add(zoneGenerations.getOrDefault(zoneId, 0L));
                    }
                }
            }
            // F5: one paced task per eviction batch (not per zone) - the per-zone
            // tier+generation re-check is preserved, but the world-thread pin-release
            // work is batched.
            if (!coldZones.isEmpty()) {
                final java.util.List<String> zones = coldZones;
                final java.util.List<Long> gens = coldGens;
                executor.submit(() -> {
                    for (int zi = 0; zi < zones.size(); zi++) {
                        final String coldZone = zones.get(zi);
                        final long coldGen = gens.get(zi);
                        if (zoneTiers.getOrDefault(coldZone, CacheTier.COLD) != CacheTier.COLD
                                || zoneGenerations.getOrDefault(coldZone, 0L) != coldGen) {
                            LOG.info("[OptiPortal] onChunksEvicted: skipping WARM -> COLD cleanup for '"
                                    + coldZone + "' (zone was re-promoted since the audit)");
                            continue;
                        }
                        deregisterAllChunks(coldZone);
                    }
                });
            }
        } finally {
            tierSnapshotLock.readLock().unlock();
        }

        // Prune empty world bucket immediately
        if (worldMap.isEmpty()) {
            chunkOwnership.remove(worldName, worldMap);
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
     * Release a single chunk's keep-loaded pin, conditionally and zone-scoped (P3).
     *
     * Threading: safe to call from any thread (commit-2: off-world-thread). The
     * decrement is applied on the executor thread under the chunk's per-chunk
     * lock (the per-chunk lock replaces the r4 world-thread FIFO total ordering
     * for same-chunk operations), and the task runs stamp-conditionally: it
     * decrements the live instance's counter only when this zone's pin is live
     * on that instance ({@link #hasLivePin} — the stamp was written at or after
     * the chunk's last observed eviction). A pin that lapsed with an evicted
     * instance must not touch the new instance's counter (the pin died with the
     * old instance): the stale stamp is cleared instead, so a later
     * re-registration is not skipped by the live-pin guard. The task owns the
     * stamp clear so the decrement and the clear stay mutually exclusive with
     * all other pin tasks on the chunk; if the executor task is dropped
     * (shutdown, task rejected) the stamp is cleared on the calling thread
     * instead.
     *
     * @param worldName the world name
     * @param cx        chunk X
     * @param cz        chunk Z
     * @param zoneId    the zone whose pin is being released
     */
    private void tryReleaseKeepLoaded(String worldName, int cx, int cz, String zoneId) {
        // Engine counter space (ChunkUtil.indexChunk: high 32 = cx, low 32 = cz)
        // vs pin-stamp bookkeeping space (packChunkIndex: low 32 = cx, high 32 = cz):
        // for non-diagonal chunks (cx != cz) these DIFFER, so the counter call and
        // the stamp clear must each use their own key (goldfish battery: clearing
        // the stamp with the engine index left non-diagonal stamps un-cleared,
        // keeping hasLivePin true and making re-registration skip the pin).
        long engineIndex = ChunkUtil.indexChunk(cx, cz);
        long stampKey = packChunkIndex(cx, cz);
        // P3: stamp-conditional decrement — the queued executor task (commit-2:
        // off-world-thread, under the chunk's per-chunk lock) touches the live
        // instance's counter only when this zone's pin is live on it, and owns
        // the stamp clear so the two stay mutually exclusive with all pin tasks
        // on the chunk. If the task is dropped (executor shut down), the stamp
        // is cleared on the calling thread instead so a re-registered zone is
        // not skipped by the live-pin guard.
        if (!submitPinTask(worldName, () -> {
                java.util.concurrent.locks.ReentrantLock lock = chunkTaskLock(worldName, engineIndex);
                lock.lock();
                try {
                    if (hasLivePin(zoneId, worldName, cx, cz)) {
                        if (applyKeepLoadedRelease(worldName, engineIndex)) {
                            LOG.fine(() -> "[OptiPortal] removeKeepLoaded: " + worldName + ":" + cx + ":" + cz);
                        }
                    }
                    removePinStamp(worldName, stampKey, zoneId);
                } finally {
                    lock.unlock();
                }
            })) {
            removePinStamp(worldName, stampKey, zoneId);
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
