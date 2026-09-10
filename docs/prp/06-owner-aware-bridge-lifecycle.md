# 06 — Owner-aware lifecycle for BridgeForegroundService

Design, not a change. Written 2026-09-09, immediately after `v0.1.0`, and
**deliberately not implemented** — see §6.

## 1. The bug class

`BridgeForegroundService` has produced three bugs of one shape. Each was
fixed individually and correctly. The shape survived every fix.

| # | Instance | Fixed in |
|---|---|---|
| 1 | A stale bounded timer's no-arg `stopSelf()` killed a scan a *later* always-on start had turned on; `cancelWeighNow()`'s `stopService()` had the same bug from the other direction | `f358f99` (H-1/H-2/M-5, 2026-09-01) |
| 2 | The adapter re-arm called `stop()` then `start()`; `stopService` is asynchronous, so the start landed on the still-live instance whose `onStartCommand` never re-registers the scan | `05569e2` |
| 3 | The re-arm's own `startForegroundService` allocated a newer `startId`, orphaning the bounded timer — `stopSelf(oldId)` became a no-op and "Weigh now" would have scanned forever | `041a99d` |

### The load-bearing observation

**The class comes from multiple stop paths, not from `startId` being hard.**

Every fix so far made one more caller reason about `startId` correctly. That
is why the count keeps going up: each new caller must independently get it
right, and instance 3 was introduced *by the fix for instance 2*.

`startId` is not the problem and should not be removed. It is correct — and
becomes trivially correct — once exactly one place calls stop.

## 2. Current state

Four fields, three of them added reactively, spread across two files:

| State | Lives in | Added for |
|---|---|---|
| `lastStartMode` | service | instance 3 — replaying restart mode across a re-arm |
| `boundedEndElapsed` | service | instance 3 — rebinding the bounded stop |
| `isRunning` | service companion | instance 2 — "do not start a bridge nobody asked for" |
| `stopRequested` | `AndroidBridgeServiceController` | instance 3's review — `isRunning` cannot express intent while `stopService` is in flight |

Four independent callers reach this service: `BasculeApplication.onCreate`
(boot/always-on), `ScaleViewModel` (toggle + `weighNow` + cancel),
`AdapterStateReceiver` (re-arm), and the bounded timer itself.

That `stopRequested` had to live in the *controller* rather than the service
is the tell: observed liveness and intended state had already diverged.

## 3. Proposed design

Replace "the last caller to start wins" with explicit ownership.

```kotlin
enum class BridgeOwner { ALWAYS_ON, WEIGH_NOW }
```

The service holds `owners: Set<BridgeOwner>` plus one `weighNowDeadline`.

| Event | Effect |
|---|---|
| `start()` | `owners += ALWAYS_ON` |
| `startBounded(ms)` | `owners += WEIGH_NOW`; set deadline; arm timer |
| `stop()` | `owners -= ALWAYS_ON` |
| bounded timer fires | `owners -= WEIGH_NOW` |
| re-arm | re-register the scan; **no ownership change** |
| after any removal | `if (owners.isEmpty()) stopSelf(latestStartId)` |

**One stop path.** `releaseOwner()` is the only function that calls
`stopSelf`, and it only does so when nobody wants the service running.

Everything else falls out:

- **Restart mode** derives from `ALWAYS_ON in owners` rather than being
  replayed from `lastStartMode`.
- **The re-arm cannot orphan a timer**, because it no longer participates in
  stopping.
- **`isRunning` + `stopRequested` collapse** into `owners`, which is intent
  by construction — a caller that has not acquired an owner cannot resurrect
  the service.
- **Interleaving is not a special case.** Toggle always-on on during a
  "Weigh now" window and both owners are held; the window expiring releases
  one and the service correctly keeps running.

### What it does not change

`startId` stays. The scan still registers in `onCreate` and re-registers via
`EXTRA_REARM_SCAN`. `boundStopScheduler`, `activeAddressProvider` and
`enqueuerFactory` remain the test seams.

## 4. Implementation sketch

1. `BridgeOwner` enum; intents carry an owner plus an action
   (`ACQUIRE` / `RELEASE` / `REARM`).
2. Service: `owners`, `weighNowDeadline`, `latestStartId`; one
   `releaseOwner()`.
3. `BridgeServiceController` gains nothing — `start`/`startBounded`/`stop`/
   `rearmScan` map onto owner acquire/release. `stopRequested` is deleted.
4. Delete `lastStartMode`, `boundedEndElapsed`, `isRunning`.
5. `ScaleViewModel.cancelWeighNow()`'s `config.alwaysOnBridging` check becomes
   unnecessary — releasing `WEIGH_NOW` cannot stop a service `ALWAYS_ON`
   still holds. Removing that check is the clearest single proof the
   refactor worked.

## 5. Tests

Each of the three historical instances becomes a regression test that would
have failed before its fix:

- `aBoundedWindowExpiringDoesNotStopAnAlwaysOnScan`
- `cancelWeighNowDoesNotStopAnAlwaysOnScan`
- `aRearmDuringABoundedWindowLeavesTheWindowEnding`
- `aRearmDoesNotResurrectAServiceNobodyOwns`
- `theServiceStopsOnlyWhenTheLastOwnerReleases`
- `anAlwaysOnAcquireDuringABoundedWindowKeepsBothOwners`

Mutation-check each. Note the standing constraint: Robolectric records
`stopSelf(int)` but does not model the platform's newer-start no-op, so
assert *which id your code passes*, never "the service stopped"
(`robolectric-stopself-startid-not-modeled`). And when testing the second
condition of a compound guard, make the fixture satisfy the first — a guard
test was vacuous for exactly that reason on 2026-09-08.

## 6. Why this is not implemented yet

Not scope creep and not timidity — position.

- `v0.1.0` was tagged an hour before this was written. Its APK is what is
  installed on the phone.
- This is the highest-risk file in the codebase by measured defect count.
- Verifying it needs a Bluetooth toggle **and** a live weigh-in, and the
  Pixel 9 was off USB when this was written. The weakest verification
  available, on the riskiest change, immediately after a release.

**Do it when a device is in hand and there is no release in flight.** The
design does not decay; the risk of doing it badly does not either.

## 7. How to know it worked

Not "tests pass". The refactor succeeds when a *fourth* caller can be added
without reasoning about `startId` at all — it acquires an owner and releases
it. If a new caller still has to think about start ids, the class survived
and this document was wrong.
