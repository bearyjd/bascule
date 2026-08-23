# Bascule — session handoff (Phase 0–2 complete, entering Phase 3)

Written 2026-08-22 to let a fresh session pick up Phase 3 without replaying
this one. Read this first, then the docs it points at — don't re-derive
anything below from git log archaeology.

## Where things actually are

- **Repo:** https://github.com/bearyjd/bascule (public, AGPL-3.0), pushed and
  tracked as `origin`. `main` is CI-green right now — check before assuming
  otherwise.
- **Process doc:** `docs/prp/bascule-agent-prompt.md` governs phases/gates.
  **PRP:** `docs/prp/bascule-prp.md` governs requirements. Where they
  conflict, the PRP wins (its own rule).
- **Phases 0, 1, 2 are done and merged**, each via its own `--no-ff` merge
  commit into `main` (see `git log --oneline` for the phase branch names —
  `phase-0-design`, `phase-1-planning`, `hardware-evidence-milestone1`,
  `phase-2-validation`). Follow the same pattern for Phase 3: branch off
  `main`, work, merge `--no-ff` with a gate-check message, push.

## Read these five files, in this order, before touching any code

1. `docs/prp/00-design.md` — the design. **§2.6, §2.7, §3.1, §9 carry
   provisional banners** — they're the *pre-hardware* version. Don't
   implement against them directly; go to #3.
2. `docs/prp/decisions.md` — 8 ADRs. **ADR-007 is the one that matters
   most**: live hardware capture found the BF720 speaks the standard
   Bluetooth SIG Weight/Body-Composition/User-Data profile gated behind a
   User Control Point register+consent handshake, not the proprietary
   opcode protocol openScale's older wiki page documents. This invalidated
   large parts of the original design and plan.
3. `docs/prp/02-interface-revision.md` — the actual revised
   `ScaleDecoder`/`DecodeEvent`/`GattOp`/`GattTransport`/`ScaleReading`
   design, already implemented in `app/`. This supersedes `00-design.md`
   §2.6/§2.7/§3.1/§9 for the decoder/handshake/schema. Read this, not the
   design doc, for what the interfaces actually look like.
4. `docs/prp/02-phase2-dispositions.md` — a fresh devil's-advocate pass
   found 11 objections (2 CRITICAL) against the post-ADR-007 design; this
   file records what was fixed, where, and what was legitimately deferred
   (only where the DA itself said "fix later"). **Read this before trusting
   any specific number, status, or claim in `00-design.md`/`01-plan.md`** —
   several were factually wrong (see O-10: two dedup/quiescence tolerance
   comments cited the wrong LSB resolution) and are now corrected, but only
   in the places this file and #3 touched.
5. `docs/prp/01-plan.md` — the 31 work packages, **as amended**: WP-00
   (interface revision, done — see #3) inserted ahead of WP-02; RISK-1
   re-ranked from `WP-03` (stabilization) to the UDS handshake work
   (originally WP-07's slot); WP-03 demoted to a portability/fallback
   guard because **this device's Weight Measurement characteristic has no
   stability flag and no live-frame stream — it notifies once, done** (see
   `02-interface-revision.md` §5). Don't build WP-03 first; that was the
   pre-evidence plan.

`docs/prp/03-hardware-validation.md` has the raw captured bytes and their
decode, if you need ground truth for a test fixture.

## What's already built (`app/`, real code, not scaffolding)

Real and unit-tested: `BeurerDecoder`, `MeasurementCorrelator`,
`WeightMeasurement`/`BodyCompositionMeasurement` parsers, `FrameReader`,
`SigWeightProfile` (the confirmed protocol constants),
`VitalForgeHttpClient`/`ResponseClassifier`/`V1Shaper`, `DedupPolicy`, the
Room schema (`ReadingEntity`, exported at
`app/schemas/.../1.json`), `AuthTokenStore`, `EncryptedConsentStore`,
`FakeGattTransport` (with its own tests — it had a real bug, a
zero-replay `SharedFlow` silently dropping pre-collector emissions; fixed).

Deliberately stubbed, each tagged with the work package that implements it:
`GattSession.run()` (WP-06/07/10 — the actual state machine), `ReadingMapper`
(WP-13), `ScaleScanner`/`ScanBroadcastReceiver`/`ScaleSessionWorker` (WP-08),
`AndroidGattTransport` (WP-04), `BridgeForegroundService`/`BootReceiver`
(WP-25/27), `DeliveryCoordinator`/`DeliveryWorker` (WP-21/22), all of `ui/`
(WP-23/24/28).

Contract tests exist and are red **on purpose** — `ScaleSessionContractTest`
fails 3 of 4 assertions because `GattSession.run()` is a stub. Run
`./gradlew testDebugUnitTest` (green, 61 tests) and
`./gradlew testDebugUnitTest -Pbascule.contractTests=true` (red, 3 of 4, by
design) — see `docs/prp/02-ci-notes.md` for why the split exists and why
`runBlocking` not `runTest` for the HTTP tests specifically.

## Hardware

Physical unit: Beurer BF720, MAC `E7:DB:51:F1:36:91`, already registered
with the app-chosen identity **scaleIndex=2, consent code 1234** (from the
milestone-1 capture session — re-usable, don't re-register blindly, it burns
one of 8 scale profile slots per O-08's finding).

Test device used for the capture: a Pixel 9 Pro Fold, adb serial
`4A111FDKD0000C` — **not currently connected** (checked at handoff time,
`adb devices` returned empty). Reconnect via USB before any hardware
checkpoint work; this environment has no other way to reach real BLE
hardware (checked: no bluetoothd/dbus in this container, and the Android
emulator has no BLE radio at all).

`tools/hw-probe/` is a **throwaway, out-of-band diagnostic app** (separate
Gradle project, not part of `app/`) used to reverse the live protocol before
`app/`'s real decoder existed. It's remote-controllable via
`adb shell am broadcast -a com.ventouxlabs.hwprobe.CMD --es cmd <scan|connect|synctime|listusers|register|consent|reset>`
(see `tools/hw-probe/README.md`) so a hardware checkpoint can be driven
without touching the phone screen — only the physical weigh-in needs a
human. It's still useful for Phase 3 hardware checkpoints if `app/`'s own
debug-build capture tooling (WP-12, not yet built) isn't ready yet, but it
is not itself under test or held to Bascule's quality bar.

## Known open items (don't silently resolve these — they're tracked on purpose)

- **A6 escalation to JD, not yet sent**: v2 replay requires VitalForge to be
  idempotent on `client_id` (or, for pre-v2 rows, on `captured_at` + weight
  tolerance) or replay duplicates Garmin history. `01-plan.md` §6 tracks it.
  Escalate before WP-22 (replay migration worker) is enabled, per the agent
  prompt's own escalation rule.
- **`androidx.security:security-crypto` 1.1.0's `EncryptedSharedPreferences`
  is deprecated** by the platform while the agent prompt's ground rule
  mandates it by name. Both the VitalForge token and the new scale consent
  code use it. Not blocking, but pick a successor (DataStore + app-managed
  keystore key, or raw Keystore) before v1 ships — migrating a stored
  credential is a data migration, not a refactor. See
  `02-interface-revision.md` §6.
- **O-08 residues** (`02-phase2-dispositions.md`): failed-registration
  behavior when the scale's 8 profile slots are full has a named outcome
  and counter, but the recovery path (read `2A9A` / use the SIG delete-user
  op instead of always re-registering) is unexplored — flagged as a
  checklist row for the real hardware session, not solved.
- **V2 contract field names** are deliberately unfilled — pinned from
  VitalForge's Track A contract doc when it lands, not invented here.

## Process notes for whoever (whatever) continues this

- Branch per phase (or per work package once inside Phase 3 — the agent
  prompt wants small PRs), `--no-ff` merge with a gate-check message, push.
- Fresh subagents for devil's-advocate passes must not share context with
  whatever produced the thing they're reviewing — that's load-bearing, not
  a nicety (see the agent prompt's own protocol section).
- If you spawn a subagent and it goes idle repeatedly without delivering
  its findings through the message/response channel, don't keep nudging —
  stop it and respawn with Write access, telling it to write findings to a
  file on disk instead of returning them in its final message. This
  happened three times in the session that produced this handoff and disk
  output fixed it every time.
- Trace adversarial-review fixes yourself before trusting them closed. The
  Phase 2 devil's-advocate's own proposed interface revision had a real
  residual bug (O-03: a late body-composition frame could pair with the
  *wrong* subsequent user's weight after the first was superseded) that
  survived one full review-and-fix cycle and was only caught by hand-tracing
  the actual code path. Don't assume a fix marked "closed" is closed without
  reading the code.
