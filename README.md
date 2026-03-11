# OptiPortal
Hytale server optimization plugin that eliminates chunk loading lag on portal travel. Pre-loads destination chunks before teleports occur using a self-learning portal link registry and three-tier cache system (HOT/WARM/COLD). Supports JSON, SQLite, H2, and MySQL storage backends.

# OptiPortal

Hytale server optimization plugin that eliminates chunk loading lag on portal travel. Pre-loads destination chunks before teleports occur using a self-learning portal link registry and three-tier cache system (HOT/WARM/COLD). Supports JSON, SQLite, H2, and MySQL storage backends.

---

## Commands

All commands use the `/preload` prefix.

### Portal Management

| Command | Description |
|---|---|
| `/preload list` | List all portals with their current tier, strategy, radius, and estimated RAM usage |
| `/preload strategy <id> <WARM\|PREDICTIVE>` | Set the preload strategy for a portal |
| `/preload radius <id> <n>` | Set a uniform preload radius (in chunks) for a portal |
| `/preload radiusxz <id> <rx> <rz>` | Set an asymmetric preload radius (different X and Z extents) |
| `/preload setwarm <id> [radius]` | Force a portal to WARM strategy and trigger an immediate preload |
| `/preload unsetwarm <id>` | Revert a portal to PREDICTIVE strategy and release its warm chunks |
| `/preload preload <id>` | Force an immediate predictive preload for a portal |

### Diagnostics

| Command | Description |
|---|---|
| `/preload ram` | Show estimated RAM usage across all warm zones |
| `/preload stats` | Show plugin metrics summary |

### Maintenance

| Command | Description |
|---|---|
| `/preload refresh warps` | Re-read `warps.json` immediately without waiting for the file watcher |
| `/preload reload` | Hot-reload `config.json` without a server restart (see below for what applies immediately) |
| `/preload migrate <JSON\|SQLITE\|H2\|MYSQL>` | Instructions for switching storage backend |
| `/preload backup list` | List available WAL backups |
| `/preload backup restore <date>` | Restore a WAL backup by date (restart recommended after) |
| `/preload help` | Print command list in chat |

---

## Hot Reload — `/preload reload`

Edit `config.json` while the server is running, then run `/preload reload` to apply changes without a restart.

### ✅ Takes effect immediately

| Setting | Notes |
|---|---|
| `decay.hotDecaySeconds` | Applied on next decay poll tick |
| `decay.warmDecayMinutes` | Applied on next decay poll tick |
| `decay.pollIntervalSeconds` | Decay manager picks up on next cycle |
| `keepalive.*` (all flags and intervals) | Tasks are cancelled and rescheduled |
| `cache.snapshotIntervalMinutes` | Snapshot task is cancelled and rescheduled |
| `activation.*` (distance, shape, cooldown, etc.) | Read fresh on next portal approach |
| `ttl.*` (all TTL values) | Applied to newly evaluated entries |
| `suppressRamWarnings` | Immediate |
| `lowTrafficThreshold` | Applied on next traffic check |
| `defaults.*` (strategy, radius, buffers, timeout) | Applied to new zone evaluations |
| `warps.sourcePath` / `warps.watchIntervalSeconds` | Watcher is fully restarted |
| `gravestones.watchIntervalSeconds` | Picked up on next watcher cycle |
| `ui.*` (showBedSpawns, showDeathLocations, etc.) | Applied on next UI open |
| `metrics.bstatsEnabled` | Takes effect on next reporting interval |

### ❌ Requires a full server restart

| Setting | Why |
|---|---|
| `backend` | Storage backend is wired at startup — use `/preload migrate` to switch |
| `startupLoadStrategy` | Only read during boot |
| `rebuildFromChunksOnCorruption` | Only evaluated at startup |
| `scheduledRebuildIntervalHours` | Scheduled once at boot |
| `mysql.*` | Connection pool is created once and cannot be re-initialised at runtime |
| `cache.cacheDirectory` | Cache files are already open against the original path |
| `immuneToSimulationReduction` | Applied to worlds at registration time |
| `updateChecker.enabled` | Runs once at startup |

### Notes

- The reload command prints a one-line summary in chat confirming what was applied.
- If the config file has a JSON syntax error, the reload is aborted and existing values are kept — the server will log the parse error.
- Warp zone data itself (the entries in `warps.json`) is separate from `config.json` — use `/preload refresh warps` or rely on the file watcher for those.
- Portal link pairs (stored in `portal-links.json`) are learned automatically at runtime and do not require a reload or restart to update. Links are overwritten on each observed teleport and persist across restarts.

---

## Portal Link Registry

OptiPortal automatically learns bidirectional portal pairs at runtime. When a player teleports through a portal, the origin and destination are recorded and saved to `portal-links.json`. From that point on, approaching either end of the pair will trigger a preload of the other end.

Links are self-correcting — if a portal destination is changed in-game, the next teleport through it will overwrite the old entry automatically. To reset all learned links, delete `portal-links.json` and let the registry rebuild from player usage.

---

## Storage Backends

| Backend | Config value | Notes |
|---|---|---|
| JSON | `JSON` | Default. Zero dependencies, human-readable |
| SQLite | `SQLITE` | Single-file database, good for most servers |
| H2 | `H2` | Embedded database with stronger consistency guarantees |
| MySQL | `MYSQL` | For shared/remote database setups |

To switch backends, update `backend` in `config.json` and restart. Use `/preload migrate <backend>` for instructions.
