package com.optiportal.preload;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.optiportal.config.PluginConfig;

/**
 * Learns and persists bidirectional portal links from observed teleports.
 *
 * <p>When a player walks through portal A and arrives near portal B, we record
 * the link A↔B. On subsequent approaches to either portal, the other is
 * automatically preloaded.
 *
 * <p>Links are promoted from pending to confirmed after reaching a configurable
 * confidence threshold (default 3 observations). Pending entries decay after
 * a configurable idle period (default 7 days).
 *
 * <p>Links are persisted to {@code portal-links.json} in the plugin data folder
 * so they survive server restarts. Pending links are persisted to
 * {@code portal-links-pending.json}.
 */
public class PortalLinkRegistry {

    private static final Logger LOG = Logger.getLogger("OptiPortal");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final File linksFile;
    private final File pendingLinksFile;

    /**
     * One-directional link map: portalId → linked portalId.
     * Both directions are stored explicitly so lookups are O(1).
     * e.g. "Othaka" → "Issa" and "Issa" → "Othaka"
     */
    private final Map<String, String> links = new ConcurrentHashMap<>();

    /**
     * Pending links staging map: canonical key "originId:destinationId" → PendingLink.
     * Pending entries accumulate observations and are promoted to confirmed links
     * when they reach the confidence threshold.
     * Key format: "originId:destinationId" with origin < destination lexicographically.
     */
    private final Map<String, PendingLink> pendingLinks = new ConcurrentHashMap<>();

    // Confidence threshold and decay configuration
    private final int confidenceThreshold;
    private final int pendingDecayDays;

    // Async debounced save fields
    private final ScheduledExecutorService executor;
    private volatile ScheduledFuture<?> pendingSave;        // debounce for save()
    private volatile ScheduledFuture<?> pendingLinksSave;   // debounce for savePendingLinks()
    private static final long SAVE_DEBOUNCE_MS = 2_000;

    /**
     * Constructor with executor and config for async debounced saves.
     * Saves are debounced for 2 seconds to batch multiple link changes.
     */
    public PortalLinkRegistry(File dataFolder, ScheduledExecutorService executor, PluginConfig config) {
        this.linksFile = new File(dataFolder, "portal-links.json");
        this.pendingLinksFile = new File(dataFolder, "portal-links-pending.json");
        this.executor = executor;
        this.confidenceThreshold = config.getPortalLinksConfidenceThreshold();
        this.pendingDecayDays = config.getPortalLinksPendingDecayDays();
        load();
        loadPendingLinks();
        scheduleDecayCleanup();
    }

    /**
     * Constructor for tests / contexts without a scheduled executor.
     * Uses hardcoded defaults (no config available).
     */
    public PortalLinkRegistry(File dataFolder) {
        this.linksFile = new File(dataFolder, "portal-links.json");
        this.pendingLinksFile = new File(dataFolder, "portal-links-pending.json");
        this.executor = null;
        this.confidenceThreshold = 3;
        this.pendingDecayDays = 7;
        load();
        loadPendingLinks();
        scheduleDecayCleanup();
    }

    /**
     * Record a link between two portals, overwriting any previous link for either.
     * Called whenever a teleport is observed: originId is where the player came from,
     * destinationId is the portal closest to where they arrived.
     *
     * If origin or destination is null or the same portal, does nothing.
     *
     * <p>Links are now promoted from pending to confirmed after reaching the
     * confidence threshold (default 3 observations). This prevents false positives
     * from single accidental observations.
     */
    public synchronized void recordLink(String originId, String destinationId) {
        if (originId == null || destinationId == null) return;
        if (originId.equals(destinationId)) return;
        if (isPlayerDataId(originId) || isPlayerDataId(destinationId)) return;

        // Skip if already confirmed
        if (destinationId.equals(links.get(originId))) return;

        String key = canonicalKey(originId, destinationId);
        PendingLink pending = pendingLinks.computeIfAbsent(key, k -> new PendingLink());
        pending.increment();

        if (pending.count >= confidenceThreshold) {
            pendingLinks.remove(key);
            // Promote to confirmed link
            links.put(originId, destinationId);
            links.put(destinationId, originId);
            LOG.info("[OptiPortal] PortalLinks: confirmed " + originId + " ↔ " + destinationId
                    + " (observed " + pending.count + " times)");
            scheduleSave();
            schedulePendingSave();
        } else {
            LOG.fine("[OptiPortal] PortalLinks: pending " + originId + " → " + destinationId
                    + " (" + pending.count + "/" + confidenceThreshold + ")");
            schedulePendingSave();
        }
    }

    /**
     * Returns the portal linked to the given portal ID, or null if unknown.
     */
    public String getLinkedPortal(String portalId) {
        return links.get(portalId);
    }

    /**
     * Returns true if a link is known for the given portal.
     */
    public boolean hasLink(String portalId) {
        return links.containsKey(portalId);
    }

    /** Returns an unmodifiable snapshot of the confirmed link map (both directions stored). */
    public Map<String, String> getLinks() {
        return java.util.Collections.unmodifiableMap(links);
    }

    /**
     * Returns a snapshot of pending link candidates as a map of canonical key → observation count.
     * Key format is "origin:destination" with the lexicographically-smaller ID first.
     */
    public Map<String, Integer> getPendingLinkCounts() {
        Map<String, Integer> snapshot = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, PendingLink> e : pendingLinks.entrySet()) {
            snapshot.put(e.getKey(), e.getValue().count);
        }
        return java.util.Collections.unmodifiableMap(snapshot);
    }

    /** Removes all pending (unconfirmed) link candidates and schedules a save. */
    public synchronized void clearPendingLinks() {
        pendingLinks.clear();
        schedulePendingSave();
    }

    /**
     * Manually remove a link (both directions). Useful if a portal is deleted.
     */
    public synchronized void removeLink(String portalId) {
        String linked = links.remove(portalId);
        if (linked != null) {
            links.remove(linked);
            LOG.info("[OptiPortal] PortalLinks: removed link for " + portalId);
            scheduleSave();
        }
    }

    // ------ Debounced Save
    // -------------------------------------------------------------------------

    /**
     * Schedules a save operation with debouncing.
     * Cancels any pending save and schedules a new one after the debounce interval.
     */
    private void scheduleSave() {
        if (executor == null) {
            save(); // fallback for no-executor constructor
            return;
        }
        ScheduledFuture<?> existing = pendingSave;
        if (existing != null) existing.cancel(false);
        pendingSave = executor.schedule(this::save, SAVE_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Schedules a pending links save operation with debouncing.
     */
    private void schedulePendingSave() {
        if (executor == null) {
            savePendingLinks(); // fallback for no-executor constructor
            return;
        }
        ScheduledFuture<?> existing = pendingLinksSave;
        if (existing != null) existing.cancel(false);
        pendingLinksSave = executor.schedule(this::savePendingLinks, SAVE_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Force an immediate save, cancelling any pending debounced write.
     * Call from shutdown hook to ensure last recorded links are not lost.
     */
    public void flush() {
        ScheduledFuture<?> existing = pendingSave;
        if (existing != null) {
            existing.cancel(false);
            pendingSave = null;
        }
        ScheduledFuture<?> existingPending = pendingLinksSave;
        if (existingPending != null) {
            existingPending.cancel(false);
            pendingLinksSave = null;
        }
        save();
        savePendingLinks();
    }

    // -------------------------------------------------------------------------
    // Persistence
    // -------------------------------------------------------------------------

    private void load() {
        if (!linksFile.exists()) return;
        try (Reader reader = new FileReader(linksFile)) {
            Type type = new TypeToken<Map<String, String>>(){}.getType();
            Map<String, String> loaded = GSON.fromJson(reader, type);
            if (loaded != null) {
                links.putAll(loaded);
                LOG.info("[OptiPortal] PortalLinks: loaded " + (links.size() / 2) + " link(s) from disk.");
            }
        } catch (Exception e) {
            LOG.warning("[OptiPortal] PortalLinks: failed to load portal-links.json: " + e.getMessage());
        }
    }

    private void loadPendingLinks() {
        if (!pendingLinksFile.exists()) return;
        try (Reader reader = new FileReader(pendingLinksFile)) {
            Type type = new TypeToken<Map<String, PendingLink>>(){}.getType();
            Map<String, PendingLink> loaded = GSON.fromJson(reader, type);
            if (loaded != null) {
                pendingLinks.putAll(loaded);
                LOG.info("[OptiPortal] PortalLinks: loaded " + pendingLinks.size() + " pending link(s) from disk.");
            }
        } catch (Exception e) {
            LOG.warning("[OptiPortal] PortalLinks: failed to load portal-links-pending.json: " + e.getMessage());
        }
    }

    /** Returns true for player-data entry IDs that should never participate in link learning. */
    private static boolean isPlayerDataId(String id) {
        return id.startsWith("death:") || id.startsWith("respawn:");
    }

    private void save() {
        try {
            linksFile.getParentFile().mkdirs();
            try (Writer writer = new FileWriter(linksFile)) {
                GSON.toJson(links, writer);
            }
        } catch (Exception e) {
            LOG.warning("[OptiPortal] PortalLinks: failed to save portal-links.json: " + e.getMessage());
        }
    }

    private void savePendingLinks() {
        try {
            pendingLinksFile.getParentFile().mkdirs();
            try (Writer writer = new FileWriter(pendingLinksFile)) {
                GSON.toJson(pendingLinks, writer);
            }
        } catch (Exception e) {
            LOG.warning("[OptiPortal] PortalLinks: failed to save portal-links-pending.json: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Decay cleanup
    // -------------------------------------------------------------------------

    /**
     * Schedules a periodic cleanup task to remove expired pending links.
     * Runs every 24 hours to remove pending links that have been idle
     * for longer than pendingDecayDays.
     */
    private void scheduleDecayCleanup() {
        if (executor == null) return;
        
        executor.scheduleAtFixedRate(() -> {
            int before = pendingLinks.size();
            pendingLinks.entrySet().removeIf(entry -> {
                PendingLink pending = entry.getValue();
                long ageMs = System.currentTimeMillis() - pending.lastSeenMs;
                return ageMs > pendingDecayDays * 86_400_000L;
            });
            int removed = before - pendingLinks.size();
            
            if (removed > 0) {
                LOG.info("[OptiPortal] PortalLinks: decayed " + removed + " expired pending link(s).");
                schedulePendingSave();
            }
        }, 24, 24, TimeUnit.HOURS);
    }

    // -------------------------------------------------------------------------
    // Inner class: PendingLink
    // -------------------------------------------------------------------------

    /**
     * Tracks pending link observations until they reach the confidence threshold.
     * Pending entries decay after a configurable idle period.
     */
    private static class PendingLink {
        int count;
        long lastSeenMs;

        // count starts at 0; recordLink always calls increment(), so first observation → count=1
        PendingLink() {
            count = 0;
            lastSeenMs = System.currentTimeMillis();
        }

        void increment() {
            count++;
            lastSeenMs = System.currentTimeMillis();
        }

        boolean isExpired(int decayDays) {
            long ageMs = System.currentTimeMillis() - lastSeenMs;
            return ageMs > decayDays * 86_400_000L;
        }
    }

    /**
     * Creates a canonical key for a link pair.
     * The key format is "originId:destinationId" with origin < destination lexicographically.
     * This ensures that the same link pair always produces the same key regardless of order.
     */
    private static String canonicalKey(String a, String b) {
        return a.compareTo(b) <= 0 ? a + ":" + b : b + ":" + a;
    }
}
