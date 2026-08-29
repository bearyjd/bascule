# PR Review #1 — Dimension: Architectural / Pattern Compliance

**Reviewed**: 2026-08-25 · **Branch**: `vitalforge-connectivity-and-login` → `main`
**Status**: COMPLETE — **13 HIGH / 9 MEDIUM / 3 LOW**, no CRITICAL.

No CRITICAL is correct for this dimension: nothing below is a security or
data-loss defect *by itself*. One exception worth routing onward — **P12
describes a real reading-loss path** (a buffered weight frame discarded on every
session exit except one) that the correctness pass should be told about
independently.

**Scope**: architectural and pattern compliance only. Excludes correctness, type
safety, security, performance, completeness, and the maintainability findings
already in `pr-1-review.md` (H1–H8, M1–M12, L1–L10). Where a finding sits
adjacent to one of those, the difference is stated inline.

---

## Dominant theme

The design's **named components were hollowed out and their responsibilities
relocated without amending the design.**

`DeliveryCoordinator` — the component ADR-005 and ADR-006 both name as the
single owner of delivery status transitions — is still a constants object
labelled `PHASE 2 SKELETON`, while the real logic landed in a new
`DeliveryDrainer` that reaches *back into* the skeleton for its one live
constant. `ReadingMapper`'s KDoc still claims it hosts "the dedup gate and the
user-attribution gate"; both now live in a `ReadingIngestor` that appears in no
design document. The E9 duplicate-emission latch moved into the decoder exactly
as specified — and its old session-side site was left behind.

The recurring cost is the same each time: **invariants the ADRs promise are
centrally enforced now have two or three independent implementations**, in
different languages, and the one that ships in production is repeatedly the one
no test executes.

---

## HIGH

### P1. The dedup predicate has three implementations, and the one that ships is the only one never executed by a test

Three encodings of the same rule:

| # | Location | Form |
|---|---|---|
| 1 | `delivery/DedupPolicy.kt:37-44` | Kotlin — `status != DECLINED`, `candidate.source == existing.source`, ±tolerance, ±window |
| 2 | `data/ReadingDao.kt:35-43` | SQL — `WHERE status != 'DECLINED' AND source = :source AND capturedAtMillis BETWEEN :from AND :to` |
| 3 | `test/.../ui/fake/FakeReadingDao.kt:31-35` | Kotlin — the SQL predicate hand-reimplemented for tests |

`ReadingIngestor.kt:43-48` runs #2 to build the corpus, then hands the
already-filtered result to #1, so the status and source rules are applied twice
in production and a third time, independently, in the fake.

**Why this specifically matters.** ADR-006 ("Qualified in Phase 2 (O-06)")
singles out the §3.3 dedup corpus as the design's **one denylist predicate**,
explains that a denylist is fail-*open* — a seventh status is included by
default and would silently suppress a genuine reading — and states the hazard is
contained because `DedupPolicyTest.everyStatusHasAnExplicitCorpusMembershipDecision`
and `...dedupCorpusMembershipIsExplicitPerStatus` force an explicit decision per
status. Both tests exist
(`test/.../delivery/DedupPolicyTest.kt:33, 42`) and both do what the ADR says —
**but they exercise `DedupPolicy.isDuplicate` only.** Neither touches the DAO.

And the SQL cannot be reached by any test at all: there is **no `androidTest`
source set** in this project, no test file references `BasculeDatabase`, and the
only other reference to `dedupCandidates` is the fake's override. The `@Query`
string is never executed anywhere.

So ADR-006's stated safety mechanism covers implementation #1, tests bind to #3,
and #2 is what runs on the device. Adding a seventh `ReadingStatus` tomorrow
fails `DedupPolicyTest` as designed, and silently inherits "included in corpus"
in a SQL string literal — the exact fail-open the ADR spends a page arguing is
contained.

### P2. Three independent writers of the delivery state machine

- `delivery/DeliveryDrainer.kt:49-112` — Kotlin `row.copy(status = …)` for expiry,
  remote-duplicate, accepted, permanent, and transient outcomes.
- `data/ReadingDao.kt:53-64` — SQL `unblockAuthRows` and `blockAllPendingForAuth`.
- `ui/HistoryViewModel.kt:83-97` — Kotlin `reading.copy(status = …)` for
  confirm / decline / retry.

ADR-005 places these transitions in `DeliveryCoordinator` ("the class→status
mapping [is] one `when` block in `DeliveryCoordinator`"). That file contains
none of them.

The duplication is already *documented* rather than removed: `ReadingDao.kt:46-52`
says the SQL reset applies "the same reset `HistoryViewModel` applies to a manual
retry" — the two are known to encode one rule and are still separate. Any future
change to what "return a row to PENDING" means (a new column, a diagnostics
counter, clearing `remoteDuplicate`) must be made in three places in two
languages, and nothing fails if one is missed. Per P1, the SQL two are also
unreachable by any test.

*Distinct from H2*, which is one duplicated WorkManager enqueue. This is the
delivery status machine itself.

### P3. `DeliveryCoordinator`'s documented backoff ladder is defined and provably unreferenced

`delivery/DeliveryCoordinator.kt:14-16` declares `BACKOFF_BASE_MILLIS = 30_000L`
and `BACKOFF_CAP_MILLIS = 900_000L` under a KDoc citing `00-design.md` §3.4's
"30 s doubling to a 15 min cap". **Verified tree-wide: zero references to
either, in main or test.** `PLANNED_IN` is likewise unreferenced. The object's
only live member is `EXPIRY_MILLIS`, with exactly two references
(`DeliveryDrainer.kt:50`, `DeliveryDrainerTest.kt:113`).

`DeliveryDrainer.drain()` returns a single `Boolean` and `DeliveryWorker.kt:23`
converts it to `Result.retry()`, delegating retry timing entirely to
WorkManager's default backoff. Two architectural consequences:

1. Backoff is now **global per drain**, not per row. ADR-005's central
   arithmetic — "roughly 1 300 attempts" inside the 14-day window, the number
   that justifies the whole time-based-expiry decision — is derived from a
   per-row ladder and does not follow from a WorkManager-level retry.
2. A file headed `PHASE 2 SKELETON. Implemented in Phase 3 WP-21.` is now load-
   bearing for one constant and decorative for the other two, with nothing
   marking which is which.

### P4. `PermissionRequester` is the tested permission abstraction and every BLE entry point bypasses it

`ui/PermissionRequester.kt` is a deliberate, SDK-branched, plain-JUnit-testable
permission decision layer with 128 lines of dedicated test. Its only consumer is
`ConfigScreen`'s launcher plumbing. The four places that actually touch the radio
each hand-roll a different strategy:

| Site | Strategy |
|---|---|
| `ble/ScaleScanner.kt:32,36` | No check at all; `runCatching { … }.getOrDefault(false)` swallows the `SecurityException` |
| `ble/ScaleRegistrar.kt:59-63, 97-101` | `try/catch (_: SecurityException)` at two separate sites, mapped to user-facing strings |
| `service/BridgeForegroundService.kt:42-44` | `checkSelfPermission(BLUETOOTH_SCAN)`, silent early `return` |
| `ble/session/ScaleSessionWorker.kt:22-25` | `checkSelfPermission(BLUETOOTH_CONNECT)`, `Result.failure()` |

Four answers to one question — and the two that do check disagree about *which*
permission gates their flow. Neither consults the ≤API-30 location branch that
`PermissionRequester` exists to encode, so on API 29/30 `ScaleSessionWorker`'s
gate is skipped entirely (`SDK_INT >= 31`) with nothing substituted.

Cost: the permission matrix has one tested implementation and four untested
shadows; adding or changing a permission means finding all five.

*Not L1* — L1 is the literal `31` vs `VERSION_CODES.S`. This is the abstraction
being bypassed.

### P5. Three BLE scan implementations with divergent settings and filters

Same files as P4, different axis:

- `ble/ScaleScanner.kt:26-33` — `PendingIntent` scan, `SCAN_MODE_LOW_POWER`,
  filter = device address **+** service UUID.
- `service/BridgeForegroundService.kt:41-50` — `ScanCallback` scan,
  `SCAN_MODE_LOW_LATENCY`, filter = device address **+** service UUID.
- `ble/ScaleRegistrar.kt:118-143` — `ScanCallback` scan, `SCAN_MODE_LOW_LATENCY`,
  filter = service UUID **only**, wrapped in `suspendCancellableCoroutine` +
  `withTimeoutOrNull`.

Each builds its own `ScanFilter`/`ScanSettings` inline. `ScaleSessionEnqueuer`
was extracted precisely so "both that receiver and `BridgeForegroundService` —
the two real dispatch paths — share one implementation" (its own KDoc); the scan
half of the same pair was not. Changing the advertised service UUID, adding a
manufacturer-data filter, or retuning scan mode for battery now needs three
edits with no compiler or test link between them.

### P6. `ScaleScreen` instantiates a second, independent `ConfigViewModel`

`ui/ScaleScreen.kt:34-35`:

```kotlin
val vm: ScaleViewModel = viewModel(factory = ScaleViewModel.factory(app))
val configVm: ConfigViewModel = viewModel(factory = ConfigViewModel.factory(app))
```

Under `NavHost`, `viewModel()` resolves against the current `NavBackStackEntry`,
so this is a **different instance** from the one `ConfigScreen.kt:71-73` creates.
Every piece of transient state in that ViewModel is per-instance:
`_scaleRegistration`, `_connectionTest`, `_credentialVersion`, `_consentVersion`,
`connectionTestGeneration`.

Verifiable in one step: start a registration from the Scale tab, switch to
Settings — Settings shows `ScaleRegistrationUiState.Idle`, because its instance
never saw it. Conversely, `_consentVersion++` after a successful registration on
one tab does not invalidate the other tab's cached `registeredUserIndex`.
Registration is the app's primary setup flow and it is surfaced on both screens,
so this is on the first-run path.

Both instances use `SharingStarted.Eagerly`, so both hold live DataStore
collectors and both re-run encrypted-prefs reads on every emission.

*Distinct from M1*, which is about `RegisteredScaleSection` living in the wrong
file. This is about the ViewModel instance it is wired to.

### P7. "Always-on bridging" has two writers with different side effects and a third reader that acts on it

One `ConfigStore.alwaysOnBridging` key, three encodings of "the flag is on ⇒ the
service should be running":

- `ui/ConfigViewModel.kt:222-224` — persists the flag and **nothing else**.
- `ui/ScaleViewModel.kt:74-77` — persists the flag **and** starts/stops
  `BridgeForegroundService` via `onBridgeChange`.
- `BasculeApplication.kt:89-99` — reads the flag on `onCreate` and starts the
  service if set.

Three places encode the invariant; none enforces it. M4 flags
`ConfigViewModel.saveAlwaysOnBridging` as currently dead — the pattern problem is
what happens when it stops being dead: wiring a Settings switch to it silently
desyncs flag from service, with no compile-time or runtime signal, until the next
process start happens to reconcile them.

### P8. Four stores in one layer, three reactivity idioms, plus a hand-rolled invalidation mechanism

| Store | Read shape | Write shape |
|---|---|---|
| `ConfigStore` | cold `Flow` over DataStore | `suspend fun` |
| `ScaleProfileStore` | hot in-memory `StateFlow` | non-suspend, blocking `prefs.edit().commit()` |
| `AuthTokenStore` | **no Flow** — synchronous `isSet()`/`token()` | non-suspend |
| `SessionCookieStore` | **no Flow** — synchronous `isSet()`/`cookie()` | non-suspend |

`ConfigViewModel` compensates for rows 3 and 4 with two manual invalidation
counters — `_credentialVersion` (`:135`) and `_consentVersion` (`:138`) —
combined into `uiState` (`:170-175`) purely so a non-observable store's change
can retrigger the pipeline. Six mutators bump them by hand: `saveToken`,
`clearCredentials`, `login`, `linkExistingScale`, `startScaleRegistration`,
`importSettings`. A missed bump is a stale screen with no other symptom.

The counters are the symptom; the idiom split is the cause. A store exposing a
`Flow` deletes both counters and all six hand-bumps.

### P9. Reads are dispatched to IO; writes to the same stores are not

`ui/ConfigViewModel.kt:194` applies `.flowOn(ioDispatcher)` to `uiState`
specifically so `authTokenStore.isSet()`, `sessionCookieStore.isSet()` and
`consentStore.credentialFor()` stay off Main — the comment at `:176-178` says so
in as many words. The *writes* to those same `EncryptedSharedPreferences`-backed
stores are not dispatched:

- `saveToken` (`:241-243`) — `authTokenStore.save()` / `sessionCookieStore.clear()`
  directly on the caller's thread (Main).
- `clearCredentials` (`:253-254`) — same.
- `linkExistingScale` (`:394-396`) — `consentStore.save()` inside
  `viewModelScope.launch`, i.e. `Dispatchers.Main.immediate`.
- `ui/ScaleViewModel.kt:84-88` — `rename` calls `profiles.saveProfile()` with
  **no `launch` at all**, so `EncryptedScaleProfileStore.persist` runs
  `prefs.edit()…commit()` (the *synchronous* variant) on the Compose click
  handler's thread.

`exportSettings`/`importSettings` in the same file *do* use
`withContext(ioDispatcher)`. The asymmetry within one file — with the correct
dispatcher already injected as a constructor parameter — is what makes this a
pattern violation rather than a one-off: no reader can infer a rule about when
the seam applies.

*The latency cost belongs to the performance pass; the finding here is the
absence of a consistent rule.*

### P10. `ScaleViewModel` owns Android service lifecycle through `Context`-capturing lambdas

`ui/ScaleViewModel.kt:34-36` takes `onArm: suspend () -> Boolean`,
`onDisarm: () -> Unit`, `onBridgeChange: (Boolean) -> Unit`; its factory
(`:93-105`) fills them with `app.scaleScanner::arm`, `app.scaleScanner::disarm`,
and a lambda capturing the `Application` that calls
`ContextCompat.startForegroundService(app, intent)` / `app.stopService(intent)`.

Every sibling ViewModel takes named interfaces — `ConfigStore`, `ConsentStore`,
`DeliveryTrigger`, `ReadingDao`, `ScaleRegistrar` — each existing so the
ViewModel is testable without a `Context` (`DeliveryTrigger`'s KDoc says exactly
that). `ScaleViewModel` instead holds three anonymous lambdas that transitively
capture `Application` and construct `Intent`s: the ViewModel layer decides when
an Android `Service` starts. With no seam to fake, arm/disarm/bridge behaviour
can only be tested by passing three ad-hoc lambdas resembling nothing in
production.

`ScaleRegistrar`, added in this same diff, is the counter-example: an
Android-implementation-behind-an-interface seam for an equally Android-bound
concern.

### P11. Two parallel diagnostics counter systems — 8 of the registry's 10 keys can never be non-zero

`diagnostics/DiagnosticsCounters.kt:23-25` states the rule outright: "Every later
package increments through this interface **rather than inventing its own
field** (`01-plan.md` §2.1)." The decode layer invents its own anyway:

- `ble/decoders/MeasurementCorrelator.kt:44-49` — `duplicateFramesSuppressed`,
  `unpairableFramesDropped`, incremented at `:56, 60, 78, 90`.
- `ble/decoders/BeurerDecoder.kt:50, 227, 230` — `malformedCount` plus getters
  re-exposing the correlator's two.

Nothing bridges the two systems. Verified tree-wide: **`app/src/main` contains
exactly four `DiagnosticsCounters` call sites, all in `GattSession.kt`** —
`INCOMPATIBLE_STREAK` (`:253` increment, `:267` reset), `REGISTRATION_REJECTED`
(`:365`), and `DUPLICATE_STABLE_SUPPRESSED` (`:518`). Seven of the ten keys are
never incremented anywhere in main: `MISSED_QUOTA`, `MALFORMED_COUNT`,
`UNPAIRABLE_FRAMES_DROPPED`, `NO_MEASUREMENT`, `DUPLICATES_SUPPRESSED`,
`DROPPED_OTHER_USER`, `REMOTE_DUPLICATES_SUPPRESSED`. The eighth,
`DUPLICATE_STABLE_SUPPRESSED`, has a single write site that is unreachable (P13).

Each has a live owner that simply doesn't report: `SessionOutcome.DecodeFailure`
carries a `malformedCount` that `ScaleSessionWorker.kt:44` discards into
`else -> Result.failure()`; `ReadingIngestor` returns `IngestResult.Duplicate`
without touching `DUPLICATES_SUPPRESSED`; `DeliveryDrainer.kt:61` sets
`remoteDuplicate = true` without touching `REMOTE_DUPLICATES_SUPPRESSED`.

Cost is user-visible: `HistoryViewModel` surfaces `diagnostics.observeAll()` in
the History screen, so the app renders a diagnostics panel where only
`INCOMPATIBLE_STREAK` and `REGISTRATION_REJECTED` can ever move, while the counts
that *are* being kept sit in decoder fields nothing reads. The
`PersistentDiagnosticsCountersTest` (WP-26) that the KDoc says will "check that
mechanically" would fail against this branch's actual ownership.

### P12. Of the three `flush()` call sites the interface revision mandates, only one is reachable

`02-interface-revision.md` §3 is explicit: "The session calls `flush()` on
`SessionComplete`, at teardown, and — added in the Phase 2 reconciliation
(O-02) — on the expiry of a dedicated **E17** body-composition correlation
timeout." Only the third landed.

- **E17 expiry** — present, `ble/session/GattSession.kt:493-495`.
- **`SessionComplete`** — present at `:451-456`, but `BeurerDecoder` never emits
  `DecodeEvent.SessionComplete`, so the branch is unreachable for the only
  decoder in the tree. (H8 notes the second event loop lacks this branch; the
  separate point here is that *neither* copy can fire.)
- **Teardown** — **absent.** `run()`'s `finally` (`:88-94`) calls
  `forwarder.cancel()` and `transport.close()`, nothing more. No terminal path
  flushes: `Missed(ADAPTER_OFF)` (`:470, 477, 498`), `Missed(DROPPED)`
  (`:471, 499`), the `HARD_SESSION_CEILING` expiry (`:86-87`), and every
  `HandshakeFailed` return discard a buffered weight frame silently.

This inverts E17's stated purpose. §3: "Applied to a completed weight awaiting
body composition, that rule discards a real measurement and inverts its own
rationale. E17's action is therefore **persist the weight-only row**, not
discard it." Today a weigh-in whose weight frame arrived and whose connection
then dropped inside the 4 s correlation window is lost — the exact loss E17 was
introduced to close, reached through a different exit.

**Route to the correctness pass**: this is the one finding here with a direct
data-loss consequence.

### P13. The E9 latch's old session-side site survives as provably dead code

`02-interface-revision.md` §3: "Because the correlator now owns what 'an
emission' is, `00-design.md` §2.3 E9's in-session latch **moves here from
`GattSession`**; leaving it in the session would split one invariant across two
layers."

The correlator side landed correctly — `MeasurementCorrelator.kt:51, 114, 166`
implement `MAX_EMISSIONS_PER_SESSION = 1` and `correlationClosed`, and once one
`Stable` has been emitted `onNotification()` can never return `Stable` again. The
session's old latch site was not removed:

```kotlin
// GattSession.kt:516-519, inside finishEmission()
is TransportEvent.CharacteristicChanged -> {
    if (decoder.onNotification(event.char, event.value) is DecodeEvent.Stable) {
        diagnostics.increment(DiagnosticsCounterKey.DUPLICATE_STABLE_SUPPRESSED)
    }
}
```

`finishEmission` runs *only after* an emission, so `correlationClosed` is already
true and the condition can never hold. Verified: this is the **sole** write site
for that counter key, so the key is permanently zero (P11).

The cost is not the dead line. It is that the code reads as though the session
still enforces E9 — a maintainer asking "where are duplicate stables
suppressed?" finds this, concludes the session owns it, and reasons about the
invariant in the wrong layer, which is precisely the split the revision moved it
to avoid.

---

## MEDIUM

### P14. `SessionOutcome.Completed` carries a `List` for a 0-or-1 value, and means two different things

`ble/session/SessionOutcome.kt:7` — `data class Completed(val readings: List<ScaleReading>)`.

1. **The list is a leftover of the retired `MAX_USERS_PER_SESSION = 2` model.**
   `02-interface-revision.md` §3's O-03 correction replaced it with
   `MAX_EMISSIONS_PER_SESSION = 1` — "one emission per session, full stop" — and
   the correlator enforces exactly that. The only two constructors in the tree
   are `Completed(listOf(reading))` (`GattSession.kt:525`) and
   `Completed(emptyList())` (`:359`). Callers still write iteration for a
   cardinality the design forbids: `ScaleSessionWorker.kt:39`'s
   `outcome.readings.forEach { app.readingIngestor.ingest(address, it) }` would
   cheerfully ingest two readings from one session — the misattribution O-03
   exists to prevent — if the latch were ever relaxed. `Completed(val reading:
   ScaleReading)` plus a distinct handshake-success case makes that
   unrepresentable.
2. **`Completed` conflates two successes.** With `stopAfterHandshake = true` (the
   registration path), `:359` returns `Completed(emptyList())` meaning
   "registration succeeded". `AndroidScaleRegistrar.kt:113` maps
   `is SessionOutcome.Completed ->` to the **failure** string "Registration
   finished without a user slot", while `ScaleSessionWorker` reads the same case
   as a successful measurement with nothing to ingest. One sealed case, two
   call-site meanings, disambiguated only by which caller you are.

### P15. The revised decoder interface has drifted from `02-interface-revision.md`, which is still marked "Status: complete"

The revision document is normative for this interface — it explicitly supersedes
`00-design.md` §2.6. The shipped `ScaleDecoder` has six members the document does
not contain: `id`, `requiredServices`, `measurementCharacteristics`,
`openingSequence(discovered, nowMillis)`, `handshakeSawUnverifiableResponse`, and
a `teardownSequence` no caller invokes. Two of the document's three data types
have grown fields: `HandshakeContext.permitsRegistration` (`ScaleDecoder.kt:105`)
and `HandshakeDirective.Abort.registrationRejected` (`:133`).

Each addition is individually well-reasoned and carries its own KDoc. The finding
is that a document reading "Status: **complete**" is now a partial description of
the interface it defines. `DecodeEvent.SessionComplete` is the sharpest instance:
§2's post-revision event list includes it, no decoder in the tree emits it, and a
reader consulting the spec to understand session event handling is reading about
a case that cannot occur.

*The dominant theme in its smallest form.*

### P16. The data layer implements a BLE-session interface

`data/ScaleProfileStore.kt:26` — `interface ScaleProfileStore : ConsentStore`,
where `ConsentStore` lives in `ble.session`. So `data` depends on `ble.session`,
inverting the direction every other pair runs (`GattSession` in `ble.session`
takes a `ConsentStore`; `ReadingIngestor` in `data` consumes `ble.ScaleReading`).

ADR-007 specifies "`GattSession` needs a new dependency (**a small consent-store
interface**)". Making the multi-profile registry *be* that interface forces a 1:N
registry to satisfy a 1:1 `credentialFor(address)` contract, resolved by silently
returning the **active** profile's credential (`:48-50`) — a semantic the
interface name does not carry. The result: two overloads with different
resolution rules (`credentialFor(address)` = active,
`credentialFor(address, scaleIndex)` = exact), and `BasculeApplication.kt:56`'s
`val consentStore: ConsentStore get() = scaleProfileStore` aliasing one object
under two identities.

### P17. `ScaleProfileStore` and `ConfigStore` are two persistence idioms for one class of data

Both hold user-facing device configuration. `ConfigStore` is an interface over
DataStore with `suspend` writes, cold `Flow`s, and a documented rationale ("so
ViewModels can be unit-tested against a fake"). `ScaleProfileStore` is an
interface over `EncryptedSharedPreferences` with **non-suspend** writes,
`.commit()` rather than `.apply()`, and `StateFlow`s mutated inline by
`persist()`. Its `require()` preconditions on `saveProfile`/`setActive`/
`replaceAll` throw synchronously into whatever thread called them.

Encrypted storage for the consent code is correct (ADR-007 requires it). Exposing
it through a differently-shaped API than the neighbouring config store is the
finding: a caller must know which store they are touching to know whether they
need a coroutine.

### P18. No shared tolerance primitive between the local and remote dedup checks

`delivery/DeliveryDrainer.kt:67-71`'s `isRemoteDuplicate` re-writes the
±`WEIGHT_TOLERANCE_KG` / ±`TIME_WINDOW_MILLIS` comparison that
`DedupPolicy.isDuplicate` (`:37-44`) already expresses. It genuinely *cannot*
call it — `RemoteReading` is not a `ReadingEntity` — so the finding is the
missing primitive rather than the duplication: `DedupPolicy` owns the constants,
and its KDoc promises "the local and remote rules cannot drift", but there is no
shared `withinTolerance(...)` both sites call. The promise is currently kept by
hand.

### P19. `ReadingIngestor` writes `HELD_CONFIRM` where ADR-006 §Scope and ADR-007 say no row can

`data/ReadingIngestor.kt:31-35` assigns `HELD_CONFIRM` whenever the matched
profile is not the active one — including when `measurement.userIndex` is null or
matches no profile.

ADR-007 resolved PRP §8.5 in favour of **Branch A**, and ADR-006 §Scope states
the consequence plainly: "`HELD_CONFIRM` is only ever written on the Branch B
path… If milestone 1 resolves PRP §8.5 in favour of Branch A, wrong-user readings
are **dropped at the persistence boundary** on an unambiguous index mismatch and
no row ever enters this status."

Reported as an **undocumented deviation, not a defect** — holding for
confirmation is arguably safer than dropping, and PRP §2 makes the local store
authoritative for capture. The cost is that the ADR set no longer describes the
code: a reader trusting ADR-006 §Scope will believe `HELD_CONFIRM` is dead and
that `HistoryViewModel.confirm`/`decline` are unreachable UI. Needs an ADR-006
amendment or a code change; it should not stay an unrecorded divergence, given
ADR-006 is the design's most safety-critical decision.

### P20. `ScaleSessionEnqueuer` is injectable at one of its two call sites

`ble/ScanBroadcastReceiver.kt:19-21` takes
`enqueuerFactory: (Context) -> ScaleSessionEnqueuer` with a production default,
and its KDoc explains the seam exists so "a test can substitute a fake instead of
touching a real `WorkManager`". `service/BridgeForegroundService.kt:26` — the
other of "the two real dispatch paths" the interface's own KDoc names —
constructs the concrete `WorkManagerScaleSessionEnqueuer(this)` directly, with no
seam. One dispatch path is testable, the other is not, for no stated reason; the
interface's justification covers both.

### P21. `ReadingMapper`'s design role was reassigned without updating the design

`data/ReadingMapper.kt:15-17` still reads "PHASE 2 SKELETON. Implemented in Phase
3 WP-13 alongside the insert path, **with the dedup gate (00-design.md §3.3) and
the user-attribution gate (§7)**". The mapper is now a pure field mapper; both
gates live in the new `ReadingIngestor`. `02-interface-revision.md` §7 also lists
`ReadingMapper` as the stub that owns them.

Beyond the stale comment (H7's category), the architectural point: the design
documents name `ReadingMapper` as the persistence-boundary gate owner, and **no
document mentions `ReadingIngestor` at all** — the component that now enforces
§3.3 and §7 is absent from the architecture record.

### P22. Permission state lives in a private composable, so the screen that needs it can't see it

`ConfigScreen.kt:134-214`'s `PermissionSection` is the only section in that file
that breaks the file's own pattern. Every sibling (`ConnectionSection`,
`UnitAndContractSection`, `CredentialsSection`) is state-hoisted — driven purely
by `ConfigUiState` plus callbacks. `PermissionSection` instead takes a stateful
`PermissionRequester`, owns its own `remember` state (`:135, 141`), a
`DisposableEffect` lifecycle observer, two `rememberLauncherForActivityResult`
launchers, and reads `LocalContext` internally (`:194`).

The consequence is not local. Permission state exists **only** inside this
private composable on the Settings screen — it is never lifted into a ViewModel.
So `ScaleScreen`, which is where the user actually arms background capture
(`vm.setAutomaticCapture` → `onArm` → `ScaleScanner.arm()`), has no access to it
and can only report the generic string at `ScaleViewModel.kt:67`: "Background
scan could not be armed. Check Bluetooth and permissions." The app knows exactly
which permission is missing and cannot say so, because the knowledge is trapped
in another screen's composable-local state.

Compounds P4: the one place permission state is modelled properly is also the one
place it cannot be reused.

---

## LOW

### P23. Composable structure is inconsistent across the four screens

Three of the four screens take their ViewModel as a **default parameter**
(`ConfigScreen.kt:71`, `ManualEntryScreen.kt:37`, `HistoryScreen.kt:45`), which
keeps them previewable and testable with a substituted ViewModel.
`ScaleScreen()` (`:32-34`) takes none and resolves
`LocalContext.current.applicationContext as BasculeApplication` inside its body,
so it cannot be rendered without a real `BasculeApplication`.

Same axis: `ConfigScreen` and `HistoryScreen` use `LazyColumn`; `ScaleScreen`
uses `Column` + `verticalScroll`. `ConfigScreen`'s `LazyColumn` holds five fixed
`item {}` blocks, where laziness buys nothing.

### P24. `SharingStarted.Eagerly` everywhere, against the project's stated coroutine convention

All four ViewModels use `stateIn(viewModelScope, SharingStarted.Eagerly, …)`. The
project's Kotlin rules specify `SharingStarted.WhileSubscribed(5_000)` for
`StateFlow` derived from cold flows. Internally consistent, hence LOW — noted
only because it compounds P6: the duplicate `ConfigViewModel` keeps a second
eager DataStore collector and a second eager encrypted-prefs read chain alive for
the lifetime of the Scale tab.

### P25. `BasculeDestination` gives one destination two navigation contracts

`ui/nav/BasculeDestination.kt:10-14` documents "three top-level, peer screens";
the enum has four, and `BasculeApp.kt:42` renders **all** `entries` as bottom-nav
items — including `ManualEntry`, which is simultaneously a nav-bar destination
and the History FAB's target (`BasculeApp.kt:59-69`). The two routes into it use
different back-stack semantics: `popUpTo(startDestination) { saveState = true }`
+ `restoreState` from the nav bar, bare `launchSingleTop` from the FAB.

*L5 covers the doc/enum count mismatch.* The pattern point is the dual navigation
contract for one destination.

---

## Reviewer's own summary

> The dominant theme is stated at the top and every HIGH is an instance of it:
> **responsibilities moved out of the components the design names, and the design
> was never amended.** `DeliveryCoordinator` is an empty shell whose one live
> constant `DeliveryDrainer` reaches back for (P2, P3); `ReadingMapper`'s gates
> moved to a `ReadingIngestor` that appears in no design document (P21); the E9
> latch moved to the correlator but its session-side site stayed (P13);
> `PermissionRequester` and `DiagnosticsCounters` are both correctly-built
> abstractions that every real call site bypasses (P4, P11).
>
> The recurring cost is the same each time: an invariant the ADRs promise is
> centrally enforced now has two or three implementations, and the one that ships
> is repeatedly the one no test executes. **P1 is the sharpest.** ADR-006 argues
> at length that the dedup denylist is safe *because*
> `DedupPolicyTest.everyStatusHasAnExplicitCorpusMembershipDecision` forces a
> per-status decision. Both named tests do exist and do exactly that — against
> `DedupPolicy`. The same rule is also written in SQL in `ReadingDao` and a third
> time in `FakeReadingDao`; tests bind to the fake, and with no `androidTest`
> source set the production `@Query` is never executed by anything. The ADR's
> safety argument is sound and covers the wrong implementation.
>
> **If only three things get fixed:** P12 (add the missing teardown `flush()` —
> the only finding here that loses real weigh-ins) · P1 (delete the SQL half of
> the dedup predicate, or extend the exhaustiveness test to reach it) · P6
> (`ScaleScreen`'s second `ConfigViewModel`, a one-line fix for a bug users hit
> on first-run registration).
>
> P2, P3, P7, P8 and P21 are all materially cheaper to fix now than after v1:
> each is a decision about *where a responsibility lives*, and every week they
> stay ambiguous adds another call site to find later. P11 is worth fixing before
> WP-26 rather than during it — the persistence work assumes an ownership map
> that this branch does not implement.
>
> **Deliberately excluded per scope** (other dimensions own these): whether
> `DeliveryDrainer`'s expiry arithmetic is correct, whether the dedup tolerance
> value is right, nullability and casting, encrypted-storage choices, the
> latency of the main-thread `commit()` calls (P9 reports the *inconsistency*,
> not its cost), and test coverage as such. Findings already in `pr-1-review.md`
> are not repeated: H2, H3, H4, H5, H6, H7, H8, M1, M4, M7, L1 and L5 each sit
> adjacent to something above, and every such finding states the difference.
