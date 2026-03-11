package com.optiportal.commands;

import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.Message;
import com.optiportal.OptiPortal;
import com.optiportal.model.PortalEntry;
import com.optiportal.model.WarmStrategy;
import com.optiportal.preload.ChunkPreloader;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

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
                        world.execute(() -> {
                            @SuppressWarnings("unchecked")
                            com.hypixel.hytale.server.core.entity.entities.Player player =
                                    (com.hypixel.hytale.server.core.entity.entities.Player)
                                    playerRef.getComponent(
                                            com.hypixel.hytale.server.core.entity.entities.Player.getComponentType());
                            if (player != null) {
                                com.optiportal.ui.PreloadUIPage.openFor(player, playerRef, plugin);
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
            case "radius"    -> handleRadius(context, args);
            case "radiusxz"  -> handleRadiusXZ(context, args);
            case "migrate"   -> handleMigrate(context, args);
            case "ram"       -> handleRam(context);
            case "reload"    -> handleReload(context);
            case "setwarm"   -> handleSetWarm(context, args);
            case "unsetwarm" -> handleUnsetWarm(context, args);
            case "stats"     -> handleStats(context);
            case "refresh"   -> handleRefresh(context, args);
            case "backup"    -> handleBackup(context, args);
            case "preload"   -> handleForcePreload(context, args);
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
        reply(ctx, "  /preload radius <id> <n> — set uniform radius");
        reply(ctx, "  /preload radiusxz <id> <rx> <rz> — set asymmetric radius");
        reply(ctx, "  /preload setwarm <id> [radius] — set WARM and preload now");
        reply(ctx, "  /preload unsetwarm <id> — revert to PREDICTIVE");
        reply(ctx, "  /preload preload <id> — force predictive preload");
        reply(ctx, "  /preload ram — RAM usage summary");
        reply(ctx, "  /preload stats — metrics summary");
        reply(ctx, "  /preload refresh warps — re-read warps.json");
        reply(ctx, "  /preload reload — hot-reload config.json (see output for what takes effect immediately)");
        reply(ctx, "  /preload migrate <JSON|SQLITE|H2|MYSQL> — migration instructions");
        reply(ctx, "  /preload backup <list|restore <date>> — WAL backups");
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
        if (args.length < 3) { reply(ctx, "Usage: /preload radius <id> <n>"); return done(); }
        String id = args[1];
        int radius;
        try { radius = Integer.parseInt(args[2]); }
        catch (NumberFormatException e) { reply(ctx, "[OptiPortal] Radius must be an integer."); return done(); }
        return plugin.getStorage().loadById(id).map(entry -> {
            entry.setWarmRadius(radius);
            entry.setWarmRadiusX(null);
            entry.setWarmRadiusZ(null);
            plugin.getStorage().save(entry);
            reply(ctx, "[OptiPortal] " + id + " radius → " + radius + ".");
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
            reply(ctx, "[OptiPortal] " + id + " radius → " + rx + "x" + rz + ".");
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
            plugin.getChunkPreloader().warmLoad(entry.getWorld(), cx, cz, effective);
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

    private CompletableFuture<Void> handleStats(CommandContext ctx) {
        reply(ctx, plugin.getMetricsCollector().getSummary());
        return done();
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
            plugin.getChunkPreloader().predictiveLoad(entry.getWorld(), cx, cz, radius);
            reply(ctx, "[OptiPortal] Force-preloading " + id + " (world=" + entry.getWorld() + " r=" + radius + ")");
            return CompletableFuture.<Void>completedFuture(null);
        }).orElseGet(() -> { reply(ctx, "[OptiPortal] Portal '" + id + "' not found."); return done(); });
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