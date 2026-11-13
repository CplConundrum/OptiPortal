# OptiPortal LLM Wiki

Purpose: concise reference for LLMs, agents, and future review passes working in this repository.

Last updated: 2026-11-12 (repo field + improvement-pass notes)  
Source basis: current workspace code (v1.3.0, 0.7.7 port + 2026-11-12 improvement pass), maintained by the author

---

## 1. What OptiPortal Is

OptiPortal is a Hytale server optimization plugin focused on pre-loading portal and teleport destination chunks so teleport-related movement does not cause chunk-load spikes.

Core characteristics:

- async-heavy design
- world-thread work is intentionally minimized
- chunk ownership and cache tiers are tracked
- WARM / HOT / COLD zone concepts are central
- storage and metrics are integrated into preload flows
- canonical git repo: `https://github.com/CplConundrum/OptiPortal` (public, `main` @ v1.2.3 — pre-port baseline; the local `PreloadPlugin3` tree is v1.3.0 source of truth)

---

## 2. Canonical Current Status

Overall state:

- generally production-capable
- several previously serious issues are already fixed
- no obvious high-severity confirmed plugin defect stands out in the current workspace
- the remaining concerns are configuration/runtime semantics, watch-list risks, or maintenance debt
- some older findings are stale and should not be repeated as active bugs
- the recent batching and reverse-preload regressions were fixed in the current workspace
- a full improvement pass ran on 2026-11-12 (Stage 1 cache-tier fixes S1–S5, Stage 2 async-core
  fixes A1–A6, Tier 1/2 hardening, and the `async.executorThreadCount` config knob); 4 dormant
  classes were archived to `_deleted-20261112/`; see §6 "Fixed in the 2026-11-12 improvement pass"

Current assessment:

- Code quality: strong overall
- Reliability: good, with a few targeted follow-ups
- Review confidence: moderate to high for code-backed findings, lower for engine-bound concurrency claims

---

## 3. Highest-Priority Current Issues

### 3.1 Reload semantics for startup-wired services

Status: confirmed current issue  
Priority: medium  
File: `src/main/java/com/optiportal/OptiPortal.java`

Current behavior:

- `tpsMonitor.enabled`, `tpsMonitor.lowThreshold`, `tpsMonitor.criticalThreshold`, and `integrations.portalChunkListener.enabled` are startup-wired settings
- `/preload reload` documents them as restart-required and calls out when any of those values changed during reload

Why it matters:

- these features are config-gated, but not hot-reloadable
- future reviews should not misstate them as live-toggle settings unless code changes

Preferred fix:

- keep restart-only wording accurate, or add explicit live rewiring if that complexity is justified

Interpretation note:

- this is a configuration/runtime semantics issue, not a known hot-path correctness defect

---

## 4. Medium-Priority Current Concerns

### 4.1 Non-volatile engine world-state reads

Status: partially mitigated, residual risk  
Priority: medium → low  
Files:

- `src/main/java/com/optiportal/async/WorldTpsMonitor.java`
- `src/main/java/com/optiportal/async/WorldThreadBridge.java`
- `decompiled/com/hypixel/hytale/server/core/universe/world/World.java`

Facts:

- The `WorldTpsMonitor` was updated in v1.1.8 to read tick duration from `World.getBufferedTickLengthMetricSet().getLastValue()` (a `HistoricMetric`) instead of sampling `World.getTick()` directly. This removes the delta-tick jitter guard and reduces the concern around stale `tick` field reads.
- The monitor still reads `world.isTicking()` and `world.isPaused()` to skip non-ticking worlds — these backing fields remain plain (non-volatile) booleans in the decompiled engine source.
- `WorldThreadBridge.getChunkAsync()` also gates on `world.isTicking()`.
- Current source comments document those reads as advisory engine-state gates, not synchronization boundaries.

Interpretation guidance:

- the stale-tick-count concern (E2 in `OPTIPORTAL_COMPLETE_REFERENCE.md`) is now largely resolved by the `HistoricMetric` migration
- the residual risk is stale `isTicking`/`isPaused` reads on stopped worlds
- treat as a low-priority engine-coupled visibility risk; do not claim permanent throttling or confirmed breakage unless logs or repros prove it

---

### 4.2 Retry behavior around engine chunk backoff

Status: tightened in current workspace  
Priority: low watch-list  
File: `src/main/java/com/optiportal/async/AsyncErrorHandler.java`

Facts:

- `handleChunkLoadError(...)` now short-circuits known `"Chunk failure backoff"` errors before scheduling plugin retry work
- retry path now checks `isChunkOnBackoff(...)`
- retry continuations also stop if a retry attempt receives an engine backoff failure

Interpretation guidance:

- keep as a low watch-list item for future engine changes
- do not describe this as an active medium defect without new evidence

---

### 4.3 Hot-path distance cleanup status

Status: current primary interceptor path appears reconciled  
Priority: low watch-list  
Files:

- `src/main/java/com/optiportal/teleport/TeleportInterceptor.java`
- `src/main/java/com/optiportal/teleport/AsyncTeleportInterceptor.java`

Current source check:

- `TeleportInterceptor` no longer has `Math.hypot(...)` matches
- current threshold-style checks use squared-distance comparisons in the primary path
- treat older `Math.hypot(...)` findings as stale for `TeleportInterceptor` unless a new match is found

Interpretation guidance:

- keep distance math on the watch list because this area has regressed before
- do not list `Math.hypot(...)` in `TeleportInterceptor` as an active issue without re-checking source
- if reviewing `AsyncTeleportInterceptor`, remember it is currently a dormant subsystem, not the live primary path

---

## 5. Low-Priority Cleanup / Maintenance Debt

### Dormant subsystems

File: `src/main/java/com/optiportal/OptiPortal.java`

Dormant list currently documented in code:

- `ChunkOwnershipAuditor`
- `EnhancedChunkPreloader`
- `AsyncTeleportInterceptor`
- `AsyncKeepaliveManager`

**2026-11-12 (improvement pass):** all four of the above were verified to have zero production
instantiations and were moved to `_deleted-20261112/` (archived, not hard-deleted — no git
history in this tree to restore from). They are no longer in `src/main`. `KeepaliveManager`
(the non-async variant, `preload/KeepaliveManager.java`) **remains in-tree by user ruling
(2026-11-12): it is deliberately kept, not a deletion candidate.** It exists to periodically
re-ping the chunks of portal-linked zones (one scheduled task per tier) so the engine's chunk
GC cannot evict them between player visits — i.e. it keeps the zones a portal links to in
memory to avoid the TPS hit of a reload on the next teleport. Its zero-instantiation state is
a wiring gap (re-enabling it is a live-feature task), not evidence the class is dead. Only a
test constructs it directly.

Config-gated rather than fully dormant:

- `PortalChunkListener` world-event activation (service object is always constructed and available for cleanup/index paths; only world-event hooks are opt-in via `integrations.portalChunkListener.enabled`)

No longer dormant (now live):

- `AsyncLoadBalancer`, `AsyncErrorHandler`, `AsyncMetrics` — instantiated at startup and exposed via public getters
- `WorldTpsMonitor` — started at startup when `tpsMonitor.enabled = true`

Interpretation guidance:

- real maintenance concern for the still-dormant systems
- not, by itself, evidence of a broken runtime architecture
- do not conflate the still-dormant subsystems with the async services that are now live

### Death tracking path

Canonical path:

- `GravestoneIntegration` and `BetterGravestoneWatcher` handle death-location cache lifecycle.
- The redundant dormant ECS `PlayerDeathSystem` class was removed from the current workspace.

### Tier lock clarity

File: `src/main/java/com/optiportal/cache/CacheManager.java`

Interpretation guidance:

- addressed in current source: the lock is now named `tierSnapshotLock`
- comments describe it as a snapshot barrier, not a conventional read/write data lock
- tier mutation paths that affect `zoneTiers` + `tierTimestamps` now use the barrier so `saveRegistry()` snapshots both maps consistently
- do not overstate this as a deadlock finding without a concrete cycle

---

## 6. Findings That Are Fixed

These should generally be treated as historical / resolved unless new evidence appears.

### Fixed in current code

- false HOT promotion on heap or pressure abort (Issue U1 — now uses `ChunkLoadAbortedException` + `exceptionally()` path)
- storage I/O on world-thread completion paths (D4 — `thenRun` → `thenRunAsync`; coalesced via `queueEntrySave`/`flushPendingEntrySaves`)
- ArrayList allocation-every-200ms issue (A5)
- synchronized EMA contention in async load balancing (A2)
- `getQueuedTaskCount()` O(n) behavior (A1)
- wrong distance metric in `EnhancedChunkPreloader` (A3 — Chebyshev now used throughout)
- reverse-preload cooldown race in `reversePreloadOrigin(...)` (D1 — atomic `compute()` now used)
- second reverse-preload cooldown path in `TeleportInterceptor` now also uses atomic `compute()`
- public async/status getters in `OptiPortal` now return service fields instead of unconditional `null`
- command/UI optional-service paths now degrade cleanly for unavailable watchers or registries
- HOT tier operator displays now distinguish verified ownership pins from partial or assumed HOT state
- `Math.hypot(...)` cleanup in the live `TeleportInterceptor` path is no longer an active source-backed finding
- `RetryPolicy` no-executor fallback now uses `CompletableFuture.delayedExecutor(...)` instead of blocking or creating a temporary scheduler per retry
- engine-state guards around `world.isTicking()` / `world.isPaused()` are documented in source as advisory reads
- redundant dormant `PlayerDeathSystem` class removed; gravestone integrations remain the canonical death-location path
- `CacheManager` tier snapshot locking is now named/documented as a snapshot barrier and used by maintenance tier mutations
- stale `tick`-count TPS estimation (WorldTpsMonitor now reads `HistoricMetric.getLastValue()` directly)
- `SnapshotScheduler` blocking executor thread (A4 — async snapshot flush via `CompletableFuture.runAsync`)
- `lingerOriginZone(...)` silently non-functional (world name now passed explicitly; dead `getWorldNameFromPosition` helper removed)
- watcher diff causing broad `loadAll()` scans (replaced with targeted `loadEntriesWithIdPrefix` / `loadWarpSyncEntries`)
- post-load stat writes triggering per-chunk storage calls (now coalesced via `queueEntrySave` map-swap drain path)
- GC detection miss window: OptiPortal now checks the server's GC detection flag before each batch

### Fixed in the 2026-11-12 improvement pass (Stage 1 + Stage 2 + Tier 1/2 + Tier 3 knob)

Stage 1 — `CacheManager` tiering (verified by `CacheManagerTierTest`):

- cache registry file default was `null` (registry silently in-memory-only) — now `cache-registry.json`
- WARM-floor policy is centralized (`markWarmFloor` / `unmarkWarmFloor` via `PreloadCommand` strategy handlers) instead of scattered tier writes
- the decay fast-path was removed (single `decayTiersInternal` path) so decay can no longer race the tier maps
- zone expiry is generation-safe: `zoneGenerations` map + generation guards on `setZoneTier` / `registerZoneChunks` so a stale batch cannot evict a newer generation
- WARM→COLD demotion is batched with pacing (`lastDecayedBatch` + batch limit) instead of one-shot sweeps

Stage 2 — async core (verified by `AsyncCoreHardeningTest`):

- `queueOperation` completes the caller's future exceptionally when a queued supplier throws synchronously (previously the future hung forever)
- the drain path re-applies the critical-load admission gate — non-CRITICAL queued work stays queued while the world thread is critically loaded
- `executeBatched` / `processBatch` now use atomic `compute()` / `computeIfPresent()` on `operationQueues` (the `computeIfAbsent`+`add` vs two-arg `remove` TOCTOU race is closed)
- circuit-breaker failure accounting has a single owner per error path (`errorHandler.handleWorldThreadError()` in the lambda-catch path; the `world.execute()` rejected-task path remains the sole owner there) — the double `recordFailure()` that opened the breaker at half the real failure rate is gone
- `WorldTpsMonitor` clamp widened from 1.5× to 3× target TPS — telemetry now reports actual TPS instead of capacity
- `LoadStats` carries the authoritative TPS from the monitor (6th field `currentTps`; -1.0 = no monitor attached) and `PreloadCommand` displays it

Tier 1 — correctness (2026-11-12):

- `TeleportInterceptor` predictive path passed a hardcoded `null` player UUID to the cooldown map (cooldown was a no-op) — now resolves the real UUID from `UUIDComponent` (null-guarded for non-player holders)
- `predictiveLoadWithRam` returned `void` and discarded the future chain — now returns `CompletableFuture<Void>` so load failures are observable via `.exceptionally`
- `RespawnTracker` did synchronous JDBC (`storage.save` / `loadById`) on the player event thread — both bodies now run on the plugin executor (constructor takes the executor; wired in `OptiPortal.start`)
- TTL sentinel collapse: `/preload ttl <id> -1` stored `null` ("use global default") instead of the `-1` never-expire sentinel — `-1` now round-trips through storage; the enforcer already skipped `ttlDays == -1` (the UI now renders "-1 (never expire)")
- **H2 backend upsert SQL was rejected by H2 2.2 plain mode** — it used SQLite/PostgreSQL `ON CONFLICT(id) DO UPDATE SET ... excluded.*` syntax, and `save()` swallows `SQLException`, so every H2-mode save was a silent no-op. Now H2-native `MERGE INTO ... KEY(id) VALUES(...)`. Empirically proven by `H2UpsertSqlTest` (RED on the old SQL, GREEN after the fix, JDBC-level row assertion)

Tier 2 — save-lifecycle fence (2026-11-12):

- `queueEntrySave` guards `executor.schedule` against `RejectedExecutionException` (completion callbacks can fire after stop(); the exception previously escaped onto the world/event thread)
- `flushPendingEntrySaves` guards `storage.saveAll` against a closed backend (a late task can run between executor shutdown and `storage.close()` — stats-only data, warn-and-stop)

Tier 3 — config knob (2026-11-12):

- the shared async executor thread count was hardcoded at 4 — now `async.executorThreadCount` in `config.json` (default 4 = legacy; clamped to ≥1; parsed in `PluginConfig`, read at executor creation which moved after config load). Verified by `ExecutorThreadCountKnobTest`

Test suite at pass end: 55/55 green (52 pre-pass + `H2UpsertSqlTest` + 2 knob tests).

Implementation notes:

- `ChunkPreloader` now uses `ChunkLoadAbortedException` + `exceptionally()` on the abort path to prevent false HOT promotion
- `AsyncLoadBalancer` now uses atomic EMA updates and direct queued-count tracking
- `EnhancedChunkPreloader` already uses Chebyshev distance in sorting
- `WorldTpsMonitor` samples every 1s with EMA (α=0.25) from `HistoricMetric`, no delta arithmetic
- `TeleportInterceptor` now uses a `PortalSpatialIndex` for world-scoped candidate lookup
- `TeleportInterceptor` routine proximity checks are now staggered and decoupled from `pollTeleportRecords()`
- adaptive preload budgeting is now per-world and re-evaluated between batches (not once per load)
- JSON write-behind is now versioned and serialized behind an I/O lock to prevent out-of-order flushes

---

## 7. Findings That Were Overstated or Stale

These are especially important for future LLM passes.

### 7.1 "Reverse preload race is still unfixed everywhere"

Stale. The atomic cooldown gate in `reversePreloadOrigin(...)` is now implemented, and the other current `TeleportInterceptor` reverse-preload cooldown path also uses `ConcurrentHashMap.compute(...)`.

### 7.1a "Public async/status getters still return null"

Stale for the current workspace. `OptiPortal` now returns `asyncLoadBalancer`, `asyncErrorHandler`, `tpsMonitor`, and `portalChunkListener` fields from the public getters. Review command/UI null-handling only if the corresponding service can still legitimately be absent under a documented config or lifecycle state.

### 7.1b "`TeleportInterceptor` still uses Math.hypot in hot paths"

Stale for the current workspace. `TeleportInterceptor` has no current `Math.hypot(...)` matches; threshold-style proximity checks use squared-distance math in the primary path.

### 7.2 "The recent async fix pass is complete and regression-free"

Stale. The earlier regressions in `WorldThreadBridge.executeBatched(...)` and reverse-preload candidate radius handling were fixed in the current workspace. Remaining concern is test depth, not a known live regression.

### 7.3 "JSON serialization is still in the critical path causing MSTP spikes"

Overstated. Current code batches stat persistence through `queueEntrySave()` and `flushPendingEntrySaves()`, and the JSON backend uses a versioned write-behind path with a dedicated I/O lock. It is no longer accurate to frame this as world-thread blocking critical-path persistence.

### 7.4 "EnhancedChunkPreloader still uses the wrong distance metric"

Stale. Current code already uses Chebyshev distance consistently.

### 7.5 "Dormant components are a critical architecture defect"

Overstated. They are mostly a maintenance and API-surface concern. The async services (`AsyncLoadBalancer`, `AsyncErrorHandler`, `AsyncMetrics`, `WorldTpsMonitor`) are now live. Only the listed dormant subsystems (Auditor, EnhancedPreloader, AsyncTeleportInterceptor, AsyncKeepaliveManager) remain inactive; the redundant `PlayerDeathSystem` class was removed.

### 7.6 "Engine backoff retry conflict is a confirmed critical bug"

Too strong. Current retry code does include engine backoff checks. Remaining concern is worth reviewing, but should be framed cautiously.

### 7.7 "WorldTpsMonitor reads non-volatile tick fields causing permanent throttle risk"

Partially stale. The delta-tick sampling path is gone; TPS is now derived from `HistoricMetric.getLastValue()`. The residual risk from non-volatile `isTicking`/`isPaused` reads is lower than described in `OPTIPORTAL_COMPLETE_REFERENCE.md` §2.5 Issue E2.

### 7.8 "false HOT promotion on abort is unfixed"

Stale. `ChunkLoadAbortedException` is now thrown on the abort path; `thenRun` does not fire on exceptional completion; an `exceptionally()` handler logs at FINE.

### 7.9 "lingerOriginZone is silently non-functional"

Stale. The `getWorldNameFromPosition(...)` dead-code path is removed. `lingerOriginZone(...)` now accepts `worldName` directly from the surrounding event context.

---

## 8. Integrations (Current)

The integrations package (`src/main/java/com/optiportal/integrations/`) currently contains:

| Class | Status | Purpose |
|---|---|---|
| `WarpFileWatcher` | Active | Mirrors warp entries into OptiPortal's predictive registry from a warps.json file |
| `HyTeleportersXWatcher` | Active | Mirrors HyTeleportersX teleporter entries; reads `Teleporters.json`; syncs on a polling schedule |
| `GravestoneIntegration` | Active | Runtime-soft Gravestones event handling for death-location cache release |
| `BetterGravestoneWatcher` | Active | Runtime-soft BetterGravestone integration; polls for gravestone blocks by block/plugin ID; handles lifecycle cleanup |

Integration hot-reload behavior:

- Optional companion-plugin integrations are runtime-soft: keep their plugin IDs in `manifest.json` `OptionalDependencies`, but do not add their APIs to `pom.xml`.
- `GravestoneIntegration` and `BetterGravestoneWatcher` use reflective lookups so missing companion plugins stay a clean no-op instead of a startup crash.
- `WarpFileWatcher`, `HyTeleportersXWatcher`, and `BetterGravestoneWatcher` support live restart on `/preload reload` when their relevant config settings change.
- Comparison is done against pre-reload config values (captured before `config.reload()`) to avoid false-equal comparisons.
- `integrations.portalChunkListener.enabled` and `tpsMonitor.enabled` are **not** hot-reloadable — require server restart.

---

## 9. Recommended Review Language

When describing the current codebase, prefer language like:

- "confirmed current issue"
- "plausible engine-bound risk"
- "fixed in current workspace"
- "stale historical finding"
- "cleanup / maintenance concern"

Avoid language like:

- "critical" for dormant code alone
- "confirmed production bug" for engine-field visibility without repro evidence
- "still broken" for issues already fixed in current files

---

## 10. Decompiled Engine Map

Use these `decompiled/` locations when a review depends on Hytale engine behavior rather than plugin code alone.

### World lifecycle and thread dispatch

- `decompiled/com/hypixel/hytale/server/core/universe/world/World.java`

Relevant reasons:

- `World` class definition and core state
- `isTicking`, `isPaused` backing fields (plain boolean, non-volatile)
- `execute(Runnable)` shutdown/rejection behavior
- chunk async entry points

### Tick-length metrics

- `decompiled/com/hypixel/hytale/metrics/metric/HistoricMetric.java`
- `decompiled/com/hypixel/hytale/server/core/universe/world/World.java`

Relevant reasons:

- `WorldTpsMonitor` now derives TPS from `getBufferedTickLengthMetricSet().getLastValue()`
- validates that `World.getBufferedTickLengthMetricSet()` returns a `HistoricMetric`
- useful when checking whether TPS is derived from tick duration vs raw counters (it is now duration-based)

### SkipSentryException behavior

- `decompiled/com/hypixel/hytale/logger/sentry/SkipSentryException.java`

Relevant reasons:

- confirms exception type used by engine shutdown/rejection paths
- useful when validating `world.execute(...)` failure handling

### Chunk load backoff behavior

- `decompiled/com/hypixel/hytale/server/core/universe/world/chunk/ChunkStore.java`

Relevant reasons:

- backoff constants such as `FAILURE_BACKOFF_NANOS`
- `MAX_FAILURE_BACKOFF_NANOS`
- engine chunk failure / retry windows
- `isChunkOnBackoff(...)` semantics

### Teleport history component

- `decompiled/com/hypixel/hytale/server/core/modules/entity/teleport/TeleportRecord.java`
- `decompiled/com/hypixel/hytale/server/core/modules/entity/teleport/TeleportSystems.java`

Relevant reasons:

- confirms how last-teleport data is stored
- useful when validating `TeleportInterceptor` assumptions

### Teleporter destination component

- `decompiled/com/hypixel/hytale/builtin/adventure/teleporter/interaction/server/UsedTeleporter.java`
- `decompiled/com/hypixel/hytale/builtin/adventure/teleporter/TeleporterPlugin.java`
- `decompiled/com/hypixel/hytale/builtin/adventure/teleporter/system/ClearUsedTeleporterSystem.java`

Relevant reasons:

- confirms destination world UUID / position flow
- useful for post-teleport and AddPlayerToWorld analysis

### Helpful repository-side decompiled references

- `decompiled/HISTORY-JAVA.md`
- `decompiled/manifests.json`

Relevant reasons:

- useful for decompile provenance and snapshot context

Interpretation rule:

- only cite decompiled files when plugin behavior depends on engine semantics
- prefer plugin source first, engine source second

---

## 11. If You Are An LLM Reviewing This Repo

Use this sequence:

1. Check whether a claim is code-backed in the current workspace.
2. Separate correctness bugs from performance micro-optimizations.
3. Separate plugin-owned defects from engine-owned risks.
4. Check whether the issue was already fixed before repeating an older finding.
5. Prefer precise statements over dramatic severity labels.

Good first files to inspect:

- `src/main/java/com/optiportal/teleport/TeleportInterceptor.java`
- `src/main/java/com/optiportal/OptiPortal.java`
- `src/main/java/com/optiportal/preload/ChunkPreloader.java`
- `src/main/java/com/optiportal/async/AsyncErrorHandler.java`
- `src/main/java/com/optiportal/async/WorldTpsMonitor.java`
- `src/main/java/com/optiportal/async/WorldThreadBridge.java`
- `src/main/java/com/optiportal/cache/CacheManager.java`
- `src/main/java/com/optiportal/preload/EnhancedChunkPreloader.java`
- `src/main/java/com/optiportal/integrations/HyTeleportersXWatcher.java`
- `src/main/java/com/optiportal/integrations/BetterGravestoneWatcher.java`

Architecture-relevant new files since `OPTIPORTAL_COMPLETE_REFERENCE.md`:

- `src/main/java/com/optiportal/teleport/PortalSpatialIndex.java` — world-scoped chunk-bucket spatial index for proximity candidate lookup
- `src/main/java/com/optiportal/preload/ChunkLoadAbortedException.java` — used on abort paths to prevent false HOT promotion via `exceptionally()`

---

## 12. Quick Summary

Best current short summary:

Operator HOT displays now separate tier state from ownership-footprint confidence: command and UI surfaces show verified, partial, or assumed HOT status instead of implying direct engine residency proof.

OptiPortal is a solid async-oriented Hytale preload plugin with most previously flagged severe issues already fixed. The async status infrastructure (`AsyncLoadBalancer`, `AsyncErrorHandler`, `AsyncMetrics`, `WorldTpsMonitor`) is now live rather than null-backed. TPS sampling was upgraded to use engine `HistoricMetric` tick-duration data, removing the delta-tick jitter concerns. The false-HOT-on-abort and linger-zone bugs are fixed. A spatial portal index now narrows proximity candidate scans. Routine proximity scheduling is staggered and decoupled from teleport polling. JSON write-behind is versioned and I/O-locked. The main remaining code-backed concern is accurate configuration/runtime semantics for startup-wired services (`tpsMonitor`, `portalChunkListener` world hooks). Other commonly cited concerns — engine visibility semantics and retry/backoff interactions — should be described as plausible or moderate risks unless directly reproduced.
