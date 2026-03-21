# Changelog

## [1.1.1] - 2026-03-21

### Changed

- **Event-driven chunk retention**: Chunks belonging to warm zones are now protected from eviction by an event subscription that fires synchronously before the engine removes a chunk, rather than relying on a periodic keepalive timer. The timer is retained as a belt-and-suspenders fallback at much longer intervals (30 / 60 / 120 minutes for HOT / WARM / COLD, down from 5 / 15 / 60). This eliminates the timing window where a chunk could be evicted between heartbeats.

- **Portal device scanning uses live block components**: Portal block scanning now reads destination world data directly from the block component system rather than relying on indirect resource heuristics. Destination worlds discovered this way are registered as warm zones immediately, and the stored destination world identifier is kept up-to-date as chunks load.

- **Portal destination world validation before preloading**: Before spending chunk budget on a predictive or warm load, the destination portal world is now checked to confirm it is live and has a valid spawn point. Worlds that are still being initialised, have been torn down, or have no spawn configured are skipped cleanly without logging noise. This applies to the approach-based trigger, the movement proximity path, and the startup warm load.

- **Zone chunk coverage tracked across the full zone footprint**: The internal chunk-to-zone index now covers every chunk within a zone's radius, not just the centre chunk. When the server loads any chunk within a registered zone for any reason — player movement, NPC pathfinding, world generation — OptiPortal claims ownership immediately without waiting for a dedicated preload pass. This closes the window where engine-loaded chunks inside a zone boundary could be evicted before OptiPortal noticed them.

- **Preload batch size and delay adapt to server TPS**: When the server is under load, preload batches are automatically halved in size and inter-batch pauses are doubled, reducing the additional pressure OptiPortal places on the world thread during busy periods. Batch sizing reverts to normal as soon as TPS recovers.

- **Zone state cleaned up immediately when a world is removed**: When the server removes a world at runtime, all warm zones belonging to that world are evicted from the chunk ownership registry and tier tracking immediately. Previously, stale zone state could linger until the next server restart, producing misleading counts in status output and keeping references to freed memory alive.

- **World resolution uses stable UUID lookup**: When resolving a destination world for preloading, the plugin now performs a direct UUID lookup via the engine's universe registry before falling back to name-based lookup. This is both faster and more correct when worlds have been recreated under the same name.

### Fixed

- **BUG FIX — Chunk eviction guard referenced incorrect engine API**: The component that prevents owned chunks from being removed from memory referenced several methods that do not exist in the current engine version.

- **BUG FIX — Auto-registered portal zone only protected its centre chunk**: When a portal block was discovered at runtime and automatically registered as a zone, only the single chunk containing the portal was added to the chunk ownership index. Neighbouring chunks within the zone's radius were unprotected until the next server restart, when the full footprint was reconstructed from storage. The full footprint is now indexed immediately on auto-registration.

- **BUG FIX — Warm load validity gate incorrectly blocked portal zones without a destination**: The check that skips preloading into dead or uninitialised portal worlds was applied to all portal-type zones, not just those with an explicit destination world configured. For portal zones with no destination UUID, the gate resolved and validated the zone's own world instead, silently skipping the zone if that world carried a portal resource that was not yet ready. The gate now only runs when a destination world UUID is explicitly set.

- **BUG FIX — TPS-adaptive batch sizing had no effect under the enhanced preloader**: The logic that halves preload batch sizes under low TPS was added to the correct override point but was never reached at runtime, because the enhanced preloader routes all loads through its own internal path rather than the base class pipeline. The adaptive cap is now applied within the enhanced path and correctly reduces batch sizes when the server is under load.

- **BUG FIX — MySQL backend served stale zone data after saves**: When using the MySQL storage backend, saving a zone did not refresh the in-memory zone list. The plugin continued to serve the pre-save state until the next restart, so changes made at runtime — including zone registrations and tier updates — were invisible to the rest of the plugin until it was restarted.

- **BUG FIX — `/preload activation` with no value silently cleared the distance**: Running `/preload activation <id>` without a value argument did not print the usage hint. Instead it silently cleared the per-zone activation distance as if `reset` had been passed, discarding the existing override without any operator confirmation.

- **BUG FIX — `/preload reload` crashed instead of reloading**: The reload command and the automatic config file watcher both crashed at runtime with an internal error rather than reloading `config.json`. No configuration changes took effect without a full server restart.

- **BUG FIX — Plugin failed to start due to missing configuration accessors**: Several configuration values read by the plugin at runtime — including chunk pressure limits, ownership audit interval, per-tier keepalive settings, and portal link decay days — had no corresponding entries in the configuration layer. This caused a build failure preventing the plugin from loading at all.

- **BUG FIX — Load balancer always reported as healthy in diagnostics**: The async infrastructure health check contained a condition that was unconditionally true regardless of actual load balancer state. The load balancer was therefore always shown as healthy in `/preload status` output even when it was not.

- **BUG FIX — Zone load caused excessive storage writes**: Every chunk loaded during a zone preload triggered a separate storage write, producing up to 121 writes for a single zone. All measurements are now batched and written once when the zone finishes loading. Additionally, the actual RAM figure shown in the UI was only reflecting the last chunk loaded rather than the full zone total — this is now accumulated correctly.

- **BUG FIX — Keepalive scanned all zones on every heartbeat**: The keepalive system was re-reading all zones from storage on every heartbeat tick rather than using the already-loaded in-memory list. On servers with many zones this caused unnecessary I/O every few minutes.

- **BUG FIX — Deleting a zone left behind internal tracking state**: When a zone was removed, a stale reference remained in the chunk-tracking index. If the server later loaded a chunk at the old zone's position, the deleted zone would reappear in tier counts and diagnostics. Deleted zones are now fully cleaned up, and any remaining stale references are caught and pruned automatically.

- **BUG FIX — Linked portal not preloaded during player approach**: When a player walked toward a portal with a known link, the destination portal was preloaded but the linked return portal was not. The linked preload was only missing from the movement-based detection path — the fix brings it in line with the rest of the preload logic.

- **BUG FIX — Predictive preload radius was not configurable**: The radius used for death-location and respawn preloads was hardcoded and ignored the configured value. It now reads correctly from `config.json`.

- **BUG FIX — Plugin log output bypassed server log level**: Some startup and configuration messages were written directly to stdout/stderr, bypassing the server's configured log level. All output now goes through the standard logging system.

- **BUG FIX — API zone registration used wrong world**: When another plugin registered a warm zone via the OptiPortal API, the zone was always created in a world named "default" regardless of what world was intended. The API now requires the world name to be supplied explicitly. (**Breaking change** for API callers — add the world name argument.)

- **BUG FIX — Chunk loading used incorrect API signature**: The async chunk loading methods expect a single packed `long` chunk index. Several call sites were passing two separate `int` coordinates, which is not a valid overload. All affected call sites now produce the correct packed index via the standard chunk utility.

- **BUG FIX — CorridorIndex calculated chunk coordinates with wrong chunk size**: Waypoint world-space coordinates were divided by an incorrect chunk width constant, causing every corridor chunk boundary to be computed against the wrong grid. Corridor prioritisation was therefore targeting the wrong chunks entirely. The conversion now uses the engine-provided utility, which applies the correct chunk size.

- **BUG FIX — Zone list loaded on the world thread when opening the admin UI**: Opening the admin UI panel triggered a storage query on the world thread, causing a measurable stall whenever the command was used. The query now runs before the world thread is involved, and the result is passed in directly.

- **BUG FIX — Actual RAM column showed inconsistent values across zones**: Two code paths wrote the same field using different formulas and no guaranteed ordering, so whichever ran last would win. Values from previous server sessions also persisted in the database, meaning the column could reflect a measurement from an entirely different version of the plugin. The competing path has been removed; all zones now use a single consistent formula based on chunk count and the configurable bytes-per-chunk value.

- **BUG FIX — Keepalive config used wrong structure**: The default config file used a flat layout for keepalive settings that did not match the nested object structure the parser expected, causing the plugin to crash on startup if the config had not been manually edited. The parser now accepts both layouts for compatibility with existing deployments.

### Added

- **Per-zone horizontal activation distance**: Zones can now have their own horizontal trigger radius, set with `/preload activation <id> <distance>`. Use `/preload activation <id> reset` to revert to the global default. Previously only the vertical distance supported per-zone overrides.

- **/preload zone command**: New diagnostic subcommand that prints the full details for a single zone — tier, strategy, radius, activation distances, TTL, RAM estimates, linked portal, last-active time, and preload count. Useful for inspecting a zone without reading data files directly.

- **/preload delete command**: Permanently removes a zone and cleans up all associated state — chunk ownership, cache tier, portal links, and the in-memory cache. Previously, auto-registered zones could only be removed by manually editing data files and restarting.

- **/preload flush command**: Forces all zones to be re-evaluated and written to storage with freshly calculated RAM estimates. Useful after changing `bytesPerChunk` or upgrading from a version where stored RAM values were inaccurate.

### Configuration

New `config.json` fields (all optional, defaults apply if absent):

- `bytesPerChunk` — estimated memory per loaded chunk in bytes, used to calculate the ACTUAL RAM column (default: `98304` = 96 KB)
- `activation.predictiveRadius` — radius in chunks for predictive preloads (default: `7`)
- `decay.hotDecaySeconds` — seconds before HOT zones decay to WARM (default: `30`)
- `decay.warmDecayMinutes` — minutes before WARM zones decay to COLD (default: `45`)
- `decay.pollIntervalSeconds` — interval for zone tier polling (default: `1`)
- `portalLinks.confidenceThreshold` — observations required to confirm a portal link (default: `5`)

---

## [1.1.0] - 2026-03-20

### Fixed

- **BUG FIX — Death/respawn preload radius ignored config**: The preload radius for death and respawn locations was hardcoded to 7 chunks and did not reflect the configured value. All preload paths now use the same radius setting.

- **BUG FIX — Per-zone vertical activation distance ignored during staggered updates**: Per-zone vertical override set via `/preload` was only respected on the synchronous proximity path. The async staggered-update path used the global value for all portals regardless of overrides.

- **BUG FIX — Disconnected player tracking entries not fully cleaned up**: On disconnect, several internal player tracking entries were left behind and accumulated over time. All tracking state for a player is now cleared atomically on removal.

### Added

- **TTL eviction now works for all zone types**: Expiry settings were stored correctly but had no effect on portal zones or respawn entries because last-activity timestamps were never recorded for them. Activity is now stamped on each preload and warm load, so TTL eviction runs correctly across all entry types.

- **Plugin log output respects server log level**: Internal messages were being written directly to stdout/stderr, bypassing the server's configured log level. All output now goes through the standard logging system and is consistently prefixed.

- **/preload status**: New subcommand showing async infrastructure health at a glance — circuit breaker state, load balancer activity, TPS, loaded chunk count, and zone tier distribution. Useful for diagnosing preload suppression under server load.

- **/preload links**: New subcommand listing all confirmed portal links and pending candidates with their observation counts. Supports `/preload links remove <id>` to delete a wrong link and `/preload links clear-pending` to discard all unconfirmed candidates. Previously, correcting wrong links required manual JSON editing and a server restart.

- **/preload ttl**: New subcommand to set per-zone TTL overrides from the command line without editing portal-data.json. Accepts a day count, `-1` for never-expire, or `reset` to revert to the global default for that zone type.

---

## [1.0.9] - 2026-03-20

### Added

- **Vertical activation bound consistency**: Proximity detection previously used a fixed vertical distance that didn't match the configurable setting, causing portals to behave inconsistently. Now it respects the configured vertical distance and per-zone overrides, ensuring portals trigger reliably from any height.

- **Asymmetric zone radius**: Zone width and depth settings were saved but never actually used — all zones were forced into circles. Now zones can be rectangular, perfect for corridors and bridges, with automatic fallback to circular zones when not specified.

- **Portal link confidence threshold**: Portal connections were saved after just one use, which could accidentally learn wrong links. Now connections require multiple confirmations (default: 3 uses) before being saved, and unconfirmed links automatically expire after a week of inactivity. Configurable via `portalLinks.confidenceThreshold` and `portalLinks.pendingDecayDays`.

- **TTL enforcement**: Time-based expiration settings were stored but never actually removed old entries. A new cleanup process now automatically deletes expired entries like death locations and temporary zones every 24 hours (configurable), while protecting active zones from being deleted. Configurable via `ttl.cleanupIntervalHours`.

- **Native death hook**: Death location tracking was completely inactive because it was waiting for an external plugin. Now it works natively with Hytale, so respawn screen preloading functions immediately without requiring any additional plugins.

### Changed

- `/preload radius` command now supports `/preload radius <id> <X> [Z]` syntax with backward-compatible Z omission (defaults to X when omitted).

### Configuration

New `config.json` fields (all optional, defaults apply if absent):

- `portalLinks.confidenceThreshold` — number of observations required to confirm a portal link (default: `3`)
- `portalLinks.pendingDecayDays` — days of idle time before pending links are purged (default: `7`)
- `ttl.cleanupIntervalHours` — interval in hours for TTL cleanup runs (default: `24`)

---

## [1.0.8] - 2026-03-20

- **Keepalive now active**: The async keepalive manager was constructed but never started, meaning no HOT/WARM heartbeat pings fired since 1.0.4. Chunks owned via the dedup path (no direct pin) were relying on this for retention.
- **Gravestone integration restored**: Due to initialization order, the gravestones plugin integration was always passed as null to the teleport interceptor, silently disabling death-location cache release even when the gravestones plugin was installed.
- **Warp file watcher portal cache callback restored on reload**: After `/preload reload`, the warp file watcher was reconstructed without its portal cache invalidation callback, meaning warp changes no longer refreshed the proximity detection cache until server restart.
- **In-memory chunk fast-path**: Chunks already resident in the engine's store are pinned and registered directly without a redundant async load call. Chunks mid-save are deferred until the save completes. Applies to both the base and enhanced preload pipelines.
- **Enhanced preload backoff guard**: The enhanced chunk preloader now correctly skips chunks on failure backoff, matching existing behaviour in the base preloader. Previously broken positions were retried on every preload pass.
- **Preload backpressure**: Chunk loading is suspended when JVM heap exceeds 80% (giving a 5% margin before the engine's own desperate-eviction threshold), and also when a world's live chunk count exceeds the configured pressure ceiling.
- **Ownership auditor fix**: The periodic audit now correctly cross-references ownership records against the engine's chunk index, resolving an encoding mismatch that prevented it from ever detecting real evictions.
- **Async metrics accuracy**: Chunk load success and error events were recorded twice per chunk in both the enhanced preload pipeline and keepalive pings (once by WorldThreadBridge with real latency, once by the caller with false 0ms duration). Duplicate calls removed.
- **RAM estimate storage efficiency**: Zone RAM estimates were updated by calling storage.loadAll() once per chunk loaded — 121 calls for a radius-5 zone. Replaced with a targeted loadById() lookup.
- **BUG FIX — Warp sync no longer deletes portal zones and player data**: The warp file watcher was deleting ALL storage entries not present in the warp file on every sync cycle (default every 30 seconds). This silently erased auto-registered portal destination zones, portal device zones, death location records, and respawn location records. The deletion loop now only removes entries of type PORTAL (warp entries).
- **BUG FIX — Portal link registry thread safety**: The portal link registry used a plain HashMap accessed from multiple executor threads, allowing lost updates and ConcurrentModificationException under concurrent teleports. Replaced with ConcurrentHashMap and added synchronization to the compound read-modify-write methods.
- **BUG FIX — JSON storage backend thread safety**: The JSON backend's in-memory entries map (LinkedHashMap) was read and mutated from multiple threads without synchronization, risking ConcurrentModificationException and lost writes. All five public read/write methods are now synchronized.
- **BUG FIX — Async keepalive enhancement never active**: The enhanced keepalive implementation (load-balanced, batched via WorldThreadBridge) was silently bypassed because the base class method it intended to override was private. All keepalive pings were falling through to the base implementation. Method visibility corrected.
- **BUG FIX — Async teleport poll override never scheduled**: The async teleport record polling override was never invoked by the scheduler for the same reason — private method in the base class. Base polling continued to function, but the enhanced batched path was dead code.
- **BUG FIX — Player disconnect leaked position tracking entries**: On disconnect, the player's last-known-position and last-seen-teleport-nanos entries were not removed, leaving them to accumulate indefinitely. Both maps are now cleared on player removal.

---

## [1.0.7] - 2026-03-19

- **Native chunk retention**: Chunks loaded for portal zones are now held in memory by the
  server's own pinning mechanism rather than relying solely on periodic reload requests,
  reducing unnecessary I/O and world-thread wake-ups during idle periods.
- **Keep-loaded pin release**: Chunks held in memory by OptiPortal are now correctly
  released when their zone goes cold, preventing long-term memory growth on servers with
  many portal zones that cycle through active and idle periods. This now applies to both
  explicit release and the HOT→WARM→COLD tier-decay path.
- **BUG FIX**: All zone registrations and preload coordinates were targeting the wrong chunk positions due to an incorrect block-to-chunk conversion. All preloads now land on the correct chunks.
- **Native warp sync**: Warp data is now read directly from the server's live in-memory state rather than polling the warps file, eliminating stale reads and file I/O overhead. Falls back to file polling if the native source is unavailable.
- **Pre-spawn chunk preloading**: The spawn area is now pre-warmed before a connecting player is placed in the world, eliminating cold-load spikes on first login.
- **Chunk backoff guard**: Chunks that have recently failed to load are now skipped during preload passes, preventing repeated load attempts against broken chunk positions.
- **Event-driven staged load**: The startup warm zone load now triggers immediately when the server finishes initialising rather than polling on a fixed interval. Polling is retained as a fallback.
- **Chunk pressure-aware batch sizing**: Preload batch sizes are now reduced automatically when the server is managing a high number of loaded chunks across all worlds.
- **Eternal world decay exemption**: Zones in worlds that never unload chunks are now permanently exempt from HOT→WARM→COLD decay, avoiding pointless tier churn for hubs and lobbies.
- **Portal zone lookup performance**: Zone promotion on chunk load now resolves in constant time regardless of how many portals are registered, replacing a full scan on every chunk load event.
- **World initialisation ordering**: Plugin subsystems now initialise against a world only after it is fully operational, preventing edge cases where portal scanning or event registration ran against a partially-started world.
- **Log level control**: Internal diagnostic output now respects the server's configured log level, allowing verbose portal and teleport tracing to be suppressed in production without code changes.

---

## [1.0.6] - 2026-03-19

- **DrainPlayerFromWorldEvent hook**: Instant origin-zone linger and portal-link capture on world exit, replacing 1-second poll latency.
- **PortalWorld resource auto-detection**: Portal destination world spawn points auto-registered as PREDICTIVE zones on world load; no warps.json required.
- **ChunkPreLoadProcessEvent listener**: PortalDevice blocks auto-discovered and registered; COLD zones promoted to WARM when the server natively loads their chunks.
- **Universe seeding fallback**: WorldRegistry seeds from the live Universe map at startup to capture worlds loaded before the plugin started.

---

## [1.0.5] - 2026-03-19

### Added
- **Warm zone state persistence** — HOT/WARM zone state is saved on shutdown and restored on startup; zones that have not yet expired are resumed without reloading chunks, reducing startup I/O after clean restarts
- **Concurrent warm zone startup loading** — startup staged load now runs up to 3 zones concurrently instead of sequentially, significantly reducing time-to-ready when multiple WARM zones are configured
- **In-memory portal cache** — portal proximity checks no longer query storage on every player position update; the portal list is cached in memory at startup and kept in sync with any changes. Cache is refreshable on demand via `AsyncTeleportInterceptor.refreshPortalCache()`
- **Chunk complexity and RAM caching** — chunk scoring and RAM estimation results are cached for the lifetime of the server session; repeated loads of the same chunk skip redundant work entirely. Cache is cleared automatically when a world unloads.

### Fixed
- Async proximity detection silently matched zero portals due to a world name lookup bug; portal preloading now triggers correctly for all async teleport paths
- `NullPointerException` on async teleport preload fixed; no longer crashes on teleport detection
- Warps removed from `warps.json` were not deleted from storage on first server boot after install
- Repeated preload log spam eliminated under both idle and active conditions
- Zone tier promotion now correctly reflects actual chunk load state rather than configured strategy
- World thread saturation under load caused by chunk operations running on the wrong thread
- Special characters in JSON output were being written as Unicode escape sequences; all config and data files now write readable characters directly
- Portal proximity detection now respects vertical distance; zones on different floors no longer trigger preloads for players above or below them
- Activation shape setting now takes effect; ellipsoid, cylinder, and box shapes are all supported with per-zone overrides via `/preload shape <id> <ELLIPSOID|CYLINDER|BOX>`

### Added
- Actual RAM total shown in UI stats bar alongside the existing RAM estimate total

### Changed
- Warp file sync reduced from one storage query per warp to a single bulk query per sync cycle; significantly faster on servers with large warp counts or remote databases
- Zone decay and release operations are now proportional to the size of the affected zone rather than the total number of loaded chunks on the server
- Async load balancer adapts batch sizes based on recent performance rather than a lifetime average that becomes unresponsive after extended uptime
- Async task queues are now lock-free, reducing contention between threads queuing and processing operations
- Teleport record checks are batched into a single scheduled task regardless of player count, reducing scheduler overhead at scale
- Portal disk writes are debounced and written asynchronously; no longer blocks on file I/O during active teleportation
- Proximity distance checks optimised to avoid unnecessary floating-point operations on every player position update
- Startup poll scheduler is cancelled immediately once worlds are ready rather than continuing to fire unnecessarily

### Configuration
New `config.json` fields (all optional, defaults apply if absent):
- `stagedLoadConcurrency` — number of WARM zones loaded concurrently at startup (default: `3`)

### Changed
- Default activation distances tightened: horizontal `16 → 10`, vertical `8 → 3`

---

## [1.0.4] - 2026-03-17

### Added
- **Corridor prioritization** — chunks near WorldPath waypoints are sorted ahead of equivalent non-corridor chunks during predictive loads, reducing visible pop-in along high-traffic routes
- **Velocity-aware preload radius** — when a player enters a portal zone while moving above a configurable speed threshold, an extra chunk ring is preloaded to compensate for the reduced time before arrival
- **Chunk complexity scoring** — loaded chunks are scored based on terrain density and used to produce per-chunk RAM estimates
- **Ownership drift auditing** — periodic background audit detects and corrects chunk ownership records that have drifted from actual loaded state
- **Async keepalive manager** — warm and hot zone keepalive heartbeats run off the world thread with proper load balancing
- **Circuit breaker** — world thread operations back off automatically when failure rate exceeds threshold, preventing cascading overload

### Fixed
- Corrected `AsyncLoadBalancer` drain loop to use `ArrayDeque` for proper FIFO task ordering
- Fixed thread-unsafe `HashSet` in warp file watcher that could cause data corruption under concurrent access
- Batch chunk eviction callbacks — auditor now processes all evicted chunks in a single pass rather than one call per chunk, avoiding redundant map operations on large eviction events

### Changed
- Chunk preloader upgraded to async implementation with world thread isolation; all chunk loads now go through `WorldThreadBridge` rather than blocking the event handler thread
- `EnhancedChunkPreloader` replaces base `ChunkPreloader` as the active implementation; base class retained for compatibility
- Predictive load dispatch in `TeleportInterceptor` is now overridable via `triggerPredictiveLoad` hook, allowing subclasses to augment radius without duplicating event handling logic
- Position update batch skipped entirely when no players are online, eliminating unnecessary allocation on idle servers
- Tier decay scheduler now skips the full zone map iteration when no zone is due for decay, reducing overhead to a single atomic read per 10-second tick on idle servers
- Load balancer task processor skips all priority queue acquisitions when the queue is empty

### Configuration
New `config.json` fields (all optional, defaults apply if absent):
- Config migration — on each startup, any keys present in the bundled default `config.json` but absent in the server's file are automatically added with their default values; existing operator settings are never overwritten
- `densityBased.corridor.enabled` — enable/disable corridor prioritization (default: `true`)
- `densityBased.corridor.radiusChunks` — waypoint radius in chunks (default: `3`)
- `activation.velocityAwareActivation` — enable/disable velocity radius boost (default: `true`)
- `activation.velocityRadiusBoostThreshold` — speed in blocks/tick above which boost applies (default: `0.5`)
- `async.tpsMonitorEnabled`, `async.tpsLowThreshold`, `async.tpsCriticalThreshold` — TPS guard settings
- `cache.ownershipAuditIntervalMinutes` — how often the ownership auditor runs (default: `5`)

---

## [1.0.31] - 2026-03-16

### Fixed
- Plugin shutdown now correctly saves the cache, flushes the WAL, and closes storage on server stop
- Fixed a NullPointerException that silently broke async teleport detection on every call
- Fixed an incompatible API call in player ready handling
- Fixed a NullPointerException on first chunk load caused by incorrect initialisation order

---

## [1.0.3] - 2026-03-14

### Added
- Async infrastructure for improved performance and reduced world thread blocking
- Enhanced teleport handling with async operations and staggered updates
- Improved keepalive operations with batched processing
- Increased executor pool size for better concurrency
- Performance optimizations for stable server TPS under high load

### Fixed
- CircuitBreaker import added to OptiPortal.java
- RAM marginal display now shows 2 decimal places instead of rounding to nearest integer
- Improved RAM estimation accuracy: changed from 256KB to 64KB per chunk with 1.5x overhead multiplier

### Removed
- `/preload stats`

---

## [1.0.21] - 2026-03-12

### Fixed
- Fixed MySQL schema errors caused by the `ram_measured` column

---

## [1.0.2] - 2026-03-11

### Added
- Respawn tracker — tracks respawn locations to preload upon death (WIP)

### Fixed
- Portal link registry no longer picks up `death:` and other player-data entries; these are now completely invisible to all portal detection logic

### Removed
- BedTracker

---

## [1.0.1] - 2026-03-11

### Added
- Native Hytale UI panel (`/preload list`) with zone table, stats bar, and command reference

### Fixed
- Portal link learning no longer breaks when multiple hub portals are in close proximity
- Proximity detection now triggers regardless of which direction the player is facing
- Resolved async threading errors during teleport polling
- Reduced log spam from chunk preload and linked preload cooldown events

### Notes
- Delete `portal-data.json` and `portal-links.json` on upgrade to clear any stale zone data

---

## [1.0.0] - 2026-03-10

Initial release
