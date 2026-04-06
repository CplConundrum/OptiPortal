# Technical Changelog

Developer-facing notes. Each entry documents the root cause, affected code paths, engine API
constraints, and threading considerations behind the corresponding changelog fix. Entries
mirror the version numbers in `changelog.md`.

---

## [1.2.1] - 2026-04-04

---

### `OptiPortal` lifecycle now runs through the engine-owned `start()` / `shutdown()` hooks

**Files:** `OptiPortal.java`

**Root cause:**
The plugin previously overrode `start0()` directly and defined a dead no-arg `shutdown0()`
overload. Decompiled engine source shows that `PluginBase.start0()` and
`PluginBase.shutdown0(boolean)` own state transitions and call `start()` / `shutdown()`
internally. By replacing the outer lifecycle methods instead of the inner hooks, OptiPortal
was bypassing the normal plugin-state path.

**Fix:**
- Moved startup logic into `protected void start()`
- Moved shutdown entry into `protected void shutdown()`
- Kept `doShutdown()` as the shared shutdown helper

**Decompiled comparison:**
- `decompiled/com/hypixel/hytale/server/core/plugin/PluginBase.java`

**Threading:** No new concurrency behavior. This restores the intended engine lifecycle path.

---

### `/preload status` now degrades safely when the async subsystem is inactive

**Files:** `PreloadCommand.java`, `OptiPortal.java`

**Root cause:**
`OptiPortal` intentionally returns `null` for the dormant async getters on the live base
runtime, but `/preload status` dereferenced those getters directly, causing a command-path NPE.

**Fix:**
- `handleStatus()` now reads async getters into locals first
- circuit breaker and load balancer sections now print an explicit “async infrastructure inactive”
  message when those services are unavailable
- base runtime information such as loaded chunk counts and cache-tier counts still renders

**Threading:** None. This is command-surface null-safety only.

---

### Plugin event listeners not unregistered on shutdown

**Files:** `OptiPortal.java`

**Root cause:**
`PluginBase.cleanup(boolean)` calls `this.eventRegistry.shutdown()`. Decompiled engine source
confirms that `Registry.shutdown()` only sets `enabled = false` — it does not iterate
registrations and does not call `unregister()` on any of them. Plugin-scoped listeners
registered via `getEventRegistry().register*(...)` were therefore not provably collectible
after plugin shutdown, only inert if every handler individually checks a shutdown flag.

**Fix:**
`OptiPortal.doShutdown()` now calls `getEventRegistry().shutdownAndCleanup(true)` after
stopping all local components (so their stopped flags are set before listeners are torn down).
`Registry.shutdownAndCleanup(boolean)` iterates registrations in reverse order, calls each
one, then clears the list — the same path the engine uses for its own owned registries.

**Threading:** Called on the shutdown thread after `warmZoneManager.stop()`,
`teleportInterceptor.stop()`, and all other component stops complete. No concurrent event
dispatch can occur after `shutdownAndCleanup` returns because the registry is then disabled
and empty.

---

### `WarmZoneManager` retained by the universe-ready callback after shutdown

**Files:** `WarmZoneManager.java`

**Root cause:**
`startStagedLoad()` called `Universe.getUniverseReady().thenRunAsync(this::triggerStagedLoadOnce, executor)`.
The returned `CompletableFuture<Void>` was not stored. If `stop()` was called before the
universe-ready future resolved (e.g. rapid server shutdown during startup), the pending stage
still held a reference to `this::triggerStagedLoadOnce`, preventing the `WarmZoneManager`
instance from being collected. Additionally, if the universe-ready future completed
exceptionally, the fallback called `startPollingFallback()` unconditionally, starting a poll
loop even if the plugin had already shut down.

**Fix:**
- Added `volatile CompletableFuture<Void> universeReadyTask` field.
- `startStagedLoad()` now returns early if `stopped.get()`, stores the direct
  `thenRunAsync(...)` stage in `universeReadyTask`, and attaches `exceptionally(...)` to that
  same stage without overwriting the field with the wrapper future.
- The `exceptionally` fallback checks `!stopped.get()` before calling `startPollingFallback()`.
- `stop()` calls `universeReadyTask.cancel(false)` (best-effort) and nulls the field.

**Note:** Cancellation of a `CompletableFuture` downstream stage does not cancel the upstream
engine future. The `stopped` guards on all callback entrypoints remain mandatory and are the
primary safety mechanism. The cancellation is belt-and-suspenders to allow the instance to be
released sooner.

**Threading:** `universeReadyTask` is `volatile`. The `stop()` method uses
`stopped.compareAndSet(false, true)` to ensure only one caller performs the cancel-and-null
sequence. A racing `startStagedLoad()` that passes the initial `stopped.get()` check may still
assign to `universeReadyTask` after `stop()` has nulled it, but the assigned future will
immediately be inert because `triggerStagedLoadOnce` checks `stopped` at entry.

---

### `CacheManager` empty-bucket race when pruning per-world entries

**Files:** `CacheManager.java`

**Root cause:**
After a zone's last chunk was removed from a world's bucket, prune sites called
`chunkOwnership.remove(worldName)` — the single-argument overload. This is not atomic with
respect to the preceding `isEmpty()` check. A concurrent `registerOwnership` call on the same
world can interleave: it sees the bucket, adds a new entry, then the prune thread calls
`remove(worldName)` and discards the newly populated bucket entirely. The lost entries would
never be re-added until the next `registerOwnership` for an existing chunk, leaving
`getOwnedChunkCount` understated and potentially confusing keepalive logic.

**Fix:**
All prune sites capture the local `worldMap` reference before the `isEmpty()` check and
replace `remove(worldName)` with `remove(worldName, worldMap)`. `ConcurrentHashMap.remove(key, value)`
is a CAS operation: it only removes the entry if the value reference still equals `worldMap`.
If another thread has already replaced the bucket with a new map, the CAS fails and the new
bucket is preserved. Affected methods: `releaseZoneChunks`, `deregisterOwnership`,
`deregisterAllChunks`, `onChunkEvicted`, `onChunksEvicted`.

**Threading:** `ConcurrentHashMap.remove(key, value)` is documented as atomic. The pattern
`local = map.get(key); if (local.isEmpty()) map.remove(key, local)` is the standard
compare-and-remove idiom for concurrent maps.

---

### `TeleportInterceptor` executor lambdas mutated plugin state after shutdown

**Files:** `TeleportInterceptor.java`

**Root cause:**
`onDrainPlayerFromWorld` and `onPlayerConnect` checked `plugin.isShuttingDown()` on the
event thread before dispatching work to the executor. However, the lambdas submitted to
`executor.execute(...)` did not re-check shutdown state before running. If shutdown began
after the event-thread check passed but before the executor ran the lambda, the lambda would
still execute: `onDrainPlayerFromWorld` would write to `lastKnownPosition` and call
`lingerOriginZone`, and `onPlayerConnect` would call `chunkPreloader.predictiveLoad` —
both against plugin-state that is being torn down concurrently.

**Fix:**
Both executor lambdas now check `if (plugin.isShuttingDown()) return;` as their first
statement. This matches the pattern already used by the position-poll lambdas at lines 386,
432, and 461.

**Threading:** `isShuttingDown()` reads a `volatile boolean` (or `AtomicBoolean.get()`).
The check-then-act window is narrow and acceptable: if it races with shutdown completing,
the worst outcome is one additional write to a ConcurrentHashMap that will be discarded when
the executor is shut down immediately after.

---

### `CacheManager` per-chunk owner cleanup could drop a live owner set

**Files:** `CacheManager.java`

**Root cause:**
After the world-bucket CAS fix, three hot deregistration paths still used the pattern
`if (owners.isEmpty()) worldMap.remove(key, owners)`. The `owners` set is mutable and shared.
If another thread re-added a zone ID to that same set after the emptiness check but before
`remove(key, owners)`, the compare-and-remove could still succeed because the value reference
was the same object. The newly re-populated owner set would be discarded entirely, dropping a
live chunk ownership record.

**Fix:**
Replaced the "check empty, then remove" pattern with `ConcurrentHashMap.computeIfPresent(...)`
in `releaseZoneChunks`, `deregisterOwnership`, and `deregisterAllChunks`. Each callback now:
- removes the current zone ID from the owner set
- releases the keep-loaded pin only if that zone actually owned the chunk
- returns `null` only if the set is still empty at the end of the atomic map callback

This keeps the emptiness check and entry removal inside one map operation.

**Threading:** `computeIfPresent(...)` runs atomically for the targeted key in
`ConcurrentHashMap`. Another thread can still race by adding a new owner after the callback
returns, but it will add into a fresh entry rather than being silently removed by a stale
post-check.

---

### Post-load completions could mutate cache/storage after shutdown started

**Files:** `ChunkPreloader.java`, `EnhancedChunkPreloader.java`, `TeleportInterceptor.java`,
`WarmZoneManager.java`, `OptiPortal.java`

**Root cause:**
Several async completion stages (`thenRunAsync`, `thenAcceptAsync`) touched cache tier,
zone statistics, and storage after the initial load futures completed. These stages run on the
plugin executor and can lag behind the event that scheduled them. Before this fix, shutdown
could begin after the load itself was dispatched but before the completion callback ran. The
callback would still promote HOT/WARM state or call `storage.save(...)` against an instance
that was in the middle of shutdown.

`ChunkPreloader` and `EnhancedChunkPreloader` had no shared shutdown predicate, so there was no
cheap way for the completion stage to know the plugin had started tearing down. `TeleportInterceptor`
and `WarmZoneManager` had direct access to shutdown state but were not checking it consistently
inside late async callbacks.

**Fix:**
- Added a `BooleanSupplier shuttingDown` to `ChunkPreloader`, plus `protected boolean isShuttingDown()`.
- `OptiPortal` now constructs the active `ChunkPreloader` with `this::isShuttingDown`.
- `ChunkPreloader` guards its predictive/warm post-load `thenRunAsync(...)` blocks before
  promoting tiers or saving entry stats.
- `EnhancedChunkPreloader` uses the inherited predicate in its own post-load completion.
- `TeleportInterceptor.predictiveLoadWithRam(...)` and `WarmZoneManager.loadWarmZone(...)`
  now bail out early if shutdown has started before their async completion work runs.

**Threading:** The shutdown predicate reads plugin state only; it does not block. Once
`OptiPortal.shuttingDown` flips true, all guarded callbacks become inert even if they were
already queued before executor shutdown.

---

### Legacy `EnhancedChunkPreloader` constructor path did not inherit shutdown awareness

**Files:** `EnhancedChunkPreloader.java`

**Root cause:**
The enhanced preloader gained shutdown-aware post-load guards through the new
`BooleanSupplier` constructor overload, but the long-standing convenience constructor still
delegated to the superclass default `() -> false`. If a caller instantiated
`EnhancedChunkPreloader` through the old constructor surface, `isShuttingDown()` inside the
subclass always returned false and the new guards were effectively dead code.

There was also an overload-resolution edge case: after adding both `CorridorIndex` and
`BooleanSupplier` convenience constructors, a `null` delegation target became ambiguous to the
compiler.

**Fix:**
- Added `pluginShuttingDownOrFalse()` which reads `OptiPortal.getInstance()` and returns
  `plugin.isShuttingDown()` when available.
- Updated the legacy constructor path to delegate through the supplier-aware overload using
  `EnhancedChunkPreloader::pluginShuttingDownOrFalse`.
- Disambiguated the convenience delegation with `(CorridorIndex) null` where required so Java
  selects the intended overload deterministically.

**Threading:** `OptiPortal.getInstance()` is read-only here and shutdown state is stored in an
`AtomicBoolean`, so the fallback supplier is safe to call from any executor callback.

---

### Shutdown could close storage before async callbacks had fully quiesced

**Files:** `OptiPortal.java`

**Root cause:**
`storage.close()` previously ran before executor shutdown had fully drained. That is harmless
for pure in-memory state, but not for real backends: `AbstractSqlStorageBackend.close()`
shuts down `HikariDataSource`, and the JSON backend tears down its in-memory snapshot state.
Any late async callback still calling `storage.loadById(...)` or `storage.save(...)` could then
race a closed datasource or file backend.

The first pass moved executor shutdown ahead of `storage.close()`, but the timeout path still
needed to be explicit: `shutdownNow()` interrupts queued/running tasks, yet does not guarantee
they have already exited by the time control returns.

**Fix:**
- `OptiPortal.doShutdown()` now stops local components, flushes registries, unregisters plugin
  listeners, then shuts down the executor before closing storage.
- If the first `awaitTermination(...)` times out, shutdown now calls `shutdownNow()` and waits
  a second time before closing storage.
- If tasks still remain after that second wait, OptiPortal logs that it is proceeding while
  relying on the new shutdown guards to keep any straggler callbacks inert.

**Threading:** This does not make a hard real-time guarantee that every task has exited before
storage close, but it narrows the window substantially and pairs the timeout path with explicit
guarded callback behavior rather than silent best-effort shutdown.

---

### `PortalChunkListener` status corrected: implemented but still dormant

**Files:** `OptiPortal.java`, `PortalChunkListener.java`, `PreloadCommand.java`,
`OptiPortalUIPage.java`

**Root cause:**
During earlier review passes, `PortalChunkListener` was mistakenly treated as active because the
class exists and contains cleanup logic. In the actual startup path, no instance is constructed
and `OptiPortal.getPortalChunkListener()` still returns `null`. That meant any claims about
listener-lifecycle fixes inside this class being active in production were overstated.

**Fix:**
Documented the dormant status directly in `OptiPortal` and kept the nullable getter behavior
explicit. Command/UI call sites already tolerate `null`, so the documentation fix brings the
maintenance notes back in sync with reality without changing runtime wiring.

**Threading:** None. This is a code-state/documentation correction so future maintenance work
does not assume an inactive listener is participating in runtime cleanup.

---

### `ZoneTtlEnforcer` is now part of the live plugin lifecycle

**Files:** `OptiPortal.java`, `ZoneTtlEnforcer.java`

**Root cause:**
TTL policy and per-zone TTL data were implemented, but the enforcement scheduler itself was not
instantiated from the live startup path. That meant TTL configuration existed without any active
cleanup loop.

**Fix:**
- `OptiPortal.start()` now constructs and starts `ZoneTtlEnforcer`
- `doShutdown()` stops it
- `reloadConfig()` reschedules it with the updated interval

**Threading:** The enforcer runs on the shared plugin executor and keeps a `ScheduledFuture`
handle so start/stop/reschedule all operate cleanly on the live runtime path.

---

### TTL-expired zones now receive the same runtime invalidation shape as manual delete

**Files:** `ZoneTtlEnforcer.java`, `OptiPortal.java`, `PreloadCommand.java`

**Root cause:**
Once TTL enforcement was activated, its delete path only:

- released zone chunks
- removed tier state
- deleted from storage

The manual `/preload delete` path also removed learned portal links and invalidated the
teleport interceptor’s in-memory portal cache. Without that parity, TTL-expired portals could
linger in runtime-only structures after their storage entry was gone.

**Fix:**
- added a nullable deletion callback to `ZoneTtlEnforcer`
- invoke that callback inline after successful storage deletion
- `OptiPortal` wires the callback to:
  - `portalLinkRegistry.removeLink(id)`
  - `teleportInterceptor.onPortalDeleted(id)`
  - `teleportInterceptor.refreshPortalCache()`
- the callback uses local captures and null checks so it tolerates startup/shutdown ordering

**Threading:** Callback invocation is inline and wrapped in a local `try/catch`, so failures are
logged without killing future TTL cleanup runs.

---

### Live metrics no longer fragment across multiple `MetricsCollector` instances

**Files:** `OptiPortal.java`, `CacheManager.java`, `ChunkPreloader.java`, `bStatsIntegration.java`

**Root cause:**
The live runtime previously constructed separate collectors for:

- the plugin-owned metrics path
- `ChunkPreloader`
- and, depending on constructor path, `CacheManager`

That fragmented observability data and made the plugin-facing collector incomplete.

**Fix:**
- `OptiPortal.start()` now creates the shared `metricsCollector` before constructing active
  components that accept one
- that same instance is passed to the live `CacheManager`, `ChunkPreloader`, and `bStatsIntegration`

**Threading:** No behavioral change beyond consolidating counter ownership onto one shared
runtime object.

---

### `UpdateChecker` now reads the current plugin version from engine manifest metadata

**Files:** `UpdateChecker.java`

**Root cause:**
`UpdateChecker.getCurrentVersion()` manually reopened `manifest.json` from the classloader even
though the plugin base already exposes manifest metadata through `plugin.getManifest()`.

Decompiled engine source confirms `PluginBase` carries the parsed manifest and exposes it via
`getManifest()`.

**Fix:**
- replaced the manual manifest resource read with:
  - `plugin.getManifest()`
  - `plugin.getManifest().getVersion()`

**Decompiled comparison:**
- `decompiled/com/hypixel/hytale/server/core/plugin/PluginBase.java`

**Threading:** None. This is a cleaner metadata read path on the update-check worker.

---

### Dormant scheduled async components now carry clearer activation warnings

**Files:** `WorldTpsMonitor.java`, `AsyncLoadBalancer.java`, `ChunkOwnershipAuditor.java`,
`OptiPortal.java`

**Root cause:**
The active scheduled components are now mostly lifecycle-safe, but several dormant async classes
still schedule recurring work without being fully wired into the live shutdown path. Because
decompiled `PluginBase.cleanup(boolean)` does not auto-cancel arbitrary tasks scheduled onto a
plugin-owned executor, future activation of these classes still requires explicit lifecycle
ownership.

**Fix:**
- tightened dormant/live documentation in `OptiPortal`
- added comments to dormant scheduled classes noting that explicit cancellation wiring is
  required before activation

**Decompiled comparison:**
- `decompiled/com/hypixel/hytale/server/core/plugin/PluginBase.java`

**Threading:** No runtime behavior change. This is preventive documentation so future activation
work does not assume executor shutdown alone is sufficient.

---

### Teleport poll interval default was too aggressive for the base polling model

**Files:** `PluginConfig.java`, `TeleportInterceptor.java`

**Root cause:**
The live runtime still uses the base `TeleportInterceptor`, which polls `TeleportRecord` on a
fixed cadence and performs one `world.execute(...)` per cached online player each cycle. The
default `pollIntervalSeconds` value was `1`, so even quiet servers paid this constant polling
cost once per second per player. That was a reasonable safety-first default during feature
bring-up, but it kept baseline CPU higher than necessary once the polling path grew additional
proximity and hotspot logic.

The default-config generator also did not explicitly emit `decay.pollIntervalSeconds`, so new
installs silently inherited the in-code default instead of declaring the intended polling
cadence in `config.json`.

**Fix:**
- Changed the in-code default `pollIntervalSeconds` from `1` to `2`
- Added an explicit `decay` block to generated default config with:
  - `hotDecaySeconds`
  - `warmDecayMinutes`
  - `pollIntervalSeconds = 2`

**Threading:** No concurrency semantics changed. This reduces scheduler frequency only; the
underlying polling model, task ownership, and world-thread access pattern remain unchanged.

---

### `TeleportInterceptor` was rescanning portals from unrelated worlds on the hot path

**Files:** `TeleportInterceptor.java`

**Root cause:**
The hot helper methods in `TeleportInterceptor` iterated the full `portalCache` and then
filtered by world name inside the loop. On multi-world servers this meant every proximity
check, nearest-portal lookup, reverse preload lookup, and same-world jump scan walked portals
from unrelated worlds only to discard them immediately.

This wasted CPU in the exact path already identified as the strongest baseline suspect:

- `checkProximityAndPreload(...)`
- `reversePreloadOrigin(...)`
- `findNearestPortal(...)`
- `lingerOriginZone(...)`
- same-world jump detection inside `pollTeleportRecords()`

The cost scaled with total portal count across the server rather than the number of portals in
the relevant world.

**Fix:**
- Added `volatile Map<String, List<PortalEntry>> portalCacheByWorld`
- Reworked `refreshPortalCache()` to rebuild both:
  - the full immutable portal snapshot
  - immutable per-world portal lists
- Added `getPortalEntriesForWorld(worldName)` helper
- Switched world-specific hot scans to iterate the world-local list instead of the full cache
- Updated `onPortalDeleted(...)` and `stop()` to keep both caches consistent

**Threading:** The caches are published as immutable snapshots through volatile fields. Readers
either see the old complete snapshot or the new complete snapshot; no reader can observe a
partially built world index.

---

### Movement-based proximity scans were still too eager for lightly moving players

**Files:** `TeleportInterceptor.java`

**Root cause:**
The first polling optimization only added a movement threshold: if the player moved about one
block total since the last successful proximity check, the poller ran another full portal scan.
On real servers, players often make frequent small adjustments while standing near a build,
inventory, teleporter, or spawn area. That meant the poller could still rescan portals on many
successive poll cycles even when the player's movement was not meaningful enough to justify
continuous proximity work.

**Fix:**
- Added `lastProximityCheckMs`
- `shouldRunProximityCheck(...)` now uses two tiers:
  - larger moves trigger immediately
  - smaller moves require both:
    - at least modest movement
    - a short wall-clock gap since the last successful proximity scan
- Wired the new timestamp map into `stop()` and `removePlayerRef(...)`

This preserves responsiveness for real repositioning while reducing repeated scans from jittery
or low-value movement.

**Threading:** The method reads and writes `ConcurrentHashMap` state only. It is called from the
polling path on the executor side and does not add any world-thread work or blocking.

---

## [1.2.0] - 2026-03-29

---

### Per-zone pin model not applied to releaseZoneChunks

**File:** `CacheManager.java`

**Root cause:**
`deregisterAllChunks` was updated to per-zone pinning: each zone holds its own independent
`addKeepLoaded` pin and must call `tryReleaseKeepLoaded` when it deregisters, regardless of
whether other zones still own the same chunk. `releaseZoneChunks` — the path used when a zone
is explicitly deleted — was not updated to match. It called `owners.remove(zoneId)` without
capturing the return value, then only called `tryReleaseKeepLoaded` when `owners.isEmpty()`.
This is the old single-pin model. A deleted zone's pin was only released if it happened to be
the last owner, leaving phantom pins that kept chunks in memory past their intended lifetime.
A secondary issue: if a zone was double-deleted, `owners.remove()` would return false (zone
already absent) but `owners.isEmpty()` could be true for unrelated reasons, causing a spurious
`tryReleaseKeepLoaded` on a chunk the zone never owned.

**Fix:**
Capture `boolean removed = owners.remove(zoneId)` and call `tryReleaseKeepLoaded` when
`removed == true`. `worldMap.remove(key, owners)` is unchanged and still fires when the set
becomes empty — these two conditions are now independent.

**Threading:** `ConcurrentHashMap.newKeySet().remove()` is atomic. `worldMap.remove(key, owners)`
is the CAS overload and only removes the entry if the value reference still matches, preventing
accidental removal of a replacement set added by a concurrent `registerOwnership`.

---

### RetryPolicy leaked a thread pool when schedule() threw

**File:** `RetryPolicy.java` — base class and `CustomRetryPolicy` inner class (two identical sites)

**Root cause:**
When no external `ScheduledExecutorService` is provided, the code creates a local one-thread
pool per retry delay. The pool is shut down inside the `whenComplete` callback after the
retried operation finishes. If `schedule()` itself throws (e.g. `RejectedExecutionException`),
execution jumps directly to the catch block, which called `future.completeExceptionally(e)`
and returned. The local pool was never shut down. Repeated retry failures at the scheduling
stage accumulated one abandoned thread per attempt indefinitely.

**Fix:**
Added `defaultExecutor.shutdownNow()` as the first statement in both catch blocks. `shutdownNow`
is used rather than `shutdown` because if `schedule()` threw, no task was ever submitted, so
there is nothing to wait for.

**Threading:** No race with the happy-path `shutdown()` inside `whenComplete` — if `schedule()`
threw, the Runnable was never submitted and `whenComplete` will never fire on this executor.

---

### KeepaliveManager residency check always evaluated to true

**File:** `KeepaliveManager.java`

**Root cause:**
The residency check loop called `world.getNonTickingChunkAsync(chunkIndex)` and compared the
result to `null`. `getNonTickingChunkAsync` returns a `CompletableFuture<WorldChunk>` — the
future object itself is never null (it either resolves to the chunk or to null, but the
reference to the future is always non-null). The `== null` check was therefore always false,
`allChunksPresent` was always true, and the HOT→WARM demotion branch (which fires when any
chunk in the zone is no longer resident) never executed. HOT zones with evicted chunks were
never demoted.

**Fix:**
Replaced the API call with `cacheManager.isChunkOwned(worldName, cx + dx, cz + dz)`.
OptiPortal's ownership map is the correct residency signal: `onChunkEvicted` removes chunks
from the map when the engine evicts them, so `isChunkOwned` returning false means the chunk
is no longer engine-resident. This avoids a world API call entirely and reads only
ConcurrentHashMap state.

**Threading:** `isChunkOwned` reads `chunkOwnership` via ConcurrentHashMap, safe from any
thread. `onChunkEvicted` removes the entry under the assumption that it runs on the world
thread; the two operations do not hold a common lock and a brief window where `isChunkOwned`
returns true for a chunk that is being evicted is acceptable — it results in a missed demotion
on this tick, caught on the next keepalive cycle.

---

### Dead code: totalExecutionTime accumulated but never read

**File:** `AsyncLoadBalancer.java`

**Root cause:**
`totalExecutionTime` (AtomicLong) was incremented on every async task completion. The comment
claimed it fed `LoadStats.totalOperations`, which is incorrect — `totalOperations` is a
separate `AtomicLong` incremented via `totalOperations.incrementAndGet()`. `totalExecutionTime`
had no getter, was not included in `LoadStats`, and was not logged anywhere. It consumed a
CAS operation on every task completion with no observable effect.

**Fix:** Field and usage removed entirely.

---

### storage.loadById() called on every chunk-load event for known zones

**File:** `PortalChunkListener.java`

**Root cause:**
`promoteColdZones()` is invoked from `onChunkPreLoad` for every chunk that loads anywhere in
a registered world. After the reverse-index lookup resolved a zone ID, the code called
`storage.loadById(zoneId)` as a stale-entry guard. `loadById` acquires a `synchronized` lock
on the storage backend's `LinkedHashMap`. On a busy server where many chunks load per second
in portal-zone areas, this lock was acquired on every such event. Additionally, when
`promoteColdZones` needed to persist a destination UUID update, it called `storage.save()`
synchronously, which includes an fsync to disk. This stalled the chunk-load event handler for
the full duration of the disk write.

`autoRegisterPortalDevice()` had the same pattern: `storage.loadById()` to check for an
existing registration, and a synchronous `storage.save()` on the registration path.

**Fix:**
Added an `entryCache` (`ConcurrentHashMap<String, PortalEntry>`) populated at construction
from `storage.loadAll()` and kept in sync on all mutation paths (`autoRegisterPortalDevice`,
`removeFromIndex`). `promoteColdZones` and `autoRegisterPortalDevice` now call
`entryCache.get()` — a single `ConcurrentHashMap.get()` with no lock. All `storage.save()`
calls on the event-handler path are dispatched to a background executor.

**Threading:** `entryCache` is a `ConcurrentHashMap` — all get/put/remove operations are
atomic. Mutations to a `PortalEntry` object (e.g. `setDestinationWorldUuid`) happen on the
event thread before `entryCache.put` makes the updated reference visible; the async
`storage.save` captures the entry reference and reads it on the background thread after the
mutation is complete.

---

### BlockModule.getComponent() scanned on every load of a portal-zone chunk

**File:** `PortalChunkListener.java`

**Root cause:**
Inside `promoteColdZones`, the `BlockModule.getComponent()` call to read the `PortalDevice`
component fired unconditionally for every chunk load that hit a PORTAL-type zone, regardless
of whether the destination UUID was already known. Once the UUID is stored on the `PortalEntry`,
the scan can never produce new information — the destination does not change in normal
operation.

**Fix:**
The `BlockModule.getComponent()` call is now guarded by
`entry.getDestinationWorldUuid() == null`. Once the UUID is recorded (either at first load or
via the async save path), all subsequent chunk-load events skip the block scan entirely.

---

### storage.loadAll() called on every keepalive cycle

**File:** `KeepaliveManager.java`, `JsonStorageBackend.java`, `StorageBackend.java`

**Root cause:**
`pingTierInternal()` called `storage.loadAll()` at the start of every HOT, WARM, and COLD
keepalive tick. `JsonStorageBackend.loadAll()` acquires a `synchronized` lock and copies the
entire `LinkedHashMap` into a new `ArrayList`. The lock contends with any concurrent
`save()` or `loadById()` call for the full copy duration.

**Fix:**
Added `volatile List<PortalEntry> cachedList` to `JsonStorageBackend`, initialised at startup
and updated to `Collections.unmodifiableList(snapshot)` inside every write method (`save`,
`saveAll`, `delete`, `close`) immediately after the snapshot is taken — before the disk flush,
while the snapshot reference is still on the stack. `loadAllCached()` returns the volatile
field directly with no lock and no allocation. `StorageBackend` interface exposes a default
`loadAllCached()` that falls back to `loadAll()` for SQL backends. `KeepaliveManager` uses
`loadAllCached()`.

**Threading:** The volatile write in `save()` (and other mutating methods) is performed after
the `synchronized` block, so the new list is always a complete, consistent snapshot. A reader
calling `loadAllCached()` concurrently with a write may see either the old or new snapshot —
both are valid immutable lists. Missing a single write by one keepalive cycle has no
correctness consequence.

---

### addKeepLoaded submitted one world-thread task per chunk during zone registration

**File:** `CacheManager.java`, `ChunkPreloader.java`

**Root cause:**
When a new zone claimed chunks that were already loaded in memory (the dedup path),
`registerOwnership(zoneId, world, cx, cz)` was called once per chunk. Each call submitted an
independent `world.execute()` task containing a single `chunk.addKeepLoaded()` call. For a
zone with radius 10, up to 441 separate world-thread tasks were enqueued in rapid succession
during registration. Each task involves: queue insertion, world-thread dequeue, a map lookup
in `getChunkIfInMemory`, a counter increment, and GC-visible object allocation for the lambda.

**Fix:**
Added `addKeepLoadedBatchAsync(String worldName, List<Long> engineIndexes)` which pre-filters
chunks via `world.getChunkStore().getChunkReference()` off the world thread, then submits one
`world.execute()` task that loops over the filtered list. Added `registerOwnershipBatch()` as
the public entry point that collects newly-added engine indexes and calls the batch method once.
`ChunkPreloader`'s predictiveLoad and warmLoad dedup paths now collect already-owned chunk
coordinates into a list and call `registerOwnershipBatch` after the loop rather than calling
`registerOwnership` per chunk. `EnhancedChunkPreloader`'s per-chunk async completion callbacks
retain individual calls — collecting across independent futures would require a fan-in
coordinator and the benefit is smaller since those callbacks fire after actual IO, not in a
tight synchronous loop.

**Threading:** `world.getChunkStore().getChunkReference()` is called off the world thread in
the pre-filter step. This is a read-only store probe and matches the same pattern used in the
existing single-chunk `addKeepLoadedAsync`. `getChunkIfInMemory()` inside the world-thread
lambda is the authoritative check.

---

### decayTiersInternal blocked the scheduler thread during bulk chunk release

**File:** `CacheManager.java`

**Root cause:**
When a WARM zone decayed to COLD in `decayTiersInternal()`, `deregisterAllChunks(zoneId)` was
called inline on the `ScheduledExecutorService` thread. `deregisterAllChunks` traverses
`zoneToChunks` and `chunkOwnership` — O(radius²) ConcurrentHashMap operations — and calls
`tryReleaseKeepLoaded` for every chunk, each of which dispatches a `world.execute()` task.
On a server that had been offline for some time with many WARM zones, all zones could decay
simultaneously on the first 10-second check after startup, blocking the scheduler thread for
the full duration of all deregistrations sequentially.

**Fix:**
Changed the call to `executor.submit(() -> deregisterAllChunks(decayedZone))`. The tier state
(`zoneTiers.put(zoneId, CacheTier.COLD)` and `tierTimestamps.remove(zoneId)`) is committed
before the submit, so the next decay check will not re-process this zone. The actual chunk
release runs on a pooled thread.

**Threading:** `deregisterAllChunks` only reads and mutates `chunkOwnership`, `zoneToChunks`,
and calls `tryReleaseKeepLoaded` — none of which are guarded by the same lock as the decay
check. Running it on a pool thread introduces no new contention. Multiple concurrent
deregistrations (one per decaying zone) are safe because `ConcurrentHashMap` operations are
individually atomic and `tryReleaseKeepLoaded` dispatches to the world thread rather than
holding any lock.

---

### CacheManager.setZoneTier() synchronized block caused WorldThread contention

**File:** `CacheManager.java`

**Root cause:**
The fix for the `saveRegistry()` dual-map snapshot race (see below) introduced a
`synchronized(this)` block inside `setZoneTier()`. This method is called from at least 9 hot
paths: every teleport detection in `TeleportInterceptor` and `AsyncTeleportInterceptor`, every
chunk load completion in `ChunkPreloader` and `EnhancedChunkPreloader`, every keepalive
heartbeat, and every `WarmZoneManager.serializeAll()`. All of these previously ran concurrently
on their respective threads. After the change, every caller blocked on the same monitor, turning
concurrent zone tier updates into a serialized queue. WorldThread CPU rose from ~8-10% baseline
to ~25% on startup and ~16-18% steady-state.

**Fix:**
Replaced `synchronized(this)` with a `ReadWriteLock` (`tierLock`). `setZoneTier()` holds
`tierLock.readLock()` — multiple concurrent callers proceed in parallel without blocking each
other, since each call writes to a different key in `ConcurrentHashMap`. `saveRegistry()` holds
`tierLock.writeLock()` — it pauses new writers only for the duration of two in-memory map
copies (~microseconds), then releases. The write lock is never held during the `walManager`
disk write, so IO does not contribute to lock contention.

**Threading note:** The name "read lock for writes" is counterintuitive but correct here. The
`ReadWriteLock` contract is: multiple readers can hold the read lock simultaneously; the write
lock is exclusive. `setZoneTier()` callers are "readers" in the sense that they don't need
mutual exclusion from each other — they only need to exclude `saveRegistry()` from observing a
half-written state. `saveRegistry()` is the "writer" that needs a stable view of both maps.

**Outcome:** WorldThread CPU dropped to ~6-7% steady-state — below the pre-optimization
baseline of ~8-10%, due to the combined effect of this fix and the SQL in-memory cache
eliminating background DB round-trips.

---

### SQL storage backends called loadAll() after every write

**File:** `AbstractSqlStorageBackend.java`

**Root cause:**
`save()`, `saveAll()`, and `delete()` each ended with `cacheUpdater.onUpdate(loadAll())`.
`loadAll()` executes `SELECT * FROM portal_entries`, fetches the full result set, maps each row
to a `PortalEntry`, and returns an `ArrayList`. This meant every single portal mutation — even
during the migration path or bulk `saveAll()` — triggered a full table scan, regardless of how
many rows were in the table. There was also a correctness window: another thread could write
between the `executeUpdate()` completing and the `loadAll()` query executing, causing the cache
to reflect a different state from what the caller just wrote.

The JSON backend had already solved this with `volatile List<PortalEntry> cachedList` built from
its in-memory `LinkedHashMap` snapshot — the SQL backends had no equivalent.

**Fix:**
Added `LinkedHashMap<String, PortalEntry> memEntries`, `ConcurrentHashMap<String, PortalEntry>
memIndex`, and `volatile List<PortalEntry> cachedList` to `AbstractSqlStorageBackend`. A
`hydrateMemory()` method reads the database exactly once at `init()` and populates the map.
`save()`, `saveAll()`, and `delete()` update `memEntries` and `memIndex` inside a `synchronized`
block, rebuild `cachedList` from the map, then pass that snapshot to `cacheUpdater.onUpdate()`.
No additional database read occurs. `loadAllCached()` is overridden to return the volatile field
directly. The database remains authoritative — on restart `hydrateMemory()` re-reads it.

**Threading:** The `synchronized` block covers only `memEntries`, `memIndex`, and the snapshot
rebuild — all fast in-memory operations. The database write happens before the lock is acquired,
so IO does not block concurrent readers. The volatile write to `cachedList` after the
`synchronized` block ensures visibility to threads calling `loadAllCached()` without a lock.

---

### SQL save failures silently notified the cache updater

**File:** `AbstractSqlStorageBackend.java`

**Root cause:**
The `catch (SQLException e)` blocks in `save()`, `saveAll()`, and `delete()` logged the error
and returned normally. Execution then fell through to the `cacheUpdater.onUpdate(loadAll())`
call unconditionally. The cache was updated with the current database state (not including the
failed write), while the caller had no indication that the write had failed. This could cause
the in-memory state diverged from what the caller expected to be persisted.

**Fix:**
Introduced a `boolean success` flag set to `true` only when `executeUpdate()` or
`executeBatch()`/`commit()` complete without throwing. The `cacheUpdater` block and the
in-memory map update are guarded by `if (success)`. Failed writes produce a log warning and
return without touching the cache.

---

### CacheManager.saveRegistry() snapshotted tier and timestamp maps in separate passes

**File:** `CacheManager.java`

**Root cause:**
`saveRegistry()` iterated over `zoneTiers` (a `ConcurrentHashMap`) to build `tierSnapshot`,
then copied `tierTimestamps` into `tsSnapshot`. Between these two iterations, `setZoneTier()`
could run on another thread: it writes to `zoneTiers` first, then writes to `tierTimestamps`.
A window existed where `tierSnapshot` contained the new tier for a zone but `tsSnapshot` was
copied before the matching timestamp was written, resulting in a serialized snapshot where the
tier and its timestamp are inconsistent.

On restart, `loadRegistry()` reads this snapshot. A zone with a HOT or WARM tier but no
timestamp would have a default timestamp of 0 (epoch), causing immediate decay. A zone with a
timestamp but a stale COLD tier would never receive a decay check.

**Fix:**
Both map iterations are now inside a `synchronized (this)` block. `setZoneTier()` is also
synchronized on the same monitor, so either the full tier+timestamp write is visible to the
snapshot or neither is. The synchronized section contains only in-memory map operations and is
non-blocking.

**Threading:** `zoneTiers` and `tierTimestamps` are `ConcurrentHashMap`s — their individual
operations remain atomic for callers that do not need cross-map consistency. The `synchronized`
block is only required for the snapshot pair and the dual-write in `setZoneTier`. Reads of
individual fields (e.g. `zoneTiers.get(id)` for tier checks) do not need the lock and are
unaffected.

---

### JsonStorageBackend.loadFromDisk() could recurse indefinitely on double-corrupt data

**File:** `JsonStorageBackend.java`

**Root cause:**
When `portal-data.json` failed to parse, the catch block copied `portal-data.json.bak` over the
primary file and called `loadFromDisk()` recursively. If the backup was also corrupt, the
recursive call would throw and enter the same catch block again. Since `bakFile.exists()` would
still return true (the file was just copied to the primary path successfully before the parse
failed), the recovery path would be entered again and the method would recurse without bound.
In practice this terminates via `StackOverflowError` but the error message gives no indication
of what happened.

**Fix:**
`loadFromDisk()` is split into a no-arg entry point and a `loadFromDisk(boolean recovering)`
overload. The no-arg version calls `loadFromDisk(false)`. The recovery path is guarded by
`!recovering` — if the recursive call is already a recovery attempt and also fails, the catch
block logs "backup is also corrupt" and returns, leaving the in-memory map empty. Startup
continues with zero portal entries.

---

### PortalLinkRegistry decay task was permanently cancelled after an uncaught exception

**File:** `PortalLinkRegistry.java`

**Root cause:**
`scheduleDecayCleanup()` submitted a `Runnable` to `scheduleAtFixedRate`. The `Runnable`
contained no exception handling. Per `ScheduledExecutorService` contract: if a task throws an
unchecked exception, the executor catches it, records the exception in the `Future` returned by
`scheduleAtFixedRate`, and permanently cancels further scheduling of that task. The executor
itself continues running; only that one task is silently dead. No log message is produced.

In practice this could be triggered by any unexpected `RuntimeException` from `removeIf` or
`schedulePendingSave()`. After the first failure, no further pending link cleanup would occur
for the remainder of the server uptime.

**Fix:**
Wrapped the task body in `try { ... } catch (Exception e)`. The catch logs the error at
`WARNING` and returns normally, allowing the executor to schedule the next invocation at the
normal 24-hour interval.

---

### JVM shutdown hook leaked on plugin reload

**File:** `OptiPortal.java`

**Root cause:**
`doShutdown()` is called on both `/preload reload` and JVM exit. At startup, a `Thread` is
registered via `Runtime.getRuntime().addShutdownHook(shutdownHook)`. On a reload cycle,
`doShutdown()` ran the full shutdown sequence but never removed the hook. The next startup added
a second hook for the new plugin instance. Over many reloads, each JVM exit would attempt to
run all accumulated hooks simultaneously, each invoking `doShutdown()` on their respective
(now-stopped) plugin instances.

**Fix:**
Added `unregisterShutdownHook()` called from `doShutdown()`. The method guards on
`shutdownHook == null`, calls `Runtime.getRuntime().removeShutdownHook(shutdownHook)`, and
catches `IllegalStateException` — the JVM throws this when shutdown is already in progress and
hook removal is no longer possible, which is expected behaviour during a normal server stop and
should not be logged as an error.

**Threading:** `doShutdown()` is already guarded by a `shuttingDown.compareAndSet(false, true)`
gate, so `unregisterShutdownHook()` cannot be called twice on the same instance. The
`IllegalStateException` catch handles the case where the JVM races the plugin reload.

---

### Portal deletion through the UI skipped hotspot cleanup

**File:** `OptiPortalUIPage.java`

**Root cause:**
The UI `Delete` action called `removeLink(id)`, then `storage.delete(id)`, then
`refreshPortalCache()`. It did not call `onPortalDeleted(id)`. The `onPortalDeleted` method
removes all hotspot and pending-hotspot entries keyed by the portal ID from `TeleportInterceptor`.
Without it, confirmed hotspots and pending candidates for the deleted portal remained live in
memory for the rest of the server uptime and could trigger preloads for a portal that no longer
existed.

**Fix:**
Added `plugin.getTeleportInterceptor().onPortalDeleted(id)` immediately after `removeLink(id)`,
matching the sequence already used in the command delete path.

---

### Warp-file sync deletion did not clear link or hotspot state

**File:** `WarpFileWatcher.java`, `OptiPortal.java`

**Root cause:**
`WarpFileWatcher.syncFromFile()` compared the set of IDs currently in storage against the IDs
present in the source warp file. For any `PORTAL`-type entry whose ID was absent from the file,
it called `storage.delete(existingId)` and logged the removal. It did not call `removeLink`,
`onPortalDeleted`, or trigger a portal-cache refresh. Over time, warp-managed portals that were
deleted from the warp file accumulated stale state: orphaned link entries in
`PortalLinkRegistry` and orphaned hotspot records in `TeleportInterceptor`. The non-portal entry
types (`death:*`, `respawn:*`) were already excluded from deletion and remain unaffected.

**Fix:**
Added a `Consumer<String> onPortalDeleted` callback field to `WarpFileWatcher`. The callback is
invoked inside the PORTAL-type deletion block, before `storage.delete()`. Both construction
sites in `OptiPortal` now pass a lambda that calls `portalLinkRegistry.removeLink(id)` followed
by `teleportInterceptor.onPortalDeleted(id)`; `onSyncComplete` (an existing callback) already
handles the cache refresh after the full sync completes. The two existing backwards-compatible
constructors delegate with a no-op for the new parameter.

---

### Hotspot staleness tracking used counts instead of timestamps

**File:** `TeleportInterceptor.java`

**Root cause:**
`pendingHotspotCounts` was `ConcurrentHashMap<String, Integer>` — it stored only an observation
count. `PortalHotspot` (confirmed entries) had no staleness metadata at all. The previous
implementation of `cleanupStaleHotspots()` subtracted the stored integer count from
`System.currentTimeMillis()`, producing a "timestamp" roughly equal to the current epoch
millisecond, then removed entries based on this value. It also removed confirmed hotspots based
on absence from `pendingHotspotCounts`, which is the expected state for any confirmed hotspot
(the pending key is removed on graduation). Both behaviours were incorrect and would have
deleted all confirmed hotspots and left pending entries permanently if scheduled.

Additionally, when a player re-observed an already-confirmed hotspot, `learnPortalHotspot`
returned immediately without recording that the hotspot was still active. There was no mechanism
to distinguish a hotspot observed five minutes ago from one that had not been seen in a year.

**Fix:**
Replaced the raw `Integer` in `pendingHotspotCounts` with a `PendingHotspot` inner class
holding `int count` and `long lastSeenMs`. The map type is now
`ConcurrentHashMap<String, PendingHotspot>`. The `merge()` call is replaced with `compute()`,
which atomically increments `count` and updates `lastSeenMs = System.currentTimeMillis()` on
each observation. `PortalHotspot` gained a `volatile long lastSeenMs` field, set at graduation
and updated whenever a player re-observes the confirmed hotspot. `cleanupStaleHotspots()` now
removes pending entries with `lastSeenMs < now - 7 days` and confirmed entries with
`lastSeenMs < now - 30 days`. Empty world buckets are removed from `portalHotspots` after each
cleanup pass to prevent accumulation from removed worlds. The cleanup task is scheduled every
24 hours via `scheduleWithFixedDelay` in the constructor and cancelled in `stop()`, which stores
the `ScheduledFuture<?>` and sets it to null after cancellation. Subclasses that override
`stop()` should call `super.stop()`.

**Threading:** `pendingHotspotCounts.compute()` is atomic in `ConcurrentHashMap` — no external
synchronisation needed for the count-and-timestamp update. `PortalHotspot.lastSeenMs` is
`volatile` so re-observation updates are immediately visible to the cleanup thread without
locking. `CopyOnWriteArrayList.removeIf()` is thread-safe; concurrent reads during cleanup
see either the pre- or post-removal state of the list, both of which are valid.

---

### Deleting a portal left confirmed hotspot entries alive until TTL expiry

**File:** `TeleportInterceptor.java`

**Root cause:**
The TTL redesign fixed hotspot staleness tracking but left a semantic gap in
`onPortalDeleted(String portalId)`. The method removed pending candidates from
`pendingHotspotCounts` by matching keys that ended in `":" + portalId`, but it never touched
the confirmed hotspot index in `portalHotspots`. Confirmed entries are stored separately as
`PortalHotspot` objects grouped by source world, each carrying `destZoneId` and `lastSeenMs`.
As a result, deleting a portal through the UI, command path, or warp sync removed the portal
record and its pending hotspot candidates, but any already-confirmed hotspot remained active
until `cleanupStaleHotspots()` eventually evicted it 30 days later. During that window,
`checkHotspotPromotion()` could still promote the deleted destination zone from a learned
source position even though the portal no longer existed.

**Fix:**
Extended `onPortalDeleted` to iterate every per-world `CopyOnWriteArrayList<PortalHotspot>` in
`portalHotspots` and remove entries whose `destZoneId.equals(portalId)`. After pruning the
lists, the method now removes empty world buckets with `portalHotspots.entrySet().removeIf(...)`
so the outer map does not accumulate empty lists after repeated deletions. The existing pending
key removal remains in place; portal deletion now clears both pending and confirmed hotspot
state immediately.

**Threading:** `CopyOnWriteArrayList.removeIf()` is safe for concurrent reads from
`checkHotspotPromotion()` and `learnPortalHotspot()`. Readers may observe either the old list
or the new list during the copy-on-write replacement, both of which are consistent states.
`portalHotspots.entrySet().removeIf(...)` operates on the outer `ConcurrentHashMap`; removing an
entry for an empty list does not race incorrectly with active readers because the list contents
have already been pruned before the bucket is removed.

---

### TeleportInterceptor's recurring teleport poll had no explicit lifecycle handle

**File:** `TeleportInterceptor.java`

**Root cause:**
`TeleportInterceptor` scheduled two independent recurring tasks:

1. the one-second teleport poll via `scheduleWithFixedDelay(this::pollTeleportRecords, ...)`
2. the daily hotspot TTL cleanup via `scheduleWithFixedDelay(this::cleanupStaleHotspots, ...)`

Only the cleanup task's `ScheduledFuture<?>` was stored. `stop()` cancelled `cleanupTask` but
had no handle for the poll task, so explicit lifecycle ownership was asymmetric: one task was
owned and cancellable, the other relied on shared-executor shutdown to stop. In the current
plugin this usually worked because `OptiPortal.doShutdown()` later shuts down the executor, but
the class itself did not fully own the resources it created. That made shutdown and reload
reasoning harder and would have been fragile if `stop()` were ever called before executor
shutdown or in tests using a still-live executor.

**Fix:**
Added a `ScheduledFuture<?> pollTask` field, assigned it from the constructor's
`scheduleWithFixedDelay(this::pollTeleportRecords, ...)` call, and updated `stop()` to cancel
both `pollTask` and `cleanupTask`, nulling each field after cancellation. `stop()` remains
idempotent: repeated calls simply see null futures and return.

**Threading:** `ScheduledFuture.cancel(false)` does not interrupt an in-flight run; it prevents
future executions only. That matches the existing cleanup-task semantics and avoids introducing
interrupt handling into `pollTeleportRecords()`. Nulling the fields after cancellation is safe
because `stop()` is only a lifecycle method and the futures are not read elsewhere.

---

## [1.1.8] - 2026-03-26

---

### HistoricMetric — TPS monitor replaced tick-delta polling with server tick-length data

**File:** `WorldTpsMonitor.java`

**Root cause:**
The previous implementation sampled `World.getTick()` on a background thread at a 1-second
interval and computed TPS as `deltaTicks / elapsedSeconds`. This introduced two failure modes:

1. **GC distortion.** A GC pause spanning part of the sampling window compresses `deltaTicks`
   relative to `elapsedSeconds`, producing a false low-TPS reading. The jitter guard
   (`|elapsed − expected| / expected > 0.30`) discarded these samples, but a pause shorter
   than 300ms (30% of 1s) would still be published as a bad sample and engage backpressure
   unnecessarily.

2. **One-second lag.** A sudden server stall was only visible to OptiPortal's backpressure
   logic after the next sample fired, up to one second later.

**Fix:**
`World` exposes `getBufferedTickLengthMetricSet()` returning a `HistoricMetric` that the
engine populates with the actual nanosecond duration of every tick as it completes. Reading
`tickLen.getLastValue()` gives the most recent tick duration without any elapsed-time
arithmetic, and converting to TPS is simply `1_000_000_000.0 / lastTickNanos`.

Because the value already represents a real tick duration, GC-induced distortion is
impossible — a GC pause extends the tick duration naturally and is reflected accurately.
The jitter guard and `lastSampleNanos`/`lastTickCounts` state are no longer needed and have
been removed. EMA smoothing over the 1-second sample interval is retained to suppress
single-tick spikes.

**Threading:** `HistoricMetric` is written on the world thread per tick and read from the
sampling thread without synchronisation. The read may observe a value one tick behind but
this is acceptable for backpressure decisions, matching the off-thread safety already
accepted by the previous `world.getTick()` call.

**Removed state:**

| Field / constant | Reason for removal |
|---|---|
| `lastTickCounts` (`ConcurrentHashMap<String, AtomicLong>`) | No longer sampling delta ticks |
| `lastSampleNanos` (`AtomicLong`) | No longer measuring elapsed wall time |
| `MAX_JITTER_FRACTION` | Jitter guard removed |
| `sampleBaseline()` | Baseline tick count no longer needed |

---

### GC guard — preload deferred when consumeGCHasRun() fires

**File:** `EnhancedChunkPreloader.java`

**Root cause:**
`WorldTpsMonitor` could theoretically detect GC via the jitter guard discarding a distorted
sample, but that required the GC pause to span >30% of the 1-second sample window. Short
collections (minor GCs) would not be caught, yet those are exactly the events that spike
allocation pressure. Preload batches submitted during or immediately after a GC increase
heap pressure on a JVM that may already be in a fragile state.

**Fix:**
`World` (via `TickingThread`) maintains a one-shot `boolean gcHasRun` flag set by a JVM
`GarbageCollectorMXBean` notification listener. `consumeGCHasRun()` reads and atomically
clears it.

In `loadChunksAsync`, after the existing heap and chunk-pressure guards, the world is
resolved and `consumeGCHasRun()` is dispatched to the world thread via
`CompletableFuture.supplyAsync(gcWorld::consumeGCHasRun, gcWorld)`. If the flag was set,
the entire preload returns a completed future immediately. If not, the load chain is built
and returned via `buildLoadChain()`, which was extracted from the inline chain construction
to keep the async branching clean.

**One-shot consumption:** `consumeGCHasRun()` clears the flag on read. The server's own
logging path in `ChunkPreLoadProcessEvent.processEvent()` also calls this method to annotate
slow hook warnings. OptiPortal consuming the flag first means the server's annotation for
that hook cycle will miss the GC, which affects only the server's log verbosity — not
correctness.

**Threading:** `consumeGCHasRun()` is dispatched to the world thread via the world's
`Executor` interface. `buildLoadChain` is called inside `thenComposeAsync(..., executor)`
so that after the world-thread flag read completes, the continuation runs on the plugin
executor — not on the world thread. See the *Guard 3 world-thread continuation* entry below
for the bug this fixed.

---

### Retry tracker — per-chunk OptiPortal cooldown for quadratic backoff

**File:** `EnhancedChunkPreloader.java`

**Root cause:**
As of Hytale 2026.03.26-89796e57b, `ChunkStore.isChunkOnBackoff(long index, long maxNanos)`
changed its internal formula from a flat comparison to a quadratic ramp:

```java
// Before (inferred from behaviour):
return nanosSince < maxFailureBackoffNanos;

// After:
return nanosSince < Math.min(maxFailureBackoffNanos, count * count * FAILURE_BACKOFF_NANOS);
// where FAILURE_BACKOFF_NANOS = TimeUnit.MILLISECONDS.toNanos(1)
```

At `count = 1` (first failure), the effective backoff is `min(10s, 1ms) = 1ms`. The cap
passed by OptiPortal (`ChunkStore.MAX_FAILURE_BACKOFF_NANOS = 10s`) is irrelevant at low
failure counts — the binding constraint is `count² × 1ms`. A chunk that fails once is
therefore suppressed for only ~1ms regardless of what cap is passed.

On a zone with a transiently-failing chunk and frequent preload triggers (e.g. player
hovering near a portal), OptiPortal could attempt that chunk thousands of times per second.
Increasing the cap constant does not help because the cap is not the binding term.

**Fix:**
`chunkFailureTimestamps: ConcurrentHashMap<String, ConcurrentHashMap<Long, Long>>`
maps `worldName → chunkIndex → nanos of last failure`. The outer key is `worldName` so
entries can be cleanly evicted on world unload via `worldRegistry.addWorldUnloadCallback`.

Guard B is placed immediately after Guard A (the engine backoff check) in the `loadChunkBatch`
loop:

```java
ConcurrentHashMap<Long, Long> worldFailures = chunkFailureTimestamps.get(worldName);
if (worldFailures != null) {
    Long lastFailed = worldFailures.get(chunkIndex);
    if (lastFailed != null && System.nanoTime() - lastFailed < RETRY_COOLDOWN_NANOS) {
        // skip
    }
}
```

`RETRY_COOLDOWN_NANOS = TimeUnit.SECONDS.toNanos(10)` matches the old flat-comparison
behaviour of the engine's backoff (before quadratic ramp), restoring equivalent suppression
for first failures.

On success, `thenAcceptAsync` removes the entry and evicts the inner map if it becomes
empty to prevent unbounded outer-map growth on long-running servers:
```java
ConcurrentHashMap<Long, Long> wf = chunkFailureTimestamps.get(worldName);
if (wf != null) {
    wf.remove(chunkIndex);
    if (wf.isEmpty()) chunkFailureTimestamps.remove(worldName, wf);
}
```
The two-arg `remove(key, value)` is used so an inner map that received a concurrent new
failure between the `isEmpty()` check and the remove call is not accidentally evicted.

On failure, `exceptionally` records the timestamp via `computeIfAbsent` to lazily create
the inner map only when a failure actually occurs — the map stays empty under normal
operation.

**Memory:** entries are `Long → Long` pairs (16 bytes each on a compressed-oops JVM).
Inner maps are only created on failure. The world-unload callback drops the entire inner
map. In the steady state (no failures) the outer map is populated but all inner maps are
empty or absent.

**Interaction with Guard A:** Guard A (`isChunkOnBackoff`) remains in place. For chunks
with high `failedCounter` (many consecutive failures), the engine's quadratic term eventually
exceeds `RETRY_COOLDOWN_NANOS` (at `count ≥ 100`, `count² × 1ms ≥ 10s`), so Guard A
becomes the binding check. Guard B is only needed to cover the low-count range where the
engine's ramp has not yet reached the 10s threshold.

---

### Guard 3 world-thread continuation — buildLoadChain was running on world thread

**File:** `EnhancedChunkPreloader.java` — `loadChunksAsync`

**Root cause:**
`CompletableFuture.thenCompose(fn)` inherits the completing thread. The Guard 3 check:
```java
CompletableFuture.supplyAsync(gcWorld::consumeGCHasRun, gcWorld)
    .thenCompose(gcRan -> { ... return buildLoadChain(...); });
```
completes `supplyAsync` on the world thread (via `gcWorld` as executor). The `thenCompose`
continuation therefore also ran on the world thread: `buildLoadChain` was called on the
world thread, and the first `thenCompose` inside `buildLoadChain` was also triggered
synchronously on the world thread because its seed future (`completedFuture(null)`) was
already done at the call site. This caused `loadChunkBatch` — and within it,
`WorldThreadBridge.getChunkAsync` → `World.getNonTickingChunkAsync` — to execute on the
world thread.

Spark profiling captured the exact frame:
```
WorldThread - default
  EnhancedChunkPreloader.lambda$loadChunksAsync$2()   ← thenCompose lambda
  EnhancedChunkPreloader.buildLoadChain()
  EnhancedChunkPreloader.lambda$buildLoadChain$0()    ← first batch thenCompose
  EnhancedChunkPreloader.loadChunkBatch()
  WorldThreadBridge.getChunkAsync()
  World.getNonTickingChunkAsync()
  libjvm.so InterpreterRuntime::resolve_invokedynamic  ← first-call lambda bootstrap
```
The `resolve_invokedynamic` chain is a one-time JVM cost (lambda call-site bootstrap),
not a recurring issue, but it surfaced here because the first call to
`getNonTickingChunkAsync` was occurring on the world thread rather than the plugin executor.

**Fix:**
Changed `.thenCompose(fn)` to `.thenComposeAsync(fn, executor)` where `executor` is the
plugin's `ScheduledExecutorService`. The world thread now does exactly one thing: reads and
clears `gcHasRun`. All subsequent work — chain building, batch dispatch, and the
`getNonTickingChunkAsync` call-site initialisation — moves to the plugin executor.

---

## [1.1.7] - 2026-03-24

---

### Sched1 — Four scheduleAtFixedRate/scheduleWithFixedDelay callbacks without exception guards

**Files:** `ZoneTtlEnforcer.java`, `KeepaliveManager.java`, `WarpFileWatcher.java`, `CacheManager.java`

**Root cause:**
From the Java documentation for `ScheduledExecutorService.scheduleAtFixedRate` and
`scheduleWithFixedDelay`:
> If any execution of the task encounters an exception, subsequent executions are suppressed.

Four scheduled callbacks had no outer try-catch, making them vulnerable to permanent
cancellation on any unexpected `RuntimeException`:

| Class | Method | Risk |
|-------|--------|------|
| `ZoneTtlEnforcer` | `runCleanup()` | `storage.loadAll()` or `storage.delete()` throws on DB error |
| `KeepaliveManager` | `pingTier()` | `storage.loadAll()` or `world.getNonTickingChunkAsync()` throws |
| `WarpFileWatcher` | `checkAndSync()` | `syncNativeWarps()` calls external plugin API — any RuntimeException from `TeleportPlugin.get()`, `tp.getWarps()`, etc. |
| `CacheManager` | `decayTiers()` | Low risk in practice, but no guard against future code paths |

These failures would be silent — no log entry, no exception visible to the server operator.
The task simply stops running for the remainder of the server uptime.

**Fix:**
Each method is split into a guarded public/protected entry point and an internal
implementation method:

```java
// Pattern applied to all four:
public void runCleanup() {
    try {
        runCleanupInternal();
    } catch (Exception e) {
        LOG.warning("[OptiPortal] ZoneTtlEnforcer: cleanup error (scheduler preserved): " + e.getMessage());
    }
}
private void runCleanupInternal() { /* original body */ }
```

For `WarpFileWatcher`, only `syncNativeWarps()` is additionally wrapped since the rest of
`checkAndSync()` already had a try-catch. The external plugin API call is isolated so a
crash in the native path does not suppress the file-based fallback path.

**`scheduleAtFixedRate` vs `scheduleWithFixedDelay`:** both suppress on uncaught exception.
`KeepaliveManager` uses `scheduleWithFixedDelay`; the same fix applies.

**`KeepaliveManager` / `AsyncKeepaliveManager` subclass dispatch:** `start()` schedules
`() -> pingTier(tier, label)`. `pingTier` is `protected` and overridden by `AsyncKeepaliveManager`.
An initial approach of wrapping only the base `pingTier` body in try-catch was bypassed by
the override via polymorphism. The correct fix is a private `safeping()` method in
`KeepaliveManager` that wraps the `pingTier` virtual call, placed at the point of scheduling:

```java
private void safeping(CacheTier tier, String label) {
    try { pingTier(tier, label); }  // virtual — dispatches to whichever subclass is active
    catch (Exception e) { LOG.warning(...); }
}
// Scheduler lambda now calls safeping(), not pingTier() directly.
```

This guards regardless of which override runs.

---

### JsonStorage — disk I/O held the monitor lock

**File:** `JsonStorageBackend.java`

**Root cause:**
`save()`, `saveAll()`, and `delete()` were declared `synchronized` and called `flush()`
directly. `flush()` was also `synchronized` and performed:

1. GSON serialisation of the full entry map
2. `FileOutputStream` + `FileChannel.force(true)` (fsync — blocks until OS writes to disk)
3. `Files.copy()` — backup of the existing file
4. `Files.move()` — atomic rename

Any thread calling `loadAll()` or `loadById()` (also `synchronized`) was blocked for the
entire duration of steps 1–4 — potentially 5–50ms on a loaded server or slow disk. Since
reads are called on plugin startup during zone registration and on every config reload, this
directly delayed startup and blocked the caller thread.

**Fix:**
The lock is held only long enough to mutate the in-memory map and take a `new ArrayList<>(entries.values())`
snapshot (microseconds). All disk I/O runs from the snapshot outside the lock:

```java
public void save(PortalEntry entry) {
    List<PortalEntry> snapshot;
    synchronized (this) {
        entries.put(entry.getId(), entry);
        snapshot = new ArrayList<>(entries.values());  // O(n) copy under lock
    }
    flush(snapshot);             // disk I/O — no lock held
    if (cacheUpdater != null) cacheUpdater.onUpdate(snapshot);
}
```

`flush()` is now `private void flush(List<PortalEntry> snapshot)` — no `synchronized`.
Two concurrent flushes can race on the tmp file, but `Files.move()` is atomic and the last
writer wins, which is correct (it holds the most recent snapshot).

**Reads unaffected:** `loadAll()` and `loadById()` remain `synchronized` — they hold the
lock for a single map read (nanoseconds) and are never blocked by I/O.

---

### AsyncTeleport — non-lazy LOG.fine with String.format

**File:** `AsyncTeleportInterceptor.java`

**Root cause:**
The velocity-boost log line in the `thenAccept` callback was:

```java
LOG.fine("[OptiPortal] Velocity boost: speed=" + String.format("%.2f", speed) + ...);
```

`Logger.fine(String)` takes a pre-built `String` — the message is always constructed
regardless of whether FINE logging is enabled. `String.format("%.2f", speed)` allocates a
`Formatter`, a `StringBuilder`, and the result `String` on every invocation. In production
(INFO level), this work is discarded immediately.

The `thenAccept` fires once per proximity poll per player when their speed exceeds the
boost threshold — potentially many times per second on a busy server.

**Fix:**
```java
LOG.fine(() -> String.format(
    "[OptiPortal] Velocity boost: speed=%.2f threshold=%s radius=%d → %d zone=%s",
    speed, speedThreshold, baseRadius, boostedRadius, zoneId));
```

`Logger.fine(Supplier<String>)` calls the supplier only if FINE is enabled. A single
`String.format` call replaces the chain of `+` concatenations, reducing intermediate
allocations from ~6 objects to 1.

---

### U1 — False HOT promotion when chunk load aborted

**Files:** `ChunkPreloader.java`, `EnhancedChunkPreloader.java`, `ChunkLoadAbortedException.java` (new)

**Root cause:**
`loadChunks()` has two early-abort guards (heap pressure, chunk count limit) that previously
returned `CompletableFuture.completedFuture(null)` — a successfully-completed future. Any
`thenRunAsync` chain attached to the caller's future would run normally:

```
predictiveLoad()
  └─ loadChunks()  → completedFuture(null)  ← abort, but looks like success
       └─ thenRunAsync(() -> setZoneTier(HOT))  ← fires, zone promoted falsely
```

**Fix:**
Abort paths now return `CompletableFuture.failedFuture(new ChunkLoadAbortedException(reason))`.
`thenRunAsync` does not fire on a failed future. An `.exceptionally()` handler on each
`thenRunAsync` chain logs aborts at `FINE` and genuine errors at `WARNING`.

`ChunkLoadAbortedException extends RuntimeException` uses the four-arg constructor
`super(reason, null, true, false)` to suppress both cause and stack trace — it is a
control-flow signal, not an error requiring a trace.

**Affected call sites:** both abort guards in `ChunkPreloader.loadChunks()` and
`EnhancedChunkPreloader.loadChunksAsync()`.

---

### E1 — SkipSentryException escaped world.execute() to CompletableFuture timeout

**File:** `WorldThreadBridge.java`

**Root cause:**
`world.execute(Runnable)` throws `SkipSentryException extends RuntimeException` synchronously
when `acceptingTasks == false` (world shutting down) — before the lambda is ever enqueued.
The try-catch protecting against this was placed **inside** the submitted lambda:

```java
world.execute(() -> {
    try {
        // ... chunk work ...
    } catch (Exception e) {        // SkipSentryException never reaches here —
        future.completeExceptionally(e);  // world.execute() already threw above
    }
});
// SkipSentryException propagates here, uncaught
```

The exception propagated to `CompletableFuture`'s completion infrastructure, was swallowed
there, and the future was left pending until the `orTimeout` window expired (5 seconds).

**Fix:**
Outer try-catch wraps the `world.execute()` call itself. On `SkipSentryException`, the circuit
breaker is notified and the future is completed exceptionally immediately:

```java
try {
    world.execute(() -> { /* lambda with its own try-catch for in-lambda errors */ });
} catch (Exception e) {
    circuitBreaker.recordFailure();
    future.completeExceptionally(e);
}
```

---

### D4 — thenRunAsync defaulted to ForkJoinPool.commonPool

**Files:** `TeleportInterceptor.java`, `WarmZoneManager.java`

**Root cause:**
`CompletableFuture.thenRunAsync(Runnable)` (no executor argument) submits to
`ForkJoinPool.commonPool()`. Two sites used this form:

1. `TeleportInterceptor.predictiveLoadWithRam` — HOT promotion and storage write ran on the
   common pool, outside the plugin's executor budget and with no backpressure.
2. `WarmZoneManager.loadWarmZone` — post-load bookkeeping (tier set, `markWarmFloor`,
   storage update) ran on the common pool.

The common pool is shared across the JVM. Heavy storage I/O or tier map updates on it could
compete with other plugin or framework tasks.

**Fix:** Both sites now call `thenRunAsync(runnable, executor)` using the plugin's
`ScheduledExecutorService`, keeping all post-load work within the controlled thread pool.

---

### U2 — Keepalive residency check was unreachable dead code

**File:** `AsyncKeepaliveManager.java` — `pingChunkBatch()`

**Root cause:**
The method called `worldBridge.getChunkAsync(world, cx, cz, true)` and then checked
`if (chunk == null)` to detect eviction. `getChunkAsync` routes to
`world.getChunkAsync(index)` → `ChunkStore.getChunkReferenceAsync(index, 4)`, which triggers
a disk load if the chunk is absent. It resolves to a non-null `WorldChunk` on success and
fails the future only on a hard error — it never resolves to null. The null branch was
therefore unreachable; evicted HOT chunks were never detected and the demotion to WARM never
fired.

**Fix:**
Residency is probed via `world.getChunkStore().getChunkReference(long index)`:
- Implemented with a `StampedLock` optimistic read; safe to call from any thread.
- Returns `null` if the chunk is not in the `ChunkStore`; does not trigger a load.
- Index format matches `ChunkUtil.indexChunk(x, z) = (long)x << 32 | z & 0xFFFFFFFFL`
  (distinct from CacheManager's `packChunkIndex` which uses the opposite bit layout).

If the reference is null while the zone is HOT, the method scans portal entries to find the
owning zone and calls `setZoneTier(WARM)`. `getChunkAsync` is then always called regardless
of residency — it is the actual keepalive ping that resets the engine's eviction timer and
reloads the chunk if it was evicted.

---

### CacheManager — tryReleaseKeepLoaded blocked executor thread per chunk

**File:** `CacheManager.java` — `tryReleaseKeepLoaded()`

**Root cause:**
The original implementation called `world.getChunkIfInMemory(engineIndex)` off the world
thread. From the decompiled engine source:

```java
public WorldChunk getChunkIfInMemory(long index) {
    Ref<ChunkStore> reference = this.chunkStore.getChunkReference(index);
    if (reference == null) return null;
    return !this.isInThread()
        ? CompletableFuture.<WorldChunk>supplyAsync(() -> this.getChunkIfInMemory(index), this).join()
        : this.chunkStore.getStore().getComponent(reference, WorldChunk.getComponentType());
}
```

Called off-thread, this dispatches back to the world thread via `supplyAsync(..., world).join()`
— blocking the calling executor thread for a full world-thread round-trip per chunk. On a
large zone decay (e.g. 200-chunk zone going COLD), this stalled the executor for hundreds of
sequential blocking calls.

**Fix:**
```
1. getChunkStore().getChunkReference(engineIndex)
   — StampedLock optimistic read, thread-safe, non-blocking.
   — If null: chunk already evicted, nothing to release, return early.
2. world.execute(lambda)
   — Enqueues to world task queue, returns immediately (fire-and-forget).
   — Lambda runs on world thread: getChunkIfInMemory (fast path, no re-dispatch),
     then chunk.removeKeepLoaded() if non-null.
3. world.execute() wrapped in try-catch for SkipSentryException (world shutting down).
```

---

### Gap 1 — Direct HOT promotions without chunk-load verification

**File:** `TeleportInterceptor.java`

**Root cause:**
Four sites called `CacheManager.setZoneTier(id, HOT)` directly without verifying that the
zone's chunks were actually in memory with an active `keepLoaded` pin:

| Site | Trigger |
|------|---------|
| `onDiscoverZoneEvent` (normalized name) | DiscoverZoneEvent for WarmStrategy.WARM zone |
| `onDiscoverZoneEvent` (raw name fallback) | Same, using unnormalized zone id |
| `checkProximityAndPreload` | Player within activation distance of WarmStrategy.WARM portal |
| `lingerOriginZone` | Player just teleported away from nearest portal |

`setZoneTier` only updates the in-memory tier map and timestamp — it does not load chunks,
does not call `addKeepLoaded`, and does not verify any engine state. If `WarmZoneManager` had
not yet loaded the zone (startup race, or a previous load future completed exceptionally), the
zone would show HOT in the UI with `getOwnedChunkCount() == 0` and no keepLoaded pin.

**Fix:**
Each site now checks `getCacheManager().getOwnedChunkCount(id) > 0` before promoting.

- If count > 0: chunks are owned and `addKeepLoaded` was called for each — safe to promote.
- If count == 0 (WarmStrategy.WARM zones only): `warmZoneManager.loadWarmZone(entry)` is
  called to retry the load; `setZoneTier(HOT)` will fire from `loadWarmZone`'s `thenRunAsync`
  on successful completion.
- Linger path (no `PortalEntry` available): skips the promotion entirely when count == 0 —
  a linger HOT on an unloaded zone is meaningless.

The hotspot promotion (`checkHotspotPromotion`, line ~1052) and the async proximity path in
`AsyncTeleportInterceptor` were already safe — both explicitly guard with
`tier == CacheTier.WARM` before promoting, which implies a prior successful load.

---

### Gap 2 — Per-zone keepLoaded pinning

**File:** `CacheManager.java`

**Root cause — single-pin model:**
The original model maintained exactly one `keepLoaded` pin per chunk regardless of how many
zones owned it. `addKeepLoaded()` was called only by the first zone to register ownership
(guarded by `isFirst[0]` inside `compute()`). `removeKeepLoaded()` was called only when the
last zone deregistered (`owners.isEmpty()` guard in both deregister methods).

**Correctness gap — concurrent deregistration:**
`owners.remove(zoneId)` and `owners.isEmpty()` were not performed atomically as a pair:

```java
owners.remove(zoneId);          // Thread A: owners = {}
owners.remove(zoneId);          // Thread B (same chunk): owners = {} (already empty)
if (owners.isEmpty()) {         // Thread A: true → calls tryReleaseKeepLoaded
if (owners.isEmpty()) {         // Thread B: true → calls tryReleaseKeepLoaded again
```

Both threads call `tryReleaseKeepLoaded`, which enqueues `chunk.removeKeepLoaded()` on the
world thread twice. keepLoaded decrements from 1 to 0 then to -1. `shouldKeepLoaded()` checks
`keepLoaded.get() > 0` — with -1 this returns false, but subsequent `addKeepLoaded()` calls
from re-registration would only bring keepLoaded back to 0, leaving the chunk permanently
evictable.

**Correctness gap — dedup path:**
The plain `registerOwnership(zoneId, world, cx, cz)` (no chunk param) recorded ownership but
called no `addKeepLoaded`. A zone registering via the dedup path held an ownership record but
no independent engine pin. Its WARM/HOT status in the UI was backed entirely by the first
owner's pin.

**Fix — per-zone pinning:**

`registerOwnership(..., chunk)`:
```java
// ConcurrentHashMap.newKeySet().add() is atomic — returns true only if newly inserted.
// Exactly one concurrent registration of the same zoneId gets added=true.
boolean added = chunkOwnership
    .computeIfAbsent(world, ...).computeIfAbsent(key, ...).add(zoneId);
recordReverseOwnership(zoneId, world, key);
if (added && chunk != null) chunk.addKeepLoaded();
```

`registerOwnership(zoneId, world, cx, cz)` (dedup):
```java
boolean added = chunkOwnership...add(zoneId);
recordReverseOwnership(zoneId, world, key);
if (added) addKeepLoadedAsync(worldName, cx, cz);
```

`addKeepLoadedAsync` mirrors `tryReleaseKeepLoaded`: `getChunkReference` pre-check, then
`world.execute(() -> { chunk = getChunkIfInMemory(index); if (chunk != null) chunk.addKeepLoaded(); })`.

`deregisterOwnership` and `deregisterAllChunks`:
```java
boolean removed = owners.remove(zoneId);
if (removed) tryReleaseKeepLoaded(...);   // per-zone: always release when actually removed
if (owners.isEmpty()) worldMap.remove(key, owners);
```

`owners.remove()` on `ConcurrentHashMap.newKeySet()` is atomic and returns true only once for
a given element across concurrent callers, eliminating the double-release race.

**Invariant after fix:**
`keepLoaded` on a chunk always equals the number of distinct zones currently registered as
owners of that chunk. A chunk's keepLoaded count reaches zero precisely when every owning zone
has deregistered — not before.

**Threading note on `addKeepLoadedAsync`:**
The world.execute() enqueue for `addKeepLoaded` may be queued after a concurrent
`tryReleaseKeepLoaded` enqueue for an older owner. World task queue is FIFO, so:

```
queue: [addKeepLoaded for Zone B] [removeKeepLoaded for Zone A]
```
keepLoaded: 1 (Zone A) → 2 (after add) → 1 (after remove) — chunk stays pinned. Correct.

In the reversed order:
```
queue: [removeKeepLoaded for Zone A] [addKeepLoaded for Zone B]
```
keepLoaded: 1 → 0 (brief eviction window) → 1 — chunk may be evicted between the two ops.
This is Gap 3 (brief async window, non-critical: `keepAlive` starts at 15 ticks = 7.5 s).

---

### TPS Monitor — EMA smoothing, jitter guard, above-20 TPS, rename

**File:** `WorldTpsMonitor.java`

**Changes:**

| Field | Before | After | Reason |
|-------|--------|-------|--------|
| `NOMINAL_TPS` | 20.0 (implied cap) | renamed `HYTALE_TARGET_TPS` | OptiPortal does not cap TPS; name was misleading |
| `NOMINAL_TPS` | — | kept as `@Deprecated` alias | binary compatibility |
| `SAMPLE_INTERVAL_SECONDS` | 2 | 1 | faster reaction to load changes |
| Upper clamp | `min(worldTps, NOMINAL_TPS)` | `min(worldTps, HYTALE_TARGET_TPS * 1.5)` | preserve above-20 readings |
| `EMA_ALPHA` | none (raw sample) | 0.25 | smooth noise, react to sustained load in ~4s |
| `MAX_JITTER_FRACTION` | none | 0.30 | discard samples where elapsed ±30% from expected |

**EMA formula:** `currentTps = 0.25 * rawSample + 0.75 * currentTps`

At α=0.25 with 1s samples, a sustained drop to 15 TPS (from 20) registers at ~95% of the
true value after ~4 samples (~4 seconds). This is fast enough for backpressure decisions
without triggering false throttling from a single-tick hiccup.

**Jitter guard:** if `|elapsed - expected| / expected > 0.30`, the sample is discarded and
`currentTps` retains its previous EMA value. A GC pause spanning 300ms of a 1s window would
compress `deltaTicks` to ~70% of a normal window, producing a false low-TPS reading that
would incorrectly engage backpressure. Discarding the sample avoids this.

**Above-20 TPS:** servers running catch-up bursts can tick faster than 20 TPS. Clamping at
20 masked this information. The new 1.5× clamp preserves readings up to 30 TPS. `getLoadFactor()`
returns `max(0, (TARGET - tps) / TARGET)` — clamped to 0.0 at or above TARGET, so readings
above 20 are treated as "no load" without triggering negative backpressure.

### Compounding — N concurrent portal approaches each queued independent loads

**Files:** `ChunkPreloader.java`, `EnhancedChunkPreloader.java`

**Root cause:**
When N players simultaneously approached the same portal, each one triggered a separate call
to `EnhancedChunkPreloader.predictiveLoad(zoneId, ...)`, which called
`loadBalancer.scheduleLoad(() -> enhancedPredictiveLoad(...))`. With no per-zone
deduplication, N `scheduleLoad` tasks were enqueued. Each task independently called
`world.getChunkAsync(index)` for every chunk in the destination zone's radius, and each
`getChunkAsync` call internally chains a `.thenApplyAsync(ref -> component_lookup, world)`,
queuing a world-thread task:

```
N players → N scheduleLoad tasks → N × radius² getChunkAsync calls
         → N × radius² .thenApplyAsync(..., world) tasks on the world thread
```

At radius=5 (121 chunks) and N=5 players: 605 world-thread tasks enqueued simultaneously.
The engine deduplicates in-flight I/O at the `ChunkStore` level (via `computeIfAbsent` +
`StampedLock`), so no duplicate disk reads occur — the waste is purely the world-thread
dispatch overhead from the redundant `thenApplyAsync` chains.

**Fix:**
`withInflightDedup(zoneId, loader)` added to `ChunkPreloader` (base class, `protected final`):

```java
boolean[] isNew = {false};
CompletableFuture<Void> relay = inflightLoads.compute(zoneId, (k, existing) -> {
    if (existing != null && !existing.isDone()) return existing; // re-use
    isNew[0] = true;
    return new CompletableFuture<>();
});
if (!isNew[0]) return relay;  // caller 2..N: no scheduleLoad, no getChunkAsync
CompletableFuture<Void> actual = loader.get();
actual.whenComplete((v, ex) -> {
    if (ex != null) relay.completeExceptionally(ex); else relay.complete(null);
    inflightLoads.remove(zoneId, relay);
});
return relay;
```

`ConcurrentHashMap.compute` is atomic: exactly one caller sets `isNew[0] = true` and starts
the load. All subsequent concurrent callers receive the relay and return immediately without
calling `scheduleLoad`. The relay is removed from the map when the actual load completes.

`EnhancedChunkPreloader.predictiveLoad` wraps its `scheduleLoad` call in `withInflightDedup`
so the deduplication occurs before any work is queued.

**Degradation behaviour:** if `withInflightDedup` were removed, behaviour reverts to the
pre-fix state (wasteful but correct — engine I/O dedup prevents duplicate reads).

---

### A2 overflow — AsyncLoadBalancer EMA counter

**File:** `AsyncLoadBalancer.java`

**Root cause:**
`getAverageExecutionTime()` computed a cumulative average over all samples since startup:

```java
DoubleAdder emaSum;        // sum of all durations
AtomicInteger emaCount;    // count of all samples

double avg = emaSum.sum() / emaCount.get();
```

`AtomicInteger` overflows at `Integer.MAX_VALUE` (2,147,483,647 ≈ 2.1 billion). After overflow
the count wraps negative. With `emaSum` still positive and large, `avg` becomes a large
negative number. `adjustBatchSize()` treats a negative average as excellent performance
(`avgExecutionTime < 50`) and drives `currentBatchSize` toward `MAX_BATCH_SIZE` (16),
where it stays permanently regardless of actual server load.

At a sustained 1,000 ops/sec, overflow occurs after ~24.8 days of uptime. At 100 ops/sec,
~248 days. A loaded server managing many players and zones can reach 1,000 ops/sec readily.

**Fix:**
Replace the `DoubleAdder + AtomicInteger` pair with a single `AtomicLong` holding
`Double.doubleToLongBits(ema)`. Update via CAS loop with α=0.1:

```java
AtomicLong emaBits = new AtomicLong(Double.doubleToLongBits(0.0));

// In executeOperation.whenComplete:
long prevBits, nextBits;
do {
    prevBits = emaBits.get();
    double prev = Double.longBitsToDouble(prevBits);
    double next = (prev == 0.0) ? duration : 0.1 * duration + 0.9 * prev;
    nextBits = Double.doubleToLongBits(next);
} while (!emaBits.compareAndSet(prevBits, nextBits));

// getAverageExecutionTime():
return Double.longBitsToDouble(emaBits.get());
```

The EMA naturally forgets old values (factor 0.9 per sample), so the stored value stays
bounded regardless of uptime. No counter required. The CAS loop is contention-free under
normal load; under high contention it retries on stale reads, which are rare.

**Behaviour change:** the metric is now a true EMA (recent samples weighted more heavily)
rather than a lifetime average. Batch size adaptation reacts to current load rather than
all-time average — which is the intended behaviour.

---

### E2 — WorldThreadBridge batch processor killed by SkipSentryException in processBatch

**File:** `WorldThreadBridge.java` — `processBatch()`, `startBatchProcessor()`

**Root cause:**
`processBatch()` called `world.execute(batch)` with no outer try-catch. As documented in E1,
`world.execute()` throws `SkipSentryException extends RuntimeException` synchronously when the
world is shutting down. This exception propagated out of `processBatch`, through the
`operationQueues.forEach(this::processBatch)` lambda inside `startBatchProcessor`, and into
the `scheduleAtFixedRate` scheduler.

From the Java documentation for `ScheduledExecutorService.scheduleAtFixedRate`:
> If any execution of the task encounters an exception, subsequent executions are suppressed.

A single world shutdown during an active batch permanently cancelled the repeating task.
All worlds — including those still running — would no longer have their operation queues
drained. Any pending `CompletableFuture` registered via `executeBatched` would hang
indefinitely, and the `operationQueues` map would grow without bound as new entries were
added but never consumed.

**Fix:**
Wrap `world.execute()` in a try-catch inside `processBatch`:

```java
try {
    world.execute(() -> { /* batch with inner try-catch for per-op errors */ });
} catch (Exception e) {
    // SkipSentryException — world is shutting down
    LOG.fine(...);
    operationQueues.remove(world, queue);  // evict dead world; two-arg remove is atomic
}
```

The two-arg `ConcurrentHashMap.remove(world, queue)` ensures only this specific queue
instance is removed, not a queue that may have been reinstated by a concurrent `executeBatched`
call after the shutdown event.

---

### CB1 — CircuitBreaker HALF_OPEN→CLOSED transition not atomic with concurrent failure

**File:** `CircuitBreaker.java` — `recordSuccess()`

**Root cause:**
`recordSuccess()` in `HALF_OPEN` state called `closeCircuit()`, which unconditionally wrote
`state.set(State.CLOSED)`. A concurrent `recordFailure()` in `HALF_OPEN` called `openCircuit()`,
which unconditionally wrote `state.set(State.OPEN)`.

If the execution order was: `openCircuit()` sets OPEN → `closeCircuit()` sets CLOSED, the
circuit would end up CLOSED even though a failure had just occurred. The failure was silently
masked; the circuit breaker would allow traffic through as though recovery had succeeded.

```
Thread A (failure): openCircuit()  → state = OPEN
Thread B (success): closeCircuit() → state = CLOSED  ← overwrites OPEN, hides failure
```

This is rare (requires a success and a failure within the same nanosecond window in
HALF_OPEN), but the severity is high: the circuit breaker's entire purpose is to block
traffic after failure.

**Fix:**
Replace the `closeCircuit()` call in `recordSuccess()` with an inline CAS:

```java
if (state.compareAndSet(State.HALF_OPEN, State.CLOSED)) {
    failureCount.set(0);
    successCount.set(0);
    lastStateChange.set(System.currentTimeMillis());
    LOG.info("Circuit breaker CLOSED - normal operation resumed");
}
```

If a concurrent `recordFailure()` has already set state to OPEN, the CAS fails and the
circuit stays OPEN. The public `reset()` method still calls `closeCircuit()` (unconditional
`state.set`) so force-reset from external code is unaffected.

---

### PredStats — PRED zone RAM and preload count never persisted

**Files:** `ChunkPreloader.java`, `TeleportInterceptor.java`

**Root cause:**
Three separate problems combined to leave PRED zone stats permanently at `--` in the UI.

**Problem 1 — `updateEntryStats` called without `storage.save`**

`ChunkPreloader.predictiveLoad` has an internal `thenRunAsync` callback that calls
`updateEntryStats(entry, loadedCount)` after tier promotion. `updateEntryStats` mutates
the in-memory `PortalEntry` object but does not save it. The callback never called
`storage.save(entry)`, so every modification was silently discarded.

**Problem 2 — `loadedCount` instead of total chunk count**

`loadedCount` is `toLoad.size()` — the number of chunks that were not already owned and
therefore needed fetching. When all chunks in a zone's footprint were already resident
and owned by another zone, `toLoad` was empty: `loadedCount == 0`. The stats call became
`updateEntryStats(entry, 0)`, writing `ramMarginalMB = 0` (displays as `--`).

The correct value is `allChunks.size()` — the total footprint regardless of how many
chunks were newly fetched vs already shared.

**Problem 3 — Race between ChunkPreloader and TeleportInterceptor callbacks**

`predictiveLoad` returned `future` (the raw chunk-loading future) rather than the
`thenRunAsync` chained future. `TeleportInterceptor.predictiveLoadWithRam` chains its own
`thenRunAsync` on the returned future, calling `updateEntryStats(e, chunkCount)` and
`storage.save(e)`.

Because `predictiveLoad` returned the *unchained* future, both callbacks were attached to
the same `future` and ran concurrently on the executor. When `toLoad` was empty, `future`
was `CompletableFuture.completedFuture(null)` — already complete — so both callbacks were
scheduled immediately. Whichever saved last won:

- ChunkPreloader callback: `updateEntryStats(entry, 0)` → `ramMarginalMB = 0`, no save (bug 1)
- TeleportInterceptor callback: `updateEntryStats(e, chunkCount)` → correct values, saves

In practice the ChunkPreloader callback (no save) ran and discarded its result, and
TeleportInterceptor's save was correct. But zones promoted to HOT via registry restore on
startup — where no player had triggered `predictiveLoadWithRam` since restart — had
neither callback run, leaving all stats at default zero.

**Fix:**
1. Changed `updateEntryStats(entry, loadedCount)` to `updateEntryStats(entry, allChunks.size())`.
2. Added `storage.save(entry)` inside the `ifPresent` block after `updateEntryStats`.
3. Stored the chained future and returned it instead of `future`:
   ```java
   CompletableFuture<Void> chainedFuture = future.thenRunAsync(...).exceptionally(...);
   return chainedFuture;
   ```
   TeleportInterceptor's callback now chains after ChunkPreloader's completes, eliminating
   the race. Since ChunkPreloader now handles `ramMarginalMB` and `preloadCount`,
   TeleportInterceptor's callback was simplified to only set `ramEstimatedMB` and
   `lastActive` and save.

---

### RamCalc — Double 1.5× overhead and hardcoded byte constant

**Files:** `TeleportInterceptor.java`, `WarmZoneManager.java`

**Root cause:**
`config.getBytesPerChunk()` defaults to `98304` bytes. The config comment documents this
as `64 KB × 1.5 overhead` — the 1.5× multiplier is already baked into the config value.

`TeleportInterceptor.predictiveLoadWithRam` applied the multiplier again:
```java
double estimatedMB = (chunkCount * (double) config.getBytesPerChunk() * 1.5) / (1024.0 * 1024.0);
```
This inflated the PRED EST RAM estimate by 50% relative to the actual configured value.

`WarmZoneManager` used a hardcoded constant instead of the config:
```java
double estimatedMB = (chunkCount * 65536.0 * 1.5) / (1024.0 * 1024.0);
```
`65536 × 1.5 = 98304`, which happens to equal the default `bytesPerChunk`. This produced
a numerically correct result at default settings, but would silently diverge from
`updateEntryStats` if `bytesPerChunk` was changed in config. It also made EST RAM and
ACTUAL RAM always identical (since both evaluated to `chunkCount × 98304 / 1048576`),
rendering the separate EST column redundant.

**Fix:**
Both sites now compute:
```java
double estimatedMB = (chunkCount * (double) config.getBytesPerChunk()) / (1024.0 * 1024.0);
```
The formula matches `updateEntryStats` exactly. The EST RAM column was removed from the
UI as it was always equal to ACTUAL RAM; the remaining column is labelled "RAM".

---

## [1.1.6] - 2026-03-24

---

### Keepalive deduplication — overlapping zones pinged same chunk multiple times

**File:** `AsyncKeepaliveManager.java` — `groupChunksByWorld()`

**Root cause:**
`groupChunksByWorld()` iterated every portal entry for the requested tier and appended every
chunk in each zone's radius to the world's chunk list with no cross-zone dedup. When two zones
in the same world had overlapping radii, shared coordinates appeared in the list once per
owning zone. Each occurrence was scheduled as an independent `getChunkAsync` call by
`processWorldChunks`.

For a 2-zone overlap of N shared chunks, N additional async world-thread requests fired every
keepalive cycle — with no benefit, since `getChunkAsync` on an already-loaded chunk just
returns the existing reference.

**Fix:**
A `Map<String, Set<Long>>` per-world dedup set tracks which chunk indices have already been
added in the current `groupChunksByWorld` call. Index format:
`((long)(cx + dx) << 32) | ((cz + dz) & 0xFFFFFFFFL)` — matches `ChunkUtil.indexChunk`.
`Set.add()` returns `false` for a duplicate; only new additions are appended to the chunk list.

---

## [1.1.5] - 2026-03-23

---

### Heap guard — wrong formula caused abort on startup

**File:** `ChunkPreloader.java`

**Root cause:**
The JVM heap guard computed abort condition using `freeMemory < totalMemory * threshold`
rather than `(maxMemory - freeMemory) > maxMemory * threshold`. On startup the JVM has
not yet expanded its heap to `maxMemory`: `totalMemory` is small and `freeMemory`
approaches `totalMemory`. The condition using `totalMemory` on the right-hand side fired
at near-zero used-heap, aborting every preload before any zone was loaded.

**Fix:**
Guard uses `(maxMemory - freeMemory) / (double) maxMemory` — actual used fraction of the
configured JVM ceiling, independent of current heap expansion state.

---

### Enhanced preloader dedup skip — already-owned chunks loaded again

**File:** `EnhancedChunkPreloader.java`

**Root cause:**
The enhanced predictive and warm-load paths called `world.getChunkAsync(index)` without first
consulting `CacheManager.isOwned(zoneId, world, cx, cz)`. The dedup check present in
`ChunkPreloader.loadChunks()` was only in the base class; the enhanced path routed through
its own internal pipeline and bypassed it.

**Fix:**
Added ownership pre-check in the enhanced path's per-chunk loop: if `cacheManager.isOwned()`
returns true, increment the dedup metric counter and skip the async load call.

---

### TTL enforcer — stale tier entry after eviction

**File:** `CacheManager.java` — TTL eviction path

**Root cause:**
The TTL enforcer called `deregisterAllChunks(zoneId)` and `storage.delete(zoneId)` but did
not call `tierRegistry.remove(zoneId)`. The tier map entry (`COLD` from the last recorded
decay) persisted in memory indefinitely. Any subsequent `getZoneTier(zoneId)` returned `COLD`
rather than absent, and the zone appeared in `status` output with a tier but no ownership.

**Fix:**
TTL eviction path now also removes the zone from the tier registry immediately after
deregistering ownership.

---

### Dedup metric — counted loads instead of skipped chunks

**File:** `ChunkPreloader.java` — `dedupedChunks` counter

**Root cause:**
The counter incremented inside the `if (!isOwned)` branch — the path where a chunk was new
and needed to be loaded, not the path where it was skipped. Metric value was therefore
inverted: high dedup activity showed as 0, high new-load activity inflated the counter.

**Fix:**
Counter moved to the `else` (already-owned) branch.

---

### Metrics collector siloing — cache manager used its own instance

**File:** `CacheManager.java`

**Root cause:**
`CacheManager` called `new MetricsCollector()` in its constructor, creating an independent
instance. The plugin's `AsyncTeleportInterceptor` holds a reference to the plugin-level
`MetricsCollector`. bStats reporting and `/preload status` both read from the plugin-level
instance. Cache manager events (ownership changes, keepLoaded mutations) were recorded to
the isolated instance and were never aggregated or surfaced.

**Fix:**
`CacheManager` constructor now accepts the plugin-level `MetricsCollector` as a parameter
and uses it directly. The internal `new MetricsCollector()` call removed.

---

### Portal link race — volatile fields for decay-path safety

**File:** `AsyncTeleportInterceptor.java` — pending link map

**Root cause:**
Pending portal links were stored as mutable objects in a `ConcurrentHashMap`. The map
itself was thread-safe, but the fields on each pending link entry (`observationCount`,
`lastObservedNanos`) were plain `int`/`long`. The decay task and the link-recording path
ran on different executor threads. Writes on the recording thread were not guaranteed
visible to reads on the decay thread — no happens-before relationship between unrelated
executor threads on plain fields under the Java Memory Model.

**Fix:**
`observationCount` declared `volatile int`; `lastObservedNanos` declared `volatile long`.
Volatile on `long` is guaranteed atomic by the JVM spec on 64-bit platforms. The
read-compare pattern in the decay task (read once, compare, conditionally remove) is safe
with volatile; no lock needed since decay only reads and the recorder only increments.

---

### JVM Error during chunk load — forwarded to recovery handler

**File:** `ChunkPreloader.java` — catch block in load future chain

**Root cause:**
The `exceptionally()` handler's catch covered `Throwable`, so a `CompletionException`
wrapping an `OutOfMemoryError` would reach the error handler. The handler called
`circuitBreaker.recordFailure()`, incremented retry counters, and scheduled a backoff
retry — all wrong responses under OOM conditions where the JVM is under severe stress and
further allocation should be minimized.

**Fix:**
`exceptionally()` handler unwraps the cause: `if (cause instanceof Error)` → log at
`SEVERE` and return; do not forward to the error handler. `Exception` subtypes proceed
through normal recovery as before.

---

## [1.1.4] - 2026-03-22

---

### Same-world teleport — destination world null in TeleportRecord

**File:** `AsyncTeleportInterceptor.java`

**Root cause:**
Hytale's `adventure.teleporter` portal fires a `PlayerTeleportedEvent` where
`event.getDestinationWorld()` is `null` for same-world teleports (destination world ==
source world, so the field is omitted). The async TeleportRecord processor read
`destinationWorld` directly without a null check. Destination zone lookup failed silently,
`setZoneTier(HOT)` was never called, and the portal-link learning path was skipped
entirely for same-world portals.

**Fix:**
```java
String destWorld = record.getDestinationWorld() != null
    ? record.getDestinationWorld()
    : record.getSourceWorld();
```
Portal-link recording (`recordPortalLink`) also moved into the async path to match the
synchronous processor.

---

### Chunk pressure default — threshold below WARM zone baseline

**File:** `PluginConfig.java` — `chunkPressure.maxLoadedThreshold`

**Root cause:**
Default threshold was 512 loaded chunks. A single radius-5 zone holds up to 121 chunks.
With 4–5 WARM zones on a server, baseline loaded chunk count already exceeded 512 before
any HOT loading began. The abort guard in `loadChunks()` compared
`world.getLoadedChunkCount() >= config.maxLoadedThreshold`, so all preloads aborted
immediately on servers with modest WARM zone counts.

**Fix:**
Default raised to 2048. Field documented in `config.json` template.

---

### WARM strategy decay — WARM zones fell to COLD

**File:** `CacheManager.java` — decay logic, `WarmZoneManager.java`

**Root cause:**
The tier decay cycle decremented tiers based solely on elapsed time since last activity,
without consulting the zone's `WarmStrategy`. A WARM-strategy zone would decay
HOT → WARM → COLD on the same schedule as a PREDICTIVE zone. The WARM floor was not
enforced by the decay cycle or the chunk-eviction upgrade path.

**Fix:**
Decay cycle: `if (strategy == WarmStrategy.WARM && tier == WARM) skip`. Chunk-eviction
upgrade path: if zone strategy is WARM and computed tier would fall below WARM, clamp at
WARM before applying.

---

### Same-world approach detection — proximity trigger missed position-based portals

**File:** `AsyncTeleportInterceptor.java` — `checkProximityAndPreload()`

**Root cause:**
For position-to-position portals the approach check ran against the destination portal
device's block position, which is `null` for coordinate-only portal types. The null check
returned early without firing HOT promotion.

**Fix:**
Position-delta detection: player movement exceeding `TELEPORT_DISTANCE_THRESHOLD` blocks
in one poll cycle is treated as a teleport. Source position is saved post-teleport and
used to reverse-look up the originating portal entry via spatial proximity. Destination
position is cached after first traversal; subsequent approaches use the cached coordinate
for HOT promotion.

---

### Destination world not persisted — portal link lost on restart

**File:** `StorageBackend.java`, `AsyncTeleportInterceptor.java`

**Root cause:**
`PortalEntry.destinationWorldId` was populated at runtime from `PlayerTeleportedEvent`
data but omitted from the JSON/JDBC serialization schema. On restart, `loadAll()` returned
entries with `destinationWorldId == null`.

**Fix:**
Field added to serialization schema. Startup backfill: if `destinationWorldId` is null
but a confirmed portal link exists in the link registry, the field is populated and
re-saved on load.

---

### Unresolved link — HOT promotion skipped with no fallback

**File:** `AsyncTeleportInterceptor.java` — `checkProximityAndPreload()`

**Root cause:**
`getPortalLink(entryId)` returns null before a link is confirmed (pre-threshold). The
approach check returned early when the link was absent, leaving the destination zone at
WARM. First-use experience: players approaching an unvisited portal got no predictive
load until the link was confirmed.

**Fix:**
When link is absent, fall back to promoting all destination zones whose `worldId` matches
`entry.destinationWorldId` (persisted since this version). Live block backfill: when the
chunk containing the portal device loads, read `BlockComponent(PortalDeviceComponent.TYPE)`
for the destination world UUID and persist immediately — link available on first approach
after the world loads, before any player traversal.

---

## [1.1.3] - 2026-03-21

---

### Destination world — lost on every restart (different field from 1.1.4)

**File:** `StorageBackend.java`

**Root cause:**
`portalDestinationWorld` (live world name string for direct world lookup, distinct from
`destinationWorldId` UUID) was stored in a runtime `Map<String, String>` on the plugin
instance but never serialized. Map rebuilt from `PlayerTeleportedEvent` observations at
runtime; until a player traversed the portal, world-to-zone lookup fell back to scanning
all registered worlds by name.

**Fix:**
Field serialized as part of `PortalEntry`. Populated on first teleport event and on
`PortalDeviceComponent` block scan.

---

### Per-zone activation overrides — lost on restart

**File:** `PluginConfig.java`, storage layer

**Root cause:**
Per-zone activation distances, trigger shapes, floor/ceiling, and facing requirements
were stored in a `Map<String, ZoneActivationSettings>` on the `PluginConfig` instance.
This map was not persisted. `/preload activation` and `/preload shape` wrote to the
in-memory map only, which was discarded on shutdown.

**Fix:**
`ZoneActivationSettings` serialized as a nested field on `PortalEntry`. All per-zone
override commands now write through to storage immediately.

---

### Bulk save partial commit — no transaction

**File:** `StorageBackend.java` — `saveAll()`

**Root cause:**
`saveAll()` iterated entries and called `save()` per entry as independent JDBC statements
(or sequential JSON writes). A constraint violation or IOException mid-iteration committed
already-processed entries and silently dropped the remainder.

**Fix:**
JDBC backend: `saveAll()` opens a transaction, executes all `save()` calls within it, and
rolls back fully on any exception. JSON backend: accumulates all entries, writes to a temp
file, fsyncs, then renames atomically — all or nothing.

---

### Cooldown ghost entry — null player ID

**File:** `TeleportInterceptor.java` — cooldown map

**Root cause:**
`recordCooldown(playerId)` inserted a `SENTINEL_UUID` when `playerId` was null — for
example on scripted or server-side zone activations that had no player context. The
sentinel was never matched by real-player lookups and never expired by player disconnect
cleanup. One permanent ghost entry per affected zone accumulated over server uptime.

**Fix:**
`if (playerId == null) return;` at the top of `recordCooldown` and `isOnCooldown`.

---

### Concurrent load double-pin — two zones race on first-owner check

**File:** `CacheManager.java` — `registerOwnership(..., chunk)`

**Root cause:**
The `isFirst[0]` pattern inside `compute()` was not atomic across the check and the
`addKeepLoaded()` call (see 1.1.7 Gap 2 for the full analysis). The 1.1.3 fix addressed
the case where two zone registrations for the same chunk could both observe an empty
owners set and both call `addKeepLoaded()`. The 1.1.7 fix later generalized this to
full per-zone pinning.

**Fix (1.1.3):**
`ConcurrentHashMap.newKeySet().add(zoneId)` used as the atomicity primitive: `add()`
returns `true` only once per element across concurrent callers. `addKeepLoaded()` called
only when `add()` returns `true`.

---

### Load balancer EMA race — reads outside the write lock

**File:** `AsyncLoadBalancer.java`

**Root cause:**
1.1.2 serialized EMA writes via `synchronized (this)`. Reads of `averageExecutionTime`
from the batch-size calculator and metrics reporter accessed the field directly (no lock).
Under concurrent task completions a read could observe a partially-written `double` (the
JMM does not guarantee atomic reads of `double` without `volatile` or synchronization).

**Fix:**
All reads of `averageExecutionTime` moved inside `synchronized (this)`, or the field
declared `volatile double`.

---

### Keepalive fixed-rate to fixed-delay

**File:** `KeepaliveManager.java`

**Root cause:**
`scheduleAtFixedRate` fires at wall-clock intervals regardless of task duration. If a
scan took longer than its period, the next scan was queued to start immediately on
completion. Three tiers could produce back-to-back scans with no gap under load.

**Fix:**
Changed to `scheduleWithFixedDelay` — delay always starts after the previous execution
completes, guaranteeing a minimum idle gap between scans.

---

### JSON file flush before rename — crash safety

**File:** `JsonStorageBackend.java`

**Root cause:**
`Files.write(tmp, bytes)` followed by `Files.move(tmp, target, ATOMIC_MOVE)`. `Files.write`
closes the stream but does not call `fsync`. The OS page cache may buffer the write; an
unclean shutdown after the directory rename completes but before the buffer is flushed
produces an empty or truncated target file.

**Fix:**
```java
try (FileChannel ch = FileChannel.open(tmpPath, WRITE, CREATE)) {
    ch.write(ByteBuffer.wrap(bytes));
    ch.force(true);  // fsync data + metadata
}
Files.move(tmpPath, targetPath, ATOMIC_MOVE, REPLACE_EXISTING);
```

---

### Portal detection scan overlap — fixed-rate scheduler

**File:** `AsyncTeleportInterceptor.java` — portal scan task

Same root cause as the keepalive overlap above (`scheduleAtFixedRate`). Changed to
`scheduleWithFixedDelay`.

---

### Shutdown — in-flight tasks cut off immediately

**File:** `PreloadPlugin.java` — `onDisable()`

**Root cause:**
`executor.shutdownNow()` interrupts running threads immediately. Chunk preloads,
keepalive pings, and storage writes in progress were interrupted mid-execution, leaving
ownership records or storage potentially half-written.

**Fix:**
```java
executor.shutdown();
try {
    executor.awaitTermination(10, TimeUnit.SECONDS);
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();
} finally {
    executor.shutdownNow();
}
```

---

### World removal — empty queue entry not cleaned up

**File:** `WorldThreadBridge.java` — pending operation queue

**Root cause:**
`worldQueues` map retained an empty `Queue` object after a world was removed and its
queue drained. The processing loop iterated all map keys including dead world entries;
each iteration called `queue.isEmpty()` (true, no-op) but still paid the iteration cost.
Entry count grew with server uptime on world-cycling servers.

**Fix:**
After draining, `worldQueues.remove(worldName)` called under the map's write lock.

---

### Predictive zones — COLD tier not preserved on world removal

**File:** `CacheManager.java` — world-removal handler

**Root cause:**
World-removal handler called `deregisterAllChunks(zoneId)` and `tierRegistry.remove(zoneId)`
for all zones in the removed world. WARM zones were re-registered by the portal scanner
when the world came back. PREDICTIVE zones had no re-registration path — they were absent
from the tier registry until a player triggered them again.

**Fix:**
For PREDICTIVE zones, `setZoneTier(zoneId, COLD)` instead of `tierRegistry.remove()`.
Ownership records still cleaned up. WARM zones unaffected (re-registration covers them).

---

### Radius-based deregister — keepLoaded pins not released

**File:** `CacheManager.java` — `deregisterZoneByRadius()`

**Root cause:**
`deregisterZoneByRadius()` removed ownership records and reverse-index entries for all
chunks in the radius loop but never called `tryReleaseKeepLoaded()`. The engine was not
told the chunks no longer needed to stay loaded; `keepLoaded` remained elevated and the
chunks stayed resident indefinitely.

**Fix:**
After `owners.remove(zoneId)` returns `true` in the radius loop, call
`tryReleaseKeepLoaded(world, cx, cz)` — matching the pattern in `deregisterOwnership()`.

---

## [1.1.2] - 2026-03-21

---

### COLD zones — not restored from storage on startup

**File:** `CacheManager.java` — startup tier restore

**Root cause:**
`loadTiersFromStorage()` filtered with `if (entry.getTier() == COLD) continue;` —
inadvertently treating `COLD` the same as a null/missing tier. HOT and WARM zones were
restored; every COLD zone was silently dropped and treated as unregistered until replayed
by a player or keepalive event.

**Fix:**
Filter changed to `if (entry.getTier() == null) continue;`.

---

### Dedup double-ownership — registered at check and again on load completion

**File:** `ChunkPreloader.java` — dedup path

**Root cause:**
When the dedup check found an already-owned chunk, it called `registerOwnership` for the
new zone immediately and then `continue`'d — but the `thenRun` post-load callback that
also called `registerOwnership` had already been chained to the outer future before the
loop reached the `continue`. The callback fired unconditionally, registering ownership a
second time and calling `addKeepLoaded` again (double-pin under the old single-pin model).

**Fix:**
Post-load `thenRun` registration placed only outside the dedup branch. The dedup branch
registers once via the no-chunk overload and skips the async load entirely.

---

### Circuit breaker OPEN→HALF_OPEN — TOCTOU race

**File:** `CircuitBreaker.java`

**Root cause:**
```java
if (state == OPEN && System.nanoTime() - openedAt >= resetTimeoutNs) {
    state = HALF_OPEN;
    successCount = 0;
}
```
Two threads can both pass the time check before either writes `HALF_OPEN`, each
independently resetting the success counter. Recovery threshold (`N consecutive successes`)
becomes unreliable.

**Fix:**
`AtomicReference<State>` with `compareAndSet(OPEN, HALF_OPEN)` — exactly one thread
transitions; others observe `HALF_OPEN` and proceed without resetting the counter.

---

### Dual circuit breaker — error handler and bridge had separate instances

**File:** `WorldThreadBridge.java`, error handler class

**Root cause:**
Both the error handler and the bridge called `new CircuitBreaker(config)` in their
respective constructors. Two independent instances, two independent failure counters.
Failures recorded by the error handler had no effect on the bridge's breaker. The breaker
never opened in practice.

**Fix:**
Circuit breaker constructed once in `PreloadPlugin.java` and injected into both
components. Single shared instance; failures from either path contribute to the same
counter.

---

### World lookup — O(N) scan per player update

**File:** `AsyncTeleportInterceptor.java` — `getPlayerWorld()`

**Root cause:**
```java
for (World world : universe.getLoadedWorlds()) {
    if (world.getEntityStore().containsPlayer(playerId)) return world;
}
```
Called on every position-poll cycle for every batched player. O(N×P) per cycle.

**Fix:**
`player.getWorld()` — O(1) direct reference on the `Player` object.

---

### Preload batches — all submitted simultaneously

**File:** `ChunkPreloader.java` — `loadChunks()`

**Root cause:**
All batch futures were submitted before any completed, flooding the world thread task
queue with one large burst rather than spaced batches.

**Fix:**
Sequential chaining via `thenComposeAsync`: each batch future starts only after the
previous one completes.

---

### Player batch — same players always selected

**File:** `AsyncTeleportInterceptor.java` — player batch selection

**Root cause:**
`playerMap.entrySet().stream().limit(batchSize)` — `ConcurrentHashMap` iteration is
stable (hash-bucket order). The same front-of-map players were always selected; players
further back were never included.

**Fix:**
`AtomicInteger batchCursor` advances by `batchSize` each cycle.
Selection: `stream().skip(cursor % size).limit(batchSize)` with wraparound.

---

### WAL atomic write — not crash-safe

**File:** `WriteAheadLog.java`

Same root cause as the JSON storage fsync issue (temp-file write without `fsync` before
rename). `FileChannel.force(true)` added before `Files.move`.

---

### Null player ID — sentinel ghost in cooldown map

**File:** `TeleportInterceptor.java`

Same root cause as 1.1.3 fix. Null guard added to the synchronous path in 1.1.2; async
path covered in 1.1.3.

---

### First-attempt log noise — INFO on every routine operation

**File:** `RetryPolicy.java`

**Root cause:**
`LOG.info("Attempting: " + name + " attempt " + attempt)` logged unconditionally before
the first try. On a 200-portal server with regular keepalive cycles this generated
hundreds of INFO lines per cycle.

**Fix:**
First attempt logged at `FINE`; retries (attempt ≥ 2) logged at `INFO`.

---

### EMA execution-time average — non-atomic read-modify-write on volatile

**File:** `AsyncLoadBalancer.java`

**Root cause:**
```java
volatile double averageExecutionTime;
// in thenApply (concurrent completions):
averageExecutionTime = ALPHA * latency + (1 - ALPHA) * averageExecutionTime;
```
`volatile` guarantees visibility of the last write but not atomicity of the
read-modify-write. Concurrent completions each read the same stale base value, apply
their sample independently, and one's write overwrites the other. EMA drifts under load.

**Fix:**
Read-modify-write synchronized on `this`. (1.1.3 extended synchronized coverage to all
reads as well.)

---

## [1.1.1] - 2026-03-21

---

### Event-driven chunk retention — eviction hook replaces keepalive timer as primary guard

**File:** `ChunkEvictionListener.java` (new), `KeepaliveManager.java`

**Root cause:**
The keepalive timer called `getChunkAsync` on a fixed interval to reset the engine's
eviction countdown. If the engine's per-world eviction idle-timeout was shorter than the
keepalive interval, chunks could be evicted in the gap. Timer at 5 / 15 / 60 minutes
was too coarse for tight engine eviction windows.

**Fix:**
`ChunkEvictionListener` subscribes to the engine's `ChunkUnloadingEvent`, which fires
synchronously before eviction. Handler checks `CacheManager.isOwned(chunk)`: if owned,
re-pins via `addKeepLoaded()` or cancels the event depending on the engine contract.
Keepalive intervals extended to 30 / 60 / 120 minutes as belt-and-suspenders fallback.

---

### Portal block scanning — live BlockComponent vs. resource heuristic

**File:** `PortalScanner.java`

**Root cause:**
Destination world lookup read chunk resource tags (world-generation metadata attached to
chunks) rather than the portal device's `BlockComponent`, which directly stores the
destination world UUID. Resource tags are absent on imported or manually-placed portal
devices; those portals had no detectable destination.

**Fix:**
`chunk.getBlockComponent(PortalDeviceComponent.TYPE)` reads `destinationWorldId` directly.
Resource-tag fallback retained for portal types that do not use `PortalDeviceComponent`.

---

### Warm load validity gate — fired on zones with no destination configured

**File:** `WarmZoneManager.java` — `isDestinationWorldValid()`

**Root cause:**
Gate ran for all portal-type zones. For zones with `destinationWorldId == null`, the
resolver called `universeRegistry.getWorld(null)`, which either threw NPE or returned an
unrelated world. If that world's spawn was null the gate returned false, silently skipping
valid zones that had no explicit destination.

**Fix:**
`if (entry.getDestinationWorldId() == null) return true;` — gate bypassed when no
destination is configured.

---

### Full zone footprint indexed on auto-registration

**File:** `CacheManager.java` — `registerPortalZone()`

**Root cause:**
Auto-registration from `ChunkPreLoadProcessEvent` called `registerOwnership` for the
single chunk in the event. Neighbouring chunks within the zone radius were not indexed
until the zone's dedicated preload pass ran. Engine-loaded neighbours in the interim
could be evicted without OptiPortal intervention.

**Fix:**
`registerPortalZone()` iterates the full `[-radiusX..radiusX] × [-radiusZ..radiusZ]`
grid. For chunks not yet in memory, ownership is recorded without calling `addKeepLoaded`
(no live `WorldChunk` object available); the pin is added when each chunk subsequently
loads via the `ChunkPreLoadProcessEvent` path.

---

## [1.1.0] - 2026-03-20

---

### Death/respawn preload radius — hardcoded, ignored config

**File:** `TeleportInterceptor.java` — `onPlayerDeath()`, `onPlayerRespawn()`

**Root cause:**
`loadChunks(world, x, z, 7)` — literal `7` rather than `config.getPredictiveRadius()`.
The config key existed and parsed correctly but this call site was missed during the
initial implementation.

**Fix:**
`loadChunks(world, x, z, config.getPredictiveRadius())`

---

### Vertical activation — async path used global override

**File:** `AsyncTeleportInterceptor.java` — staggered update loop

**Root cause:**
The staggered-update path passed no `entry` to the vertical distance getter, calling the
global-default overload. Per-zone vertical overrides set via `/preload activation` were
only respected on the synchronous path.

**Fix:**
Staggered loop passes `entry` to `getActivationDistanceVertical(entry)`.

---

### Disconnect — multiple tracking maps not fully cleared

**File:** `AsyncTeleportInterceptor.java` — `onPlayerQuit()`

**Root cause:**
`onPlayerQuit()` removed the player's position and teleport-nanos entries but left the
cooldown entry, in-flight future reference, and pending teleport record. These accumulated
across player sessions.

**Fix:**
All five per-player maps cleared atomically in a single synchronized block on quit.

---

## [1.0.9] - 2026-03-20

---

### Vertical activation — fixed constant in proximity check

**File:** `TeleportInterceptor.java` — `checkProximityAndPreload()`

**Root cause:**
`Math.abs(player.getY() - portal.getY()) < 16` — literal constant. Per-zone vertical
overrides and the global `activationDistanceVertical` config value were both ignored on
this path.

**Fix:**
`Math.abs(player.getY() - portal.getY()) < getActivationDistanceVertical(entry)`

---

### Asymmetric radius — radiusZ stored but never applied

**File:** `ChunkPreloader.java` — `loadChunks()`, `CacheManager.java`

**Root cause:**
`loadChunks()` used `entry.getRadius()` (single-axis, returned `radiusX`) for both the
X and Z loop bounds. `PortalEntry` had separate `radiusX` and `radiusZ` fields but
`radiusZ` was never consulted. All zones were loaded as squares regardless of configured
depth.

**Fix:**
Loop bounds: `dx ∈ [-radiusX, radiusX]`, `dz ∈ [-radiusZ, radiusZ]`.
`getRadius()` kept as `@Deprecated` returning `radiusX` for backward compatibility.

---

### Portal link confidence — saved on first observation

**File:** `AsyncTeleportInterceptor.java` — `recordPortalLink()`

**Root cause:**
`storage.save(link)` called immediately on the first `PlayerTeleportedEvent` pairing a
source and destination. False links from accidental proximity or scripted teleports were
persisted and loaded on next restart.

**Fix:**
Pending link accumulator: `pendingLinks.merge(sourceId, new PendingLink(destId, 1), (e, n) -> { e.count++; return e; })`.
`storage.save()` called only when `e.count >= config.getConfidenceThreshold()`.
Decay task cleans pending links inactive for `config.getPendingDecayDays()` days.

---

## [1.0.8] - 2026-03-20

---

### Keepalive never started — constructed but not scheduled

**File:** `PreloadPlugin.java` — `onEnable()`

**Root cause:**
`keepaliveManager = new AsyncKeepaliveManager(...)` constructed but `keepaliveManager.start()`
never called. The scheduler registration inside `start()` never ran. No HOT/WARM heartbeat
pings fired for the lifetime of the server since 1.0.4.

**Fix:**
`keepaliveManager.start()` called after construction in `onEnable()`.

---

### Gravestones integration — always null at construction time

**File:** `PreloadPlugin.java` — `onEnable()`

**Root cause:**
`Bukkit.getPlugin("Gravestones")` called during `onEnable()`. If Gravestones loaded after
OptiPortal in plugin initialization order, the call returned null. `TeleportInterceptor`
stored null permanently; every gravestone check was silently a no-op.

**Fix:**
Gravestones reference resolved lazily on first use via a `getGravestones()` accessor that
calls `Bukkit.getPlugin` at call time rather than at construction.

---

### Warp file watcher callback — lost on plugin reload

**File:** `PreloadPlugin.java` — `onReload()`

**Root cause:**
On reload, `new WarpFileWatcher()` (no-arg constructor) was used instead of the
single-arg constructor that accepts the cache-invalidation `Runnable`. The new watcher
instance had no callback; warp changes no longer triggered `refreshPortalCache()` after
any reload.

**Fix:**
Reload path uses the same single-arg constructor, passing the cache-invalidation callback.

---

### In-memory chunk fast-path — redundant async load for resident chunks

**File:** `ChunkPreloader.java` — `loadChunks()`

**Root cause:**
Every chunk was submitted to `world.getChunkAsync(index)` unconditionally. For WARM zones
whose chunks are always in memory, this dispatched to the world thread via
`CompletableFuture` overhead with no actual I/O benefit.

**Fix:**
Pre-check: `world.getChunkStore().getChunkReference(engineIndex) != null` — chunk in
store. If present: `world.getChunkIfInMemory(engineIndex)` on the world thread (via
`world.execute`) for the live `WorldChunk` reference, then `registerOwnership` with the
chunk object. Skip `getChunkAsync`. Chunks mid-save: reference is non-null during save;
treated as resident.

---

### Enhanced preload backoff guard — missing skip for failed positions

**File:** `EnhancedChunkPreloader.java`

**Root cause:**
`ChunkPreloader.loadChunks()` checked `backoffManager.isInBackoff(cx, cz)` before
submitting. `EnhancedChunkPreloader.loadChunksAsync()` had no such check — broken
positions were retried every pass, each retry extending the backoff window but never
being skipped.

**Fix:**
Backoff check added at the top of the enhanced path's per-chunk loop.

---

### Ownership auditor — encoding mismatch, never detected evictions

**File:** `CacheManager.java` — `runOwnershipAudit()`

**Root cause:**
Auditor looked up chunks using `packChunkIndex(cx, cz)` (CacheManager's own format:
`((long)(cx & 0xFFFFFFFF)) | ((long)(cz & 0xFFFFFFFF) << 32)`) against
`world.getChunkStore().getChunkReference(index)`, which expects `ChunkUtil.indexChunk`
format (`(long)cx << 32 | cz & 0xFFFFFFFFL`). All lookups returned null — auditor
saw every owned chunk as evicted, but in practice the auditor was never scheduled so
no incorrect releases occurred.

**Fix:**
Auditor uses `ChunkUtil.indexChunk(cx, cz)` for all engine-side lookups.
Auditor scheduled task started.

---

### Async metrics double-count — caller and WorldThreadBridge both recorded

**File:** `ChunkPreloader.java`, `AsyncKeepaliveManager.java`

**Root cause:**
`WorldThreadBridge.getChunkAsync()` records a success/error metric with real round-trip
latency when the future resolves. Callers in `loadChunks()` and `pingChunkBatch()` also
called `metrics.recordChunkLoad(0)` immediately after chaining — a zero-latency duplicate
for every chunk.

**Fix:**
Duplicate `metrics.recordChunkLoad(0)` calls removed from callers. Bridge is the sole
recorder.

---

### RAM estimate — 121 storage queries per zone load

**File:** `ChunkPreloader.java` — post-load callback

**Root cause:**
`thenRun` after each chunk load called `storage.loadAll()` to find the zone entry, update
the RAM estimate, and `storage.save(entry)`. O(Z×C) storage reads per zone load.

**Fix:**
Post-load callback uses `storage.loadById(zoneId)` — one targeted lookup. RAM accumulated
in a per-zone `AtomicLong` during the load pass, committed in a single `storage.save()` in
the `allOf` completion handler.

---

### Warp sync — deleted all non-warp entries

**File:** `WarpFileSyncer.java`

**Root cause:**
Sync deletion loop: for each storage entry whose ID was absent from the current warp file,
call `storage.delete()`. No type filter. Portal device zones, death records, respawn
records, and auto-registered portal zones were all deleted every 30-second sync cycle.

**Fix:**
Deletion condition: `entry.getType() == EntryType.PORTAL && !warpIds.contains(entry.getId())`.
All other entry types skipped.

---

### Portal link registry — plain HashMap with concurrent access

**File:** `PortalLinkRegistry.java`

**Root cause:**
`HashMap<String, String>` accessed from multiple executor threads (teleport recorder,
link decay task, proximity check). Concurrent `put`/`get` under resize is undefined
under the JMM; `ConcurrentModificationException` possible during iteration.

**Fix:**
`ConcurrentHashMap<String, String>`. Compound check-then-update operations (confidence
threshold guard) synchronized on the registry instance.

---

### JSON backend — unsynchronized LinkedHashMap

**File:** `JsonStorageBackend.java`

**Root cause:**
`entries` (`LinkedHashMap<String, PortalEntry>`) accessed from multiple threads without
synchronization across all five public methods. Concurrent `put` and iteration risked
`ConcurrentModificationException` and lost writes.

**Fix:**
All five methods declared `synchronized`. `loadAll()` returns a defensive copy.

---

### AsyncKeepaliveManager override target was private

**File:** `KeepaliveManager.java` — `pingTier()`

**Root cause:**
`AsyncKeepaliveManager.pingTier()` declared `@Override` but `KeepaliveManager.pingTier()`
was `private`. In Java a subclass method cannot override a `private` superclass method —
it declares a new, unrelated method. The scheduler in `KeepaliveManager.start()` dispatched
to `this.pingTier()`, always calling the private base implementation.

**Fix:**
`KeepaliveManager.pingTier()` changed to `protected`. `@Override` in
`AsyncKeepaliveManager` is now valid.

---

### AsyncTeleportInterceptor poll override — same private method issue

**File:** `TeleportInterceptor.java` — `pollTeleportRecords()`

Same root cause. `protected` visibility applied.

---

## [1.0.7] - 2026-03-19

---

### Block-to-chunk conversion — wrong chunk size used everywhere

**File:** `ChunkPreloader.java`, `TeleportInterceptor.java`, `CacheManager.java`

**Root cause:**
Zone center and preload coordinates computed as `blockCoord / 16`. Hytale uses a chunk
size of 32 blocks. The correct conversion is `Math.floorDiv(blockCoord, 32)`. Every zone
preloaded the wrong 32×32 area — up to 16 blocks offset in each axis. On negative block
coordinates, integer division (`/`) truncates toward zero rather than toward negative
infinity, producing an additional off-by-one compared to `floorDiv`.

**Fix:**
`ChunkPreloader.toChunkCoord(int blockCoord)` added as the single canonical converter:
`return Math.floorDiv(blockCoord, 32)`. All call sites updated.

---

### keepLoaded pin — first implementation

**File:** `CacheManager.java`, `ChunkPreloader.java`

**Root cause (pre-1.0.7 behavior):**
Chunks were retained only by periodic `getChunkAsync` pings. Between pings the engine
could evict any chunk whose access count dropped to zero. Chunks were regularly evicted
and reloaded, causing unnecessary disk I/O and world-thread spikes.

**Implementation:**
`WorldChunk.addKeepLoaded()` / `removeKeepLoaded()` — `AtomicInteger` reference counter;
`ChunkUnloadingSystem.shouldKeepLoaded()` blocks eviction when count > 0.
`registerOwnership(..., chunk)` calls `chunk.addKeepLoaded()` on first registration.
`tryReleaseKeepLoaded()` dispatches `removeKeepLoaded()` to the world thread via
`world.execute()` on last-owner deregistration.

---

### keepLoaded release — HOT→WARM→COLD decay path missed

**File:** `CacheManager.java` — `setZoneTier()`

**Root cause:**
`tryReleaseKeepLoaded()` was called from `deregisterOwnership()` (explicit removal) but
not from the tier-decay path. A zone decaying WARM → COLD kept its keepLoaded pins
indefinitely. Chunks were never released from memory through normal tier decay, causing
unbounded memory growth on servers with zones that cycled through active and idle periods.

**Fix:**
`setZoneTier(id, COLD)` now calls `deregisterAllChunks(id)`, which triggers
`tryReleaseKeepLoaded` for each owned chunk.

---

### Eternal world decay exemption

**File:** `CacheManager.java` — decay cycle

**Root cause:**
Hub/lobby worlds configured as eternal (engine never cycles their chunks) had their zones
processed through the normal HOT → WARM → COLD decay, producing `setZoneTier` calls every
`pollIntervalSeconds` with no meaningful effect (engine would never evict regardless of
keepLoaded state). Pure overhead.

**Fix:**
Decay cycle: `if (worldRegistry.getWorld(worldName).getChunkLifecyclePolicy() == ETERNAL) continue;`

---

### Portal zone lookup — O(N) scan per chunk load event

**File:** `CacheManager.java` — `ChunkPreLoadProcessEvent` handler

**Root cause:**
Handler called `storage.loadAll()` and linearly scanned every registered zone to find
which zone owned the newly-loaded chunk's coordinate. O(N) per event. High chunk-load
rates (world generation, player travel) produced O(N) storage reads per chunk.

**Fix:**
Reverse index `Map<String, Map<Long, String>>` (world → chunkIndex → zoneId) maintained
in `CacheManager`. Built at startup; updated on registration/deregistration. Handler
lookup is O(1).

---

### Native warp sync — file polling replaced with live registry

**File:** `WarpFileSyncer.java`

**Root cause:**
Previous implementation polled `warps.json` every 30 seconds via `Files.readAllBytes()`.
File reads racing an external write produced truncated JSON. The JSON file is the
persistence target — reading it back is redundant when the in-memory `WarpRegistry` is
available.

**Fix:**
`WarpRegistry.getAll()` (in-memory, O(1)) as primary source. File polling retained as
fallback for older engine versions where `WarpRegistry` is unavailable.

---
