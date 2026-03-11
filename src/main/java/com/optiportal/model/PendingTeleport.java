package com.optiportal.model;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Represents a teleport that is pending chunk pre-load completion.
 * Held until all required chunks are ready, then fired on the world thread.
 */
public class PendingTeleport {

    private final UUID playerId;
    private final double destX;
    private final double destY;
    private final double destZ;
    private final double destYaw;
    private final String world;
    private final Instant createdAt;
    private final CompletableFuture<Void> completionFuture;
    private final TeleportCause cause;

    public enum TeleportCause {
        PORTAL,
        RESPAWN,
        BED_RESPAWN,
        DEATH_RETURN,
        COMMAND,
        ADMIN
    }

    public PendingTeleport(UUID playerId, double destX, double destY, double destZ,
                           double destYaw, String world, TeleportCause cause) {
        this.playerId = playerId;
        this.destX = destX;
        this.destY = destY;
        this.destZ = destZ;
        this.destYaw = destYaw;
        this.world = world;
        this.createdAt = Instant.now();
        this.completionFuture = new CompletableFuture<>();
        this.cause = cause;
    }

    public boolean isTimedOut(int timeoutSeconds) {
        return Instant.now().isAfter(createdAt.plusSeconds(timeoutSeconds));
    }

    public UUID getPlayerId() { return playerId; }
    public double getDestX() { return destX; }
    public double getDestY() { return destY; }
    public double getDestZ() { return destZ; }
    public double getDestYaw() { return destYaw; }
    public String getWorld() { return world; }
    public Instant getCreatedAt() { return createdAt; }
    public CompletableFuture<Void> getCompletionFuture() { return completionFuture; }
    public TeleportCause getCause() { return cause; }
}
