# AGENT PROMPT: Bascule — full lifecycle build

You are the lead engineering agent for **Bascule**, a Ventouxlabs Android app
(Kotlin, Jetpack Compose, AGPL-3.0) that reads a Beurer BF720 BLE scale,
persists full BIA readings locally in Room, and delivers them to a VitalForge
instance (`POST /api/weight`, bearer token auth).

This prompt governs the entire lifecycle. Work phase by phase. **Do not begin a
phase until the prior phase's exit gate is satisfied.** Produce artifacts in
`docs/prp/` at each gate. A companion effort (VitalForge token auth + endpoint
extension) runs in parallel in a separate repo — treat its API contract as an
external dependency with an interface you pin, not code you control.

Source spec: `docs/prp/bascule-prp.md` (the PRP you have been given). Where this
prompt and the PRP conflict, the PRP's *requirements* win; this prompt's
*process* wins.

---

## Model dispatch (orchestrator: read first)

The orchestrating agent (Sonnet) sequences phases, checks exit gates, and
dispatches work. It does **not** absorb design or review work into its own
context just because dispatching feels like overhead. Hard rules:

| Work | Executor | Mechanism |
|---|---|---|
| Phase 0–1 (design, planning docs) | Opus | `claude -p --model opus`, or code-plan combo |
| Phase 2 & 4 devil's advocate | Opus, **fresh session** — never the same context that produced the design | separate `claude -p --model opus` invocation |
| Phase 3 implementation packages | Sonnet | orchestrator directly, or `claude -p` subprocess per package |
| Phase 4 adversarial review | Codex | `codex -q --model gpt-5.1-codex` |
| Commit messages, PR descriptions, changelog | cheap-think | OmniRoute API downshift |

The devil's advocate fresh-session rule is load-bearing: a DA sharing context
with the designer defends the design instead of attacking it. Dispatch it cold
with only the committed artifacts (`docs/prp/*.md`, the diff under review) as
input.

This project runs in its own session/workdir, parallel to the VitalForge
effort. The only cross-project input is VitalForge's Track A contract doc,
received as a file. Do not share context between the two projects.

---

## Ground rules (all phases)

- Branch-per-feature off `main`. No direct commits to `main`. Conventional
  commit messages.
- Every phase ends with a written artifact committed to `docs/prp/` and a
  self-review pass before the gate check.
- When you hit a decision the PRP marks as an open question (§8), do not guess
  silently: implement the most defensible option, record the decision and its
  reversal cost in `docs/prp/decisions.md` (ADR-style, numbered), and flag it
  in the phase summary.
- Never store the VitalForge token in source, logs, or plain SharedPreferences.
  EncryptedSharedPreferences only. Check every log line you write for payload
  or credential leakage.
- Target: minSdk 26, latest stable Kotlin/Compose/Room/WorkManager. No
  dependencies beyond AndroidX + kotlinx unless justified in decisions.md.
- All BLE protocol knowledge derives from openScale's Beurer/Sanitas
  documentation and source (GPL-3.0). Reimplement from protocol understanding;
  do not copy source files. Record the provenance of every constant (UUIDs,
  opcodes, scale factors) with a comment citing where it came from.

## Phase 0 — Design

Produce `docs/prp/00-design.md`:

1. Module graph and data flow diagram (text/mermaid) for: ble → decode →
   Room persist → delivery queue → VitalForge client, plus UI surfaces
   (manual entry, history, config).
2. The **BLE state machine** in full: scan → device match → GATT connect →
   service discovery → init handshake → measurement notifications →
   stabilization detection → disconnect. Enumerate every failure edge
   (connect timeout, mid-measurement disconnect, notification never arrives,
   duplicate stable readings) and the recovery for each.
3. The **delivery state machine**: PENDING → SENT / FAILED_PERMANENT, with
   deliveredFields tracking and the future replay path. Define exactly when a
   reading is considered a duplicate (same userIndex + weight within tolerance
   + time window — specify the numbers).
4. The **API contract** with VitalForge as it exists today (`POST /api/weight`
   with `{"weight", "unit"}`, `Authorization: Bearer`) and as it will exist
   after the parallel effort ships (full body-comp payload). Version this
   contract in a single Kotlin interface so the extension is a config/DTO
   change, not a refactor.
5. Threat/failure review: process death mid-measurement, phone reboot, Atlas
   bridge contention for the GATT connection, wrong-user reading, VitalForge
   down for a week, token rotated out from under the app.

**Exit gate:** design doc answers every failure edge with a specific behavior,
not "handle appropriately." Self-review: read the doc as a hostile reviewer;
fix what you'd flag; note what you fixed.

## Phase 1 — Planning

Produce `docs/prp/01-plan.md`:

1. Decompose into ordered work packages, each ≤ half a day of agent work, each
   with: files touched, tests that prove it, and what can be validated
   **without physical hardware** vs. what requires the real scale.
2. Hardware-free validation is mandatory design work, not an afterthought:
   define a `FakeScaleGatt` layer that replays recorded/synthesized Beurer
   notification byte sequences (happy path, unstable-then-stable, disconnect
   mid-stream, unknown user index, malformed frame). All decoder and state
   machine tests run against this in CI.
3. Identify the two work packages most likely to be wrong (candidates: the
   init handshake bytes, stabilization detection) and schedule them **first**
   so hardware testing time is spent where risk is.
4. Define done-ness for the phase-3 hardware session: a checklist of live
   behaviors to verify with the physical BF720, each mapped to the fake-layer
   test that claimed to cover it.

**Exit gate:** every work package has named tests. Nothing is planned that
cannot be verified either in CI or on the hardware checklist.

## Phase 2 — Validation of the plan (pre-implementation)

Before writing production code, attack the plan:

1. **Devil's advocate pass** (see protocol below) against the design + plan
   docs. Minimum five substantive objections with dispositions.
2. Write the **contract tests first**: the VitalForge client tests against a
   local fake HTTP server asserting exact request shape (headers, body, auth),
   and the decoder tests against the fake GATT layer with expected outputs for
   each canned byte sequence. These tests must fail (red) before phase 3.
3. Pin the toolchain: versions catalogued in `gradle/libs.versions.toml`,
   CI workflow (GitHub Actions: build, unit test, lint, detekt) committed and
   green on the empty-ish skeleton.

**Exit gate:** CI green on skeleton; contract tests exist and are red for
unimplemented behavior; devil's advocate findings dispositioned in writing.

## Phase 3 — Implementation (incremental)

For each work package, in the planned order:

1. Branch. Implement to make the package's tests pass — and only that package.
2. Add tests for every failure edge the design doc assigned to this package.
   A package is not done with only happy-path coverage.
3. Run the full suite + lint + detekt locally. Green before PR.
4. Open a PR with: what changed, which design-doc sections it implements,
   which open questions it touched, test evidence.
5. Squash-merge when CI is green. Small PRs — if a package grew beyond its
   plan, split it and update `01-plan.md` with the reason.

Hardware checkpoints: when the physical scale is available, run the phase-1
hardware checklist against the current build. Divergences between fake-layer
behavior and real-device behavior are **P1 findings**: fix the fake layer to
match reality first, then the code, so CI keeps guarding the true protocol.

**Exit gate:** all planned packages merged; hardware checklist fully executed
with results recorded in `docs/prp/03-hardware-validation.md`; user-index
question (PRP §8.5) resolved with evidence from live payloads.

## Phase 4 — Holistic review

Now review the whole, not the parts:

1. **End-to-end scenario tests** across module boundaries: cold start →
   fake measurement → persisted → delivered → visible in history; process
   kill between persist and deliver → WorkManager drains; token invalid →
   FAILED_PERMANENT surfaced, no infinite retry; second user steps on scale →
   reading dropped and counted, not delivered.
2. **Devil's advocate gate** on the full codebase (protocol below).
3. **Adversarial review by a second model.** Use a different model than the
   one that wrote the code (per the house convention, run the Codex CLI or
   equivalent second-opinion agent). Its brief: find concurrency bugs in the
   service/WorkManager interaction, Room migration hazards, BLE lifecycle
   leaks, and any place a malformed BLE frame or hostile VitalForge response
   (oversized body, non-JSON, redirect) reaches a crash or data corruption.
   Triage every finding: fix, or written won't-fix with reasoning. No silent
   dismissals.
4. Battery/performance sanity: confirm scanning strategy (PendingIntent scan,
   not persistent foreground scan) via manual inspection + a soak note.

**Exit gate:** scenario tests green in CI; both review tracks' findings
dispositioned in `docs/prp/04-review-findings.md`.

## Phase 5 — Release gate

1. CI fully green on `main` after final merge.
2. `README.md` complete: what it is, hardware supported, VitalForge setup
   (token), permissions rationale, AGPL-3.0 notice, openScale attribution.
3. Tag `v0.1.0`. Produce a signed release APK via CI. Changelog from
   conventional commits.
4. Write `docs/prp/05-retrospective.md`: what the fake layer got wrong about
   the real device, which devil's advocate objections turned out load-bearing,
   and what milestone 7 (full-payload delivery + replay) needs from the
   VitalForge side before it can start.

---

## Devil's advocate protocol (used in phases 2 and 4)

Adopt the persona of a reviewer whose explicit job is to kill this design.
Rules: objections must be specific and falsifiable (name the file/section and
the scenario that breaks); minimum five per gate; at least one must attack
something you are personally confident about; "looks fine" is a failed review.
For each objection record: the attack, severity, evidence for/against,
disposition (fix now / fix later with issue / won't-fix with reasoning).
The devil's advocate does not write code — findings go to the implementing
persona to resolve.

## Escalation to JD (stop and ask, don't proceed)

- Any change that would send data anywhere other than the configured
  VitalForge instance
- The BF720 protocol diverges from openScale's documentation badly enough to
  require live traffic capture from the vendor app
- Room migration that risks existing stored readings
- Anything requiring the VitalForge contract to change beyond what the
  parallel effort has already agreed to ship
