# Bascule — session handoff (post-review fix pipeline, Phase 3)

Written 2026-08-28, replacing the 2026-08-25 version, which described a
7-way review as "6 of 7 dimensions never delivered." All 6 delivered since,
and the entire multi-batch fix pipeline this file describes happened after
that write. Read this first; don't re-derive state from git log archaeology.

## Where things actually are

- **Repo:** https://github.com/bearyjd/bascule (public, AGPL-3.0). Branch
  `vitalforge-connectivity-and-login`, against open **PR #1**. `main` is
  untouched by any of this.
- **Committed:** `294d09e` ("fix: resolve correctness, security, and
  performance findings from review") — 93 files, the CRITICAL/HIGH findings
  plus most MEDIUM/LOW from the full 6-dimension review, in four fix batches.
  **This commit is NOT pushed.** `git log origin/main..HEAD` / PR #1's diff
  does not reflect it yet.
- **Uncommitted, on top of `294d09e`, as of this write:** 13 files — a
  further fix pass responding to a *second* review round (see below), all
  verified: `detekt` 0 issues, **443/443 tests passing**. This needs a commit
  (and both commits need a push) before PR #1 reflects current state.
- **Test count: 443**, all green locally. Don't trust any older number
  written elsewhere in this repo's docs — it moved from 249 → 267 → 337 →
  375 → 377 → 429 → 443 across this session's fix batches.
- **Process doc:** `docs/prp/bascule-agent-prompt.md` governs phases/gates.
  **PRP:** `docs/prp/bascule-prp.md` governs requirements, wins on conflict.

## What actually happened this session (chronological)

1. **A 6-dimension parallel `/code-review`** (correctness, type safety,
   patterns, security, performance, completeness) ran against the full
   `main...HEAD` diff. All 6 delivered in full this time, written to
   `.claude/PRPs/reviews/pr-1-review-{dimension}.md`. Maintainability's
   pass from the *previous* session (`pr-1-review.md`, 8 HIGH/12 MEDIUM/10
   LOW) was already fixed by then — see item 2. The 6 new passes found
   roughly 150 more findings, headlined by:
   - **Correctness H1/H6**: a weigh-in was lost if any non-`Stable` frame
     arrived first (collapsed the 45s measurement window to 4s), and a
     buffered weight was discarded rather than flushed if the connection
     dropped mid-correlation-window.
   - **Performance CRITICAL**: `BridgeForegroundService` enqueued a full
     GATT connect/handshake session on *every* BLE advertisement — 2-10/sec
     while the scale was in range — with no cooldown.
   - **Security S1-S3**: a malformed settings backup could crash-loop the
     app permanently; importing settings silently drained the whole reading
     backlog to whatever host the backup pointed at; a Keystore fault was an
     unrecoverable launch crash.
2. **Four fix batches**, each independently verified green before the next
   started:
   - Batch 2 (parallel: `fix-session-core`, `fix-scan-lifecycle`,
     `fix-data-config`, `fix-delivery-layer`) — all CRITICAL/HIGH findings.
     337 → tests. Implemented the E8 single-reconnect behavior, made every
     `TransportEvent`/`DecodeEvent` dispatch exhaustive, fixed the GATT
     transport data race, added the BLE-advertisement enqueue cooldown, made
     `ScaleScanner.arm()` disarm before re-arming, fixed the silent-registry-
     destruction bugs in the backup codec and profile store, implemented the
     previously-unimplemented §3.4 per-row retry ladder and `Retry-After`
     handling.
   - Batch 3 (parallel: `fix-security-remainder`, `fix-test-coverage`,
     `fix-ui-patterns`) — remaining security MEDIUM/LOW, the three largest
     untested production classes (`ScaleProfileStore`, `AndroidScaleRegistrar`,
     `ScaleViewModel`), UI/ViewModel dispatcher and testability findings.
     → 375 tests.
   - **`/devils-advocate` round 1** on the batch-2/3 diff (adversarial
     dialogue review, done directly rather than via subagent). Found an ANR
     risk (`WorkManagerScaleSessionEnqueuer` blocking the calling thread —
     likely main — on a WorkManager query from `ScanBroadcastReceiver`) and a
     `hostOf()` port-comparison gap neither the 6-way review nor batch 2/3
     caught. Fixed directly. → 377 tests.
   - Batch 4 (parallel: `fix-outcome-mapping`, `fix-data-docs`,
     `fix-ui-remainder`) — the deliberately-deferred MEDIUM/LOW findings:
     `SessionOutcome.Completed` changed from `List<ScaleReading>` to
     `ScaleReading?` (the list could never legitimately hold >1 given
     `MAX_EMISSIONS_PER_SESSION = 1`), gave `AndroidGattTransport` real
     failure events instead of silent no-ops on a missing characteristic,
     extracted a shared `DedupPolicy.withinTolerance` so local/remote dedup
     literally cannot drift, amended three stale design docs, added Room
     indices. → 429 tests. **Committed as `294d09e`.**
3. **`/code-review` (Local Review Mode, adapted)** — since the working tree
   was clean post-commit, scoped to the one slice that had never had an
   independent look: the batch-4 diff plus the devil's-advocate-round-1
   fixes (nobody but the fixing agent/session had read that code). Dispatched
   fresh `code-reviewer` + `security-reviewer` agents in parallel.
   - **Quality review**: 1 HIGH, 3 MEDIUM. Most consequential:
     `AndroidScaleRegistrar`'s `forceNew` re-registration could report
     **`Success` carrying the OLD scale slot number** when the handshake
     completed without actually registering (`BeurerDecoder`'s `Complete`
     carries a null credential whenever `registered` is false) — the
     fallback line queried the real consent store directly, bypassing the
     wrapper meant to prevent exactly this.
   - **Security review**: 1 HIGH, 2 MEDIUM, 3 LOW, **REQUEST CHANGES**. The
     `hostOf()` same-host gate (from devil's-advocate round 1) only ever
     protected the one `unblockAuthRowsAndDrain()` call — the *existing*
     `PENDING` backlog and *every future capture* were never gated by
     anything, because the periodic drain re-reads the base URL and
     credential fresh on every run. A crafted settings backup could
     therefore silently and permanently redirect all present and future
     weight/body-composition data to an attacker's server, or suppress
     delivery to the real one via the remote-dedup path.
4. **A second independent `/devils-advocate` round** (round 2, also direct,
   scoped the same way) found two more issues neither review dimension nor
   round 1 caught: `BasculeApplication`'s boot-time
   `ForegroundServiceStartNotAllowedException` guard existed on the boot
   path but not the interactive-toggle path (`ScaleViewModel.
   setAlwaysOnBridging` → `AndroidBridgeServiceController.start()`, an
   uncaught throw there crashes the process from a plain UI tap), and
   `ManualEntryViewModel.save()`'s post-save reset used the *pre-save*
   captured display unit rather than the live one, silently reverting a
   unit change that landed mid-save.
5. **All of the above fixed directly** (not dispatched — the fixes were
   intricate enough, especially the host-change security logic, to keep in
   one context). Notable design decision made while fixing the security
   HIGH: **"no host configured yet" (a fresh install / first restore) is
   deliberately NOT gated the same as "host silently changed"** — the first
   pass over this fix broke the ordinary first-time-restore flow (an
   existing test caught it) by treating "nothing configured" as
   automatically different from any imported host. The corrected rule:
   `keepsSameHost = currentHost == null || currentHost == importedHost` —
   only an *existing, real* host now gates on a mismatch. On an actual host
   change, the fix (a) parks the entire existing `PENDING` backlog behind
   `BLOCKED_AUTH` and (b) does **not** auto-install the backup's own
   credential — the user must take an explicit, visible Login/Save-token
   action before anything drains to the new host again. → 443 tests.
   Verification for this round is **not yet committed** — see "Where things
   are" above.

## Two process mistakes made and caught this session, worth not repeating

- **Piping `./gradlew ... | tail -N` masks the real exit code.** The `tail`
  in the pipe succeeds even when gradle fails, so `[exited with code 0]`
  annotations on a piped command are worthless as a pass/fail signal — read
  the actual text for `BUILD SUCCESSFUL`/`BUILD FAILED`, or better, redirect
  to a file first (`... > /tmp/log 2>&1; echo "EXIT=$?"`) and check that
  captured code. This cost one full extra round-trip mid-session: a
  genuinely broken compile was read as "429 tests, 0 failures" because that
  was stale XML from the *previous* successful run, and the failing
  `compileDebugKotlin` never got to overwrite it.
- **A Kotlin trailing lambda always binds to the *last* parameter**, even
  when an earlier functional-type parameter is the "obvious" target and the
  true last parameter has a default. Adding an injectable `starter: () ->
  Unit = { ... }` parameter *after* an existing `onStartResult: (Boolean) ->
  Unit` parameter silently broke the call site's trailing-lambda syntax —
  the lambda rebound to `starter`, and the compiler errors this produced
  (`Unresolved reference 'not'`, `Cannot infer type for value parameter`)
  did not obviously point at "wrong parameter bound." When adding a
  defaulted functional parameter after an existing one, either use a named
  argument at every call site or put the new parameter earlier.

## Known open items (don't silently resolve these — they're tracked on purpose)

- **A6 escalation to JD, not yet sent**: v2 replay requires VitalForge to be
  idempotent on `client_id` (or `captured_at` + weight tolerance for pre-v2
  rows) or replay duplicates Garmin history. `01-plan.md` §6 tracks it.
- **`androidx.security:security-crypto` 1.1.0's `EncryptedSharedPreferences`
  is deprecated** by the platform. Both the VitalForge token and scale
  consent codes use it. Pick a successor before v1 ships.
- **O-08 residues**: the recovery path for a full 8-slot scale registry
  (read `2A9A` / SIG delete-user op) is unexplored.
- **V2 contract field names** deliberately unfilled — pinned from
  VitalForge's Track A contract doc when it lands.
- **C16 residual (flagged by its own fixing agent, not silently closed)**:
  `AndroidGattTransport.write()` now emits a real failure event on a missing
  characteristic instead of no-op'ing, but `GattSession.awaitWriteComplete`
  and the handshake path both discard `WriteComplete.status` entirely, so
  the fix improved observability without changing session behavior yet.
  Needs its own scoped fix — making the session actually fail on non-success
  status would also newly surface two *pre-existing* silently-swallowed
  `-1` emissions with real blast radius into handshake retry, uncharacterized.
- **L1 residual**: `ConfigStore`'s `StoredEnum`/`readStoredEnum` correctly
  classifies a corrupted persisted enum as `Unreadable` rather than
  silently defaulting, but nothing consumes the `Unreadable` case yet — a
  corrupted `ContractVersion` still silently downgrades a V2 user to
  `V1_WEIGHT_ONLY`. Needs a `ConfigStore` interface member (mirroring
  `ScaleProfileStore.readFailure`, which *is* fully wired end-to-end) plus a
  matching `FakeConfigStore` update.
- **Architectural findings deliberately not fixed this session** (each
  needs a real design decision, not a mechanical patch — see
  `pr-1-review-patterns.md` for full detail): P8 (four config/credential
  stores, three incompatible reactivity idioms), P16 (`ScaleProfileStore`
  inherits `ConsentStore`, inverting the data-layer/BLE-layer dependency
  direction), P17 (`ScaleProfileStore`/`ConfigStore` differing persistence
  idioms), P25's behavioral half (`ManualEntry` is simultaneously a nav-bar
  destination and a FAB target with two different back-stack contracts).
- **C5/C7 deliberately deferred**: the project's first Room schema
  migration (`MIGRATION_2_3`, added this session) has zero coverage, and
  Compose has zero UI-test infrastructure. Both need their own instrumented-
  test go/no-go decision — this repo has zero `app/src/androidTest/`
  infrastructure today, and adding an emulator CI step is a real cost/
  flakiness tradeoff, not a rider on any of the above fixes.
- **A real environment constraint**: this sandbox has a shared, per-user
  disk quota and a shared shell that other concurrent sessions on the same
  machine can exhaust unpredictably (a full Bash outage — every command
  returning exit 1 with zero output — happened mid-session and resolved on
  its own). If a local build/test run fails inexplicably, don't assume your
  change is broken before re-running.

## A real discovery worth knowing before touching `GattSession` measurement code

`MeasurementCorrelator.MAX_EMISSIONS_PER_SESSION = 1` is a **permanent
one-shot latch** — this is now reflected directly in `SessionOutcome.
Completed`'s type (`reading: ScaleReading?`, not a list) rather than left as
an unenforced convention. See `ScaleRegistrar.kt`'s `registrationCredential`
function for the one place this session found the convention had already
been violated (the `forceNew` stale-credential bug above).

## Read these five files, in this order, before touching decoder/handshake code

1. `docs/prp/00-design.md` — the design. §2.6, §2.7, §3.1, §9 carry
   provisional banners, superseded by #3 below.
2. `docs/prp/decisions.md` — the ADRs (ADR-006 now has a superseded-by note
   pointing at `04-scale-admin-and-automation-plan.md`, added this session).
   ADR-007 is the one that matters most: the BF720 speaks the standard
   Bluetooth SIG Weight/Body-Composition/User-Data profile, not a
   proprietary opcode protocol.
3. `docs/prp/02-interface-revision.md` — the actual revised
   `ScaleDecoder`/`DecodeEvent`/`GattOp`/`GattTransport`/`ScaleReading`
   design (updated this session to match the real interface — it had
   drifted, six members undocumented). Supersedes `00-design.md`.
4. `docs/prp/02-phase2-dispositions.md` — a Phase 2 devil's-advocate pass.
5. `docs/prp/01-plan.md` — the 31 original work packages, as amended.

`docs/prp/03-hardware-validation.md` has the raw captured bytes and their
decode. `docs/prp/04-scale-admin-and-automation-plan.md` covers the
scale-admin/hands-off-capture work.

## Hardware

Physical unit: Beurer BF720, MAC `E7:DB:51:F1:36:91`, already registered
with the app-chosen identity **scaleIndex=2, consent code 1234** — reusable,
don't re-register blindly, it burns one of 8 scale profile slots.

Test device: a Pixel 9 Pro Fold, adb serial `4A111FDKD0000C` — **not
connected in this environment**; this sandbox has no BLE radio at all. No
on-device live verification has happened since the last hardware capture
session, and none of this session's fixes have been hardware-validated.
Reconnect via USB before any hardware checkpoint work.

`tools/hw-probe/` is a throwaway diagnostic app for exactly that — see
`tools/hw-probe/README.md`.

## Process notes for whoever (whatever) continues this

- **Before doing anything else: commit the uncommitted work (13 files, all
  verified green) and push both it and `294d09e`.** PR #1 currently
  reflects neither.
- Branch per phase/work-package, `--no-ff` merge with a gate-check message,
  push — same pattern as Phases 0-2.
- Fresh subagents for devil's-advocate/review passes must not share context
  with whatever produced the thing they're reviewing — this session ran two
  independent devil's-advocate rounds directly (not via subagent) plus one
  fresh-subagent review round, and each of the three caught something the
  previous ones missed. **Don't treat one review pass as sufficient once a
  large fix wave has landed — the fixes themselves are new, unreviewed
  surface area.**
- **If you dispatch parallel review/analysis subagents and need their
  findings back, have them write to a file the orchestrating session
  reads, not just their final chat message.** This was a real, repeated
  problem in the *previous* session (6 of 7 dimension reports lost) — this
  session's agents were explicitly instructed to write to
  `.claude/PRPs/reviews/pr-1-review-{dimension}.md` and all 6 delivered.
- Trace adversarial-review fixes yourself before trusting them closed. This
  session's own security fix needed a second pass after an existing test
  caught a real regression the first version introduced (see "Two process
  mistakes" above, and the `keepsSameHost` fresh-install carve-out) — a
  security fix that breaks the most common legitimate use of a feature is
  not a fix that should ship on the first draft.
