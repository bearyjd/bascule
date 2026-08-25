# Plan: Scale-Admin / Hands-Off-Capture Testing Completeness

> **Status (2026-08-25): Tasks 1-4 and 5a/5b done, CI-green.** Task 5c
> (`BridgeForegroundServiceTest`) and Task 4's full scope (branches after the
> `applicationContext as BasculeApplication` cast) remain — both need a
> decision on making `BasculeApplication`'s dependencies swappable
> (`WorkerFactory` or `open` properties), materially bigger than this pass's
> `ScaleSessionEnqueuer` extraction. Task 6 (`connectedDebugAndroidTest` CI
> job) remains deliberately deferred, as originally scoped. See
> `.claude/PRPs/reports/scale-admin-testing-completeness-report.md` for full
> detail. Not moved to `completed/` — real work remains.

## Summary

A devil's-advocate review of `vitalforge-connectivity-and-login` (this session, commits `a7bb664`/`32fd6b1`) closed the highest-value testing gap in the hands-off-capture feature: `ReadingIngestor`, `ReadingMapper`, `ScaleProfileCodec`, and `DeliveryDrainer` now have full JVM unit coverage (232/232 tests green). This plan covers what's left — the Android-framework-coupled classes that logic extraction can't reach (workers, receivers, a service, the real BLE transport), plus a discovered gap this review missed: `GattSession`'s measurement-phase edge cases (E7/E8/E9/E17/E18, `docs/prp/01-plan.md` WP-10) were spec'd with a named `GattSessionMeasureTest` that was never written — the code exists, dedicated regression tests for it do not.

## User Story

As the engineer maintaining Bascule's hands-off scale capture, I want the framework-coupled classes (`ScaleScanner`, `ScanBroadcastReceiver`, `ScaleSessionWorker`, `BootReceiver`, `BridgeForegroundService`, `AndroidScaleRegistrar`) and `GattSession`'s measurement-phase edge cases under regression test, so that a future change to the wake path, delivery retry, or measurement timing fails a test instead of failing silently on a real weigh-in.

## Problem → Solution

**Current state:** `testDebugUnitTest` is 232/232 green, but every class that touches `BluetoothLeScanner`, `WorkManager`, `BroadcastReceiver`, or `Service` has zero tests, and `GattSession`'s measurement path (implemented, working, exercised only incidentally by 4 contract tests) has no dedicated edge-case coverage. CI runs only the JVM lane — `androidx-test-junit`/`androidx-test-runner`/`androidx-room-testing`/`androidx-work-testing` are pinned dependencies with no consumer; `app/src/androidTest/` does not exist.

**Desired state:** Everything reachable by Robolectric or `androidx.work`'s `TestListenableWorkerBuilder` runs in the existing JVM CI lane (no new CI infrastructure). What genuinely needs a `connectedAndroidTest` (WP-28's originally-planned scenario suite) gets that infrastructure added deliberately, as its own tracked step, not smuggled in. What needs real BLE hardware (`ScaleScanner.arm()` actually receiving an OS-delivered advertisement) stays out of CI by design — per `docs/prp/01-plan.md §0`, a standard AVD has no BLE radio at all — and is tracked as a `PHONE`-bucket checklist item, the same taxonomy this codebase already uses.

## Metadata
- **Complexity**: Large
- **Source PRD**: `docs/prp/01-plan.md` (WP-08, WP-10, WP-11, WP-25, WP-27, WP-28, WP-29 — this plan operationalizes their already-named-but-unwritten test lists)
- **PRD Phase**: Phase 3 (per `HANDOFF.md`: "instrumented tests remain unwritten... are Phase 3 work")
- **Estimated Files**: ~14 new test files, 2 small production seams, 1 CI workflow addition

---

## UX Design

N/A — internal testing infrastructure change. No user-facing behavior changes; every task in this plan is additive (tests, plus the minimum seam needed to make something testable) or CI-configuration.

---

## Mandatory Reading

| Priority | File | Lines | Why |
|---|---|---|---|
| P0 | `docs/prp/01-plan.md` | 21-60 (§0, the CI/PHONE/SCALE taxonomy) | Defines the bucket system this plan reuses — do not invent a new classification |
| P0 | `docs/prp/01-plan.md` | 867-908 (WP-08) | Names the exact tests originally planned for `ScaleScanner`/`ScanBroadcastReceiver`/`ScaleSessionWorker`, with CI/PHONE split per sub-item |
| P0 | `docs/prp/01-plan.md` | 948-991 (WP-10) | Names `GattSessionMeasureTest`'s edge cases (E7/E8/E9/E17/E18) — cross-check every assertion against current code before writing, see Risks |
| P0 | `docs/prp/01-plan.md` | 1534-1592 (WP-27, WP-28, WP-29) | `BootReceiver`/`BridgeForegroundService` test names; confirms WP-28's scenario suite is bucket **CI** (via `FakeScaleGatt` + MockWebServer, not real BLE) — it needs an emulator, not a phone |
| P0 | `app/src/main/kotlin/com/ventouxlabs/bascule/ble/session/GattSession.kt` | 27-93, 507-560 | `run()`'s `HARD_SESSION_CEILING` wrapper and `awaitMeasurement`/`finishEmission` — the actual current measurement-phase behavior to test |
| P0 | `app/src/main/kotlin/com/ventouxlabs/bascule/ble/session/SessionBudget.kt` | 63-82 | `FIRST_INDICATION_TIMEOUT` (45s, E7), `BODY_COMPOSITION_CORRELATION_WINDOW` (4s, E17), `POST_EMISSION_IDLE` (10s), `HARD_SESSION_CEILING` (90s) — exact constants every timing assertion must use, never a hardcoded literal |
| P1 | `app/src/test/kotlin/com/ventouxlabs/bascule/ble/session/GattSessionConnectTest.kt` | 1-42 | The established `FakeGattTransport` + `runTest`/`advanceTimeBy` pattern to mirror for `GattSessionMeasureTest` |
| P1 | `app/src/test/kotlin/com/ventouxlabs/bascule/ble/fake/FakeGattTransport.kt` | 1-154 | `indicate(char, value)` pushes an unsolicited frame; `emitAdapterOff()` exists; **no method emits an unsolicited mid-measurement disconnect — needs adding, see Task 1** |
| P1 | `app/src/main/kotlin/com/ventouxlabs/bascule/ble/ScaleScanner.kt` | 1-58 | `arm()`'s `automaticCaptureEnabled`/`activeProfile` gates (pure, testable without a real scanner) vs. `scanner?.startScan(...)` (needs a real radio, per §0) |
| P1 | `app/src/main/kotlin/com/ventouxlabs/bascule/ble/ScanBroadcastReceiver.kt` | 1-43 | `onReceive`'s API-31-branch parcelable extraction and `enqueueSession`'s `ExistingWorkPolicy.KEEP` — Robolectric + real `WorkManager` testing init is the right tool, not a hand fake |
| P1 | `app/src/main/kotlin/com/ventouxlabs/bascule/ble/session/ScaleSessionWorker.kt` | 1-59 | `doWork()`'s staleness abort (`STALENESS_ABORT_MILLIS`), permission check, and outcome-to-`Result` mapping — `TestListenableWorkerBuilder` pattern |
| P1 | `app/src/main/kotlin/com/ventouxlabs/bascule/service/BootReceiver.kt` | 1-24 | `goAsync()` + coroutine launch — Robolectric's `Robolectric.buildBroadcastIntent` handles this without a real device |
| P1 | `app/src/main/kotlin/com/ventouxlabs/bascule/service/BridgeForegroundService.kt` | 1-70 | Full `Service` — foreground notification, scan start/stop lifecycle |
| P1 | `.github/workflows/ci.yml` | 1-52 | Current CI has one job, no emulator step, no `connectedAndroidTest` — Task 6 adds this deliberately |
| P2 | `app/src/test/kotlin/com/ventouxlabs/bascule/ui/PermissionRequesterTest.kt` | 1-16 | The one place Robolectric is *declared* but explicitly **not used** — its own KDoc explains why (pure-logic extraction was possible there). This plan's classes generally cannot avoid Robolectric the same way; note the difference explicitly in code review rather than copy that KDoc's approach where it doesn't apply |
| P2 | `docs/prp/02-ci-notes.md` | full | JVM-lane conventions this project already has opinions about (e.g. `runBlocking` not `runTest` for real blocking IO) — follow for any new HTTP-adjacent test |

## External Documentation

| Topic | Source | Key Takeaway |
|---|---|---|
| `TestListenableWorkerBuilder` | AndroidX WorkManager testing guide (`androidx.work:work-testing`, already pinned at the project's WorkManager version in `gradle/libs.versions.toml`) | `TestListenableWorkerBuilder.from(context, ScaleSessionWorker::class.java).setInputData(data).build()` constructs a real worker instance whose `doWork()` runs synchronously in a JVM/Robolectric test — no `WorkManager.enqueue` round trip needed for unit-level coverage of `doWork()`'s branching logic |
| Robolectric + Bluetooth | Robolectric's shadow set (`org.robolectric:shadows-framework`, transitively included by `org.robolectric:robolectric`) | Robolectric ships `ShadowBluetoothAdapter`/`ShadowBluetoothDevice`/`ShadowBluetoothManager` for classic Bluetooth and connection state, but **BLE scan (`BluetoothLeScanner.startScan`) has historically thin-to-absent shadow support** — this matches `docs/prp/01-plan.md §0`'s own claim about the emulator (no BLE radio) and extends it: even Robolectric's *simulated* Android framework does not meaningfully simulate a BLE scan callback firing. Treat `ScaleScanner.arm()`'s actual `scanner?.startScan(...)` call, and `BridgeForegroundService`'s `scanner?.startScan(...)`, as **PHONE-bucket, not Robolectric-testable** — verify this claim against the exact Robolectric version pinned (`4.16.1`) before writing a test that assumes otherwise; do not assume shadow coverage that hasn't been checked for that release |
| Robolectric + BroadcastReceiver/Service | Robolectric's `@RunWith(RobolectricTestRunner::class)` + `Robolectric.buildService(...)`/`Robolectric.buildBroadcastIntent(...)` | Standard, stable, well-documented pattern for exactly `BootReceiver`, `ScanBroadcastReceiver`, `BridgeForegroundService` — the parts of those classes that don't touch BLE scan directly |

---

## Patterns to Mirror

### FAKE_GATT_TRANSPORT_TEST_PATTERN
// SOURCE: app/src/test/kotlin/com/ventouxlabs/bascule/ble/session/GattSessionConnectTest.kt:19-41
```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
class GattSessionConnectTest {
    private fun session(transport: FakeGattTransport) = GattSession(
        transport = transport,
        decoder = BeurerDecoder(),
        consentStore = InMemoryConsentStore(),
        deviceAddress = DEVICE_ADDRESS,
        diagnostics = InMemoryDiagnosticsCounters(),
    )

    @Test
    fun connectTimeoutRetriesExactlyOnce() = runTest {
        val transport = FakeGattTransport(connectOutcomes = listOf(ConnectOutcome.Timeout, ConnectOutcome.Timeout))
        val outcome = session(transport).run()
        assertEquals(SessionOutcome.Missed(MissReason.CONNECT_TIMEOUT), outcome)
        assertEquals("2 attempts total (E1)", 2, transport.connectCallCount)
    }
}
```
`GattSessionMeasureTest` (Task 1) follows this exactly — same `session()` helper shape, same `runTest`/virtual-time style, same `FakeGattTransport`. For a measurement-phase test, drive it with `transport.indicate(char, bytes)` for the frame and, where a test needs the clock to move (e.g. proving the 45s `FIRST_INDICATION_TIMEOUT` fires), `advanceTimeBy(...)`/`runCurrent()` exactly as `connectPhaseNeverExceedsTwentySeconds` (same file, line 53) already does for the connect phase.

### DIAGNOSTICS_COUNTER_ASSERTION_PATTERN
// SOURCE: app/src/main/kotlin/com/ventouxlabs/bascule/ble/session/GattSession.kt (this session's fix, DUPLICATE_STABLE_SUPPRESSED)
```kotlin
class InMemoryDiagnosticsCounters : DiagnosticsCounters {
    // already exists — app/src/main/kotlin/com/ventouxlabs/bascule/diagnostics/InMemoryDiagnosticsCounters.kt
}
```
Every `GattSessionMeasureTest` case that exercises a counted edge (E7's `noMeasurement`, E9's `duplicateStableSuppressed`) should assert `diagnostics.value(DiagnosticsCounterKey.X)` the same way `GattSessionHandshakeTest` already asserts `REGISTRATION_REJECTED` — read that file for the exact assertion shape before writing new ones.

### FAKE_OVER_MOCK_TEST_DOUBLE
// SOURCE: app/src/test/kotlin/com/ventouxlabs/bascule/data/fake/FakeScaleProfileStore.kt (this session)
```kotlin
class FakeScaleProfileStore(initial: List<ScaleProfile> = emptyList()) : ScaleProfileStore {
    private val mutableProfiles = MutableStateFlow(initial)
    override val profiles: StateFlow<List<ScaleProfile>> = mutableProfiles
    // ...
}
```
Every new fake in this plan (`FakeDeliveryTrigger` already exists; add nothing that duplicates an existing fake — grep `app/src/test/kotlin/**/fake/` first) follows this shape: implement the real interface, in-memory backing, no mocking framework. This project has zero mocking library dependency — keep it that way.

### ROBOLECTRIC_RUNNER_PATTERN (new to this codebase — no existing example; write it first, in Task 2, then mirror it for Tasks 3-5)
```kotlin
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34]) // match compileSdk / a real device API level in the support matrix, not an arbitrary pick
class ScanBroadcastReceiverTest {
    @Test
    fun enqueuesUniqueWorkWithKeepPolicy() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        // ... build the ACTION_SCAN intent, call receiver.onReceive(context, intent) directly (no need to
        // go through a real BroadcastReceiver dispatch — onReceive is a plain method), then assert against
        // WorkManagerTestInitHelper.getTestDriver(context)!!.workManager or WorkManager.getInstance(context)
    }
}
```
`androidx.work:work-testing`'s `WorkManagerTestInitHelper` needs `testImplementation(libs.androidx.work.testing)` — currently only `androidTestImplementation`, see Task 2's GOTCHA.

---

## Files to Change

| File | Action | Justification |
|---|---|---|
| `app/src/test/kotlin/com/ventouxlabs/bascule/ble/session/GattSessionMeasureTest.kt` | CREATE | Closes the WP-10 gap — measurement-phase edge cases have no dedicated test today |
| `app/src/test/kotlin/com/ventouxlabs/bascule/ble/fake/FakeGattTransport.kt` | UPDATE | Add `dropConnection(status: Int = ...)` to emit an unsolicited mid-measurement disconnect — no existing method does this |
| `app/build.gradle.kts` | UPDATE | Move `androidx.work:work-testing` (and add `androidx.test.core`'s `ApplicationProvider` if not already transitively present) to `testImplementation` alongside `androidTestImplementation`, so Robolectric-based JVM tests can use it |
| `app/src/test/kotlin/com/ventouxlabs/bascule/ble/ScanBroadcastReceiverTest.kt` | CREATE | WP-08's named tests: `enqueuesUniqueWorkWithKeepPolicy`, `secondBroadcastDuringLiveSessionIsNoOp`, `returnsWithinReceiverWindow` |
| `app/src/test/kotlin/com/ventouxlabs/bascule/ble/ScaleScannerTest.kt` | CREATE | Pure-logic subset only: `arm()`'s early-return gates (`automaticCaptureEnabled` off, no active profile) — see Task 3's scope note on what's excluded |
| `app/src/test/kotlin/com/ventouxlabs/bascule/ble/session/ScaleSessionWorkerTest.kt` | CREATE | `TestListenableWorkerBuilder`-based: staleness abort, missing-permission failure, adapter-off retry, every `SessionOutcome` → `Result` branch |
| `app/src/test/kotlin/com/ventouxlabs/bascule/service/BootReceiverTest.kt` | CREATE | `doesNotArmWhenBridgingIsDisabled` etc. — delegates to the already-tested `ScaleScanner.arm()` gate, so this test is really "did `onReceive` call `arm()` at all", not re-testing the gate logic |
| `app/src/test/kotlin/com/ventouxlabs/bascule/service/BridgeForegroundServiceTest.kt` | CREATE | `Robolectric.buildService(...)` — notification channel creation, scan start on `onCreate`, scan stop on `onDestroy` |
| `.github/workflows/ci.yml` | UPDATE (Task 6, separate/later PR — see NOT Building) | Add a `connectedDebugAndroidTest` job on an emulator, gated behind its own decision — do not bundle into this PR |

## NOT Building

- **`connectedAndroidTest` infrastructure and WP-28's scenario suite** (`ColdStartScenarioTest`, `ProcessDeathScenarioTest`, `TokenRotationScenarioTest`, `SecondUserScenarioTest`, `OutageScenarioTest`, `ContentionScenarioTest`). These are real, valuable, and already named in `docs/prp/01-plan.md` WP-28 — but adding a CI emulator job is a separate, larger decision (cost, flakiness, maintenance) that deserves its own explicit go/no-go, not a rider on a testing-gap fix. Tracked as Task 6, deliberately last and separable.
- **Real BLE scan behavior** (`ScaleScanner.arm()`'s `scanner?.startScan(...)` actually receiving an OS-delivered advertisement, `AndroidScaleRegistrar.findScale()`'s real scan callback). Per `docs/prp/01-plan.md §0`, no environment available to this plan has a BLE radio — not the emulator, not (verify, don't assume) Robolectric's shadows. This is `PHONE`-bucket by the codebase's own established taxonomy; it stays a manual/`tools/hw-probe`-driven checklist item, not a CI test.
- **`AndroidGattTransport`'s real `BluetoothGatt`/`BluetoothGattCallback` wiring.** `GattSession` itself is thoroughly tested via `FakeGattTransport`; `AndroidGattTransport` is a thin, callback-forwarding adapter with the deliberate no-logic design its own KDoc states. A Robolectric test here would mostly test Robolectric's Bluetooth shadow, not this code. Lower priority than the classes above — note as a candidate for a future pass, not built here.
- **Fixing the E8 reconnect-ladder discrepancy** discovered while planning this (see Risks) — that is a production-behavior question, not a testing-coverage one, and is explicitly out of scope for "increase testing surface."
- **Encrypted-store instrumented tests** (`EncryptedScaleProfileStore`, `EncryptedAuthTokenStore`, `EncryptedSessionCookieStore`, `EncryptedConsentStore`'s actual `EncryptedSharedPreferences`/Keystore wiring). Their pure logic is now extracted and tested (`ScaleProfileCodec`); the Keystore-backed persistence itself needs `connectedAndroidTest` (a real Android Keystore), so it's covered by Task 6's infrastructure decision, not built ahead of it.

---

## Step-by-Step Tasks

### Task 1: `GattSessionMeasureTest` — close the WP-10 measurement-phase gap
- **ACTION**: Create `GattSessionMeasureTest.kt` mirroring `GattSessionConnectTest`'s structure. Add `FakeGattTransport.dropConnection(status: Int = 19)` (emits `TransportEvent.ConnectionStateChanged(connected = false, status = status)` unsolicited, distinct from the transport's own reaction to `disconnect()`).
- **IMPLEMENT**: Before writing each assertion, re-verify against the current `GattSession.kt` (lines 27-93, 507-560) rather than trusting `01-plan.md`'s Phase-1 wording — Phase 2/3 implementation diverged from at least one named edge (E8, see GOTCHA). Cases to cover against *actual current behavior*:
  - E7: no `CharacteristicChanged` within `SessionBudget.FIRST_INDICATION_TIMEOUT` (45s) → `SessionOutcome.Missed(MissReason.NO_MEASUREMENT)`.
  - E9 (as it exists today, via this session's fix): a second `Stable`-decoding frame arriving during `POST_EMISSION_IDLE` increments `DiagnosticsCounterKey.DUPLICATE_STABLE_SUPPRESSED` and does not change the returned reading.
  - E17: a weight frame with no paired body-composition frame within `BODY_COMPOSITION_CORRELATION_WINDOW` (4s) still persists via `decoder.flush()` — weight-only, not discarded.
  - Hard ceiling: a session that never reaches a terminal outcome is cut off at `SessionBudget.HARD_SESSION_CEILING` (90s) and returns `Missed(NO_MEASUREMENT)` (see `run()`'s `withTimeoutOrNull` wrapper, line ~87).
  - Post-emission teardown: after a successful `Stable` reading, the session tears down within `POST_EMISSION_IDLE` (10s) even with no further transport events.
  - Dropped mid-measurement: using the new `dropConnection()`, confirm current behavior is `Missed(MissReason.DROPPED)` with **no reconnect attempt** — assert this as the documented current contract, not as "correct" or "incorrect" (that's a product decision, see Risks).
- **MIRROR**: `FAKE_GATT_TRANSPORT_TEST_PATTERN` above.
- **IMPORTS**: `com.ventouxlabs.bascule.ble.fake.{FakeGattTransport, ConnectOutcome, DiscoverOutcome, InMemoryConsentStore}`, `com.ventouxlabs.bascule.diagnostics.InMemoryDiagnosticsCounters`, `kotlinx.coroutines.test.{runTest, advanceTimeBy, runCurrent}`.
- **GOTCHA**: E8's "exactly one reconnect within a 5s window" from `01-plan.md` line 971-974 is **not implemented** in current `GattSession.kt` — `awaitMeasurement`'s `Dropped` branch maps straight to `Missed(DROPPED)`, no reconnect call anywhere in the measurement path. Do not write a test asserting a reconnect happens; that would be testing a feature that doesn't exist, or worse, get "fixed" by adding unrequested production behavior. Flag this gap in the PR description as a discovered discrepancy between plan and implementation, for a human to decide on separately.
- **VALIDATE**: `./gradlew testDebugUnitTest --tests "*.GattSessionMeasureTest"` — every case green; then `./gradlew testDebugUnitTest` full lane — no regressions.

### Task 2: Wire Robolectric + WorkManager testing into the JVM lane
- **ACTION**: Move `androidx.work:work-testing` from `androidTestImplementation` to also (or instead) `testImplementation` in `app/build.gradle.kts`. Confirm `ApplicationProvider` (from `androidx.test:core`, likely a transitive dependency of `androidx-test-junit`) resolves in the `test` source set once Robolectric is actually used — Robolectric brings its own `RuntimeEnvironment`, but `ApplicationProvider.getApplicationContext()` is the idiomatic call in every current Robolectric example.
- **IMPLEMENT**: Write ONE minimal smoke test first (not one of the real target classes) proving `@RunWith(RobolectricTestRunner::class)` actually runs in this project's JVM lane before building four more test files on top of it — e.g. a throwaway assertion that `ApplicationProvider.getApplicationContext<Context>()` returns non-null. Delete the smoke test once `ScanBroadcastReceiverTest` (Task 3) passes and proves the same thing for real.
- **MIRROR**: N/A — first use in this codebase. `PermissionRequesterTest`'s KDoc (P2 reading) explains why it avoided Robolectric; that reasoning does not apply to `BroadcastReceiver`/`Service`/`CoroutineWorker` classes, which have no seam to extract the Android-coupled parts away from.
- **IMPORTS**: `org.robolectric.RobolectricTestRunner`, `org.robolectric.annotation.Config`, `androidx.test.core.app.ApplicationProvider`, `org.junit.runner.RunWith`.
- **GOTCHA**: `testOptions.unitTests.isIncludeAndroidResources = true` is already set (`app/build.gradle.kts:42-44`) — required for Robolectric, already done, do not re-add. Pin `@Config(sdk = [...])` to a real API level already in this project's support matrix (check `minSdk`/`targetSdk`/`compileSdk` in `gradle/libs.versions.toml`) rather than an arbitrary one.
- **VALIDATE**: `./gradlew testDebugUnitTest` — smoke test passes, full lane still green, build time doesn't balloon unreasonably (Robolectric's first run downloads Android jar sets — note the one-time cost, don't be alarmed by it).

### Task 3: `ScanBroadcastReceiverTest` (Robolectric + real `WorkManager` test driver)
- **ACTION**: Cover WP-08's named cases for this class.
- **IMPLEMENT**: `enqueuesUniqueWorkWithKeepPolicy` (call `receiver.onReceive(context, intent)` directly with a constructed `ACTION_SCAN` intent carrying a scripted `EXTRA_LIST_SCAN_RESULT`; assert via `WorkManagerTestInitHelper` that `ScaleSessionWorker.UNIQUE_WORK_NAME` was enqueued with `ExistingWorkPolicy.KEEP`), `secondBroadcastDuringLiveSessionIsNoOp` (enqueue twice, assert still one work item — this is what `KEEP` actually buys), `returnsWithinReceiverWindow` (assert `onReceive` itself does no blocking work — a timing/architectural assertion, not a literal 10s test; check what "10 s limit" means concretely before writing this one, it may just be "onReceive doesn't call anything suspend").
- **MIRROR**: `ROBOLECTRIC_RUNNER_PATTERN` above.
- **IMPORTS**: `androidx.work.testing.WorkManagerTestInitHelper`, `androidx.test.core.app.ApplicationProvider`.
- **GOTCHA**: `ScanBroadcastReceiver.onReceive`'s SDK branch (`Build.VERSION.SDK_INT >= TIRAMISU`) needs two `@Config(sdk = [...])` variants or two `@Test` methods pinning different SDKs to cover both the API-33+ typed `getParcelableArrayListExtra` overload and the deprecated pre-33 one.
- **VALIDATE**: `./gradlew testDebugUnitTest --tests "*.ScanBroadcastReceiverTest"`.

### Task 4: `ScaleSessionWorkerTest` (`TestListenableWorkerBuilder`)
- **ACTION**: Cover `doWork()`'s branching — this is real logic (staleness check, permission check, adapter state, outcome mapping), not glue, and per `WP-08`'s own split table the CI-testable half is exactly this.
- **IMPLEMENT**: `abortsWhenStalenessExceedsThreshold` (input data `seenAt` older than `STALENESS_ABORT_MILLIS` → `Result.success()` with no session attempted — needs a fake/seam for "was a session attempted"; consider whether `ScaleOperationCoordinator`/`GattSession` construction can be verified via a spy-free means, e.g. injecting a fake `BasculeApplication`-shaped dependency bundle rather than the real `applicationContext as BasculeApplication` cast — this may need a small constructor-injection seam on `ScaleSessionWorker`, mirroring how `DeliveryWorker` already got one for `DeliveryDrainer` this session), `failsWithoutBluetoothConnectPermissionOnApi31Plus`, `retriesWhenAdapterIsOff`, `succeedsAndIngestsOnCompletedOutcome`, `failsOnMissedNonAdapterOff` (matches the `else -> Result.failure()` branch — note this collapses `HandshakeFailed`/`Incompatible`/`DecodeFailure` into one outcome; test at least one representative, not all three redundantly).
- **MIRROR**: `ROBOLECTRIC_RUNNER_PATTERN`; `androidx.work.testing.TestListenableWorkerBuilder.from(context, ScaleSessionWorker::class.java).setInputData(...).build()`.
- **IMPORTS**: `androidx.work.testing.TestListenableWorkerBuilder`, `androidx.work.Data`.
- **GOTCHA**: `ScaleSessionWorker.doWork()` casts `applicationContext as BasculeApplication` and reaches into `app.scaleProfileStore`, `app.scaleOperationCoordinator`, `app.diagnosticsCounters`, `app.readingIngestor`, `app.deliveryScheduler` directly (`ScaleSessionWorker.kt:709-719`) — this is the actual blocker to clean unit-testing, not Robolectric itself. Decide before writing tests: either (a) run against a real (test-configured) `BasculeApplication` subclass registered via `AndroidManifest`'s test variant, or (b) add a small constructor-injectable seam. Prefer (b), consistent with this session's `DeliveryWorker` → `DeliveryDrainer` split — but confirm with the user/reviewer before doing a second production refactor in the same spirit, since it touches how the real app wires the worker (`AndroidManifest.xml`'s default `WorkerFactory`).
- **VALIDATE**: `./gradlew testDebugUnitTest --tests "*.ScaleSessionWorkerTest"`.

### Task 5: `BootReceiverTest` and `BridgeForegroundServiceTest` (Robolectric)
- **ACTION**: Cover WP-27's named cases.
- **IMPLEMENT**: `BootReceiverTest.doesNotArmWhenBridgingIsDisabled`/`doesNotArmWhenPermissionsAreMissing` are really assertions that `onReceive` calls `scaleScanner.arm()` and lets `arm()`'s own already-tested gates (Task 3's `ScaleScannerTest`, the pure-logic subset) decide — inject a fake/spy-free `ScaleScanner` substitute if the class doesn't already support one, rather than re-testing `arm()`'s internal gating logic here. `BridgeForegroundServiceTest.isOffByDefault`/`stopsScanOnDestroy`/`usesLowPowerScanMode` via `Robolectric.buildService(BridgeForegroundService::class.java).create().get()`, asserting the notification channel exists and `onDestroy()` calls `stopScan`.
- **MIRROR**: `ROBOLECTRIC_RUNNER_PATTERN`.
- **IMPORTS**: `org.robolectric.Robolectric`, `org.robolectric.Shadows.shadowOf` (for `NotificationManager`/`BluetoothAdapter` shadow assertions where useful).
- **GOTCHA**: `BridgeForegroundService.startActiveScan()` calls the real `scanner?.startScan(...)` — per the External Documentation note above, verify whether Robolectric's shadow actually invokes the `ScanCallback` before writing any test that depends on a scan *result* arriving; if it doesn't (likely), scope this test to lifecycle/permission/notification behavior only, not scan-result handling (which `ScanBroadcastReceiverTest`, Task 3, already covers via the receiver path instead).
- **VALIDATE**: `./gradlew testDebugUnitTest --tests "*.BootReceiverTest" --tests "*.BridgeForegroundServiceTest"`.

### Task 6 (separate PR, own decision): `connectedDebugAndroidTest` CI job
- **ACTION**: Only after Tasks 1-5 land and are reviewed. Add an emulator step to `.github/workflows/ci.yml` (e.g. `reactivecircus/android-emulator-runner`), create `app/src/androidTest/kotlin/...`, and begin WP-28's scenario suite plus the encrypted-store instrumented tests this plan deliberately deferred.
- **IMPLEMENT**: Out of scope for this plan's task list — this task exists to be scheduled, not executed here. Its own plan should size the CI time/cost tradeoff (emulator boot + test run time per PR) before committing to it.
- **VALIDATE**: N/A here.

---

## Testing Strategy

### Unit Tests

| Test | Input | Expected Output | Edge Case? |
|---|---|---|---|
| `GattSessionMeasureTest.noNotificationWithinFortyFiveSecondsYieldsNoMeasurement` | No `CharacteristicChanged` for 45s | `Missed(NO_MEASUREMENT)` | Yes — E7 |
| `GattSessionMeasureTest.aSecondStableFrameDuringPostEmissionIdleIsCountedNotEmitted` | Two `Stable`-decoding frames within `POST_EMISSION_IDLE` | First reading returned; `DUPLICATE_STABLE_SUPPRESSED` incremented | Yes — E9 (this session's fix) |
| `GattSessionMeasureTest.missingBodyCompositionFlushesWeightOnlyAfterFourSeconds` | Weight frame, no body-comp frame for 4s | `Completed` with weight-only reading | Yes — E17 |
| `GattSessionMeasureTest.disconnectDuringMeasurementYieldsDroppedWithNoReconnectAttempt` | `dropConnection()` mid-`awaitMeasurement` | `Missed(DROPPED)`, `transport.connectCallCount` unchanged | Yes — documents the E8 discrepancy |
| `ScanBroadcastReceiverTest.enqueuesUniqueWorkWithKeepPolicy` | `ACTION_SCAN` intent with one scan result | One `ScaleSessionWorker` work item enqueued | No — happy path |
| `ScaleSessionWorkerTest.abortsWhenStalenessExceedsThreshold` | `seenAt` older than `STALENESS_ABORT_MILLIS` | `Result.success()`, no session attempted | Yes — E10 |
| `BootReceiverTest.doesNotArmWhenBridgingIsDisabled` | `automaticCaptureEnabled = false` | `arm()` returns `false`, no scan registered | Yes |

### Edge Cases Checklist
- [x] Empty input — N/A per-class, covered individually above (e.g. `ScanBroadcastReceiver` with an empty scan-result list, already implicitly true via `results.firstOrNull() ?: return`)
- [x] Maximum size input — N/A, no unbounded collections in this subsystem's inputs
- [x] Invalid types — N/A, sealed types throughout
- [ ] Concurrent access — explicitly out of scope for this plan; `ScaleOperationCoordinator`'s mutual-exclusion behavior already has `ScaleOperationCoordinatorTest` (this session)
- [x] Network failure — N/A to this plan's classes (covered by existing `DeliveryDrainerTest`)
- [x] Permission denied — `ScaleSessionWorkerTest.failsWithoutBluetoothConnectPermissionOnApi31Plus`

---

## Validation Commands

### Static Analysis
```bash
./gradlew detekt lintDebug
```
EXPECT: Zero issues (same gate this session's fixes already pass).

### Unit Tests
```bash
./gradlew testDebugUnitTest
```
EXPECT: All tests pass, count strictly increases from the current 232.

### Full Test Suite
```bash
./gradlew testDebugUnitTest detekt lint assembleDebug --rerun-tasks
```
EXPECT: BUILD SUCCESSFUL, matching this session's validation gate exactly.

### Manual Validation
- [ ] After Task 2, confirm Robolectric's first-run Android jar download doesn't break CI (check the GitHub Actions run's `Unit tests` step timing before/after)
- [ ] After Task 4, confirm the `BasculeApplication` cast seam decision (GOTCHA) was actually discussed with the user before landing, since it's a second production-code refactor in the same spirit as `DeliveryDrainer`'s extraction

---

## Acceptance Criteria
- [ ] Tasks 1-5 completed (Task 6 explicitly deferred to its own decision)
- [ ] All validation commands pass
- [ ] Tests written and passing for every class named in "Files to Change"
- [ ] No type errors, no detekt/lint errors
- [ ] The E8 reconnect-ladder discrepancy is documented in the PR description, not silently resolved either direction

## Completion Checklist
- [ ] Code follows discovered patterns (fakes-over-mocks, `runTest`/`advanceTimeBy` for virtual time, Robolectric only where a real seam extraction isn't possible)
- [ ] Error handling matches codebase style
- [ ] Tests follow the naming convention already established (`ClassNameTest.methodNameDescribingTheAssertion`)
- [ ] No hardcoded timing literals — always reference `SessionBudget`/`DeliveryCoordinator`/`DedupPolicy` constants
- [ ] No unnecessary scope additions — Task 6 stays deferred, real-BLE scan behavior stays PHONE-bucket
- [ ] Self-contained — the one open question (Task 4's `BasculeApplication` seam) is called out explicitly, not silently decided

## Risks

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Robolectric's Bluetooth/BLE shadows don't cover what a test assumes, producing a green test that verifies nothing real | Medium | High — false confidence is worse than a known gap | Task 5's GOTCHA explicitly requires checking shadow behavior before depending on it; scope tests to lifecycle/logic, not simulated scan results, wherever real shadow coverage is unverified |
| `01-plan.md`'s named edge cases (E8 especially) don't match current implementation, since Phase 2/3 diverged from the Phase 1 spec in ways not fully re-documented | Confirmed for E8 | Medium | Task 1 tests actual behavior and flags the divergence explicitly rather than either fabricating a test for unimplemented behavior or silently normalizing the gap |
| `ScaleSessionWorkerTest` (Task 4) needs a production seam (`BasculeApplication` injection) whose exact shape isn't decided yet | Medium | Medium — could stall Task 4 or trigger scope creep into a second refactor | GOTCHA explicitly flags this as a decision point requiring confirmation before implementation, not something to resolve unilaterally mid-task |
| Task 6 (CI emulator) creep — someone starts it inside this plan's PR instead of as its own tracked decision | Low if this plan is followed | Medium (CI cost/flakiness surprise) | Task 6 is explicitly marked "separate PR, own decision" in both Files to Change and NOT Building |

## Notes

This plan was generated by `/prp-plan` directly after a devil's-advocate review and its fix pass (commits `a7bb664`, `32fd6b1`) closed the pure-logic testing gap in the same subsystem. Read those commits' diffs before starting Task 1 — the `DeliveryWorker` → `DeliveryDrainer` extraction is the concrete precedent Task 4's GOTCHA points to for how this codebase prefers to make Android-framework-coupled classes testable (extract the logic, leave a thin adapter) rather than defaulting straight to Robolectric.
