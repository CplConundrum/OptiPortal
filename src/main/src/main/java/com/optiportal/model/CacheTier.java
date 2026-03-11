package com.optiportal.model;

/**
 * Represents the current loading state of a zone's chunks.
 *
 * HOT      - Chunks fully loaded, world thread active. Player present or recently active.
 * WARM     - Chunks still loaded, in buffer period before dropping to COLD.
 * COLD     - Chunk geometry serialized to in-memory cache. Zero world thread cost.
 *            Restores to HOT in milliseconds on demand.
 * UNVISITED - No cache exists yet. Will load from Hytale chunk files on first visit.
 * REBUILDING - Cache is being rebuilt async from Hytale chunk files.
 */
public enum CacheTier {
    HOT,
    WARM,
    COLD,
    UNVISITED,
    REBUILDING
}
