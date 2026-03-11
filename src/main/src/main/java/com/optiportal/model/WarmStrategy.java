package com.optiportal.model;

/**
 * Strategy determining how a zone's chunks are managed.
 *
 * WARM     - Chunks permanently kept in memory as geometry only (no entities).
 *            Rebuilt on startup. Never expires. Immune to simulation distance reduction.
 *
 * PREDICTIVE - Chunks loaded async on portal approach. Cached after first visit.
 *              Cache expires per TTL config. Rebuilt from chunk files on cache miss.
 */
public enum WarmStrategy {
    WARM,
    PREDICTIVE
}
