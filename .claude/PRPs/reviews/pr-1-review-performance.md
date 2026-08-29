# PR Review: #1 — Findings: Performance

**Reviewed**: 2026-08-25
**Branch**: `vitalforge-connectivity-and-login` → `main`
**Dimension**: performance only (1 of 7 parallel passes)
**Scope**: `git diff main...HEAD`, verified against the working tree at review time.

Counts: **1 CRITICAL / 4 HIGH / 6 MEDIUM / 7 LOW**.

Maintainability findings in `pr-1-review.md` are not repeated here. Two
findings below overlap files that section already names
(`DeliveryTrigger.kt`, `HistoryViewModel.kt`) but on a different axis —
runtime cost, not structure — and are cross-referenced where they touch.

---

## CRITICAL

### C1. `BridgeForegroundService` enqueues a full GATT session per advertisement packet, indefinitely

`service/BridgeForegroundService.kt:48-56`

`startActiveScan()` registers a `ScanCallback` with no `setReportDelay()` and
the default `CALLBACK_TYPE_ALL_MATCHES`, so `onScanResult` fires **once per
received advertisement** — a BF720 advertising at a typical 100–500 ms
interval produces roughly 2–10 callbacks per second while in range. Each one
runs `enqueuer.enqueue(...)` → `WorkManager.enqueueUniqueWork`, which is a
binder round-trip plus a `WorkDatabase` read/write. That alone is 2–10 SQLite
transactions per second, sustained, for as long as the scale is within radio
range.

The worse half is what gets enqueued. `ExistingWorkPolicy.KEEP`
(`ble/session/ScaleSessionEnqueuer.kt:36`) only suppresses a duplicate while a
worker is **actually in flight**. The moment `ScaleSessionWorker.doWork()`
returns, the very next advertisement enqueues a brand-new session: connect,
discover services, run the full ADR-007 handshake, subscribe, then sit through
`SessionBudget.FIRST_INDICATION_TIMEOUT` and
`BODY_COMPOSITION_CORRELATION_WINDOW` before tearing down. Nothing stops this
loop:

- `ScaleSessionWorker.kt:21`'s `STALENESS_ABORT_MILLIS` guard cannot help —
  each callback stamps a fresh `System.currentTimeMillis()`, so `seenAt` is
  never stale.
- No cooldown is applied after a successful capture. `doWork()` ingests the
  reading and returns `Result.success()` (`:38-42`); the scan is still running
  and the next packet starts session *n+1*.
- `ScaleOperationCoordinator` (`ble/session/ScaleOperationCoordinator.kt:23`)
  serializes sessions but does not suppress them — queued sessions run
  back-to-back rather than concurrently, which is worse for wall-clock
  occupancy, not better.
- `ReadingIngestor`'s dedup (`data/ReadingIngestor.kt:43-48`) stops duplicate
  *rows*, not duplicate *sessions*. The radio work is already spent by then.

**Manifests**: whenever always-on bridging is enabled and the paired scale is
in range. Standing on the scale, or simply leaving the phone near a scale that
periodically wakes, produces an unbroken chain of GATT connect/handshake/
timeout cycles. Each cycle holds a foreground-service notification, keeps the
radio in connected mode, and writes to the WorkManager DB. This is the single
largest battery cost in the branch, and it scales with proximity rather than
with the number of weigh-ins.

**Direction**: set `setReportDelay()` (batching) or
`CALLBACK_TYPE_FIRST_MATCH`, and gate enqueue behind a per-address cooldown —
e.g. suppress re-enqueue for `N` minutes after any terminal session outcome,
not merely while one is running.

---

## HIGH

### H1. Always-on bridge scans at `SCAN_MODE_LOW_LATENCY` with no duty cycle

`service/BridgeForegroundService.kt:48`

`ScanSettings.SCAN_MODE_LOW_LATENCY` is a ~100% duty-cycle scan. It is the
correct mode for a short, user-initiated, foreground discovery — not for a
service started from `BasculeApplication.onCreate` (`BasculeApplication.kt:89-99`)
that runs until explicitly stopped. `ScaleScanner`, the *background* path,
correctly uses `SCAN_MODE_LOW_POWER` (`ble/ScaleScanner.kt:31`); the always-on
path picked the opposite and stricter mode for the strictly longer-lived
scan.

**Manifests**: continuously, whenever always-on bridging is on. Independent of
whether a scale is ever in range — the radio duty cycle is paid regardless.
`SCAN_MODE_BALANCED` would cut this substantially at the cost of a few seconds
of detection latency, which the 20-second staleness budget already tolerates.

### H2. One drain of N rows triggers up to 3N full-table Room queries, and `HistoryViewModel` does its work on the main thread

`ui/HistoryViewModel.kt:51-65`, `ui/ScaleViewModel.kt:39-58`,
`delivery/DeliveryDrainer.kt:39-45`

The network N+1 the prior cycle fixed **is** genuinely fixed:
`DeliveryDrainer.drain()` fetches `recentReadings` once (`:37`) and reuses the
result across every row, and all N `submitReading` calls share one client
instance. A read-side N+1 remains, on the Room side.

`drain()` calls `dao.update()` once per row (`:51`, `:61`, `:75`, `:90`,
`:100`) with a network round-trip in between, so Room's `InvalidationTracker`
has no opportunity to coalesce — N delivered rows produce N distinct
invalidations of the `readings` table. Three observers are subscribed to that
table with `SharingStarted.Eagerly`, i.e. for their ViewModel's entire
lifetime regardless of whether the screen is visible:

- `HistoryViewModel.uiState` ← `dao.observeAll()` (unbounded `SELECT *`)
- `ScaleViewModel.uiState` ← `dao.observePendingCount()`
- `ScaleViewModel.uiState` ← `dao.observeLastScaleCapture()`

So a single drain re-runs up to **3N queries**, one of which returns the entire
readings table each time. Each of those N `observeAll()` emissions then does,
in `HistoryViewModel`'s transform: a full `sortedWith` of the table, plus
`any` (`:57`), `any` (`:58`), and `filter` + `minOfOrNull` (`:59-61`) — four
more full passes and a fresh list allocation per emission.

The part that makes this a main-thread finding rather than merely wasted CPU:
**`HistoryViewModel.uiState` has no `flowOn`.** `stateIn(viewModelScope, ...)`
collects on `Dispatchers.Main.immediate`, so the sort and all four scans run on
the UI thread. `ConfigViewModel.kt:194` — same PR, same `combine`/`stateIn`
shape — *does* apply `.flowOn(ioDispatcher)`. The asymmetry looks unintended.
`ScaleViewModel.kt:58` has the same omission.

`InMemoryDiagnosticsCounters.publishSnapshot()`
(`diagnostics/InMemoryDiagnosticsCounters.kt:36-38`) compounds it: it rebuilds
the whole counter map on every `increment`/`reset`, and that flow is combined
into `HistoryViewModel.uiState` (`:53`). Every counter bump therefore also
triggers a full re-sort of the readings table on the main thread.

**Manifests**: every drain, scaling with the pending backlog; and on every
diagnostics counter change during a session. Sub-millisecond per pass at
today's row counts, but the multiplier is 3N and one lane is the UI thread.

Scoped precisely: the 3N amplification requires the app process to be alive
with both ViewModels already constructed — a background `DeliveryWorker` run
against a killed process pays none of it. That is not a retraction, because
H4's recovery path is triggered *from the Settings screen* (`saveToken()` /
`login()` → `unblockAuthRows` → immediate drain), so the largest-N case is
exactly the case where both ViewModels are guaranteed live and the main thread
is guaranteed to be the one rendering.

Note also what is *not* claimed: the Room queries themselves run on Room's own
query executor, not the main thread. The main-thread cost is the `combine`
transform — the sort and the four scans — which `stateIn` collects on
`Dispatchers.Main.immediate`.

**Direction**: `flowOn(Dispatchers.Default)` on both ViewModels;
`SharingStarted.WhileSubscribed(5_000)` instead of `Eagerly` (which is also
what this project's own `~/.claude/rules/kotlin/patterns.md` prescribes); and
compute `hasBlockedAuth`/`hasFailedPermanent`/`oldestPendingAge` in a single
pass, or as dedicated `COUNT`/`MIN` queries.

### H3. Blocking `SharedPreferences.commit()` on an encrypted store, reached from the main thread

`data/ScaleProfileStore.kt:114`

`EncryptedScaleProfileStore.persist()` uses `.commit()`, not `.apply()` — a
synchronous fsync, on top of a Tink AES-GCM encrypt of the full serialized
profile list. Three call paths reach it without leaving the main thread:

- **`ui/ScaleViewModel.kt:84-88` — `rename()` is not a coroutine at all.**
  A Compose click handler calls it directly → `profiles.saveProfile()` →
  `persist()` → `.commit()`. Straight-line blocking disk I/O in the input
  handler.
- `ui/ScaleViewModel.kt:79-82` — `setActive()` is inside
  `viewModelScope.launch { }` with no `withContext`, so it runs on
  `Dispatchers.Main.immediate`. Same for `profiles.setActive()` → `persist()`.
- `ui/ConfigViewModel.kt:394-399` — `linkExistingScale()`'s
  `consentStore.save(...)`. `BasculeApplication.kt:56` aliases `consentStore`
  to `scaleProfileStore`, so this is `EncryptedScaleProfileStore.save()` →
  `saveProfile()` → `persist()`, again on `Main.immediate`.

Checked and **not** affected: `EncryptedAuthTokenStore`
(`network/AuthTokenStore.kt:38,42`) and `EncryptedSessionCookieStore`
(`network/SessionCookieStore.kt:32,36`) both use `.apply()`, so
`ConfigViewModel.saveToken()`/`clearCredentials()` are async writes despite
being called outside a coroutine.

**Manifests**: on every profile rename, every profile-selection tap, and every
"Use existing" mapping save. A single fsync is typically a few ms but can
reach tens to hundreds of ms on a loaded or low-end device — enough for a
visible jank frame, in the exact interaction that caused it.

**Direction**: `.apply()` unless the write must be durable before the next
statement; if durability is required, move `persist()` behind
`withContext(Dispatchers.IO)` and make `saveProfile`/`setActive`/`save`
suspending.

### H4. The drain is unbounded, unbatched, and strictly sequential inside a 10-minute worker

`data/ReadingDao.kt:26-27`, `delivery/DeliveryDrainer.kt:34-47`

`dao.pending()` selects every `PENDING` row with no `LIMIT`, and `drain()`
walks them one at a time, awaiting a full HTTP round-trip per row before
starting the next. There is no batch cap and no concurrency.

The bad case is reachable by design rather than by accident. `§8.6`'s
recovery flow — `dao.unblockAuthRows()` in
`ui/ConfigViewModel.kt:315`, called from `saveToken()` and a successful
`login()` — flips **every** `BLOCKED_AUTH` row back to `PENDING` in one
statement and then triggers an immediate drain. A user who was signed out for
a few weeks of daily weigh-ins re-authenticates into a single `DeliveryWorker`
run that must complete N sequential round-trips. `CoroutineWorker` is subject
to WorkManager's 10-minute execution limit; on a slow connection with a
15 s connect + 10 s read timeout per row (`network/VitalForgeHttpClient.kt:242-244`),
that ceiling is reachable at a few dozen rows. When it is hit the worker is
stopped mid-drain — the rows already updated persist, but the run is retried
from the top, re-issuing `recentReadings` and re-walking the remainder.

Every one of those N `dao.update()` calls also pays H2's 3N read
amplification, so the two findings multiply.

**Manifests**: after any extended auth outage, and after any long offline
period. Invisible at N ≈ 1–5, which is the normal case; degrades sharply and
non-linearly past a few dozen.

**Direction**: add `LIMIT` to `pending()` and let the worker re-enqueue while
rows remain, so each run is bounded and the 10-minute ceiling is never the
thing that decides where a drain stops.

---

## MEDIUM

### M1. A fresh `OkHttpClient` is built per API instance

`network/VitalForgeHttpClient.kt:40`, `network/RuntimeApiFactory.kt:24-30`,
`ui/ConfigViewModel.kt:121-129`

`client: OkHttpClient = defaultClient()` as a default parameter means every
`VitalForgeHttpClient` that isn't handed an explicit client builds its own —
new `ConnectionPool`, new `Dispatcher`, new route database. In production
nothing passes one:

- `RuntimeApiFactory.create()` builds one **per `DeliveryWorker` run**.
- `ConfigViewModel.apiFactory` builds one **per `testConnection()` tap and per
  `login()` tap** (`:280`, `:329`).

Within a single drain this costs nothing — all N `submitReading` calls share
the one instance, so keep-alive works. It costs across calls: back-to-back
`triggerImmediateDrain()` runs (which H4's recovery path and every capture
produce) each open a fresh TLS session instead of reusing the previous one,
and a user tapping "Test connection" then "Log in" then "Test connection"
pays three full handshakes. Abandoned pools also hold their idle connections
until the keepalive timer evicts them.

Deliberately *not* claimed: thread-pool churn. OkHttp creates dispatcher
threads lazily and shares a process-wide `TaskRunner`, so the cost here is
connection/TLS reuse, not threads. For the 15-minute periodic drain the
default 5-minute keepalive would have evicted the connection anyway — which is
why this is MEDIUM and not HIGH.

**Direction**: one `OkHttpClient` on `BasculeApplication`, passed in. The
`newBuilder().build()` at `:45-50` is already the right shape for deriving
per-call config from a shared parent — it just never gets a shared parent.

### M2. `readings` has no indices on any queried column

`data/ReadingEntity.kt:36`, verified in
`app/schemas/…BasculeDatabase/1.json` and `2.json` (both report
`indices: None`)

Every query in `ReadingDao` filters or sorts on an unindexed column:
`status` (`:26`, `:43`, `:63`, `:66`), `source` + `capturedAtMillis`
(`:33-41`), `capturedAtMillis` (`:18`), and `source` again (`:69`). All are
full table scans.

Rated MEDIUM rather than HIGH deliberately: a weigh-in table grows one or two
rows a day, so a full scan of a few thousand rows is sub-millisecond, and no
single query here is expensive. The argument is frequency, not per-query cost
— these scans are paid 3N times per drain (H2), once per capture for
`dedupCandidates`, and once per any write for the three `Eagerly` observers,
and one of those lanes is the main thread (H2). An index on `status` and a
composite on `(source, capturedAtMillis)` are close to free and remove the
scan from all of those paths at once.

### M3. `ScaleScreen` instantiates a second, independent `ConfigViewModel`

`ui/ScaleScreen.kt:34-37`

`viewModel(factory = ConfigViewModel.factory(app))` is scoped to the Scale
destination's `NavBackStackEntry`, which is a different store from
`ConfigScreen`'s. With bottom-nav `saveState`/`restoreState`
(`ui/BasculeApp.kt:47-49`) retaining both entries, the app holds **two live
`ConfigViewModel` instances** once both tabs have been visited.

Each carries a nine-flow `combine` with `SharingStarted.Eagerly`
(`ui/ConfigViewModel.kt:170-194`), so each independently collects five
DataStore flows for the process lifetime, and each re-runs
`authTokenStore.isSet()`, `sessionCookieStore.isSet()`, and
`consentStore.credentialFor()` on every emission of any of the nine. Those are
three Tink AES decrypts per emission, doubled. They are correctly held off the
main thread by `.flowOn(ioDispatcher)` (`:194`) — this is duplicated work and
duplicated DataStore subscriptions, not a jank source.

**Manifests**: after the user has opened both the Scale and Settings tabs.
Every config write thereafter does twice the decrypt work it needs to.

### M4. `ConfigUiState` is recomputed, with three decrypts, on every emission of nine upstream flows

`ui/ConfigViewModel.kt:170-194`

Separately from M3's doubling: the transform reads three encrypted stores on
every emission, but those stores change only via `_credentialVersion` /
`_consentVersion` (`:135`, `:138`). A `baseUrl` keystroke-save, a unit change,
a contract change, a bridging toggle, a connection-test state change, a login
spinner flip, or a scale-registration phase update each re-decrypt all three
for values that provably did not change. `Eagerly` means this runs whether or
not either config surface is on screen.

### M5. The 15-minute periodic drain is scheduled unconditionally

`BasculeApplication.kt:84`, `delivery/DeliveryScheduler.kt:29-35`

`ensurePeriodicDrain()` is called from `onCreate` with no gate on whether a
credential exists, a base URL is configured, or any reading has ever been
captured. `PeriodicWorkRequestBuilder<DeliveryWorker>(15, TimeUnit.MINUTES)`
is the minimum interval WorkManager allows, so a user who installs the app and
never completes setup still gets ~96 wakeups a day, each doing a
`dao.pending()` full scan (M2) and building an `OkHttpClient` (M1) before
finding nothing to send.

The `NetworkType.CONNECTED` constraint is correctly attached here, so these
wakeups are at least batched with other network work. The `KEEP` policy also
means the interval can never be revised for an existing install without a new
unique work name.

**Direction**: schedule the periodic drain on first successful credential
save, cancel it when `pending()` is empty and no credential is set, or raise
the interval — 15 minutes is aggressive for a signal that arrives once or
twice a day.

### M6. `DateFormat.getDateTimeInstance()` allocated per row, per recomposition

`ui/ScaleScreen.kt:105`, called at `:49` and `:72`

`formatTime` builds a fresh `SimpleDateFormat` on every call — locale lookup,
pattern resolution, and a new `Calendar`. It is called once for
`lastCaptureMillis` and once per profile row, inside a `forEach` in a
recomposing `Column`, with no `remember`. That is `1 + profileCount`
allocations of a comparatively heavy object on each recomposition of the Scale
screen.

**Direction**: hoist to a `remember { DateFormat.getDateTimeInstance() }` or a
file-level `val` (guarding for `SimpleDateFormat`'s thread-unsafety, which is
fine if only touched from the composition thread).

---

## LOW

### L1. Per-frame allocation in the BLE path — answering the brief directly

The brief asked about per-frame allocation in `ble/decoders/`. Verified
against the diff: **this branch introduces no new per-frame decoder work.**
`decoders/` changed only by threading `permitsRegistration` through
`HandshakeContext` (`ScaleDecoder.kt:105`) and two early-return guards in
`BeurerDecoder.kt:93,149` — all handshake-time, none on the notification path.
`MeasurementCorrelator`, `FrameReader`, `WeightMeasurement` and
`BodyCompositionMeasurement` are untouched.

The new per-frame code this branch adds is in `AndroidGattTransport` instead,
and it triple-buffers each frame:

- `ble/session/AndroidGattTransport.kt:197,205` — `value.copyOf()` on every
  `onCharacteristicChanged`. Necessary (the platform reuses the buffer on the
  deprecated path), but it is a fresh `ByteArray` per frame.
- `:36` — `MutableSharedFlow(replay = 128)` retains the last 128
  `TransportEvent`s, byte arrays included, for the session's lifetime.
- `ble/session/GattSession.kt:81-82` — every event is *additionally* forwarded
  into a `Channel(Channel.UNLIMITED)`.

So each frame is copied once and then held by two independent buffers. In
absolute terms this is negligible — a measurement session is a handful of
frames over a few seconds, and 128 × ~20-byte events is a few KiB — which is
why it is LOW. Worth knowing that the `replay = 128` figure is doing nothing
useful now that the `Channel` is the actual consumer.

### L2. Linear characteristic lookup per GATT operation

`ble/session/AndroidGattTransport.kt:226-227`

`findCharacteristic` flattens every service's characteristics and scans for a
UUID match, on every `write()` and every `subscribe()`. A handful of calls per
session against a small service table — negligible, but a `Map` built once at
`onServicesDiscovered` would be free, since `:188-190` already walks the same
structure.

### L3. `NotificationChannel` recreated on every session worker run

`ble/session/ScaleSessionWorker.kt:49-51`

`foregroundInfo()` calls `createNotificationChannel` unconditionally. It is
idempotent, but it is a binder round-trip on a path C1 can drive many times
per minute. Belongs in `BasculeApplication.onCreate` alongside the bridge
service's own channel.

### L4. `System.currentTimeMillis()` read inside a composable

`ui/HistoryScreen.kt:160`

`ReadingRow` computes its relative age from a direct clock read. Not a Compose
state read, so it triggers no recomposition — but it means the age is
recomputed to a different value on every unrelated recomposition, and the
displayed value never refreshes on its own. `HistoryViewModel` already reads
the clock through an injected `nowMillis` (`ui/HistoryViewModel.kt:41`);
`oldestPendingAgeMillis` uses it. Per-row age does not.

### L5. `ReadingRow` and `DiagnosticsSection` can never be skipped

`ui/HistoryScreen.kt:77-83`, `:87`, `:208-209`

`ReadingEntity` carries a `Set<ReadingField>` (`data/ReadingEntity.kt:73`) and
`HistoryUiState` carries a `List` and a `Map` — all unstable to the Compose
compiler, so neither composable is skippable. Combined with H2's N emissions
per drain and Room returning fresh entity instances each query, every visible
row recomposes on every emission. `DiagnosticsSection` additionally allocates a
`filterValues` map and a `sortedBy` list per recomposition (`:209`, `:214`).

Bounded by what's on screen, so LOW — but marking `ReadingEntity`/
`HistoryUiState` `@Immutable`, or hoisting the diagnostics filter/sort into the
ViewModel, removes it.

### L6. `WorkManagerDeliveryTrigger` would enqueue unconstrained work

`delivery/DeliveryTrigger.kt:25-28`

`OneTimeWorkRequestBuilder<DeliveryWorker>().build()` attaches no constraints,
unlike `WorkManagerDeliveryScheduler`'s `NetworkType.CONNECTED`
(`delivery/DeliveryScheduler.kt:19,25`) — so it would wake the device to run a
drain with no network available. Rated LOW **only** because the class is
currently dead (zero references; `BasculeApplication.kt:59` aliases
`deliveryTrigger` to the scheduler). `pr-1-review.md` H2 already flags the
dead-code and divergence angle; this is the runtime consequence if it is ever
wired instead of deleted.

### L7. A failed registration scan is never stopped

`ble/ScaleRegistrar.kt:129-131`

`findScale()`'s teardown is otherwise correct — the scan is stopped on first
result (`:125`) and on cancellation/timeout via `invokeOnCancellation`
(`:133`). `onScanFailed` is the one gap: it calls `continuation.resume(null)`
without `scanner.stopScan(callback)`. Resuming normally completes the
continuation, so `invokeOnCancellation` never fires and the callback stays
registered.

For most error codes this is harmless (the scan never started). It bites on
`SCAN_FAILED_ALREADY_STARTED`, where a scan *is* running: registration returns
"No BF720 found" while a `SCAN_MODE_LOW_LATENCY` scan is left running
indefinitely with nothing consuming it. Rated LOW because it needs a
pre-existing scan under the same callback identity to trigger, but the fix is
one line and the failure mode is H1's cost with none of H1's benefit.

---

## Not findings — checked and clear

- **The `DeliveryWorker` N+1 fix is real.** `DeliveryDrainer.kt:37` fetches
  `recentReadings` once per drain and `isRemoteDuplicate` (`:67-71`) reuses
  it. No remaining per-row network call other than the `submitReading` each
  row inherently requires.
- **`ConfigScreen.kt`** (+785 lines, the largest UI addition) is clean on this
  axis: `LazyColumn` with `item {}` blocks rather than a scrolling `Column`,
  all mutable UI state properly `remember`ed, and both file I/O paths
  (`:657`, `:669`) correctly wrapped in `withContext(Dispatchers.IO)` with a
  size cap in `readSettingsBackup` (`:730-742`).
- **`ConfigViewModel.exportSettings`/`importSettings`** (`:403-466`) wrap all
  DataStore and crypto work in `withContext(ioDispatcher)`.
- **`VitalForgeHttpClient.execute`** (`:191-205`) is on `Dispatchers.IO`, and
  `bodyExceedsCap` (`:211-215`) peeks rather than buffering — an endless
  response cannot exhaust memory.
- **`EncryptedAuthTokenStore` / `EncryptedSessionCookieStore`** use `.apply()`,
  so the credential writes called outside coroutines in
  `ConfigViewModel.saveToken()`/`clearCredentials()` do not block.
- **`ScaleScanner`** (`ble/ScaleScanner.kt:31-32`) correctly uses
  `SCAN_MODE_LOW_POWER` with a `PendingIntent` scan, which is the
  battery-appropriate shape for the background wake path.
- **`ScaleSessionWorker`** rejects stale broadcasts (`:21`) and returns
  `Result.retry()` only for adapter-off (`:43`), so WorkManager's exponential
  backoff applies rather than a tight retry loop.
- **`AndroidScaleRegistrar.findScale()`** (`ble/ScaleRegistrar.kt:119-143`) is
  the correct shape for a foreground scan and is the deliberate contrast to
  H1: bounded by a 20-second `withTimeoutOrNull`, stopped on first result, and
  stopped again via `invokeOnCancellation` on timeout or coroutine
  cancellation. `SCAN_MODE_LOW_LATENCY` is appropriate *here* precisely
  because the scan is short, user-initiated, and guaranteed to terminate —
  which is what `BridgeForegroundService` is not. Only the `onScanFailed` exit
  leaks (L7).
- **`ScaleOperationCoordinator`** (`ble/session/ScaleOperationCoordinator.kt:23`)
  correctly serializes registration and measurement sessions, so the two BLE
  paths never contend for the radio concurrently.

## Not reviewed

- `ui/ManualEntryScreen.kt` (+91) — a static form with no list rendering or
  background work; not opened. Flagging as unreviewed rather than implying
  coverage.
