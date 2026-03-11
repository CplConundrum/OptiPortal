package com.optiportal.model;

import java.time.Instant;

/**
 * Represents a warp/portal destination managed by OptiPortal.
 * Owns all data previously held by Hytale's warps.json, plus plugin metadata.
 */
public class PortalEntry {

    // Core warp data (sourced from warps.json)
    private String id;
    private String world;
    private double x;
    private double y;
    private double z;
    private double yaw;
    private String creator;
    private Instant creationDate;

    // Plugin strategy data
    private WarmStrategy strategy = WarmStrategy.PREDICTIVE;
    private int warmRadius = -1;        // -1 = use global default
    private Integer warmRadiusX = null; // null = use warmRadius
    private Integer warmRadiusZ = null; // null = use warmRadius
    private boolean instanced = false;
    private String notes = "";

    // Activation config (null = use global defaults)
    private Double activationDistance = null;
    private Double activationDistanceVertical = null;
    private String activationShape = null;
    private Boolean floorCeilingCheck = null;
    private Boolean facingCheck = null;

    // Cache TTL override (-1 = never, null = use global default)
    private Integer cacheTTLDays = null;

    // RAM tracking
    private double ramEstimatedMB = 0;
    private double ramMarginalMB = 0;

    // Usage stats
    private int preloadCount = 0;
    private CacheTier lastCacheTier = CacheTier.UNVISITED;
    private Instant lastActive = null;
    private String lastStatus = "UNVISITED";

    // Entry type
    private EntryType type = EntryType.PORTAL;

    public enum EntryType {
        PORTAL,
        BED,
        DEATH,
        MANUAL
    }

    public PortalEntry() {}

    public PortalEntry(String id, String world, double x, double y, double z, double yaw) {
        this.id = id;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
    }

    // --- Getters and setters ---

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getWorld() { return world; }
    public void setWorld(String world) { this.world = world; }

    public double getX() { return x; }
    public void setX(double x) { this.x = x; }

    public double getY() { return y; }
    public void setY(double y) { this.y = y; }

    public double getZ() { return z; }
    public void setZ(double z) { this.z = z; }

    public double getYaw() { return yaw; }
    public void setYaw(double yaw) { this.yaw = yaw; }

    public String getCreator() { return creator; }
    public void setCreator(String creator) { this.creator = creator; }

    public Instant getCreationDate() { return creationDate; }
    public void setCreationDate(Instant creationDate) { this.creationDate = creationDate; }

    public WarmStrategy getStrategy() { return strategy; }
    public void setStrategy(WarmStrategy strategy) { this.strategy = strategy; }

    public int getWarmRadius() { return warmRadius; }
    public void setWarmRadius(int warmRadius) { this.warmRadius = warmRadius; }

    public Integer getWarmRadiusX() { return warmRadiusX; }
    public void setWarmRadiusX(Integer warmRadiusX) { this.warmRadiusX = warmRadiusX; }

    public Integer getWarmRadiusZ() { return warmRadiusZ; }
    public void setWarmRadiusZ(Integer warmRadiusZ) { this.warmRadiusZ = warmRadiusZ; }

    public boolean isInstanced() { return instanced; }
    public void setInstanced(boolean instanced) { this.instanced = instanced; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public Double getActivationDistance() { return activationDistance; }
    public void setActivationDistance(Double activationDistance) { this.activationDistance = activationDistance; }

    public Double getActivationDistanceVertical() { return activationDistanceVertical; }
    public void setActivationDistanceVertical(Double activationDistanceVertical) { this.activationDistanceVertical = activationDistanceVertical; }

    public String getActivationShape() { return activationShape; }
    public void setActivationShape(String activationShape) { this.activationShape = activationShape; }

    public Boolean getFloorCeilingCheck() { return floorCeilingCheck; }
    public void setFloorCeilingCheck(Boolean floorCeilingCheck) { this.floorCeilingCheck = floorCeilingCheck; }

    public Boolean getFacingCheck() { return facingCheck; }
    public void setFacingCheck(Boolean facingCheck) { this.facingCheck = facingCheck; }

    public Integer getCacheTTLDays() { return cacheTTLDays; }
    public void setCacheTTLDays(Integer cacheTTLDays) { this.cacheTTLDays = cacheTTLDays; }

    public double getRamEstimatedMB() { return ramEstimatedMB; }
    public void setRamEstimatedMB(double ramEstimatedMB) { this.ramEstimatedMB = ramEstimatedMB; }

    public double getRamMarginalMB() { return ramMarginalMB; }
    public void setRamMarginalMB(double ramMarginalMB) { this.ramMarginalMB = ramMarginalMB; }

    public int getPreloadCount() { return preloadCount; }
    public void setPreloadCount(int preloadCount) { this.preloadCount = preloadCount; }
    public void incrementPreloadCount() { this.preloadCount++; }

    public CacheTier getLastCacheTier() { return lastCacheTier; }
    public void setLastCacheTier(CacheTier lastCacheTier) { this.lastCacheTier = lastCacheTier; }

    public Instant getLastActive() { return lastActive; }
    public void setLastActive(Instant lastActive) { this.lastActive = lastActive; }

    public String getLastStatus() { return lastStatus; }
    public void setLastStatus(String lastStatus) { this.lastStatus = lastStatus; }

    public EntryType getType() { return type; }
    public void setType(EntryType type) { this.type = type; }

    /**
     * Returns true if this entry is for a bed or death location.
     */
    public boolean isPlayerOwned() {
        return type == EntryType.BED || type == EntryType.DEATH;
    }
}