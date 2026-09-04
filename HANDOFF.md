# Bascule — session handoff (post-merge)

Written 2026-09-01, superseding the numbers in "Where things actually are"
below (still 08-29's: `main@1b6db33`, 524 tests, PR #1) and this file's own
08-31 claim that `ui-modernization`'s branches were already gone — they
weren't; see the housekeeping note below. **`main` is at `f358f99`**, 564
tests, detekt clean, independently re-verified. Local branch list is `main`
only — `ui-modernization` plus five other fully-merged local-only stragglers
(`hardware-evidence-milestone1`, `phase-0-design`, `phase-1-planning`,
`phase-2-validation`, `ui-material-design-wp23-24-25`) were all deleted in a
2026-09-01 housekeeping pass, confirmed zero commits ahead of `main` each
before deletion.

**Updated 2026-09-03**: A6 resolved and merged in `vitalforge`, WP-22
implemented on top of it (committed, `47c25cb`, 574 tests), then a
bmi/bmr/amr gap found and fixed on the `vitalforge` side (`vitalforge` PR
#40, **open, not yet merged** — see the dated section below). No further
Bascule-side changes needed for that last one; `V2Shaper.kt` already had the
right field names.

## 2026-09-03: bmi/bmr/amr had no home anywhere in VitalForge — found, fixed

Found while correcting a stale `HANDOFF.md` claim, not from a review or a
bug report: the "V2 contract field names" open item said field names were
"deliberately unfilled, pinned from VitalForge's Track A contract doc when
it lands" — but that doc was never going to land; the real contract is
whatever `vitalforge`'s actual code accepts, directly readable from the
sibling checkout at `~/Documents/vibe-code/vitalforge`. Checking it properly
(the same methodology A6 used) turned up a second landmine of the exact
same shape client_id had: `V2Shaper.kt` sends `bmi`, `bmr`, and `amr`
whenever a reading has them, but VitalForge's `WeightIn` had no fields for
them and `weight_log` had no columns. `extra="forbid"` meant any reading
carrying one would 422 the *entire* request — not just drop the extra
field — the moment V2 was ever selected.

Not live today (`V2_BODY_COMP` isn't selectable in Settings), but reachable
today via `ConfigViewModel.importSettings`, which accepts it without going
through the UI gate — so this wasn't purely theoretical housekeeping.

Fixed entirely on the `vitalforge` side (`vitalforge` PR #40,
`feat/weightin-bmi-bmr-amr`, **open, not merged**): `bmi`/`bmr`/`amr` added
to `WeightIn` and `weight_log`, folded into the existing `COMPOSITION_FIELDS`
machinery (enrichment, conflict-detection, and Garmin-repush-on-enrich all
work for these three for free), and wired into `push_weight`/
`add_body_composition`, which already supported them
(`bmi`/`basal_met`/`active_met`) — VitalForge's own wrapper just never
forwarded those three kwargs. `bmr`/`amr` are kcal/day on both sides, so no
unit conversion was needed. Bascule needed **no changes** — `V2Shaper.kt`
already had the correct field names; the gap was entirely server-side.
688 `vitalforge` tests (669 + 19 new/expanded), ruff clean.

**Corrected the record, not just the code**: the "V2 contract field names"
item in "Known open items" below was rewritten to reflect that this is
verified against real code, not pending a document that was never coming.

## 2026-09-03: WP-22 implemented — replay migration worker, not wired in

Continuation of the A6 resolution below: once VitalForge's side (`vitalforge`
PR #39) was reviewed, fixed, and merged, the natural next step was WP-22
itself — the Kotlin side that A6 had been blocking. New:
`delivery/ReplayEligibility.kt` (the two-clause + status-gate predicate from
`00-design.md` §4.4) and `delivery/ReplayMigrationWorker.kt` (the
`CoroutineWorker` shell, `applicationContext as BasculeApplication` like
every other worker in this repo — no DI framework here). `ReadingDao` gained
`sent()` and `requeueForReplay(ids, nowMillis)`; `ConfigStore` gained
`lastReplayMigrationContractVersion` so the worker knows whether it's already
run for the *current* contract version (not a boolean flag — a second
upgrade must run again).

**Deliberately not wired into any scheduling path.** Nothing calls
`WorkManager` for this worker anywhere in the app. `01-plan.md`'s WP-22
section already said enabling it is a Phase-5+ decision, and that's still
true after A6 — the residual gap (a row whose *original* delivery was itself
delayed past the dedup window has no reliable capture-time proxy for replay
to match against) is a real scope question, not something this session had
standing to decide unilaterally.

**Two of the plan's four named worker tests were deliberately not written
as literally named**, and the reasoning is recorded in `01-plan.md`'s WP-22
section, not just here: `isDisabledPendingIdempotencyEscalation` named a
hard gate that stopped existing once A6 resolved — keeping that name would
have meant either a dishonest test (asserting a disabled-flag that isn't
there) or a misleadingly-named one (testing something else under a stale
title). Renamed to `onlyEligibleRowsAreRequeued`.
`requeuedRowResetsRetryEpochAndAttemptCount` moved to `ReadingDaoSqlTest`
(renamed `requeueingForReplayResetsRetryEpochAndAttemptCountAndOnlyTouchesTheNamedRows`)
since it's a DAO-SQL-correctness question against real Room, the same
category every other test in that file covers — not a reason to pull
Robolectric into what's otherwise a set of plain-JUnit companion-function
tests in `ReplayMigrationWorkerTest`.

Every new guard mutation-tested by hand before committing (the status gate,
the `remoteDuplicate` gate, and the subset check in `ReplayEligibility.isEligible`
— broke each in turn, confirmed the right tests went red, reverted). 574
tests (565 + 9 new/moved), detekt clean.

## 2026-09-02: A6 resolved — client_id + captured_at idempotency (cross-repo)

Picked "continue open work" from the previous handoff's list of genuinely
open items and chose **A6** (`00-design.md` §4.4): whether VitalForge is
idempotent on `client_id`, which WP-22's replay path has been blocked on
since Phase 2. This had sat as "escalation to JD, not yet sent" for weeks —
today it actually got asked, and answered with evidence rather than
assumption, because `~/Documents/vibe-code/vitalforge` turned out to be a
sibling checkout of the actual server this session could read.

**What the evidence said, first pass:** VitalForge had no `client_id`
concept at all. `WeightIn`'s `extra="forbid"` meant `V2Shaper.kt`'s existing
`client_id` field (already on the wire, since `V2Shaper` was written before
this was checked) would 422 the *entire* v2 payload the instant it was sent
— worse than "not idempotent," an immediate `FAILED_PERMANENT` on every v2
delivery. Not live today only because `V2_BODY_COMP` isn't selectable in the
UI yet.

**First proposed fix was itself incomplete — caught by a second opinion
before writing any code.** Adding `client_id` alone only protects *future*
rows. WP-22 exists to replay the **backlog** of already-`SENT` v1 rows, none
of which will ever have a `client_id` — a replay of one of those would still
miss VitalForge's receipt-time-only dedup window and duplicate into Garmin.
Also checked and ruled out: Bascule never persisted VitalForge's returned
`row_id` from a successful delivery (`ReadingEntity` has no such column), so
there was no cheaper "match by previously-seen server id" path available
either.

**Full fix, chosen explicitly over the cheaper partial one:** `vitalforge`
PR #39 (`fix/a6-weight-client-id-idempotency`, https://github.com/bearyjd/vitalforge/pull/39)
adds both `client_id` (exact-identity match, checked before the dedup
window) and an optional `captured_at` the window anchors on instead of
receipt time. `ruff` clean, 664 tests (646 pre-existing + 18 new), all
passing. Committed and pushed on that repo's own branch — **not merged**,
left for review since it's a live health-data production service.

**Then closed the loop on this side**, since `V2Shaper.kt` already put both
keys on the wire and one of them was silently wrong: `client_id` was fine,
but `captured_at` was sent as raw `capturedAtMillis` — a bare number, which
VitalForge's Pydantic `datetime` field parses as Unix **seconds**, landing
tens of thousands of years in the future. Fixed to `Instant.ofEpochMilli(…).toString()`
(ISO-8601 with an explicit UTC offset). `V2ShaperTest.kt` updated to match
and gained a dedicated regression test for the wire-format bug itself, not
just the value. 565 unit tests, detekt clean.

**Residual gap, not silently closed — read this before touching WP-22:** a
legacy row whose *original* delivery was itself delayed past the dedup
window has no reliable capture-time proxy for a later replay to match
against; VitalForge never had the chance to record one for that row.
`captured_at` fixes every row captured from here forward, not retroactively
for that specific backlog shape. `00-design.md` §4.4 and `01-plan.md`'s WP-22
section both carry this note now — don't let it get silently reopened as
"unknown" the way A6 itself did.

**Not done this session, deliberately:** `ReplayMigrationWorker.kt` and
`ReplayEligibility.kt` still don't exist in this repo. This resolution
unblocks writing WP-22 with confidence; it does not implement it. The
`vitalforge` PR is also not merged — check its status before assuming the
server side is actually live.

## 2026-08-31, night: live hardware session — profile management + weigh-now

Continuation of the same day's UI-modernization merge, this time with the
Pixel actually connected and the physical BF720 in reach. Three real findings,
one real feature, all device-verified — not just unit-tested.

**Uncommitted at handoff — nothing here is pushed or even committed.**
`git status` shows 9 modified/added files, +326/-9:
`BasculeApplication.kt`, `BridgeForegroundService.kt`, `BasculeApp.kt`,
`HistoryScreen.kt`, `RegisteredScaleSection.kt`, `ScaleScreen.kt`,
`ScaleViewModel.kt`, plus the three matching test files, plus this spec:
`docs/superpowers/specs/2026-08-31-scale-profile-management.md` (written
*before* implementing — read it for the full design reasoning behind all
three items below, including the rejected alternatives). All green:
**557 unit tests pass** (`--rerun-tasks`, forced non-cached, confirmed after
the History-screen wiring — up from 543 at last night's merge), detekt
clean, every new guard mutation-tested by hand (broke it, confirmed red,
reverted).
**Not yet committed on purpose** — the session ended mid-flow when the user's
phone left the app for an unrelated text conversation; nothing was lost, but
nobody has reviewed the diff as a whole yet the way `finishing-a-development-branch`
would.

1. **Delete a local profile.** `ScaleProfileStore.deleteProfile` existed and
   was never wired up. Added `ScaleViewModel.delete()`, a `Remove` button per
   profile row with a confirm dialog, and fixed the stale "can't be removed
   yet" copy. Device-verified: dialog opens, cancels cleanly, copy is
   accurate. Never device-verified confirming an *actual* delete on this
   user's real (only-known) profiles — deliberately not exercised destructively
   against real data mid-session.

2. **The real bug behind "why doesn't Bascule see Profile 1."** Not a display
   bug — `RegisteredScaleSection`'s "Use existing" button was gated behind
   `registeredUserIndex == null`, so once *any* scale is registered there was
   no reachable UI path to link a second profile, even though
   `ConfigViewModel.linkExistingScale` → `EncryptedScaleProfileStore.save()`
   already handles multiple profiles per address correctly. Fixed the gating
   (`RegisteredScaleSection.kt`) so "Use existing" is always offered alongside
   "Re-register." **Then actually used it live**, with the user's real PIN
   (P01 → slot 1, consent 3907) for the same BF720 already holding slot 2
   ("bryn"/"JD" — profiles have since been renamed on-device by the user).
   Slot 1 ("jd") is now the active profile. This is real production data on
   the user's real device, not test fixtures.

   **This produced independent, unplanned proof the whole pipeline works**:
   minutes after linking, a real 200.8 lbs weigh-in was captured through slot
   1, landed in History as `pending`, and resolved to `sent` against
   `https://weight.grepon.cc` on its own within a few minutes — no
   intervention needed. `BLOCKED_AUTH`/credential-rejection UI was never
   exercised because auth is fine; not a coverage gap the app's still hiding.

3. **`weighNow()` — a bounded fast-scan button, on both Scale and History.**
   Diagnosed first: the two existing scan paths (`ScaleScanner`'s
   `LOW_POWER`/`PendingIntent` background scan, and `BridgeForegroundService`'s
   `BALANCED` always-on one) both turned out to work correctly — an earlier
   150s silent window in this same session was bad timing (scale not
   advertising, or nobody actually on it), not a broken pipeline. Confirmed by
   later `dumpsys`/logcat evidence of a real `ScaleSessionWorker` completing.
   Given that, `weighNow()` doesn't add a third scan subsystem — it reuses
   `BridgeForegroundService` with a new `EXTRA_BOUND_MILLIS` intent extra
   (120s, past `SessionBudget.HARD_SESSION_CEILING`'s 90s with margin) that
   arms a self-stop via an injectable `boundStopScheduler` seam
   (`Handler.postDelayed` in production). No-ops with a clear diagnostic line
   when "Always-on foreground fallback" is already on, since starting a
   second scan against the same filter would just fight the first one's
   `PendingIntent` registration for nothing.

   `ScaleViewModel` and `AndroidBridgeServiceController` both grew a
   `startBounded(durationMillis)` alongside the existing unbounded `start()`.
   `HistoryScreen` now takes an optional `scaleViewModel` parameter —
   `BasculeApp.kt` constructs it once, explicitly scoped to the same shared
   `ViewModelStoreOwner` `ConfigViewModel` already uses across Settings/Scale,
   so History's button and Scale's button read one shared `weighNowActive`
   flag rather than two independent ones that could silently disagree
   (exactly the P25 failure shape this codebase has hit before). The button
   composable itself (`WeighNowButton`) is shared, not duplicated, between the
   two screens.

   **Device-verified end to end, both directions**: tapped on Scale, showed
   "Waiting…" on History too; `dumpsys activity services` confirmed
   `BridgeForegroundService` actually running foreground with `(has extras)`
   on the intent both times; cancel (once a tap actually landed on it — two
   earlier taps missed the button on a resized layout) stopped the service,
   confirmed absent from `dumpsys` afterward. **One weighNow window also
   caught a live capture** — a second foreground notification
   (`scale_capture` channel) appeared mid-window and cleared on its own,
   consistent with a real `ScaleSessionWorker` session running to completion,
   though the resulting History row was not directly re-confirmed before the
   phone left the app (see below) — worth a quick glance next time it's in
   hand.

**Device end-state when the session paused:** Bascule was backgrounded (the
phone switched to a messaging app under the user's own control, not a crash).
No foreground service was left running — the last `weighNow()` window had
already self-expired cleanly by the time this was checked
(`dumpsys` showed nothing). Both `Automatic background capture` and
`Always-on foreground fallback` toggles were left **off**. Active profile is
**"jd" (slot 1)** — "bryn" (slot 2) is registered but currently inactive, so
only one of the two is actually being watched; whether that's what the user
wants day-to-day was flagged but not resolved.

**Before doing anything else with this branch of work:** read the diff as a
whole, then decide the commit boundary — none of tonight's three items
depend on each other, so they could ship as one commit or three.

**Resolved 2026-09-01 morning:** committed as `b3c10bd` and pushed straight
to `main` (no branch, no PR — see the next section for why that couldn't be
retrofitted into a PR after the fact). Also resolved: slot 2 ("bryn") sitting
idle was flagged, not fixed — still true, this app only ever watches one
active profile at a time; not addressed this pass either.

## 2026-09-01, morning: independent review of `b3c10bd`, then a fix-all pass

`b3c10bd` had already shipped straight to `main` with no review (see above).
Dispatched a fresh `code-reviewer` subagent against it — never self-review
your own just-written code, per this project's own standing rule — which
found **2 HIGH, 5 MEDIUM, 6 LOW**, all real, none CRITICAL. Decision was
REQUEST_CHANGES / follow-up commit, not a revert: architecture was sound
(reusing `BridgeForegroundService` over a third scan subsystem, the
injectable `boundStopScheduler` seam, sharing `WeighNowButton`, hoisting
`ScaleViewModel` to the shared owner all correct), validation was still
green (557 tests, detekt 0). Fixed everything HIGH/MEDIUM plus the LOW items
that were real defects, each with its own test, three spot-checked by
mutation (broke it, confirmed red, reverted) — same discipline as the night
before. **564 tests, detekt clean**, all pushed to `main` in a follow-up
commit.

**What was actually wrong, in order of how much it mattered:**

- **H-1 + H-2 — one defect, two independent fixes needed.** `weighNow()`'s
  bounded scan and the "Always-on foreground fallback" toggle share one
  `BridgeForegroundService`. Interleave them — tap Weigh now, then turn
  always-on on before the 120s window expires — and the stale bounded timer
  fired `stopSelf()` with no `startId`, unconditionally killing the scan the
  user had separately asked to keep running, with the toggle still reading
  on and nothing behind it. `cancelWeighNow()` had the identical bug from
  the other direction (`context.stopService()` ignores start ids entirely,
  so fixing only the service side left this route open). Fixed: `onStartCommand`
  now captures and passes its own `startId` to `stopSelf(startId)` (a
  documented no-op once a newer start has landed); `cancelWeighNow()` now
  checks `config.alwaysOnBridging` before calling `stop()` at all.
  `BridgeForegroundService.kt`, `ScaleViewModel.kt`.
- **M-5, same method as H-1.** `START_STICKY` on a *bounded* start meant a
  mid-window process kill restarted with a null `Intent` → `boundMillis = 0`
  → no timer armed → a scan nothing would ever stop. Now `START_NOT_STICKY`
  for a bounded start only; the always-on path's restart behavior is
  unchanged.
- **M-1.** `weighNow()`'s no-op cases (already-running, no active profile)
  write a diagnostic message that `ScaleScreen` renders and `HistoryScreen`
  silently did not — so the identical tap explained itself on one screen and
  did nothing visible on the other. History now renders it too.
- **M-3.** Deleting the *active* profile while automatic capture was on left
  the config flag and the LOW_POWER scan both armed against a profile that
  no longer existed — `delete()` now disarms and turns capture off when this
  happens, with a diagnostic. The KDoc's original claim that existing guards
  already covered this was false; corrected.
- **M-2.** `HistoryScreen`'s `scaleViewModel` parameter had a `viewModel()`
  default that silently resolved to a second, route-scoped instance,
  defeating the one-shared-instance guarantee its own KDoc promised (the
  exact P25 shape this whole feature exists to avoid). Made required —
  `BasculeApp.kt` already supplied it explicitly, so nothing else changed.
- **M-4.** `weighNow()` didn't check for an active profile before showing
  "Waiting…" for the full 120s over a scan that self-stopped instantly.
  Guarded, mirroring `setAutomaticCapture`'s existing line-118 check.
- **L-1, subtle.** `weighNowJob = launch{}.also{ invokeOnCompletion {...} }`
  had a real evaluation-order bug: if the coroutine completed before
  `launch()` returned, the completion callback could fire *before* the outer
  assignment landed, leaving `weighNowJob` stuck non-null forever — a dead
  `weighNow()` with no error. Didn't fire in practice (today's suspend calls
  never resolve synchronously) but was one dispatcher change away from being
  live, and — per the reviewer — `MainDispatcherRule` always dispatches, so
  **no test in this suite's current form could have caught it**. Fixed:
  assign first, then register with an identity check (`if (weighNowJob ===
  job)`).
- **L-6, cosmetic but real.** "Use existing" carries a fixed leading
  8dp padding meant to separate it from a preceding button — in the one
  state where it's the *only* button in the row, that gap floated it off the
  card edge for no reason. Now conditional on whether a leading button
  actually rendered.

**Explicitly not fixed, with reasons — don't silently re-open these without
re-reading why:**
- **L-2** (expose `weighNowActive` as its own narrower `StateFlow` so History
  doesn't recompose on unrelated `ScaleUiState` changes) is superseded by
  M-1's fix: M-1 requires History to read `diagnostic` too, so collecting
  only a narrower flow was never actually available as an option once M-1
  landed.
- **L-3** (`alwaysOnBridgingStartFailed` renders nowhere in the UI) is a
  pre-existing gap this commit made reachable via a new path, not one it
  introduced — flagged, not fixed.
- **L-4** (`WeighNowButton` lives in `HistoryScreen.kt` despite being shared
  by two screens — convention wants its own file) and **L-5** (new strings
  hardcoded instead of `stringResource`, matching a pre-existing, wider
  pattern across `ScaleScreen.kt` and others) were both left alone as
  genuinely out of scope for a bug-fix pass — L-5 in particular is drift
  this commit didn't create.

**On the missing PR:** the review happened *after* `b3c10bd` was already on
`main`, because it was pushed directly there the night before with no branch
cut. `/prp-pr` was tried afterward and correctly refused — the current
branch *is* `main`, so there was nothing to open a PR from. Retrofitting one
would mean reverting `main` and replaying the commit on a branch, which
wasn't done. If review-before-merge matters going forward, cut a branch
*before* committing, not after.

**The fix commit itself, `f358f99`, had the identical near-miss**: committed
locally, then genuinely forgotten — not pushed until a `git status` check
during an unrelated "housekeeping" request caught `main` sitting one commit
ahead of `origin/main` with nobody aware. Caught and pushed same session, but
note the pattern: two commits in a row on this branch of work almost shipped
un-pushed or un-PR'd. Verify `git log origin/main..HEAD` is empty as a matter
of course after any commit, not just when asked to tidy up.

**2026-09-01 housekeeping**, prompted by the same request: `ui-modernization`
was *not* actually auto-deleted after PR #2 merged, on either `origin` or
locally — this file's earlier claim that it was gone was wrong, uncaught
until now. Confirmed `gh pr view 2` showed `MERGED`, confirmed zero commits
ahead of `main`, deleted both copies with the user's explicit sign-off (two
separate confirmations — the merged branch first, five more fully-merged
local-only relics from earlier phases second). Local branch list is now
`main` only, matching `origin`.

## 2026-08-29, evening: UI modernization branch (`ui-modernization`)

An eight-task plan executed by subagents with a review after every task and a
whole-branch review at the end. **16 commits off `main@73716c7`, 27 files,
+603/-155, 542 tests, detekt clean.** Spec: `docs/superpowers/specs/2026-08-29-ui-modernization-design.md`.
Plan: `docs/superpowers/plans/2026-08-29-ui-modernization.md`.

What landed: the app's first launcher icon (adaptive, with a monochrome layer
for themed icons); manual entry dropped from the bottom bar so the rarest
action no longer owns a quarter of primary nav (closing **P25**); automatic
capture enabled on registration via a helper shared by *both* registration
paths; delivery-status colours pinned so a failed delivery no longer depends
on the wallpaper to be distinguishable from a successful one; History now
renders the user's selected weight unit instead of each row's capture-time
unit (which also closed a documented defect where a corrupt stored unit
rendered a silently wrong number); a capture-state banner so a registered-but-
idle app stops looking identical to "nobody weighed in"; the single-option
contract-version dropdown removed; and Scale-screen copy rewritten out of
implementation vocabulary.

### KNOWN DEVIATION FROM THE SPEC — read before trusting §5.3

Spec §5.3 requires the capture-off state offer **a one-tap enable**. The plan
specified only banners, and the branch faithfully implements the plan, so the
button does not exist. The banner now at least names where the toggle lives.
This was caught by the final whole-branch review, not by any per-task review —
each of those only compared code against its own brief. Deliberately deferred
rather than landed as unreviewed UI after the last review pass.

### Verification limits — do not overstate these

- **Nothing in this branch has been verified on a physical device.** The Pixel
  was unplugged partway through the first task and never reconnected. The
  icon, every screen layout, the status chips and the capture banner are
  code-verified only. A green unit lane depends on `processDebugResources`, so
  it proves the icon vectors and `<adaptive-icon>` XML compile and link — and
  nothing whatever about how any of it looks.
- **This repo has no Compose/instrumented lane**, so the contract-dropdown
  removal is verified by nothing and cannot be; its tripwire test pins the
  premise (exactly one selectable version), not the removal.

### Follow-up work, triaged by the final review and deliberately deferred

- **`Banner()` is hardcoded to `errorContainer` + a warning icon**
  (`HistoryScreen.kt`), so the informational capture-state banner renders as a
  red error — a brand-new user's first screen is an error bar — and up to four
  banners can stack as identical red bars. One root cause; the fix is a visual
  judgement that needs a device.
- **`ConfigViewModel` is ~730 lines at 19 of detekt's 20-function ceiling.**
  Task 3 had to inline an unrelated helper purely to make room. Deleting the
  dead `saveContractVersion` wrapper freed one slot, but this needs a design
  decision, not a patch. Reverting that inlining is clean once it is addressed.
- **`ConfigStore` maps three preferences over one DataStore**, so any unrelated
  preference write re-sorts the whole readings table three times. Fix is
  `distinctUntilChanged()`. Invisible to the JVM lane because `FakeConfigStore`
  uses separate flows.
- **`DECLINED` now shares `SENT`'s colours**, so "not me" looks like
  "delivered". Defensible but unremarked and uncovered.
- **The violet fallback re-tune is half-done** — neutrals keep a teal-green
  tint (`OutlineVariantLight`, `InverseSurfaceLight`, `BackgroundDark`).
  Near-unreachable on SDK 31+ where dynamic colour wins.
- **`HistoryScreen` calls `isSystemInDarkTheme()` directly** while `BasculeTheme`
  is parameterized — no live defect today, but a `@Preview` or a future in-app
  theme setting would get status cards from the system and everything else from
  the override.
- **`StatusLabel`'s own chip background is still wallpaper-derived**, and the
  contrast test only covers container-vs-content.

## Later the same day (2026-08-29): repo-glowup completed (local files only)

Supersedes the earlier "repo-glowup started, not completed" note. No app code
changed this pass — the whole diff is docs/branding/licensing. HEAD is still
`efd7411`; **everything below is uncommitted and unpushed.**

1. Answered a "what's left to get this app fully working?" question by
   summarizing the "Known open items" section below (nothing new — see that
   section, unchanged, for the actual list). Flagged the VitalForge
   idempotency item (A6) as the one that actually blocks real-world use
   verified as still open.
2. **`/repo-glowup` resumed and finished.** The blocker that stopped the
   earlier attempt is gone: `rsvg-convert` 2.61.4 *and* Google Chrome 145 are
   both installed now, plus Pillow 12.2.0 for the GitHub-safe PNG re-encode.
   No `sudo` was needed this time. Preflight was fully green.

   Landed (all untracked/modified, nothing committed):
   - `docs/assets/branding.json` — the regeneration source of truth. Palette
     `violet`, tagline "Bluetooth scale to VitalForge, no manual entry". Also
     carries the drafted `topics` list for whenever the remote step happens.
   - `docs/assets/banner.svg` / `banner-light.svg` — dark + light hero,
     both visually verified rendered, not just generated.
   - `docs/assets/social-preview.{svg,png,jpg}` — 1280×640, confirmed.
   - `README.md` — **authored from scratch. There was no README before**;
     the old handoff's "branded README hero" framing understated this.
   - `LICENSE` — see below.

   One caveat on the visual check: this machine has Cantarell and Fira Code
   installed, so the approved render used them. Most GitHub viewers won't have
   Cantarell — they'll get the SVG's fallback stack (`Segoe UI` / `Noto Sans`).
   The fallback chain is sane and the generator's text-fitting is conservative,
   so this is accepted, not a defect; just know the approved render and a
   Windows/macOS reader's render differ slightly.

   To regenerate the art after editing `branding.json`:
   ```
   SK=~/.claude/plugins/cache/bearyjd/repo-glowup/0.1.0/skills/repo-glowup
   python3 "$SK/scripts/gen_branding.py" --config docs/assets/branding.json --outdir docs/assets
   bash "$SK/scripts/rasterize.sh" docs/assets
   ```

### The licensing gap this surfaced — worth reading

`docs/prp/bascule-prp.md:7` declares **License: AGPL-3.0**, and
`decisions.md:191` lists "README carries openScale attribution and the
AGPL-3.0 notice" as a Phase 5 gate item. Neither had ever shipped: there was
no `README.md` and no `LICENSE` file, and `gh repo view` returned
`licenseInfo: null`. A public repo with no LICENSE is **all-rights-reserved
by default** — the opposite of the stated intent, and it had been that way
for the repo's whole public life.

Fixed with user approval: `LICENSE` now holds the canonical AGPL-3.0 text
(662 lines, sha256 `8d56b405…`), fetched from GitHub's `/licenses/agpl-3.0`
API because gnu.org is unreachable from this sandbox. Installed **verbatim** —
the `<year> <name of author>` placeholders in the "How to Apply" appendix are
part of the license document and must not be edited; attribution lives in the
README's License section instead (Copyright (C) 2026 Ventouxlabs).

The README also carries the openScale acknowledgement ADR-002 requires,
stating plainly that no openScale source is used and the decoders were
reimplemented from protocol understanding.

### Deliberately NOT done — remote metadata

User chose "local files only". So all of this is still open:
- Social preview **not** uploaded. There is no API for it; it is a manual
  step at **Settings → General → Social preview**, using
  `docs/assets/social-preview.png` (`.jpg` is the fallback if GitHub rejects
  the PNG).
- Repo **topics not set** (still empty). A 12-tag draft is sitting in
  `docs/assets/branding.json` ready to apply.
- About **description untouched** — the existing one is already accurate,
  so overwriting it was a real decision, not a default.
- The README's license badge currently renders **"LICENSE: NOT SPECIFIED"**.
  This is expected, not a bug: shields.io reads GitHub's API, which cannot
  see an unpushed `LICENSE`. It flips to AGPL-3.0 on push. Don't "fix" it.

## 2026-08-29, later: FIRST on-device run since the round-3 fix wave

The Pixel 9 Pro Fold (`4A111FDKD0000C`, Android 17 / SDK 37) was connected and
the current build was installed and launched. **The scale was not present**, so
everything BLE-dependent is still unverified. This is the first hardware
contact since the fix wave, which until now had 524 green tests and zero real
runs.

**Device state found (pre-existing install dated 2026-08-23, i.e. before all
the round-3 fixes):**
- `base_url = https://weight.grepon.cc`, `display_unit = POUNDS`,
  `paired_device_address = E7:DB:51:F1:36:91` — the BF720, registered as
  **user slot 2**, matching the Hardware section below.
- Exactly one reading in Room: a **MANUAL 70.5 kg entry from 2026-08-23
  08:57:35**, `status=PENDING`, `attemptCount=0`. It had sat undelivered for
  six days having never been attempted once — consistent with the delivery
  scheduling defect the Aug 28 `APPEND_OR_REPLACE` fix addressed.

**Backup taken first, and why it is not sufficient:** the whole data dir was
pulled to `~/bascule-device-backup-20260829-091045.tar.gz` (verified archive).
**That backup cannot survive an uninstall.** `EncryptedPreferences.kt:72-84`
builds its `MasterKey` in the `AndroidKeyStore`, so the key is device- and
install-bound and is destroyed on uninstall; restoring
`bascule_scale_consent.xml` afterwards yields an undecryptable file. The scale
registration is therefore only recoverable via the app's own passphrase-based
Settings → Export, or by never uninstalling. **Install over the top
(`adb install -r`) — never uninstall.** Done that way here; data confirmed
intact afterwards (`firstInstallTime` still Aug 23).

**Results:**
1. **No crash on startup.** `BasculeApplication.onCreate` — the method the
   devil's-advocate pass found a reordering regression in — ran clean on real
   hardware with always-on bridging enabled and a registered profile present.
   Process stayed alive. That regression fix now has hardware evidence, not
   just a unit test.
2. **`DeliveryWorker` actually runs now**: `WM-WorkerWrapper: Worker result
   SUCCESS for ...delivery.DeliveryWorker`. On the old build the row sat
   PENDING with the worker never firing. The scheduling fix is confirmed
   against real, six-day-stale data.
3. **The stale row moved `PENDING` → `BLOCKED_AUTH`** (`lastError=
   "authentication required"`, `lastErrorClass=AUTH`) with `attemptCount`
   still 0 and `lastAttemptMillis` still null. Correct: `ReadingDao.kt:78`
   parks the queue with a bulk UPDATE rather than a per-row attempt, so no
   HTTP call was made and no attempt was burned. Re-login flips it back
   (`ReadingDao.kt:61`).

**Two design findings from the real UI (screenshots taken of all four tabs):**
- **The credentials card lies about session validity.** `ConfigScreen.kt:407`
  renders "Signed in via username/password" from `state.sessionIsSet`, which
  means *a cookie is stored*, not *the cookie still works*. Right now Settings
  says "Signed in" while History simultaneously says "VitalForge needs your
  login again" — both from the same state. Same family as the two unrendered
  failure flows below: the app knows, the surface doesn't say.
- **The display-unit setting does not affect History.** `formatWeight()`
  (`HistoryFormatting.kt:19-20`) formats from the *reading's* persisted
  `displayUnit`, and `HistoryViewModel` never reads the config preference at
  all. So with the unit set to Pounds, the Add-weight field correctly says
  "Weight (lbs)" while History still renders "70.5 kg". Arguably right (an
  immutable historical record) but currently undocumented and surprising —
  needs a decision, not a reflexive fix.

**Still completely unverified — needs the physical scale:** the `0x2A9F`
handshake, weight/body-composition frame correlation, and C3's 90s session
ceiling. "Last successful capture: Never" on the Scale screen; automatic
background capture is currently OFF, always-on foreground fallback is ON.

4. **The delivery path is now proven end-to-end on real hardware.** The user
   logged out and back in against `weight.grepon.cc`; the BLOCKED_AUTH row
   flipped to PENDING and delivered:

   | field | value |
   |---|---|
   | `status` | `SENT` |
   | `attemptCount` | **1** |
   | `deliveredFields` | `WEIGHT` |
   | `contractVersionAtDelivery` | `1` |
   | `lastError` / `lastErrorClass` | *(none)* |
   | `remoteDuplicate` | `0` |
   | `lastAttempt` | 2026-08-29 13:16:58 |

   logcat shows exactly one `WM-WorkerWrapper: Starting work for
   ...DeliveryWorker` → one INTERNET `requestNetwork` for uid 10321 → one
   `Worker result SUCCESS`, with no exceptions. The History UI's error banner
   cleared and the row's chip changed to `sent`.

   **`attemptCount = 1` is the load-bearing number here.** It is direct
   evidence against **C1** (concurrent periodic + immediate-trigger drains
   double-submitting the same row): both drains were live, and the row was
   submitted exactly once. C1 was previously closed on reasoning and unit
   tests only. Note this is one observation, not a stress test — it does not
   prove the race can never occur, only that the fixed code did the right
   thing on a real drain. **§8.6's re-login recovery** (`ReadingDao.kt:61`)
   is likewise now confirmed against real data rather than a fixture.

## Where things actually are

- **Repo:** https://github.com/bearyjd/bascule (public, AGPL-3.0).
  **`main` now has everything** — the VitalForge connectivity/login feature,
  its two prior review-and-fix rounds, and this session's round-3
  multi-agent review plus fix pipeline. Head is `1b6db33` (merge commit for
  PR #1, now closed/merged).
- **PR #1 is merged, not open.** The `vitalforge-connectivity-and-login`
  branch it lived on is deleted, both locally and on origin. There is
  nothing further to push for this feature.
- **Test count: 524**, all green on `main`. `detekt`: 0 issues. CI (GitHub
  Actions, `.github/workflows/ci.yml`) passed on the merge commit.
- **Process doc:** `docs/prp/bascule-agent-prompt.md` governs phases/gates.
  **PRP:** `docs/prp/bascule-prp.md` governs requirements, wins on conflict.

## What actually happened this session (chronological)

Picked up after the previous session's handoff (443 tests, PR #1 open with
two commits pending push) with a request for "opus subagent code review" —
which grew into a full round-3 review-and-fix cycle, then a devil's-advocate
follow-up, then push and merge.

1. **Round-3 multi-agent review**, four independently-dispatched slices
   (delivery/network, BLE decoders — previously never reviewed at all,
   encrypted storage/UI, BLE session/lifecycle), each re-verified fresh
   rather than trusted from a lossy pre-compaction summary. **55 findings:
   3 CRITICAL, 10 HIGH, 42 MEDIUM/LOW.** Full detail in
   `.claude/PRPs/reviews/pr-1-review-round3.md`. Headlined by:
   - **C1**: concurrent delivery drains (periodic + immediate-trigger) could
     both run at once and double-submit the same pending rows, with no
     server-side idempotency key to dedupe on.
   - **C2**: any 3xx HTTP response was classified as a permanent failure;
     combined with redirects deliberately not being followed, a single
     server-side redirect would mark the *entire* pending queue
     `FAILED_PERMANENT` on first attempt.
   - **C3**: the 90s hard session ceiling could fire during the
     post-emission idle wait and discard an *already-decoded* weight
     reading, since the decoder had nothing left to flush by then.
   - The single most user-visible HIGH: registering a scale (replacing one,
     or re-registering the same one) reported success but never activated
     the new profile or re-armed the scanner — capture silently died.
2. **Nine fix batches**, run in parallel where files didn't overlap, each
   independently verified green before integration: delivery layer (closes
   C1/C2 + 3 composing HIGHs), GATT session (closes C3 + the GATT-leak
   HIGH), boot/startup crash-safety (2 HIGH uncaught-exception crashes),
   scan/foreground-service lifecycle (the last 2 HIGHs), BLE decoder package
   (orphan-pairing/plausibility bugs), scale-registration activation (the
   user-visible bug above, closing 3 findings at once) + ConfigScreen UI
   messaging (landed together — a real compile dependency), encrypted-
   storage hardening, and misc test-infra cleanup. All landed as separate,
   independently-verifiable commits — see `git log` for the individual
   messages, each one has real detail on what changed and why.
3. **`/devils-advocate` on the round-3 fix commits themselves** (scoped to
   just the 9 new commits' diff — 68 files / ~3k lines — not the full
   156-file branch diff, which had already been reviewed exhaustively by
   this point). Found 5 real issues across all six review topics, most
   notably: the boot/startup fix batch had reordered `BasculeApplication.
   onCreate`'s steps for one reason (FGS exemption window timing) and, in
   doing so, raced *two other steps against each other* — starting
   always-on bridging before the legacy-credential migration that populates
   the active profile it needs, so on exactly the first launch after a
   BF720-slot-mapping upgrade with bridging already enabled, the newly-added
   `stopSelf()` guard from a *different* fix in the same batch would fire
   immediately. A genuine regression, introduced by this session's own
   earlier work, caught only because the adversarial-review step existed.
   Fixed along with 4 other items (extracted a shared `runNonCancelling`
   coroutine-safety helper that replaced 4 hand-duplicated copies of the
   same pattern, split `Error` from routine `Exception` handling at those
   sites, extracted a testable `classifyForegroundStartFailure` seam, and
   made `ScanEnqueueCooldown`'s now-persistent backing store prune stale
   entries instead of accumulating a permanent plaintext BLE-address log).
   → 524 tests.
4. **Pushed and merged.** CI green on the final push, no merge conflicts
   (branch already contained everything from `main`), PR merged via merge
   commit (not squash — the individual fix-batch commits are each
   independently meaningful and were left intact). Branch deleted. Local
   `main` fast-forwarded to match.

## Known open items (carried forward, still genuinely open — don't silently resolve)

Everything below predates this session except where noted; this session's
round-3 review was scoped to specific findings, not a re-litigation of these.

- ~~A6 escalation to JD, not yet sent~~ **Resolved 2026-09-02/03** — see the
  dated sections above. `vitalforge` PR #39 merged; WP-22 implemented, not
  wired in (deliberately — see that section for the residual gap).
- **`androidx.security:security-crypto` 1.1.0's `EncryptedSharedPreferences`
  is deprecated** by the platform. Both the VitalForge token and scale
  consent codes use it. Pick a successor before v1 ships.
- **O-08 residues**: the recovery path for a full 8-slot scale registry
  (read `2A9A` / SIG delete-user op) is unexplored.
- **V2 contract field names — verified 2026-09-03, not still waiting on a
  doc.** Re-checking this item during A6 follow-up meant reading VitalForge's
  actual `WeightIn` model directly (`~/Documents/vibe-code/vitalforge`, a
  sibling checkout), not a Track A contract doc that never materialized.
  Every field name `V2Shaper.kt` already sends —
  `body_fat_pct`/`body_water_pct`/`muscle_pct`/`bone_mass_kg`/`client_id`/`captured_at`
  — matched exactly. Three didn't: `bmi`/`bmr`/`amr` had no home anywhere in
  VitalForge (no `WeightIn` field, no `weight_log` column) — `extra="forbid"`
  would have 422'd the whole request the instant a reading carrying one was
  ever sent under V2, the identical failure shape `client_id` had before A6.
  Fixed in `vitalforge` PR #40 (open, not yet merged) — `V2Shaper.kt` itself
  needed no Bascule-side change, it already had the right names. It was
  previously
  selectable in the Settings UI dropdown despite the shaper's own KDoc
  falsely claiming otherwise, but that dropdown offered exactly one
  choice (`V1_WEIGHT_ONLY`) once gated, so a later session removed the
  control itself rather than leave a single-option dropdown in the UI.
  `ui/ConfigScreen.kt`'s `selectableContractVersions` survives as the sole
  remaining gate — it now backs only `ui/ConfigViewModel.kt`'s import path
  — plus `ContractVersionSelectionTest`'s
  `exactlyOneContractVersionIsSelectableSoNoControlIsWarranted` tripwire,
  which fails the moment a second version becomes selectable. Now that the
  field names are verified rather than pending — once `vitalforge` PR #40
  merges, nothing server-side blocks V2 — enabling it is a product decision,
  not a data-availability one: delete the `V2_BODY_COMP` filter from
  `selectableContractVersions` (do not delete the constant itself — it
  still gates `ConfigViewModel.importSettings`'s
  `imported.contractVersion in selectableContractVersions` check), update
  or remove the tripwire test, and decide whether the Settings dropdown
  should come back now that there is more than one legitimate option.
- **C16 residual**: `AndroidGattTransport.write()` emits a real failure
  event on a missing characteristic, but `GattSession.awaitWriteComplete`
  and the handshake path both still discard `WriteComplete.status`
  entirely. Needs its own scoped fix.
- **L1 residual**: `ConfigStore`'s `StoredEnum.Unreadable` case is correctly
  classified but nothing consumes it — a corrupted `ContractVersion` still
  silently downgrades to `V1_WEIGHT_ONLY`.
- **Architectural findings, deliberately not fixed** (each needs a real
  design decision — see `pr-1-review-patterns.md`): P8 (four config/
  credential stores, three incompatible reactivity idioms), P16
  (`ScaleProfileStore` inherits `ConsentStore`, inverting the data-layer/
  BLE-layer dependency direction), P17 (differing persistence idioms), P25's
  behavioral half (`ManualEntry`'s dual nav-bar/FAB back-stack contracts).
- **androidTest infrastructure was removed this session, not built out**
  (LOW finding: the deps and `testInstrumentationRunner` were declared with
  no `app/src/androidTest/` tree and no CI lane ever running them — dead
  config that also carried a false KDoc claim of coverage that didn't
  exist). This *changes* the framing of the old C5/C7 items: there's no
  longer a half-started instrumented-test setup to finish, just a clean
  decision to make from scratch if/when instrumented coverage is wanted.
  The gap this leaves *un*covered, named explicitly by this session's own
  fixing agents rather than hidden: `EncryptedScaleProfileStore`'s real
  persistence/quarantine logic (only a hand-written fake is exercised in
  the JVM lane), `BasculeApplication.onCreate`'s new crash-containment
  guards (no injectable seam — `ScaleSessionWorker`'s equivalent guard does
  have one, via `applicationContext as BasculeApplication`... which is
  itself the blocker for testing anything downstream of it, see next item).
- **`applicationContext as BasculeApplication` still blocks JVM testing of
  `ScaleSessionWorker.doWork`'s post-cast branches** (staleness/permission
  checks before the cast are covered; nothing after it is). This session
  extracted the one piece of logic that *could* be pulled out
  (`classifyForegroundStartFailure`) without the larger refactor (a
  `WorkerFactory` or `open`/overridable dependencies) this has needed since
  before this session started. Still not done; still a real decision, not
  a mechanical patch.
- **Smaller gaps flagged by name during this session's fixes, not silently
  closed**: `MeasurementCorrelator.flush()` still clears a held orphan
  body-composition frame without counting it as dropped (only reachable at
  end-of-session, low severity). The FGS-exemption-window fix in
  `BasculeApplication.onCreate` has no retry, and neither its
  `startupFailure` flow nor the pre-existing `alwaysOnBridgingStartFailed`
  flag is rendered anywhere in the UI yet — both need a screen surface.
  `EncryptedScaleProfileStore.clear()` now routes through the stricter
  `replaceAll` (which rejects duplicate ids) but is untested on the real
  encrypted path for the same androidTest-gap reason above; safe by
  reasoning (every write path already preserves id-uniqueness), not by
  test.

## A real discovery worth knowing before touching startup-sequencing code

Reordering independently-`guarded`/try-caught startup steps for one
step's sake can silently break an *implicit* ordering dependency between
two *other* steps that individually still "succeed." This session's own
devil's-advocate round caught exactly this in `BasculeApplication.onCreate`
(see item 3 above) — logged as a durable learning
(`startup-step-reorder-races-dependency`) precisely because the mistake was
made by this session's own earlier work, not inherited. When touching that
method again: check whether any step reads state another step writes, not
just whether each step individually still succeeds in isolation.

## Read these five files, in this order, before touching decoder/handshake code

1. `docs/prp/00-design.md` — the design. §2.6, §2.7, §3.1, §9 carry
   provisional banners, superseded by #3 below.
2. `docs/prp/decisions.md` — the ADRs. ADR-007 matters most: the BF720
   speaks the standard Bluetooth SIG Weight/Body-Composition/User-Data
   profile, not a proprietary opcode protocol.
3. `docs/prp/02-interface-revision.md` — the actual revised
   `ScaleDecoder`/`DecodeEvent`/`GattOp`/`GattTransport`/`ScaleReading`
   design. Supersedes `00-design.md`.
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

- **There is no open PR and no pending push for this feature.** Start from
  `main`. If the next piece of work is another feature, branch from `main`
  fresh rather than looking for `vitalforge-connectivity-and-login` — it's
  gone.
- **Multiple independent review passes each caught something the previous
  ones missed, again, this session** — the round-3 review found things two
  prior review rounds didn't (the whole decoder package had literally never
  been reviewed before this session); the devil's-advocate pass on the
  fix commits then found a regression *introduced by those same fixes*.
  Don't treat one review pass as sufficient once a large fix wave has
  landed — the fixes themselves are new, unreviewed surface area, same
  lesson as the previous handoff, reconfirmed independently.
- **Have review/fix subagents write full reports to durable storage, not
  just their final chat message.** This session hit the same failure mode
  the previous one named explicitly: a mid-session context compaction lost
  the *content* of 33 findings from an earlier review round, leaving only
  an aggregate severity tally. Recovery required re-running that scope's
  review from scratch rather than trusting a lossy summary — costly, and
  avoidable. This session's round-3 report (`pr-1-review-round3.md`) was
  written to disk specifically so it wouldn't happen again; keep doing that.
- **When agents report a cross-batch dependency mid-flight (a fix needing a
  small change in a file another batch owns), route the change to whichever
  agent already owns that file** rather than letting two agents edit the
  same file concurrently. This came up twice this session (a `ConfigScreen`
  fix needing a signal from `ConfigViewModel`, and a follow-up V2-contract
  gate needing to apply in both the UI dropdown and the import path) and
  both resolved cleanly by explicit routing rather than by accident.
