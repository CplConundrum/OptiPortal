# OptiPortal

Hytale server optimization plugin that eliminates chunk loading lag on portal travel. Pre-loads destination chunks before teleports occur using a self-learning portal link registry and three-tier cache system (HOT/WARM/COLD). Supports JSON, SQLite, H2, and MySQL storage backends.

---
## Test System

`CPU: E5-1650v4`  
`RAM: 48GB`  
`Storage: NVMe`  
`OS: Proxmox LXC/ubuntu`

---

## Commands

All commands use the `/preload` prefix. Running `/preload` with no arguments opens the native in-game UI panel (zone table, stats bar, and command reference).

### Portal Management

| Command | Description |
|---|---|
| `/preload list` | List all portals with their current tier, strategy, radius, and estimated RAM usage |
| `/preload strategy <id> <WARM\|PREDICTIVE>` | Set the preload strategy for a portal |
| `/preload shape <id> <ELLIPSOID\|CYLINDER\|BOX>` | Set the activation shape for a portal (overrides global config) |
| `/preload radius <id> <n>` | Set a uniform preload radius (in chunks) for a portal |
| `/preload radiusxz <id> <rx> <rz>` | Set an asymmetric preload radius (different X and Z extents) |
| `/preload setwarm <id> [radius]` | Force a portal to WARM strategy and trigger an immediate preload |
| `/preload unsetwarm <id>` | Revert a portal to PREDICTIVE strategy and release its warm chunks |
| `/preload preload <id>` | Force an immediate predictive preload for a portal |

### Diagnostics

| Command | Description |
|---|---|
| `/preload ram` | Show estimated RAM usage across all warm zones |

### Maintenance

| Command | Description |
|---|---|
| `/preload refresh warps` | Re-read `warps.json` immediately without waiting for the file watcher |
| `/preload reload` | Hot-reload `config.json` without a server restart (see below for what applies immediately) |
| `/preload flush` | Recalculate and persist RAM estimates for all zones in the storage backend |
| `/preload migrate <JSON\|SQLITE\|H2\|MYSQL>` | Instructions for switching storage backend |
| `/preload backup list` | List available WAL backups |
| `/preload backup restore <date>` | Restore a WAL backup by date (restart recommended after) |
| `/preload help` | Print command list in chat |

---

## Hot Reload — `/preload reload`

Edit `config.json` while the server is running, then run `/preload reload` to apply changes without a restart.

### Takes effect immediately

| Setting | Notes |
|---|---|
| `decay.hotDecaySeconds` | Applied on next decay poll tick |
| `decay.warmDecayMinutes` | Applied on next decay poll tick |
| `decay.pollIntervalSeconds` | Decay manager picks up on next cycle |
| `keepalive.*` (all flags and intervals) | Tasks are cancelled and rescheduled |
| `cache.snapshotIntervalMinutes` | Snapshot task is cancelled and rescheduled |
| `cache.persistColdCache` | Immediate |
| `cache.ownershipAuditIntervalMinutes` | Audit task is rescheduled |
| `activation.*` (distance, shape, cooldown, velocity, etc.) | Read fresh on next portal approach |
| `ttl.*` (all TTL values) | Applied to newly evaluated entries |
| `suppressRamWarnings` | Immediate |
| `lowTrafficThreshold` | Applied on next traffic check |
| `defaults.*` (strategy, radius, buffers, timeout) | Applied to new zone evaluations |
| `warps.sourcePath` / `warps.watchIntervalSeconds` | Watcher is fully restarted |
| `gravestones.watchIntervalSeconds` | Picked up on next watcher cycle |
| `async.tpsMonitorEnabled` / `async.tps*` thresholds | Applied on next monitoring cycle |
| `async.maxLoadedChunksPressureThreshold` | Applied on next pressure check |
| `densityBased.corridor.*` | Applied to next predictive load |
| `integrations.gravestone.releaseOnBreak` / `releaseOnEmpty` | Immediate |
| `ui.*` visibility toggles | Immediate |
| `metrics.bstatsEnabled` | Takes effect on next reporting interval |

### Requires a full server restart

| Setting | Why |
|---|---|
| `backend` | Storage backend is wired at startup — use `/preload migrate` to switch |
| `startupLoadStrategy` | Only read during boot |
| `rebuildFromChunksOnCorruption` | Only evaluated at startup |
| `scheduledRebuildIntervalHours` | Scheduled once at boot |
| `mysql.*` | Connection pool is created once and cannot be re-initialised at runtime |
| `cache.cacheDirectory` | Cache files are already open against the original path |
| `cache.maxCacheAgeDays` | Only evaluated at startup |
| `immuneToSimulationReduction` | Applied to worlds at registration time |
| `updateChecker.enabled` | Runs once at startup |
| `integrations.gravestone.pluginId` | Resolved at plugin registration time |
| `metrics.bstatsPluginId` | bStats client is created once at startup |

### Notes

- The reload command prints a one-line summary in chat confirming what was applied.
- If the config file has a JSON syntax error, the reload is aborted and existing values are kept — the server will log the parse error.
- Warp zone data itself (the entries in `warps.json`) is separate from `config.json` — use `/preload refresh warps` or rely on the file watcher for those.
- Portal link pairs (stored in `portal-links.json`) are learned automatically at runtime and do not require a reload or restart to update.

---

## Three-Tier Cache System

| Tier | Description |
|---|---|
| **HOT** | Chunk geometry cached in memory, no entity ticking. Kept alive by the keepalive system. |
| **WARM** | Chunks held in memory for high-traffic portals, either permanently or for a configured interval. |
| **COLD** | Chunks evicted from memory but persisted to disk. Reloaded on next portal approach. |

Zones decay automatically through the tiers based on inactivity. The keepalive system can hold any tier in place indefinitely.

---

## Portal Link Registry

OptiPortal automatically learns bidirectional portal pairs at runtime. When a player teleports through a portal, the origin and destination are recorded and saved to `portal-links.json`. From that point on, approaching either end of the pair will trigger a preload of the other end.

Links are self-correcting — if a portal destination is changed in-game, the next teleport through it will overwrite the old entry automatically. To reset all learned links, delete `portal-links.json` and let the registry rebuild from player usage.

---

## Enhanced Predictive Loading

OptiPortal uses terrain-density-aware chunk prioritization to load chunks in the most impactful order:

- **Priority ring loading** — Inner ring chunks (radius ≤ 2) are loaded first and gate the completion future; outer rings load best-effort in the background.
- **Corridor prioritization** — Chunks near WorldPath waypoints are sorted ahead of others during predictive loads, reducing pop-in on frequently-travelled routes.
- **Velocity-aware radius boost** — An extra chunk ring is preloaded when a player enters a portal zone while moving above a configurable speed threshold.
- **Chunk complexity scoring** — Per-chunk RAM estimates are calculated based on terrain density for more accurate usage reporting.

### API Methods

The plugin exposes async methods for programmatic access:

- `asyncEnhancedPredictiveLoad(String worldName, double x, double z, int radius)` — Asynchronously triggers predictive loading with terrain-density-based prioritization.
- `predictiveLoad(String zoneId, String worldName, int cx, int cz, int radius)` — Enhanced async predictive load with load balancing and world thread isolation.
- `warmLoad(String zoneId, String worldName, int cx, int cz, int radius)` — Enhanced warm load with batched async operations.

---

## Server Load Sensing

OptiPortal monitors server health and adapts automatically:

- **TPS monitoring** — Tracks the world thread tick rate. Below `tpsLowThreshold` (default 15.0 TPS), batch sizes are reduced. Below `tpsCriticalThreshold` (default 12.0 TPS), non-critical operations are queued.
- **Loaded chunk pressure** — Monitors `ChunkStore.getLoadedChunksCount()` and proportionally reduces batch sizes when the count exceeds `maxLoadedChunksPressureThreshold` (default 2000, set to -1 to disable).
- **Circuit breaker** — Automatically backs off when the world thread failure rate exceeds a threshold, cycling through CLOSED → OPEN → HALF_OPEN states to prevent cascading overload.

---

## Warp Integration

OptiPortal reads `warps.json` at startup and registers all warp points as preload zones. The file watcher automatically picks up additions, removals, and changes without a reload. Use `/preload refresh warps` to force an immediate re-read.

JSON field names are fully configurable if your `warps.json` uses different property names (see `warps.*` config options).

---

## Gravestone Integration

When enabled, OptiPortal listens to Gravestones plugin events and preloads chunks around death locations before the player retrieves their gravestone.

- Caches death locations with a configurable TTL
- Automatically releases cache when a gravestone is broken or emptied (configurable)
- Pre-loads respawn point chunks on the player's next login

Enable with `integrations.gravestone.enabled: true` in `config.json`.

---

## Storage Backends

| Backend | Config value | Notes |
|---|---|---|
| JSON | `JSON` | Default. Zero dependencies, human-readable |
| SQLite | `SQLITE` | Single-file database, good for most servers |
| H2 | `H2` | Embedded database with stronger consistency guarantees |
| MySQL | `MYSQL` | For shared/remote database setups |

To switch backends, update `backend` in `config.json` and restart. Use `/preload migrate <backend>` for step-by-step instructions.

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

---

## Configuration Reference

### Storage
| Key | Default | Hot-reload | Notes |
|---|---|---|---|
| `backend` | `JSON` | No | `JSON`, `SQLITE`, `H2`, or `MYSQL` |
| `bytesPerChunk` | `98304` | No | Estimated memory per loaded chunk in bytes (96 KB). Used to calculate ACTUAL RAM in the zone table. |

### Startup
| Key | Default | Hot-reload | Notes |
|---|---|---|---|
| `startupLoadStrategy` | `STAGED` | No | `STAGED` or `IMMEDIATE` |
| `suppressRamWarnings` | `false` | Yes | |
| `rebuildFromChunksOnCorruption` | `true` | No | |
| `scheduledRebuildIntervalHours` | `24` | No | `0` to disable |
| `immuneToSimulationReduction` | `true` | No | |
| `updateChecker.enabled` | `true` | No | |

### Zone Defaults
| Key | Default | Hot-reload | Notes |
|---|---|---|---|
| `defaults.strategy` | `PREDICTIVE` | Yes | `WARM` or `PREDICTIVE` |
| `defaults.warmRadius` | `4` | Yes | Chunks |
| `defaults.warmRadiusX` | `null` | Yes | Overrides X axis only |
| `defaults.warmRadiusZ` | `null` | Yes | Overrides Z axis only |
| `defaults.timeoutSeconds` | `5` | Yes | |
| `defaults.bufferSeconds.WARM` | `60` | Yes | |
| `defaults.bufferSeconds.PREDICTIVE` | `20` | Yes | |

### Activation
| Key | Default | Hot-reload | Notes |
|---|---|---|---|
| `activation.distance` | `10` | Yes | Blocks |
| `activation.distanceVertical` | `3` | Yes | Blocks |
| `activation.shape` | `ELLIPSOID` | Yes | `ELLIPSOID`, `CYLINDER`, or `BOX` |
| `activation.floorCeilingCheck` | `true` | Yes | |
| `activation.facingCheck` | `true` | Yes | |
| `activation.cooldownSeconds` | `30` | Yes | |
| `activation.commitWindowSeconds` | `3` | Yes | |
| `activation.predictiveRadius` | `7` | Yes | Radius in chunks for predictive pre-loading on player approach |
| `activation.velocityAwareActivation` | `true` | Yes | |
| `activation.velocityRadiusBoostThreshold` | `0.5` | Yes | Blocks/tick |

### Cache
| Key | Default | Hot-reload | Notes |
|---|---|---|---|
| `cache.persistColdCache` | `true` | Yes | |
| `cache.cacheDirectory` | `preload-cache/` | No | Relative to plugin dir |
| `cache.maxCacheAgeDays` | `7` | No | Files older than this deleted at startup |
| `cache.predictiveCacheTTLDays` | `2` | Yes | |
| `cache.startupLoadStrategy` | `STAGED` | No | |
| `cache.ownershipAuditIntervalMinutes` | `5` | Yes | `-1` to disable |

### TTL (days, `-1` = never expire)
| Key | Default | Hot-reload |
|---|---|---|
| `ttl.warm` | `-1` | Yes |
| `ttl.recentHot` | `7` | Yes |
| `ttl.predictive` | `2` | Yes |
| `ttl.lowTraffic` | `1` | Yes |
| `ttl.bed` | `3` | Yes |
| `ttl.deathLocation` | `1` | Yes |

### Keepalive
| Key | Default | Hot-reload | Notes |
|---|---|---|---|
| `keepalive.hot.enabled` | `true` | Yes | |
| `keepalive.hot.intervalMinutes` | `5` | Yes | |
| `keepalive.warm.enabled` | `true` | Yes | |
| `keepalive.warm.intervalMinutes` | `15` | Yes | |
| `keepalive.cold.enabled` | `false` | Yes | Brings COLD zones back to WARM |
| `keepalive.cold.intervalMinutes` | `60` | Yes | |

### Decay
| Key | Default | Hot-reload | Notes |
|---|---|---|---|
| `decay.hotDecaySeconds` | `30` | Yes | HOT → WARM |
| `decay.warmDecayMinutes` | `45` | Yes | WARM → COLD |
| `decay.pollIntervalSeconds` | `1` | Yes | |

### Low Traffic
| Key | Default | Hot-reload | Notes |
|---|---|---|---|
| `lowTrafficThreshold` | `5` | Yes | Player count below which low-traffic TTL applies |

### Async / TPS
| Key | Default | Hot-reload | Notes |
|---|---|---|---|
| `async.tpsMonitorEnabled` | `true` | Yes | |
| `async.tpsLowThreshold` | `15.0` | Yes | Reduces batch sizes below this |
| `async.tpsCriticalThreshold` | `12.0` | Yes | Queues non-critical ops below this |
| `async.maxLoadedChunksPressureThreshold` | `2000` | Yes | `-1` to disable |

### Warps
| Key | Default | Hot-reload |
|---|---|---|
| `warps.sourcePath` | `universe/warps.json` | Yes |
| `warps.watchForChanges` | `true` | Yes |
| `warps.watchIntervalSeconds` | `30` | Yes |
| `warps.worldField` | `World` | Yes |
| `warps.idField` | `Id` | Yes |
| `warps.xField` / `yField` / `zField` | `X` / `Y` / `Z` | Yes |
| `warps.yawField` | `Yaw` | Yes |

### Gravestones
| Key | Default | Hot-reload |
|---|---|---|
| `gravestones.sourcePath` | `plugins/Gravestones/gravestones.json` | Yes |
| `gravestones.watchForChanges` | `true` | Yes |
| `gravestones.watchIntervalSeconds` | `5` | Yes |

### Integrations
| Key | Default | Hot-reload | Notes |
|---|---|---|---|
| `integrations.gravestone.enabled` | `false` | Yes | |
| `integrations.gravestone.pluginId` | `gravestones` | No | |
| `integrations.gravestone.releaseOnBreak` | `true` | Yes | |
| `integrations.gravestone.releaseOnEmpty` | `true` | Yes | |

### Density-Based Prioritization
| Key | Default | Hot-reload | Notes |
|---|---|---|---|
| `densityBased.corridor.enabled` | `true` | Yes | Prioritize chunks near WorldPath waypoints |
| `densityBased.corridor.radiusChunks` | `3` | Yes | Radius around waypoints to mark as corridor |

### In-Game UI
| Key | Default | Hot-reload |
|---|---|---|
| `ui.showInstancedPortals` | `false` | Yes |
| `ui.showBedSpawns` | `true` | Yes |
| `ui.showDeathLocations` | `true` | Yes |

### MySQL (only if `backend: MYSQL`)
| Key | Default | Hot-reload |
|---|---|---|
| `mysql.host` | `localhost` | No |
| `mysql.port` | `3306` | No |
| `mysql.database` | `preload` | No |
| `mysql.username` / `mysql.password` | — | No |
| `mysql.tablePrefix` | `pre_` | No |

### Metrics
| Key | Default | Hot-reload | Notes |
|---|---|---|---|
| `metrics.bstatsEnabled` | `true` | Yes | |
| `metrics.bstatsPluginId` | `0` | No | `0` = unregistered |

---
