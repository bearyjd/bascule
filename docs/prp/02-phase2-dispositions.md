# Bascule — Phase 2 Devil's Advocate Dispositions

**What this file is for.** The Phase 2 exit gate requires "devil's advocate
findings dispositioned in writing." This is that record: one entry per objection
in `02-devils-advocate-findings.md` (O-01 … O-11), stating the DA's severity,
what was actually done, and **which file and section now carries the fix** — so
the gate can be verified by reading rather than taken on assertion.

Three dispositions are used, and the difference matters:

| | Meaning |
|---|---|
| **Closed** | The objection no longer holds. A named file/section carries the fix. |
| **Closed, with a tracked residue** | The substance is fixed; a named part remains, with an owner and a trigger. |
| **Downgraded to tracked follow-up** | Not fixed now. **Only used where the DA's own disposition said "fix later" or "checklist row"** — the justification quotes it. |

Nothing the DA marked "fix now" is downgraded.

**Where the work landed.** Two passes are recorded here. `02-interface-revision.md`
(the Phase 2 skeleton, now entered in `01-plan.md` §2 as **WP-00**) closed several
objections in code before this reconciliation ran. This reconciliation closed the
rest, corrected one real bug the skeleton left open (O-03), and corrected the
places where the skeleton's own documents had gone stale against it. Where an
objection was closed by the earlier pass, this record says so and points at the
code, because "the interface revision handled it" is exactly the kind of claim
this file exists to make checkable.

---

## Summary

| # | Severity | Disposition |
|---|---|---|
| O-01 | CRITICAL | **Closed, with a tracked residue** — schema fixed in WP-00; `boneMassKg`/`amr` provenance is HW-30 |
| O-02 | CRITICAL | **Closed** — E17 named, persist rule restated, §8.1 amended, HW-25 added |
| O-03 | HIGH | **Closed** — real bug found and fixed in `MeasurementCorrelator`, with a test that fails without the fix |
| O-04 | HIGH | **Closed, with a tracked residue** — vocabulary neutral; the ATT confirmation model is HW-29 |
| O-05 | HIGH | **Closed** — all seven sub-items (a…g) |
| O-06 | HIGH | **Split**: dedup test **closed**; replay test **downgraded** (the DA said "fix later"); ADR-006 claim **closed** |
| O-07 | HIGH | **Closed** — re-ranked, WP-03 demoted and rescheduled, §7 self-check corrected |
| O-08 | HIGH | **Closed, with tracked residues** — HW-26/HW-27 are the DA's own checklist rows |
| O-09 | MEDIUM-HIGH | **Closed** (wording) — the DA said "fix later"; the overclaim was fixed anyway. CI matrix **downgraded to HW-28**, per the DA |
| O-10 | MEDIUM-HIGH | **Closed, with a tracked residue** — the CTS write is decided; issuing it is WP-07 |
| O-11 | MEDIUM | **Closed, with a tracked residue** — the DA said "fix later"; items 1–3 done, item 4 is WP-23 |

---

## O-01 — The schema cannot hold the payload that was actually captured

**DA severity: CRITICAL.** Disposition: "Fix now, before WP-13."

**Closed, with a tracked residue.**

**Item 1 — add the missing columns.** Closed **by the interface revision (WP-00)**,
before this reconciliation. `ScaleReading` carries `impedanceOhms`,
`softLeanMassKg`, `bodyWaterMassKg`, `muscleMassKg`, `fatFreeMassKg`, `heightM`
and `scaleTimestampMillis`
(`app/src/main/kotlin/com/ventouxlabs/bascule/ble/ScaleReading.kt`);
`ReadingEntity` carries `impedanceOhms` and `softLeanMassKg`
(`.../data/ReadingEntity.kt`).

**The two field sets deliberately differ, and the delta is stated rather than
left for a reader to find.** Three `ScaleReading` fields have no
`ReadingEntity` column: `muscleMassKg`, `fatFreeMassKg` and `heightM`. The first
two are reported **unsupported by this unit's own feature bitmap** (`cf 31 00 00`,
`03-hardware-validation.md` §4) and are always null — asserted, not assumed, by
`BeurerDecoderCaptureTest.fieldsTheUnitDoesNotSupportStayNull` — so a column would
store nothing and would read as "the scale didn't report it this time", the exact
ambiguity the DA's item 2 forbids. `heightM` is the case the DA itself
pre-conceded ("minor; derivable from config"): it is a property of the person,
not of the weigh-in, it is already in config, and it is retained on
`ScaleReading` only because the frame carries it and BMI is computed from it.
**Revisit trigger:** the first decoder that populates `muscleMassKg` or
`fatFreeMassKg` — adding the columns is WP-13's job at that point, and while v1
is unshipped it costs no migration.

The kg↔% and kJ↔kcal conversions the DA called
"lossy, unspecified anywhere" are now specified and located, at `ReadingMapper`
rather than in the parser — `02-interface-revision.md` §3's table, with the
reason (a conversion is not a decode; burying it in the parser makes the parser's
own test unverifiable against the spec).

**Fixed in docs by this pass**, because the schema documents still described the
old field set: `00-design.md` §2.7 and §3.1 now carry provisional banners naming
`02-interface-revision.md` §3 as authoritative, and §3.1's table has an explicit
row for `impedanceOhms`/`softLeanMassKg` stating *why* impedance in particular
cannot be dropped — it is the raw measured signal every other body-comp number is
a formula over, so discarding it makes body composition permanently
non-recomputable.

**Item 2 — `boneMassKg` / `amr`.** The DA asked for a PRP amendment *or* an ADR,
and specifically "do not leave two nullable columns that will silently always be
null and read as 'the scale didn't report it this time.'" Recorded rather than
either: `02-interface-revision.md` §3's table and `ReadingEntity`'s KDoc both
state that these are **not fields of the SIG Body Composition profile at all**,
are never populated by `BeurerDecoder`, and are retained only because PRP §5 pins
the schema and a future non-SIG decoder may supply them. The PRP itself is not
edited — it is the requirements input, not a Bascule work product.

**Item 3 — enumerate the proprietary services.** This is the residue, and it is
what the DA's own "evidence against" section says is needed before "unobtainable"
can be claimed: **`01-plan.md` §5, HW-30**, which enumerates `0x0000FFFF` and
`0x0000FF00` and asks the specific question — do they carry bone mass and AMR?
A yes reopens the schema; a no closes the field list.

---

## O-02 — Correlation has no timeout, and it breaks the rule the design calls load-bearing

**DA severity: CRITICAL.** Disposition: "Fix now. The single most consequential
unresolved item."

**Closed.** All four items.

**Item 1 — a named edge with a concrete value.** `00-design.md` §2.3 **E17**,
body-composition correlation timeout, **4 s** from the buffered Weight
indication, added to §2.5's timer table. The edge exists because none of the
timers the DA checked can fire in this state, exactly as it argued: E7's 45 s is
satisfied by the *first* frame, post-emission idle counts from an `EMITTED` that
by construction has not been reached, and the only backstop was the 90 s ceiling.
4 s rather than tens of seconds because the captured pair arrived within
milliseconds of each other.

**Item 2 — persist the weight-only row.** E17's action is `flush()` → persist →
`EMITTED`. E8's "partial data is discarded, never persisted" is explicitly not
applied here: it was written for an unstable weight, which is a non-measurement,
and applying it to a completed weight awaiting body composition inverts its own
rationale. Recorded at `02-interface-revision.md` §3's `flush()` paragraph, which
this pass corrected — it previously said the session flushes at post-emission
idle, which is the timer that cannot start.

**Item 3 — restate the load-bearing sentence.** Done, and this was the DA's
sharpest instruction ("a future reader will implement it literally").
`00-design.md` §2.1's persist rule now names **the correlated pair** as the
emission unit and states that nothing partial is ever written and later amended —
there is no second UPDATE carrying body composition, because `EMITTED` is not
reached until correlation closes. §8.1's process-death guarantee is amended to
match: it now names the buffered-weight case, explains why emitting at the Weight
frame would have produced silent partial loss wearing the shape of success, and
concedes that the 4 s E17 window is genuinely unprotected.

**Item 4 — the socks-on checklist row.** `01-plan.md` §5, **HW-25**, recording
which indications arrive and in what window. The DA's prediction is falsifiable
and the row is written to falsify it.

**Implementation status.** `ScaleDecoder.flush()` is implemented and tested
(`BeurerDecoderCaptureTest.weightFrameWithNoBodyCompositionIsReleasedOnFlush`).
The 4 s *timer* lives in `GattSession.run()`, which is a documented Phase 2 stub
(WP-10) — per the ground rules, the specification and the fixture
(`weight_without_bodycomp.scale`, `01-plan.md` §3.4) are fixed here and the run
loop stays stubbed.

---

## O-03 — Two users in one session cannot be correlated, and the failure writes someone else's body composition into JD's row

**DA severity: HIGH.** Disposition: "Fix now, cheaply", recommending option (a),
one weigh-in per session.

**Closed — and this one was a live bug, not a documentation gap.**

`02-interface-revision.md` §3 claimed to close it and did not. Traced and
confirmed before fixing:

```
onWeight(W1)            → pendingWeight = W1
onWeight(W2)            → no orphan; supersedes: emits W1 weight-only,
                          sets pendingWeight = W2
onBodyComposition(BC1)  → pendingWeight != null, so BC1 pairs with W2
```

`BC1` is user 1's body composition. It is persisted on user 2's row, under a
`userIndex` the wrong-user gate reads as correct. There is no signal in the frame
— no timestamp, no user ID, confirmed in `03-hardware-validation.md` §5 — that
could detect the mis-pairing.

**Fixed in code:**
`app/src/main/kotlin/com/ventouxlabs/bascule/ble/decoders/MeasurementCorrelator.kt`.
The latch is now **one emission per session, full stop**
(`MAX_EMISSIONS_PER_SESSION = 1`, replacing `MAX_USERS_PER_SESSION = 2`). A
superseding weight releases the buffered one weight-only and **closes**
correlation rather than becoming the new pending weight; every frame after that,
of either kind, is counted (`unpairableFramesDropped`) and dropped.

**A second entry point the DA did not name, closed by the same change.** After a
*completed* pair, a repeated body-composition frame was buffered as an orphan and
would have paired with the next weight frame — the identical misattribution
reached by a different route. Closing on every emission, not only on
supersession, is what covers both.

**Tests** (`app/src/test/.../BeurerDecoderCaptureTest.kt`), both of which fail
against the pre-fix correlator:

- `lateBodyCompositionAfterSupersededWeightIsDroppedNotMisattributed` — the exact
  `W1, W2, BC1-late` sequence. Asserts exactly one `Stable` for the whole
  sequence, that it carries W1's `userIndex` with **null** body-comp fields, that
  `BC1` returns `Ignored`, that `flush()` returns null afterwards (nothing is
  corrected retroactively), and that the drop counter reads 2.
- `bodyCompositionAfterACompletedPairIsDroppedNotHeldForTheNextWeight` — the
  second entry point.

**Cost, stated rather than hidden.** If a household member weighs first and JD
second inside one session, JD's reading is now lost where the buggy code would
have kept it. `00-design.md` §8.4's asymmetry decides it — bad Garmin history is
materially harder to clean up than a missed weigh-in is to redo — and the DA's
own evidence-against notes the scale most likely powers down between users. The
trade is written where it is made (the `MeasurementCorrelator` KDoc) and in
`00-design.md` §8.4.

**Dead machinery marked, not deleted**, on the DA's O-05(g) footing and matching
how `02-interface-revision.md` §5 treats `StabilityDetector`/`Live`:
`00-design.md` §2.1 carries a retired-edge note for `EMITTED → MEASURING`, E9 is
restated, `01-plan.md` §3.4 retires `second_user_index.scale` and replaces it
with `superseded_weight_late_bodycomp.scale`, and
`02-interface-revision.md` §3 carries the correction with the trace above.

---

## O-04 — The transport models notifications; the device uses indications

**DA severity: HIGH.** Disposition: "Fix now — cheaper before WP-04 and WP-02
than after."

**Closed, with a tracked residue.**

**Item 1 — neutral vocabulary.** Partly closed by WP-00 and completed here. The
skeleton had already added `GattOp.EnableIndications` and
`GattTransport.enableIndications` alongside the notify pair, and `ScaleDecoder`
declares the neutral `measurementCharacteristics` rather than
`notifyCharacteristics`. What remained notify-shaped was the event: this pass
replaced `TransportEvent.NotificationsEnabled` with
`SubscriptionEnabled(char, kind, status)` and added the `SubscriptionKind`
enum (`.../ble/session/GattTransport.kt`). Two distinct calls were kept rather
than the DA's single `Subscribe(char, kind)` — the effect is the same and the
call site cannot forget to pass a kind.

**Item 2 — make the failure testable in the fake.** `FakeGattTransport` now
records `subscribedCharacteristics: Map<UUID, SubscriptionKind>` instead of a
bare `Set`, so *which CCCD bit was written* is assertable rather than assumed
(`FakeGattTransportTest.notifyAndIndicateAreDistinguishableSubscriptions`,
`...recordsWritesAndSubscriptionsForAssertion`). This is the DA's stated minimum
("at minimum a fault-injection knob… so the failure has a test that can fail").

**Item 3 — the checklist row.** `01-plan.md` §5, **HW-29**.

**Residue:** the ATT confirmation model — one outstanding indication at a time,
30 s transaction timeout — is **not** modelled in the fake. That is stated
explicitly in `01-plan.md` §3.1's new note and is the second half of HW-29.
Modelling it is only worth doing once real back-to-back indication timing has
been observed.

`01-plan.md` §3.1's `GattTransport`/`TransportEvent` block was stale against all
of the above and is updated, with the reason recorded: this is the abstraction
whose failure mode is *silence*, and the fake was previously certifying it.

---

## O-05 — ADR-007's staleness is much wider than §2.6, and the merge order builds the stale interface first

**DA severity: HIGH.** Disposition: "Fix now — the cheapest high-value fix in the
report."

**Closed**, all seven sub-items.

**(a) §9's constants table.** `00-design.md` §9 now carries a provisional banner
matching §2.6's, stating that every symbol in the table belongs to a protocol the
BF720 does not speak, that `SigWeightProfile.kt` replaces it, and that leaving it
unmarked is worse than leaving it blank because the values would *look* sourced.

**(b) WP-05's sourcing.** `01-plan.md` WP-05 is retitled `SigWeightProfile` and
re-sourced: **Bluetooth SIG specifications** cross-checked against openScale's
`StandardWeightProfileHandler.kt` / `StandardBeurerSanitasHandler.kt`, **not** the
Beurer/Sanitas wiki page. Its constants are marked **confirmed**
(2026-08-22), not `UNCONFIRMED` — the DA's point that the marker had stopped
carrying information in both directions — and the guard test is inverted
accordingly (`everyConstantIsDatedOrMarkedUnconfirmed`). The package is re-costed:
the fixture corpus needs rewriting rather than re-valuing, because §3.2's
symbolic-name property holds for *values* and the *shape* changed.

**(c) WP-30's "data-only diff".** `01-plan.md` WP-30 now opens by stating that
**its own tripwire has fired**: the plan said a structural change would be "a P1
finding and an ADR", and it is — ADR-007, implemented as WP-00. The expected diff
is re-costed to what genuinely remains data-only.

**(d) The fixture corpus.** `01-plan.md` §3.4: `second_user_index.scale` retired,
`superseded_weight_late_bodycomp.scale` and `weight_without_bodycomp.scale`
added, `starving_connect.scale` added (see O-09c). The corpus-wide rewrite is
scoped in WP-05's re-cost note.

**(e) §1.2's data-flow diagram.** Redrawn. It now shows the UDS handshake node on
`2A9F` with `EncryptedConsentStore` attached, the consent-gated subscribe step,
the correlation step between `BeurerDecoder` and user attribution, and a
handshake-failure exit (E6/E19). The DA's point was that this is Phase 0
deliverable #1 and a reader taking it at face value implements pre-ADR-007
behaviour — the identical defect self-review item 24 fixed one cycle earlier.

**(f) The merge order.** `01-plan.md` §2 gains **WP-00 "Interface revision per
ADR-007"**, recorded retroactively with the full file list and what it produced,
and §4.3's strict prefix becomes `WP-01 → WP-00 → WP-02 → WP-04 → WP-05 → WP-06 →
WP-07 → WP-08`. WP-02 is now the residue of the domain types WP-00 did not touch.
WP-03 leaves the prefix (O-07).

**(g) Branch B's cost.** `01-plan.md` §2.2, a new subsection: **keep, cost
acknowledged.** Branch B stays for PRP §2's pluggable-decoder goal, and the entry
names the four packages carrying the cost (WP-15's eight tests, WP-23's decline
affordance, WP-26's held-confirm notifications, WP-28's
`branchBReadingIsHeldAndNotDelivered`), states that they exercise code no BF720
session can reach, and names the revisit trigger — a v1 scope squeeze, where
Branch B is the first thing to defer.

**Also:** §4.1's "28 of 31" claim is re-costed to 29 of 32 and states which
package *sizes* changed, since the DA noted it was asserted on the old shapes.

---

## O-06 — "A status makes the safe behaviour structural" is only half true

**DA severity: HIGH.** Disposition: **split by the DA itself** — "fix later, with
a tracked issue for the general property; **fix now** for the two concrete test
gaps."

**Split, accordingly. Two items closed, one downgraded — on the DA's own terms.**

**Item 1 — `ReplayEligibilityTest.onlySentRowsAreEligible`: downgraded to tracked
follow-up.** Legitimate because the underlying logic **does not exist**:
`DeliveryCoordinator` is a constants-only Phase 2 stub
(`.../delivery/DeliveryCoordinator.kt`, `PLANNED_IN = "WP-21"`) and the replay
worker is WP-22. There is nothing to parameterise over. Tracked as Phase 3
test-debt owned by **WP-22**, with the scope the DA specified: parameterised over
all six statuses, converting §4.4's prose scoping ("**A `SENT` row** is
replay-eligible when…") into an enforced invariant. This is the objection's
weaker half by the DA's own admission — its evidence-against concedes a faithful
implementer filters on `SENT` and `DECLINED` never enters the path.

**Item 2 — `DedupPolicyTest`: closed.** `DedupPolicy` is real, implemented code,
so this one had to be written and was. New file
`app/src/test/kotlin/com/ventouxlabs/bascule/delivery/DedupPolicyTest.kt`:

- `everyStatusHasAnExplicitCorpusMembershipDecision` — asserts the membership map
  covers exactly `ReadingStatus.entries`, so **adding a seventh status fails a
  test** rather than silently inheriting corpus membership.
- `dedupCorpusMembershipIsExplicitPerStatus` — parameterised over all six.

**Honesty about coverage:** the corpus filter lives in **two** places —
`DedupPolicy.isDuplicate` (Kotlin) and `ReadingDao.dedupCandidates` (SQL,
`status != 'DECLINED'`). This JVM-lane test covers the first only. The SQL half
needs a Room instrumented test and is Phase 3 (WP-14), alongside the rest of the
instrumented suite `02-ci-notes.md` records as unwritten.

**Item 3 — soften ADR-006's claim: closed.** `decisions.md` ADR-006, "Why a
status and not a boolean", now carries a **Qualified in Phase 2** paragraph using
the DA's own wording: a status makes the safe behaviour structural **for
allowlist predicates**, and the design must keep its status-filtering predicates
allowlist-shaped or test them exhaustively. It concedes that §3.3's denylist
shape is itself *reasonable* (a dedup check that misses rows creates duplicates)
and locates the error precisely in the unqualified word — "an unqualified claim
is how the next reviewer stops looking."

**One extra finding, surfaced by writing the test.** `00-design.md` §3.3 promised
a boundary test "at 0.20 vs 0.21 kg". That assertion is **not decidable** against
a `<=` comparison of `Double`s: `90.20 - 90.00` evaluates to
`0.2000000000000028`, which is outside a 0.20 tolerance, so the nominal boundary
case is *not* a duplicate. §3.3 now records this, `DedupPolicyTest` brackets the
tolerance instead of asserting the knife edge, and **WP-14** owns the fix —
compare scaled integers, the way `MeasurementCorrelator`'s frame identity already
does, or restate the rule.

---

## O-07 — RISK-1 is scheduled first against a risk the project's own evidence says may not exist

**DA severity: HIGH.** Disposition: "Fix now — a re-ranking, not a deletion."

**Closed. Decided, not flagged.**

`01-plan.md` §1 carries a **Re-ranked in Phase 2** block ahead of the original
reasoning, which is preserved unedited because it was sound on the evidence
available when written:

| | Was | Now |
|---|---|---|
| RISK-1 | Stabilization detection (WP-03) | **UDS register/consent handshake (WP-07)** |
| RISK-2 | Init handshake sequencing (WP-07) | **Stabilization detection (WP-03)**, demoted |
| RISK-3 | Wake path (WP-08) | unchanged |

The discriminating test is re-run explicitly (consequence × likelihood ×
retirable-now) and the handshake scores high on all three. **WP-07 is re-scoped**
in place — no new package number — from the stateless `initSequence()` model to
the three-branch UDS exchange, with the consent gate, the `scaleIndex`
persistence, and E19 named among the things it must prove.

**WP-03 is demoted, not deleted**, with its rationale changed to
"**portability and fallback guard**, not live-path risk" per
`02-interface-revision.md` §5, and moved out of the strictly-ordered prefix into
the decode lane after WP-10 (§4.3). HW-08's pass criterion becomes "confirm
whether any live/intermediate weight indications occur at all" — the observation
that would restore it, since the DA correctly noted the demotion rests on a
single capture and one capture is thin evidence for a negative claim about
streaming behaviour.

**§7's exit-gate self-check is rewritten.** It previously certified the stale
ranking as "the design's judgment is upheld". It now states the Phase 2 ranking,
names what changed, and points at §1 for the evidence that overturned it.

**Provenance note.** `02-interface-revision.md` §5 raised this re-ranking and
deliberately declined to act — "flagged for the lead rather than acted on
unilaterally", since it argued the plan's *ordering* was wrong rather than its
content. The lead authorised it as part of this reconciliation. Both §1's block
and §5's new **Resolved** note record that chain, because a decision a previous
document refused to make needs its authorisation visible.

---

## O-08 — The consent code is an unowned credential, and `allowBackup="false"` guarantees it is lost on every device change

**DA severity: HIGH.** Disposition: "Fix now for the design gaps; checklist row
for the prediction."

**Closed, with tracked residues that are the DA's own checklist rows.**

**(a) No work package owns it — closed by WP-00.** `ConsentStore` and
`EncryptedConsentStore` exist
(`.../ble/session/ConsentStore.kt`, `EncryptedConsentStore.kt`), `GattSession`
takes the store as a constructor dependency and exposes `handshakeContext()` /
`rememberCredential()`, and `01-plan.md` WP-00 lists both files. The DA's
requested tests — mirroring `AuthTokenStore`'s, **including the file-bytes test
that proves the code is not stored in plaintext** — are named in **WP-19** and are
**unwritten**: `EncryptedSharedPreferences` needs a real keystore, so they are
instrumented, and `02-ci-notes.md` records that no emulator was started this
phase. The JVM lane substitutes an in-memory `ConsentStore`, which proves the
interface and proves nothing about the encryption. Tracked residue, owner WP-19.

**(b) Absent from the threat review — closed.** `00-design.md` §8.8 gains two
paragraphs: the consent code as the app's second credential (encrypted store
only, never in a log line, `lastError`, or a frame dump — the opcode-and-length
rule covers the UCP write's code bytes by construction), and its
**non-portability as a deliberate consequence with the re-registration cost
named**.

**(c) The `allowBackup="false"` collision — closed as a stated trade.** §8.8 now
says plainly that the rule written to protect the token also guarantees the
consent mapping does not survive migration, reinstall, or "clear app data"; that
the recovery is re-registration; that the scale holds 8 slots and the live
capture already came back as index 2, so slot 1 was taken by something that is
not Bascule; and that a portable consent code would have to leave the device in a
form ADB backup can read, which is the hole the rule closes. The trade is
accepted **because the failure is now visible rather than silent** — see (d).

**(d) Failed-registration behaviour — closed.** `00-design.md` §2.3 **E19**:
trigger (UCP failure for opcode `0x01`, or pool exhausted), outcome
**`SessionOutcome.HandshakeFailed("scale refused Register New User")`** — an
existing sealed-type case, deliberately not a new one, and the value
`BeurerDecoder.onRegistrationEvent` already produces — counter
**`registrationRejected`** (registered in `01-plan.md` §2.1, owner WP-07), and the
user-facing message: *"The scale's user profiles are full — delete one in the
Beurer app, then step on the scale again."* Without E19 this case presents as
E6/E7: a handshake that never completes, silently, forever.

**(e) Investigate `2A9A` and delete-user — tracked.** `01-plan.md` §5,
**HW-27**, which also asks for the definitive UCP response byte for "pool full"
so E19 fires on a signal rather than on a timeout.

**(f) §7 Branch A's onboarding sentence — closed.** `00-design.md` §7 now states
that **the index is assigned by the scale, not discovered by the user**: it comes
back in the Register reply and is persisted at that moment or lost. ConfigScreen
*displays* it read-only with a "Re-register with the scale" action, rather than
offering a 1–8 picker filled in after weighing once. The old sentence was wrong
in both direction and timing — the index exists before the first weigh-in, and no
weigh-in produces data at all until consent is granted. **WP-25 is re-scoped**
accordingly in `01-plan.md`, with two new named tests, and WP-15's
`branchAMatchingIndexPersistsAsPending` is noted as now comparing against a
consent-store value rather than a typed config field.

**(g) Registration-epoch column — decided and recorded, not added.**
`00-design.md` §3.1 carries the decision with its reasoning: **no
`registrationEpoch` column in v1**, because nothing would read it — Branch A
compares an incoming reading against the *current* index at the persistence
boundary and never re-evaluates stored rows, and a column no code consults is a
column that drifts. Reversible at low cost while v1 is unshipped (schema version
1, no migration). The revisit trigger is named: the first feature that re-reads
`userIndex` on stored rows.

**(h) The checklist row for the slot-burn prediction — added.** `01-plan.md` §5,
**HW-26**: register twice from a wiped app, record whether `scaleIndex` is reused
or incremented. Like HW-01, it names **no covering fake-layer test on purpose** —
no fake can know what the scale's slot allocator does.

---

## O-09 — ADR-004's "retired in CI" claim does not retire what it says, and the CI matrix misses the only device that exists

**DA severity: MEDIUM-HIGH.** Disposition: "**Fix later, with tracked issues** —
none of this blocks a merge, but the justification should stop overclaiming."

**Closed on the wording (done now, though the DA allowed later); the CI matrix
downgraded to a checklist row, which is what the DA asked for.**

**Item 1 — restate the WP-08 rejection honestly.** `01-plan.md` §1 gains a
paragraph, **"What position 8 actually retires, stated honestly"**. It concedes
the DA's point in full: `TestListenableWorkerBuilder` invokes a worker in
isolation, outside WorkManager's real expedited scheduling and outside the
platform's background-start restrictions, which are what ADR-004's claim is
*about*. Position 8 therefore retires the **plumbing**; the platform-permission
claim is retired at **HW-01 / WP-29** on a real device. The circularity is named
("WP-08 was demoted because its risky half was retired early, and its risky half
is not retired early"). **The demotion is kept** — the DA agreed it survives —
but on the correct reasoning: ADR-004's path is documented Android practice, so
its *likelihood* of being wrong is low even though its consequence and reversal
cost are high. §7's self-check is updated to match.

**Item 2 — the CI emulator matrix.** Downgraded to a tracked row, explicitly
**not** fixed by re-imaging CI, which the DA also concluded ("the API-37 gap is
partly unavoidable — emulator images lag"). `01-plan.md` §1 records the arithmetic
(34 < 37), says WP-01 should add the highest image actually available rather than
chase one that may not exist, and adds **HW-28**: run the wake path on the real
API 37 device *early*, before twenty more packages are built on ADR-004.

**Item 3 — the starving-connect gap.** Fixture `starving_connect.scale` added to
`01-plan.md` §3.4, with the honest caveat the DA's own §3.6(b) implies: the
fixture cannot *distinguish* a starving contention from a genuine E7 — nothing
inside Bascule can — but it pins the behaviour and the counter, so
`Missed(CONTENTION)` undercounting is a known bias rather than an invisible one.
This also feeds O-11.

---

## O-10 — Two clocks, and two constants whose stated justifications are factually wrong

**DA severity: MEDIUM-HIGH.** Disposition: "(a) fix now as a documentation and
escalation change; (b) fix now as two comment edits."

**Closed, with a tracked implementation residue.**

**Item 1 — decide and record the Current Time write.** **Decided: yes.**
`00-design.md` §4.4 records it — Bascule writes `2A2B` as the first step of every
handshake, before Register or Consent — with the reason: the probe did exactly
this and the resulting frame timestamp matched the written value to the second
(`03-hardware-validation.md` §5), which is the only thing that makes the scale's
timestamp trustworthy. An unset RTC drifts or resets on a battery change, and "a
timestamp nobody sets is garbage the design would be lucky not to read" is the
DA's point, conceded. **Tracked residue:** `BeurerDecoder.beginHandshake`
currently opens with Register/Consent and does **not** issue the CTS write.
Issuing it is **WP-07** — it adds a handshake state, which is that package's job.
The design says so rather than implying the code already does it.

**Item 2 — store the scale's timestamp.** `ScaleReading.scaleTimestampMillis`
already existed from WP-00; this pass **finished threading it into persistence**.
`ReadingEntity.scaleTimestampMillis` added
(`.../data/ReadingEntity.kt`), the exported Room schema regenerated
(`app/schemas/*/1.json` — verified to contain the column), and `00-design.md`
§3.1 gains the column row with the reason it is kept *alongside*
`capturedAtMillis` rather than replacing it: dedup and the history sort key run
on the phone clock, but a reading the scale buffered and delivered later would
otherwise record its *delivery* time as its capture time.

**Item 3 — fold the clock question into A6.** One line, not a relitigation.
`00-design.md` §4.4's escalation paragraph and `01-plan.md` §6's open-items table
both carry it: *which timestamp does VitalForge store, and which one should
replay join on?* Both values are now available locally, so the answer is a shaper
change either way — which is precisely why it is a line on an existing escalation
rather than a new one.

**Item 4 — correct the two false LSB claims.** Both corrected verbatim, with the
**values kept** and the justifications replaced:

- `00-design.md` §3.3 — ±0.20 kg is no longer "2 LSBs of the BF720's 0.1 kg
  resolution". The confirmed resolution is **0.01 kg** with a **×0.005 kg** raw
  multiplier, making 200 g 20–40 LSBs and a re-reported tick **5 g**, not 100 g.
  The value stands on physiology.
- `00-design.md` §2.4 — ±0.1 kg is no longer "one display LSB"; it is sized by
  step-on dynamics.
- The same correction is made in the code comment, which is where §3.3 promised
  the rationale would live:
  `.../delivery/DedupPolicy.kt`'s `WEIGHT_TOLERANCE_KG` KDoc. The DA's argument
  for doing this — "shipping a comment that states a false hardware fact is worse
  than shipping no comment, because the next person to tune the constant reasons
  from it" — is why the comment was edited and not just the document.

No value changed, so no WP-14 or WP-03 boundary test needed updating.

---

## O-11 — E7 is now the exact signature of a lost consent, and it has no counter, no streak guard, and no diagnosis

**DA severity: MEDIUM.** Disposition: "**Fix later, with a tracked issue**, but
decide item 1 during the interface revision because it is nearly free."

**Closed, with one tracked residue — items 1–3 done rather than deferred.**

**Item 1 — gate `SUBSCRIBED` on consent.** This is the crux the DA identified:
"the fix depends entirely on the interface revision placing the consent gate
before subscription." **Confirmed and specified.** In code,
`BeurerDecoder.onConsentEvent` returns `HandshakeDirective.Complete` only on
`ConsentResult(success = true)`, and `Abort` otherwise — the session cannot reach
subscription without it. In the specification, `00-design.md` §2.3 **E6** now
states the gate explicitly, in the DA's terms: `SUBSCRIBED` is gated on
`DecodeEvent.ConsentResult(success = true)`, "because an unconsented subscriber
receives nothing at all", and without the gate a lost consent would present as
45 s of silence rather than the handshake failure it is. The session-level
enforcement lives in `GattSession.run()` — a Phase 3 stub — and its test already
exists and **already fails on purpose**:
`ScaleSessionContractTest.theSessionSubscribesOnlyAfterConsentIsGranted`
("the session never sent Consent"), one of the three documented reds in
`02-ci-notes.md`.

**Item 2 — the `noMeasurement` counter.** Added to `01-plan.md` §2.1's registry
with an owning package (**WP-10**) and a named incrementing test, since
`PersistentDiagnosticsCountersTest.everyCounterKeyIsOwnedByExactlyOnePackage`
enforces that table mechanically and a counter without a row is a latent break.
The registry note explains why E7's absence was by design when written and is not
any more.

**Item 3 — a consent failure surfaces as handshake-shaped, and E7 gets a
streak.** Both done. E19 (O-08d) is the handshake-shaped outcome for a refused
registration; E6 covers a refused consent. E7's recovery in `00-design.md` §2.3
now carries a **3-consecutive-session streak** mirroring E4's, because even with
the consent gate in place a starving connect under Atlas contention still lands
in E7 (O-09c) and would otherwise repeat silently, once per weigh-in, forever.

**Item 4 — HistoryScreen must show sessions that produced nothing.** **Downgraded
to tracked follow-up**, legitimately: the DA's overall disposition for O-11 is
"fix later, with a tracked issue", and this item needs UI that Phase 2 explicitly
does not build (`02-interface-revision.md` §7 lists `ui/` as stubbed). Tracked in
`01-plan.md` **WP-23** as
`HistoryScreenTest.showsSessionsThatProducedNoReading`, carrying the DA's
reasoning: HistoryScreen is documented as "the single answer to *did my weigh-in
reach Garmin*", and a history that can only show rows answers it in every case
except the one where it is actually being asked.

---

## Not dispositioned, deliberately

- **ADR-002 (openScale licensing).** Out of scope by construction — settled by
  the agent prompt's ground rules, ring-fenced from review cycles, and the DA's
  own §13 lists it as "a *decision* not to look, rather than a look that found
  nothing." Not relitigated here.
- **V2 contract field names.** `00-design.md` §4.2 and the DA's O-05 are both
  explicit that these are pinned from VitalForge's Track A document when it
  arrives. `V2Shaper`'s key strings stay blank. Nothing was invented.
- **The DA's §13 list** — the retry ladder arithmetic, §4.5's HTTP table, §6's
  permission matrix, the `PHONE` bucket concept, §3.6's fake-layer limits. The DA
  read these and found nothing, and says explicitly that this "is not the same as
  having been verified." They are not verified here either. The absence of a
  finding is not a clearance, and this file does not convert one into the other.
