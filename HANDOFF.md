# Bascule — session handoff (post-merge)

Written 2026-08-29, replacing the 2026-08-28 version, which described PR #1
as open with two commits still unpushed. Both are pushed, the PR is merged,
and the branch it lived on is deleted. Read this first; don't re-derive
state from git log archaeology.

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

- **A6 escalation to JD, not yet sent**: v2 replay requires VitalForge to be
  idempotent on `client_id` (or `captured_at` + weight tolerance for pre-v2
  rows) or replay duplicates Garmin history. `01-plan.md` §6 tracks it.
- **`androidx.security:security-crypto` 1.1.0's `EncryptedSharedPreferences`
  is deprecated** by the platform. Both the VitalForge token and scale
  consent codes use it. Pick a successor before v1 ships.
- **O-08 residues**: the recovery path for a full 8-slot scale registry
  (read `2A9A` / SIG delete-user op) is unexplored.
- **V2 contract field names** deliberately unfilled — pinned from
  VitalForge's Track A contract doc when it lands. This session added a
  *second*, independent gate keeping V2 unreachable in the meantime (it was
  previously selectable in the UI dropdown despite the shaper's own KDoc
  falsely claiming otherwise) — both `ui/ConfigScreen.kt`'s
  `selectableContractVersions` and `ui/ConfigViewModel.kt`'s matching import
  gate need deleting together when the doc lands, not just one.
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
