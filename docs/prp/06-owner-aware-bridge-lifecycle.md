# 06 — Owner-aware lifecycle for BridgeForegroundService

Designed 2026-09-09, **implemented and hardware-verified 2026-09-11**. §6 records
why it waited two days, §8 what the implementation changed about this plan, and
§9 the evidence.

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

## 6. Why this waited (written before implementing)

Not scope creep and not timidity — position.

- `v0.1.0` was tagged an hour before this was written. Its APK is what is
  installed on the phone.
- This is the highest-risk file in the codebase by measured defect count.
- Verifying it needs a Bluetooth toggle **and** a live weigh-in, and the
  Pixel 9 was off USB when this was written. The weakest verification
  available, on the riskiest change, immediately after a release.

**Do it when a device is in hand and there is no release in flight.** The
design does not decay; the risk of doing it badly does not either.

Both conditions were met on 2026-09-11: no release in flight, and the Pixel 9
(`4A111FDKD0000C`) attached with a current build.

## 7. How to know it worked

Not "tests pass". The refactor succeeds when a *fourth* caller can be added
without reasoning about `startId` at all — it acquires an owner and releases
it. If a new caller still has to think about start ids, the class survived
and this document was wrong.

## 8. What implementing it changed about this plan

Three things §3 and §4 did not settle. All three were decided while building,
and each is the kind of thing that would have been discovered late.

**Releases are a direct call, not an intent.** §4 had intents carrying an owner
plus an action. That does not work for a release: `startForegroundService`
throws from the background on API 31+, so a release delivered as an intent
cannot be relied on to arrive — and you cannot start a foreground service just
to tell it to stop. The service publishes a `BridgeOwnership` handle
(`instance`), and the controller calls `releaseOwner` on it synchronously.
Acquires stay intents, because they may still need to *create* the service.

A good consequence: `stopRequested` disappeared rather than moving. It existed
because `Context.stopService` is asynchronous, so observed liveness and intent
could disagree; a synchronous release leaves no gap to disagree in.

**A null `Intent` means `ALWAYS_ON`.** A sticky restart delivers no intent, so
the owner set would come back empty and the service would either stop while
always-on was configured, or need a `ConfigStore` read from `onStartCommand`.
Neither is necessary: `START_STICKY` is only ever returned while `ALWAYS_ON` is
held, so a null intent *is* that owner asking for its scan back. `WEIGH_NOW` is
deliberately not restored — seconds-long window, user was on the scale, and a
resurrected one would scan with nobody watching.

**The stop condition is membership, not emptiness.** "Stop if the set is empty"
is wrong, and subtly: removing an absent element from a set is a no-op, so a
release of something never held leaves an empty set empty and reads as "this
emptied it". That `stopSelf`s a service a concurrent acquire may have just
started — the original race in new clothes. The guard is `if (owner !in
heldOwners) return`.

**This last one was nearly shipped untested.** The first test written for it
released `WEIGH_NOW` while `ALWAYS_ON` was held, and passed with the guard
deleted, because `setOf(ALWAYS_ON) - WEIGH_NOW` is still `setOf(ALWAYS_ON)`.
Only the mutation exposed it. The test that has teeth releases when *nothing*
is held.

### The risk this moved off untestable ground

In the two-owner case the window's expiry calls `releaseOwner` and never
reaches `stopSelf` at all. So correctness there no longer depends on the
platform's newer-start no-op — the exact semantic Robolectric does not model
and which this file's bugs kept hiding behind. `stopSelf(latestStartId)` now
runs only when nothing owns the bridge, and the id passed is the newest by
construction, so it cannot be a no-op. That is better than testing around the
gap.

## 9. Evidence

**JVM lane:** 683 tests green, detekt green. detekt was itself checked by
planting a 200-character line and confirming it failed the build — an empty
report proves zero findings, not zero inputs.

**Mutations, each confirmed red then green on restore:** the membership guard in
`releaseOwner`; `restartMode` hardcoded to `START_STICKY`; the re-arm's
owners-empty guard; the null-intent acquire; and `stop()` regressed to an
unconditional `context.stopService` — that last one caught by
`stoppingAlwaysOnLeavesAWeighNowWindowHoldingTheBridge`, which is the H-2 bug.

**Hardware,** Pixel 9 Pro Fold `4A111FDKD0000C`, build installed
2026-09-11 20:35. The sequence that exercises the historical bugs together:

| Step | Observed |
|---|---|
| always-on ON at rest | bridge running |
| toggle always-on OFF | bridge stopped — the direct-call release path |
| tap "Weigh now" | bridge running — bounded acquire |
| Bluetooth off, then on | bridge still running; re-arm landed on a new `startId` |
| 120 s later | **bridge stopped** — the re-armed window still ended |

That last row is the one the JVM lane cannot produce: real `startId` allocation
across a genuine adapter cycle, with the stop carrying the newest id and
actually taking effect. Bug #3 does not reproduce.

**Not covered on hardware:** the interleaved two-owner state. `weighNow()`
deliberately skips `startBounded` when always-on is already on (it only clears
the cooldown and says so), so two owners are reachable only by enabling
always-on *during* a window. That ordering is covered in the JVM lane by
`aWeighNowWindowEndingUnderAlwaysOnReleasesTheWindowWithoutStoppingTheService`,
and per the note above, that case no longer rests on anything Robolectric
cannot see.
