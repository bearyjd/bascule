# PR Review: #1 — Completeness dimension

**Reviewed**: 2026-08-25
**Branch**: `vitalforge-connectivity-and-login` → `main` (`git diff main...HEAD`, 95 files, ~9,200 lines)
**Dimension**: Completeness only — missing test coverage, unimplemented edges,
gaps between spec and implementation.
**Status**: COMPLETE. 5 HIGH / 11 MEDIUM / 5 LOW.

Correctness, security, performance, type safety, patterns and maintainability
are other passes' job and are excluded here even where noticed. Where a finding
touches a swallow that another dimension may also claim, it is framed as *what a
test must pin*, not as *the code is wrong*.

## Method note

Verification was by reading production files against their test files. Nothing
was executed: this sandbox has the shared per-user disk-quota problem
`HANDOFF.md` documents, and it degraded `Bash` to the point that even `grep`
returned empty during this session. A local test run would have proved nothing
either way, so none was attempted. Every claim below rests on a file read.

## Scope note — what is *not* reported here

Per `HANDOFF.md` and
`.claude/PRPs/reports/scale-admin-testing-completeness-report.md`, these are
already-tracked known gaps and are **not** re-reported as new findings:

- `BridgeForegroundServiceTest` (Task 5c) never written.
- `ScaleSessionWorkerTest` covering only the pre-`BasculeApplication`-cast
  branches. (Its post-cast branches — including the discarded `IngestResult` at
  `ScaleSessionWorker.kt:39` and the `else -> Result.failure()` collapse at
  `:44` — fall inside that known gap.)
- No `androidTest`/instrumented CI job (Task 6).
- `ScaleScanner.arm()` covered only for its pure early-return gates (so the
  `runCatching{…}.getOrDefault(false)` at `ScaleScanner.kt:32` is inside that
  known scope note).
- Every `E1`–`E19` failure edge already has a named test per `01-plan.md`;
  `GattSessionMeasureTest` closed the last of those this session.

---

## The dominant theme

**Three ViewModels in this diff take an optional collaborator that defaults to
`null`, and every single test leaves it `null`.** The branches guarded by those
collaborators are therefore not merely untested — they are *structurally
unreachable* by the existing suite, and will stay unreachable as tests are
added, because the gap is in the shared test helper, not in any one test.

| Production | Optional param | Test helper that always leaves it null |
|---|---|---|
| `ConfigViewModel.kt:115` | `scaleProfileStore: ScaleProfileStore? = null` | `ConfigViewModelTest.kt:40-59` (`viewModel(...)`, all 31 tests) |
| `HistoryViewModel.kt` | `deliveryTrigger` | `HistoryViewModelTest.kt:26-27` |
| `ManualEntryViewModel.kt` | `deliveryTrigger` | `ManualEntryViewModelTest.kt:27-28` |

`FakeScaleProfileStore` and `FakeDeliveryTrigger` both already exist in this
diff. Nothing needed to close this is missing except the wiring. C1 is the worst
consequence, with C14 and C15 behind it; fixing the three helpers is one small
change that unlocks all three.

---

## HIGH

### C1. The multi-profile settings export/import path — the headline feature of this diff — has zero effective test coverage, and the existing tests structurally cannot catch a regression in it.

`ConfigViewModelTest`'s `viewModel(...)` helper (`ConfigViewModelTest.kt:40-59`)
does not accept or pass a `scaleProfileStore`, so it is `null` in all 31 tests.
Two consequences:

- **Export.** `ConfigViewModel.kt:423` reads
  `profiles = scaleProfileStore?.profiles?.value.orEmpty()`. With the store
  null, every test exports an **empty** profile list.
  `settingsExportIncludesCredentialsAndScaleMappingOnlyInsideEncryption`
  (`ConfigViewModelTest.kt:551`) asserts `baseUrl`, `credentialType`,
  `credentialValue` and the legacy single `scaleCredential` — it never asserts
  `restored.profiles`. A change that dropped profiles from the export entirely
  would pass the whole suite.
- **Import.** `ConfigViewModel.kt:444-445`
  (`if (imported.profiles.isNotEmpty() && scaleProfileStore != null)
  scaleProfileStore.replaceAll(imported.profiles)`) is never entered. The
  import test (`ConfigViewModelTest.kt:576`) builds a `PortableSettings` at
  `:587-599` that omits `profiles`, so it defaults to empty and the code always
  takes the **legacy `else` branch** at `:447-451`. `replaceAll` — the only
  path that restores a multi-profile registry — is dead in the test suite.

This matters because a settings backup is a user's disaster-recovery artifact
for exactly the thing that is expensive to recreate: `HANDOFF.md` records the
BF720 has **8 profile slots** (O-08) and re-registering burns one. A backup
that silently round-trips zero profiles is discovered only when the user needs
it.

*What a test needs to cover*: add `scaleProfileStore: ScaleProfileStore? =
FakeScaleProfileStore()` to the helper; then (a) export with two profiles, one
active, and assert both survive `decrypt` with the active flag intact; (b)
import a `PortableSettings` carrying profiles and assert `replaceAll` was called
and the legacy `consentStore.clear/save` branch was **not**; (c) assert the
`isNotEmpty()` guard — importing a backup with zero profiles onto a device that
*has* profiles must not wipe them.

### C2. `EncryptedScaleProfileStore` (`data/ScaleProfileStore.kt`, 128 new lines) has no test at all.

`ScaleProfileCodecTest` covers the codec; `FakeScaleProfileStore` covers the
*interface* for consumers. The real implementation — the encrypted registry that
holds every scale credential — is untested. Uncovered:

- **`readProfiles()`'s `runCatching { … }.getOrDefault(emptyList())`
  (`:119-121`).** A malformed stored blob yields an empty registry, and the very
  next `persist()` (`:113-117`) writes that empty list back over the stored
  value. No test proves this swallow is safe. A test needs to seed a corrupt
  `profiles_v2` string and assert what the store reports *and* what a subsequent
  `saveProfile` does to the pre-existing data.
- **The legacy-migration branch of `credentialFor(deviceAddress)`
  (`:51-63`).** The only path that reads the pre-v2 `ConsentStore` and
  synthesises a profile. `BasculeApplication.kt:89` invokes it purely for this
  side effect, so it runs on real upgrades. A test needs: legacy returns a
  credential → exactly one profile created, `active = true`, address uppercased
  — and, the case most likely to be wrong, that a **second** call does not
  create a duplicate.
- **All three `require()` guards** (`:97` index/code bounds, `:104` unknown
  `profileId`, `:109` more than one active). All three are message-less, so a
  triggered one surfaces as a bare `IllegalArgumentException`. `:109` is
  reachable from user-supplied input via settings import (C1's `replaceAll`).
- **`credentialFor` returning `null` when a profile for that address exists but
  is not active (`:49`)** — the load-bearing behaviour that makes multi-profile
  capture select the right scale. No test pins it.

### C3. `AndroidScaleRegistrar` (`ble/ScaleRegistrar.kt`, 148 new lines) has no test, including its entire user-facing failure-message mapping.

The `ScaleRegistrar` interface (`:33-35`) is a clean seam and `ConfigViewModel`
consumes it via an anonymous stub, but no `ScaleRegistrarTest` and no
`FakeScaleRegistrar` exist. Uncovered, in a class whose every output is a string
the user reads:

- **The five-way `when (outcome)` at `:108-114`.** Nothing asserts that
  `HandshakeFailed.detail` reaches the user verbatim rather than being flattened
  into generic copy — the exact regression `01-plan.md` §WP-07 records having
  already happened once ("the 'after 2 retries' message E6 eventually produced
  was actively false").
- **Both `SecurityException` catches** (`:61`, `:99`) — permission revoked
  mid-scan and mid-session, two distinct user messages, neither exercised.
- **The `forceNew` anonymous `ConsentStore` decorator (`:79-87`).** A test must
  pin that with `forceNew = true` the session sees `credentialFor() == null` (so
  it registers a *new* slot) while `save()` still writes through to the real
  store, and that `newlySavedCredential` — not a stale registry entry — decides
  `Success.scaleIndex`. Given O-08's 8-slot budget, a bug here is expensive and
  silent.
- **`findScale()`'s two `null` returns are conflated.** `:120` (no BLE scanner)
  and `:129-131` (`onScanFailed`, `errorCode` discarded) both surface at `:64`
  as *"No BF720 found. Wake the scale, stay nearby, and try again."* — as does a
  genuine 20 s timeout. Three different causes, one message, no test asserting
  the conflation is intended.

### C4. `ScaleViewModel` (`ui/ScaleViewModel.kt`, 109 new lines) is the only ViewModel in the diff with no test file.

`ConfigViewModelTest` (611 lines), `HistoryViewModelTest` (211) and
`ManualEntryViewModelTest` (259) exist. `ScaleViewModelTest` does not — and
`FakeScaleProfileStore`, `FakeConfigStore` and `FakeReadingDao`, everything
needed to write it, already exist in this diff. Uncovered branches, all
user-visible:

- **`setAutomaticCapture(true)` with no active profile (`:61-64`)** — sets a
  diagnostic and returns *without* persisting the flag. Nothing asserts the
  toggle stays off; a regression leaves the UI reading "on" while nothing is
  armed, i.e. silent capture failure — the app's core promise.
- **`setAutomaticCapture(true)` where `onArm()` returns `false` (`:67`)** — the
  flag has already been persisted as `true` at `:65`, so state and reality
  diverge. `ScaleScannerTest` covers `arm()` *returning* false; nothing covers
  the ViewModel reacting to it.
- **`setActive(profileId)` (`:81`)** — re-arms only when automatic capture is
  on, and **discards `onArm()`'s result entirely**. Switching the active scale
  can silently fail to re-arm, leaving the scanner filtered on the old address.
- **`rename()` (`:85-88`)** — trim / `take(40)` / empty-guard. The
  empty-after-trim early return does nothing, with no feedback, and
  `ScaleScreen.kt:76-78` closes the editor regardless, so the user sees the
  rename appear to succeed and then revert.

### C5. Room `MIGRATION_1_2` has zero coverage, and the test lane that could cover it does not exist.

`data/BasculeDatabase.kt:37-41` adds the first schema migration this project has
ever had (`ALTER TABLE readings ADD COLUMN scaleProfileId TEXT`, v1→v2), with
`schemas/…/2.json` exported alongside. Nothing executes it.

The KDoc at `BasculeDatabase.kt:11-15` explicitly promises
`fallbackToDestructiveMigration` is never enabled *because* it "would silently
delete undelivered readings". That promise is exactly what makes an untested
migration severe: a migration that throws leaves the app crash-looping on open
for any user who upgrades with pending rows, rather than degrading.

Compounding it, **the lane is declared but empty**: `app/build.gradle.kts:97-100`
declares `androidTestImplementation` for `room-testing`, `test-junit`,
`test-runner` and `work-testing`, and `defaultConfig` sets a
`testInstrumentationRunner` — but `app/src/androidTest/` **does not exist**
(`app/src/` contains only `main` and `test`). Four dependency declarations and a
runner config read as "instrumented tests exist" when none do.

*What a test needs to cover*: `MigrationTestHelper` opening a v1 DB, inserting a
reading row, running `MIGRATION_1_2`, asserting (a) the row survives with
original values, (b) `scaleProfileId` reads back `NULL`, (c) the post-migration
schema validates against `2.json`. `MigrationTestHelper` is instrumented-only,
so this is **the highest-severity single item gated behind the deferred Task
6** rather than an independent new gap — but "deps declared, directory absent"
means Task 6 is further from done than "just add a CI job".

---

## MEDIUM

### C6. A reading captured before the server URL is configured is marked `FAILED_PERMANENT` with a message that misattributes the cause — and no test covers the chain.

Three untested links compose into one user-visible failure:

1. `RuntimeApiFactory.kt:16` — `config.baseUrl.first().orEmpty()`. An unset base
   URL becomes `""`. **`RuntimeApiFactory` has no test file at all.**
2. `VitalForgeHttpClient` `resolve()` returns `null` for `""`, so
   `submitReading` returns `SubmitResult.PermanentRejection(0, "base URL is not
   a valid http(s) URL")` (`:69-70`). `VitalForgeHttpClientTest`'s `client(...)`
   helper takes a `baseUrl` parameter (`:48`) that **no test ever overrides** —
   every test points at a live `MockWebServer`, so the blank/invalid-URL branch
   is never taken.
3. `DeliveryDrainer.applySubmitResult` (`:90-98`) maps `PermanentRejection` to
   `ReadingStatus.FAILED_PERMANENT` with `lastError = "server rejected reading
   (0)"`. `DeliveryDrainerTest.aPermanentRejectionFailsTheRowWithoutRequestingRetry`
   (`:80`) only ever supplies `PermanentRejection(422, "bad payload")`.

The row is recoverable — `HistoryScreen.kt:173` offers a Retry button for
`FAILED_PERMANENT` — so this is not data loss, which is why it sits in MEDIUM
rather than HIGH. It is: a local configuration error reported to the user as
*the server rejecting the reading*, with no automatic retry and manual,
per-row recovery. `httpCode = 0` is the tell that no request was ever sent, and
nothing surfaces it.

*What a test needs to cover*: `RuntimeApiFactory.create()` with an unset base
URL; `VitalForgeHttpClient` constructed with `""` and with a non-URL string,
asserting the `PermanentRejection(0, …)` shape; and a `DeliveryDrainer` test
that a `PermanentRejection` with `httpCode == 0` is distinguishable from a real
server rejection.

### C7. Zero Compose UI-test infrastructure exists, in any configuration.
No `compose-ui-test-junit4` or `ui-test-manifest` dependency appears in
`gradle/libs.versions.toml` or `app/build.gradle.kts` in **any** configuration,
so ~1,240 new lines of Compose (`ConfigScreen.kt` 787, `HistoryScreen.kt` 255,
`ScaleScreen.kt` 107, `ManualEntryScreen.kt` 91) have no test harness at all.

This *may* be separable from the deferred Task 6: Robolectric is already in
`testImplementation` and `testOptions.unitTests.isIncludeAndroidResources =
true` is already set (`app/build.gradle.kts:44-48`), which are the usual
prerequisites for running `createComposeRule()` on the JVM. **Unverified**,
though — nothing here establishes that `createComposeRule()` actually works at
this AGP 9.3.1 / Compose BOM / Robolectric 4.16.1 combination, and two signals
counsel caution: `BootReceiverTest` is pinned to `@Config(sdk = [34])` against
`compileSdk = 37`, and this project has already hit Robolectric **native
library loading** failures (`UnsatisfiedLinkError`, per the testing-completeness
report) — the same class of dependency the Compose test harness pulls in. Spike
it before committing to the JVM lane; if the spike fails, this folds back into
Task 6 rather than standing alone.

Highest-value untested states, whichever lane ends up carrying them:
`ConnectionTestResultText`'s 4-way `when` (`ConfigScreen.kt:258-266`);
`RegisteredScaleSection`'s 5-way registration `when` (`:512-520`) plus its three
action-row states (`:525-546`); `LoginEditForm`'s error and in-flight states
(`:472`, `:481-486`); `HistoryScreen`'s three banners and empty state
(`:62`, `:65`, `:68-70`, `:74`); `ScaleScreen`'s empty-profiles state (`:66`)
and its diagnostic line (`:52`) — the screen's *only* error surface, fed by the
two untested `ScaleViewModel` paths in C4.

### C8. Real logic is trapped inside Compose files where no JVM test can reach it.
Four pure, non-`@Composable` helpers with genuine logic are `private` in
Compose files, and one predicate is inlined into a composable body:

- **`InputStream.readSettingsBackup` (`ConfigScreen.kt:730-742`)** — the
  `require(total <= SettingsBackupCodec.MAX_BACKUP_BYTES)` size cap on
  **untrusted user-selected file input**. Untested. Worse, the `runCatching` at
  `:668-675` swallows its message, so "Settings backup is too large" reaches the
  user as *"Could not read the selected file."* A test needs a stream just over
  and just under the cap, and must assert the size-cap failure is
  distinguishable from an unreadable file.
- **`formatRelativeAge` (`HistoryScreen.kt:243-252`)** — threshold formatting
  across 1 min / 60 min / 24 h boundaries, feeding both the backlog banner
  (`:70`) and every row's age line (`:160`). Pure, JVM-testable, untested.
- **`formatWeight` (`HistoryScreen.kt:237-239`)** — falls back to kilograms when
  the persisted `displayUnit` string matches no `WeightUnit`, so a stored pounds
  row with a corrupt unit renders a **wrong number**, not an error. Untested.
- **`formatTime` (`ScaleScreen.kt:107`)** — locale/TZ-dependent, two
  user-visible fields.
- **Passphrase validity (`ConfigScreen.kt:752-753`)** — `length >=
  MIN_PASSPHRASE_LENGTH && (!confirmPassphrase || passphrase == confirmation)`.
  The app's only passphrase validation, inlined into a composable body, so it is
  not a function any test can call.

Extracting these to testable top-level functions is the cheaper half of C7.

### C9. `ConfigViewModel.linkExistingScale`'s three validation failure branches are untested.
Only the happy path is covered
(`linkingExistingScaleRestoresMappingWithoutRunningRegistrar`,
`ConfigViewModelTest.kt:536`). Untested: the address-regex rejection
(`ConfigViewModel.kt:384`), the scale-index range rejection (`:386`), and the
consent-code range rejection (`:390`) — including the `toIntOrNull()`-returns-
null case that falls through to the range check. This is the manual-entry escape
hatch for restoring a scale mapping without burning a slot; a wrong bound here
either blocks a valid mapping or writes an invalid credential.

### C10. `ConfigViewModel.startScaleRegistration`'s failure mapping and re-entrancy guard are untested.
`scaleRegistrationSurfacesSuccess` (`ConfigViewModelTest.kt:512`) covers only
`ScaleRegistrationResult.Success`. Untested: the `Failure` → `Failure(message)`
mapping (`:373`), the phase callback producing `Scanning` then `Connecting`
(`:363-366`) as observable intermediate states, and the busy guard at `:351-355`
— the one that stops a second tap firing a second registration and burning a
second slot. Note `testConnection` and `login` both *have* explicit second-tap
tests (`:342`, `:479`); this third guard, protecting the most expensive
operation of the three, does not.

### C11. `BootReceiver` has one happy-path test; an `arm()` that throws is untested.
`BootReceiverTest.kt` (37 lines, 1 test) proves `onReceive` calls the injected
`arm` and waits for it. Nothing covers `arm` throwing — a `SecurityException`
from a scan permission revoked across a reboot is the realistic case.

`BootReceiver.kt:28-30` launches on a bare `CoroutineScope(Dispatchers.IO)` with
no `CoroutineExceptionHandler`. The `finally` is inside the coroutine body, so
`pending.finish()` does run on the throwing path — that part is already
guaranteed by the code and needs no test. What is untested is where the
exception goes: with no handler on that scope it reaches the thread's default
uncaught handler, from a `BOOT_COMPLETED` broadcast.

*What a test needs to cover*: `arm = { throw SecurityException() }`, asserting
what the receiver does with it — i.e. pinning whether a failed arm at boot is
swallowed, handled, or allowed to propagate. Any of the three may be the
intended design; none is currently recorded anywhere.

### C12. `SettingsBackupCodec`'s version-downgrade and malformed-profiles paths are untested.
`SettingsBackupCodecTest` (67 lines, 4 tests) covers round-trip, plaintext
absence, wrong passphrase and short passphrase. Untested:

- **`version >= 2` vs the `else -> emptyList()` branch (`:108-112`)** —
  decoding a **v1 backup**, the backward-compatibility path for a user restoring
  a file written by an earlier build. Nothing exercises `version < 2`.
- **The `.orEmpty()` on `(obj["profiles"] as? JsonArray)` (`:109`)** — a v2
  backup whose `profiles` key is present but not a `JsonArray` imports **zero**
  profiles, indistinguishable from a legitimately empty list. Every other field
  in the same function uses `require(...)` and fails loudly; this one does not,
  and no test pins which behaviour is intended.

### C13. `recentReadings` silently degrades to "zero remote readings", disabling dedup, with no test.
`VitalForgeHttpClient.kt:177-182`'s `mapNotNull` drops any entry missing
`weight_kg` or `captured_at`. A well-formed JSON array whose entries have
renamed fields therefore yields `RecentResult.Readings(emptyList())` — reported
as a **successful** dedup check that found nothing, so
`DeliveryDrainer.isRemoteDuplicate` (`:67-71`) is always false and every reading
is re-posted. This is distinct from `Unavailable`, which callers correctly treat
as "check failed". `VitalForgeHttpClientTest` covers a well-formed array
(`:240`) and `"not json at all"` (`:263`) but not the middle case: valid JSON,
wrong field names. Given A6 (VitalForge is not yet idempotent on `client_id`)
this is the code path that decides whether replay duplicates Garmin history.

### C14. `HistoryViewModel`'s drain trigger and three state flags are unasserted.
Beyond the null-`deliveryTrigger` blind spot (theme, above): `confirm`/`retry`
must trigger a drain and `decline` must **not** (`HistoryViewModel.kt:97` — the
guard and its negative are what discriminate a correct implementation from an
unconditional call). Also unasserted: `hasFailedPermanent` (`:60`) — the
`FAILED_PERMANENT` fixture exists in `heldConfirmRowsRankAboveAllOthers` but
only `hasBlockedAuth` is ever read, so a copy-paste of the wrong predicate into
`:60` passes the suite while `HistoryScreen.kt:65`'s banner never fires; the
`minOfOrNull` backlog age (`:61-63`), tested with exactly one PENDING row so min
/ max / first-inserted are indistinguishable; and `retryEpochMillis = now`
(`:92`), never asserted despite `nowMillis` being injected for that purpose.

### C15. `ManualEntryViewModel`'s post-save reset and error-clearing are unasserted, and its clock is not injectable.
`_uiState.value = ManualEntryUiState(unit = state.unit)` (`:124`) — nothing
asserts `weightText` is cleared, `isSaving` returns false, or that `unit`
survives; a regression to plain `ManualEntryUiState()` would silently reset the
user's pounds selection to kilograms and pass every current test. `errorMessage
= null` on text change (`:69`) is never observed independently of a save.
`isSaving == true` (`:120`) — the re-entrancy test (`:44`) proves the guard
works but never reads the flag that actually disables the field and swaps the
button label. Root cause of the timestamp gaps: `:91` calls
`System.currentTimeMillis()` directly, unlike `HistoryViewModel.kt:43` which
injects `nowMillis: () -> Long` — injecting a clock is the prerequisite for
asserting `capturedAtMillis`/`retryEpochMillis` at all.

Also worth flagging: the test at `ManualEntryViewModelTest.kt:191-216` carries a
docstring claiming it exercises `fromKilograms`'s `BigDecimal.setScale(2,
HALF_UP)` rounding at the pounds boundary, but uses `"44"` and `"662"` — values
well clear of the ~44.09 / ~661.39 rounded bounds. The test does not verify what
its own comment says it verifies.

### C16. `AndroidGattTransport`'s silent no-ops misreport as timeouts, and the class has no test.
`write()` (`:56-58`) and `subscribe()` (`:80-82`) return early when the
characteristic is not found, emitting **no** `TransportEvent`. `GattSession`'s
`awaitWriteComplete` / `awaitSubscription` therefore block to their timeout and
report "no ack" / `Failed` for what was really "never sent". The contrast is
local and visible: `:84` and `:89`, immediately below the silent return, *do*
emit a synthetic status. `onDescriptorWrite` (`:217`) discards unmatched
descriptor callbacks the same way. No `AndroidGattTransportTest` exists.
Robolectric can shadow `BluetoothGatt`, so a test asserting "a missing
characteristic emits a distinguishable failure event, not silence" is reachable
in the JVM lane.

---

## LOW

- **L1.** `ConfigStore.kt:56` and `:59-62` — `runCatching { WeightUnit.valueOf(it)
  }.getOrNull() ?: KILOGRAMS` and the same for `ContractVersion`. A corrupted or
  renamed persisted value silently reverts to the default; for
  `ContractVersion` that means a user configured for V2 downgrades to
  `V1_WEIGHT_ONLY` and every body-composition field silently stops being
  delivered. No `ConfigStoreTest` exists.
- **L2.** `BasculeApplication.kt:90` — `runCatching` with no failure branch
  around `startForegroundService`. On Android 12+ a
  `ForegroundServiceStartNotAllowedException` at boot means always-on bridging
  never starts, invisibly. No `BasculeApplication` test exists (and it is hard
  to write — noted as a gap, not a demand).
- **L3.** `ScaleScreen` has no loading state at all — it renders
  `ScaleUiState()` defaults during the initial `stateIn` emission, so an empty
  profile list briefly shows the "no profiles" empty state (`:66`) before real
  data arrives. No test, and no design note saying this is intended.
- **L4.** `ScaleScreen.kt:52`'s diagnostic line is the only surface for both
  `ScaleViewModel` error strings, and it is never cleared on navigation away —
  untested either way.
- **L5.** `V2Shaper` has no test. `V1ShaperTest` covers `V1Shaper`; both live in
  `network/ReadingPayloadShaper.kt`, and `V2Shaper`'s `putOptional` null-skip
  (`:67`) is exercised only indirectly through
  `VitalForgeHttpClientTest.v2OmitsNullBodyCompositionFields`.

---

## Recommended triage order

1. **Wire the three null optional dependencies into the test helpers** (theme,
   C1/C14/C15) — one small change to three helper functions, and it converts
   several "unreachable" gaps into ordinary "unwritten" ones. Do this first;
   everything else in `ui/` is cheaper afterwards.
2. **C4 `ScaleViewModelTest`** — every fake it needs already exists, and it
   covers the app's core promise (silent capture failure).
3. **C2 `EncryptedScaleProfileStoreTest`** and **C3 `ScaleRegistrarTest`** —
   the two largest wholly-untested production classes in the diff.
4. **C8's extractions** — pulling `readSettingsBackup`, `formatRelativeAge`,
   `formatWeight` and the passphrase predicate out of the Compose files makes
   them ordinary JVM tests, with no dependency on how C7's spike resolves.
5. **C6** — cheap (three small tests) and closes a misattributed user-facing
   error.
6. **C7's spike** — add the dependency and try one `createComposeRule()` test.
   If it runs, this is separable from Task 6; if it hits the native-loading
   problem, fold it in.
7. **C5** — bundle with the Task 6 go/no-go, and note that decision now also
   covers "create `app/src/androidTest/`", not just "add a CI job".

## Note for the reviewer

`ScaleSessionWorker.kt:39` discards every `IngestResult`, including
`Rejected("implausible weight")` — a reading dropped there leaves no trace in
the DB, no counter, and no log. It sits inside the already-known
`ScaleSessionWorkerTest` gap, so it is not counted as a new finding, but it is
the most consequential single line inside that known gap and is worth raising
when that gap is scheduled.
