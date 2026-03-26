# Changelog

---

## [1.1.7] - 2026-03-24

### Fixed

- **BUG FIX — Multiple players approaching the same portal simultaneously each triggered a full independent load**: When several players walked toward the same portal at the same time, each one triggered a separate chunk load for the destination zone, queuing duplicate sets of world-thread tasks that scaled with the number of concurrent players. At most one load now runs per zone at a time. Additional callers that arrive while a load is already in progress attach to the existing result and no extra work is queued.

- **BUG FIX — Load balancer batch size could lock at maximum permanently after long uptime**: The average execution time used by the batch size controller was calculated from an unbounded sum and counter. After approximately 2 billion operations the counter would overflow and the calculated average would read as a large negative number. The controller would interpret this as excellent performance and permanently lock the batch size at its maximum regardless of actual server load. The measurement now uses a decaying average that gives more weight to recent samples and requires no counter, so it is unaffected by uptime.

- **BUG FIX — World thread batch processor stopped permanently on world shutdown**: When the engine threw a shutdown error while the batch processor was dispatching a batch to a world thread, that error would propagate out of the scheduled task and silently cancel all future invocations of the processor across all worlds. The batch processor now catches shutdown errors, discards the affected batch, and continues running for all remaining worlds.

- **BUG FIX — TTL cleanup, keepalive, warp sync, and tier decay could each stop permanently after a single error**: Four background tasks ran on a fixed schedule with no exception guard. If any one of them threw an unexpected error — such as a storage failure during a database hiccup, or an engine error while checking chunk residency — the Java scheduler would silently cancel that task for the lifetime of the server. TTL would stop expiring entries, keepalive would stop pinging chunks, warp sync would freeze, and tier decay would halt. Each task is now guarded so errors are logged and the schedule continues.

- **BUG FIX — A concurrent failure during circuit breaker recovery could be silently masked by a success**: When a success and a failure arrived at the circuit breaker at the same moment while it was in the half-open recovery state, the success path could close the circuit after the failure had already re-opened it, hiding the failure entirely. The circuit breaker now only closes on success if it is still in the recovery state at the moment the transition is applied.

- **BUG FIX — Zone shown as HOT after chunk load was cancelled by a server guard**: If a chunk load was stopped early because the server was low on memory, under chunk pressure, or lagging, the zone was still promoted to HOT as though the load had succeeded. The zone would appear active in the UI with no chunks actually held in memory. Cancelled loads now correctly suppress the HOT promotion.

- **BUG FIX — Shutdown errors stalled background tasks for up to five seconds each**: A specific engine error thrown when the server begins shutting down was being caught in the wrong place, leaving background chunk tasks waiting for a timeout that would never resolve. The error is now caught at the right point and tasks complete immediately when the server is closing.

- **BUG FIX — Post-load work ran on a shared JVM thread pool instead of the plugin's own**: Two internal task chains were accidentally running on the JVM's general-purpose pool rather than the pool managed by the plugin. This bypassed the plugin's own load controls and could compete with unrelated JVM work. Both now use the plugin's thread pool as intended.

- **BUG FIX — Keepalive incorrectly reported HOT chunks as still loaded after eviction**: The keepalive check was using a method that loads a chunk if it is missing, so it never actually detected eviction — a missing chunk would simply be reloaded and reported as present. The check now correctly detects whether the chunk is already in memory without triggering a load. Zones whose chunks have been evicted are demoted from HOT to WARM to reflect their actual state.

- **BUG FIX — Releasing a large zone stalled background tasks during COLD decay**: When a zone transitioned to COLD and its chunks were released, the release operation was blocking a background thread once per chunk while it waited for confirmation from the world thread. On zones with many chunks this froze the plugin's background work for a noticeable period. Chunk release is now fire-and-forget and does not block.

- **BUG FIX — Zone shown as HOT in the UI without any chunks actually loaded**: In certain conditions — such as when a zone's initial load had previously failed — proximity triggers and portal discovery events would mark a zone HOT based on player position alone without verifying that its chunks were in memory. The tier is now only promoted if the zone has confirmed loaded chunks. If no chunks are loaded, the zone load is retried instead.

- **BUG FIX — Chunks shared between two zones could be evicted while one zone was still active**: When two zones overlapped and shared chunks, those chunks could become eligible for eviction as soon as either zone became inactive, even if the other zone was still HOT or WARM. Each zone now independently holds its own memory pin on the chunks it owns. A shared chunk stays in memory as long as any zone that owns it remains active, and is only released when the last owning zone goes inactive.

- **BUG FIX — PRED zone RAM and preload count always showed `--` in the zone table**: After a predictive load completed, the internal callback in ChunkPreloader called `updateEntryStats` but never saved the result to storage, so the updated values were discarded on every load. Additionally, when all chunks in a zone's footprint were already resident and shared with another zone, the stats were recorded against zero chunks rather than the full footprint, writing a RAM value of zero. Zones whose HOT tier was restored from the registry on server startup — where no player had approached them since restart — were never updated at all. RAM and preload count are now correctly recorded for all predictive loads regardless of chunk sharing or restart history.

### Changed

- **IMPROVEMENT — JSON storage no longer blocks reads during a write**: When a portal entry was saved or deleted, the JSON backend held its internal lock for the entire duration of the disk write — including JSON serialisation, fsync, backup copy, and atomic rename. Any concurrent read such as a zone lookup at startup or a config reload would stall until the write finished. The lock is now released immediately after updating the in-memory map; the disk write runs from a snapshot taken under the lock.

- **IMPROVEMENT — Velocity-boost log line no longer allocates when fine logging is disabled**: The debug message logged when a player's movement speed triggers a larger preload radius was built unconditionally using string concatenation and number formatting, even in production where fine-level logging is off. It is now built lazily and only evaluated if the log level is active.

- **IMPROVEMENT — TPS reading is now more accurate and no longer misreports servers above 20 TPS**: The server performance monitor previously capped its reading at 20 TPS and sampled less frequently, causing it to miss brief lag spikes and incorrectly show 20 TPS on servers that were running faster. Sampling is now more frequent, smoothed to reduce noise, and the cap is raised so above-20 readings are preserved. Readings distorted by garbage collection pauses are discarded rather than published. OptiPortal has never limited server TPS and the internal naming has been updated to make this clearer.

- **IMPROVEMENT — Zone cache UI no longer shows a redundant RAM column**: The EST RAM and ACTUAL RAM columns always displayed identical values because the estimated and measured formulas resolved to the same number. The duplicate column has been removed and the remaining column is labelled RAM.

---

## [1.1.6] - 2026-03-24

### Fixed

- **BUG FIX — Keepalive pinged overlapping chunks multiple times per cycle**: When two zones in the same world had overlapping chunk areas, the keepalive built its ping list per-zone without cross-zone deduplication. Each shared chunk coordinate appeared multiple times, producing redundant async chunk requests every keepalive cycle. Overlapping chunks are now deduplicated before the ping list is dispatched.

---

## [1.1.5] - 2026-03-23

### Fixed

- **BUG FIX — Chunk preloading aborted immediately on server startup**: The JVM heap guard used the wrong formula, causing it to see near-maximum heap usage even when the server had just started and the JVM had not yet expanded its heap. Preloading would abort before any zones were ever loaded. The guard now correctly measures actual used heap against the configured maximum.

- **BUG FIX — Enhanced preloader loaded duplicate chunks already owned by other zones**: The enhanced predictive and warm load paths skipped the deduplication check present in the base preloader, causing chunks already claimed by another zone to be requested from the world again. The enhanced paths now skip chunks that are already owned and only load genuinely new ones.

- **BUG FIX — Tier registry leaked stale entries after TTL eviction**: When the TTL enforcer removed an expired zone, it released the zone's chunk pins and deleted it from storage but did not remove its entry from the in-memory tier registry. The stale tier entry persisted indefinitely, causing the zone to appear as COLD in status output and interfering with tier-aware logic even though the zone no longer existed.

- **BUG FIX — Deduplication metric reported newly-loaded chunks instead of skipped ones**: The chunk deduplication counter tracked chunks that were loaded for the first time rather than chunks that were skipped because they were already owned. The metric now correctly counts chunks that were deduplicated away.

- **BUG FIX — Cache manager metrics were siloed from the rest of the plugin**: The cache manager created its own isolated metrics collector instead of sharing the one owned by the plugin. Metrics recorded inside the cache manager were never visible to the plugin's reporting and bStats integration.

- **BUG FIX — Portal link confidence counters could be corrupted by a concurrent decay sweep**: The pending-link decay task ran on a background thread without holding the lock used by the link-recording path. A concurrent write to a pending link's observation count or timestamp while the decay task was reading those fields was a data race under the Java Memory Model. The fields are now declared volatile so reads by the decay task always see the latest written values.

- **BUG FIX — JVM errors during chunk loading triggered recovery logic**: When a non-recoverable JVM `Error` (such as `OutOfMemoryError`) occurred during chunk loading, it was wrapped in a generic `Exception` and passed to the error handler, which would attempt retry and recovery logic — the wrong response under severe JVM stress. `Error` types are now logged directly at SEVERE level and not forwarded to the error handler. Recoverable `Exception` types are unaffected.

---

## [1.1.4] - 2026-03-22

### Fixed

- **BUG FIX — Destination zone not promoted to HOT after same-world portal teleport**: The async TeleportRecord processing path blocked same-world `adventure.teleporter` portals because the destination world field is null for same-world teleports. The current world name is now used as the fallback, so destination zones are promoted to HOT immediately on arrival. Portal link learning (source → destination mapping) was also missing from the async path and is now restored.

- **BUG FIX — Chunk pressure limit too low for servers with permanently-warm zones**: The hard abort threshold for chunk loading defaulted to 512, which could be exceeded by the chunks already held by WARM zones alone. The default is now 2048, and the threshold is documented in config.json under `chunkPressure.maxLoadedThreshold`.
- **BUG FIX — WARM zones dropped to COLD**: Zones set to the WARM (never-expire) strategy were still subject to normal tier decay and chunk eviction downgrade logic, causing them to drop to COLD and lose their loaded chunks between player visits. WARM zones now hold a minimum tier of WARM — they decay from HOT to WARM normally when not in active use, but are protected from dropping to COLD through both the periodic decay cycle and the chunk eviction path.

- **BUG FIX — Destination zone not promoted to HOT when approaching a same-world portal**: Walking toward a location-to-location portal did not promote the destination zone from WARM to HOT before the player arrived. The proximity check only fired when the player was already near the destination position, which is too late. The plugin now detects teleports by observing sudden position changes in the async position poll, learns the source portal position after the first use, and promotes the destination to HOT on all subsequent approaches.

- **BUG FIX — Portal destination world link lost on server restart**: The world a portal device linked to was not saved when the zone was first registered, so the connection was forgotten on every restart and had to be rediscovered at runtime. The destination is now persisted immediately on registration.

- **BUG FIX — Destination zone not promoted to HOT when approaching a portal with an unresolved link**: If the destination link for a portal had not yet been confirmed — for example on first login after a fresh install — the approach check skipped HOT promotion entirely. The link is now backfilled from the live portal block the first time the surrounding chunk loads, and the approach check falls back to promoting all known destination zones while the link is still being resolved.

---

## [1.1.3] - 2026-03-21

### Fixed

- **BUG FIX — Portal destination world lost after server restart**: The world a portal linked to was only tracked in memory. On every restart that mapping was wiped, forcing the plugin to fall back to a slower name-based lookup until players walked through the portal again and the link was rediscovered. The destination world is now persisted to the database and restored on startup.

- **BUG FIX — Per-zone activation overrides lost after server restart**: Custom activation distances, trigger shapes, floor/ceiling checks, and facing requirements configured per zone via `/preload activation` or `/preload shape` were stored in memory only. They appeared to apply correctly during a session but were silently reset to defaults on every restart. These settings are now saved and restored correctly.

- **BUG FIX — Storage left in inconsistent state after a failed bulk save**: When saving multiple zones at once, a failure partway through committed the zones processed before the error and silently dropped the rest. After a crash or constraint violation during a bulk operation, some zones would reflect their new state while others were rolled back to an older snapshot with no indication that anything was wrong. Bulk saves are now wrapped in a transaction that rolls back fully on any error.

- **BUG FIX — Cooldown map grew unboundedly for anonymous zone activations**: A ghost entry was inserted into the teleport cooldown map whenever a zone activation occurred without a traceable player — for example, from scripted or server-side triggers. This entry was never associated with a real player and was never cleaned up, causing the map to accumulate one entry per affected zone indefinitely. Anonymous activations are now ignored cleanly.

- **BUG FIX — Chunks could be permanently locked in memory under concurrent load**: When two zone loads completed at the same moment for the same chunk, both could race to claim first-owner status and each pin the chunk independently. Because the chunk only needed one pin but received two, it required two releases before the engine was allowed to unload it — meaning it could never be evicted until both owning zones were fully torn down. The first-owner check is now atomic.

- **BUG FIX — Load balancer performance metric drifted under concurrent completions (regression from v1.1.2)**: The v1.1.2 fix serialized writes to the async execution-time average but left reads unprotected. Under concurrent task completions the read could race the write, producing a stale or partially-updated value. Reads are now covered by the same lock as writes.

- **BUG FIX — Keepalive scans could run back-to-back if a scan took longer than its interval**: Keepalive pings for HOT, WARM, and COLD zones were scheduled on a fixed wall-clock interval. If a scan took longer than its configured interval to complete, the next scan was queued to start immediately on completion rather than waiting the full interval again. On a loaded server this caused consecutive scans with no gap, adding unnecessary pressure. The scheduler now always waits the full interval after a scan finishes before starting the next.

- **BUG FIX — JSON portal data file could be corrupted on an unclean shutdown**: When saving portal data, content was written to a temporary file and then renamed into place — but the file was not flushed to disk before the rename. On an unclean shutdown, the OS could complete the rename while the write was still buffered, producing an empty or truncated data file after recovery. The file is now synced to disk before the rename takes effect.

- **BUG FIX — Portal detection scans could overlap on busy servers**: The periodic scan that detects players walking through portals was scheduled to fire at a fixed rate. On servers with many online players the scan could take longer than its one-second interval, causing the next scan to start immediately when the previous finished rather than waiting. Under sustained load this caused overlapping scans that crowded out other background tasks. The scheduler now waits the full interval between scans.

- **BUG FIX — In-flight tasks abandoned on plugin shutdown or reload**: When the plugin shut down, background tasks that were already mid-execution — chunk preloads, keepalive pings, storage writes — were cut off immediately rather than being allowed to finish. This could leave the cache registry or storage in a partially-updated state. Shutdown now waits up to ten seconds for running tasks to complete before forcing a stop.

- **BUG FIX — Memory overhead accumulated on servers that frequently load and unload worlds**: Each time a world's pending operation queue was fully processed, its empty entry was left in the queue registry rather than removed. On servers that regularly create and destroy worlds this caused the registry to grow with dead entries that were visited on every processing cycle but did no work.

- **BUG FIX — Predictive zones showed as Unvisited after a world reload**: When a world was removed and re-created — for example during a world reload or dynamic world cycle — all zones belonging to that world were fully erased from the tier registry. Warm zones recovered automatically when the world came back because the portal scanner re-registered them on load. Predictive zones had no equivalent recovery path, so they stayed absent from the registry and reported Unvisited until a player approached them again and triggered a fresh load. Predictive zones now retain a Cold tier entry when their world is removed, matching the behaviour of all other tiers on world cycle.

- **BUG FIX — Chunks could remain pinned after a zone was removed by radius**: When a zone was deregistered using a radius-based removal, the chunks it exclusively owned were correctly removed from the ownership registry but their in-memory pin was never released. The engine was not told the chunks no longer needed to stay loaded, so they remained resident longer than necessary. The pin is now released correctly alongside the ownership record.

## [1.1.2] - 2026-03-21

### Fixed

- **BUG FIX — Cold zones lost after server restart**: Zones that had fully decayed to COLD while the server was running were saved correctly, but on the next startup they were silently dropped from the tier registry instead of being restored. After a restart, these zones were treated as unregistered, causing them to miss COLD→WARM promotion and tier-based diagnostics until they were explicitly reloaded or a player triggered them.

- **BUG FIX — Chunk ownership registered twice for deduped chunks**: When a zone's preload pass encountered a chunk that was already owned by another zone, ownership was claimed for the new zone immediately in the dedup check — and then claimed a second time after the load completed. This produced duplicate ownership records, inflated ref counts in the chunk registry, and could interfere with correct eviction protection.

- **BUG FIX — Circuit breaker transition was not thread-safe**: The OPEN→HALF_OPEN state transition checked the elapsed time and then wrote the new state in two separate operations. Under concurrent access, multiple threads could pass the time check simultaneously, each independently resetting the circuit breaker and the success counter, making recovery unreliable under load.

- **BUG FIX — World thread error handler and bridge used separate circuit breakers**: The async error handler and the world thread bridge each created their own independent circuit breaker instance. Failures recorded by the error handler had no effect on the bridge's breaker, and vice versa — the breaker never opened even when the error threshold was repeatedly exceeded.

- **BUG FIX — World lookup for player unnecessarily scanned all worlds**: Resolving which world a player was in iterated every loaded world and queried each one's entity store until a match was found. This O(N) scan runs on every position update cycle; it is now replaced with a direct O(1) lookup using the world identity already stored on the player reference.

- **BUG FIX — Chunk preload batches all dispatched simultaneously**: When loading chunks for a zone, all batches were submitted at once and allowed to run in parallel. This could produce a large burst of concurrent world thread requests, negating the purpose of batching. Batches are now chained sequentially so each one starts only after the previous completes.

- **BUG FIX — Same players always selected for position update batch**: The player batch selector iterated the player map from the beginning on every cycle, meaning the same players at the front of the map were always included and players further down were consistently skipped. A round-robin cursor now advances each cycle, giving all online players equal update frequency.

- **BUG FIX — WAL atomic write not truly crash-safe**: The write-ahead log wrote content to a temporary file and renamed it into place, but did not flush the file to disk before renaming. On an unclean shutdown, the rename could complete while the file content was still in the OS write buffer, leaving a zero-byte or partial file visible after recovery. The file is now fsynced before the rename.

- **BUG FIX — Null player ID created a permanent ghost cooldown entry**: If a cooldown check or record was called with a null player ID, a substitute sentinel UUID was inserted into the cooldown map. This entry accumulated indefinitely, was never associated with a real player, and could not be evicted by normal player lifecycle events. Null player IDs are now rejected early and treated as not on cooldown.

- **BUG FIX — First operation attempt logged at INFO level**: Every operation submitted through the retry policy produced an INFO log line on the first attempt, regardless of whether it succeeded. On busy servers this generated continuous noise for routine operations. First attempts are now logged at FINE; only retries (second attempt and beyond) are logged at INFO.

- **BUG FIX — Execution time average subject to race condition**: The exponential moving average used to track async execution time was updated with a non-atomic read-modify-write on a volatile field. Concurrent completions could read the same stale value, each apply their sample independently, and one overwrite the other — making the average drift under load. Updates are now serialized through a dedicated lock.

---

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
