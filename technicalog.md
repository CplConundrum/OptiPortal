# Technical Changelog

Developer-facing notes. Each entry documents the root cause, affected code paths, engine API
constraints, and threading considerations behind the corresponding changelog fix. Entries
mirror the version numbers in `changelog.md`.

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
