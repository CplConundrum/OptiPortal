package com.optiportal.preload;

import java.util.HashSet;
import java.util.Set;
import java.util.logging.Logger;

import com.hypixel.hytale.server.core.universe.world.chunk.BlockChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.EntityChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;

/**
 * Computes a complexity score for a loaded WorldChunk based on structural,
 * entity, and environmental factors accessible via the Hytale API.
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
    private static final int MAX_ENTITY_COUNT      = 64;   // normalize entity count to 0-1
    private static final int MAX_TICKING_BLOCKS    = 256;  // normalize ticking block count
    private static final int MAX_SECTIONS          = 24;   // Hytale max vertical sections
    private static final double HEIGHT_VARIANCE_SCALE = 1000.0; // normalize height variance

    // Score weights — must sum to 1.0
    private static final double WEIGHT_ENTITIES    = 0.30;
    private static final double WEIGHT_TICKING     = 0.25;
    private static final double WEIGHT_SECTIONS    = 0.20;
    private static final double WEIGHT_HEIGHT_VAR  = 0.15;
    private static final double WEIGHT_ENV_DENSITY = 0.10;

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
            BlockChunk bc = chunk.getBlockChunk();
            EntityChunk ec = chunk.getEntityChunk();
            if (bc == null || ec == null) return 0.0f;

            // --- Entity count ---
            int entityCount = 0;
            try {
                entityCount = ec.getEntityHolders().size();
            } catch (Exception e) {
                LOG.fine("[OptiPortal] ChunkComplexityScorer: entityHolders error: " + e.getMessage());
            }
            double entityScore = Math.min(1.0, entityCount / (double) MAX_ENTITY_COUNT);

            // --- Ticking blocks ---
            int tickingBlocks = 0;
            try {
                tickingBlocks = bc.getTickingBlocksCount();
            } catch (Exception e) {
                LOG.fine("[OptiPortal] ChunkComplexityScorer: tickingBlocks error: " + e.getMessage());
            }
            double tickingScore = Math.min(1.0, tickingBlocks / (double) MAX_TICKING_BLOCKS);

            // --- Section count (vertical density) ---
            int sectionCount = 0;
            try {
                sectionCount = bc.getSectionCount();
            } catch (Exception e) {
                LOG.fine("[OptiPortal] ChunkComplexityScorer: sectionCount error: " + e.getMessage());
            }
            double sectionScore = Math.min(1.0, sectionCount / (double) MAX_SECTIONS);

            // --- Height variance across 3x3 sample grid ---
            double heightVariance = 0.0;
            try {
                double[] heights = new double[HEIGHT_SAMPLE_X.length];
                double heightSum = 0.0;
                for (int i = 0; i < HEIGHT_SAMPLE_X.length; i++) {
                    short h = bc.getHeight(HEIGHT_SAMPLE_X[i], HEIGHT_SAMPLE_Z[i]);
                    heights[i] = h;
                    heightSum += h;
                }
                double mean = heightSum / heights.length;
                double variance = 0.0;
                for (double h : heights) {
                    double delta = h - mean;
                    variance += delta * delta;
                }
                variance /= heights.length;
                heightVariance = Math.min(1.0, variance / HEIGHT_VARIANCE_SCALE);
            } catch (Exception e) {
                LOG.fine("[OptiPortal] ChunkComplexityScorer: height sample error: " + e.getMessage());
            }

            // --- Environment diversity (biome sampling) ---
            // We cannot access EnvironmentChunk.counts directly (it is private).
            // Sample 3 positions and count distinct environment IDs.
            double envScore = 0.0;
            try {
                Set<Integer> envIds = new HashSet<>(4);
                // Centre of chunk
                int midY = bc.getHeight(7, 7);
                envIds.add(bc.getEnvironment(0,  bc.getHeight(0, 0),   0));
                envIds.add(bc.getEnvironment(7,  midY,                 7));
                envIds.add(bc.getEnvironment(15, bc.getHeight(15, 15), 15));
                // Additional corner samples to improve diversity detection
                envIds.add(bc.getEnvironment(0,  bc.getHeight(0, 15),  15));
                envIds.add(bc.getEnvironment(15, bc.getHeight(15, 0),   0));
                // 1 unique env → 0.0, 2 → 0.25, 3 → 0.5, 4 → 0.75, 5 → 1.0
                envScore = (envIds.size() - 1) / 4.0;
            } catch (Exception e) {
                LOG.fine("[OptiPortal] ChunkComplexityScorer: env sample error: " + e.getMessage());
            }

            // --- Weighted combination ---
            double combined = WEIGHT_ENTITIES   * entityScore
                            + WEIGHT_TICKING    * tickingScore
                            + WEIGHT_SECTIONS   * sectionScore
                            + WEIGHT_HEIGHT_VAR * heightVariance
                            + WEIGHT_ENV_DENSITY * envScore;

            return (float) Math.min(1.0, Math.max(0.0, combined));

        } catch (Exception e) {
            LOG.fine("[OptiPortal] ChunkComplexityScorer: unexpected error: " + e.getMessage());
            return 0.0f;
        }
    }

    /**
     * Estimate RAM usage for a chunk based on its structural properties.
     *
     * Formula:
     *   base = baseKbPerChunk (from config: bytesPerChunk / 1024.0)
     *   estimated = (base + entityCount * 2.0 + tickingBlocks * 0.5 + sectionCount * 8.0) / 1024.0
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
            BlockChunk bc = chunk.getBlockChunk();
            EntityChunk ec = chunk.getEntityChunk();
            if (bc == null || ec == null) return baseKbPerChunk / 1024.0;

            int entityCount = 0;
            int tickingBlocks = 0;
            int sectionCount = 0;

            try { entityCount   = ec.getEntityHolders().size();  } catch (Exception ignored) {}
            try { tickingBlocks = bc.getTickingBlocksCount();     } catch (Exception ignored) {}
            try { sectionCount  = bc.getSectionCount();           } catch (Exception ignored) {}

            double estimatedKb = baseKbPerChunk
                    + entityCount   * 2.0
                    + tickingBlocks * 0.5
                    + sectionCount  * 8.0;

            return estimatedKb / 1024.0; // Convert KB to MB
        } catch (Exception e) {
            LOG.fine("[OptiPortal] ChunkComplexityScorer.estimateRamMB error: " + e.getMessage());
            return baseKbPerChunk / 1024.0;
        }
    }
}
