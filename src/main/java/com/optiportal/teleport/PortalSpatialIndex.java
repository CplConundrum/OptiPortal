package com.optiportal.teleport;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.optiportal.model.PortalEntry;
import com.optiportal.preload.ChunkPreloader;

/**
 * Immutable spatial index for portal entries grouped by chunk.
 * 
 * Provides O(1) chunk lookup for nearby portal candidates, avoiding full-world scans.
 */
public final class PortalSpatialIndex {

    /** Chunk key → list of plain portal entries in that chunk. */
    private final Map<Long, List<PortalEntry>> plainPortalsByChunk;

    /** Chunk key → list of portal device entries in that chunk. */
    private final Map<Long, List<PortalEntry>> portalDevicesByChunk;

    /** Flat list of all plain portals for iteration fallback. */
    private final List<PortalEntry> plainPortals;

    /** Flat list of all portal devices for iteration fallback. */
    private final List<PortalEntry> portalDevices;

    /**
     * Maximum activationDistanceHorizontal override across all plain portals in this index.
     * 0.0 if no plain portal has an override set.
     */
    private final double maxHorizontalActivationDistance;

    /**
     * Creates an immutable spatial index from a list of portal entries.
     *
     * @param entries Portal entries to index
     */
    public PortalSpatialIndex(List<PortalEntry> entries) {
        // Initialize bucket maps - single-threaded assembly, then immutable snapshot
        HashMap<Long, List<PortalEntry>> plainMap = new HashMap<>();
        HashMap<Long, List<PortalEntry>> deviceMap = new HashMap<>();
        ArrayList<PortalEntry> plainList = new ArrayList<>();
        ArrayList<PortalEntry> deviceList = new ArrayList<>();
        double maxHoriz = 0.0;

        for (PortalEntry entry : entries) {
            long chunkKey = ChunkPreloader.packChunk(
                ChunkPreloader.toChunkCoord(entry.getX()),
                ChunkPreloader.toChunkCoord(entry.getZ())
            );

            // Separate plain portals from portal devices
            boolean isPlain = isPlainPortal(entry);
            boolean isDevice = isPortalDevice(entry);

            if (isPlain) {
                plainList.add(entry);
                plainMap.computeIfAbsent(chunkKey, k -> new ArrayList<>()).add(entry);
                if (entry.getActivationDistanceHorizontal() != null) {
                    maxHoriz = Math.max(maxHoriz, entry.getActivationDistanceHorizontal());
                }
            }

            if (isDevice) {
                deviceList.add(entry);
                deviceMap.computeIfAbsent(chunkKey, k -> new ArrayList<>()).add(entry);
            }
        }

        this.plainPortalsByChunk = Collections.unmodifiableMap(plainMap);
        this.portalDevicesByChunk = Collections.unmodifiableMap(deviceMap);
        this.plainPortals = Collections.unmodifiableList(plainList);
        this.portalDevices = Collections.unmodifiableList(deviceList);
        this.maxHorizontalActivationDistance = maxHoriz;
    }

    /**
     * Returns the largest activationDistanceHorizontal override across all plain portals.
     * Returns 0.0 if no portal has an override (caller should fall back to global config).
     */
    public double getMaxHorizontalActivationDistance() {
        return maxHorizontalActivationDistance;
    }

    /**
     * Returns the list of plain portal entries in a chunk, or empty list if none.
     * 
     * @param chunkKey Chunk key (from ChunkPreloader.packChunk)
     * @return List of plain portal entries in that chunk
     */
    public List<PortalEntry> getPlainPortalsInChunk(long chunkKey) {
        return plainPortalsByChunk.getOrDefault(chunkKey, Collections.emptyList());
    }

    /**
     * Returns the list of portal device entries in a chunk, or empty list if none.
     * 
     * @param chunkKey Chunk key (from ChunkPreloader.packChunk)
     * @return List of portal device entries in that chunk
     */
    public List<PortalEntry> getPortalDevicesInChunk(long chunkKey) {
        return portalDevicesByChunk.getOrDefault(chunkKey, Collections.emptyList());
    }

    /**
     * Returns all plain portals in this index.
     * 
     * @return Unmodifiable list of plain portal entries
     */
    public List<PortalEntry> getAllPlainPortals() {
        return plainPortals;
    }

    /**
     * Returns all portal devices in this index.
     * 
     * @return Unmodifiable list of portal device entries
     */
    public List<PortalEntry> getAllPortalDevices() {
        return portalDevices;
    }

    /**
     * Returns the count of plain portals in this index.
     * 
     * @return Number of plain portal entries
     */
    public int getPlainPortalCount() {
        return plainPortals.size();
    }

    /**
     * Returns the count of portal devices in this index.
     * 
     * @return Number of portal device entries
     */
    public int getPortalDeviceCount() {
        return portalDevices.size();
    }

    /**
     * Checks if an entry should be indexed as a plain portal.
     * Plain portals are non-instanced entries without ":" in the ID.
     */
    private static boolean isPlainPortal(PortalEntry entry) {
        return !entry.isInstanced() && !entry.getId().contains(":");
    }

    /**
     * Checks if an entry is a portal device.
     * Portal devices have IDs starting with "portaldevice:".
     */
    private static boolean isPortalDevice(PortalEntry entry) {
        return entry.getId().startsWith("portaldevice:");
    }
}