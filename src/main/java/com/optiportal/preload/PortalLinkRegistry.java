package com.optiportal.preload;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

/**
 * Learns and persists bidirectional portal links from observed teleports.
 *
 * <p>When a player walks through portal A and arrives near portal B, we record
 * the link A↔B. On subsequent approaches to either portal, the other is
 * automatically preloaded.
 *
 * <p>Links are overwritten on every new teleport observation — if a portal's
 * target warp is changed in-game, the next teleport through it updates the
 * learned link automatically.
 *
 * <p>Links are persisted to {@code portal-links.json} in the plugin data folder
 * so they survive server restarts.
 */
public class PortalLinkRegistry {

    private static final Logger LOG = Logger.getLogger("OptiPortal");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final File linksFile;

    /**
     * One-directional link map: portalId → linked portalId.
     * Both directions are stored explicitly so lookups are O(1).
     * e.g. "Othaka" → "Issa" and "Issa" → "Othaka"
     */
    private final Map<String, String> links = new HashMap<>();

    public PortalLinkRegistry(File dataFolder) {
        this.linksFile = new File(dataFolder, "portal-links.json");
        load();
    }

    /**
     * Record a link between two portals, overwriting any previous link for either.
     * Called whenever a teleport is observed: originId is where the player came from,
     * destinationId is the portal closest to where they arrived.
     *
     * If origin or destination is null or the same portal, does nothing.
     */
    public void recordLink(String originId, String destinationId) {
        if (originId == null || destinationId == null) return;
        if (originId.equals(destinationId)) return;
        if (isPlayerDataId(originId) || isPlayerDataId(destinationId)) return;

        // Skip if link is already correct — avoids unnecessary map writes and disk I/O
        if (destinationId.equals(links.get(originId)) && originId.equals(links.get(destinationId))) return;

        String prevDestination = links.get(originId);
        String prevOrigin = links.get(destinationId);

        if (prevDestination != null && !prevDestination.equals(destinationId)) {
            links.remove(prevDestination);
            LOG.info("[OptiPortal] PortalLinks: updated " + originId
                    + " (was → " + prevDestination + ", now → " + destinationId + ")");
        }
        if (prevOrigin != null && !prevOrigin.equals(originId)) {
            links.remove(prevOrigin);
        }

        links.put(originId, destinationId);
        links.put(destinationId, originId);

        LOG.info("[OptiPortal] PortalLinks: learned " + originId + " ↔ " + destinationId);
        save();
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

    /**
     * Manually remove a link (both directions). Useful if a portal is deleted.
     */
    public void removeLink(String portalId) {
        String linked = links.remove(portalId);
        if (linked != null) {
            links.remove(linked);
            LOG.info("[OptiPortal] PortalLinks: removed link for " + portalId);
            save();
        }
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
}
