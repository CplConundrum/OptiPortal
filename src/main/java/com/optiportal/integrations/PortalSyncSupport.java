package com.optiportal.integrations;

import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.optiportal.model.PortalEntry;
import com.optiportal.storage.StorageBackend;

import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Shared helpers for the integration watchers (deslopify qd-002 minimal move).
 *
 * <p>{@link #deleteMissing} is the watcher-owned deletion loop previously
 * duplicated in HyTeleportersXWatcher.syncTeleporters and
 * WarpFileWatcher.syncWarps: entries in storage that are no longer present in
 * the incoming set are deleted and reported through the callback.
 *
 * <p>{@link #isPluginLoaded} is the engine plugin-availability probe
 * previously duplicated in HyTeleportersXWatcher.isPluginAvailable and
 * BetterGravestoneWatcher.isPluginAvailable.
 */
public final class PortalSyncSupport {

    private static final Logger LOG = Logger.getLogger("OptiPortal");

    private PortalSyncSupport() {
        throw new UnsupportedOperationException("static helpers only");
    }

    /**
     * Deletes storage entries owned by the watcher that are no longer present
     * in the incoming id set.
     *
     * <p>Each removed id is reported to {@code onPortalDeleted} (so portal
     * caches can purge) and then deleted from storage. The per-watcher log
     * label is preserved by the {@code logLabel} parameter ("HyTeleportersX
     * teleporter" / "Warp").
     *
     * @return the number of entries removed
     */
    public static int deleteMissing(Map<String, PortalEntry> existing, Set<String> incomingIds,
                                    Consumer<String> onPortalDeleted, StorageBackend storage,
                                    String logLabel) {
        int removed = 0;
        for (String existingId : existing.keySet()) {
            if (!incomingIds.contains(existingId)) {
                PortalEntry ex = existing.get(existingId);
                if (ex != null) {
                    onPortalDeleted.accept(existingId);
                    storage.delete(existingId);
                    LOG.info("[OptiPortal] " + logLabel + " removed: " + existingId);
                    removed++;
                }
            }
        }
        return removed;
    }

    /**
     * Engine probe: whether a plugin with the given configured identifier is
     * currently loaded. A fully-qualified identifier ("name:group") is
     * resolved directly; a bare name is matched against the plugin manager's
     * loaded plugin names. Blank or unresolvable identifiers yield false.
     */
    public static boolean isPluginLoaded(String configuredId) {
        if (configuredId == null || configuredId.isBlank()) {
            return false;
        }

        try {
            if (configuredId.contains(":")) {
                return HytaleServer.get().getPluginManager().getPlugin(PluginIdentifier.fromString(configuredId)) != null;
            }
        } catch (IllegalArgumentException ignored) {
            return false;
        }

        for (PluginBase plugin : HytaleServer.get().getPluginManager().getPlugins()) {
            if (plugin != null && configuredId.equals(plugin.getIdentifier().getName())) {
                return true;
            }
        }

        return false;
    }
}
