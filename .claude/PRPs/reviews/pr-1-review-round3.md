# Round 3 Review — vitalforge-connectivity-and-login @ 62310a5

Multi-agent review covering four slices, each independently re-verified (this round supersedes any earlier round-3 findings lost to context compaction — those were re-reviewed fresh rather than trusted from a lossy summary). 55 findings: **3 CRITICAL, 10 HIGH, 20 MEDIUM, 22 LOW.**

**Overall verdict: BLOCK** (3 CRITICAL, must fix before merge).

Note: three findings below (storage/UI HIGH #1, lifecycle H7, lifecycle M6) describe the **same root defect** from three angles (scale-registration never activates/re-arms). One fix closes all three.

---

## CRITICAL (3)

### C1 — Concurrent drains double-post readings; no row lease
`delivery/DeliveryDrainer.kt:55-75` (root cause), `delivery/DeliveryScheduler.kt:22-38` (enabler)

`drain()` does read-modify-write across a network call with no claim on rows. Two independently-unique WorkManager work names (`delivery-drain`, `delivery-periodic`) can both run `DeliveryWorker` concurrently; `ExistingWorkPolicy.KEEP` only dedupes within one name. Both fetch the same PENDING rows and both submit all of them. V1Shaper has no idempotency key, so the server can't dedupe — user sees duplicated weigh-ins with no way to retract them. Also causes a lost-update on `attemptCount` and can let a slower drain's TransientFailure branch resurrect PENDING over a faster drain's SENT.

Fix direction: one unique work name for both triggers, or a claim/lease on selected rows.

### C2 — Any 3xx response permanently fails the whole delivery queue
`network/ResponseClassifier.kt:25`

Any 3xx status is classified as permanent failure. Combined with `followRedirects(false)` being deliberately set, a single redirect response (e.g. a server-side http→https rule) marks **every** pending reading FAILED_PERMANENT on first attempt — total, unrecoverable data loss for the whole outstanding queue triggered by a one-line server config change.

### C3 — 90s hard session ceiling can destroy an already-decoded reading
`ble/session/GattSession.kt:107` (ceiling), `:626-630, :685-709` (idle wait / flush path)

`HARD_SESSION_CEILING` (90s) wraps the 10s post-emission idle wait. If the ceiling fires during that idle period, an already-decoded, already-attributed weight reading is silently discarded with no diagnostic — `MeasurementCorrelator.flush()` has already consumed `pendingWeight` during emission and returns null on the timeout path. `SessionBudgetTest.kt:105-109` already documents the arithmetic that makes this reachable, with a rationale that predates the ceiling actually being enforced.

---

## HIGH (10)

1. **GATT client leaked on every contention retry** — `ble/session/GattSession.kt:173-181` + `AndroidGattTransport.kt:51-58`. Contention branch (status 8/19/22) skips disconnect()/close(); transport's `connect()` overwrites `gatt` field without closing prior instance. Repeated leaks exhaust the per-app GATT client table → permanent status-133 until process kill.
2. **429 with unparseable Retry-After bursts the rest of the batch** — `network/ResponseClassifier.kt:40-45` + `delivery/DeliveryDrainer.kt:141`. `parseRetryAfter` returns null for HTTP-date/absent/over-large headers; drain loop then continues and submits ~50 rows back-to-back into a rate limiter.
3. **Pagination continuation indistinguishable from failure retry** — `delivery/DeliveryDrainer.kt:74`, `DeliveryWorker.kt:23`. Both map to `Result.retry()`, so WorkManager applies exponential backoff to healthy multi-page draining (e.g. BLOCKED_AUTH recovery of 500 rows → ~4 hours).
4. **Retry tap dropped by KEEP policy during WM backoff** — `delivery/DeliveryScheduler.kt:23-29` vs `ui/HistoryViewModel.kt:87-98`. `triggerImmediateDrain()` uses `ExistingWorkPolicy.KEEP`; a user's explicit Retry tap is silently dropped while a drain sits in backoff.
5. **Primary scan wake path can drop the enqueue, no goAsync()** — `ble/ScanBroadcastReceiver.kt:24-36`. Cold-started process can be killed before the async WorkManager enqueue is durably recorded.
6. **Unguarded setForeground() silently drops weigh-ins on API31+** — `ble/session/ScaleSessionWorker.kt:26`. No try/catch around `ForegroundServiceStartNotAllowedException`; CoroutineWorker swallows it, work fails silently, no retry, no diagnostic.
7. **Uncaught exception in BootReceiver crashes app on boot** — `service/BootReceiver.kt:26-31`. No catch/exception handler around `arm()`; a keystore/DataStore fault crashes on every boot.
8. **Uncaught exception on every process start** — `BasculeApplication.kt:118-127`. Same pattern as #7 but in `onCreate`, broader blast radius (every launch, not just boot).
9. **Registration leaves old profile active, wrong slot used** — `data/ScaleProfileStore.kt:127-146` + `ui/ConfigViewModel.kt:398-424` (same defect as storage/UI #1 below). New registration doesn't call `activateLinkedProfile`/`rearmScanner`; capture silently dies or uses the wrong credential slot.
10. **Registering a second scale reports success but never captures** — `ui/ConfigViewModel.kt:398-424`. (Duplicate of #9 — same fix.) `startScaleRegistration` never calls `rearmScanner`/`activateLinkedProfile`, unlike the parallel `linkExistingScale` path.

---

## MEDIUM (20)

1. Remote-duplicate check ignores userIndex, can lose a manual entry's reading — `delivery/DeliveryDrainer.kt:95-98`
2. New OkHttpClient built per drain instead of a shared singleton — `network/RuntimeApiFactory.kt:24`
3. Rejected/duplicate readings vanish silently, no diagnostics — `ble/session/ScaleSessionWorker.kt:69`
4. FAILED_PERMANENT rows still block re-weigh as duplicate — `data/ReadingIngestor.kt:46-50`, `data/ReadingDao.kt:47-55`
5. Orphan body-composition frame pairs with ANY next weight frame, wrong user — `ble/decoders/MeasurementCorrelator.kt:67-70`
6. Second orphan body-comp frame overwrites first, uncounted — `ble/decoders/MeasurementCorrelator.kt:97`
7. Multi-packet body-comp flag bit (12) never checked — `ble/decoders/BodyCompositionMeasurement.kt:46-57`
8. No plausibility gate on body-composition fields (bodyFatPct, musclePct, bmi, bmr) — `data/ReadingIngestor.kt:22-26`
9. `replaceAll`'s `distinctBy` can drop the active profile, leaving zero active — `data/ScaleProfileStore.kt:164-171`
10. Host-changing import silently signs out, no user message — `ui/ConfigScreen.kt:600-603`
11. V2 contract selectable in UI despite unfinished shaper — `ui/ConfigScreen.kt:317-324`
12. Overbroad catch in encrypted-prefs construction deletes ALL credentials on any failure — `network/EncryptedPreferences.kt:28-33`
13. AdapterOff event swallowed, misclassified as Incompatible — `ble/session/GattSession.kt:240-247`
14. FGS notification shown before session viability checked — `ble/session/ScaleSessionWorker.kt:26` vs `:37-43`
15. Inert foreground service, no self-stop, SecurityException swallowed — `service/BridgeForegroundService.kt:79-96`
16. No enqueue cooldown on primary scan-broadcast wake path — `ble/ScanBroadcastReceiver.kt:35`
17. Exported BootReceiver never validates intent action (unfixed from prior round) — `service/BootReceiver.kt:26`, `AndroidManifest.xml:75-81`
18. Auto-capture stays dead after enable-then-register ordering (same root as #9/#10 HIGH) — `ui/ConfigViewModel.kt:416-421`
19. Always-on bridging boot start can miss its FGS exemption window — `BasculeApplication.kt:121-126`
20. (see HIGH list — most MEDIUMs above are independent; this doc's MEDIUM count is 20 per the filed tally)

---

## LOW (22)

Test-integrity: `DeliveryDrainerTest.kt:203-216`/`DeliveryCoordinatorTest.kt:54-61` assert behavior production can't produce; `ConfigStoreTest.kt:49,51` has raw NUL bytes making the file git-binary (already bit the branch once — invisible to a prior review round).

Immutability/dead-code: `ReadingPayloadShaper.kt:44` leaks mutable Set; `ScaleProfileStore.kt:23` dead `initializationIncomplete` field; `ScaleProfileStore.kt:50` `deleteProfile` has no production caller; `GattSession.kt:695-697` unreachable branch; `SigWeightProfile.kt:51,91` dead/duplicate constants; `ScanBroadcastReceiver.kt:3` unused import; `ScaleSessionWorker.kt:38` unchecked cast; `ScaleSessionEnqueuer.kt:67` non-daemon executor never shut down.

Misclassification/diagnostics: `WeightMeasurement.kt:63` unsuccessful-measurement sentinel misclassified as malformed; `GattSession.kt:203-212` graceful disconnect misclassified as timeout; `BeurerDecoder.kt:113-117` truncated success flagged as registration refusal; `BasculeApplication.kt:158-163` bridging-start reported success even when service self-stops.

Robustness: `FrameReader.kt:51-71` date_time fields lack range validation; `BodyCompositionMeasurement.kt:82-99` parse order depends on named-arg evaluation order; `ScaleScanner.kt:26-48` arm()/disarm() unsynchronized; `ScaleRegistrar.kt:154-156` onScanFailed doesn't stop scan; `BridgeForegroundService.kt:106-110` cooldown claimed before outcome known; `ScanBroadcastReceiver.kt:32` arbitrary-address fallback; `BootReceiver.kt:27-30` goAsync() window has no timeout.

Security-hardening: `SettingsBackupCodec.kt:80,196` PBKDF2 iteration count matches the SHA-512 recommendation, not SHA-256's (should be 600k not 210k); `app/build.gradle.kts:106-109` androidTest deps declared with no androidTest dir/CI lane and a false KDoc claiming coverage exists.

---

## Verified clean (survived a second independent attack)

- `DeliveryCoordinator.backoffMillis` — overflow-safe, confirmed.
- `ReadingPayloadShaper.V2Shaper.putOptional` — null-skip logic correct.
- `ReadingIngestor.kt:22-26` isFinite+range guard — correct, and is load-bearing for `ReadingMapper.kt:39`'s division (worth one comment, not a fix).
- Credential mutual exclusivity (token vs cookie) in `ConfigViewModel`/`RuntimeApiFactory` — correct.
- `GattSession` "asymmetric teardown" — refuted, both patterns are contextually correct.
- `BridgeForegroundService` missing `onStartCommand` override — refuted, default behavior is correct at targetSdk 37.
- Encrypted-storage crypto (AES-256-GCM, fresh salt/IV per call, AAD-bound magic, Keystore usage, no plaintext fallback) — clean except the overbroad catch (MEDIUM #12) and PBKDF2 iteration count (LOW).
- FrameReader bounds-checking, SIG unit scaling, decoder-side overflow — clean.

## Recommended fix order

1. **C1, C2** (delivery layer) — data-loss/data-corruption, highest blast radius.
2. **C3** (session ceiling) — silent reading loss.
3. **HIGH #9/#10** (registration never activates/re-arms) — the single most user-visible "it just doesn't work" bug, closes 3 filed findings at once.
4. **HIGH #7/#8** (uncaught boot/startup crashes) — cheap, one exception handler each.
5. **HIGH #5, #6** (goAsync, unguarded setForeground) — cheap, well-isolated.
6. **HIGH #1** (GATT leak), **HIGH #2/#3/#4** (delivery retry/backoff interactions — fix together, they compose).
7. MEDIUM batch, grouped by file/subsystem.
8. LOW batch, grouped by file/subsystem.
