package com.optiportal.commands;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.optiportal.OptiPortal;
import com.optiportal.model.PortalEntry;
import com.optiportal.model.WarmStrategy;
import com.optiportal.preload.ChunkPreloader;

/**
 * /preload command — uses withDefaultArg so the framework accepts zero tokens.
 *
 * A single DefaultArg<String> captures everything after "/preload " (defaulting
 * to ""). We split and dispatch manually inside execute().
 */
public class PreloadCommand extends AbstractCommand {

    private final OptiPortal plugin;

    public PreloadCommand(OptiPortal plugin) {
        super("preload", "OptiPortal management commands.");
        this.plugin = plugin;
        setAllowsExtraArguments(true);
    }

    @Override
    protected CompletableFuture<Void> execute(CommandContext context) {
        // getInputString() returns the full typed line e.g. "preload list" or "preload setwarm Berkan".
        // The first token is always the command name — skip it.
        String raw = context.getInputString();
        if (raw == null) raw = "";
        String[] tokens = raw.trim().split("\\s+");
        // tokens[0] = "preload" (command name), rest = actual args
        String[] args = tokens.length <= 1
                ? new String[0]
                : java.util.Arrays.copyOfRange(tokens, 1, tokens.length);

        if (args.length == 0) {
            // getComponent(Player) must run on the world tick thread — dispatch via world.execute()
            UUID senderUuid = context.sender().getUuid();
            if (senderUuid != null) {
                com.hypixel.hytale.server.core.universe.PlayerRef playerRef =
                        plugin.getTeleportInterceptor().getPlayerRef(senderUuid);
                if (playerRef != null) {
                    com.hypixel.hytale.server.core.universe.world.World world =
                            plugin.getChunkPreloader().getWorldRegistry().getWorldForPlayer(playerRef);
                    if (world != null) {
                        // Fetch storage data off the world thread to avoid blocking it
                        List<com.optiportal.model.PortalEntry> zones = plugin.getStorage().loadAll();
                        world.execute(() -> {
                            @SuppressWarnings("unchecked")
                            com.hypixel.hytale.server.core.entity.entities.Player player =
                                    (com.hypixel.hytale.server.core.entity.entities.Player)
                                    playerRef.getComponent(
                                            com.hypixel.hytale.server.core.entity.entities.Player.getComponentType());
                            if (player != null) {
                                com.optiportal.ui.OptiPortalUIPage.openFor(player, playerRef, plugin, zones);
                            }
                        });
                        return done();
                    }
                }
            }
            reply(context, "[OptiPortal] Use /preload help for commands.");
            return done();
        }

        return switch (args[0].toLowerCase()) {
            case "list"      -> handleList(context);
            case "strategy"  -> handleStrategy(context, args);
            case "shape"     -> handleShape(context, args);
            case "radius"    -> handleRadius(context, args);
            case "radiusxz"  -> handleRadiusXZ(context, args);
            case "activation"-> handleActivation(context, args);
            case "migrate"   -> handleMigrate(context, args);
            case "ram"       -> handleRam(context);
            case "reload"    -> handleReload(context);
            case "setwarm"   -> handleSetWarm(context, args);
            case "unsetwarm" -> handleUnsetWarm(context, args);
            case "refresh"   -> handleRefresh(context, args);
            case "backup"    -> handleBackup(context, args);
            case "preload"   -> handleForcePreload(context, args);
            case "status"    -> handleStatus(context);
            case "links"     -> handleLinks(context, args);
            case "ttl"       -> handleTtl(context, args);
            case "zone"      -> handleZone(context, args);
            case "delete"    -> handleDelete(context, args);
            case "flush"     -> handleFlush(context);
            case "help"      -> handleHelp(context);
            default -> {
                reply(context, "[OptiPortal] Unknown subcommand: " + args[0] + ". Try /preload help.");
                yield done();
            }
        };
    }

    // -------------------------------------------------------------------------
    // Subcommand handlers
    // -------------------------------------------------------------------------

    private CompletableFuture<Void> handleHelp(CommandContext ctx) {
        reply(ctx, "[OptiPortal] Commands:");
        reply(ctx, "  /preload list — list all portals");
        reply(ctx, "  /preload strategy <id> <WARM|PREDICTIVE> — set strategy");
        reply(ctx, "  /preload shape <id> <ELLIPSOID|CYLINDER|BOX> — set activation shape");
        reply(ctx, "  /preload radius <id> <X> [Z] — set asymmetric radius (Z defaults to X)");
        reply(ctx, "  /preload radiusxz <id> <rx> <rz> — set asymmetric radius (deprecated, use radius)");
        reply(ctx, "  /preload activation <id> <distance> — set per-zone horizontal activation distance");
        reply(ctx, "  /preload activation <id> reset — reset to global default");
        reply(ctx, "  /preload setwarm <id> [radius] — set WARM and preload now");
        reply(ctx, "  /preload unsetwarm <id> — revert to PREDICTIVE");
        reply(ctx, "  /preload preload <id> — force predictive preload");
        reply(ctx, "  /preload ram — RAM usage summary");
        reply(ctx, "  /preload refresh warps — re-read warps.json");
        reply(ctx, "  /preload reload — hot-reload config.json (see output for what takes effect immediately)");
        reply(ctx, "  /preload migrate <JSON|SQLITE|H2|MYSQL> — migration instructions");
        reply(ctx, "  /preload backup <list|restore <date>> — WAL backups");
        reply(ctx, "  /preload status — async infrastructure health (circuit breaker, TPS, zone tiers)");
        reply(ctx, "  /preload links [remove <id>|clear-pending] — list or manage portal links");
        reply(ctx, "  /preload ttl <id> <days|-1|reset> — set per-zone TTL override");
        reply(ctx, "  /preload zone <id> — per-zone diagnostic information");
        reply(ctx, "  /preload delete <id> — delete zone with full cleanup");
        reply(ctx, "  /preload flush — force-write all zone entries to storage");
        return done();
    }

    private CompletableFuture<Void> handleList(CommandContext ctx) {
        List<PortalEntry> all = plugin.getStorage().loadAll();
        if (all.isEmpty()) {
            reply(ctx, "[OptiPortal] No portal entries found.");
            return done();
        }
        reply(ctx, "[OptiPortal] Portals (" + all.size() + "):");
        for (PortalEntry entry : all) {
            com.optiportal.model.CacheTier storedTier = plugin.getCacheManager().getZoneTier(entry.getId());
            String tierLabel = switch (storedTier) {
                case HOT -> "HOT";
                case WARM -> "WARM";
                case COLD -> "COLD";
                case REBUILDING -> "REBUILDING";
                default -> "UNVISITED";
            };
            int radius = entry.getWarmRadius() < 0
                    ? plugin.getPluginConfig().getDefaultWarmRadius()
                    : entry.getWarmRadius();
            reply(ctx, String.format("  %s | %s | world=%s | r=%d | tier=%s | est=%.0fMB",
                    entry.getId(), entry.getStrategy(), entry.getWorld(), radius,
                    tierLabel,
                    entry.getRamEstimatedMB()));
        }
        return done();
    }

    private CompletableFuture<Void> handleShape(CommandContext ctx, String[] args) {
        if (args.length < 3) { reply(ctx, "Usage: /preload shape <id> <ELLIPSOID|CYLINDER|BOX>"); return done(); }
        String id = args[1];
        String shape = args[2].toUpperCase();
        if (!shape.equals("ELLIPSOID") && !shape.equals("CYLINDER") && !shape.equals("BOX")) {
            reply(ctx, "[OptiPortal] Invalid shape. Use ELLIPSOID, CYLINDER, or BOX."); return done();
        }
        return plugin.getStorage().loadById(id).map(entry -> {
            entry.setActivationShape(shape);
            plugin.getStorage().save(entry);
            reply(ctx, "[OptiPortal] " + id + " shape → " + shape + ".");
            return CompletableFuture.<Void>completedFuture(null);
        }).orElseGet(() -> { reply(ctx, "[OptiPortal] Portal '" + id + "' not found."); return done(); });
    }

    private CompletableFuture<Void> handleStrategy(CommandContext ctx, String[] args) {
        if (args.length < 3) { reply(ctx, "Usage: /preload strategy <id> <WARM|PREDICTIVE>"); return done(); }
        WarmStrategy strategy;
        try { strategy = WarmStrategy.valueOf(args[2].toUpperCase()); }
        catch (IllegalArgumentException e) { reply(ctx, "[OptiPortal] Invalid strategy. Use WARM or PREDICTIVE."); return done(); }
        String id = args[1];
        return plugin.getStorage().loadById(id).map(entry -> {
            entry.setStrategy(strategy);
            plugin.getStorage().save(entry);
            reply(ctx, "[OptiPortal] " + id + " strategy → " + strategy + ".");
            return CompletableFuture.<Void>completedFuture(null);
        }).orElseGet(() -> { reply(ctx, "[OptiPortal] Portal '" + id + "' not found."); return done(); });
    }

    private CompletableFuture<Void> handleRadius(CommandContext ctx, String[] args) {
        if (args.length < 3) { reply(ctx, "Usage: /preload radius <id> <X> [Z]"); return done(); }
        String id = args[1];
        int rx, rz;
        try {
            rx = Integer.parseInt(args[2]);
            rz = (args.length >= 4) ? Integer.parseInt(args[3]) : rx; // Z defaults to X for backward compatibility
        }
        catch (NumberFormatException e) { reply(ctx, "[OptiPortal] Radii must be integers."); return done(); }
        return plugin.getStorage().loadById(id).map(entry -> {
            entry.setWarmRadius(-1);
            entry.setWarmRadiusX(rx);
            entry.setWarmRadiusZ(rz);
            plugin.getStorage().save(entry);
            reply(ctx, "[OptiPortal] " + id + " radius → " + rx + "x" + rz + ".");
            return CompletableFuture.<Void>completedFuture(null);
        }).orElseGet(() -> { reply(ctx, "[OptiPortal] Portal '" + id + "' not found."); return done(); });
    }

    private CompletableFuture<Void> handleRadiusXZ(CommandContext ctx, String[] args) {
        if (args.length < 4) { reply(ctx, "Usage: /preload radiusxz <id> <radiusX> <radiusZ>"); return done(); }
        String id = args[1];
        int rx, rz;
        try { rx = Integer.parseInt(args[2]); rz = Integer.parseInt(args[3]); }
        catch (NumberFormatException e) { reply(ctx, "[OptiPortal] Radii must be integers."); return done(); }
        return plugin.getStorage().loadById(id).map(entry -> {
            entry.setWarmRadius(-1);
            entry.setWarmRadiusX(rx);
            entry.setWarmRadiusZ(rz);
            plugin.getStorage().save(entry);
            reply(ctx, "[OptiPortal] " + id + " radius → " + rx + "x" + rz + " (deprecated: use /preload radius).");
            return CompletableFuture.<Void>completedFuture(null);
        }).orElseGet(() -> { reply(ctx, "[OptiPortal] Portal '" + id + "' not found."); return done(); });
    }

    private CompletableFuture<Void> handleRam(CommandContext ctx) {
        List<PortalEntry> all = plugin.getStorage().loadAll();
        double total = plugin.getCacheManager().getTotalSharedChunks() * 0.25;
        reply(ctx, String.format("[OptiPortal] Est. warm cache: %.1f MB (%d zones)", total, all.size()));
        for (PortalEntry entry : all) {
            if (entry.getRamEstimatedMB() > 0) {
                reply(ctx, String.format("  %s: est=%.1fMB marginal=%.1fMB",
                        entry.getId(), entry.getRamEstimatedMB(), entry.getRamMarginalMB()));
            }
        }
        return done();
    }

    private CompletableFuture<Void> handleReload(CommandContext ctx) {
        String summary = plugin.reloadConfig();
        reply(ctx, "[OptiPortal] " + summary);
        return done();
    }

    private CompletableFuture<Void> handleSetWarm(CommandContext ctx, String[] args) {
        if (args.length < 2) { reply(ctx, "Usage: /preload setwarm <id> [radius]"); return done(); }
        String id = args[1];
        int radius = args.length >= 3 ? parseIntOrDefault(args[2], -1) : -1;
        return plugin.getStorage().loadById(id).map(entry -> {
            entry.setStrategy(WarmStrategy.WARM);
            if (radius > 0) entry.setWarmRadius(radius);
            plugin.getStorage().save(entry);
            int effective = radius > 0 ? radius : plugin.getPluginConfig().getDefaultWarmRadius();
            int cx = ChunkPreloader.toChunkCoord(entry.getX());
            int cz = ChunkPreloader.toChunkCoord(entry.getZ());
            plugin.getChunkPreloader().warmLoad(id, entry.getWorld(), cx, cz, effective);
            reply(ctx, "[OptiPortal] " + id + " → WARM (r=" + effective + "). Loading now...");
            return CompletableFuture.<Void>completedFuture(null);
        }).orElseGet(() -> { reply(ctx, "[OptiPortal] Portal '" + id + "' not found."); return done(); });
    }

    private CompletableFuture<Void> handleUnsetWarm(CommandContext ctx, String[] args) {
        if (args.length < 2) { reply(ctx, "Usage: /preload unsetwarm <id>"); return done(); }
        String id = args[1];
        return plugin.getStorage().loadById(id).map(entry -> {
            entry.setStrategy(WarmStrategy.PREDICTIVE);
            plugin.getStorage().save(entry);
            plugin.getCacheManager().releaseZoneChunks(id);
            reply(ctx, "[OptiPortal] " + id + " → PREDICTIVE. Warm chunks released.");
            return CompletableFuture.<Void>completedFuture(null);
        }).orElseGet(() -> { reply(ctx, "[OptiPortal] Portal '" + id + "' not found."); return done(); });
    }

    private CompletableFuture<Void> handleRefresh(CommandContext ctx, String[] args) {
        if (args.length < 2 || !args[1].equalsIgnoreCase("warps")) {
            reply(ctx, "Usage: /preload refresh warps"); return done();
        }
        int count = plugin.getWarpFileWatcher().forceRefresh();
        reply(ctx, "[OptiPortal] Refreshed " + count + " warps from warps.json.");
        return done();
    }

    private CompletableFuture<Void> handleMigrate(CommandContext ctx, String[] args) {
        if (args.length < 2) { reply(ctx, "Usage: /preload migrate <JSON|SQLITE|H2|MYSQL>"); return done(); }
        String target = args[1].toUpperCase();
        if (!target.equals("JSON") && !target.equals("SQLITE") && !target.equals("H2") && !target.equals("MYSQL")) {
            reply(ctx, "[OptiPortal] Unknown backend. Use JSON, SQLITE, H2, or MYSQL."); return done();
        }
        reply(ctx, "[OptiPortal] To migrate to " + target + ": set backend in config.json, then restart.");
        return done();
    }

    private CompletableFuture<Void> handleBackup(CommandContext ctx, String[] args) {
        if (args.length < 2) { reply(ctx, "Usage: /preload backup <list|restore <date>>"); return done(); }
        switch (args[1].toLowerCase()) {
            case "list" -> {
                List<String> backups = plugin.getWalManager().listBackups();
                if (backups.isEmpty()) { reply(ctx, "[OptiPortal] No backups found."); }
                else { reply(ctx, "[OptiPortal] Backups:"); backups.forEach(b -> reply(ctx, "  " + b)); }
            }
            case "restore" -> {
                if (args.length < 3) { reply(ctx, "Usage: /preload backup restore <date>"); return done(); }
                boolean ok = plugin.getWalManager().restoreBackup(args[2]);
                reply(ctx, ok ? "[OptiPortal] Restored " + args[2] + ". Restart recommended."
                             : "[OptiPortal] Backup '" + args[2] + "' not found or restore failed.");
            }
            default -> reply(ctx, "Usage: /preload backup <list|restore <date>>");
        }
        return done();
    }

    private CompletableFuture<Void> handleForcePreload(CommandContext ctx, String[] args) {
        if (args.length < 2) { reply(ctx, "Usage: /preload preload <id>"); return done(); }
        String id = args[1];
        return plugin.getStorage().loadById(id).map(entry -> {
            int cx = ChunkPreloader.toChunkCoord(entry.getX());
            int cz = ChunkPreloader.toChunkCoord(entry.getZ());
            int radius = entry.getWarmRadius() < 0 ? plugin.getPluginConfig().getPredictiveRadius() : entry.getWarmRadius();
            plugin.getChunkPreloader().predictiveLoad(id, entry.getWorld(), cx, cz, radius);
            reply(ctx, "[OptiPortal] Force-preloading " + id + " (world=" + entry.getWorld() + " r=" + radius + ")");
            return CompletableFuture.<Void>completedFuture(null);
        }).orElseGet(() -> { reply(ctx, "[OptiPortal] Portal '" + id + "' not found."); return done(); });
    }

    private CompletableFuture<Void> handleStatus(CommandContext ctx) {
        com.optiportal.async.CircuitBreaker cb = plugin.getAsyncErrorHandler().getCircuitBreaker();
        com.optiportal.async.CircuitBreaker.CircuitBreakerStats cbStats = cb.getStats();
        com.optiportal.async.AsyncLoadBalancer.LoadStats lb = plugin.getAsyncLoadBalancer().getLoadStats();
        com.optiportal.async.WorldTpsMonitor tps = plugin.getTpsMonitor();
        java.util.Map<com.optiportal.model.CacheTier, Integer> tiers = plugin.getCacheManager().getTierCounts();
        int totalChunks = plugin.getChunkPreloader().getWorldRegistry().getTotalLoadedChunkCount();

        String tpsLabel;
        double tpsVal;
        if (tps == null) {
            tpsVal = -1;
            tpsLabel = "N/A (disabled)";
        } else {
            tpsVal = tps.getCurrentTps();
            tpsLabel = tps.isCriticallyLoaded() ? "critical"
                     : tps.isServerUnderLoad()  ? "under load"
                     : "nominal";
        }

        reply(ctx, "[OptiPortal] Status — v1.1.1");
        reply(ctx, String.format("  Circuit breaker : %s  (failures=%d)", cbStats.state, cbStats.failureCount));
        reply(ctx, String.format("  Load balancer   : active=%d queued=%d avgTime=%.0fms batchSize=%d",
                lb.activeOperations, lb.queuedTasks, lb.averageExecutionTime, lb.currentBatchSize));
        if (tpsVal < 0) {
            reply(ctx, "  TPS             : N/A (monitor disabled)");
        } else {
            reply(ctx, String.format("  TPS             : %.1f tps  (%s)", tpsVal, tpsLabel));
        }
        reply(ctx, String.format("  Server chunks   : %d loaded", totalChunks));
        reply(ctx, String.format("  Zone registry   : %d HOT  |  %d WARM  |  %d COLD  |  %d UNVISITED",
                tiers.getOrDefault(com.optiportal.model.CacheTier.HOT, 0),
                tiers.getOrDefault(com.optiportal.model.CacheTier.WARM, 0),
                tiers.getOrDefault(com.optiportal.model.CacheTier.COLD, 0),
                tiers.getOrDefault(com.optiportal.model.CacheTier.UNVISITED, 0)));
        return done();
    }

    private CompletableFuture<Void> handleLinks(CommandContext ctx, String[] args) {
        com.optiportal.preload.PortalLinkRegistry reg = plugin.getPortalLinkRegistry();
        if (reg == null) {
            reply(ctx, "[OptiPortal] Portal link registry not available.");
            return done();
        }

        if (args.length >= 2 && args[1].equalsIgnoreCase("clear-pending")) {
            int before = reg.getPendingLinkCounts().size();
            reg.clearPendingLinks();
            reply(ctx, "[OptiPortal] Cleared " + before + " pending link(s).");
            return done();
        }

        if (args.length >= 3 && args[1].equalsIgnoreCase("remove")) {
            String id = args[2];
            String linked = reg.getLinkedPortal(id);
            if (linked == null) {
                reply(ctx, "[OptiPortal] No confirmed link found for '" + id + "'.");
            } else {
                reg.removeLink(id);
                reply(ctx, "[OptiPortal] Link removed: " + id + " ↔ " + linked + ".");
            }
            return done();
        }

        // Default: list all
        java.util.Map<String, String> confirmed = reg.getLinks();
        java.util.Map<String, Integer> pending = reg.getPendingLinkCounts();

        // Deduplicate confirmed — both directions are stored, show each pair once
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        java.util.List<String> pairs = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, String> e : confirmed.entrySet()) {
            String key = e.getKey().compareTo(e.getValue()) <= 0
                    ? e.getKey() + "↔" + e.getValue()
                    : e.getValue() + "↔" + e.getKey();
            if (seen.add(key)) pairs.add(e.getKey() + " ↔ " + e.getValue());
        }

        int confirmedCount = pairs.size();
        int pendingCount   = pending.size();
        reply(ctx, String.format("[OptiPortal] Portal links (%d confirmed, %d pending):", confirmedCount, pendingCount));
        if (!pairs.isEmpty()) {
            reply(ctx, "  Confirmed:");
            for (String pair : pairs) reply(ctx, "    " + pair);
        }
        if (!pending.isEmpty()) {
            reply(ctx, "  Pending (threshold: " + plugin.getPluginConfig().getPortalLinksConfidenceThreshold() + "):");
            for (java.util.Map.Entry<String, Integer> e : pending.entrySet()) {
                String[] parts = e.getKey().split(":", 2);
                String display = parts.length == 2 ? parts[0] + " → " + parts[1] : e.getKey();
                reply(ctx, "    " + display + "  (" + e.getValue() + "/"
                        + plugin.getPluginConfig().getPortalLinksConfidenceThreshold() + " observations)");
            }
        }
        return done();
    }

    private CompletableFuture<Void> handleTtl(CommandContext ctx, String[] args) {
        if (args.length < 3) {
            reply(ctx, "Usage: /preload ttl <id> <days|-1|reset>");
            return done();
        }
        String id  = args[1];
        String val = args[2];
        return plugin.getStorage().loadById(id).map(entry -> {
            if (val.equalsIgnoreCase("reset")) {
                entry.setCacheTTLDays(null);
                plugin.getStorage().save(entry);
                reply(ctx, "[OptiPortal] " + id + " TTL cleared (uses global default).");
            } else {
                int days;
                try { days = Integer.parseInt(val); }
                catch (NumberFormatException e) {
                    reply(ctx, "[OptiPortal] Days must be an integer or 'reset'.");
                    return CompletableFuture.<Void>completedFuture(null);
                }
                entry.setCacheTTLDays(days == -1 ? null : days);
                plugin.getStorage().save(entry);
                reply(ctx, days == -1
                        ? "[OptiPortal] " + id + " TTL → never expire (-1)."
                        : "[OptiPortal] " + id + " TTL → " + days + " day(s).");
            }
            return CompletableFuture.<Void>completedFuture(null);
        }).orElseGet(() -> {
            reply(ctx, "[OptiPortal] Portal '" + id + "' not found.");
            return done();
        });
    }

    // F1 — /preload activation <id> <distance> | <id> reset
    private CompletableFuture<Void> handleActivation(CommandContext ctx, String[] args) {
        if (args.length < 3) {
            reply(ctx, "Usage: /preload activation <id> <distance> | <id> reset");
            return done();
        }
        String id = args[1];
        
        return plugin.getStorage().loadById(id).map(entry -> {
            if (args.length >= 3 && !args[2].equalsIgnoreCase("reset")) {
                // Set horizontal activation distance
                double distance;
                try {
                    distance = Double.parseDouble(args[2]);
                } catch (NumberFormatException e) {
                    reply(ctx, "[OptiPortal] Distance must be a number.");
                    return CompletableFuture.<Void>completedFuture(null);
                }
                entry.setActivationDistanceHorizontal(distance);
                plugin.getStorage().save(entry);
                reply(ctx, "[OptiPortal] " + id + " horizontal activation distance → " + distance);
            } else {
                // Reset to global default
                entry.setActivationDistanceHorizontal(null);
                plugin.getStorage().save(entry);
                reply(ctx, "[OptiPortal] " + id + " horizontal activation distance reset (uses global default).");
            }
            return CompletableFuture.<Void>completedFuture(null);
        }).orElseGet(() -> {
            reply(ctx, "[OptiPortal] Portal '" + id + "' not found.");
            return done();
        });
    }

    // F2 — /preload zone <id> — diagnostic command
    private CompletableFuture<Void> handleZone(CommandContext ctx, String[] args) {
        if (args.length < 2) {
            reply(ctx, "Usage: /preload zone <id>");
            return done();
        }
        String id = args[1];
        
        return plugin.getStorage().loadById(id).map(entry -> {
            com.optiportal.model.CacheTier tier = plugin.getCacheManager().getZoneTier(id);
            long tierAgeMs = plugin.getCacheManager().getZoneTierAgeMs(id);
            int ownedChunks = plugin.getCacheManager().getOwnedChunkCount(id);
            
            reply(ctx, "[OptiPortal] Zone diagnostics for '" + id + "':");
            reply(ctx, "  Strategy: " + entry.getStrategy());
            reply(ctx, "  World: " + entry.getWorld());
            reply(ctx, "  Position: (" + String.format("%.2f", entry.getX()) + ", "
                    + String.format("%.2f", entry.getY()) + ", " + String.format("%.2f", entry.getZ()) + ")");
            reply(ctx, "  Warm radius: " + (entry.getWarmRadius() >= 0 ? entry.getWarmRadius() : "default"));
            reply(ctx, "  Warm radius X/Z: " + (entry.getWarmRadiusX() != null ? entry.getWarmRadiusX() + "x" + entry.getWarmRadiusZ() : "N/A"));
            reply(ctx, "  Activation shape: " + (entry.getActivationShape() != null ? entry.getActivationShape() : "global"));
            reply(ctx, "  Activation distance (H): " + (entry.getActivationDistanceHorizontal() != null ? entry.getActivationDistanceHorizontal() : "global"));
            reply(ctx, "  Activation distance (V): " + (entry.getActivationDistanceVertical() != null ? entry.getActivationDistanceVertical() : "global"));
            reply(ctx, "  Cache tier: " + tier);
            reply(ctx, "  Tier age: " + (tierAgeMs > 0 ? String.format("%.1fs", tierAgeMs / 1000.0) : "N/A"));
            reply(ctx, "  Owned chunks: " + ownedChunks);
            reply(ctx, "  RAM est: " + String.format("%.1fMB", entry.getRamEstimatedMB()));
            reply(ctx, "  Preload count: " + entry.getPreloadCount());
            reply(ctx, "  Last active: " + (entry.getLastActive() != null ? entry.getLastActive() : "N/A"));
            reply(ctx, "  Entry type: " + entry.getType());
            return CompletableFuture.<Void>completedFuture(null);
        }).orElseGet(() -> {
            reply(ctx, "[OptiPortal] Portal '" + id + "' not found.");
            return done();
        });
    }

    private CompletableFuture<Void> handleFlush(CommandContext ctx) {
        List<PortalEntry> all = plugin.getStorage().loadAll();
        com.optiportal.config.PluginConfig cfg = plugin.getPluginConfig();
        for (PortalEntry entry : all) {
            int fallback = entry.getStrategy() == WarmStrategy.WARM
                    ? cfg.getDefaultWarmRadius() : cfg.getPredictiveRadius();
            int rx = entry.getWarmRadiusX() != null ? entry.getWarmRadiusX()
                   : entry.getWarmRadius() > 0      ? entry.getWarmRadius()
                   : fallback;
            int rz = entry.getWarmRadiusZ() != null ? entry.getWarmRadiusZ()
                   : entry.getWarmRadius() > 0      ? entry.getWarmRadius()
                   : fallback;
            int chunkCount = (2 * rx + 1) * (2 * rz + 1);
            plugin.getChunkPreloader().updateEntryStats(entry, chunkCount);
        }
        plugin.getStorage().saveAll(all);
        reply(ctx, "[OptiPortal] Flushed " + all.size() + " zone(s) to "
                + plugin.getPluginConfig().getBackend().toUpperCase()
                + " with recalculated RAM values.");
        return done();
    }

    // F3 — /preload delete <id> — proper zone deletion with full cleanup
    private CompletableFuture<Void> handleDelete(CommandContext ctx, String[] args) {
        if (args.length < 2) {
            reply(ctx, "Usage: /preload delete <id>");
            return done();
        }
        String id = args[1];
        
        return plugin.getStorage().loadById(id).map(entry -> {
            String world = entry.getWorld();

            // Step 1: Release keep-loaded pins and deregister chunk ownership
            plugin.getCacheManager().deregisterAllChunks(id);

            // Step 2: Remove tier and timestamp entries
            plugin.getCacheManager().removeTierEntry(id);

            // Step 3: Remove from portal chunk listener reverse index
            var portalChunkListener = plugin.getPortalChunkListener();
            if (portalChunkListener != null) {
                portalChunkListener.removeFromIndex(id);
            }

            // Step 4: Remove any confirmed or pending portal links
            plugin.getPortalLinkRegistry().removeLink(id);

            // Step 5: Delete from storage
            plugin.getStorage().delete(id);

            // Step 6: Invalidate in-memory portal cache
            plugin.getTeleportInterceptor().refreshPortalCache();

            reply(ctx, "[OptiPortal] Deleted zone '" + id + "' (world=" + world + ").");
            return CompletableFuture.<Void>completedFuture(null);
        }).orElseGet(() -> {
            reply(ctx, "[OptiPortal] Portal '" + id + "' not found.");
            return done();
        });
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    private void reply(CommandContext ctx, String text) {
        ctx.sender().sendMessage(Message.translation(text));
    }

    private CompletableFuture<Void> done() {
        return CompletableFuture.completedFuture(null);
    }

    private int parseIntOrDefault(String s, int def) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return def; }
    }
}