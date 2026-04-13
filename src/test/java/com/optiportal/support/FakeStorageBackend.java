package com.optiportal.support;

import com.optiportal.model.PortalEntry;
import com.optiportal.storage.StorageBackend;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Lightweight in-memory StorageBackend for tests.
 */
public class FakeStorageBackend implements StorageBackend {

    private final Map<String, PortalEntry> entries = new LinkedHashMap<>();

    public FakeStorageBackend() {}

    public FakeStorageBackend(List<PortalEntry> seededEntries) {
        saveAll(seededEntries);
    }

    @Override public void init() {}
    @Override public List<PortalEntry> loadAll() {
        return entries.values().stream()
                .map(FakeStorageBackend::copyEntry)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }
    @Override public Optional<PortalEntry> loadById(String id) {
        return Optional.ofNullable(entries.get(id)).map(FakeStorageBackend::copyEntry);
    }
    @Override public void save(PortalEntry entry) {
        if (entry != null && entry.getId() != null) {
            entries.put(entry.getId(), copyEntry(entry));
        }
    }
    @Override public void saveAll(List<PortalEntry> entries) {
        if (entries == null) return;
        for (PortalEntry entry : entries) {
            save(entry);
        }
    }
    @Override public void delete(String id) { entries.remove(id); }
    @Override public String getBackendType() { return "FAKE"; }
    @Override public void close() {}

    private static PortalEntry copyEntry(PortalEntry entry) {
        PortalEntry copy = new PortalEntry();
        copy.setId(entry.getId());
        copy.setWorld(entry.getWorld());
        copy.setX(entry.getX());
        copy.setY(entry.getY());
        copy.setZ(entry.getZ());
        copy.setYaw(entry.getYaw());
        copy.setCreator(entry.getCreator());
        copy.setCreationDate(entry.getCreationDate());
        copy.setDestinationWorldUuid(entry.getDestinationWorldUuid());
        copy.setStrategy(entry.getStrategy());
        copy.setWarmRadius(entry.getWarmRadius());
        copy.setWarmRadiusX(entry.getWarmRadiusX());
        copy.setWarmRadiusZ(entry.getWarmRadiusZ());
        copy.setInstanced(entry.isInstanced());
        copy.setNotes(entry.getNotes());
        copy.setActivationDistance(entry.getActivationDistance());
        copy.setActivationDistanceHorizontal(entry.getActivationDistanceHorizontal());
        copy.setActivationDistanceVertical(entry.getActivationDistanceVertical());
        copy.setActivationShape(entry.getActivationShape());
        copy.setFloorCeilingCheck(entry.getFloorCeilingCheck());
        copy.setFacingCheck(entry.getFacingCheck());
        copy.setCacheTTLDays(entry.getCacheTTLDays());
        copy.setRamEstimatedMB(entry.getRamEstimatedMB());
        copy.setRamMarginalMB(entry.getRamMarginalMB());
        copy.setPreloadCount(entry.getPreloadCount());
        copy.setLastCacheTier(entry.getLastCacheTier());
        copy.setLastActive(entry.getLastActive());
        copy.setLastStatus(entry.getLastStatus());
        copy.setType(entry.getType());
        return copy;
    }
}
