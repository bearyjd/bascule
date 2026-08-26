# Bascule — session handoff (Phase 3 well underway)

Written 2026-08-25, replacing the 2026-08-22/24 versions of this file, whose
"deliberately stubbed" lists and test counts are now stale. Read this first.
Don't re-derive state from git log archaeology — this file plus the two
`.claude/PRPs/` artifacts it points at cover what changed since 08-22.

## Where things actually are

- **Repo:** https://github.com/bearyjd/bascule (public, AGPL-3.0). All work
  described below lives on branch `vitalforge-connectivity-and-login`,
  fully pushed, against open **PR #1**. `main` itself is untouched by any of
  this — don't assume `main` reflects current Phase 3 state.
- **CI is green** on the PR branch (last confirmed run:
  `gh run view --repo bearyjd/bascule` on the head commit below). The old
  split contract-test lane (`-Pbascule.contractTests`) is gone — removed in
  commit `2940226` once `ScaleSessionContractTest` started passing 4/4;
  contract tests now run in the ordinary `testDebugUnitTest` task and CI's
  single `Unit tests` step.
- **Test count: 249** as of the current HEAD (`f99fb93`), all green in CI.
  Don't trust any older number written elsewhere in this repo's docs.
- **Process doc:** `docs/prp/bascule-agent-prompt.md` governs phases/gates.
  **PRP:** `docs/prp/bascule-prp.md` governs requirements, and wins on conflict.

## What actually happened this session (chronological, so you can see the shape of it)

1. **Hands-off scale capture + administration shipped** (`b661498`,
   `1b7773d`, `b9b1839`, pre-existing this session's start): scale
   registration, encrypted multi-profile registry, `ScaleOperationCoordinator`,
   background wake scanning, WorkManager delivery, the **Scale** tab. Every
   class this repo's earlier docs (`HANDOFF.md` 08-22/08-24 versions,
   `docs/prp/01-plan.md`) called "deliberately stubbed" is now real,
   implemented code — `grep -r "TODO()" app/src/main/kotlin/` returns nothing.
2. **Devil's-advocate review, then fixes** (`a7bb664`, `32fd6b1`): found and
   fixed 5 real issues in that work — a silently-discarded second `Stable`
   reading in `GattSession.finishEmission` (now counted, see caveat below), a
   non-atomic `SettingsBackupCodec`/`ConfigViewModel.importSettings` partial-write
   hazard, an N+1 network call in `DeliveryWorker` (extracted to
   `DeliveryDrainer`), an unenforced `ScaleOperationCoordinator` purpose
   parameter, and a footgun default constructor arg on `AndroidScaleRegistrar`.
   Added test coverage for all of it (`ReadingIngestorTest`,
   `ReadingMapperTest`, `ScaleProfileCodecTest`, `DeliveryDrainerTest`) —
   232 tests at that point.
3. **Phase 3 CI cleanup** (`2940226`): removed the now-pointless split
   contract-test lane, see above.
4. **A `/prp-plan` for the remaining testing gap** (`c7dd918`,
   `.claude/PRPs/plans/scale-admin-testing-completeness.plan.md`) —
   discovered while planning that `docs/prp/01-plan.md` WP-10 named a
   `GattSessionMeasureTest` with specific edge cases (E7/E8/E9/E17) that was
   never actually written.
5. **`/prp-implement` of that plan** (`352199f` through `f99fb93`) — see
   `.claude/PRPs/reports/scale-admin-testing-completeness-report.md` for full
   detail. Short version: 249 tests now, first real Robolectric usage in this
   codebase, a new `ScaleSessionEnqueuer` seam (mirrors the existing
   `DeliveryTrigger`/`DeliveryDrainer` split), and several things **deliberately
   left undone** — see "Open items from the testing-completeness plan" below.
6. **A 7-way parallel `/code-review`** was dispatched against the full branch
   diff, split by dimension (correctness, type safety, patterns, security,
   performance, completeness, maintainability). **Only the maintainability
   pass has actually delivered its findings** — written in full to
   `.claude/PRPs/reviews/pr-1-review.md` (8 HIGH / 12 MEDIUM / 10 LOW; the
   single biggest theme is that this branch introduced all 15 file-level
   detekt suppressions that exist in the repo, several of which are hiding
   other real findings). **The other six dimension passes never delivered**
   before this handoff was written — they are in-process subagents tied to
   the session that spawned them, not resumable from a fresh session. See
   that file's "Status" section for what to do about it.

## Open items from the testing-completeness plan (see the report for full detail)

- **`BridgeForegroundServiceTest` (Task 5c) was never written.** It needs the
  same class of seam `BootReceiver` got (it also casts
  `application as BasculeApplication`) plus `Robolectric.buildService(...)`
  lifecycle testing not yet exercised anywhere in this repo.
- **`ScaleSessionWorkerTest` only covers the branches before
  `applicationContext as BasculeApplication`** (staleness abort, permission
  check). The adapter-off retry, profile-mismatch, and outcome-mapping
  branches all run after that cast and need either a `WorkerFactory` or
  `open`/overridable `BasculeApplication` properties to reach — a materially
  bigger decision than the `ScaleSessionEnqueuer` extraction, deliberately
  not done unilaterally.
- **Task 6 (a `connectedDebugAndroidTest` CI job)** remains deliberately
  deferred as its own decision — this repo has zero instrumented-test
  infrastructure (`app/src/androidTest/` doesn't exist), and adding an
  emulator step to CI is a real cost/flakiness tradeoff that deserves its own
  explicit go/no-go, not a rider on a testing-gap fix.
- **A real environment constraint, not a code issue**: this sandbox has a
  shared, per-user disk quota that other concurrent Claude Code sessions on
  the same machine can (and did, during this session) exhaust — Robolectric's
  native library loading fails with `UnsatisfiedLinkError`/`Disk quota
  exceeded` when that happens, unpredictably, to tests that passed moments
  earlier with no code change. If you hit this: don't assume your test is
  broken — re-run in CI (an isolated environment) before trusting a local
  failure. The one safe local mitigation is clearing this project's own
  `~/.gradle/caches` (fully regenerable); do not delete other sessions' files
  under `/tmp/claude-1000/` without asking.

## A real discovery worth knowing before touching `GattSession` measurement code

`MeasurementCorrelator.MAX_EMISSIONS_PER_SESSION = 1`
(`ble/decoders/MeasurementCorrelator.kt:166`) is a **permanent one-shot
latch** — once a session emits one `Stable` reading, a second `Stable` decode
is structurally impossible for the rest of that session; a later frame just
returns `Ignored` and increments the correlator's own internal
`unpairableFramesDropped`/`duplicateFramesSuppressed` counters, which are
**not currently wired to `DiagnosticsCounters` at all**. This means
`GattSession.finishEmission`'s `DUPLICATE_STABLE_SUPPRESSED` counter (added
this session for an earlier review finding) can never actually fire in the
scenario it was added for — it's not wrong, just unreachable by that specific
mechanism. See `GattSessionMeasureTest.aSecondIndependentPairDuringPostEmissionIdleIsDroppedNotEmittedTwice`'s
KDoc. Wiring the correlator's real counters to `DiagnosticsCounters` would be
a genuinely valuable, currently-untracked follow-up.

## Read these five files, in this order, before touching decoder/handshake code

1. `docs/prp/00-design.md` — the design. **§2.6, §2.7, §3.1, §9 carry
   provisional banners** — pre-hardware version, superseded by #3 below.
2. `docs/prp/decisions.md` — 8 ADRs. **ADR-007** is the one that matters most:
   live hardware capture found the BF720 speaks the standard Bluetooth SIG
   Weight/Body-Composition/User-Data profile gated behind a User Control
   Point register+consent handshake, not the proprietary opcode protocol
   openScale's older wiki page documents.
3. `docs/prp/02-interface-revision.md` — the actual revised
   `ScaleDecoder`/`DecodeEvent`/`GattOp`/`GattTransport`/`ScaleReading`
   design, implemented in `app/`. Supersedes `00-design.md` for the
   decoder/handshake/schema.
4. `docs/prp/02-phase2-dispositions.md` — records what a Phase 2
   devil's-advocate pass found and fixed. **Read before trusting any specific
   number/status/claim in `00-design.md`/`01-plan.md`.**
5. `docs/prp/01-plan.md` — the 31 work packages, as amended. Note its own
   CI/PHONE/SCALE bucket taxonomy (§0) — reused by the testing-completeness
   plan; don't invent a new classification if you're extending test coverage.

`docs/prp/03-hardware-validation.md` has the raw captured bytes and their
decode, if you need ground truth for a test fixture.
`docs/prp/04-scale-admin-and-automation-plan.md` covers the scale-admin/
hands-off-capture work item 6 above added on top of the original WP list.

## Hardware

Physical unit: Beurer BF720, MAC `E7:DB:51:F1:36:91`, already registered with
the app-chosen identity **scaleIndex=2, consent code 1234** — re-usable,
don't re-register blindly, it burns one of 8 scale profile slots (O-08).

Test device: a Pixel 9 Pro Fold, adb serial `4A111FDKD0000C` — **not
connected in this environment** (`adb devices` returns empty; this sandbox
has no BLE radio at all, checked). No on-device live verification has
happened since the last hardware capture session. Reconnect via USB before
any hardware checkpoint work.

`tools/hw-probe/` is a throwaway, out-of-band diagnostic app (separate Gradle
project) for exactly that: `adb shell am broadcast -a
com.ventouxlabs.hwprobe.CMD --es cmd <scan|connect|synctime|listusers|register|consent|reset>`
— see `tools/hw-probe/README.md`.

## Known open items (don't silently resolve these — they're tracked on purpose)

- **A6 escalation to JD, not yet sent**: v2 replay requires VitalForge to be
  idempotent on `client_id` (or `captured_at` + weight tolerance for pre-v2
  rows) or replay duplicates Garmin history. `01-plan.md` §6 tracks it.
  Escalate before WP-22's replay-migration worker is enabled.
- **`androidx.security:security-crypto` 1.1.0's `EncryptedSharedPreferences`
  is deprecated** by the platform. Both the VitalForge token and scale
  consent codes use it. Not blocking, but pick a successor (DataStore +
  app-managed keystore key, or raw Keystore) before v1 ships — migrating a
  stored credential is a data migration, not a refactor.
- **O-08 residues**: failed-registration behavior when the scale's 8 profile
  slots are full has a named outcome and counter, but the recovery path
  (read `2A9A` / SIG delete-user op) is unexplored.
- **V2 contract field names** deliberately unfilled — pinned from
  VitalForge's Track A contract doc when it lands, not invented here.
- **`WorkManagerDeliveryTrigger` is dead code** (see the maintainability
  review's H2) — decide whether to delete it or wire it; don't let a future
  change accidentally pick the wrong one of two diverging implementations.

## Process notes for whoever (whatever) continues this

- Branch per phase/work-package, `--no-ff` merge with a gate-check message,
  push — same pattern as Phases 0-2.
- Fresh subagents for devil's-advocate/review passes must not share context
  with whatever produced the thing they're reviewing.
- **If you dispatch parallel review/analysis subagents and need their
  findings back, have them write to a file the orchestrating session reads,
  not just their final chat message** — this session's 7-way `/code-review`
  lost 6 of 7 dimension reports to exactly this problem (in-process
  subagents whose reports never got collected before the session that spawned
  them would've ended); only maintainability's got through, on a retry, and
  even that took several nudges. Don't repeat it.
- Trace adversarial-review fixes yourself before trusting them closed — see
  the `MeasurementCorrelator` latch discovery above, which is exactly this
  pattern: a fix from an earlier review cycle (`DUPLICATE_STABLE_SUPPRESSED`)
  turned out to guard a condition that couldn't actually occur, and only
  hand-tracing the code (while writing a test for it) caught that.
