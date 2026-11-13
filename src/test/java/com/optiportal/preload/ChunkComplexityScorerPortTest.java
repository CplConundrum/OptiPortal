package com.optiportal.preload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.common.collection.Flags;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.ChunkFlag;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

/**
 * §2 port tests for the 0.7.7 {@link ChunkComplexityScorer} rework:
 * per-section ticking, loaded-section residency, height variance, and
 * environment-diversity inputs (entity factor dropped; weights renormalized
 * to 0.3125 / 0.25 / 0.1875 / 0.125).
 *
 * <p>Follows the suite convention of no booted World: real engine value
 * classes (WorldChunk / BlockChunk / ChunkStore) are used directly — the
 * ChunkStore constructor is world-agnostic (verified in bytecode: it only
 * stores the field and returns), so a fresh store is a valid null-safe
 * fixture. Engine-level behavior (a genuinely loaded section producing
 * ticking counts) is covered at runtime by plan gates 4/5.
 */
class ChunkComplexityScorerPortTest {

    /**
     * Hytale server classes carry static initializers that force
     * {@code java.util.logging.manager} to {@code HytaleLogManager} (their
     * static block throws otherwise). Set the property at class-init time —
     * first chance before any engine class loads in the test JVM.
     */
    static {
        System.setProperty("java.util.logging.manager",
                "com.hypixel.hytale.logger.backend.HytaleLogManager");
    }

    /** Real WorldChunk with no reference set — resolveStore() must degrade to null. */
    private static WorldChunk newUnreferencedChunk() {
        return new WorldChunk(new Flags<ChunkFlag>(), new BlockChunk(3, -2));
    }

    @Test
    void scoreNullChunkIsZero() {
        assertEquals(0.0f, ChunkComplexityScorer.score(null));
    }

    @Test
    void unreferencedChunkScoresZero() {
        // No ref → no store → all four inputs read 0 → clamped 0.0.
        assertEquals(0.0f, ChunkComplexityScorer.score(newUnreferencedChunk()));
    }

    @Test
    void referencedEmptyChunkScoresZeroAndStaysInUnitRange() {
        ChunkStore store = new ChunkStore(null);
        WorldChunk chunk = new WorldChunk(new Flags<ChunkFlag>(), new BlockChunk(0, 0));
        chunk.setReference(new Ref<>(store.getStore(), 7));
        float score = ChunkComplexityScorer.score(chunk);
        assertTrue(score >= 0.0f && score <= 1.0f, "score must stay in [0,1], got " + score);
        // Bare store (internal Store is null until start()): the ref path NPEs
        // inside resolveStore's try/catch → 0.0f; and on a fully empty store the
        // zero inputs clamp the negative env term up to 0.0. Either way: 0.0.
        assertEquals(0.0f, score, 1e-6f);
    }

    @Test
    void estimateRamMBNullChunkIsBaseOnly() {
        // entity term ≡ 0 in 0.7.7; a null chunk returns exactly base/1024.0.
        assertEquals(8.0 / 1024.0, ChunkComplexityScorer.estimateRamMB(null, 8.0), 1e-9);
    }

    @Test
    void estimateRamMBUnreferencedChunkIsBaseOnly() {
        assertEquals(8.0 / 1024.0,
                ChunkComplexityScorer.estimateRamMB(newUnreferencedChunk(), 8.0), 1e-9);
    }

    @Test
    void cachePairIsNullSafeAndStable() {
        ChunkComplexityScorer.Cache cache = new ChunkComplexityScorer.Cache();
        assertEquals(0.0f, cache.getOrComputeScore("w", 1, 2, null));
        assertEquals(8.0 / 1024.0, cache.getOrComputeRam("w", 1, 2, null, 8.0), 1e-9);
        // Second call hits the cache — same result, no NPE (the rebuilt
        // [ticking, section] pair from the 0.7.7 rework).
        assertEquals(0.0f, cache.getOrComputeScore("w", 1, 2, null));
        assertEquals(8.0 / 1024.0, cache.getOrComputeRam("w", 1, 2, null, 8.0), 1e-9);
        cache.clearWorld("w");
    }

    // --- Formula seams (goldfish fix pass): the weighted combination and the
    // RAM estimate must be DISCRIMINATING — a "returns zero / base only"
    // implementation fails every one of these. ---

    @Test
    void scoreInputsAllMaximalInputsScorePointEightSevenFive() {
        // All inputs at their maxima: 0.3125 + 0.25 + 0.1875 + 0.125 = 0.875.
        // The weights deliberately sum below 1.0 (entity factor dropped), so a
        // fully dense chunk scores 0.875, NOT 1.0.
        assertEquals(0.875f, ChunkComplexityScorer.scoreInputs(256, 24, 1.0, 1.0), 1e-6f);
    }

    @Test
    void scoreInputsPartialInputsFollowTheWeightTable() {
        // ticking 128/256 = 0.5, sections 6/24 = 0.25, height 0.5, env 0.25:
        // 0.3125*0.5 + 0.25*0.25 + 0.1875*0.5 + 0.125*0.25 = 0.34375
        assertEquals(0.34375f, ChunkComplexityScorer.scoreInputs(128, 6, 0.5, 0.25), 1e-6f);
    }

    @Test
    void scoreInputsClampsOversizedAndNegativeInputs() {
        assertEquals(0.0f, ChunkComplexityScorer.scoreInputs(0, 0, 0.0, 0.0), 1e-6f);
        // Oversized raw counts clamp to their maxima (same as all-maximal).
        assertEquals(0.875f, ChunkComplexityScorer.scoreInputs(10_000, 500, 99.0, 99.0), 1e-6f);
        // Negative inputs clamp to 0 (never a negative score).
        assertEquals(0.0f, ChunkComplexityScorer.scoreInputs(-5, -2, -1.0, -1.0), 1e-6f);
    }

    @Test
    void estimateRamMBFormulaUsesTickingAndSectionTerms() {
        // (8 + 10*0.5 + 3*8.0) / 1024 = (8 + 5 + 24) / 1024 = 37/1024
        assertEquals(37.0 / 1024.0, ChunkComplexityScorer.estimateRamMBFromInputs(10, 3, 8.0), 1e-9);
        // Zero-activity chunk: base only.
        assertEquals(8.0 / 1024.0, ChunkComplexityScorer.estimateRamMBFromInputs(0, 0, 8.0), 1e-9);
    }
}
