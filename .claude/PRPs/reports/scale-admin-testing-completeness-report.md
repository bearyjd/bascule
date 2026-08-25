# Implementation Report: Scale-Admin / Hands-Off-Capture Testing Completeness

## Summary

Implemented Tasks 1-5 of the testing-completeness plan: `GattSessionMeasureTest`
(closing a gap discovered mid-plan — WP-10's named measurement-phase edge
cases were never written), Robolectric wired into the JVM lane for the first
time in this codebase, a `ScaleSessionEnqueuer` seam extracted from
`ScanBroadcastReceiver`/`BridgeForegroundService`, and Robolectric-based
coverage for `ScanBroadcastReceiver`, `ScaleSessionWorker`'s pre-`BasculeApplication`
branches, `ScaleScanner.arm()`'s pure gates, and `BootReceiver`. Task 6
(`connectedAndroidTest` CI infrastructure) remains deliberately deferred per
the plan's own scope boundary.

## Assessment vs Reality

| Metric | Predicted (Plan) | Actual |
|---|---|---|
| Complexity | Large | Large — confirmed |
| Estimated Files | ~14 new test files, 2 small production seams, 1 CI workflow addition | 9 new test files, 2 new production files (`ScaleSessionEnqueuer`, `FakeScaleSessionEnqueuer`), 4 production seams (`ScanBroadcastReceiver`, `BridgeForegroundService`, `BootReceiver`, `app/build.gradle.kts`), 0 CI workflow changes (Task 6 deferred as planned) |
| Confidence | N/A (plan didn't score this) | High for Tasks 1-3 (locally CI-verified twice over); Task 4/5 scoped down from the plan's original ambition after concrete evidence the full scope wasn't safely reachable |

## Tasks Completed

| # | Task | Status | Notes |
|---|---|---|---|
| 1 | `GattSessionMeasureTest` | Done | Also fixed a real bug in the test's own first draft: discovered `MeasurementCorrelator.MAX_EMISSIONS_PER_SESSION = 1` makes the prior session's `DUPLICATE_STABLE_SUPPRESSED` counter structurally unreachable in the post-emission-idle scenario — documented, not silently worked around |
| 2 | Wire Robolectric + `WorkManager` testing into the JVM lane | Done | Deviated — the plan assumed `WorkManagerTestInitHelper` would work; it hits a real `UnsatisfiedLinkError` (native SQLite) in this environment, a known upstream Robolectric limitation. Resolved via the `ScaleSessionEnqueuer` seam instead (see Task 3) |
| 3 | `ScanBroadcastReceiverTest` | Done | Deviated from the plan's exact task text (which expected asserting against a real `WorkManagerTestInitHelper`-backed `WorkManager`) — extracted `ScaleSessionEnqueuer` so the test needs no real `WorkManager` at all |
| 4 | `ScaleSessionWorkerTest` | Done, deliberately scoped down | Only the branches that return before `applicationContext as BasculeApplication` (staleness abort, permission check). The adapter-off/profile-mismatch/outcome-mapping branches need `BasculeApplication`'s dependencies to be swappable — a materially bigger decision, left open and documented rather than done unilaterally |
| 5a | `ScaleScannerTest` | Done | Only `arm()`'s pure early-return gates, per the plan's own scope note |
| 5b | `BootReceiverTest` | Done | Added the same injectable-seam pattern as Task 3 (`arm: suspend (Context) -> Boolean`) to make it testable at all |
| 5c | `BridgeForegroundServiceTest` | Not done | Not reached — see Deviations |
| 6 | `connectedDebugAndroidTest` CI job | Deliberately deferred | Per the plan's own explicit scope boundary — a separate decision |

## Validation Results

| Level | Status | Notes |
|---|---|---|
| Static Analysis (detekt) | Pass | One `MaxLineLength` violation caught by CI and fixed (`BootReceiver.kt`) |
| Unit Tests | Pass | 249 tests total (232 baseline + 17 new), all green in CI |
| Build (assembleDebug) | Pass | |
| Lint | Pass | |
| Edge Cases | Partial | See Deviations — Task 4/5's full scope wasn't safely reachable; documented rather than silently dropped |

**Local vs CI verification split**: Tasks 1-3 and Task 4 were fully verified locally (real green `./gradlew` runs) before commit. Task 5's two files (`ScaleScannerTest`, `BootReceiverTest`) were committed with explicit disclosure that local *execution* verification was blocked by a shared, per-user disk quota this sandbox ran into (other concurrent Claude Code sessions on the same machine exhausting `/tmp` quota, confirmed by watching an already-passing test start failing identically with no code change) — both compiled cleanly locally, and CI (an isolated environment) subsequently confirmed both pass for real.

## Files Changed

| File | Action | Lines |
|---|---|---|
| `app/src/test/kotlin/com/ventouxlabs/bascule/ble/session/GattSessionMeasureTest.kt` | CREATED | +166 |
| `app/src/test/kotlin/com/ventouxlabs/bascule/ble/fake/FakeGattTransport.kt` | UPDATED | +6 (`dropConnection()`) |
| `app/build.gradle.kts` | UPDATED | +4 (Robolectric/work-testing to `testImplementation`) |
| `app/src/main/kotlin/com/ventouxlabs/bascule/ble/session/ScaleSessionEnqueuer.kt` | CREATED | +30 |
| `app/src/main/kotlin/com/ventouxlabs/bascule/ble/ScanBroadcastReceiver.kt` | UPDATED | rewritten, seam-injected |
| `app/src/main/kotlin/com/ventouxlabs/bascule/service/BridgeForegroundService.kt` | UPDATED | uses `ScaleSessionEnqueuer` directly |
| `app/src/test/kotlin/com/ventouxlabs/bascule/ble/ScanBroadcastReceiverTest.kt` | CREATED | +97 |
| `app/src/test/kotlin/com/ventouxlabs/bascule/ble/fake/FakeScaleSessionEnqueuer.kt` | CREATED | +14 |
| `app/src/test/kotlin/com/ventouxlabs/bascule/ble/session/ScaleSessionWorkerTest.kt` | CREATED | +84 |
| `app/src/main/kotlin/com/ventouxlabs/bascule/service/BootReceiver.kt` | UPDATED | injectable `arm` seam |
| `app/src/test/kotlin/com/ventouxlabs/bascule/ble/ScaleScannerTest.kt` | CREATED | +51 |
| `app/src/test/kotlin/com/ventouxlabs/bascule/service/BootReceiverTest.kt` | CREATED | +28 |

## Deviations from Plan

1. **`WorkManagerTestInitHelper` doesn't work in this environment** (native SQLite `UnsatisfiedLinkError`, matches upstream Robolectric issues #7879/#8754/#9099). The plan's Task 3 assumed it would. Resolved by extracting `ScaleSessionEnqueuer` — a real, user-approved production seam (confirmed via `AskUserQuestion` mid-implementation) rather than a workaround.
2. **Task 4 scoped down further than planned.** The plan's own GOTCHA already flagged the `applicationContext as BasculeApplication` cast as a decision point; concrete evidence (Robolectric crashing on `BasculeApplication.onCreate()`'s real `WorkManager`/Keystore bootstrap) confirmed a full fix needs either a `WorkerFactory` or `open`/overridable `BasculeApplication` properties — materially bigger than the `ScaleSessionEnqueuer` extraction. Left open rather than done unilaterally; only the pre-cast branches are tested.
3. **`BridgeForegroundServiceTest` (Task 5c) not reached.** After the environment/scope discoveries above consumed significant budget, this file was not written. It needs the same class of seam as `BootReceiver` (it also casts `application as BasculeApplication` for `scaleProfileStore.activeProfile`) plus Robolectric `Service` lifecycle testing (`Robolectric.buildService(...)`) not yet exercised anywhere in this pass.
4. **A local sandbox environment issue (shared per-user disk quota) blocked local test *execution* for two files** (not compilation, not the code itself) — worked around by verifying via CI instead. Documented in both the commit message and this report rather than silently claimed as locally verified.

## Issues Encountered

- Shared disk quota exhaustion from other concurrent Claude Code sessions on the same machine (see Deviations #4). Mitigated: cleared this project's own regenerable `~/.gradle/caches` (25GB freed) as the one safe, in-scope cleanup; did not touch other sessions' files.
- One detekt `MaxLineLength` violation shipped in the first push of Task 5's commit, caught by CI, fixed in a follow-up commit (`80b4a6a`).

## Tests Written

| Test File | Tests | Coverage |
|---|---|---|
| `GattSessionMeasureTest.kt` | 5 | E7 (45s timeout), E17 (4s correlation flush), E8 (documents no-reconnect current behavior), post-emission teardown, second-pair-during-idle (documents the correlator latch) |
| `ScanBroadcastReceiverTest.kt` | 5 | Enqueue on valid scan result, per-broadcast enqueue count, wrong-action ignored, no-results ignored, receiver-window non-blocking |
| `ScaleSessionWorkerTest.kt` | 4 | Missing address, missing/stale `seenAt`, missing `BLUETOOTH_CONNECT` permission |
| `ScaleScannerTest.kt` | 2 | `arm()` false when automatic capture disabled, false when no active profile |
| `BootReceiverTest.kt` | 1 | `onReceive` calls the injected `arm` function and waits for it |

## Next Steps
- [ ] `BridgeForegroundServiceTest` (Task 5c) — needs the same `BasculeApplication`-seam decision as Task 4, plus `Robolectric.buildService(...)` lifecycle testing
- [ ] Task 4's full scope (adapter-off/profile-mismatch/outcome-mapping branches) — needs a `WorkerFactory` or `open` `BasculeApplication` decision, explicitly deferred to the user/reviewer
- [ ] Task 6 (`connectedDebugAndroidTest` CI job) — deliberately deferred, its own decision
- [ ] Code review via `/code-review` (in progress separately — see session notes on the parallel multi-agent review dispatched earlier)
