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

## Enhanced Predictive Loading

OptiPortal now supports enhanced predictive loading that leverages HytaleServer's field functions for more intelligent chunk prioritization based on terrain density. This enhancement provides better performance by pre-loading chunks in a more strategic order.

### New API Methods

The plugin exposes new async methods for enhanced predictive loading:

- `asyncEnhancedPredictiveLoad(String worldName, double x, double z, int radius)` - Asynchronously triggers predictive loading with enhanced prioritization based on terrain density.

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

### Backend Details

**JSON Backend**
- Stores data in a single `portal-data.json` file
- Human-readable format that can be edited manually
- Uses WAL-safe write pattern with atomic renames to prevent corruption
- Includes automatic backup recovery on load failure

**SQLite Backend**
- Uses SQLite database file (`portal-data.db`)
- Built-in WAL (Write-Ahead Logging) mode for improved concurrency
- Synchronous setting set to "NORMAL" for good balance of performance and durability
- Supports large datasets efficiently

**H2 Backend**
- Embedded H2 database with full SQL support
- No external dependencies beyond the JDBC driver
- Provides stronger consistency guarantees than SQLite
- Uses file-based storage without requiring a separate server process

**MySQL Backend**
- Requires a separate MySQL/MariaDB server
- Supports shared database setups for multi-server environments
- Uses connection pooling via HikariCP for efficient resource management
- Allows for advanced database features and monitoring
