# OptiPortal

OptiPortal is a Hytale server optimization plugin focused on reducing or eliminating lag spikes caused by chunk loading during portal travel. It tracks warp and portal destinations, learns portal links from real teleports, and preloads destination chunks ahead of player arrival to reduce cold-load disk I/O, avoid sudden chunk-load bursts, and cut TPS and MSPT hits during teleports, warps, respawns, and similar location-based transitions.

OptiPortal manages zones through `HOT`, `WARM`, and `COLD` cache tiers, persists useful cache state, and adapts preload behavior when the server is under pressure so chunk readiness improves without keeping everything loaded all the time. It also provides live tools for diagnostics, per-zone tuning, TTL control, backups, and hot config reloads.

## What It Does

- Preloads destination chunks before portal travel completes
- Learns portal-to-portal links automatically at runtime and stores them in `portal-links.json`
- Maintains a three-tier cache model: `HOT`, `WARM`, and `COLD`
- Supports `JSON`, `SQLITE`, `H2`, and `MYSQL` storage backends
- Watches `warps.json` for live updates
- Optionally integrates with the Gravestones plugin
- Exposes a native in-game UI when you run `/preload` with no arguments
- Includes operator commands for zone cleanup, link management, TTL overrides, and runtime status checks

## Core Ideas

### Three-Tier Cache

| Tier | Purpose |
|---|---|
| `HOT` | Chunks are actively loaded and ready for immediate use |
| `WARM` | Chunks are kept in memory for high-traffic or pinned destinations |
| `COLD` | Chunks are no longer resident but can persist on disk for faster rehydration |

Zones decay naturally based on activity. Keepalive tasks can hold important zones in place longer.

### Portal Link Registry

Portal pairs are learned from real player teleports. Once OptiPortal has enough observations to trust a link, approaching one end of the pair preloads the other. If a portal's destination changes later, the learned mapping updates itself as new travel events come in.

### Load-Aware Preloading

The current implementation includes:

- terrain-density-based chunk prioritization
- corridor prioritization near WorldPath routes
- velocity-aware predictive radius boosting
- TPS-sensitive throttling
- GC-aware batch deferral
- loaded-chunk pressure backoff
- chunk failure retry cooldowns
- a circuit breaker for overloaded async paths

## Commands

All commands use the `/preload` prefix.

Running `/preload` with no arguments opens the native UI panel for players in-world.

### Zone Management

| Command | Description |
|---|---|
| `/preload list` | List all known zone entries |
| `/preload strategy <id> <WARM\|PREDICTIVE>` | Change a zone strategy |
| `/preload shape <id> <ELLIPSOID\|CYLINDER\|BOX>` | Override a zone's activation shape |
| `/preload radius <id> <X> [Z]` | Set a zone radius, with optional separate Z radius |
| `/preload radiusxz <id> <rx> <rz>` | Legacy asymmetric radius command; `radius` is preferred |
| `/preload activation <id> <distance>` | Override horizontal activation distance for one zone |
| `/preload activation <id> reset` | Reset per-zone activation distance |
| `/preload setwarm <id> [radius]` | Force a zone to `WARM` and load it now |
| `/preload unsetwarm <id>` | Return a zone to `PREDICTIVE` and release warm chunks |
| `/preload preload <id>` | Trigger a predictive preload immediately |
| `/preload ttl <id> <days\|-1\|reset>` | Set or clear a per-zone TTL override |
| `/preload zone <id>` | Show per-zone diagnostic details |
| `/preload delete <id>` | Delete a zone and perform full cleanup |
| `/preload flush` | Recalculate zone RAM values and write all current zone entries back to storage |

### Diagnostics

| Command | Description |
|---|---|
| `/preload ram` | Show cache RAM estimates |
| `/preload status` | Show async health, circuit breaker state, TPS, chunk count, and zone tier totals |
| `/preload links` | List confirmed and pending portal links |
| `/preload links remove <id>` | Remove a confirmed link for a portal |
| `/preload links clear-pending` | Clear unconfirmed candidate links |
| `/preload help` | Print command help in chat |

### Maintenance

| Command | Description |
|---|---|
| `/preload refresh warps` | Re-read `warps.json` immediately |
| `/preload reload` | Hot-reload `config.json` where supported |
| `/preload migrate <JSON\|SQLITE\|H2\|MYSQL>` | Print backend migration guidance |
| `/preload backup list` | List WAL backups |
| `/preload backup restore <date>` | Restore a backup and recommend restart |

## Integrations

### Warps

OptiPortal reads `warps.json` and registers warp destinations as preload zones. The watcher can refresh automatically, and `/preload refresh warps` forces an immediate re-read.

The field names are configurable through the `warps.*` settings if your warp data uses different property names.

### Gravestones

When enabled, OptiPortal can watch gravestone data and preload around death or recovery locations. This integration is optional and depends on the Gravestones plugin being installed.

## Storage Backends

| Backend | Config Value | Notes |
|---|---|---|
| JSON | `JSON` | Easiest setup, human-readable flat file |
| SQLite | `SQLITE` | Single-file SQL database, good default upgrade path |
| H2 | `H2` | Embedded SQL backend with stronger database tooling |
| MySQL | `MYSQL` | External database for shared or remote setups |

JSON storage uses WAL-safe atomic writes and backup recovery. SQL backends support the same zone model while improving performance on larger or busier servers.

## Hot Reload

`/preload reload` applies a large portion of `config.json` without a restart, including many activation, decay, keepalive, cache, watcher, UI, and metrics settings.

A full server restart is still required for settings that wire core infrastructure at startup, such as:

- `backend`
- `startupLoadStrategy`
- `rebuildFromChunksOnCorruption`
- `scheduledRebuildIntervalHours`
- `mysql.*`
- `cache.cacheDirectory`
- `cache.maxCacheAgeDays`
- `immuneToSimulationReduction`
- `updateChecker.enabled`
- `integrations.gravestone.pluginId`
- `metrics.bstatsPluginId`

If `config.json` contains invalid JSON, the reload is rejected and the current runtime config remains in place.

## Important Config Areas

The full default config lives at [`src/main/resources/config.json`](/i:/OptiPortal%20-%20Copy%20-%20Copy/OptiPortal%20safe/PreloadPlugin3/src/main/resources/config.json).

The settings operators usually care about first are:

- `backend`
- `defaults.strategy`
- `defaults.warmRadius`
- `activation.distance`
- `activation.distanceVertical`
- `activation.predictiveRadius`
- `keepalive.*`
- `decay.*`
- `ttl.*`
- `warps.*`
- `integrations.gravestone.*`
- `portalLinks.confidenceThreshold`

## Runtime Notes

- Portal links are saved separately from zone storage in `portal-links.json`
- Cold-cache data can be persisted to `preload-cache/`
- WAL backups can be listed and restored through `/preload backup ...`
- `/preload status` is the quickest way to confirm whether load throttling or async infrastructure is affecting behavior

## Test Server

`CPU: E5-1650v4`  
`RAM: 48GB`  
`Storage: NVMe`  
`OS: Proxmox LXC / Ubuntu`
