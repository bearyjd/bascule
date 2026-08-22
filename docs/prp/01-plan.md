# Bascule — Phase 1 Plan

Status: **complete, pending Phase 2 devil's advocate**
Inputs: `00-design.md` (structure), `decisions.md` (ADR-001…006), `bascule-prp.md`
(requirements), `bascule-agent-prompt.md` (process)
Decomposes: `00-design.md` §1.1 module graph, §2.3 failure edges E1–E16, §3.2
delivery state machine, §4.3 versioned contract

**Exit gate held to:** every work package names its tests as
`ClassNameTest.methodName`. Nothing is planned that cannot be verified either in
CI or on the §5 hardware checklist.

---

## 0. The environment constraint that shapes everything below

This environment has Gradle, the Android SDK, and an emulator. It has **no
physical Beurer BF720**. It also — and this is the part that is easy to get
wrong — has **no BLE radio at all**: a standard AVD does not provide a Bluetooth
LE stack, so `BluetoothLeScanner.startScan(filters, settings, pendingIntent)`
cannot be exercised on the emulator regardless of how the app is written.

A two-way CI-vs-hardware split is therefore too coarse and would misclassify a
whole tier of work as "needs the scale" when it only needs *a phone*. Three
buckets are used throughout:

| Bucket | Meaning | Available now? |
|---|---|---|
| **CI** | JVM unit test, Robolectric, or emulator instrumented test. Runs in GitHub Actions. | **Yes** |
| **PHONE** | Real Android device with a working BLE radio. **No scale needed.** Covers scan registration, PendingIntent delivery, bond flow, adapter-off events, real GATT status codes. | Not yet — unblocks with *any* Android phone, well before the BF720 |
| **SCALE** | Physical BF720 required. Protocol constants, frame layouts, device timing, PRP §8.5. | Not yet — milestone 1 |

The `PHONE` bucket is the key move. It is small (4 packages, §4.2) and it means
"no hardware" does not collapse into "cannot verify". The Phase 3 exit gate is
satisfiable because every non-CI row lands on an explicit checklist item in §5
tagged with its bucket.

### 0.1 Consequences for sequencing

1. **Constants land early as *unconfirmed*, not last.** `BeurerProtocol.kt`
   (WP-05) is written at position 5 of 31, populated from openScale's public
   Beurer/Sanitas wiki page with ADR-002 provenance comments and an explicit
   `unconfirmed — pending live scan` marker on every value. This is what lets the
   app compile, lets the fixture corpus carry plausible frames, and lets 24
   downstream packages reach green. What is deferred to `SCALE` is **confirming
   or correcting** those values and appending the date per ADR-002 rule 2 — a
   data-only edit to one file, because everything else sits behind the
   `ScaleDecoder` interface (`00-design.md` §2.6).
2. **PRP §8.5 (user index) stays genuinely open.** Both branches of
   `00-design.md` §7 are implemented and tested (WP-15). The branch selector is
   one config value. No package assumes a resolution.
3. **The bulk of the app reaches mergeable green without hardware.** 24 of 31
   packages are `CI`. See §4.

### 0.2 Plan-level finding that amends Phase 0

**P1-A — `00-design.md` §8.8 forbids the frame capture Phase 3 requires.**

§8.8 states: "BLE frame diagnostics record **opcode and length only**, never full
payload bytes, because those bytes contain the user's body composition." E6 and
E11 both mandate recording opcode+length for the hardware log, consistent with
that rule.

But `WEIGHT_SCALE_FACTOR`, the body-comp scale factors, the frame field offsets,
and the `USER_INDEX_FIELD` question (PRP §8.5) **cannot be derived from opcode
and length**. They require full frame bytes. Worse, §5's "fix the fake to match
reality first" protocol requires *replaying real captures through
`FakeScaleGatt`* — which needs whole frames byte-for-byte. As written, Phase 3
hits a ground rule it cannot satisfy, and would either stall or quietly violate
it.

**Proposed resolution** (to be recorded as ADR-007 in Phase 2, not decided
unilaterally here): a full-frame capture path that is

- compiled only under `BuildConfig.DEBUG` (absent from release by
  `sourceSets`/`isDebuggable` gating, not by a runtime `if`),
- written to app-private storage, never to logcat, never to `lastError`,
- off by default behind a ConfigScreen debug toggle that is itself
  debug-build-only,
- emitting the `.frames` fixture format (§3.2) directly, so a capture is a
  drop-in CI fixture with no transcription step.

§8.8's production rule is unchanged: release builds still record opcode+length
only. WP-12 implements the tool; WP-30 uses it; WP-31 reconciles the fixtures.

---

## 1. Risk-first ranking

The agent prompt (§Phase 1, item 3) requires naming the two packages most likely
to be wrong and scheduling them first, and the lead asked that the design's own
candidates — **init handshake sequencing/logic** and **stabilization detection**
(`00-design.md` §2.4) — be validated or overridden against the actual breakdown.

**Verdict: the design's judgment is upheld, with both candidates re-scoped, and a
third contender named and explicitly rejected from the top two with reasoning.**

The discriminating test used: *highest consequence if wrong × likelihood of being
wrong × fully retirable in this environment*. A risk that cannot be retired now
does not benefit from being scheduled now.

### RISK-1 — Stabilization detection → **WP-03 `StabilityDetector`**

- **Likelihood: high.** `00-design.md` §2.4 rests on an unvalidated disjunction —
  either the BF720 frame carries a final/stabilized flag, or it does not. Neither
  branch is confirmed. The fallback heuristic's three numbers (±0.1 kg, ≥4
  consecutive frames, ≥2.0 s) are reasoned but not measured.
- **Consequence: the worst class in the document.** Every other failure mode
  produces a *missed* reading, which the user fixes by stepping on the scale
  again. This one produces a **silently wrong** reading that is persisted,
  delivered, and written into Garmin history. `00-design.md` §8.4 states the
  asymmetry directly: "Bad Garmin weight history is materially harder to clean up
  than a missed weigh-in is to redo."
- **Retirable now: its logic, fully; its inputs, not yet.** The detector is a
  pure function over a sequence of `(timestampMillis, weightKg, finalFlag)` and
  needs no fake, no Android, no radio — so every branch and boundary is a JVM
  unit test at position 3. What is *not* retired there is whether real BF720
  frames produce those samples: that needs `WEIGHT_SCALE_FACTOR` and the frame
  layout (WP-05, WP-09), and fixture-driven validation of the same logic lands in
  WP-10. The narrower true claim is therefore: **the detector's decision logic is
  fully retired at position 3; its input fidelity is retired at WP-10 and
  confirmed at HW-08/HW-09.** That is still the earliest and highest-value
  position available to it.
- **Scheduled at position 3** — the earliest position any behavioral package can
  occupy, behind only the build skeleton and the domain types.

### RISK-2 — Init handshake sequencing/logic → **WP-07**

Re-scoped explicitly to **sequencing and logic, not byte values**. The byte
values are `SCALE`-bucket and unknowable now; the logic around them is fully
`CI`-verifiable and is where the design can be wrong today:

- does `initSequence()` emit its `GattOp`s in the required order, and only after
  discovery confirms the required characteristics exist;
- does the session **refuse to subscribe before `InitAcknowledged`** — E6 is
  explicit ("Do not proceed to subscribe"), and this gate exists in prose only
  until a test enforces it;
- does the 3 s ack timeout × 2 retries ladder actually fit inside the §2.5
  budgets alongside the connect phase;
- what happens when an ack-shaped notification arrives on an *unexpected*
  characteristic, or arrives twice, or arrives after the retry already re-issued
  the write.

- **Likelihood: moderate-high.** The design has already been wrong twice about
  exactly this class of thing — its own self-review items 17 ("E1's ladder
  doesn't fit E1's own budget") and 21 ("the bonding path can never complete")
  were both timing-arithmetic defects caught only on a hostile re-read. That is
  direct evidence the timing/gating model is defect-prone.
- **Consequence: total silent failure.** No handshake means no notification
  stream means the app never produces a reading, in a way that looks like "the
  scale didn't work."
- **Retirable now: fully**, against `FakeScaleGatt`.
- **Scheduled at position 7**, the earliest position it can occupy — see §1.1.

### Rejected from the top two — the wake path (ADR-004) → **WP-08, RISK-3**

Seriously considered and deliberately placed third. The case for promoting it:
the highest reversal cost of anything in the plan (if
`ScanBroadcastReceiver → expedited Worker → setForeground(connectedDevice)` does
not work, ADR-004 falls and the entire primary entry path is replaced by A1's
ranked fallbacks), and total-silent-failure consequence.

The case against, which wins: **it is only half-retirable in this environment.**
The package splits cleanly into two halves with different buckets:

| Half | What it claims | Bucket |
|---|---|---|
| Receiver → enqueue → expedited worker → `setForeground` succeeds on API 31+/34+ with the right FGS type | **The ADR-004 platform claim** — the half carrying the reversal risk | **CI** (synthetic `Intent` delivered to the receiver on an emulator; no radio involved) |
| `ScaleScanner.arm()` registers a `ScanFilter` + `PendingIntent` scan, and the OS actually delivers that broadcast on a matching advertisement | Whether the wake trigger fires at all | **PHONE** — needs a real BLE stack |

Because the second half cannot be retired here at any scheduling position,
scheduling the package first would not buy what risk-first scheduling is for. The
first half — the ADR-004 claim — *is* retired in CI at position 8, which is early
enough that only WP-01…07 would need revisiting if it fails, and ADR-004 already
notes the blast radius is contained: `GattSession` is host-agnostic and
`ScaleSessionWorker` is a thin host over it.

**RISK-1 and RISK-2 are both fully retired, in CI, before any of the 23 packages
downstream of them are written. RISK-3's retirable half is retired immediately
after.** That is the strongest ordering available under the constraint.

### 1.1 Why RISK-2 is at position 7 and not position 4

WP-06 (`GattSession` connect/discover/teardown) is a mechanical prerequisite: the
handshake gate is a transition *within* the session state machine, so a minimal
session must exist to gate. WP-06 is deliberately scoped down to "reach
`DISCOVERING` and tear down cleanly" — E1–E4, E12, E15 — and excludes everything
after `HANDSHAKING`, precisely so RISK-2 lands as early as it physically can.
WP-04 (`FakeScaleGatt`) and WP-05 (constants + fixtures) are likewise
irreducible: there is nothing to drive the handshake with otherwise.

Positions 1, 2, 4, 5, 6 are enablers with no behavioral risk of their own. They
are not scheduled first because they are risky; they are scheduled first because
nothing can be tested without them.

---

## 2. Work packages

Each package is ≤ half a day of agent work, is one branch and one PR per the
Phase 3 protocol, and must be green on the full suite + lint + detekt before
merge. Every package lists the diagnostics counter it owns, if any (see §2.1).

Column key — **B** = bucket (`CI` / `PHONE` / `SCALE`).

### Track A — Foundation

---

#### WP-01 — Build skeleton, toolchain pin, CI · **B: CI**

**Files:** `settings.gradle.kts`, `build.gradle.kts`, `app/build.gradle.kts`,
`gradle/libs.versions.toml`, `gradle/wrapper/*`, `config/detekt/detekt.yml`,
`.github/workflows/ci.yml`, `app/proguard-rules.pro`, `app/src/main/AndroidManifest.xml`
(skeleton), `.gitignore`, `LICENSE` (AGPL-3.0)

**Does:** minSdk 26 / targetSdk current. Version catalog for Kotlin, Compose BOM,
Room, WorkManager, OkHttp, `androidx.security-crypto`, kotlinx-coroutines-test,
Robolectric, MockWebServer, Turbine. Room `room.schemaLocation` exported from this
commit (`00-design.md` §3.1, §8.12). ProGuard rule stripping `Log.d`/`Log.v` in
release (§8.8). `android:allowBackup="false"` + `dataExtractionRules` (§8.8). CI:
assemble, `testDebugUnitTest`, `connectedDebugAndroidTest` on an emulator matrix
(API 26, 31, 34), lint, detekt.

**Tests:**
- `SkeletonSmokeTest.appModuleAssembles`
- `ManifestPolicyTest.allowBackupIsFalse`
- `ManifestPolicyTest.dataExtractionRulesExcludeDatabaseAndPrefs`
- CI itself is the evidence: green on all three emulator API levels.

**Counter:** none.
**Phase note:** satisfies the Phase 2 exit-gate clause "CI green on skeleton".

---

#### WP-02 — Core domain types + diagnostics interface · **B: CI**

**Files:** `ble/ScaleReading.kt`, `ble/decoders/ScaleDecoder.kt` (interface only),
`ble/session/SessionOutcome.kt`, `ble/session/GattOp.kt`, `ble/session/DecodeEvent.kt`,
`network/ReadingField.kt`, `network/ContractVersion.kt`, `data/WeightUnit.kt`,
`diagnostics/DiagnosticsCounters.kt`, `diagnostics/InMemoryDiagnosticsCounters.kt`

**Does:** every pure type from `00-design.md` §2.6, §2.7, §4.3. No Android
imports — this is a plain Kotlin source set so it is JVM-testable. `ScaleReading`
has **no** `isStable` field (§2.7). `WeightUnit` conversion is kg-canonical.

Also introduces `DiagnosticsCounters` as an **interface** here, at position 2, so
every later package increments through it rather than inventing its own field
(see §2.1). `InMemoryDiagnosticsCounters` is the test double; the persistent impl
is WP-26.

**Tests:**
- `WeightUnitTest.kgToLbRoundTripsWithinOneMilligram`
- `WeightUnitTest.lbToKgMatchesKnownPairs`
- `ContractVersionTest.v1SupportsWeightOnly`
- `ContractVersionTest.v2SupportsAllReadingFields`
- `ScaleReadingTest.hasNoIsStableField` (reflection guard on §2.7's invariant)
- `InMemoryDiagnosticsCountersTest.incrementIsAdditivePerKey`

**Counter:** owns the counter surface definition.

---

### Track B — Risk-first

---

#### WP-03 — `StabilityDetector` · **B: CI** · ⚠ **RISK-1**

**Files:** `ble/decoders/StabilityDetector.kt`, `ble/decoders/WeightSample.kt`

**Does:** `00-design.md` §2.4, both paths, as a pure function over a sequence of
`WeightSample(timestampMillis, weightKg, finalFlag: Boolean?)`.

- **Flag path:** emit `Stable` on `finalFlag == true` alone.
- **Quiescence path:** all of last **≥4 consecutive** samples within **±0.1 kg**
  of each other, spanning **≥2.0 s**.
- Which path is live is a single injected `StabilityStrategy` enum — flipped with
  evidence in WP-30, not a rewrite.
- The quiescence path is implemented and tested **regardless** of which is live,
  because §2.4 makes it the guard if the flag proves unreliable on the BF720.

**Test inputs are hand-authored `WeightSample` sequences, not fixtures.** At this
position neither `BeurerProtocol` (WP-05) nor frame parsing (WP-09) exists, so
nothing can yet turn a notification into a sample. That is a deliberate scoping
choice, not an oversight: it is what lets RISK-1 be scheduled third. The ramp
sequence in `stepOnRampNeverSatisfiesQuiescence` is a **synthetic** model of
step-on dynamics; whether real step-on actually moves that fast is HW-09.
Fixture-driven validation of this same logic against decoded frames lands in
WP-10.

**Tests:**
- `StabilityDetectorTest.flagPathEmitsStableOnFirstFinalFrame`
- `StabilityDetectorTest.flagPathIgnoresQuiescenceWhenFlagAbsent`
- `StabilityDetectorTest.quiescenceRequiresFourConsecutiveSamples` (3 → no emit)
- `StabilityDetectorTest.quiescenceRequiresTwoFullSeconds` (4 samples in 1.9 s → no emit)
- `StabilityDetectorTest.quiescenceBoundaryAtExactly100Grams` (0.10 vs 0.11 kg)
- `StabilityDetectorTest.quiescenceBoundaryAtExactly2000Millis` (2000 vs 1999 ms)
- `StabilityDetectorTest.stepOnRampNeverSatisfiesQuiescence` (monotone ramp fixture)
- `StabilityDetectorTest.driftOutsideBandResetsTheWindow`
- `StabilityDetectorTest.emitsAtMostOncePerDetectorInstance`
- `StabilityDetectorTest.strategySwitchChangesNothingElse` (both strategies over
  the same sample stream, asserting only the emit point differs)

**Counter:** none.
**Hardware checklist:** HW-08, HW-09.

---

#### WP-04 — `GattTransport` + `FakeScaleGatt` engine · **B: CI**

**Files:** `ble/session/GattTransport.kt`, `ble/session/TransportEvent.kt`,
`ble/session/AndroidGattTransport.kt`,
`app/src/test/kotlin/.../fake/FakeScaleGatt.kt`,
`app/src/test/kotlin/.../fake/ScaleScript.kt`,
`app/src/test/kotlin/.../fake/ScaleScriptParser.kt`,
`app/src/test/kotlin/.../fake/VirtualClock.kt`

**Does:** the full §3 design below. `AndroidGattTransport` is the thin
`BluetoothGatt` + `BluetoothGattCallback` adapter and carries **no logic** — it
translates callbacks to `TransportEvent` and nothing else, so that
`FakeScaleGatt` substituting for it leaves no untested surface.

**Tests:** (the fake is test infrastructure, so it gets its own tests)
- `ScaleScriptParserTest.parsesFrameLinesWithRelativeTimestamps`
- `ScaleScriptParserTest.parsesOnWriteTriggerWithByteMatcher`
- `ScaleScriptParserTest.rejectsUnknownDirectiveWithLineNumber`
- `ScaleScriptParserTest.roundTripsRawFramesFileIntoScript`
- `FakeScaleGattTest.emitsEventsInScriptedOrder`
- `FakeScaleGattTest.honoursVirtualClockWithoutWallClockSleep`
- `FakeScaleGattTest.faultInjectionOverridesScriptedConnectStatus`
- `FakeScaleGattTest.closeIsIdempotentAndRecordsCallCount`
- `AndroidGattTransportTest.mapsEveryBluetoothGattCallbackToATransportEvent` (Robolectric)

**Counter:** none.

---

#### WP-05 — `BeurerProtocol` constants (unconfirmed) + fixture corpus · **B: CI**

**Files:** `ble/decoders/BeurerProtocol.kt`,
`app/src/test/resources/fixtures/beurer/*.scale` (the §3.3 corpus),
`docs/prp/constants-provenance.md`

**Does:** fills in `00-design.md` §9's symbolic table with values sourced from
**openScale's public Beurer/Sanitas wiki page**
(`github.com/oliexdev/openScale/wiki/Beurer-Sanitas`) and the corresponding
handler, reimplemented from protocol description per ADR-002 — no source copied.

Every constant carries the ADR-002 provenance comment plus an explicit
unconfirmed marker:

```kotlin
// Provenance: openScale wiki, Beurer/Sanitas page
// (github.com/oliexdev/openScale/wiki/Beurer-Sanitas), cross-checked against the
// Beurer handler in openScale source. Reimplemented from protocol description;
// no source copied.
// UNCONFIRMED — pending live scan (WP-30 / HW-03). Confirmation date appended here.
internal val BEURER_SERVICE_UUID: UUID = ...
```

A constant with **no** provenance comment is a review blocker (ADR-002 rule 3),
and in this package a constant with no `UNCONFIRMED` marker and no confirmation
date is equally a blocker — the marker is what stops an unverified value from
silently reading as verified in Phase 4.

`constants-provenance.md` is the tracking table: symbol → value → source →
confirmed date (blank until WP-30). It is the artifact WP-30 fills in and WP-31
diffs against.

**Tests:**
- `BeurerProtocolTest.everyConstantHasAProvenanceComment` — a source-scanning
  test, so ADR-002 rule 3 is machine-enforced rather than review-enforced. It
  scans **both** `BeurerProtocol.kt` and `FrameBounds.kt` (WP-09), because
  ADR-002 rule 1 covers byte offsets and magic lengths too and those live in the
  latter. The file list is a constant in the test; adding a constants file
  without adding it here is itself a review blocker.
- `BeurerProtocolTest.everyUnconfirmedConstantIsMarkedOrDated`
- `BeurerProtocolTest.uuidsAreDistinct`
- `FixtureCorpusTest.everyFixtureParses`
- `FixtureCorpusTest.corpusCoversEveryNamedScenario` (asserts the §3.3 list by name)

**Counter:** none.
**Hardware checklist:** HW-03, HW-10, HW-11 — every row in
`constants-provenance.md` is a checklist line.

---

#### WP-06 — `GattSession` connect / discover / teardown · **B: CI**

**Files:** `ble/session/GattSession.kt`, `ble/session/SessionBudget.kt`

**Does:** the §2.1 state machine from `DISARMED` through `DISCOVERING`, plus
teardown discipline. Scoped to stop at `DISCOVERING` so RISK-2 lands next.
Covers **E1** (8 s × 2, 20 s connect-phase budget), **E2** (133 → full
`close()` before retry, ≤3 retries at 500 ms/1 s/2 s), **E3** (one retry at 2 s
then `Missed(CONTENTION)`), **E4** (5 s discovery timeout, `incompatibleStreak`),
**E12** (adapter off → immediate teardown, no retry), **E15** (`close()` on
cancellation, unconditionally). `SessionBudget` owns §2.5's timer table as named
constants so the arithmetic is testable in one place.

**Tests:**
- `GattSessionConnectTest.connectTimeoutRetriesExactlyOnce` (E1)
- `GattSessionConnectTest.connectPhaseNeverExceedsTwentySeconds` (E1 budget cap)
- `GattSessionConnectTest.status133ClosesGattBeforeRetrying` (E2 — asserts
  `FakeScaleGatt.closeCallCount` ordering, the classic Android leak)
- `GattSessionConnectTest.status133RetriesAtFiveHundredOneAndTwoSeconds` (E2)
- `GattSessionConnectTest.busyStatusYieldsAfterOneRetry` (E3)
- `GattSessionConnectTest.contentionOutcomeIsMissedContention` (E3)
- `GattSessionDiscoveryTest.discoveryTimeoutAtFiveSeconds` (E4)
- `GattSessionDiscoveryTest.missingRequiredServiceYieldsIncompatible` (E4)
- `GattSessionDiscoveryTest.thirdConsecutiveIncompatibleSuspendsArming` (E4)
- `GattSessionTeardownTest.adapterOffTearsDownWithoutRetry` (E12)
- `GattSessionTeardownTest.cancellationClosesGattExactlyOnce` (E15)
- `GattSessionTeardownTest.everyTerminalPathClosesGattExactlyOnce`
  (parameterised across all outcomes — §8.10's invariant)
- `SessionBudgetTest.connectLadderFitsWithinConnectPhaseBudget`
- `SessionBudgetTest.hardCeilingExceedsSumOfNonBondTimers`

**Counter:** `incompatibleStreak` (E4).
**Hardware checklist:** HW-14, HW-17, HW-18.

---

#### WP-07 — Init handshake sequencing + E6 · **B: CI** · ⚠ **RISK-2**

**Files:** `ble/decoders/BeurerDecoder.kt` (`initSequence()`, `InitAcknowledged`
recognition only), `ble/session/GattSession.kt` (`HANDSHAKING` → `SUBSCRIBED`)

**Does:** RISK-2 as scoped in §1. `initSequence(discovered)` returns the ordered
`List<GattOp>`; the session executes them and gates `SUBSCRIBED` on
`DecodeEvent.InitAcknowledged`. **E6**: 3 s ack timeout, re-issue the init write,
max 2 retries, then `TEARDOWN` with `HandshakeFailed` — and explicitly **do not
subscribe**, because without init the BF720 does not stream measurements and
continuing burns the connection window.

**Tests:**
- `BeurerDecoderInitTest.initSequenceOrderIsDeterministic`
- `BeurerDecoderInitTest.initSequenceRequiresBothCharacteristicsPresent`
- `BeurerDecoderInitTest.ackFrameProducesInitAcknowledged`
- `BeurerDecoderInitTest.nonAckFrameDuringHandshakeIsIgnoredNotMalformed`
- `BeurerDecoderInitTest.ackOnUnexpectedCharacteristicIsNotAnAck`
- `GattSessionHandshakeTest.doesNotSubscribeBeforeInitAcknowledged` ← **the E6
  gate that exists in prose only until this test enforces it**
- `GattSessionHandshakeTest.missingAckReissuesWriteAfterThreeSeconds`
- `GattSessionHandshakeTest.reissuesAtMostTwiceThenTearsDown`
- `GattSessionHandshakeTest.handshakeFailureOutcomeIsHandshakeFailed`
- `GattSessionHandshakeTest.lateAckAfterReissueDoesNotDoubleSubscribe`
- `GattSessionHandshakeTest.duplicateAckIsIdempotent`
- `GattSessionHandshakeTest.handshakeFailureRecordsOpcodeAndLengthOnly` (§8.8)
- `SessionBudgetTest.handshakeLadderFitsWithinHardCeilingAfterConnectPhase`

**Counter:** none.
**Hardware checklist:** HW-04, HW-05.

---

#### WP-08 — Wake path: scan arm, receiver, session worker · **B: CI + PHONE** · ⚠ **RISK-3**

**Files:** `ble/ScaleScanner.kt`, `ble/ScanBroadcastReceiver.kt`,
`ble/session/ScaleSessionWorker.kt`, `AndroidManifest.xml` (receiver, permissions
per §6.1, `foregroundServiceType`)

**Does:** ADR-004's path. Receiver enqueues an expedited `OneTimeWorkRequest`
under unique name `scale-session` with `ExistingWorkPolicy.KEEP` (**E16**); the
worker calls `setForeground()` with type `connectedDevice` and owns the session.
**E10**: worker aborts before connecting if `now - enqueuedAt > 20 s`, outcome
`Missed(QUOTA)`; three in 7 days raises the always-on suggestion. **E13**:
`SecurityException` → `DISARMED` + notification, never a crash. **E14**:
`LocationManager.isLocationEnabled` checked on API ≤ 30 at arm time, arm refused
with an actionable message.

**Split by bucket** — this is the §1 rejection made concrete:

| Sub-item | Bucket |
|---|---|
| Receiver → enqueue → expedited worker → `setForeground(connectedDevice)` succeeds, API 31 + 34 (**the ADR-004 claim**) | **CI** (instrumented, synthetic `Intent`) |
| E10 staleness abort, E16 unique-work collapse, E13, E14 | **CI** |
| `ScaleScanner.arm()` registers the `ScanFilter` + `PendingIntent` scan and the OS delivers the broadcast on a real advertisement | **PHONE** |

**Tests:**
- `ScaleSessionWorkerTest.setForegroundSucceedsOnApi31` (instrumented)
- `ScaleSessionWorkerTest.setForegroundSucceedsOnApi34WithConnectedDeviceType` (instrumented)
- `ScaleSessionWorkerTest.abortsWhenEnqueuedMoreThanTwentySecondsAgo` (E10)
- `ScaleSessionWorkerTest.staleAbortNeverOpensGatt` (E10)
- `ScaleSessionWorkerTest.missedQuotaIncrementsCounter` (E10)
- `ScaleSessionWorkerTest.onStoppedClosesGatt` (E15, worker host half)
- `ScanBroadcastReceiverTest.enqueuesUniqueWorkWithKeepPolicy` (E16)
- `ScanBroadcastReceiverTest.secondBroadcastDuringLiveSessionIsNoOp` (E16)
- `ScanBroadcastReceiverTest.returnsWithinReceiverWindow` (asserts no session work
  on the receiver thread — ADR-004's 10 s limit)
- `ScaleScannerTest.armRefusedWhenLocationServicesOffBelowApi31` (E14, Robolectric)
- `ScaleScannerTest.securityExceptionDisarmsWithoutCrashing` (E13)
- `ScaleScannerTest.buildsNeverForLocationFilterOnApi31Plus`
- **PHONE:** `HW-01`, `HW-02` on the §5 checklist.

**Counter:** `missedQuota` (E10).
**Hardware checklist:** HW-01, HW-02, HW-20.

---

### Track C — Decode and measurement

---

#### WP-09 — `BeurerDecoder` frame dispatch + E11 · **B: CI**

**Files:** `ble/decoders/BeurerDecoder.kt` (`onNotification` dispatch, bounds-checked
field extraction), `ble/decoders/FrameBounds.kt`

**Provenance:** `FrameBounds.kt` holds the byte offsets and magic lengths ADR-002
rule 1 covers. It inherits WP-05's `BeurerProtocolTest.everyConstantHasAProvenanceComment`
scanner — this package adds the file to that test's file list in the same PR.

**Does:** opcode dispatch, bounds-checked parsing, `matches()`. **E11**: unknown
opcode → `Ignored`, session continues (forward compatibility); short buffer or
failed field bounds → `Malformed`, `malformedCount++`; at 5, abort with
`DecodeFailure`. **No throwable ever escapes** — §8.9's binder-thread rule.

**Tests:**
- `BeurerDecoderFrameTest.decodesWeightFieldFromCanonicalFrame`
- `BeurerDecoderFrameTest.decodesEveryBodyCompFieldWhenPresent`
- `BeurerDecoderFrameTest.absentBodyCompFieldsDecodeToNull`
- `BeurerDecoderFrameTest.unknownOpcodeIsIgnoredNotMalformed` (E11)
- `BeurerDecoderFrameTest.shortBufferIsMalformedNotAnException` (E11)
- `BeurerDecoderFrameTest.truncatedAtEveryOffsetNeverThrows` (parameterised over
  every prefix length of every corpus frame — §8.9's hard invariant)
- `BeurerDecoderFrameTest.fifthMalformedFrameAbortsSession` (E11)
- `BeurerDecoderFrameTest.malformedEventCarriesOpcodeAndLengthOnly` (§8.8)
- `BeurerDecoderFrameTest.matchesOnAdvertisedNamePrefix`
- `BeurerDecoderFrameTest.doesNotMatchForeignServiceUuid`
- `BeurerDecoderFrameTest.canonicalUnitIsAlwaysKilograms` (§2.7)

**Counter:** `malformedCount` (E11).
**Hardware checklist:** HW-10, HW-11, HW-16.

---

#### WP-10 — Measurement phase: E7, E8, E9 + `EMITTED` persist point · **B: CI**

**Files:** `ble/session/GattSession.kt` (`SUBSCRIBED` → `MEASURING` → `EMITTED`),
`ble/session/SessionOutcome.kt`

**Does:** wires WP-03's detector into the decoder's `Stable` emission. **E7**: no
frame within 45 s → `NoMeasurement`. **E8**: disconnect while `MEASURING` →
partial data **discarded, never persisted**; exactly one reconnect within a 5 s
window. **E9**: in-session latch, at most one emission per `userIndex`, at most 2
distinct indexes. Establishes §2.1's load-bearing rule: **the Room write completes
at `EMITTED`, synchronously, before `disconnect()` is requested.**

**Tests:**
- `GattSessionMeasureTest.noNotificationWithinFortyFiveSecondsYieldsNoMeasurement` (E7)
- `GattSessionMeasureTest.liveFramesAreNeverPersisted` (E8/§2.6 `Live`)
- `GattSessionMeasureTest.disconnectBeforeStabilityDiscardsPartialData` (E8)
- `GattSessionMeasureTest.disconnectBeforeStabilityReconnectsExactlyOnce` (E8)
- `GattSessionMeasureTest.reconnectWindowClosesAfterFiveSeconds` (E8)
- `GattSessionMeasureTest.secondReconnectFailureYieldsMissedDropped` (E8)
- `GattSessionMeasureTest.duplicateStableForSameUserIndexIsLatched` (E9)
- `GattSessionMeasureTest.secondDistinctUserIndexIsEmitted` (E9)
- `GattSessionMeasureTest.thirdDistinctUserIndexIsDropped` (E9)
- `GattSessionMeasureTest.persistCompletesBeforeDisconnectIsRequested` ← §2.1's
  write-ahead rule, asserted on call ordering
- `GattSessionMeasureTest.postEmissionIdleTearsDownAfterTenSeconds`
- `GattSessionMeasureTest.hardCeilingTearsDownAtNinetySeconds`

**Counter:** `duplicateStableSuppressed` (E9).
**Hardware checklist:** HW-07, HW-13, HW-15, HW-20.

---

#### WP-11 — Bonding path: E5, E5b · **B: CI + PHONE**

**Files:** `ble/session/GattSession.kt` (`BONDING`), `ble/session/BondMonitor.kt`

**Does:** **E5**: GATT status 5/15 → `createBond()`, wait ≤30 s for
`BOND_BONDED`, then one full reconnect. **E5b**: `BOND_NONE` or 30 s elapsed →
teardown + persistent pairing notification, not retried automatically. Implements
§2.5's carve-out: the bond wait is **excluded** from the 90 s radio-time ceiling,
and `BONDING` sessions get a 150 s budget.

**Bucket note:** the recovery logic is `CI` against `FakeScaleGatt`. Whether the
BF720 actually demands bonding (assumption A3) and how the real system pairing
dialog interleaves with GATT callbacks is `PHONE`/`SCALE` — see §3.4.

**Tests:**
- `GattSessionBondTest.insufficientAuthenticationTriggersCreateBond` (E5)
- `GattSessionBondTest.bondedStateTriggersExactlyOneReconnect` (E5)
- `GattSessionBondTest.bondTimeoutAtThirtySecondsTearsDown` (E5b)
- `GattSessionBondTest.bondNoneTearsDownWithoutAutomaticRetry` (E5b)
- `SessionBudgetTest.bondWaitIsExcludedFromNinetySecondCeiling` ← self-review
  item 21's defect, now a test
- `SessionBudgetTest.bondingSessionCompletesWithinOneHundredFiftySeconds`
- **PHONE:** HW-06.

**Counter:** none.
**Hardware checklist:** HW-06.

---

#### WP-12 — DEBUG-gated frame capture tool · **B: CI**

**Files:** `app/src/debug/kotlin/.../capture/FrameCaptureSink.kt`,
`app/src/debug/kotlin/.../capture/FramesFileWriter.kt`,
`ble/session/GattSession.kt` (no-op sink injection point)

**Does:** resolves finding **P1-A**. Lives in the `debug` source set, so it is
absent from release by construction rather than by a runtime branch. Writes
full frames to app-private storage in the `.frames` format (§3.2) — directly
ingestible by `ScaleScriptParser`, so a real capture becomes a CI fixture with
no transcription step. Off by default behind a debug-build-only toggle.

**Tests:**
- `FramesFileWriterTest.emitsParsableFramesFormat`
- `FramesFileWriterTest.outputRoundTripsThroughScaleScriptParser` ← the property
  that makes §5's "fix the fake first" mechanical
- `FramesFileWriterTest.writesToAppPrivateStorageOnly`
- `FrameCaptureSinkTest.isDisabledByDefault`
- `ReleaseBuildTest.captureClassesAbsentFromReleaseVariant` (asserts the class is
  not on the release classpath — §8.8's production rule is preserved)

**Counter:** none.
**Blocks:** WP-30, WP-31.

---

### Track D — Persistence

---

#### WP-13 — Room schema · **B: CI**

**Files:** `data/ReadingEntity.kt`, `data/ReadingDao.kt`, `data/BasculeDatabase.kt`,
`data/Converters.kt`, `app/schemas/**/1.json`

**Does:** `00-design.md` §3.1 verbatim, all 18 columns including the ▲ additions
(`retryEpochMillis`, `lastErrorClass`, `contractVersionAtDelivery`,
`remoteDuplicate`, `source`, `displayUnit`). `Set<ReadingField>` ↔ sorted CSV
converter. Schema version 1, exported, no migration.
`fallbackToDestructiveMigration` is **never** enabled (§8.12).

**Tests:**
- `ReadingDaoTest.insertAndReadBackEveryColumn`
- `ReadingDaoTest.drainQuerySelectsOnlyPendingStatus` ← ADR-006's structural
  guarantee: asserts `HELD_CONFIRM`, `DECLINED`, `BLOCKED_AUTH`,
  `FAILED_PERMANENT`, `SENT` rows are all absent from the drain result
- `ReadingDaoTest.historyFlowEmitsOnStatusChange`
- `ConvertersTest.readingFieldSetRoundTripsInSortedOrder`
- `ConvertersTest.emptyFieldSetRoundTrips`
- `BasculeDatabaseTest.schemaVersionOneIsExported`
- `BasculeDatabaseTest.destructiveMigrationIsNotEnabled`
- `MigrationTest.helperOpensVersionOne` (scaffolding for future migrations)

**Counter:** none.

---

#### WP-14 — `DedupPolicy` · **B: CI**

**Files:** `delivery/DedupPolicy.kt`

**Does:** §3.3's four conjunctive rules with the constants as `const val` carrying
their rationale comments. Compares against **all** rows in the window regardless
of status, **except `DECLINED`**.

**Tests:**
- `DedupPolicyTest.weightBoundaryAtExactlyTwoHundredGrams` (0.20 vs 0.21 kg)
- `DedupPolicyTest.timeBoundaryAtExactlyFiveMinutes` (300 000 vs 300 001 ms)
- `DedupPolicyTest.nullUserIndexMatchesNullUserIndex` (§7 Branch B)
- `DedupPolicyTest.differentUserIndexIsNeverADuplicate`
- `DedupPolicyTest.manualNeverDedupsAgainstScale` (`source` clause)
- `DedupPolicyTest.pendingRowIsAValidDuplicateTarget` ← self-review item 11
- `DedupPolicyTest.declinedRowIsExcludedFromCorpus` ← self-review item 23
- `DedupPolicyTest.suppressionIncrementsCounterAndDoesNotInsert`

**Counter:** `duplicatesSuppressed` (§3.3).

---

#### WP-15 — User attribution gate, both branches · **B: CI**

**Files:** `delivery/UserAttributionGate.kt`, `delivery/AttributionBranch.kt`

**Does:** `00-design.md` §7 in full, both branches, selected by one config value
so PRP §8.5 stays genuinely open.

- **Branch A:** index mismatch → dropped **at the persistence boundary**,
  `droppedOtherUser++`, no row written.
- **Branch B:** Δ ≤ 1.5 kg from last confirmed → `PENDING`; Δ > 1.5 kg →
  `HELD_CONFIRM`. First-ever reading, and any reading after a 14-day gap, always
  requires confirmation.

**Tests:**
- `UserAttributionGateTest.branchAMatchingIndexPersistsAsPending`
- `UserAttributionGateTest.branchAMismatchedIndexWritesNoRow` (asserts row count
  unchanged, not merely status)
- `UserAttributionGateTest.branchAMismatchIncrementsDroppedOtherUser`
- `UserAttributionGateTest.branchBWithinBandPersistsAsPending`
- `UserAttributionGateTest.branchBBoundaryAtExactlyFifteenHundredGrams` (1.50 vs 1.51 kg)
- `UserAttributionGateTest.branchBOutsideBandPersistsAsHeldConfirm`
- `UserAttributionGateTest.branchBFirstEverReadingIsAlwaysHeld`
- `UserAttributionGateTest.branchBAfterFourteenDayGapIsAlwaysHeld`
- `UserAttributionGateTest.confirmedRowBecomesTheNewBaseline`
- `UserAttributionGateTest.declinedRowIsNeverTheBaseline`
- `UserAttributionGateTest.manualEntryBypassesTheGate`
- `UserAttributionGateTest.branchSelectionIsConfigOnlyAndChangesNoOtherBehaviour`

**Counter:** `droppedOtherUser` (§7).
**Hardware checklist:** HW-12 — this is the package PRP §8.5 resolves.

---

#### WP-16 — `DeliveryCoordinator`: transitions, error classes, expiry · **B: CI**

**Files:** `delivery/DeliveryCoordinator.kt`, `delivery/ErrorClass.kt`,
`delivery/RetrySchedule.kt`

**Does:** §3.2's transition table and §3.4's schedule. The three error classes
(ADR-005). `retryEpochMillis = now, attemptCount = 0` on **every** entry into
`PENDING`. `BLOCKED_AUTH` and `HELD_CONFIRM` accrue no attempts and run no clock.
`DECLINED` is terminal with no path back.

**Tests:**
- `RetryScheduleTest.backoffLadderIsThirtySecondsToFifteenMinutes`
- `RetryScheduleTest.backoffCapsAtFifteenMinutesForever`
- `RetryScheduleTest.honoursRetryAfterWhenUnderOneHour`
- `RetryScheduleTest.ignoresRetryAfterOverOneHour`
- `DeliveryCoordinatorTest.transientFailureIncrementsAttemptAndStaysPending`
- `DeliveryCoordinatorTest.authRejectionMovesToBlockedAuth`
- `DeliveryCoordinatorTest.permanentRejectionFailsOnFirstAttempt`
- `DeliveryCoordinatorTest.expiryAtFourteenDaysFromRetryEpoch`
- `DeliveryCoordinatorTest.expiryBoundaryAtExactlyFourteenDays`
- `DeliveryCoordinatorTest.blockedAuthAccruesNoAttemptsAndNeverExpires` (§8.6)
- `DeliveryCoordinatorTest.heldConfirmAccruesNoAttemptsAndNeverExpires` (ADR-006)
- `DeliveryCoordinatorTest.newTokenResetsRetryEpochOnEveryBlockedRow` ← self-review
  item 18: the guarantee that a 16-day-blocked backlog is not expired the instant
  it unblocks
- `DeliveryCoordinatorTest.retryTapResetsRetryEpochOnMonthsOldRow` ← item 18
- `DeliveryCoordinatorTest.branchBConfirmationResetsRetryEpoch` ← item 18
- `DeliveryCoordinatorTest.replayRequeueResetsRetryEpoch` ← item 18
- `DeliveryCoordinatorTest.declinedHasNoTransitionBackToPending` (parameterised
  over every trigger — ADR-006's terminality)
- `DeliveryCoordinatorTest.capturedAtIsNeverUsedForExpiry`

**Counter:** none.

---

### Track E — Network

---

#### WP-17 — `VitalForgeApi`, contract versions, shapers · **B: CI**

**Files:** `network/VitalForgeApi.kt`, `network/ReadingPayloadShaper.kt`,
`network/V1Shaper.kt`, `network/V2Shaper.kt`, `network/SubmitResult.kt`

**Does:** §4.3's single versioned interface — one `submitReading`, a swappable
shaper, `deliveredFields` derived from `ShapedPayload.fields` so it cannot drift
from the wire. **V1 sends exactly `{"weight", "unit"}` and no `client_id`**
(§4.4, self-review item 15). V2 field names are `TODO` pinned from VitalForge's
Track A contract doc — not invented; the shaper is written, its exact key strings
are the one thing left blank.

These are the **Phase 2 contract tests** and must be red before Phase 3.

**Tests:** (against MockWebServer)
- `V1ShaperTest.bodyIsExactlyWeightAndUnit` ← asserts *no extra keys*, the
  property whose absence is total data loss under strict Python validation
- `V1ShaperTest.doesNotSendClientId` (§4.4)
- `V1ShaperTest.weightIsConvertedFromCanonicalKgToDisplayUnit`
- `V1ShaperTest.deliveredFieldsIsWeightOnly`
- `V2ShaperTest.includesEveryPopulatedBodyCompField`
- `V2ShaperTest.omitsNullBodyCompFields`
- `V2ShaperTest.includesClientId`
- `VitalForgeApiTest.sendsAuthorizationBearerHeader`
- `VitalForgeApiTest.sendsContentTypeApplicationJson`
- `VitalForgeApiTest.postsToApiWeightPath`
- `VitalForgeApiTest.deliveredFieldsMatchesShaperOutputExactly` ← §4.3's
  anti-drift property
- `VitalForgeApiTest.contractVersionSwitchChangesOnlyTheBody` (same call site,
  both versions)

**Counter:** none.

---

#### WP-18 — `VitalForgeHttpClient` hardening · **B: CI**

**Files:** `network/VitalForgeHttpClient.kt`, `network/ResponseClassifier.kt`

**Does:** §4.5's classification table and §8.7's hardening.
`followRedirects = false`, `followSslRedirects = false`. Body read capped at
64 KiB. Non-JSON on 2xx is success. All parsing inside `runCatching` mapping any
throwable to `TransientFailure`. Timeouts 10/10/15 s. Error strings built from
status code + fixed reason phrase **only** — never from headers or bodies (§8.8).

**Tests:** (MockWebServer)
- `ResponseClassifierTest.everyStatusCodeMapsToTheDocumentedResult` (parameterised
  over the full §4.5 table)
- `VitalForgeHttpClientTest.redirectIsNotFollowed` (asserts the second MockWebServer
  received **zero** requests — token-leak prevention)
- `VitalForgeHttpClientTest.redirectIsClassifiedPermanent`
- `VitalForgeHttpClientTest.oversizedBodyIsCappedAtSixtyFourKilobytes`
- `VitalForgeHttpClientTest.oversizedBodyIsTransientNotACrash`
- `VitalForgeHttpClientTest.nonJsonOnTwoHundredIsAccepted`
- `VitalForgeHttpClientTest.malformedJsonNeverThrows`
- `VitalForgeHttpClientTest.socketHangUpIsTransient`
- `VitalForgeHttpClientTest.errorStringNeverContainsTheToken` ← §8.8
- `VitalForgeHttpClientTest.errorStringNeverContainsResponseBody` ← §8.8
- `VitalForgeHttpClientTest.honoursConnectReadWriteTimeouts`

**Counter:** none.

---

#### WP-19 — `AuthTokenStore` · **B: CI**

**Files:** `network/AuthTokenStore.kt`

**Does:** `EncryptedSharedPreferences` only, per the agent prompt's ground rule.
Never returns the token for display — exposes `isSet(): Boolean` for the UI.

**Tests:** (instrumented — `EncryptedSharedPreferences` needs a real keystore)
- `AuthTokenStoreTest.storesAndRetrievesToken`
- `AuthTokenStoreTest.underlyingPrefsFileDoesNotContainPlaintextToken` ← the test
  that actually proves the ground rule, by reading the file bytes
- `AuthTokenStoreTest.isSetReportsPresenceWithoutReturningValue`
- `AuthTokenStoreTest.clearRemovesToken`
- `AuthTokenStoreTest.toStringNeverContainsTheToken`

**Counter:** none.

---

#### WP-20 — `recentReadings` contention check · **B: CI**

**Files:** `network/VitalForgeApi.kt` (`recentReadings`),
`delivery/RemoteDuplicateCheck.kt`

**Does:** ADR-003 step 2 and step 3. Same constants as §3.3 (±0.20 kg / 5 min).
On a match: row → `SENT`, `deliveredFields = ∅`, `remoteDuplicate = true`. On
endpoint absent or call failure: **post anyway** — a failed dedup check never
blocks a delivery.

**Tests:**
- `RemoteDuplicateCheckTest.matchWithinToleranceMarksRemoteDuplicate`
- `RemoteDuplicateCheckTest.remoteDuplicateRowHasEmptyDeliveredFields`
- `RemoteDuplicateCheckTest.outsideToleranceProceedsToPost`
- `RemoteDuplicateCheckTest.endpointFourOhFourProceedsToPost` (ADR-003 step 3)
- `RemoteDuplicateCheckTest.endpointTimeoutProceedsToPost` ← "losing a reading is
  worse than a duplicate"
- `RemoteDuplicateCheckTest.usesSameConstantsAsDedupPolicy` (asserts identity with
  `DedupPolicy`'s `const val`s, so the two cannot drift)

**Counter:** `remoteDuplicatesSuppressed`.
**Hardware checklist:** HW-18.

---

### Track F — Drain

---

#### WP-21 — `DeliveryWorker` + triggers · **B: CI**

**Files:** `delivery/DeliveryWorker.kt`, `delivery/DeliveryScheduler.kt`

**Does:** §3.4's triggers — expedited one-shot on insert, periodic every 15 min,
plus immediate drain on connectivity return, app foreground, and new token saved.
Unique work `delivery-drain` with `ExistingWorkPolicy.KEEP`. Global drain pause
on `BLOCKED_AUTH` (not per-row). Per-row `lastAttemptMillis` claim so a crash
mid-POST strands nothing (§3.2's `IN_FLIGHT` rationale).

**Tests:** (`WorkManagerTestInitHelper` + `TestListenableWorkerBuilder`)
- `DeliveryWorkerTest.drainsOnlyPendingRows`
- `DeliveryWorkerTest.respectsPerRowBackoffSchedule`
- `DeliveryWorkerTest.networkConstraintIsAttached`
- `DeliveryWorkerTest.authRejectionPausesDrainGlobally`
- `DeliveryWorkerTest.crashMidPostLeavesRowDrainableNotStranded` ← §3.2's reason
  for having no `IN_FLIGHT` state
- `DeliverySchedulerTest.insertEnqueuesExpeditedOneShot`
- `DeliverySchedulerTest.periodicIsFifteenMinutes`
- `DeliverySchedulerTest.newTokenTriggersImmediateDrain`
- `DeliverySchedulerTest.connectivityReturnTriggersDrain`
- `DeliverySchedulerTest.uniqueWorkPreventsConcurrentDrains`

**Counter:** none.

---

#### WP-22 — Replay migration worker (contract v2) · **B: CI**

**Files:** `delivery/ReplayMigrationWorker.kt`, `delivery/ReplayEligibility.kt`

**Does:** §4.4's two-clause eligibility. **Ships disabled**: the escalation to JD
on v2 `client_id` idempotency (A6) is unresolved, so the worker is written,
tested, and gated off. Enabling it is a Phase-5+ decision, not a merge decision.

**Tests:**
- `ReplayEligibilityTest.undeliveredPopulatedFieldMakesRowEligible`
- `ReplayEligibilityTest.fullyDeliveredRowIsNotEligible`
- `ReplayEligibilityTest.remoteDuplicateRowIsNeverEligible` ← self-review item 20:
  the clause without which the v2 upgrade bulk-injects every Atlas-won reading
- `ReplayEligibilityTest.emptyDeliveredFieldsAloneDoesNotImplyEligible`
- `ReplayMigrationWorkerTest.requeuedRowResetsRetryEpochAndAttemptCount`
- `ReplayMigrationWorkerTest.isDisabledUnderContractV1`
- `ReplayMigrationWorkerTest.isDisabledPendingIdempotencyEscalation`
- `ReplayMigrationWorkerTest.runsAtMostOncePerContractVersionChange`

**Counter:** none.
**Escalation:** A6, §4.4 — blocked on JD.

---

### Track G — UI and platform surfaces

---

#### WP-23 — `HistoryScreen` · **B: CI**

**Files:** `ui/HistoryScreen.kt`, `ui/HistoryViewModel.kt`

**Does:** §5's table. All six statuses. `HELD_CONFIRM` ranked top, then
`BLOCKED_AUTH` / `FAILED_PERMANENT` with explanatory banners, then `SENT`.
**No action offered on a `DECLINED` row** (ADR-006). Surfaces the counter set.

**Tests:**
- `HistoryViewModelTest.heldConfirmRowsRankAboveAllOthers`
- `HistoryViewModelTest.sentRowsRankLast`
- `HistoryViewModelTest.sortsByCapturedAtWithinAStatusGroup`
- `HistoryScreenTest.declinedRowOffersNoRetryAffordance` ← ADR-006/self-review
  item 23: the one-tap path that would defeat the entire Branch B hold
- `HistoryScreenTest.failedPermanentRowOffersRetry`
- `HistoryScreenTest.heldConfirmRowOffersYesThatsMeAndNotMe`
- `HistoryScreenTest.confirmTransitionsRowToPending`
- `HistoryScreenTest.declineTransitionsRowToDeclined`
- `HistoryScreenTest.blockedAuthBannerIsShownWhenAnyRowIsBlocked`
- `HistoryScreenTest.showsPendingBacklogAge` (§8.5 — outage visible, not inferred)
- `HistoryScreenTest.showsDiagnosticsCounters`

**Counter:** consumes all.

---

#### WP-24 — `ManualEntryScreen` · **B: CI**

**Files:** `ui/ManualEntryScreen.kt`, `ui/ManualEntryViewModel.kt`

**Does:** inserts `source = MANUAL`, `PENDING`, body-comp fields null. Bypasses
the attribution gate (§7 — a manual entry is attributed by construction).

**Tests:**
- `ManualEntryViewModelTest.insertsWithSourceManual`
- `ManualEntryViewModelTest.insertsAsPendingNotHeldConfirm`
- `ManualEntryViewModelTest.bodyCompFieldsAreNull`
- `ManualEntryViewModelTest.convertsDisplayUnitToCanonicalKg`
- `ManualEntryViewModelTest.rejectsNonNumericInput`
- `ManualEntryViewModelTest.rejectsImplausibleWeightAtBoundaries`
- `ManualEntryViewModelTest.doesNotDedupAgainstScaleRows` (§3.3 `source` clause)

**Counter:** none.

---

#### WP-25 — `ConfigScreen` + permission flow · **B: CI + PHONE**

**Files:** `ui/ConfigScreen.kt`, `ui/ConfigViewModel.kt`, `ui/PermissionRequester.kt`,
`data/ConfigStore.kt`

**Does:** §5's config surface plus §6.3's SDK-branched runtime request flow.
`ACCESS_BACKGROUND_LOCATION` requested in a **second** dialog after fine location
(API 29/30 platform rule). Rationale copy on API ≤ 30 explaining why a scale app
asks for location (§6.3). Base URL validated at save (scheme, parseable host).
Token field renders "set" / "not set", never the value.

**Tests:**
- `ConfigViewModelTest.baseUrlRejectsNonHttpScheme`
- `ConfigViewModelTest.baseUrlRejectsUnparseableHost`
- `ConfigViewModelTest.tokenIsNeverExposedForDisplay`
- `ConfigViewModelTest.savingTokenTriggersImmediateDrain` (§8.6)
- `PermissionRequesterTest.requestsScanAndConnectOnApi31Plus` (Robolectric, API 31)
- `PermissionRequesterTest.requestsFineLocationBelowApi31` (Robolectric, API 30)
- `PermissionRequesterTest.requestsBackgroundLocationInASecondDialog` (API 30)
- `PermissionRequesterTest.requestsPostNotificationsOnApi33Plus`
- `PermissionRequesterTest.neverRequestsBothLocationPermissionsAtOnce` ← §6.3's
  platform rule; requesting both together is denied outright
- `ConfigScreenTest.showsLocationRationaleBelowApi31`
- **PHONE:** HW-19 (real grant/revoke round trip).

**Counter:** none.

---

#### WP-26 — Notifications + persistent diagnostics · **B: CI**

**Files:** `ui/NotificationSurface.kt`, `diagnostics/PersistentDiagnosticsCounters.kt`

**Does:** the persistent `DiagnosticsCounters` implementation behind WP-02's
interface, plus every notification the design owes: `BLOCKED_AUTH` persistent,
`HELD_CONFIRM` actionable ("Yes, that's me" / "Not me"), E5b pairing prompt, E10
three-in-seven-days always-on suggestion, E4 `incompatibleStreak` suspension
notice.

This is the package that makes §2.1's counters real rather than scattered.

**Tests:**
- `PersistentDiagnosticsCountersTest.survivesProcessRestart`
- `PersistentDiagnosticsCountersTest.everyCounterKeyIsOwnedByExactlyOnePackage`
  (asserts the §2.1 registry matches the enum — catches a counter added without
  an owner)
- `NotificationSurfaceTest.blockedAuthNotificationIsOngoing`
- `NotificationSurfaceTest.heldConfirmNotificationCarriesBothActions`
- `NotificationSurfaceTest.heldConfirmConfirmActionTransitionsToPending`
- `NotificationSurfaceTest.heldConfirmDeclineActionTransitionsToDeclined`
- `NotificationSurfaceTest.threeMissedQuotaInSevenDaysNotifiesOnce` (E10)
- `NotificationSurfaceTest.fourthMissedQuotaDoesNotRenotify` (E10)
- `NotificationSurfaceTest.bondFailureShowsPairingInstruction` (E5b)
- `NotificationSurfaceTest.incompatibleStreakShowsUnrecognisedScaleMessage` (E4)
- `NotificationSurfaceTest.noNotificationTextContainsAWeightValue` (§8.8 — body
  composition is not lock-screen content)

**Counter:** owns the persistent implementation.

---

#### WP-27 — `BootReceiver` + always-on mode · **B: CI + PHONE**

**Files:** `service/BootReceiver.kt`, `service/BridgeForegroundService.kt`

**Does:** §8.2 — re-arm the `PendingIntent` scan after reboot, since scan
registrations do not survive it. `BridgeForegroundService` is the opt-in
always-on mode (§2.2), off by default, started from the UI where a foreground
start is legal.

**Tests:**
- `BootReceiverTest.reArmsScanOnBootCompleted`
- `BootReceiverTest.doesNotArmWhenBridgingIsDisabled`
- `BootReceiverTest.doesNotArmWhenPermissionsAreMissing`
- `BridgeForegroundServiceTest.startsWithConnectedDeviceType`
- `BridgeForegroundServiceTest.isOffByDefault`
- `BridgeForegroundServiceTest.stopsScanOnDestroy`
- `BridgeForegroundServiceTest.usesLowPowerScanMode` (§8.11)
- **PHONE:** HW-21 (real reboot → scan re-armed → advertisement still wakes).

**Counter:** none.

---

### Track H — Integration, then hardware

---

#### WP-28 — End-to-end scenario tests · **B: CI**

**Files:** `app/src/androidTest/kotlin/.../scenario/*.kt`

**Does:** the Phase 4 item-1 scenarios, across module boundaries, against
`FakeScaleGatt` + MockWebServer.

**Tests:**
- `ColdStartScenarioTest.fakeMeasurementPersistsDeliversAndAppearsInHistory`
- `ProcessDeathScenarioTest.killBetweenPersistAndDeliverDrainsOnRestart` (§8.1, §8.2)
- `TokenRotationScenarioTest.invalidTokenSurfacesBlockedAuthWithoutInfiniteRetry` (§8.6)
- `TokenRotationScenarioTest.newTokenDeliversTheEntireBacklog` (§8.6)
- `SecondUserScenarioTest.branchAReadingIsDroppedAndCountedNotDelivered` (§7)
- `SecondUserScenarioTest.branchBReadingIsHeldAndNotDelivered` (§7)
- `OutageScenarioTest.sevenDayOutageDeliversEverythingOnRecovery` (§8.5)
- `OutageScenarioTest.fifteenDayOutageMarksFailedPermanentNotSilentLoss` (§3.4)
- `ContentionScenarioTest.remoteDuplicateIsSuppressedAndNotReplayed` (ADR-003 + §4.4)

**Counter:** none.

---

#### WP-29 — Phone-only validation pass · **B: PHONE**

**Files:** `docs/prp/03-hardware-validation.md` (part 1 — phone section)

**Does:** executes every `PHONE`-bucket checklist row in §5 on any Android device
with a BLE radio, **before the BF720 arrives**. Uses a generic BLE peripheral (a
second phone in peripheral mode, or any advertising device) to exercise scan
registration, PendingIntent delivery, and real GATT status codes.

**Tests:** HW-01(partial), HW-02(partial), HW-17, HW-19, HW-21 — see §5.

**Counter:** none.
**Unblocks:** as soon as any Android phone is available.

---

#### WP-30 — Hardware session: constants, PRP §8.5, checklist · **B: SCALE**

**Files:** `ble/decoders/BeurerProtocol.kt` (values confirmed/corrected +
confirmation dates), `docs/prp/constants-provenance.md`,
`docs/prp/03-hardware-validation.md`

**Does:** milestone 1. Runs the full §5 checklist against the physical BF720 using
WP-12's capture tool. Every constant gets a confirmation date appended per ADR-002
rule 2, or is corrected. **Resolves PRP §8.5 with evidence from live payloads** and
sets the `AttributionBranch` config accordingly.

Because everything sits behind `ScaleDecoder` (§2.6), the code change here is
expected to be **a data-only edit to `BeurerProtocol.kt`** plus one strategy flag
in WP-03's detector. If it is not — if the real protocol needs structural change —
that is itself a P1 finding and an ADR.

**Tests:** the §5 checklist is the test. Every row records observed / expected /
verdict / the fake-layer test it validates.

**Counter:** none.
**Escalation trigger:** if the BF720 diverges from openScale's documentation badly
enough to need vendor-app traffic capture, stop and ask JD (agent prompt
§Escalation).

---

#### WP-31 — Fixture reconciliation (P1 findings) · **B: CI, after SCALE**

**Files:** `app/src/test/resources/fixtures/beurer/*.scale` (replaced by real
captures), `docs/prp/03-hardware-validation.md` (divergence log)

**Does:** the Phase 3 protocol's load-bearing step — **fix the fake to match
reality first, then the code**, so CI keeps guarding the true protocol. WP-12's
`.frames` captures are dropped in and the synthesized corpus is retired or
annotated. Every divergence between a fake-layer assumption and real-device
behavior is logged as a P1 finding with the checklist row that caught it.

**Tests:**
- The entire existing suite, re-run against real-capture fixtures. Any test that
  now fails is a P1 finding, not a test to relax.
- `FixtureCorpusTest.everyScenarioHasARealCaptureOrADocumentedReasonWhyNot` ←
  prevents a synthesized fixture surviving into Phase 5 unnoticed

**Counter:** none.

---

### 2.1 Diagnostics counter registry

Counters are referenced across E4, E9, E10, E11, §3.3, §7 and rendered in
HistoryScreen. Each has exactly one owning package; `WP-26` provides the
persistent implementation behind `WP-02`'s interface, and
`PersistentDiagnosticsCountersTest.everyCounterKeyIsOwnedByExactlyOnePackage`
enforces this table mechanically.

| Counter | Owner | Source | Incremented-test |
|---|---|---|---|
| `incompatibleStreak` | WP-06 | E4 | `GattSessionDiscoveryTest.thirdConsecutiveIncompatibleSuspendsArming` |
| `missedQuota` | WP-08 | E10 | `ScaleSessionWorkerTest.missedQuotaIncrementsCounter` |
| `malformedCount` | WP-09 | E11 | `BeurerDecoderFrameTest.fifthMalformedFrameAbortsSession` |
| `duplicateStableSuppressed` | WP-10 | E9 | `GattSessionMeasureTest.duplicateStableForSameUserIndexIsLatched` |
| `duplicatesSuppressed` | WP-14 | §3.3 | `DedupPolicyTest.suppressionIncrementsCounterAndDoesNotInsert` |
| `droppedOtherUser` | WP-15 | §7 | `UserAttributionGateTest.branchAMismatchIncrementsDroppedOtherUser` |
| `remoteDuplicatesSuppressed` | WP-20 | ADR-003 | `RemoteDuplicateCheckTest.matchWithinToleranceMarksRemoteDuplicate` |

---

## 3. `FakeScaleGatt` design

Detailed to hand-off level. Implemented in WP-04; corpus in WP-05.

### 3.1 The plug-in point: `GattTransport`

`00-design.md` §1.1 names `GattTransport.kt` as "interface over `BluetoothGatt` —
faked in tests" but does not define it. Defined here.

```kotlin
interface GattTransport {
    val events: SharedFlow<TransportEvent>

    fun connect()
    fun discoverServices()
    fun write(char: UUID, bytes: ByteArray)
    fun enableNotifications(char: UUID)
    fun requestMtu(mtu: Int)
    fun createBond()
    fun disconnect()
    fun close()
}

sealed interface TransportEvent {
    data class ConnectionStateChanged(val connected: Boolean, val status: Int) : TransportEvent
    data class ServicesDiscovered(val services: DiscoveredServices, val status: Int) : TransportEvent
    data class CharacteristicChanged(val char: UUID, val value: ByteArray) : TransportEvent
    data class WriteComplete(val char: UUID, val status: Int) : TransportEvent
    data class NotificationsEnabled(val char: UUID, val status: Int) : TransportEvent
    data class MtuChanged(val mtu: Int, val status: Int) : TransportEvent
    data class BondStateChanged(val state: Int) : TransportEvent
    data object AdapterOff : TransportEvent
}
```

Two implementations:

- **`AndroidGattTransport`** (`main`) — wraps `BluetoothGatt` +
  `BluetoothGattCallback`. Carries **no logic**: it translates callbacks into
  `TransportEvent` and forwards calls. This is deliberate — anything it decided
  for itself would be untested when `FakeScaleGatt` substitutes for it.
- **`FakeScaleGatt`** (`test`) — script-driven, virtual-clock.

`GattSession` depends only on the interface, so **every** state transition and
every failure edge in §2.3 that originates from the radio is reproducible in a
JVM unit test with no Android and no BLE stack.

### 3.2 Fixture formats

Two formats, one parser. This matters: it is what makes "fix the fake first"
(Phase 3, §5) a file-drop rather than a transcription exercise.

**`.frames` — raw capture.** Exactly what WP-12's capture tool emits from a real
BF720. Relative timestamps, one frame per line.

```
# BF720 capture — 2026-__-__, session 3, subject JD, scale unit kg
# tool: FrameCaptureSink (debug build only)
+0000 NOTIFY e7 00 01 02
+0412 NOTIFY e7 0a 01 ...
+0838 NOTIFY e7 0a 01 ...
```

**`.scale` — full session script.** Wraps frames with connect/discovery/write
behavior so a whole session, including failure edges, is one file.

```
# fixtures/beurer/happy_path.scale
# Synthesized from BeurerProtocol (UNCONFIRMED — pending live scan).
@connect         delay=120ms status=0 state=CONNECTED
@discover        delay=80ms  status=0 services=BEURER_SERVICE:[NOTIFY_CHAR,WRITE_CHAR]
@onWrite         char=WRITE_CHAR match=INIT_SEQ[0] -> after=40ms notify=NOTIFY_CHAR:e7000102
@onEnableNotify  char=NOTIFY_CHAR after=20ms status=0
@frame  +1200ms  NOTIFY_CHAR e70a01...     # live 78.1 kg
@frame  +0420ms  NOTIFY_CHAR e70a01...     # live 78.4 kg
@frame  +0430ms  NOTIFY_CHAR e70b01...     # final 78.4 kg, stability flag set
@disconnect      delay=200ms status=0 initiator=REMOTE
```

`ScaleScriptParser` reads both; a `.frames` file is lifted into a default
`.scale` by prepending a standard successful connect/discover/handshake preamble.
`ScaleScriptParserTest.roundTripsRawFramesFileIntoScript` and
`FramesFileWriterTest.outputRoundTripsThroughScaleScriptParser` are the two tests
that keep this property true.

Symbolic names (`BEURER_SERVICE`, `INIT_SEQ[0]`) resolve against
`BeurerProtocol.kt`, so **when WP-30 corrects a constant, every fixture follows
automatically** rather than needing hand-editing. This is the single most
important property of the format under a no-hardware constraint.

### 3.3 Virtual clock

`FakeScaleGatt` runs on `kotlinx-coroutines-test`'s `TestScope` /
`TestCoroutineScheduler`. All `delay=` values are virtual. Consequence: the 45 s
first-notification timeout (E7), the 90 s hard ceiling, the 30 s bond wait (E5),
and the 14-day delivery expiry are all exercised in **milliseconds of wall
clock**. No test in the suite sleeps. `SessionBudget` reads time through an
injected `TimeSource` for the same reason.

### 3.4 Scenario corpus

The five scenarios the lead named as the minimum, plus full E-edge coverage.

| Fixture | Drives | Package |
|---|---|---|
| `happy_path.scale` | connect → handshake → live frames → stable → emit | WP-07, WP-10 |
| `unstable_then_stable.scale` | 8 drifting live frames, then 4 quiescent within ±0.1 kg over 2.1 s | WP-03, WP-10 |
| `unstable_then_stable_flagged.scale` | same, final frame carries the stability flag (flag-path variant) | WP-03 |
| `disconnect_mid_stream.scale` | **E8** — 3 live frames then `DISCONNECTED` | WP-10 |
| `disconnect_mid_stream_recovers.scale` | E8 with a successful reconnect inside 5 s | WP-10 |
| `second_user_index.scale` | **E9** — stable for index 1, then stable for index 3 | WP-10, WP-15 |
| `unknown_user_index.scale` | stable for an index the user has not configured (Branch A drop) | WP-15 |
| `malformed_frame.scale` | **E11** — short buffer, then a valid frame | WP-09 |
| `unknown_opcode.scale` | E11 forward-compat: unknown opcode, session continues | WP-09 |
| `five_malformed.scale` | E11 abort threshold | WP-09 |
| `connect_timeout.scale` | **E1** — no `CONNECTED` event ever | WP-06 |
| `gatt_133.scale` | **E2** — `status=133` on connect, twice, then success | WP-06 |
| `device_busy.scale` | **E3** — connect then immediate drop, status 19 | WP-06 |
| `discovery_timeout.scale` | **E4** — no `ServicesDiscovered` | WP-06 |
| `service_missing.scale` | E4 — discovery succeeds, required service absent | WP-06 |
| `insufficient_auth.scale` | **E5** — status 5 on write, bond, reconnect, succeed | WP-11 |
| `bond_fails.scale` | **E5b** — `BOND_NONE` after `createBond()` | WP-11 |
| `no_init_ack.scale` | **E6** — init write accepted, no ack notification | WP-07 |
| `late_init_ack.scale` | E6 — ack arrives after the first re-issue | WP-07 |
| `no_notification.scale` | **E7** — subscribe succeeds, no frame ever | WP-10 |
| `duplicate_stable.scale` | **E9** — final frame repeated 4× (real scales do this) | WP-10 |
| `adapter_off_mid_session.scale` | **E12** — `AdapterOff` during `MEASURING` | WP-06 |

### 3.5 Fault-injection knobs

Beyond scripts, for parameterised tests: `connectStatus`, `connectDelay`,
`discoverStatus`, `writeStatus`, `notifyEnableStatus`, `mtuStatus`,
`dropAfterNFrames`, `emitAdapterOffAt`, `bondOutcome`. Plus assertion surface:
`closeCallCount`, `connectCallCount`, `writesPerformed: List<Pair<UUID, ByteArray>>`,
`subscribedCharacteristics`. `closeCallCount` is what makes §8.10's
"every terminal path calls `gatt.close()` exactly once" a real test
(`GattSessionTeardownTest.everyTerminalPathClosesGattExactlyOnce`) rather than a
claim.

### 3.6 Which edges the fake cannot fully drive — stated, not hidden

The lead asked which edges can only be approximated in the fake layer and why.
Three categories.

**(a) Fully driven by the fake — recovery *and* trigger.** E1, E4, E6, E7, E8,
E9, E11. These originate from absence (a timeout) or from bytes, both of which
the fake reproduces exactly. Nothing about them is approximate.

**(b) Recovery fully driven; *trigger fidelity* approximated.** The fake injects
the status code, but *when the real Android stack emits it* is not something a
fake can be faithful about.

| Edge | What is exact | What is approximated | Checklist row |
|---|---|---|---|
| **E2** (status 133) | The close-before-retry discipline and the 500 ms/1 s/2 s ladder | 133 is Android's catch-all GATT error. Its real-world *frequency and triggers* on this phone/scale pair are unknown; we test that we recover, not that we predicted when | HW-17 |
| **E3** (contention) | One retry at 2 s, then yield | Real Atlas contention may present as status 8, 19, 22, a silent connect failure, or a successful connect that starves. The fake covers the documented codes; the real presentation is unverified | HW-18 |
| **E5 / E5b** (bonding) | Bond state transitions and the 150 s budget carve-out | `createBond()` raises a **system pairing dialog**. Real user-interaction timing, dialog dismissal, and how bond callbacks interleave with in-flight GATT operations are not reproducible in a fake | HW-06 |
| **E12** (adapter off) | Immediate teardown, no retry, re-arm on `STATE_ON` | The ordering between `ACTION_STATE_CHANGED` and the GATT status 8/22 the stack emits is stack-specific and racy on real hardware | HW-22 |

**(c) Not a transport concern at all — CI-verified at a different layer.** These
never reach `GattTransport`, so the fake is simply the wrong tool; they are still
fully CI-verified.

| Edge | Verified by |
|---|---|
| **E10** (expedited quota) | `ScaleSessionWorkerTest.abortsWhenEnqueuedMoreThanTwentySecondsAgo` — injected `enqueuedAt`. *Approximation note:* real quota exhaustion cannot be forced on demand; we test the abort branch, not the platform's quota accounting |
| **E13** (permission revoked) | `ScaleScannerTest.securityExceptionDisarmsWithoutCrashing`. *Approximation note:* on several Android versions revoking a runtime permission kills the app process outright, so the in-process `SecurityException` path is the milder of the two real behaviours → HW-19 |
| **E14** (location services off) | `ScaleScannerTest.armRefusedWhenLocationServicesOffBelowApi31` (Robolectric shadow) |
| **E15** (worker killed) | `ScaleSessionWorkerTest.onStoppedClosesGatt` + `GattSessionTeardownTest.cancellationClosesGattExactlyOnce` |
| **E16** (overlapping sessions) | `ScanBroadcastReceiverTest.secondBroadcastDuringLiveSessionIsNoOp` (WorkManager test harness) |

**Not an edge, but the largest fake-layer blind spot:** assumption **A1** — that
the BF720 advertises connectably on step-on without prior app interaction. No
fake can test this; the fake *presupposes* it. It is HW-01, it is the first row
of the checklist, and if it fails the wake path is replaced by A1's ranked
fallbacks (`00-design.md` §10).

---

## 4. Bucket summary

### 4.1 Counts

| Bucket | Packages | Notes |
|---|---|---|
| **CI** | 25 | WP-01…07, 09, 10, 12…24, 26, 28, 31 |
| **CI + PHONE** | 4 | WP-08, 11, 25, 27 — CI half merges now, PHONE half is a checklist row |
| **PHONE only** | 1 | WP-29 |
| **SCALE only** | 1 | WP-30 |
| **Total** | **31** | |

**Twenty-eight of thirty-one packages reach a green, reviewed, mergeable state
with no hardware of any kind.** WP-29 needs any Android phone. Only WP-30 needs
the BF720, and its expected diff is data-only.

### 4.2 The `PHONE` set, isolated

Five checklist rows and one package. All unblock with any Android device:

- HW-01/HW-02 (partial) — scan registration + PendingIntent delivery (WP-08)
- HW-17 — real GATT status-code behavior (WP-06/WP-11)
- HW-19 — permission grant/revoke round trip (WP-25)
- HW-21 — reboot → re-arm (WP-27)
- HW-22 — adapter-off event ordering (WP-06)

### 4.3 Merge order and parallelism

Strictly ordered: WP-01 → WP-02 → **WP-03** → WP-04 → WP-05 → WP-06 → **WP-07**
→ **WP-08**.

After WP-08 the graph opens up. Independent lanes that can proceed in parallel:

- **Decode lane:** WP-09 → WP-10 → WP-11 → WP-12
- **Persistence lane:** WP-13 → WP-14 → WP-15 → WP-16
- **Network lane:** WP-17 → WP-18 → WP-19 → WP-20

Then: WP-21 → WP-22 (needs persistence + network). Then WP-23…27 (needs all
three). Then WP-28. Then WP-29 (phone), WP-30 (scale), WP-31.

---

## 5. Phase-3 hardware checklist

The Phase 3 exit gate requires this executed with results in
`docs/prp/03-hardware-validation.md`. Every row names the fake-layer test that
**claims** to cover it, so a mismatch is immediately visible and becomes a P1
finding.

**Protocol on a mismatch (agent prompt, Phase 3):** fix the **fixture** first so
CI guards the true protocol, then fix the code. Never relax the test. Log the
divergence in `03-hardware-validation.md` and, if it is structural rather than a
constant value, raise an ADR.

Columns: **B** = bucket. **Claimed by** = the fake-layer test asserted to cover
this behavior.

| # | B | Live behavior to verify | Pass criterion | Claimed by |
|---|---|---|---|---|
| **HW-01** | PHONE→SCALE | BF720 advertises connectably on step-on, no prior app interaction (**A1**) | Advertisement observed within 3 s of step-on; connect succeeds | *Nothing — the fake presupposes it.* This is the plan's largest blind spot (§3.6) |
| **HW-02** | PHONE→SCALE | Advertised name / service UUID is stable enough for a `ScanFilter` (**A2**) | Same name prefix and service UUID across 10 consecutive weigh-ins | `BeurerDecoderFrameTest.matchesOnAdvertisedNamePrefix`, `ScaleScannerTest.buildsNeverForLocationFilterOnApi31Plus` |
| **HW-03** | SCALE | `BEURER_SERVICE_UUID`, notify char, write char present after discovery | All three found; `constants-provenance.md` dated | `BeurerProtocolTest.uuidsAreDistinct`, `GattSessionDiscoveryTest.missingRequiredServiceYieldsIncompatible` |
| **HW-04** | SCALE | `INIT_SEQUENCE` accepted in the written order | Ack notification received | `BeurerDecoderInitTest.initSequenceOrderIsDeterministic`, `BeurerDecoderInitTest.ackFrameProducesInitAcknowledged` |
| **HW-05** | SCALE | Init ack arrives within the 3 s E6 timeout | Observed ack latency < 3 s across 5 sessions | `GattSessionHandshakeTest.missingAckReissuesWriteAfterThreeSeconds` |
| **HW-06** | PHONE→SCALE | No bonding required (**A3**); if required, E5 path completes | Either no status 5/15, or bond → reconnect → measurement inside 150 s | `GattSessionBondTest.*`, `SessionBudgetTest.bondingSessionCompletesWithinOneHundredFiftySeconds` |
| **HW-07** | SCALE | First measurement notification arrives well inside 45 s (E7) | Observed latency < 20 s across 5 sessions | `GattSessionMeasureTest.noNotificationWithinFortyFiveSecondsYieldsNoMeasurement` |
| **HW-08** | SCALE | **Does a stability/final flag exist in the frame?** (§2.4 branch) | Live and final frames distinguishable by a flag bit, or definitively not | `StabilityDetectorTest.flagPathEmitsStableOnFirstFinalFrame` vs `...quiescenceRequiresFourConsecutiveSamples` — **exactly one of these two paths is validated here; the other stays as the guard** |
| **HW-09** | SCALE | If flagless: real frames satisfy ±0.1 kg / ≥4 frames / ≥2.0 s | Quiescence detected within 5 s of the display settling, no false early emit | `StabilityDetectorTest.quiescenceBoundaryAtExactly100Grams`, `...quiescenceRequiresTwoFullSeconds`, `...stepOnRampNeverSatisfiesQuiescence` |
| **HW-10** | SCALE | `WEIGHT_SCALE_FACTOR` decodes to the scale's own display value | Decoded kg matches display to ±0.1 kg across 10 readings at different weights | `BeurerDecoderFrameTest.decodesWeightFieldFromCanonicalFrame` — **a guessed scale factor produces plausible wrong weights no test catches (ADR-002 rule 3); this row is the only thing that catches it** |
| **HW-11** | SCALE | Body-comp fields present and their scale factors correct | Fat/water/muscle/bone/BMI/BMR/AMR within plausible physiological ranges and matching the Beurer app if available | `BeurerDecoderFrameTest.decodesEveryBodyCompFieldWhenPresent`, `...absentBodyCompFieldsDecodeToNull` |
| **HW-12** | SCALE | **Does the payload expose a user index? (PRP §8.5)** | Definitive yes/no from live payloads across ≥2 profiles; sets `AttributionBranch` | `UserAttributionGateTest.branchAMatchingIndexPersistsAsPending` (A) vs `...branchBBoundaryAtExactlyFifteenHundredGrams` (B) — **the Phase 3 exit gate names this row explicitly** |
| **HW-13** | SCALE | Final frame is repeated (most BLE scales do) — E9 latch fires | Duplicate finals observed and suppressed; exactly one Room row | `GattSessionMeasureTest.duplicateStableForSameUserIndexIsLatched`, `DedupPolicyTest.weightBoundaryAtExactlyTwoHundredGrams` |
| **HW-14** | SCALE | Scale stays connectable ≥15 s after step-on (**A4**) | Connect at T+10 s succeeds across 5 attempts | `SessionBudgetTest.connectLadderFitsWithinConnectPhaseBudget` — the 20 s budget is derived from A4; if A4 is false the ladder must shorten |
| **HW-15** | SCALE | Does the scale signal end-of-transmission, or just drop? | `SessionComplete` opcode observed, or confirmed absent (then the 10 s idle teardown is the only exit) | `GattSessionMeasureTest.postEmissionIdleTearsDownAfterTenSeconds` |
| **HW-16** | SCALE | Switching the scale to lb/st does **not** change the wire format | Same opcodes and same raw values at both display units | `BeurerDecoderFrameTest.canonicalUnitIsAlwaysKilograms` — §2.7's canonical-kg invariant depends on this |
| **HW-17** | PHONE | Real-world status-133 frequency and trigger conditions | Recorded across ≥20 connects; ladder confirmed adequate | `GattSessionConnectTest.status133ClosesGattBeforeRetrying`, `...status133RetriesAtFiveHundredOneAndTwoSeconds` (§3.6b — trigger fidelity approximated) |
| **HW-18** | SCALE | Atlas contention presents as a code E3 actually handles | With `ble-scale-sync` connected, Bascule's failure is one of status 8/19/22 or a clean connect failure | `GattSessionConnectTest.busyStatusYieldsAfterOneRetry`, `RemoteDuplicateCheckTest.matchWithinToleranceMarksRemoteDuplicate` (§3.6b) |
| **HW-19** | PHONE | Permission grant/revoke round trip; revoke-while-armed behavior | Revoke either raises `SecurityException` (handled) or kills the process (acceptable); never a visible crash | `ScaleScannerTest.securityExceptionDisarmsWithoutCrashing`, `PermissionRequesterTest.neverRequestsBothLocationPermissionsAtOnce` (§3.6c) |
| **HW-20** | SCALE | A full real session completes inside the 90 s ceiling | Median session < 45 s; no session hits the ceiling | `GattSessionMeasureTest.hardCeilingTearsDownAtNinetySeconds`, `SessionBudgetTest.hardCeilingExceedsSumOfNonBondTimers` |
| **HW-21** | PHONE | Reboot → `BootReceiver` re-arms → advertisement still wakes the app | Weigh-in after a reboot produces a reading with no app launch | `BootReceiverTest.reArmsScanOnBootCompleted`, `ProcessDeathScenarioTest.killBetweenPersistAndDeliverDrainsOnRestart` |
| **HW-22** | PHONE | Adapter-off event ordering vs GATT status 8/22 | Teardown is clean regardless of ordering; re-arm on `STATE_ON` | `GattSessionTeardownTest.adapterOffTearsDownWithoutRetry` (§3.6b) |
| **HW-23** | SCALE | MTU negotiation — do frames exceed the 23-byte default ATT MTU? | Frames fit, or `RequestMtu` succeeds | `FakeScaleGattTest.faultInjectionOverridesScriptedConnectStatus` (mtuStatus knob) |
| **HW-24** | SCALE | End-to-end: step on → Room row → VitalForge 2xx → HistoryScreen `SENT` | One weigh-in, one row, one POST, visible in history | `ColdStartScenarioTest.fakeMeasurementPersistsDeliversAndAppearsInHistory` |

### 5.1 Deliverables from the hardware session

1. `constants-provenance.md` — every row dated or corrected (ADR-002 rule 2).
2. `03-hardware-validation.md` — every HW row with observed / expected / verdict.
3. **PRP §8.5 resolved with evidence** (HW-12) — the Phase 3 exit gate names it.
4. Real `.frames` captures committed as the fixture corpus (WP-31).
5. Every divergence logged as a P1 finding with the fake-layer test it falsified.

---

## 6. Open items carried into Phase 2

| Item | Kind | Owner |
|---|---|---|
| **P1-A** — §8.8 forbids the full-frame capture Phase 3 needs (§0.2) | Design amendment → **ADR-007** | Phase 2 |
| **A6** — v2 idempotency on `client_id`; replay is unsafe without it | **Escalation to JD** (§4.4) | Before WP-22 is enabled |
| **A7** — does v1 `/api/weight` ignore unknown fields? | Confirm against Track A contract doc, **not** by probing with a real reading | Before WP-17 merges |
| **A5** — does `GET /api/weight/recent` exist? | ADR-003 degrades gracefully either way | WP-20 |
| V2 exact field names | Pinned from Track A contract doc; `V2Shaper` written, key strings blank | WP-17 |
| PRP §8.2 (LAN vs Tailscale), §8.4 (repo location) | Configuration / administrative, no design impact | JD, before Phase 5 |

---

## 7. Exit gate self-check

> "Every work package has named tests. Nothing is planned that cannot be verified
> either in CI or on the hardware checklist."

- **31 packages, 31 with named tests** in `ClassNameTest.methodName` form. No
  package says "tests as appropriate".
- **Every failure edge E1–E16** has at least one named test, and §3.6 states
  explicitly which four have approximated *trigger fidelity* (E2, E3, E5/E5b,
  E12) with the checklist row that closes each.
- **Every checklist row HW-01…HW-24** names the fake-layer test it validates —
  except **HW-01**, which names *nothing on purpose*, because no fake can test
  whether a real scale advertises. That is stated as the plan's largest blind
  spot rather than papered over with a test that would not actually cover it.
- **28 of 31 packages need no hardware at all**; 1 needs any phone; 1 needs the
  BF720, with a data-only expected diff.
- The two risk-first packages (**WP-03** stabilization, **WP-07** init handshake
  sequencing) are **retirable in CI** and are scheduled at the earliest positions
  their dependencies permit. WP-07 is retired in full at position 7. WP-03's
  *decision logic* is retired in full at position 3 and its *input fidelity* at
  WP-10 / HW-08 / HW-09 — the narrower claim is stated in §1 rather than rounded
  up. The rejected third contender (**WP-08**, the wake path) and the reasoning
  for rejecting it are in §1.
