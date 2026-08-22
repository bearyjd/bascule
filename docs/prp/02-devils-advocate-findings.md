# Bascule — Phase 2 Devil's Advocate Findings

**Reviewer role:** hostile reviewer, no prior context on this project. Inputs read
in full: `bascule-prp.md`, `00-design.md`, `01-plan.md`, `decisions.md`,
`03-hardware-validation.md`.

**`02-interface-revision.md` did not exist at review time** (checked twice, at the
start and immediately before writing this file). It is therefore not reviewed
here. See §12 for the specific objections below that a `ScaleDecoder` interface
rewrite alone would **not** close — that is the useful half of the negative
answer.

**Verdict:** 11 substantive objections. **2 CRITICAL, 6 HIGH, 2 MEDIUM-HIGH,
1 MEDIUM.** Two of them (O-02 and O-06) attack language the authors explicitly
marked load-bearing or structural.

The headline: **ADR-007 is being treated as a decoder-interface problem. It is
not.** It is a change of protocol family — from a proprietary Beurer opcode
stream to the Bluetooth SIG Weight/Body-Composition/User-Data profile — and its
consequences reach the Room schema, the PRP's own scope promises, the credential
inventory, the risk ranking, the fixture corpus, and the merge order. Marking
`00-design.md` §2.6 "provisional" is a note on the one place the authors happened
to look.

| # | Objection | Severity | Attacks |
|---|---|---|---|
| O-01 | Schema cannot hold the confirmed payload; PRP §2 violated on the first real reading | **CRITICAL** | `00-design.md` §2.7, §3.1; PRP §2 |
| O-02 | Weight↔BodyComp correlation has no timeout and breaks the "load-bearing" EMITTED persist rule | **CRITICAL** | `00-design.md` §2.1, §2.3, §2.6 |
| O-03 | Two-user session + unidentified body-comp frames = another person's body composition in JD's row | **HIGH** | `00-design.md` §2.3 E9, §8.4 |
| O-04 | Indications, not notifications — the entire transport vocabulary is wrong and the fake hides it | **HIGH** | `00-design.md` §2.6; `01-plan.md` §3.1 |
| O-05 | ADR-007 staleness reaches §9, WP-05, WP-30, the fixture corpus, §1.2's diagram, and the merge order | **HIGH** | `01-plan.md` §0.1, §4.3; `00-design.md` §9 |
| O-06 | ADR-006's "structural" safety claim is only half true | **HIGH** | ADR-006; `00-design.md` §3.2, §3.3, §4.4 |
| O-07 | RISK-1 ranking is falsified by the project's own hardware evidence | **HIGH** | `01-plan.md` §1 |
| O-08 | Consent code is an unowned credential that collides with `allowBackup="false"` | **HIGH** | ADR-007; `00-design.md` §8.8, §7 |
| O-09 | ADR-004's "retired in CI" claim is optimistic; CI matrix misses the only real device | **MEDIUM-HIGH** | `01-plan.md` §1, §3.6(c), WP-01 |
| O-10 | Two clocks, and two dedup/stability rationales are factually wrong | **MEDIUM-HIGH** | `00-design.md` §3.3, §2.4, §4.4 |
| O-11 | E7 is now the signature of failed consent, with no counter and no streak guard | **MEDIUM** | `00-design.md` §2.3 E7 |

---

## O-01 — The schema cannot hold the payload that was actually captured

**Severity: CRITICAL** · **Attacks:** `00-design.md` §2.7 (`ScaleReading`), §3.1
(`ReadingEntity`), against `03-hardware-validation.md` §5 and PRP §2.

### The attack

PRP §2 makes two promises in consecutive bullets: **"Full BIA payload capture"**
listing weight, fat %, water %, muscle %, bone mass, BMI, BMR, AMR; and
**"Local store is authoritative for capture… nothing is discarded at the point of
measurement."** `ScaleReading` and `ReadingEntity` are built to exactly that
field list.

The one real frame anyone has captured does not fit that list in either
direction.

**Fields captured on hardware that have no column anywhere:**

| Captured (`03-hardware-validation.md` §5) | Column in §2.7 / §3.1 |
|---|---|
| Impedance, 437.0 Ω | **none** |
| Soft lean mass, 49.08 kg | **none** |
| Body water **mass**, 36.96 kg | `bodyWaterPct` — a percentage, not a mass |
| Height, 1.700 m | none (minor; derivable from config) |
| Scale-side timestamp | none — see O-10 |

Impedance is the raw measured signal; every other body-comp number is a vendor
formula applied to it. Discarding it means body composition can **never** be
recomputed later with a better or corrected formula — and recomputation is
precisely the kind of thing "the local store is authoritative for capture" exists
to make possible. Dropping impedance while claiming nothing is discarded at the
point of measurement is the sharpest form of this objection and needs no further
hardware evidence: the byte pair `12 11` was decoded, named, and then has
nowhere to go.

**Fields the design promises that the standard-profile path does not appear to
supply:** `boneMassKg` and `amr`. The SIG Body Composition Measurement
characteristic has no bone-mass or active-metabolic-rate field, and
`03-hardware-validation.md` §4 reports this unit's feature bitmap as *not*
supporting Muscle Mass or Fat-Free Mass either. Two fields PRP §2 lists as
in-scope are, on the path the design now depends on, unobtainable.

`bodyWaterPct` is the quiet one: the frame carries water in **kg**, the column
stores a **percentage**. Deriving one from the other is a division by weight —
lossy, unspecified anywhere in the design, and a silent precision change in a
field the PRP treats as first-class.

### Evidence for

- `03-hardware-validation.md` §5 decodes impedance and soft lean mass explicitly,
  with source bytes, and cross-checks the whole frame for internal consistency.
  This is not a guess about what the scale might send; it is what it sent.
- §4 of the same document reads the feature bitmap directly (`cf 31 00 00`) and
  enumerates supported fields. Bone mass is not among them.
- PRP §2's "nothing is discarded at the point of measurement" is unconditional
  language, and `00-design.md` §3.1's schema table is presented as complete
  ("all 18 columns" per WP-13).
- No ADR, no self-review item, and no work package mentions impedance or soft
  lean mass. This gap is currently invisible to the process.

### Evidence against

- The two proprietary services `0x0000FFFF` and `0x0000FF00` were **explicitly
  not exercised** (`03-hardware-validation.md` §"What was NOT yet confirmed").
  Bone mass and AMR — which the Beurer consumer app does display — may well live
  there. So "unobtainable" is too strong; "unobtainable via the standard-profile
  path the design now depends on, with no work package touching the services that
  might carry them" is the defensible claim.
- Adding columns pre-v1 is free (§3.1 ships schema version 1 with no migration),
  so the *fix* is cheap. The severity is CRITICAL because of what happens if it
  is not noticed until after v1 ships: PRP §2's whole point is that readings are
  irreplaceable, and a reading captured without its impedance cannot be
  back-filled.
- `bodyWaterPct` may have been chosen deliberately for VitalForge-side
  compatibility. Nothing says so, which is the problem.

### Disposition

**Fix now**, before WP-13 (Room schema) is written — it is upstream of the whole
persistence lane.

1. Add `impedanceOhms`, `softLeanMassKg`, and `bodyWaterKg` columns; keep
   `bodyWaterPct` as a derived presentation value or drop it.
2. Amend PRP §2's field list, or open an ADR stating that `boneMassKg` and `amr`
   are out of v1 scope for this device pending investigation of the proprietary
   services. Do not leave two nullable columns that will silently always be null
   and read as "the scale didn't report it this time."
3. Add a work package (or a WP-30 checklist row) to enumerate `0xFFFF`/`0xFF00`
   before declaring the field list closed.

---

## O-02 — Correlation has no timeout, and it breaks the rule the design calls load-bearing

**Severity: CRITICAL** · **Attacks:** `00-design.md` §2.1 (persist rule), §2.3
(E7, post-emission idle), §2.6 (`DecodeEvent`) · **This objection attacks
explicitly load-bearing language.**

### The attack

`00-design.md` §2.1 states, in bold, immediately under the state diagram:

> Persist rule, load-bearing: **the reading is written to Room at `EMITTED`,
> synchronously, before disconnect is requested.** Nothing after `EMITTED` can
> lose the reading.

ADR-007 establishes that a complete reading is now **two** frames: the Weight
Measurement (`2A9D`, carries user ID, timestamp, BMI, height) and the Body
Composition Measurement (`2A9C`, carries fat/BMR/muscle/lean/water/impedance and
**no** user ID or timestamp of its own). Neither frame alone is a complete,
attributable reading.

That leaves exactly two options, and the design defines neither:

**Option A — emit at the Weight frame.** The `EMITTED` persist rule is honoured,
but the body-composition half arrives *after* `EMITTED` and must be written by a
second, later UPDATE. So "nothing after `EMITTED` can lose the reading" becomes
false for every body-comp field: a process death in the window between the two
indications loses the entire BIA payload, silently, leaving a row that looks like
a complete weight-only reading. §8.1's process-death analysis explicitly reasons
that death after `EMITTED` "loses nothing" — that reasoning no longer holds.

**Option B — wait for both, then emit.** The persist rule survives, but there is
**no timer anywhere in the design for "Weight arrived, Body Composition never
did."** Check the timeout table (§2.5) yourself:

- **E7** fires 45 s from `SUBSCRIBED` — it is satisfied the instant the *first*
  frame arrives, so it cannot fire here.
- **Post-emission idle** is 10 s from `EMITTED` — which by construction has not
  been reached.
- The only backstop is the **90 s hard session ceiling**, whose recovery is
  `TEARDOWN`. §2.3 E8 says partial data is "**discarded, never persisted**."

So under Option B, a weigh-in that produces a valid weight and no body
composition results in the weight being **thrown away** after 90 seconds of held
radio, with outcome semantics undefined. A user who steps on the scale, sees
their weight on the display, and gets nothing in the app — with the app's own
explanation being a 90-second timeout — is the worst diagnostic surface in the
document.

This is a hole in the specification regardless of *why* the second frame is
missing. But the reason it is likely to be common rather than exotic:

**Falsifiable prediction.** The BF720 measures bioimpedance through foot
electrodes. A weigh-in performed with socks or shoes on should produce a valid
Weight Measurement and **no** Body Composition Measurement (or one with an
impedance-failed indication). If that prediction holds, Option B discards a
perfectly good weight reading on what is probably the single most common
real-world variant of the primary use case. **Closed by:** a new checklist row —
weigh in with socks on, record which indications arrive and in what window.

### Evidence for

- ADR-007's second consequence states the problem plainly ("the two
  characteristics must be correlated within one session before either is treated
  as complete and attributable") and then explicitly declines to solve it,
  deferring to Phase 2 — which is this review.
- The §2.5 timeout table is presented as complete and every other phase boundary
  has a named timer. This one has none.
- §8.1's process-death guarantee is written as an absolute and is derived
  directly from the `EMITTED` rule.
- E8's "partial data is discarded, never persisted" was written for unstable
  weight — a non-measurement. Applied to a completed weight awaiting body comp,
  it discards a real measurement, which inverts its own rationale.

### Evidence against

- The socks scenario is a prediction, not an observation. The single capture in
  `03-hardware-validation.md` produced both frames.
- Option A's exposure window is genuinely small — the two indications in the
  capture appear to arrive close together — so the practical loss rate from
  process death in that window may be near zero. The objection to Option A is
  about the *guarantee* §8.1 makes, not the observed frequency.
- A reasonable author would say "obviously we add a correlation timeout." Agreed
  — but the value of that timeout, and what is persisted when it expires, are
  policy decisions with real consequences (throw away the weight, or persist a
  weight-only row), and this document is elsewhere extremely disciplined about
  putting a concrete number and a concrete action on every edge.

### Disposition

**Fix now.** This is the single most consequential unresolved item and it is
upstream of WP-07, WP-09, WP-10, and WP-13.

1. Add a named edge — call it **E17, body-composition correlation timeout** —
   with a concrete value (a few seconds from the Weight indication, not tens)
   and a concrete action.
2. Make the action **persist the weight-only row**, not discard it. PRP §5 is
   unambiguous: "Every decoded weight reading is precious." A weight without
   body composition is a complete, attributable, deliverable reading — VitalForge
   v1 only accepts weight anyway.
3. Restate §2.1's persist rule to name the correlated pair as the emission unit,
   and amend §8.1 so its process-death guarantee matches whichever option is
   chosen. Do not leave the bold "load-bearing" sentence standing unmodified — a
   future reader will implement it literally.
4. Add the socks-on checklist row.

---

## O-03 — Two users in one session cannot be correlated, and the failure writes someone else's body composition into JD's row

**Severity: HIGH** · **Attacks:** `00-design.md` §2.3 E9, §2.1 state diagram,
§8.4.

### The attack

The design deliberately supports two users per session. §2.1's state diagram has
the edge `EMITTED --> MEASURING: second distinct userIndex (max 2)`, E9 codifies
"at most **2** distinct userIndexes per session," and `01-plan.md`'s fixture
corpus contains `second_user_index.scale` driving exactly this.

Now add ADR-007's finding that Body Composition frames carry no user ID. A
two-user session produces four indications with only two of them self-
identifying:

```
2A9D  Weight, user 2      → identifiable
2A9C  Body Composition    → belongs to… ?
2A9D  Weight, user 5      → identifiable
2A9C  Body Composition    → belongs to… ?
```

Correlation "with the paired Weight Measurement frame from the same session"
(ADR-007's wording, and `03-hardware-validation.md` §5's) is well-defined for one
user and **ambiguous for two**. The design has no per-user correlation buffer,
no statement that indications are strictly ordered W→BC→W→BC, and no evidence
that they are — the capture exercised one user, once.

If the ordering ever differs from the assumed interleave — both weights first,
or a late body-comp frame arriving after the state machine has already returned
to `MEASURING` for the second user — the correlation attaches **another
household member's body fat, muscle mass, and impedance to JD's weight row**,
which is then persisted, delivered, and written into Garmin history under JD's
identity.

§8.4 names this exact class as the worst outcome in the design: "Bad Garmin
weight history is materially harder to clean up than a missed weigh-in is to
redo." The multi-user machinery (E9, the `EMITTED → MEASURING` edge) was designed
under §2.6's model where each notification is independently complete and
attributable. That model is dead, and the machinery built on it was not
re-examined.

Note also that Branch A's protection does not help: the *weight* frames are
correctly attributed and correctly filtered by user index. It is the *body
composition* that is mis-joined. The wrong-user gate sits at the persistence
boundary and inspects `ScaleReading.userIndex`, which will say "2" — correctly —
on a row whose body-comp fields came from user 5.

### Evidence for

- E9 and the `EMITTED → MEASURING` edge exist and are explicitly numbered
  ("max 2"), so this is a supported path, not an edge case someone invented.
- `03-hardware-validation.md` §5 confirms the body-comp flags declare no user ID
  and no timestamp — there is no in-band signal to disambiguate.
- The BF720 is explicitly an 8-profile auto-recognition scale (PRP §2, and the
  feature bitmaps in `03-hardware-validation.md` §3–4 both report "Multiple Users
  Supported"), so multiple household members using it is the designed-for case.
- PRP §8.5's entire concern is household members' readings being misattributed.
  This is that concern, displaced from weight to body composition, where none of
  the §7 machinery looks.

### Evidence against

- Two users completing weigh-ins inside one BLE session may be rare or
  impossible in practice — the scale likely powers down between users, producing
  two separate sessions and two separate advertisements. If so, E9's max-2 is
  dead machinery and the ambiguity never arises.
- Even if it does arise, the practical ordering is probably W→BC→W→BC, which
  correlates correctly under a naive "most recent unpaired weight" rule.
- Branch A means a second user's *weight* row is dropped at the persistence
  boundary, so a mis-joined body-comp on a dropped row is harmless. The damage
  requires the mis-join to land on JD's row specifically.

None of these are established; all three are assumptions of the same kind that
ADR-007 has already invalidated once.

### Disposition

**Fix now**, cheaply, by choosing one of two:

- **(a) Restrict to one user per session.** Drop the `EMITTED → MEASURING` edge,
  make E9's latch "one emission per session, full stop," retire
  `second_user_index.scale`. Justification: with body-comp correlation in play,
  a second user in one session is more risk than value, and the scale almost
  certainly produces a fresh advertisement per user anyway. This is the smaller,
  safer change and it deletes code rather than adding it.
- **(b) Keep two users** and specify the correlation rule explicitly — a
  per-session map keyed by the most recent unpaired Weight indication, with a
  stated rule that an unpairable body-comp frame is **dropped, never
  speculatively attached** — plus a checklist row observing real two-user
  ordering before anyone trusts it.

Recommend **(a)**. Either way this needs an explicit decision recorded, because
the current text supports two users while making correlation undefined for them.

---

## O-04 — The transport models notifications; the device uses indications

**Severity: HIGH** · **Attacks:** `00-design.md` §2.6 (`GattOp`, `ScaleDecoder`),
`01-plan.md` §3.1 (`GattTransport`, `TransportEvent`), §3.5.

### The attack

Every layer of the transport vocabulary is notification-shaped:

| Location | Symbol |
|---|---|
| `00-design.md` §2.6 | `GattOp.EnableNotifications(char)` |
| `00-design.md` §2.6 | `ScaleDecoder.notifyCharacteristics: Set<UUID>` |
| `00-design.md` §2.6 | `ScaleDecoder.onNotification(characteristic, value)` |
| `00-design.md` §2.3 | E7 "no measurement frame within 45 s", E11 "notification callback" |
| `01-plan.md` §3.1 | `GattTransport.enableNotifications(char)` |
| `01-plan.md` §3.1 | `TransportEvent.NotificationsEnabled` |
| `01-plan.md` §3.5 | fault-injection knob `notifyEnableStatus` |
| `01-plan.md` §3.2 | fixture directives `@onEnableNotify`, `NOTIFY` |

`03-hardware-validation.md` §1 reports the three characteristics that matter as
**indicate**, not notify: `2A9D` Weight Measurement (indicate), `2A9C` Body
Composition Measurement (indicate), `2A9F` User Control Point (write +
**indicate**).

Notifications and indications are different ATT operations. On Android they are
enabled by writing *different* values to the Client Characteristic Configuration
Descriptor — the notification bit versus the indication bit — and indications
require the stack to send a Handle Value Confirmation for each one, which
serialises them (one outstanding indication at a time, with a 30-second ATT
transaction timeout).

Concretely: an implementation that faithfully executes
`GattOp.EnableNotifications(2A9D)` writes the notification bit to a
characteristic that does not support notify. The descriptor write either fails
outright or succeeds while the device sends nothing — and **"connected,
subscribed, zero measurement traffic across multiple complete weigh-ins" is
exactly the symptom `03-hardware-validation.md` already documented once**, before
the UDS handshake was found. The project has already spent a hardware session
chasing that symptom. This bug reproduces it.

The compounding problem is the fake. `FakeScaleGatt` has a `notifyEnableStatus`
knob and a `NotificationsEnabled` event and no concept of an indication or a
confirmation. So CI stays green — all 22 fixtures and every one of WP-06/07/09/10's
named tests pass — on code that receives nothing from the real device. That is
`01-plan.md` §3.6's stated goal ("every failure edge that originates from the
radio is reproducible in a JVM unit test") failing in the direction that matters:
the fake does not merely under-test this, it actively certifies it.

Note that ADR-007's provisional note on §2.6 does **not** mention this. It calls
out `initSequence` and `DecodeEvent`. The notify/indicate mismatch sits in
`GattOp` and `GattTransport` and is untouched by the revision the note asks for.

### Evidence for

- `03-hardware-validation.md` §1 lists the characteristic properties explicitly,
  including "`2A9F` User Control Point, write+indicate" and "`2A9D` Weight
  Measurement, indicate."
- The transport vocabulary is uniformly notify-only across both documents — this
  is not one overlooked call site, it is the whole abstraction.
- The design is otherwise scrupulous about platform-specific GATT hazards (E2's
  status-133 close discipline, §8.10's connection-limit note), which makes this
  omission a genuine outlier rather than a deliberate simplification.
- The consequence — silent absence of data — is the failure mode the design most
  fears elsewhere (§6.2 calls a silently-empty scan "the worst possible failure
  mode").

### Evidence against

- This is arguably an implementation detail that a competent implementer fixes on
  first contact with hardware, without a design change: rename to `enableCccd`
  and pass the right descriptor value.
- WP-30's hardware session would catch it immediately (the app would produce no
  readings at all — loud, not silent, at the system level).
- The `AndroidGattTransport` is specified as carrying "no logic," so the
  descriptor-value choice arguably belongs there and is invisible above it.

The rebuttal fails on the last point, though: the *decoder* declares
`notifyCharacteristics`, so the notify/indicate distinction is already leaking
above the transport, and `AndroidGattTransport` cannot decide it without being
told.

### Disposition

**Fix now** — it is a rename plus one modelling addition, and it is far cheaper
before WP-04 (the fake) and WP-02 (the types) than after 22 fixtures exist.

1. Rename to a neutral `GattOp.Subscribe(char)` / `GattTransport.subscribe(char)`
   / `TransportEvent.SubscriptionEnabled`, and have the decoder declare
   `subscribeCharacteristics: Map<UUID, SubscriptionKind>` where kind is
   `NOTIFY` or `INDICATE`.
2. Give `FakeScaleGatt` a confirmation model, or at minimum a fault-injection
   knob for "CCCD written with the wrong bit → no traffic," so the failure has a
   test that can fail.
3. Add a checklist row asserting the CCCD write uses the indication value and
   that indications are confirmed.

---

## O-05 — ADR-007's staleness is much wider than §2.6, and the merge order builds the stale interface first

**Severity: HIGH** · **Attacks:** `01-plan.md` §0.1, §2 (WP-02, WP-05, WP-30),
§3.2–3.4, §4.1, §4.3; `00-design.md` §9, §1.2.

### The attack

This is the direct answer to the question "can the 31 work packages still be
built in the stated order?" **No.** The provisional marker on §2.6 covers one
section; the finding invalidates at least seven other places, several of which
are scheduled *before* the revision it asks for.

**(a) §9's constants table is for the wrong protocol, and carries no marker.**
`00-design.md` §9 lists `BEURER_SERVICE_UUID`, `INIT_SEQUENCE`, `OPCODE_*`,
`WEIGHT_SCALE_FACTOR`, `IMPEDANCE_*`, `ADVERTISED_NAME_PREFIX`,
`USER_INDEX_FIELD` — the symbol set of a proprietary opcode protocol. The device
uses SIG-assigned 16-bit UUIDs (`0x181D`, `0x181B`, `0x181C`, `2A9D`, `2A9C`,
`2A9F`) and SIG-defined characteristic layouts with spec-defined resolutions.
There is no `INIT_SEQUENCE` and no `OPCODE_*`. Unlike §2.6, §9 has **no
provisional note at all**.

**(b) WP-05 is mostly obsolete and its sourcing instruction points at the wrong
document.** WP-05 populates §9's table from "openScale's public Beurer/Sanitas
**wiki page**" — the page for the older proprietary family members.
`03-hardware-validation.md` says the correct reference is openScale's *modern*
`StandardWeightProfileHandler.kt` / `StandardBeurerSanitasHandler.kt`, and that
this unit is "not the fully-proprietary opcode protocol openScale's older wiki
page documents." WP-05 sits at **position 5 of the strictly-ordered prefix**, and
`01-plan.md` §0.1 makes it load-bearing: it "is what lets the app compile, lets
the fixture corpus carry plausible frames, and lets 24 downstream packages reach
green." Twenty-four packages are scheduled to go green against a constants file
built from the wrong source. Its `UNCONFIRMED — pending live scan` markers are
also now doubly wrong: for the standard-profile fields the values are *already
confirmed* by `03-hardware-validation.md` §5, so the marker stops carrying
information in both directions.

**(c) WP-30's expected diff is no longer data-only.** WP-30 states "the code
change here is expected to be **a data-only edit to `BeurerProtocol.kt`** plus
one strategy flag," and adds "If it is not — if the real protocol needs
structural change — that is itself a P1 finding and an ADR." The plan set its own
tripwire and the tripwire has fired: the protocol needs a stateful handshake, a
persisted credential, two correlated characteristics, and indication subscription.
The plan has not been re-costed against its own criterion.

**(d) The fixture corpus is largely invalid.** §3.4's 22 fixtures use
`INIT_SEQ[0]`, `NOTIFY_CHAR`, `WRITE_CHAR`, and `@onEnableNotify` — none of which
map onto a UDS register/consent exchange over `2A9F` followed by indications on
two separate characteristics. §3.2's claim that symbolic names mean "when WP-30
corrects a constant, every fixture follows automatically" holds for *values*, not
for a changed *shape*, and the shape has changed. `happy_path.scale`,
`no_init_ack.scale`, `late_init_ack.scale`, `second_user_index.scale`,
`unstable_then_stable.scale` and others need rewriting, not re-valuing.

**(e) §1.2's data-flow diagram is re-broken by the same mechanism that
self-review item 24 fixed.** Item 24's stated reason for fixing the diagram was
that "the module-graph/data-flow diagram is Phase 0 deliverable #1, so a reader
taking it at face value would implement the pre-fix behaviour." The diagram still
shows a single `H[BeurerDecoder] -->|DecodeEvent.Stable| U{User attribution}`
edge with no correlation step and no UDS handshake node. A reader taking it at
face value today implements the pre-ADR-007 behaviour. The identical defect, from
the identical cause, one review cycle later.

**(f) The merge order builds the stale types first.** §4.3 fixes a strict prefix:
WP-01 → WP-02 → WP-03 → WP-04 → WP-05 → WP-06 → **WP-07** → WP-08. **WP-02 is
"Core domain types"** and its file list includes `ScaleDecoder.kt`,
`DecodeEvent.kt`, and `GattOp.kt` — precisely the three files ADR-007 says must
be revised. The plan therefore merges the known-stale interface at position 2 and
then rewrites it at 5, 7, 9, and 10. WP-02 is also where `ScaleReading` lands,
which O-01 says is missing columns.

**(g) Branch B is now dead weight carried at full cost.** ADR-007 confirms Branch
A. `HELD_CONFIRM` and `DECLINED` — the two statuses added by self-review items 19
and 23, the two most recent and most heavily-argued fixes in the document — are
dead code for v1's hardware. WP-15 keeps eight Branch B tests, WP-23 keeps the
decline affordance, WP-26 keeps held-confirm notifications, WP-28 keeps
`branchBReadingIsHeldAndNotDelivered`. §4.1 still counts all of it at full cost
while §7's own amended note calls Branch B "dead code for v1's target hardware."
That is defensible (PRP §2 wants a pluggable decoder) but it should be a stated
decision with its cost acknowledged, not an unexamined carry-over — especially
against O-01 and O-02, which are real v1 work competing for the same budget.

### Evidence for

Each item above cites the specific text. The strongest single piece: ADR-007's
own reversal-cost section says "**Treat `00-design.md` §2.6 as provisional until
that revision lands — a note has been added there**," scoping the staleness to
one section, while its own body describes changes to the handshake model, the
credential inventory, `GattSession`'s dependencies, and the emission model.

### Evidence against

- Much of this is bookkeeping that a Phase 2 interface revision would sweep up
  naturally as it touches the same files. A competent reviser rewriting §2.6
  would notice §9 in passing.
- WP-02's stale types are cheap to fix — it is position 2, nothing depends on it
  yet, and no code exists at all. The "wrong order" objection costs a
  re-sequencing, not a rewrite.
- Keeping Branch B is genuinely defensible on PRP §2's pluggability grounds, and
  removing it would burn the argument that produced ADR-006.
- The plan's §6 "Open items carried into Phase 2" already anticipates that
  Phase 2 amends things, so the process is functioning as designed — this
  objection is partly "the process has not run yet."

That last point is fair but does not rescue the sequencing: §4.3's strict prefix
is stated as a merge order, not a draft, and nothing in the plan currently blocks
WP-02 from merging first.

### Disposition

**Fix now**, as a sequencing change plus three marker edits — this is the cheapest
high-value fix in the report.

1. Insert a **WP-00 "Interface revision per ADR-007"** ahead of WP-02 in §4.3's
   strict prefix, owning `ScaleDecoder`, `DecodeEvent`, `GattOp`, `GattTransport`
   (O-04), `ScaleReading` (O-01), and the correlation model (O-02, O-03). Nothing
   else merges until it lands.
2. Put the same provisional banner on `00-design.md` §9 that §2.6 carries, and
   redirect WP-05's source from the openScale wiki page to the standard-profile
   handlers named in `03-hardware-validation.md`.
3. Re-cost WP-05, WP-07, WP-09, WP-10 and update WP-30's "data-only diff" claim.
   Re-cost §4.1's "28 of 31 mergeable without hardware" — it may still be true,
   but it is currently asserted on the old package shapes.
4. Redraw §1.2's diagram with the UDS handshake and the correlation step, for
   exactly the reason self-review item 24 gives.
5. Record an explicit decision on Branch B: keep it (with its cost stated) or
   defer it to the v2 pluggability work.

---

## O-06 — "A status makes the safe behaviour structural" is only half true

**Severity: HIGH** · **Attacks:** ADR-006 ("Why a status and not a boolean"),
`00-design.md` §3.2, §3.3, §4.4; `01-plan.md` WP-22 · **This objection attacks
the design's most confident structural claim.**

### The attack

ADR-006 is the most confidently-argued section in the document set. It rejects a
boolean flag on `PENDING` in explicit structural-safety terms:

> Encoding it as `PENDING` + `confirmationRequired = true` makes the wrong-user
> gate depend on every present and future drain query remembering to
> `AND NOT confirmationRequired` — and the first query that forgets delivers a
> household member's weight to Garmin silently… **A status makes the safe
> behaviour structural: a query that forgets `HELD_CONFIRM` exists simply does
> not select those rows.**

`00-design.md` §3.2 repeats it: `HELD_CONFIRM` "is not a sub-state of `PENDING`
precisely so that no drain, backoff, or expiry path can reach a reading that has
not been attributed."

**The claim is true for allowlist-shaped predicates and false for denylist-shaped
ones — and this design uses both.**

An allowlist predicate (`WHERE status = 'PENDING'`) is fail-safe: a new status is
excluded by default. That is the drain query, and the claim holds there.

A denylist predicate is fail-*open*: a new status is **included** by default. The
design has at least one, load-bearing, in §3.3:

> Compared against **all** rows in the window regardless of status, **except
> `DECLINED` rows**, which are excluded from the dedup corpus entirely.

That is a denylist. Add a seventh status tomorrow — an `ARCHIVED`, a v2
`REPLAY_QUEUED`, a `SUPERSEDED` — and it silently joins the dedup corpus. If any
such status can hold another person's weight, or a stale weight, it can suppress
one of JD's genuine readings within 0.20 kg and 5 minutes: **exactly the loss
mode self-review item 23 identified and fixed for `DECLINED`, reappearing by
default for every status added after it.**

And status growth is not hypothetical here. The status set went from **3** (PRP
§5) to **6** in a single self-review pass — `BLOCKED_AUTH` (item 4),
`HELD_CONFIRM` (item 19), `DECLINED` (item 23). A design whose status set doubled
in one review is precisely the design where "adding a status is fail-open in one
of my two query shapes" is a live hazard, not a theoretical one.

The second, softer instance is the replay predicate. §4.4's eligibility test is:

> A `SENT` row is replay-eligible when **both**: 1. `contract.supportedFields ∩
> row.populatedFields ⊄ row.deliveredFields`, **and** 2. `row.remoteDuplicate == false`.

A `DECLINED` row satisfies both numbered clauses — it has `deliveredFields = ∅`
and `remoteDuplicate = false`. If it entered the replay path it would be
re-queued as `PENDING` and delivered, which is precisely the one-tap
Garmin-delivery that ADR-006 and item 23 exist to prevent, arriving instead as a
one-time migration worker with no user action at all.

### Evidence for

- The §3.3 denylist and the "structural" claim are both verbatim quotes and are
  in direct tension.
- The 3→6 status growth in one pass is documented in the self-review list itself.
- WP-22's named tests are `undeliveredPopulatedFieldMakesRowEligible`,
  `fullyDeliveredRowIsNotEligible`, `remoteDuplicateRowIsNeverEligible`,
  `emptyDeliveredFieldsAloneDoesNotImplyEligible`. **None asserts a status
  filter.** The `SENT`-only scoping is enforced by prose alone.
- WP-14's `DedupPolicyTest` similarly tests `declinedRowIsExcludedFromCorpus` —
  a per-status assertion. There is no test asserting the *general* property
  ("only these statuses are in the corpus"), so the next status is untested by
  construction.
- ADR-006 itself demonstrates the defect class it is warning about: item 23 is
  the record of ADR-006's own first draft being undone by an affordance three
  sections away.

### Evidence against

**This is the important part, and it materially weakens the replay half of the
objection.** §4.4's prose does scope the predicate — it says "**A `SENT` row** is
replay-eligible when…", and §3.2's diagram shows the transition only as
`SENT --> PENDING`. So a faithful implementer filters on `SENT` and `DECLINED`
never enters the replay path. The objection there is not "the design is wrong"
but "the scoping lives in a sentence and not in a test, in a document that
elsewhere insists on machine-enforcing exactly this kind of invariant" — WP-05's
`everyConstantHasAProvenanceComment` is a source-scanning test written precisely
so a rule is "machine-enforced rather than review-enforced."

Further counter-arguments:

- The status set is unlikely to grow again before v1 ships, so the denylist
  hazard is a future-maintenance concern, not a v1 defect.
- ADR-006's core comparison is still correct: a status *is* safer than a boolean
  for the drain query, which is the query that mattered.
- One could argue §3.3's denylist is deliberate — the dedup corpus genuinely
  should default to including everything, since a duplicate check that misses
  rows creates duplicates.

That last point is the strongest defence and it is worth conceding: the denylist
shape in §3.3 is a *reasonable* choice. The objection is to the unqualified word
**structural**, which claims a safety property the design only has in one of the
two shapes it uses, and to the absence of any test enforcing the boundary in
either shape.

### Disposition

**Fix later, with a tracked issue** for the general property; **fix now** for the
two concrete test gaps, which are one line each.

1. Add `ReplayEligibilityTest.onlySentRowsAreEligible`, parameterised over all
   six statuses. This converts §4.4's prose scoping into an enforced invariant
   and costs nothing.
2. Add `DedupPolicyTest.dedupCorpusMembershipIsExplicitPerStatus`, parameterised
   over all six, so adding a seventh status forces a deliberate decision rather
   than inheriting one.
3. Soften ADR-006's claim to what is actually true: a status makes the safe
   behaviour structural **for allowlist predicates**, and the design must keep
   its status-filtering predicates allowlist-shaped or test them exhaustively.
   Leaving the unqualified claim standing is how the next reviewer stops looking.

---

## O-07 — RISK-1 is scheduled first against a risk the project's own evidence says may not exist

**Severity: HIGH** · **Attacks:** `01-plan.md` §1 (risk ranking), §1.1, WP-03,
§3.4, §7.

### The attack

`01-plan.md` §1 is emphatic: stabilization detection is **RISK-1**, "the worst
class in the document," scheduled at position 3 — "the earliest position any
behavioral package can occupy." It gets ten named tests, two dedicated fixtures
(`unstable_then_stable.scale`, `unstable_then_stable_flagged.scale`), a
`StabilityStrategy` enum, and two hardware checklist rows (HW-08, HW-09). §7's
exit-gate self-check restates the ranking as validated: "the design's judgment is
upheld."

`03-hardware-validation.md` says:

> the standard Weight Measurement characteristic has no explicit "final/stable"
> flag in the Bluetooth SIG spec; it simply **notifies once when the scale has a
> result**. This likely **replaces the need for the quiescence heuristic entirely
> for this device**.

If that holds, WP-03's premise is void in *both* branches simultaneously. The
flag path has no flag to read. The quiescence path has no stream of live frames
to detect quiescence across — a single indication carrying a final result cannot
satisfy "≥4 consecutive samples within ±0.1 kg spanning ≥2.0 s," because there is
one sample. The entire `DecodeEvent.Live(weightKg)` case, which §2.6 defines and
§2.4 builds on, may have no producer on this device.

So the plan's most expensive risk-retirement package sits at the head of a
strictly-ordered prefix, retiring a risk that the only hardware evidence in the
project suggests does not exist — while **the real RISK-1 has no work package at
all.** The genuinely novel, genuinely defect-prone thing ADR-007 uncovered is the
stateful UDS register/consent exchange with a persisted consent code and a
conditional branch on whether registration has already happened. That is exactly
the profile §1 uses to justify RISK-2 ("stateful, conditional, timing-gated, the
design has already been wrong twice about this class of thing"), and it is
strictly harder than the fixed `List<GattOp>` WP-07 is scoped to test. WP-07's
named tests — `initSequenceOrderIsDeterministic`,
`initSequenceRequiresBothCharacteristicsPresent`, `ackFrameProducesInitAcknowledged`
— all presuppose the stateless model ADR-007 retired.

Risk-first ordering exists to buy information early. Spending position 3 on a
phantom and leaving the real risk unowned inverts the thing the section is for.

### Evidence for

- The quoted hardware note is the project's own document and is unambiguous
  about the absence of a stability flag.
- §1's justification for RISK-1 rests on an "unvalidated disjunction — either the
  BF720 frame carries a final/stabilized flag, or it does not." Evidence now says
  the SIG characteristic has neither, which is a third case the disjunction did
  not admit.
- WP-07's test names are all shape-dependent on `initSequence()` returning a
  fixed ordered list, which ADR-007 explicitly says is wrong ("The real handshake
  is stateful and conditional").
- No work package in the 31 owns registration/consent, the consent store, or the
  `UserRegistered`/`UserConsented` events ADR-007 asks for. See also O-08.

### Evidence against

**Substantial, and it should be weighed carefully:**

- The hardware note itself hedges — "**likely** replaces the need" — and rests on
  a **single** weigh-in producing a single captured pair. The scale may well emit
  multiple weight indications during settling; the probe may simply not have
  logged them, or may have connected after settling. One capture is thin evidence
  for a negative claim about streaming behaviour.
- `01-plan.md` §2.4 and WP-03 explicitly argue the quiescence path is implemented
  "regardless… because it is also the guard if the flag turns out to be
  unreliable." That defensive posture is *correct* and remains correct. Deleting
  WP-03 on one capture would be exactly the over-correction the plan's own
  scepticism guards against.
- WP-03 is a pure function with no dependencies. It is cheap, and being wrong
  about its necessity costs little compared to being wrong about needing it.

So the objection is **not** "delete WP-03." It is that the *ranking* — presented
as validated, with a paragraph explaining why a third contender was rejected — is
stale, and that the ordering decision it drives has not been revisited against
evidence that arrived afterwards.

### Disposition

**Fix now** — a re-ranking, not a deletion.

1. Re-run §1's discriminating test (consequence × likelihood × retirable-now)
   with ADR-007 in hand. The UDS consent handshake scores high on all three: high
   consequence (no consent, no data, ever), high likelihood (stateful, conditional,
   persisted, first-use-versus-subsequent-use branching), and fully retirable in
   CI against a fake.
2. Promote it to **RISK-1** with its own work package, and re-scope WP-07 to it.
3. Keep WP-03, demoted, with its rationale amended: it is now a **portability and
   fallback** guard rather than a live-path risk, and HW-08's pass criterion
   becomes "confirm whether any live/intermediate weight indications occur at
   all" — which is the observation that decides its fate.
4. Update §7's exit-gate self-check, which currently certifies the stale ranking.

---

## O-08 — The consent code is an unowned credential, and `allowBackup="false"` guarantees it is lost on every device change

**Severity: HIGH** · **Attacks:** ADR-007, `00-design.md` §8.8, §7, §3.1;
`01-plan.md` §2 (no owning package), §2.1.

### The attack

ADR-007 introduces a new persisted secret:

> This mapping must be stored — EncryptedSharedPreferences per the agent prompt's
> ground rule on credential storage, since a consent code is a shared secret with
> the scale in the same sense a token is a shared secret with VitalForge.

Correctly identified. Then nothing else in the project accounts for it.

**(a) No work package owns it.** All 31 packages have file lists. None contains a
`ConsentStore`. WP-19 is `AuthTokenStore` and is scoped to the VitalForge bearer
token. WP-07 (the handshake package) lists only `BeurerDecoder.kt` and
`GattSession.kt`. WP-02's type list does not include it. ADR-007 says
"`GattSession` needs a new dependency (a small consent-store interface)" — that
dependency has no package, no tests, and no position in the merge order.

**(b) It is absent from the threat review.** §8.8 "Credential and payload
leakage" enumerates the token, log lines, `lastError`, BLE frame diagnostics,
and backup. The consent code — a shared secret whose compromise lets another
device impersonate JD's profile to the scale and read the household's stored body
composition — is not mentioned, because §8.8 predates ADR-007.

**(c) The collision — `allowBackup="false"` versus a finite pool of scale
slots.** §8.8 mandates:

> Backup is disabled (`android:allowBackup="false"`, `dataExtractionRules`
> excluding the DB and prefs) so readings and token cannot be extracted through
> ADB backup **or transferred to a new device unencrypted**.

That rule, written to protect the bearer token, now also guarantees the consent
mapping **cannot survive a device migration, a reinstall, or a "clear app data"**.
And the recovery path ADR-007 specifies is: "register only if no local mapping
exists yet… otherwise send Consent directly." No mapping ⇒ **register again**.

Registration is `Register New User` on the User Control Point. The scale supports
**8 profiles** (PRP §2: "auto-recognition across 8 profiles"; both feature
bitmaps in `03-hardware-validation.md` report "Multiple Users Supported").

**Falsifiable prediction:** each `Register New User` write consumes a fresh
profile slot rather than reusing an orphaned one, so every phone replacement,
reinstall, or data-clear permanently burns one of eight. After the eighth,
registration fails — and the design specifies **no behaviour for a failed
registration**, no diagnostics counter, and no user-facing message. The app would
present as E6/E7: a handshake that never acknowledges, a silent teardown, and no
readings, forever. **Closed by:** a checklist row that registers twice from a
wiped app and records whether the returned `scaleIndex` is reused or incremented,
and what the scale returns when the pool is exhausted.

Slot pressure is already non-zero: the live capture returned **`scaleIndex = 2`**,
not 1, which suggests slot 1 was already occupied — plausibly by the Beurer
consumer app during setup. The pool is being consumed by parties other than
Bascule, and Bascule cannot see how many remain.

**(d) It invalidates the documented onboarding flow.** `00-design.md` §7 Branch A
says: "ConfigScreen has 'My user index' (1–8), **set during onboarding by
weighing once and picking the index that appeared.**" That is now wrong in
mechanism — the index is *assigned by the scale* during registration, not
discovered by weighing, and it must be persisted at that moment or it is lost.
WP-25 (ConfigScreen) and WP-15 (attribution gate) both encode the stale flow, and
WP-15's `branchAMatchingIndexPersistsAsPending` tests a config value that will now
arrive from a completely different source.

**(e) The schema does not record it.** §3.1's `ReadingEntity` has `userIndex`
but no record of *which registration* produced it. After a re-registration, JD's
index changes (say 2 → 4) and every historical row's `userIndex` refers to a
different registration than the current one. Branch A's filter compares
`ScaleReading.userIndex` to a configured value; that comparison is silently wrong
for the entire pre-migration history, and §7's "confirmed reading becomes the new
baseline" logic has no way to notice.

### Evidence for

- ADR-007 states the storage requirement itself, so the design agrees this is a
  credential; it simply has not been carried through.
- The absence from all 31 package file lists, from §2.1's counter registry, and
  from §8.8 is verifiable by inspection.
- §8.8's backup prohibition is unconditional and explicitly names device
  transfer.
- `scaleIndex = 2` in the capture is direct evidence that slot 1 was taken.
- The design is otherwise meticulous about recovery paths for every other
  credential (§8.6's whole `BLOCKED_AUTH` apparatus exists for token rotation).
  There is no equivalent for consent loss.

### Evidence against

- The slot-burn mechanism is a prediction, not an observation. The scale may
  reuse orphaned slots, may allow re-registration into an existing index, or may
  expose a delete-user operation on the same control point (the SIG User Data
  Service defines one). If so, the severity drops sharply.
- `2A9A` User Index is a readable characteristic per `03-hardware-validation.md`
  §1, so recovery of a lost mapping may be possible by reading it rather than
  re-registering — but this is unexplored, and it would still leave the consent
  *code* (the 16-bit secret) unrecoverable.
- Eight slots is a lot for a single-user household; a user who replaces their
  phone eight times before the scale dies is unusual. The "clear app data" and
  reinstall paths are more likely than device replacement.
- ADR-007 explicitly defers the design work to Phase 2, so "no package owns it"
  is partly "Phase 2 hasn't happened yet" — which is fair for (a) but not for
  (c), (d), or (e), which are consequences nobody has written down anywhere.

### Disposition

**Fix now** for the design gaps; **checklist row** for the prediction.

1. Add a **`ConsentStore`** work package (EncryptedSharedPreferences, alongside
   WP-19), with tests mirroring `AuthTokenStore`'s — including the file-bytes
   test that proves the code is not stored in plaintext.
2. Add the consent code to §8.8's threat review, and state explicitly that
   `allowBackup="false"` means it is deliberately not portable, with the
   re-registration cost named.
3. Define **failed-registration behaviour**: a diagnostics counter, a distinct
   `SessionOutcome`, and a user-facing message that says what is actually wrong
   ("the scale's user profiles are full — delete one in the Beurer app"), not a
   silent teardown.
4. Investigate reading `2A9A` and the SIG delete-user operation as a recovery
   path before accepting re-registration as the only option.
5. Rewrite §7 Branch A's onboarding sentence and re-scope WP-15/WP-25.
6. Decide whether `ReadingEntity` needs a registration-epoch column so historical
   `userIndex` values stay interpretable across a re-registration.
7. Add the checklist row described above.

---

## O-09 — ADR-004's "retired in CI" claim does not retire what it says, and the CI matrix misses the only device that exists

**Severity: MEDIUM-HIGH** · **Attacks:** `01-plan.md` §1 (rejection of WP-08 from
the top two), §3.6(c), WP-08, WP-01.

### The attack

This is the direct answer to "does `FakeScaleGatt` actually cover ADR-004's and
ADR-003's failure modes, or is the coverage claim optimistic?" The plan is
commendably honest about most of it — §3.6 splits edges into fully-driven,
trigger-approximated, and not-a-transport-concern, and states the approximations.
Two things survive that honesty.

**(a) The test that justifies demoting WP-08 does not exercise the claim.**
§1's rejection of the wake path from the top-two risk slots turns entirely on
this table row:

> Receiver → enqueue → expedited worker → `setForeground` succeeds on API 31+/34+
> with the right FGS type — **The ADR-004 platform claim — the half carrying the
> reversal risk** — Bucket: **CI**

and the named tests are `ScaleSessionWorkerTest.setForegroundSucceedsOnApi31` and
`...setForegroundSucceedsOnApi34WithConnectedDeviceType`. `01-plan.md` §2's WP-21
tells us the harness in use is `WorkManagerTestInitHelper` +
`TestListenableWorkerBuilder`.

The discriminating question the plan should answer and does not: **can that test
fail if the platform would reject the foreground start?** A worker driven
directly by a test builder is invoked in isolation — it does not go through
WorkManager's real expedited scheduling, and it is not subject to the platform's
foreground-service background-start restrictions, which are what ADR-004's claim
is actually about. ADR-004 asserts a *platform permission* result
(`ForegroundServiceStartNotAllowedException` is avoided because expedited work
with `setForeground()` is an exempt path). A test that never triggers the
platform check cannot falsify that assertion.

If that is right, §1's reasoning is circular: WP-08 was demoted from the top two
because its risky half is "retired in CI at position 8," but the CI test retires
the *plumbing* (does the receiver enqueue, does the worker call `setForeground`)
and not the *claim* (does the platform permit it). The reversal cost §1 itself
describes as "the highest of anything in the plan" is therefore still live at
position 8 and stays live until a real device runs it — which the plan buckets
as PHONE and defers to WP-29, position 29.

**(b) The CI emulator matrix does not include the target device's API level.**
WP-01 pins CI to "an emulator matrix (API 26, 31, 34)."
`03-hardware-validation.md` names the test host: **Pixel 9 Pro Fold, Android 17
(API 37)**. Every foreground-service, expedited-work, and background-start
restriction in this design is API-level-gated, and each recent Android release
has tightened them. The one device that will ever run this app is three major
versions beyond the highest CI level. A green matrix on 26/31/34 is not evidence
about API 37, and ADR-004 is precisely the kind of claim that a new release
invalidates.

**(c) Minor, for completeness — ADR-003's contention coverage is honestly
stated.** §3.6(b) says of E3: "Real Atlas contention may present as status 8, 19,
22, a silent connect failure, or a successful connect that starves. The fake
covers the documented codes; the real presentation is unverified," with HW-18
named. That is not optimistic — it is exactly right, and it is a model for how
(a) should have been written. The one gap worth noting is the case §3.6(b) itself
lists and no fixture covers: **"a successful connect that starves"** — connect
succeeds, discovery succeeds, subscription succeeds, and no indication ever
arrives. That presents identically to E7, whose recovery is a silent teardown
(see O-11), so a contention that manifests this way is invisible in the
diagnostics as `NoMeasurement` rather than `Missed(CONTENTION)`, and the
`Missed(CONTENTION)` counts ADR-003 nominates as its revisit trigger would
undercount.

### Evidence for

- §1's rejection argument is quoted above and rests entirely on the CI bucket
  assignment.
- The named tests and the harness are both stated in the plan.
- The API-level mismatch is arithmetic: 34 < 37.
- §3.6(b)'s own list contains "a successful connect that starves," and §3.4's
  fixture table has no fixture for it — `no_notification.scale` exists but is
  attributed to E7, not E3.

### Evidence against

- ADR-004's platform claim is well-established Android practice, not a novel bet.
  Expedited work with `setForeground()` is the documented path. The probability
  of it being wrong is genuinely low, which supports §1's decision to demote
  WP-08 even if the stated justification is imprecise.
- An instrumented test on a real emulator *does* run a real Android framework, so
  depending on how the test is written it may exercise more of the platform path
  than this objection assumes. The plan says "instrumented" for both
  `setForegroundSucceeds*` tests.
- The API-37 gap is partly unavoidable — emulator images lag, and CI cannot
  test an OS that may not have a stable image.
- The starving-connect gap is explicitly acknowledged in §3.6(b) prose even
  though no fixture covers it, so it is a known-unknown, not a blind spot.

### Disposition

**Fix later, with tracked issues** — none of this blocks a merge, but the
justification should stop overclaiming.

1. Restate §1's WP-08 rejection honestly: the CI half retires the *plumbing*;
   the ADR-004 platform claim is retired at **HW-01/WP-29** on a real device.
   Keep the demotion — the reasoning for it survives — but do not describe the
   reversal risk as retired at position 8.
2. Add the highest available emulator API to WP-01's matrix, and add a checklist
   row that runs the wake path on the actual API 37 device early, before 20 more
   packages are built on ADR-004.
3. Add a `starving_connect.scale` fixture and a distinct outcome so a contention
   that presents as silence is not miscounted as `NoMeasurement`. This also feeds
   O-11.

---

## O-10 — Two clocks, and two constants whose stated justifications are factually wrong

**Severity: MEDIUM-HIGH** · **Attacks:** `00-design.md` §3.3, §2.4, §2.7, §4.4.

### The attack

**(a) The design ingests the wrong timestamp, and does not write the one the
scale uses.** §2.7 defines `capturedAtMillis` as "device clock at `EMITTED`" —
the *phone's* clock, at the moment the app finishes decoding.
`03-hardware-validation.md` §5 shows the Weight Measurement frame carries the
**scale's own timestamp** (`ea 07 08 16 10 33 01` → 2026-08-22 16:51:01), and
notes it "matches the Current Time value written moments earlier to the second"
— i.e. the probe wrote `2A2B` Current Time Service, and the scale timestamped
from it.

Two consequences the design does not address:

- **Nothing in Bascule writes CTS.** No work package, no `GattOp`, no mention in
  §2.6's `initSequence` or ADR-007's handshake. If the scale's clock is never
  set, its timestamps are whatever its internal RTC says — potentially years off,
  or reset on battery change. The frame's timestamp then becomes garbage that
  the design happens not to read, which is lucky rather than designed.
- **The design stores the phone-clock time and discards the scale's.** For a
  wake-on-advertisement session that is close enough. But for any reading the
  scale buffered and delivered later — which a UDS scale with "Multiple Users
  Supported" and a Database Change Increment characteristic (`2A99`, present per
  `03-hardware-validation.md` §1) plausibly does — `capturedAtMillis` would be
  the *delivery* time, not the weigh-in time. That silently corrupts the §3.3
  dedup time key and the HistoryScreen sort key.

This matters most at §4.4, which stakes the replay path's correctness on it:

> rows captured under v1 carry no server-side client key, so v2 idempotency for
> **pre-existing** rows must key on `captured_at` plus a weight tolerance

If `captured_at` is phone-clock-at-decode and VitalForge/Atlas ever holds a
scale-clock timestamp for the same weigh-in, the join key does not match and the
replay produces duplicates — the exact outcome §4.4's whole escalation exists to
prevent. This should be part of the A6 escalation to JD and currently is not.

**(b) Two constants have rationales that the hardware evidence falsifies.**

§3.3 justifies the dedup tolerance:

> **±0.20 kg** — the BF720 resolution is **0.1 kg**, so this is **2 LSBs**.

§2.4 justifies the quiescence band:

> weight must stay within **±0.1 kg** (**one display LSB**)

`03-hardware-validation.md` §3 reads the Weight Scale Feature characteristic
directly: "**weight resolution 0.01 kg**", and §5 decodes the measurement with a
multiplier of **×0.005 kg**. So ±0.20 kg is 20 or 40 LSBs, not 2, and ±0.1 kg is
10 or 20 LSBs, not one.

The *values* may still be fine — 200 g is a defensible dedup window for human
weight regardless of how it is derived, and the design's other justification for
it ("two genuinely different weigh-ins are not collapsed") stands on its own. But
this document insists that numbers carry justifications: §3.3 says both constants
are "`const val` in `DedupPolicy.kt` **with this rationale in a comment**," and
ADR-002 rule 3 makes an unjustified constant a review blocker. Shipping a comment
that states a false hardware fact is worse than shipping no comment, because the
next person to tune the constant reasons from it.

There is a second-order effect: at 0.005 kg resolution the scale reports weight
to a precision that makes a *tighter* dedup window viable, and §3.3's stated
worry ("absorbs the scale re-reporting a final frame that differs by one tick")
is now about a 5 g tick, not a 100 g one.

### Evidence for

- All quoted text is verbatim from the two documents and directly contradictory.
- The CTS write is absent from every package file list in `01-plan.md` §2.
- `2A99` Database Change Increment being present is at least suggestive that the
  scale maintains a measurement database, which is what makes deferred delivery
  plausible.
- §4.4 explicitly names `captured_at` as the v1-row replay join key.

### Evidence against

- The design never reads the frame's timestamp, so the two-clock problem is
  currently latent rather than active — for a live wake-on-advertisement session,
  phone-clock-at-decode and scale-clock-at-weigh-in differ by seconds, which is
  well inside the 5-minute dedup window and irrelevant to sort order.
- Deferred/buffered delivery is speculative. The capture showed an immediate
  indication after a live weigh-in.
- The LSB error is genuinely cosmetic *as to the values chosen*; nobody would
  pick a different dedup window knowing the true resolution, because the window
  is sized by human physiology, not by scale precision.
- `03-hardware-validation.md` reports the resolution from a feature bitmap
  decode, which is itself an interpretation — though the ×0.005 multiplier is
  corroborated by the internal consistency check (BMI from weight/height matching
  the scale's own reported BMI).

### Disposition

**(a) Fix now** as a documentation and escalation change; **(b) fix now** as two
comment edits.

1. Decide and record whether Bascule writes CTS (`2A2B`) during the handshake. If
   the answer is no, state why and note that frame timestamps are not trusted.
2. Store the frame's timestamp alongside `capturedAtMillis` — a
   `scaleTimestampMillis` column, nullable — so the information is not discarded
   at the point of measurement (PRP §2 again) and so §4.4's replay join has the
   option of using the right key.
3. Fold the clock question into the **A6 escalation to JD**: "which timestamp
   does VitalForge store, and which one should replay join on?" is a contract
   question, not a Bascule-internal one.
4. Correct the two LSB claims in §3.3 and §2.4 to the confirmed 0.01 kg / ×0.005,
   and either keep the values with a physiology-based justification or re-derive
   them. Update WP-14's and WP-03's boundary tests if any value changes.

---

## O-11 — E7 is now the exact signature of a lost consent, and it has no counter, no streak guard, and no diagnosis

**Severity: MEDIUM** · **Attacks:** `00-design.md` §2.3 E7, §2.1, §8.11;
`01-plan.md` §2.1 counter registry.

### The attack

E7's definition:

> **E7** — Notifications never arrive — no measurement frame within **45 s** of
> `SUBSCRIBED` — `TEARDOWN`, outcome `NoMeasurement`.

That is a silent teardown. E7 has **no diagnostics counter** (verify against
`01-plan.md` §2.1's registry — `incompatibleStreak`, `missedQuota`,
`malformedCount`, `duplicateStableSuppressed`, `duplicatesSuppressed`,
`droppedOtherUser`, `remoteDuplicatesSuppressed`; no `noMeasurement`), no streak
threshold, and no user-facing message.

Compare E4, which does have a guard: three consecutive `Incompatible` sessions
suspend arming and tell the user "Scale not recognised." §8.11 names the reason —
an unbounded wake-connect-fail loop is a battery drain and a dead end.

ADR-007's evidence is that **connect + discover + subscribe + wait, with no
consent, produces exactly zero measurement traffic** across multiple complete
weigh-ins. That is E7's precise signature. So E7 is no longer an exotic edge; it
is the failure mode of the single most fragile new mechanism in the design.

Ways this fires in production, none of which the design handles distinguishably:

- consent state lost on the scale (factory reset, battery change, profile deleted
  in the Beurer app) while Bascule's stored mapping says "already registered," so
  it sends Consent for an index that no longer exists and the scale stays silent;
- consent rejected because the stored consent code no longer matches;
- registration silently failing because the 8-slot pool is full (O-08);
- a starving connect from Atlas contention (O-09c).

In every case the app connects successfully, subscribes successfully, waits 45
seconds burning radio, tears down, and says nothing — once per weigh-in, forever.
E4's protective streak logic does not apply because discovery *succeeds*: the
service is present, the device is the right one, everything looks healthy. The
user's experience is "it worked yesterday and now nothing happens," with no
signal anywhere in the app, while §8.11's battery argument quietly inverts.

### Evidence for

- E7's recovery is stated as a bare `TEARDOWN` with an outcome and nothing else.
- The counter registry in `01-plan.md` §2.1 is explicitly complete and
  machine-enforced (`everyCounterKeyIsOwnedByExactlyOnePackage`) — E7 is absent
  from it by design, not by oversight, because when it was written E7 was a rare
  edge.
- ADR-007 documents this exact symptom occurring on real hardware.
- E4's existence proves the design already accepts that repeated silent failures
  need a streak guard and a user message; E7 simply was not a candidate before.
- HistoryScreen is documented as "the single answer to 'did my weigh-in reach
  Garmin'" (§5). Under repeated E7 it shows nothing at all — no rows, no banner —
  which is indistinguishable from "you didn't weigh yourself."

### Evidence against

- A `SessionOutcome.NoMeasurement` *is* recorded per §2.3, and §2.2 establishes
  that outcomes feed "a diagnostics event." So the information exists in some
  form; what is missing is aggregation, thresholding, and surfacing.
- E7's 45 s cost is bounded and occurs 1–3× per day (§2.2), so the battery
  argument is weak in isolation — this is a diagnosability objection more than a
  battery one.
- Post-ADR-007, a well-implemented session would gate `SUBSCRIBED` on
  `UserConsented` the same way it gates on `InitAcknowledged` today, so a consent
  failure would surface as a handshake failure (E6-shaped) rather than as E7.
  That is the right fix and it is arguably implied by ADR-007's request for
  `UserConsented` as a `DecodeEvent` case — which weakens this objection
  considerably, *provided* the Phase 2 revision does it.

That caveat is the crux: the fix depends entirely on the interface revision
placing the consent gate before subscription. If it lands after, or if consent
is silently assumed on the strength of stored state, E7 absorbs the failure.

### Disposition

**Fix later, with a tracked issue**, but decide item 1 during the interface
revision because it is nearly free.

1. In the revised state machine, gate `SUBSCRIBED` on `UserConsented` explicitly,
   so a consent failure is a *handshake* failure with its own outcome and message
   — never a 45-second silence. Mirror E6's ladder.
2. Add a `noMeasurement` counter to §2.1's registry with an owning package.
3. Give E7 an E4-style streak: N consecutive `NoMeasurement` sessions raise a
   notification suggesting re-registration or re-pairing, rather than repeating
   silently.
4. Make HistoryScreen show *sessions that produced nothing*, not only rows. A
   history that can only show successes cannot answer "did my weigh-in reach
   Garmin" in the case where it most needs to.

---

## 12. On `02-interface-revision.md`

The file did not exist at review time. Rather than leaving item 6 unanswered, the
useful statement is **which of these objections a `ScaleDecoder`/`DecodeEvent`
rewrite would and would not close.**

**Likely closed by a competent interface revision alone:**

- **O-02** (correlation timeout) — if the revision defines the emission unit as
  the correlated pair *and* adds a named timeout edge with a persist-the-weight
  action. A revision that only adds `UserRegistered`/`UserConsented` cases, as
  ADR-007 literally requests, does **not** close it.
- **O-03** (two-user correlation) — if the revision decides one-user-per-session
  or specifies the pairing rule.
- **O-04** (indications) — if the revision touches `GattOp`/`GattTransport` and
  not only the decoder. ADR-007's note does not direct it there, so this could
  easily be missed.

**Not closed by an interface revision at all** — these sit outside the decoder
contract and need separate owners:

- **O-01** — Room schema and `ScaleReading` field set; PRP §2's scope promises.
- **O-05** — merge order, §9's constants table, WP-05's sourcing, the fixture
  corpus, §1.2's diagram, Branch B's cost.
- **O-06** — dedup/replay predicate shapes and their missing tests.
- **O-07** — the risk ranking and the strict merge prefix.
- **O-08** — the consent store package, §8.8's threat review, slot exhaustion,
  §7's onboarding text, registration-epoch in the schema.
- **O-09** — ADR-004's justification wording and the CI API matrix.
- **O-10** — CTS, the timestamp column, and the A6 escalation.
- **O-11** — counters, streak guard, HistoryScreen's blind spot. (Partially
  mitigated if the revision gates subscription on consent.)

**Recommendation:** when `02-interface-revision.md` lands, review it against this
list explicitly rather than in isolation. The dominant failure mode this review
found is not any single wrong decision — the design work is unusually rigorous —
it is that **ADR-007 was scoped as a decoder problem and filed against one
section**, and the eight-of-eleven objections above that live outside §2.6 are
the cost of that scoping. A revision document that inherits the same scope will
inherit the same blind spot.

---

## 13. What this review did not attack

Listed so the absence of a finding is not misread as a clearance. **These areas
were read but were not the focus of this review, and no objection surfaced. That
is not the same as having been verified.** Anyone relying on these sections
should have them checked by someone whose job is to check them.

- **ADR-002 (openScale licensing).** Deliberately not relitigated — settled by
  the agent prompt's ground rules and explicitly ring-fenced from review cycles.
  This is the one item here that is a *decision* not to look, rather than a look
  that found nothing.
- **§3.4's retry ladder, the 14-day expiry, and the `retryEpochMillis` anchor
  (self-review items 18, 23).** Read; the reasoning is unusually careful and
  nothing contradicted it on a read-through. The arithmetic was not independently
  recomputed.
- **§4.5's HTTP classification table and §8.7's response hardening.** Read; no
  gap surfaced against the scenarios this review was looking at. Not audited
  against the full HTTP status space or a threat model of its own.
- **§6's permission matrix.** Read; the API 29–30 `ACCESS_BACKGROUND_LOCATION`
  requirement and E14's location-services distinction are both things that get
  missed and are present here. Not verified against platform documentation.
- **The `PHONE` bucket concept (`01-plan.md` §0).** The idea looks sound and is
  the reason 28 of 31 packages are claimed mergeable. The *claim* needs re-costing
  after O-05; the concept was not attacked.
- **§3.6's treatment of fake-layer limits.** With the exception noted in O-09,
  this section states its approximations rather than papering over them, and
  HW-01 naming *no* covering test on purpose is the right call. Not audited edge
  by edge.
