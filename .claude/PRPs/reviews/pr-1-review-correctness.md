# PR-1 Review — Correctness

Branch `vitalforge-connectivity-and-login` vs `main` (~85 files, ~8,200 lines).
Scope: logic bugs, state transitions, races, error handling, decode/measurement
edge cases, documented-vs-actual behaviour. Maintainability findings are
deliberately out of scope — see `pr-1-review.md`.

Line references are against the branch as reviewed.

---

## CRITICAL

None. The closest are H1, H2 and H6, all HIGH — H6 does cause silent loss of a
fully decoded weigh-in, but only on a specific mid-window disconnect, and no
already-persisted data is destroyed.

---

## HIGH

### H1. A single non-`Stable` indication collapses the 45 s first-measurement budget to 4 s
`app/src/main/kotlin/com/ventouxlabs/bascule/ble/session/GattSession.kt:447-460`

In `awaitMeasurement`, the first wait loop runs inside
`withTimeoutOrNull(SessionBudget.FIRST_INDICATION_TIMEOUT)` (45 s,
`SessionBudget.kt`). The `is TransportEvent.CharacteristicChanged ->` branch
decodes the frame and then **unconditionally** falls through to
`return@withTimeoutOrNull MeasureStep.Pending` (line 459) — regardless of
whether the decode produced `Stable`, `Malformed`, `Ignored`, or nothing.

Only `DecodeEvent.Stable` and a `SessionComplete`-triggered `flush()` return
early with a reading. Every other decode result exits the 45 s window and drops
the session into the `MeasureStep.Pending` branch, whose budget is
`SessionBudget.BODY_COMPOSITION_CORRELATION_WINDOW` = **4 s**.

Failure scenario: the user steps on the scale. Any indication that decodes to
non-`Stable` arrives first — a frame on a characteristic the decoder does not
know (`BeurerDecoder.onNotification` → `DecodeEvent.Ignored`, line 175), a
short/truncated frame (`Malformed`), or a Body Composition frame that arrives
before its Weight frame (`MeasurementCorrelator.onBodyComposition` holds it as
an orphan and returns `Ignored`, line 95). The remaining budget is now 4 s. The
real Weight Measurement lands at t≈8-15 s (weight stabilisation, per E7's own
rationale in `00-design.md` §2.3), by which time the correlation window has
expired and `flush()` has nothing buffered. Session returns
`Missed(NO_MEASUREMENT)`; the weigh-in is silently lost.

The orphan-body-composition path is the concerning one because it is a normal
protocol ordering, not a fault: it turns a routine frame-order variation into a
4 s deadline for the weight frame.

`MeasureStep.Pending` should only be returned once the decoder has actually
buffered a weight awaiting its pair; an `Ignored`/`Malformed` result should
`continue` the 45 s loop.

### H2. §3.4's per-row retry backoff is not implemented, and `Retry-After` is parsed then discarded
`app/src/main/kotlin/com/ventouxlabs/bascule/delivery/DeliveryDrainer.kt:34-47, 99-109`
`app/src/main/kotlin/com/ventouxlabs/bascule/delivery/DeliveryCoordinator.kt:15-16`
`app/src/main/kotlin/com/ventouxlabs/bascule/network/ResponseClassifier.kt:40-45`

`00-design.md` §3.4 specifies a per-row next-attempt gate:
`lastAttemptMillis + min(30 s * 2^(attemptCount - 1), 15 min)`. §4.5 additionally
requires that a `TransientFailure` honour the server's `Retry-After` when ≤ 1 h.

Neither exists in the drain path:

- `DeliveryCoordinator.BACKOFF_BASE_MILLIS` and `BACKOFF_CAP_MILLIS` are
  declared and have **zero readers** anywhere in production code.
- `DeliveryDrainer.drain()` calls `dao.pending()` and submits **every** row
  returned, with no comparison of `lastAttemptMillis` against any next-attempt
  time. `lastAttemptMillis` is written but never read for scheduling.
- `ResponseClassifier.parseRetryAfter` populates
  `SubmitResult.TransientFailure.retryAfter`, and
  `DeliveryDrainer.applySubmitResult`'s `TransientFailure` branch (lines 99-109)
  never reads it. The value is computed and thrown away.

Failure scenario: the server returns 429 with `Retry-After: 60`. Row 1 becomes
`TransientFailure`; the drain **continues to rows 2..N in the same pass** and
submits all of them into the same rate limit. `drain()` returns `true` →
`Result.retry()`, which uses WorkManager's own default backoff, not §3.4's
ladder. Separately, every unrelated drain trigger — a manual entry insert
(`ManualEntryViewModel.kt:123`), a token save or login
(`ConfigViewModel.kt:316`), a scale capture (`ScaleSessionWorker.kt:40`), a
History retry tap (`HistoryViewModel.kt:95`) — immediately re-submits the whole
pending set with no per-row gate. A user saving a token while five rows are
backing off resubmits all five instantly.

The only clock that runs against a row is the 14-day
`EXPIRY_MILLIS` check at `DeliveryDrainer.kt:50`, which is the *expiry*
mechanism, not the backoff.

### H3. E8's "exactly one reconnect within a 5 s window" is not implemented
`app/src/main/kotlin/com/ventouxlabs/bascule/ble/session/GattSession.kt:444-446, 478-480`

`00-design.md` §2.3 E8 and the §2.1 state diagram specify a `RECONNECT_ONCE`
state: on `onConnectionStateChange(DISCONNECTED)` while `MEASURING`, discard
partial data and make **exactly one** reconnect attempt within a 5 s window;
only if that fails does the session tear down with `Missed(DROPPED)`.

Both measurement wait loops return `MeasureStep.Dropped` the moment they see
`ConnectionStateChanged(connected = false)`, and `awaitMeasurement` maps that
straight to `SessionOutcome.Missed(MissReason.DROPPED)` (lines 471, 499). There
is no reconnect attempt and no `RECONNECT_ONCE` state anywhere in the class.

Failure scenario: the BF720 drops the link briefly mid-weigh-in (common while
the scale is still powered and the user is still standing on it). The documented
design recovers; this implementation gives up and reports `Missed(DROPPED)`.
`ScaleSessionWorker.kt:43` maps a non-`ADAPTER_OFF` `Missed` to
`Result.success()`, so WorkManager does not retry either — the weigh-in is lost
until the user steps off and on again.

### H4. Re-arming the scan never stops the previous registration, so a profile switch may leave the old device filter in effect
`app/src/main/kotlin/com/ventouxlabs/bascule/ble/ScaleScanner.kt:19-33`
`app/src/main/kotlin/com/ventouxlabs/bascule/ui/ScaleViewModel.kt:79-82`
`app/src/main/kotlin/com/ventouxlabs/bascule/BasculeApplication.kt:88`

Verifiable from the code: `ScaleScanner.arm()` builds a `ScanFilter` keyed to
`profile.deviceAddress` (line 29) and calls
`scanner.startScan(filters, settings, pendingIntent)`. It never calls
`stopScan(pendingIntent)` first. The `pendingIntent` getter uses a fixed
`REQUEST_CODE` (720) and a fixed `Intent` (same component + action) with
`FLAG_UPDATE_CURRENT`, so every `arm()` call passes the **same** PendingIntent
identity with a **different** filter.

`ScaleViewModel.setActive` (lines 79-82) calls `profiles.setActive(profileId)`
then `onArm()` with no intervening `onDisarm()`. `ScaleScanner.disarm()` exists
and has exactly one caller: `setAutomaticCapture(false)`
(`ScaleViewModel.kt:69`).

The code therefore never establishes which filter is in effect after a profile
switch — it depends on undocumented platform behaviour for a duplicate
PendingIntent scan registration. Both branches are defects:

- If the platform ignores the duplicate registration (AOSP's `GattService`
  historically logs "already registered" and returns), the scan stays filtered
  on the **old** device address. Automatic capture never fires for the newly
  activated profile, silently and permanently.
- If the platform replaces it, `startScan` returns
  `SCAN_FAILED_ALREADY_STARTED` (= 1), and `arm()`'s success check is
  `startScan(...) == 0` (line 32), so `arm()` returns `false` and
  `ScaleViewModel.kt:67` shows "Background scan could not be armed. Check
  Bluetooth and permissions." to a user whose scan is fine.

The same ambiguity applies to `BasculeApplication.onCreate`'s unconditional
`scaleScanner.arm()` (line 88): a PendingIntent scan registration survives
process death, so every subsequent process start re-registers the same
PendingIntent.

The fix is the same under either platform behaviour: `disarm()` before every
`arm()`.

### H5. `AndroidGattTransport.gatt` is a non-volatile `var` shared between the session coroutine and binder-thread callbacks
`app/src/main/kotlin/com/ventouxlabs/bascule/ble/session/AndroidGattTransport.kt:38, 43-50, 126-134`

`private var gatt: BluetoothGatt? = null` is written from `connect()` (line 49)
and `close()` (line 128), both driven from the `GattSession` coroutine, and read
from `discoverServices()`, `write()`, `subscribe()`, and `requestMtu()` — while
`BluetoothGattCallback` methods execute on a binder thread and `close()` may
race with an in-flight callback. There is no `@Volatile`, no synchronisation,
and no happens-before edge between the two.

`receiverRegistered` (line 39) has the same problem.

Failure scenario: `GattSession.connectWithRetries` runs `transport.close()`
(setting `gatt = null`) on the E2/E1 ladder while a `onConnectionStateChange`
callback is mid-flight. Under the JMM the write is not guaranteed visible, so
the next `write()`/`subscribe()` can operate on a closed `BluetoothGatt`, or a
stale non-null read can be used after `close()`. This is precisely the class of
leak `00-design.md` §8.10 exists to prevent, and it is invisible to the JVM
tests because they exercise `FakeGattTransport`, not this class.

### H6. A buffered weight is discarded — not flushed — on every non-timeout exit from the correlation window
`app/src/main/kotlin/com/ventouxlabs/bascule/ble/session/GattSession.kt:451-456, 474-502, 86-87`
`app/src/main/kotlin/com/ventouxlabs/bascule/ble/decoders/ScaleDecoder.kt:77-87`

Independently confirmed after the pattern-compliance pass flagged it; the
analysis below is my own verification and extends it.

`ScaleDecoder.flush()`'s contract is "Releases anything the decoder is still
holding for correlation, **at end-of-session or post-emission idle**"
(`ScaleDecoder.kt:78-79`). `MeasurementCorrelator` buffers a completed Weight
Measurement in `pendingWeight` while waiting up to 4 s for its Body Composition
pair; `flush()` releases it as a weight-only reading.

`GattSession` has exactly **two** `flush()` call sites, and one of them is dead:

- **Line 451-456** — the `DecodeEvent.SessionComplete` branch. `BeurerDecoder`
  is the only decoder, and no path through `onNotification` →
  `decodeControlPoint` / `decodeWeight` / `decodeBodyComposition` can ever
  return `SessionComplete`. This branch is unreachable.
- **Line 493** — the elvis on
  `withTimeoutOrNull(BODY_COMPOSITION_CORRELATION_WINDOW)`. Reached **only**
  when the 4 s window expires.

There is no end-of-session flush. `decoder.teardownSequence()` is never called
by `GattSession` either. So every non-timeout exit from the correlation window
drops the buffered weight on the floor:

- `ConnectionStateChanged(connected = false)` (lines 478-480) → `Dropped` →
  `Missed(DROPPED)` at line 499, with `pendingWeight` still held.
- `AdapterOff` (line 477) → `Missed(ADAPTER_OFF)` at line 498, same.
- `run()`'s `withTimeoutOrNull(HARD_SESSION_CEILING)` (line 86) firing at 90 s
  mid-window → `Missed(NO_MEASUREMENT)` at line 87, same.

Failure scenario: the user steps on, the scale sends its Weight Measurement, the
correlator buffers it, and 1-2 s into the 4 s pairing window the BF720 drops the
link (it powers down aggressively after a weigh-in) or the user turns Bluetooth
off. A **completed, valid weight measurement** — already decoded, already
attributed with a user index and a scale timestamp — is discarded. The session
reports `Missed(DROPPED)`, `ScaleSessionWorker.kt:43` maps that to
`Result.success()`, no row is written, and nothing retries.

This is the exact inversion `02-interface-revision.md` §3 was written to
prevent. E17's action is specified as "**persist the weight-only row**, not
discard it", with the explicit note that applying E8's "partial data is
discarded, never persisted" to a completed weight awaiting body composition
"discards a real measurement and inverts its own rationale". `00-design.md`
§8.10-style teardown discipline is honoured; the *data* teardown is not.

Compounds with **H3** (no E8 reconnect, so the disconnect is terminal rather
than recoverable) and with **H1** (H1 shortens the window in which this is
reachable to 4 s, but a disconnect inside those 4 s is common precisely because
the scale is powering off). The fix is a single flush on every terminal path out
of `awaitMeasurement`/`run()`, not just the window timeout.

---

## MEDIUM

### M1. `linkExistingScale` reports success but creates an *inactive* profile, so capture silently never happens
`app/src/main/kotlin/com/ventouxlabs/bascule/ui/ConfigViewModel.kt:394-399`
`app/src/main/kotlin/com/ventouxlabs/bascule/data/ScaleProfileStore.kt:71-86`

`linkExistingScale` calls `consentStore.save(normalizedAddress, credential)`,
which lands in `EncryptedScaleProfileStore.save`. For a new (address, index)
pair, that constructs a profile with
`active = mutableProfiles.value.none { it.active }` (line 83) — i.e. the new
profile is active **only if no other profile is already active**.

Then `configStore.savePairedDeviceAddress(normalizedAddress)` is written and the
UI is set to `ScaleRegistrationUiState.Success` (line 398).

Failure scenario: the user already has one registered profile (active) and links
a second scale/slot. The link reports success and the ConfigScreen shows the
registered index. But `ScaleSessionWorker.kt:30-31` reads
`app.scaleProfileStore.activeProfile.value` and returns `Result.success()`
without capturing whenever the advertising address does not match the *active*
profile — and `ScaleScanner.arm()` (line 28) filters on the active profile's
address too. The newly linked scale is never scanned for and never captured
from, with no error surfaced anywhere.

Same shape applies to `AndroidScaleRegistrar` → `consentStore.save` on a
successful registration when another profile is already active.

### M2. `AndroidScaleRegistrar` reports `Success` for a session that failed, whenever a credential already exists
`app/src/main/kotlin/com/ventouxlabs/bascule/ble/ScaleRegistrar.kt:97-106`

After `session.run()`, the outcome is only consulted to build a *failure*
message. Success is decided by
`newlySavedCredential ?: consentStore.credentialFor(address)`.

Failure scenario: `forceNew = false`, a credential for this address already
exists from an earlier registration, and this session fails outright —
`Missed(ADAPTER_OFF)`, `HandshakeFailed("stored scale consent was rejected")`,
whatever. `newlySavedCredential` is null (nothing was saved), but
`consentStore.credentialFor(address)` returns the pre-existing credential, so
the branch at line 103 fires: `savePairedDeviceAddress` is written and the user
is told registration succeeded. The stored credential the scale just rejected is
now presented as a working registration.

Note also that `consentStore.credentialFor(deviceAddress)` requires
`it.active` (`ScaleProfileStore.kt:49`), so under M1's inactive-profile case the
lookup returns null and the same call reports a *failure* for a session that
actually worked. Both directions of the mismatch are reachable.

### M3. `ExistingWorkPolicy.KEEP` plus the 20 s staleness abort can drop a live advertisement
`app/src/main/kotlin/com/ventouxlabs/bascule/ble/session/ScaleSessionEnqueuer.kt:36`
`app/src/main/kotlin/com/ventouxlabs/bascule/ble/session/ScaleSessionWorker.kt:21`

`enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)` discards
the new request whenever work under that name is enqueued or running. The worker
independently aborts with `Result.success()` when
`System.currentTimeMillis() - seenAt > STALENESS_ABORT_MILLIS` (20 s).

Failure scenario: an expedited request is queued but not yet dispatched (the
worker is expedited via `RUN_AS_NON_EXPEDITED_WORK_REQUEST`, so it competes with
ordinary WorkManager scheduling and can sit for a while, especially in Doze).
The user steps on the scale; `ScanBroadcastReceiver` enqueues with a fresh
`seenAt`, and `KEEP` throws it away. The queued request eventually runs with the
old `seenAt`, finds it stale, and returns `success()` without ever connecting.

The unique-work policy is what satisfies the plan's acceptance criterion
"Duplicate advertisements, frames, and sessions do not create duplicate
deliveries", but the same policy discards a legitimate re-trigger. A
`seenAt`-aware policy (or `REPLACE` when the existing request is not yet
running) would satisfy both.

### M4. Most diagnostics counters are never incremented; two documented behaviours depend on them
`app/src/main/kotlin/com/ventouxlabs/bascule/diagnostics/DiagnosticsCounters.kt:10-21`

Of the ten `DiagnosticsCounterKey` values, only three are ever written, all in
`GattSession.kt`: `INCOMPATIBLE_STREAK` (increment :251, reset :265),
`REGISTRATION_REJECTED` (:363), `DUPLICATE_STABLE_SUPPRESSED` (:516 — and see
M5, it cannot fire).

Never written anywhere in production: `MISSED_QUOTA`, `MALFORMED_COUNT`,
`NO_MEASUREMENT`, `UNPAIRABLE_FRAMES_DROPPED`, `DUPLICATES_SUPPRESSED`,
`DROPPED_OTHER_USER`, `REMOTE_DUPLICATES_SUPPRESSED`. Note that
`BeurerDecoder` *computes* `duplicateFramesSuppressed` and
`unpairableFramesDropped` (:225, :228) but nothing plumbs them into
`DiagnosticsCounters`, and `ReadingIngestor` returns `IngestResult.Duplicate`
without counting it.

Two consequences beyond an empty diagnostics panel:

- **E4's arming suspension does not exist.** `00-design.md` §2.3 E4: at 3
  consecutive `Incompatible` outcomes, ConfigScreen shows "Scale not
  recognised…" and "scan arming is suspended until the user re-selects a
  device", with §8.11 and §12 item 13 both naming it as the guard against an
  infinite wake-connect-fail loop against a neighbour's device.
  `INCOMPATIBLE_STREAK` is incremented but never compared against
  `SessionBudget.INCOMPATIBLE_STREAK_SUSPEND_THRESHOLD`, which itself has no
  readers. `01-plan.md` WP-06 explicitly deferred the consumer to "WP-08 or
  later"; WP-08 has landed on this branch and the consumer did not.
- **E7's re-pairing notification does not exist.** §2.3 E7 requires that 3
  consecutive `NoMeasurement` sessions "raise an E4-style notification
  suggesting re-pairing, rather than repeating silently forever".
  `MissReason.NO_MEASUREMENT` is produced at `GattSession.kt:87, 467, 501`, but
  `DiagnosticsCounterKey.NO_MEASUREMENT` is never incremented, so the streak can
  never be observed. `HistoryViewModel.kt:45-50`'s KDoc cites this exact counter
  as the reason the counter flow is combined alongside `dao.observeAll()` —
  documenting behaviour that cannot occur.

### M5. `DUPLICATE_STABLE_SUPPRESSED` is unreachable — the post-emission duplicate check can never fire
`app/src/main/kotlin/com/ventouxlabs/bascule/ble/session/GattSession.kt:511-524`
`app/src/main/kotlin/com/ventouxlabs/bascule/ble/decoders/MeasurementCorrelator.kt:51-62, 166`

`finishEmission` drains the post-emission idle window and increments
`DUPLICATE_STABLE_SUPPRESSED` whenever `decoder.onNotification(...)` returns
`Stable`. It cannot: `MAX_EMISSIONS_PER_SESSION = 1`, so after the first `emit`
`correlationClosed` is true, and `onWeight` checks `correlationClosed`
(line 59) **before** the superseded-weight branch that is the only other
`emit` path. `onBodyComposition` with `pendingWeight == null` also short-circuits
on `correlationClosed`.

This is a correctness finding rather than dead code: E9's in-session duplicate
latch is supposed to be *observable*, and the counter that would prove a second
weigh-in was suppressed is structurally always zero. Anyone diagnosing a lost
household-member weigh-in gets no signal at all — the frame is counted only in
`unpairableFramesDropped`, which (per M4) is never published either.

### M6. SIG `0xFFFF` "value unknown / measurement unsuccessful" is decoded as a real number
`app/src/main/kotlin/com/ventouxlabs/bascule/ble/decoders/BodyCompositionMeasurement.kt:78, 82-93`
`app/src/main/kotlin/com/ventouxlabs/bascule/ble/decoders/WeightMeasurement.kt:53-58, 66-73`

Bluetooth SIG Body Composition Service 1.0 defines `0xFFFF` in these fields as
"value unknown" / the measurement was unsuccessful. Neither parser checks for
it: every `u16()` is scaled unconditionally.

Failure scenario: the BIA impedance pass fails (bare feet not making contact,
socks, a very short stand) and the BF720 reports body fat as `0xFFFF`. The
parser yields `65535 * PERCENT_PER_LSB` = **6553.5 %** body fat, which flows
through `MeasurementCorrelator.merge` into `ScaleReading.bodyFatPct`, through
`ReadingMapper.map` into `ReadingEntity.bodyFatPct`, and is shaped into the
VitalForge payload on the V2 contract. The same applies to `musclePct`,
`impedanceOhms`, and every mass field (`0xFFFF * 0.005` = 327.675 kg).
`ReadingIngestor`'s plausibility gate (line 22) only checks `weightKg`, so
nothing downstream catches it.

Being explicit about the evidence: **no project document requires this check.**
`02-interface-revision.md` §3 and `03-hardware-validation.md` document
absent-field handling only via flags and the feature bitmap (fields the unit
declares unsupported are null), and the only `0xFFFF` references in the corpus
are to the proprietary *service UUID* `0x0000FFFF`. This is a specification
conformance gap, not a documented-behaviour mismatch — rated MEDIUM on that
basis.

Related, lower confidence: BCS 1.0 flags bit 12 is "Multiple Packet
Measurement". `BodyCompositionMeasurementParser` defines bits 0-11 only and
would misparse a multi-packet frame as a truncated one.

### M7. An accepted submission is downgraded to `TransientFailure`, causing a re-submit of a reading the server already stored
`app/src/main/kotlin/com/ventouxlabs/bascule/network/VitalForgeHttpClient.kt:92-96`

When `ResponseClassifier` returns `Accepted` and `response.bodyExceedsCap()` is
true, the result becomes `TransientFailure(OVERSIZED_BODY_REASON, null)`. The
comment reasons that "a success we could not read is not a success" — but the
server has already committed the write; only the *response* was unreadable.
`DeliveryDrainer` then leaves the row `PENDING` and re-submits it on the next
drain, duplicating the reading server-side.

The remote-duplicate check at `DeliveryDrainer.kt:60,67` is the only thing
preventing that, and it is itself best-effort: if `recentReadings` returns
`RecentResult.Unavailable` (network hiccup, unparseable body, or its *own*
`bodyExceedsCap` at line 113), `isRemoteDuplicate` returns false and the
duplicate goes through.

### M8. `runCatching` around suspending work swallows `CancellationException`
`app/src/main/kotlin/com/ventouxlabs/bascule/network/VitalForgeHttpClient.kt:196`
`app/src/main/kotlin/com/ventouxlabs/bascule/ui/ConfigViewModel.kt:403, 431`

`execute()` wraps `client.newCall(request).execute().use(handle)` inside
`runCatching` within `withContext(Dispatchers.IO)`. A cancelled call surfaces as
an `IOException` from OkHttp and is converted into
`onFailure("network error")` — a normal-looking transient failure — rather than
propagating cancellation. `ConfigViewModel.exportSettings`/`importSettings` wrap
`withContext(ioDispatcher)` in `runCatching` directly, which catches
`CancellationException` itself.

Failure scenario: the `DeliveryWorker` is stopped by WorkManager mid-submit. The
cancellation is reported as a transient network failure, so
`applySubmitResult` increments `attemptCount` and records a `lastError` for an
attempt that was never made. Over repeated cancellations this inflates
`attemptCount` (which §3.4 makes the backoff exponent — see H2 — and which
`HistoryViewModel` resets on manual retry precisely because a stale count is
harmful).

### M9. `BridgeForegroundService` stays running as an inert foreground service when it cannot scan
`app/src/main/kotlin/com/ventouxlabs/bascule/service/BridgeForegroundService.kt:28-50`

`onCreate` calls `startForeground(...)` (line 31) and then `startActiveScan()`.
`startActiveScan` returns early — leaving the FGS running, notification showing
"Scale bridge active / Waiting for the configured scale" — in two cases:

- `BLUETOOTH_SCAN` not granted on API 31+ (line 44);
- no active profile (line 45).

Neither calls `stopSelf()`, and there is no listener that retries the scan when
the permission is later granted or a profile is registered. The user sees a
persistent "bridge active" notification, pays the battery cost of a foreground
service, and gets no captures, indefinitely, until they toggle always-on
bridging off and on again.

Secondary note (not independently verifiable from the sources read):
`startForeground` runs *before* the permission check, and the manifest declares
`android:foregroundServiceType="connectedDevice"` (`AndroidManifest.xml:61`).
On API 34+ starting a `connectedDevice`-typed FGS without the corresponding
Bluetooth permission throws `SecurityException` — which here would be thrown
from `onCreate` and crash the process. Whether that path is reachable depends on
`targetSdk`, which I did not read.

### M10. Read-modify-write on `EncryptedScaleProfileStore`'s profile list is not atomic
`app/src/main/kotlin/com/ventouxlabs/bascule/data/ScaleProfileStore.kt:96-117`

Every mutator computes its next state from `mutableProfiles.value` and then
calls `persist(next)`, which writes prefs and assigns
`mutableProfiles.value = next`. There is no lock and `MutableStateFlow` assignment
is not a compare-and-set here.

`ScaleProfileStore` is reached concurrently from at least three contexts: the
`GattSession` coroutine via `ConsentStore.save` (`GattSession.rememberCredential`,
running on a WorkManager dispatcher), `ScaleViewModel` on `viewModelScope`
(`setActive`, `rename`), and `ConfigViewModel.importSettings` on
`ioDispatcher`. A concurrent registration completing while the user renames a
profile drops one of the two writes.

`persist` also uses `.commit()` (line 114) — a synchronous disk write — inside
what may be a main-thread-dispatched ViewModel call (`rename` is not launched in
a coroutine at all, `ScaleViewModel.kt:84-88`).

### M11. `EncryptedScaleProfileStore.credentialFor(address)` requires the profile to be *active*, so a non-active profile triggers a fresh registration
`app/src/main/kotlin/com/ventouxlabs/bascule/data/ScaleProfileStore.kt:48-64`

The single-argument `credentialFor` matches on `deviceAddress` **and**
`it.active`. When it returns null and a `legacy` store is present (it always is
in production — `BasculeApplication.kt:55`), the method falls through to the
legacy-migration branch and, if the legacy store has a mapping, *writes a new
profile* (lines 52-62) — potentially a duplicate of the existing non-active one,
since the new profile gets a fresh `UUID` and `active = true`.

Downstream, `GattSession.handshakeContext()` calls exactly this overload. A
session against a device that has a registered-but-inactive profile therefore
sees `storedCredential == null` and, if `purpose.permitsRegistration`, sends
**Register New User** — consuming one of the scale's 8 slots. That directly
contradicts the plan's acceptance criterion "Registration, administration, and
measurement never overlap or silently allocate profiles". The `MEASUREMENT`
purpose does not permit registration, so the reachable path is a foreground
re-register, but the pre-existing slot is still orphaned.

### M12. `importSettings` changes automatic-capture state and profiles without re-arming or disarming the scanner
`app/src/main/kotlin/com/ventouxlabs/bascule/ui/ConfigViewModel.kt:442-445`

`importSettings` writes `saveAutomaticCaptureEnabled(imported.…)` and calls
`scaleProfileStore.replaceAll(imported.profiles)` — changing both the enable
flag and which profile is active — but never touches `ScaleScanner`.
`ConfigViewModel` has no reference to it.

Result: importing a backup with automatic capture enabled does not start
scanning (until the next process start hits `BasculeApplication.kt:88`), and
importing one with it disabled leaves a live scan registered. Combined with H4,
importing a backup with a *different* active profile leaves the scan filtered on
the previous device address.

Separately, `replaceAll` only enforces `count { it.active } <= 1` — a backup with
zero active profiles is accepted, after which `activeProfile` is null and both
`arm()` and `ScaleSessionWorker` refuse to do anything, with nothing surfaced to
the user.

### M13. `ScanBroadcastReceiver` acts on only the first scan result and does not check it against the active profile
`app/src/main/kotlin/com/ventouxlabs/bascule/ble/ScanBroadcastReceiver.kt:30-31`

`results.firstOrNull()?.device?.address ?: return` — the remaining results are
discarded. Batched PendingIntent scan deliveries routinely carry multiple
results; if a non-scale device that happens to advertise `0x181D` sorts first in
a batch, the scale's own result in the same batch is dropped and no session is
enqueued for it. The receiver also does not verify the address against the
active profile before enqueuing (`ScaleSessionWorker` does check, at line 31, so
the consequence is a wasted expedited worker plus — via M3's `KEEP` — a window
in which the real advertisement's enqueue is discarded).

---

## LOW

### L1. `00-design.md` §7 Branch A now contradicts the branch's own plan
`00-design.md` §7 specifies that a reading whose `userIndex` differs from the
registered one is "dropped at the persistence boundary, counted as
`droppedOtherUser`", and ADR-006 states `HELD_CONFIRM` is Branch-B-only dead
code. `ReadingIngestor.kt:31-35` writes `HELD_CONFIRM` instead.

This is **not** a bug: `docs/prp/04-scale-admin-and-automation-plan.md`
supersedes it explicitly ("Readings from another slot are stored as
`HELD_CONFIRM`; confirming uploads that reading once without changing the active
profile", plus the acceptance criterion "Another profile's reading is held and
never uploaded without confirmation"). Flagged only so §7 and ADR-006 get a
superseded-by marker; a future reader following §7 would call the current code
broken.

One genuine sub-case worth a look: a reading with `userIndex == null`, or one
whose index matches no profile at all, takes the same `HELD_CONFIRM` path with
`scaleProfileId = null`. In HistoryScreen it is indistinguishable from a
known-other-profile reading, so "Yes, that's me" uploads a completely
unattributed weight.

### L2. Terminal paths in the connect ladder call `close()` twice
`app/src/main/kotlin/com/ventouxlabs/bascule/ble/session/GattSession.kt:136-140, 169-172, 88-94`

The E2 branch calls `transport.disconnect()` then `transport.close()` *before*
checking the retry cap, so on exhaustion it closes and returns
`ConnectPhaseResult.Failed`, after which `run()`'s `finally` closes again. Same
for the E1/`else` branch (line 170 precedes the cap check at line 171).

§8.10's literal wording is "Every terminal path calls `gatt.close()` exactly
once", though E1/E2 mandate a `close()` per retry, so the invariant is really
per-`BluetoothGatt`-instance. `BluetoothGatt.close()` is effectively idempotent,
so this is cosmetic — but the second `close()` on the terminal path also
re-runs `AndroidGattTransport.close()`'s `unregisterReceiver` in a
`runCatching`, which is exactly the shape that hides a real unregister failure.
Moving the cap check above the `close()` calls would satisfy the doc literally.

### L3. `ScaleSessionWorker.setForeground` is unguarded
`app/src/main/kotlin/com/ventouxlabs/bascule/ble/session/ScaleSessionWorker.kt:26`

`setForeground(foregroundInfo())` throws `IllegalStateException` when the
expedited-work quota is exhausted or foreground-service start restrictions
apply. Unwrapped, the worker fails with an exception rather than a diagnosable
outcome, and nothing distinguishes it from a genuine session failure.

### L4. `AndroidScaleRegistrar.findScale` takes the first weight-scale advertisement it sees
`app/src/main/kotlin/com/ventouxlabs/bascule/ble/ScaleRegistrar.kt:119-143`

The filter is `WEIGHT_SCALE_SERVICE` only, and `onScanResult` resumes with the
first result. `BeurerDecoder.matches` (name prefix `"BF"`) is never consulted on
this path. In a flat with a neighbour's SIG-compliant scale in range,
registration can connect to — and attempt to register a user slot on — the wrong
device, while reporting "No BF720 found" language in its failure copy.
`onScanFailed` also resolves to the same "No BF720 found" message rather than a
scan-error message.

### L5. `ScaleOperationCoordinator.isBusy`/`busyWith` are not consistent
`app/src/main/kotlin/com/ventouxlabs/bascule/ble/session/ScaleOperationCoordinator.kt:23-33`

`currentPurpose` is assigned *inside* `withLock`, after acquisition, so there is
a window where `isBusy` is true and `busyWith` is null. Any caller that renders
"waiting on ${busyWith}" can show a blank. `isBusy` reading `mutex.isLocked` is
also inherently TOCTOU.

---

## Reviewer's summary

The BLE decode layer is the strongest part of this branch: the SIG parsers are
bounds-checked, the correlator's one-emission-per-session rule is coherently
argued and correctly implemented, and the handshake state machine's
stale-response budget is genuinely careful reasoning about a protocol with no
correlation IDs. The KDoc is unusually good at recording *why* a mechanism
exists, which made this review possible at all.

The defects cluster in the layer above the decoder — session orchestration,
scan lifecycle, and delivery scheduling — and share one shape: **a documented
mechanism whose consumer was never written.** E4's arming suspension, E7's
re-pairing notification, E8's single reconnect, E17's end-of-session flush,
§3.4's backoff ladder, and `Retry-After` handling are each specified in the
docs, each have a constant, a call site, or a data-carrier field in the code,
and each have zero live readers. Seven of ten diagnostics counters are never
incremented; `DecodeEvent.SessionComplete` is never emitted;
`decoder.teardownSequence()` is never called. The pattern suggests the Phase-3
work packages landed their *producers* and deferred their *consumers* to a WP
that has now been marked done — I'd check the WP-08/WP-10/WP-21 exit criteria
against this list before merge, because the code compiles and the tests pass in
every one of these cases.

Prioritisation, if only some can be fixed: **H1 and H6 are the same weigh-in
being lost twice** and should be fixed together — H1 shortens the window in
which a real measurement can be captured from 45 s to 4 s, and H6 throws the
measurement away if the link drops inside that window. Between them they are
the most likely cause of a user reporting "I stepped on the scale and nothing
happened". **H4 and M1** make the new multi-profile feature not work at all once
a second profile exists, and they compound: link a scale (M1 leaves it
inactive), switch to it (H4 leaves the scan on the old address), and automatic
capture is dead with a "Success" toast behind it. **H2** is the one with
server-visible consequences.

Two things I could not verify and flagged as such rather than asserting:
AOSP's handling of a duplicate PendingIntent scan registration (H4 — restructured
so the finding holds either way), and whether M9's `SecurityException` path is
reachable at this project's `targetSdk`.
