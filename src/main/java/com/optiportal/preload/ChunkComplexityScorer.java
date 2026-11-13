package com.optiportal.preload;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockChunk.EnvironmentRunCursor;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.heightmap.HeightmapColumn;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

/**
 * Computes a complexity score for a loaded WorldChunk based on ticking,
 * section, height and environmental factors accessible via the Hytale API.
 * 0.7.7: entity factor dropped (EntityChunk access is destructive-only).
 *
 * Threading: All methods are stateless and thread-safe.
 * All chunk data access is done on whatever thread this is called from.
 * Call from a CompletableFuture callback (not the world thread) is safe since
 * BlockChunk / EntityChunk reads do not require the world thread.
 *
 * Score range: 0.0 (empty/flat) to 1.0 (dense/complex).
 */
public final class ChunkComplexityScorer {

    private static final Logger LOG = Logger.getLogger("OptiPortal");

    // Normalization constants
    private static final int MAX_TICKING_BLOCKS    = 256;  // normalize ticking block count
    private static final int MAX_SECTIONS          = 24;   // Hytale max vertical sections
    private static final double HEIGHT_VARIANCE_SCALE = 1000.0; // normalize height variance

    // Score weights — sum to 1.0 (entity factor dropped in 0.7.7, ÷0.8 renormalized)
    private static final double WEIGHT_TICKING     = 0.3125;
    private static final double WEIGHT_SECTIONS    = 0.25;
    private static final double WEIGHT_HEIGHT_VAR  = 0.1875;
    private static final double WEIGHT_ENV_DENSITY = 0.125;

    // Height sample grid positions within a chunk (x: 0-15, z: 0-15)
    private static final int[] HEIGHT_SAMPLE_X = {0, 7, 15, 0, 7, 15, 0, 7, 15};
    private static final int[] HEIGHT_SAMPLE_Z = {0, 0,  0, 7, 7,  7, 15, 15, 15};

    private ChunkComplexityScorer() {} // static utility class

    /**
     * Compute a complexity score for the given chunk.
     *
     * @param chunk A loaded WorldChunk (must not be null).
     * @return Complexity score in [0.0, 1.0]. Returns 0.0 on any access error.
     */
    public static float score(WorldChunk chunk) {
        if (chunk == null) return 0.0f;
        try {
            ChunkStore store = resolveStore(chunk);
            if (store == null) return 0.0f;
            int cx = chunk.getX();
            int cz = chunk.getZ();
            long index = chunk.getIndex();

            // --- Ticking blocks (per-section, non-destructive Copy getter) ---
            // --- Section residency (resident section references) ---
            int tickingBlocks = 0;
            int sectionCount = 0;
            try {
                java.util.List<Ref<ChunkStore>> sectionRefs =
                        store.getLoadedSectionReferences(cx, cz);
                if (sectionRefs != null) {
                    sectionCount = sectionRefs.size();
                    for (Ref<ChunkStore> sectionRef : sectionRefs) {
                        if (sectionRef == null || !sectionRef.isValid()) continue;
                        // Ref-based concurrent read (the entity slot is not a
                        // spatial key — getChunkComponent would resolve the wrong
                        // column or null).
                        BlockSection section = store.getStore().getComponentConcurrent(
                                sectionRef, BlockSection.getComponentType());
                        if (section != null) {
                            try { tickingBlocks += section.getTickingBlocksCountCopy(); }
                            catch (Exception ignored) {} // section mid-mutation — skip
                        }
                    }
                }
            } catch (Exception e) {
                LOG.fine("[OptiPortal] ChunkComplexityScorer: ticking/section error: " + e.getMessage());
            }
            double tickingScore = Math.min(1.0, tickingBlocks / (double) MAX_TICKING_BLOCKS);
            double sectionScore = Math.min(1.0, sectionCount / (double) MAX_SECTIONS);

            // --- Height variance across 3x3 sample grid (NO_HEIGHT samples skipped) ---
            double heightVariance = 0.0;
            try {
                HeightmapColumn heightmap = store.getChunkComponent(
                        index, HeightmapColumn.getComponentType());
                if (heightmap != null) {
                    double[] heights = new double[HEIGHT_SAMPLE_X.length];
                    double heightSum = 0.0;
                    int samples = 0;
                    for (int i = 0; i < HEIGHT_SAMPLE_X.length; i++) {
                        int h = heightmap.getHeight(HEIGHT_SAMPLE_X[i], HEIGHT_SAMPLE_Z[i]);
                        if (h == HeightmapColumn.NO_HEIGHT) continue; // column not loaded
                        heights[samples++] = h;
                        heightSum += h;
                    }
                    if (samples > 1) {
                        double mean = heightSum / samples;
                        double variance = 0.0;
                        for (int i = 0; i < samples; i++) {
                            double delta = heights[i] - mean;
                            variance += delta * delta;
                        }
                        variance /= samples;
                        heightVariance = Math.min(1.0, variance / HEIGHT_VARIANCE_SCALE);
                    }
                }
            } catch (Exception e) {
                LOG.fine("[OptiPortal] ChunkComplexityScorer: height sample error: " + e.getMessage());
            }

            // --- Environment diversity (distinct environment IDs per sample column) ---
            // 0.7.7: environment is stored as per-column runs; sample the 5
            // positions and count DISTINCT IDs (diversity, not run length).
            double envScore = 0.0;
            try {
                BlockChunk bc = store.getChunkComponent(index, BlockChunk.getComponentType());
                if (bc != null) {
                    Set<Integer> envIds = new HashSet<>(4);
                    int[][] samplesXZ = {{0, 0}, {7, 7}, {15, 15}, {0, 15}, {15, 0}};
                    for (int[] s : samplesXZ) {
                        EnvironmentRunCursor cursor = new EnvironmentRunCursor();
                        bc.readEnvironmentRuns(s[0], s[1], cursor);
                        while (cursor.next()) {
                            envIds.add(cursor.getEnvironmentId());
                        }
                    }
                    // 1 unique env → 0.0, 2 → 0.25, 3 → 0.5, 4 → 0.75, 5 → 1.0
                    envScore = Math.min(1.0, (envIds.size() - 1) / 4.0);
                }
            } catch (Exception e) {
                LOG.fine("[OptiPortal] ChunkComplexityScorer: env sample error: " + e.getMessage());
            }

            // --- Weighted combination (entity factor dropped in 0.7.7) ---
            return scoreInputs(tickingBlocks, sectionCount, heightVariance, envScore);

        } catch (Exception e) {
            LOG.fine(() -> "[OptiPortal] ChunkComplexityScorer: unexpected error: " + e.getMessage());
            return 0.0f;
        }
    }

    /**
     * Weighted combination of the four scorer inputs. Package-private formula
     * seam: testable without a booted engine store; {@link #score(WorldChunk)}
     * feeds it the values read from the chunk's components.
     *
     * @param tickingBlocks  raw ticking-block count (clamped at MAX_TICKING_BLOCKS)
     * @param sectionCount   loaded section count (clamped at MAX_SECTIONS)
     * @param heightVariance normalized height variance in [0,1]
     * @param envScore       normalized environment diversity in [0,1]
     * @return weighted score clamped to [0,1] — the weights sum to 0.875, so a
     *         fully maximal chunk scores 0.875 by construction, not 1.0
     */
    static float scoreInputs(int tickingBlocks, int sectionCount, double heightVariance, double envScore) {
        double tickingScore = Math.min(1.0, tickingBlocks / (double) MAX_TICKING_BLOCKS);
        double sectionScore = Math.min(1.0, sectionCount / (double) MAX_SECTIONS);
        double hScore = Math.min(1.0, Math.max(0.0, heightVariance));
        double eScore = Math.min(1.0, Math.max(0.0, envScore));
        double combined = WEIGHT_TICKING     * tickingScore
                        + WEIGHT_SECTIONS    * sectionScore
                        + WEIGHT_HEIGHT_VAR  * hScore
                        + WEIGHT_ENV_DENSITY * eScore;
        return (float) Math.min(1.0, Math.max(0.0, combined));
    }

    /**
     * Estimate RAM usage for a chunk based on its structural properties.
     *
     * Formula (entity factor dropped in 0.7.7):
     *   base = baseKbPerChunk (from config: bytesPerChunk / 1024.0)
     *   estimated = (base + tickingBlocks * 0.5 + sectionCount * 8.0) / 1024.0
     *
     * Result is in MB.
     *
     * @param chunk           A loaded WorldChunk (must not be null).
     * @param baseKbPerChunk  Base KB per chunk from config (bytesPerChunk / 1024.0).
     * @return Estimated RAM in MB. Returns baseKbPerChunk/1024.0 on error.
     */
    public static double estimateRamMB(WorldChunk chunk, double baseKbPerChunk) {
        if (chunk == null) return baseKbPerChunk / 1024.0;
        try {
            ChunkStore store = resolveStore(chunk);
            if (store == null) return baseKbPerChunk / 1024.0;

            int tickingBlocks = 0;
            int sectionCount = 0;
            java.util.List<Ref<ChunkStore>> sectionRefs =
                    store.getLoadedSectionReferences(chunk.getX(), chunk.getZ());
            if (sectionRefs != null) {
                sectionCount = sectionRefs.size();
                for (Ref<ChunkStore> sectionRef : sectionRefs) {
                    if (sectionRef == null || !sectionRef.isValid()) continue;
                    BlockSection section = store.getStore().getComponentConcurrent(
                            sectionRef, BlockSection.getComponentType());
                    if (section != null) {
                        try { tickingBlocks += section.getTickingBlocksCountCopy(); }
                        catch (Exception ignored) {}
                    }
                }
            }

            return estimateRamMBFromInputs(tickingBlocks, sectionCount, baseKbPerChunk);
        } catch (Exception e) {
            LOG.fine(() -> "[OptiPortal] ChunkComplexityScorer.estimateRamMB error: " + e.getMessage());
            return baseKbPerChunk / 1024.0;
        }
    }

    /**
     * RAM-estimation formula seam (package-private, testable without a booted
     * engine store): KB estimate from the structural inputs, converted to MB.
     */
    static double estimateRamMBFromInputs(int tickingBlocks, int sectionCount, double baseKbPerChunk) {
        double estimatedKb = baseKbPerChunk + tickingBlocks * 0.5 + sectionCount * 8.0;
        return estimatedKb / 1024.0;
    }
/**
 * 0.7.7: World no longer exposes chunk accessors — the owning ChunkStore is
 * obtained from the chunk's ECS store (root external data). Null-safe.
 */
private static ChunkStore resolveStore(WorldChunk chunk) {
    try {
        Ref<ChunkStore> ref = chunk.getReference();
        if (ref == null || !ref.isValid()) return null;
        return ref.getStore().getExternalData();
    } catch (Exception e) {
        LOG.fine("[OptiPortal] ChunkComplexityScorer: store resolve error: " + e.getMessage());
        return null;
    }
}

/**
 * Optional result cache for ChunkComplexityScorer.score() and estimateRamMB().
 * Keyed by worldName → packed chunk index (low32=cx, high32=cz).
 * Thread-safe. Bounded per world via MAX_ENTRIES_PER_WORLD.
 * Clear a world's entries with clearWorld(worldName) on RemoveWorldEvent.
 */
public static final class Cache {

    private static final int MAX_ENTRIES_PER_WORLD = 5_000;

    // outer key = world name, inner key = packChunkIndex(cx, cz)
    private final ConcurrentHashMap<String, ConcurrentHashMap<Long, Float>> scores =
            new ConcurrentHashMap<>();

    // worldName → packed chunk index → [tickingBlocks, sectionCount]
    private final ConcurrentHashMap<String, ConcurrentHashMap<Long, long[]>> components =
            new ConcurrentHashMap<>();

    /** Return cached score, or compute-and-store via ChunkComplexityScorer.score(). */
    public float getOrComputeScore(String worldName, int cx, int cz,
                                   WorldChunk chunk) {
        ConcurrentHashMap<Long, Float> worldCache =
                scores.computeIfAbsent(worldName, k -> new ConcurrentHashMap<>());
        long key = packChunkIndex(cx, cz);
        Float cached = worldCache.get(key);
        if (cached != null) return cached;

        float computed = ChunkComplexityScorer.score(chunk);
        if (worldCache.size() < MAX_ENTRIES_PER_WORLD) {
            worldCache.put(key, computed);
        }
        return computed;
    }

    /** Return cached RAM estimate, or compute-and-store via estimateRamMB(). */
    public double getOrComputeRam(String worldName, int cx, int cz,
                                  WorldChunk chunk, double baseKbPerChunk) {
        ConcurrentHashMap<Long, long[]> worldComponents =
                components.computeIfAbsent(worldName, k -> new ConcurrentHashMap<>());
        long key = packChunkIndex(cx, cz);
        long[] cached = worldComponents.get(key);

        long tickingBlocks, sectionCount;
        if (cached != null) {
            // Use cached raw values — zero Hytale API calls
            tickingBlocks = cached[0];
            sectionCount  = cached[1];
        } else {
            // First time this chunk is seen — read from chunk and cache components
            tickingBlocks = 0;
            sectionCount  = 0;
            if (chunk != null) {
                ChunkStore store = resolveStore(chunk);
                if (store != null) {
                    java.util.List<Ref<ChunkStore>> sectionRefs =
                            store.getLoadedSectionReferences(cx, cz);
                    if (sectionRefs != null) {
                        sectionCount = sectionRefs.size();
                        for (Ref<ChunkStore> sectionRef : sectionRefs) {
                            if (sectionRef == null || !sectionRef.isValid()) continue;
                            BlockSection section = store.getStore().getComponentConcurrent(
                                    sectionRef, BlockSection.getComponentType());
                            if (section != null) {
                                try { tickingBlocks += section.getTickingBlocksCountCopy(); }
                                catch (Exception ignored) {}
                            }
                        }
                    }
                }
            }
            if (worldComponents.size() < MAX_ENTRIES_PER_WORLD) {
                worldComponents.put(key, new long[]{ tickingBlocks, sectionCount });
            }
        }

        double estimatedKb = baseKbPerChunk
                + tickingBlocks * 0.5
                + sectionCount  * 8.0;
        return estimatedKb / 1024.0;
    }

    /** Remove all cached scores for a world. Call on RemoveWorldEvent. */
    public void clearWorld(String worldName) {
        scores.remove(worldName);
        components.remove(worldName);
    }

    /** Remove all cached scores. */
    public void clearAll() {
        scores.clear();
        components.clear();
    }

    public boolean isEmpty() {
        return scores.isEmpty() && components.isEmpty();
    }

    private static long packChunkIndex(int cx, int cz) {
        return ((long)(cx & 0xFFFFFFFF)) | ((long)(cz & 0xFFFFFFFF) << 32);
    }
    }
}
