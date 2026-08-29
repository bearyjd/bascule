# Bascule — Decision Record

ADR-style, numbered, append-only. Per the agent prompt's ground rules: when a
decision the PRP marks as open (§8) is hit, implement the most defensible option
and record it here with its **reversal cost**.

| ADR | Title | Status | Phase |
|---|---|---|---|
| [001](#adr-001) | BLE flow is connection-oriented GATT, not broadcast decode | Accepted | 0 |
| [002](#adr-002) | openScale reuse: reimplement with provenance comments (settled) | Accepted | 0 |
| [003](#adr-003) | Atlas GATT contention: yield on connect, dedup before POST | Accepted (default, revisit before milestone 3) | 0 |
| [004](#adr-004) | Session runs in an expedited foreground Worker, not an FGS started from the receiver | Accepted | 0 |
| [005](#adr-005) | Delivery expiry is time-based and anchored to `retryEpochMillis`; `BLOCKED_AUTH` is a fourth status | Accepted (amended in Phase 0 self-review) | 0 |
| [006](#adr-006) | `HELD_CONFIRM` is a fifth status, not a flag on `PENDING`; decline is a terminal `DECLINED` | Accepted | 0 |
| [007](#adr-007) | PRP §8.5 resolved live (Branch A): UDS register+consent handshake required, not just connect+subscribe | Accepted, evidence-backed | 1 (early) |

---

## ADR-001

### BLE flow is connection-oriented GATT, not broadcast advertisement decode

**Status:** Accepted · **Date:** Phase 0 · **Supersedes:** PRP §3 data-flow steps 2–3

#### The contradiction

The PRP describes the core BLE flow two incompatible ways.

**PRP §3 (Architecture / Data flow)** describes passive broadcast decode:

- module tree: `ScaleDecoder.kt  # interface: fun decode(advertisement): ScaleReading?`
- data flow step 2: "`ScaleScanner` registers a `ScanFilter` for **the Renpho's**
  manufacturer ID / service UUID"
- data flow step 3: "On matching advertisement → **`RenphoDecoder.decode()`** →
  `ScaleReading`"

**PRP §4 (BLE decode reference)** states the opposite for the actual target:

> "GATT connect + characteristic subscribe (Beurer is **not** a pure broadcast
> scale like the Xiaomi — expect a connection-oriented flow, which means
> `BLUETOOTH_CONNECT` permission is required, not optional)"

and lists initialisation handshake and stabilized-measurement detection as
required elements, then draws the architectural implication itself: "because
Beurer requires a GATT connection rather than passive advertisement listening,
the Atlas-side `ble-scale-sync` bridge and Bascule cannot both hold a connection
to the scale simultaneously."

#### Decision: §4 wins

§3's data flow is **stale copy from an earlier draft that targeted the Renpho
ES-CS20M**. The evidence is internal to the document: §3 step 2 says "the
Renpho's manufacturer ID" and step 3 calls `RenphoDecoder.decode()`, in the
architecture for an app whose §2 names the **Beurer BF720** as the v1 target and
demotes the Renpho to "acceptable fallback". §3 was not updated when the hardware
choice changed.

§4 is the deliberate hardware analysis for the chosen device, is stated in bold,
is corroborated by openScale's Beurer/Sanitas documentation (which documents an
initialisation handshake — meaningless for a broadcast scale), and is echoed in
PRP §7's "`BLUETOOTH_CONNECT` — **required**" and PRP §9's milestone 1 ("BLE
**connect** + Beurer decode working standalone").

The agent prompt's precedence rule — "Where this prompt and the PRP conflict, the
PRP's *requirements* win" — resolves prompt-vs-PRP. Applying the same logic
within the PRP: an explicit, reasoned hardware analysis outranks unrevised
architectural prose.

#### Consequence 1 — the `ScaleDecoder` interface cannot be `decode(advertisement)`

A single BLE advertisement from a connection-oriented scale carries no
measurement. There is nothing to decode at advertisement time; the advertisement
is only a **device match**. The interface must model a conversation — connect,
discover, handshake, subscribe, consume a notification stream, detect stability —
not a synchronous pure function from one packet to one reading.

Adopted shape (full definition in `00-design.md` §2.6):

```kotlin
interface ScaleDecoder {
    fun matches(advertisedName: String?, serviceUuids: Set<UUID>): Boolean  // dispatch only
    fun initSequence(discovered: DiscoveredServices): List<GattOp>          // handshake
    fun onNotification(characteristic: UUID, value: ByteArray): DecodeEvent // stream
    fun teardownSequence(): List<GattOp>
}
```

`DecodeEvent` distinguishes `Ignored` / `InitAcknowledged` / `Live` / `Stable` /
`Malformed` / `SessionComplete`. The decoder is per-session stateful and performs
no I/O; `GattSession` executes `GattOp`s. That split is what makes `FakeScaleGatt`
(Phase 1) able to drive every failure edge in a JVM unit test.

#### Consequence 2 — the background scan is a wake trigger, not the decode path

The `PendingIntent` `ScanFilter` background scan from PRP's scope list **still
exists and is still the primary entry point** — it is how the phone learns a
weigh-in started without running a process all day. What changes is what happens
next: the broadcast **initiates a GATT connect**; it does not produce a reading.

Concretely, the receiver cannot own the session: a `BroadcastReceiver` is dead
after ~10 s, and a BF720 session runs tens of seconds. Receiver → enqueue
expedited worker → worker becomes foreground → worker owns the GATT connection.
See ADR-004.

#### Consequence 3 — module additions

`GattSession`, `GattTransport`, `ScaleSessionWorker`, `SessionOutcome` do not
appear in PRP §3's tree because a broadcast design needs none of them. They are
mandatory here. `00-design.md` §1.1 marks each addition.

#### Also settled here: entity naming

PRP §3 names the persistence entity `PendingReadingEntity`; PRP §5 names it
`ReadingEntity` and gives it a full schema (`id`, `capturedAtMillis`, `userIndex`,
… `status` (`PENDING`/`SENT`/`FAILED_PERMANENT`), `deliveredFields`).

**`ReadingEntity` is the name.** §5 carries the detailed schema and is the later,
more considered treatment. `PendingReadingEntity` is also actively misleading: the
table holds `SENT` and `FAILED_PERMANENT` rows too — it is the local system of
record for capture (PRP §2: "Local store is authoritative for capture"), not a
pending queue. Naming it after one of its six statuses would invite exactly the bug
where someone deletes non-`PENDING` rows to "clean up the queue".

#### Reversal cost

**High.** Reverting to a broadcast design would discard the session layer, the
worker wake path, the state machine, and the decoder interface — effectively all
of Phase 0 §2. It would also be wrong for the BF720. The only scenario that
reopens this: a live scan in milestone 1 shows the BF720 putting a complete,
stable measurement in its advertisement payload. If that happens, the broadcast
path becomes an *additional* fast path, not a replacement — the connect path is
still needed for full BIA fields.

---

## ADR-002

### openScale / ble-scale-sync reuse: reimplement from protocol understanding, with provenance comments

**Status:** Accepted — **settled by the agent prompt's ground rules; not to be relitigated**
**Closes:** PRP §8 open question 1

#### Context

PRP §8.1 asks: "Confirm openScale (GPL-3.0) / `ble-scale-sync` license terms
permit reusing decoder logic in an AGPL-3.0 repo, or reimplement from protocol
docs." PRP §4 similarly hedges: "AGPL-3.0 is compatible for reuse in this
direction, but confirm before copying source verbatim."

#### Decision

The question is **already answered by the agent prompt's ground rules**, which
govern process and are unconditional:

> "All BLE protocol knowledge derives from openScale's Beurer/Sanitas
> documentation and source (GPL-3.0). **Reimplement from protocol understanding;
> do not copy source files.** Record the provenance of every constant (UUIDs,
> opcodes, scale factors) with a comment citing where it came from."

Because no source file is copied, the license-compatibility question never
arises — there is nothing to relicense. Protocol facts (a UUID, a byte offset, a
scale factor) are not themselves copyrightable expression; the expression is the
code, and the code is written fresh.

**No review cycles are to be spent on GPL→AGPL compatibility analysis.** It is
out of scope by construction. Anyone raising it in a Phase 2 or Phase 4 review
should be pointed here.

#### What ships instead: the provenance convention

Every protocol constant carries a citation comment. Format, applied uniformly:

```kotlin
// Provenance: openScale wiki, Beurer/Sanitas page (github.com/oliexdev/openScale/wiki/Beurer-Sanitas),
// cross-checked against the Beurer handler in openScale source. Confirmed on live BF720 2026-__-__.
// Reimplemented from protocol description; no source copied.
private val BEURER_SERVICE_UUID: UUID = ...
```

Rules:

1. Every UUID, opcode, byte offset, scale factor, and magic length carries a
   provenance comment naming **the document or handler**, not just "openScale".
2. Constants confirmed against the physical BF720 get the confirmation date
   appended during the milestone-1 hardware session.
3. A constant with no provenance comment is a **review blocker** — it means
   someone guessed, and a guessed scale factor produces plausible-looking wrong
   weights that no test will catch.
4. `docs/prp/00-design.md` §9 holds the symbolic table of every constant owed a
   value; Phase 3 fills it in.
5. `README.md` carries openScale attribution and the AGPL-3.0 notice (Phase 5
   gate item 2), acknowledging the protocol documentation regardless of the
   licence question being moot.

#### Reversal cost

**None** in the reimplement direction. Reversing — deciding to vendor openScale
source after all — would require a real licence review, an attribution audit, and
would contradict a standing ground rule. Not contemplated.

---

## ADR-003

### Atlas contention: yield the connection, dedup before POST

**Status:** Accepted as the working default · **Revisit:** before milestone 3
**Addresses:** PRP §8 open question 3 · **Unresolvable in Phase 0** (needs a decision, not evidence)

#### Context

Because the BF720 is connection-oriented (ADR-001), Atlas's `ble-scale-sync` and
Bascule cannot both hold the connection for one weigh-in. PRP §8.3 offers three
options: (a) Atlas primary, Bascule only when travelling; (b) first-to-connect
wins plus VitalForge-side dedup; (c) Bascule checks `/api/weight/recent` before
pushing.

The PRP says "decide before milestone 3", so Phase 0 does not need the final
answer — but the design cannot be written without *a* policy, and the agent
prompt requires implementing the most defensible option now.

#### The constraint that eliminates the obvious answer

The tempting policy — "if Atlas connected in the last N seconds, back off" — is
**unimplementable**. Bascule has no channel through which it can observe Atlas's
GATT activity: separate host, separate radio, no shared state. Any policy must be
expressed in signals Bascule can actually see.

#### Decision: (b) + (c), layered, using only observable signals

1. **Connect-level — yield, do not race.** If the GATT connect fails, or connects
   and immediately drops with status 8/19/22 (edge **E3**), Bascule assumes
   contention: **one** retry after 2 s, then yields with
   `SessionOutcome.Missed(CONTENTION)`. It does not hammer the connection. If
   Atlas got there first, Atlas finishes the weigh-in and VitalForge gets the
   reading through the other bridge — the user's outcome is correct either way.
2. **Delivery-level — dedup before POST.** Before submitting, Bascule calls
   `recentReadings(5 min)`. If VitalForge already holds a reading within
   **±0.20 kg** and **5 minutes** (identical constants to the local dedup rule,
   `00-design.md` §3.3), the local row is marked `SENT` with
   `deliveredFields = ∅` and `remoteDuplicate = true`. The full reading is kept
   locally — PRP §2 makes the local store authoritative for capture — but Garmin
   is not double-logged. **`remoteDuplicate` rows are permanently excluded from
   the v2 replay path** (`00-design.md` §4.4): their empty `deliveredFields`
   otherwise satisfies the replay predicate for every field, so the contract
   upgrade would re-POST every reading Atlas won and bulk-inject the duplicates
   this step exists to prevent, months after the fact.
3. **Degradation.** If `/api/weight/recent` does not exist, or the check itself
   fails, Bascule **posts anyway** — pure option (b). A failed dedup check must
   never block a delivery: a duplicate the user can delete is strictly better
   than a lost weigh-in. Assumption A5 in `00-design.md` §10 tracks whether the
   endpoint exists.

Option (a) — designating Atlas primary and running Bascule only when travelling —
is rejected as the *default* because it makes the common case require a manual
mode switch, and a bridge the user must remember to enable is a bridge that
misses readings. It remains available: the always-on toggle in ConfigScreen is
off by default and the wake path can be disarmed entirely.

#### Why this is defensible with what is known now

It requires no coordination protocol between two independently-deployed bridges,
no shared state, and no new VitalForge contract surface beyond an endpoint PRP
§8.3(c) already assumes. Every branch degrades toward "deliver the reading",
which is the direction the PRP is explicit about (§5: "Every decoded weight
reading is precious").

#### Reversal cost

**Low.** Concretely:

- Switching to pure (b): delete the `recentReadings` call and the
  `remoteDuplicate` flag's write site — one client method, one branch in
  `DeliveryCoordinator`. The column stays, harmlessly.
- Switching to (a): flip the wake-path default off and document the travel-mode
  toggle. No code change.
- Making Bascule primary and asking Atlas to yield: a change on the Atlas side,
  not here.

None of these touch the BLE state machine, the schema (beyond an unused column),
or the delivery state machine. This is the cheapest ADR in the set to reverse,
which is why it is safe to decide now rather than blocking Phase 1.

#### Revisit trigger

Before milestone 3, with: whether `/api/weight/recent` shipped, and observed
real-world contention frequency from `Missed(CONTENTION)` diagnostics counts.

---

## ADR-004

### The GATT session runs in an expedited foreground Worker, not a service started from the scan receiver

**Status:** Accepted · **Consequence of:** ADR-001

#### Context

ADR-001 establishes that a scan broadcast must initiate a GATT connect. The naive
implementation — receiver calls `startForegroundService()`, service connects —
fails on two independent platform limits:

1. A `BroadcastReceiver` is killable ~10 s after `onReceive` (even with
   `goAsync()`). A BF720 session takes tens of seconds. The receiver cannot own
   the connection.
2. On Android 12+ (API 31), starting a foreground service from the background
   throws `ForegroundServiceStartNotAllowedException`. A BLE scan `PendingIntent`
   broadcast is **not** on the platform's exemption list. On the primary target
   device this would throw on every single weigh-in.

#### Decision

Receiver → enqueue an **expedited `OneTimeWorkRequest`** (`ScaleSessionWorker`,
unique work name `scale-session`, `ExistingWorkPolicy.KEEP`) → the worker calls
`setForeground()` with type `connectedDevice` and owns the GATT session for its
lifetime. `WorkManager` expedited work with `setForeground()` is a supported path
to foreground execution from the background.

`BridgeForegroundService` survives only as the opt-in **always-on bridging** mode
(off by default), started from the UI where a foreground start is legal.

Expedited-work quota is finite. Edge **E10** covers exhaustion: if
`now - enqueuedAt > 20 s` at worker start the scale has powered off, so the worker
aborts before connecting and records `Missed(QUOTA)`. Three such events in 7 days
prompts the user once to enable always-on bridging. Quota pressure is inherently
low — sessions are ≤ 60 s (90 s hard ceiling) and occur 1–3× per day.

#### Reversal cost

**Low-to-moderate.** The session logic lives in `GattSession`, which is
host-agnostic; `ScaleSessionWorker` and `BridgeForegroundService` are both thin
hosts over it. Moving the primary path to a persistent FGS is a host swap plus a
default-toggle change, not a rewrite. Reverting to `startForegroundService` from
the receiver is not available — it is a platform prohibition, not a preference.

---

## ADR-005

### Delivery expiry is time-based and anchored to `retryEpochMillis`; `BLOCKED_AUTH` is a fourth status

**Status:** Accepted · **Modifies:** PRP §5 (retry cap and status enum)

#### Context

PRP §5 specifies three statuses (`PENDING` / `SENT` / `FAILED_PERMANENT`), a
backoff "capped at 15 min interval", and giving up "after N attempts (e.g. 10) or
M days". Two problems surface when those are made concrete against the PRP's own
requirements.

**Problem 1 — the arithmetic does not survive the stated outage.** With the
schedule 30 s, 1 m, 2 m, 4 m, 8 m, then 15 m, ten attempts elapse in roughly two
hours. The agent prompt's threat list and PRP §5's own rationale both require
surviving "VitalForge down for a week". A ten-attempt cap deletes the backlog
before lunch.

**Problem 2 — one status collapses three unrelated failures.** A 401 (token
rotated), a 400 (malformed body), and a 503 (server down) have nothing in common.
Under a single attempt counter, rotating the VitalForge token would march every
pending reading to `FAILED_PERMANENT` while the user was unaware — the exact
scenario the agent prompt names ("token rotated out from under the app"), and the
exact data loss PRP §5 exists to prevent.

#### Decision

**Expiry is time-based.** A row becomes `FAILED_PERMANENT` when
`now - retryEpochMillis > 14 days` **only if** its failures have been
`TRANSIENT`. With the 15-minute cap that is roughly 1 300 attempts and double the
required one-week outage. Attempt count is retained for diagnostics and for
computing backoff, but is not a give-up condition.

**The anchor is `retryEpochMillis`, not `capturedAtMillis`** — amended during the
Phase 0 hostile self-review, which found that the original capture-time anchor
silently cancelled this ADR's own central guarantee. Four transitions return a
row to `PENDING` long after capture: a new token saved, a "Retry" tap, a Branch B
confirmation (ADR-006), and a v2 replay requeue. Anchored at capture, each of
those rows is already past 14 days at the instant it re-enters `PENDING` and is
marked `FAILED_PERMANENT` again on its first transient failure. The
`BLOCKED_AUTH` case is the sharp one: this ADR exists so a token rotation noticed
two weeks later does not destroy the backlog, and a capture-anchored clock would
have destroyed exactly that backlog seconds after the user fixed the token —
"never expires while blocked" is worthless if unblocking expires everything.
`retryEpochMillis` is set to `capturedAtMillis` on insert and reset to `now` on
every entry into `PENDING`, alongside `attemptCount = 0`, giving each recovery a
full fresh 14-day retriable window. Full statement in `00-design.md` §3.4.

**Three error classes, six statuses.** Two of the six — `HELD_CONFIRM` and
`DECLINED` — are not delivery outcomes at all: both are set before any attempt
and neither is reachable from an HTTP response. They are named here only so the
status set is stated in one place; their rationale is ADR-006.

| Class | HTTP | Effect |
|---|---|---|
| `TRANSIENT` | 408, 429, 5xx, IO / timeout / DNS | `attemptCount++`, stays `PENDING`, backoff |
| `AUTH` | 401, 403 | → `BLOCKED_AUTH` |
| `PERMANENT` | 400, 404, 409, 413, 422, 3xx | → `FAILED_PERMANENT` immediately, one attempt |

`BLOCKED_AUTH` rows **accrue no attempts and run no expiry clock**. The drain
pauses globally (not per-row — every row would fail identically), a persistent
notification and a HistoryScreen banner surface it, and saving a new token flips
all `BLOCKED_AUTH` rows back to `PENDING` and drains immediately.

Permanent rejections fail on the first attempt rather than after ten: a body the
server calls malformed will still be malformed in fifteen minutes, and retrying
it wastes battery while hiding the real problem. `3xx` is permanent because
redirects are not followed at all (token-leak prevention, `00-design.md` §8.7) —
a moved endpoint is a configuration error the user must fix.

Recovery from `FAILED_PERMANENT` is always available — and that blanket promise
is exactly why a user-declined reading must **not** live in `FAILED_PERMANENT`.
`DECLINED` (ADR-006) is a separate terminal status offering no Retry, because one
tap on a standing Retry button would deliver a household member's weight to
Garmin and undo the entire Branch B hold. `FAILED_PERMANENT` means "we tried and
the server refused"; `DECLINED` means "the user told us this is not their
reading". Only the first is retryable. HistoryScreen offers
"Retry", which resets the row to `PENDING` with `attemptCount = 0` **and
`retryEpochMillis = now`** — without the second reset the Retry button would be
inert on any row older than a fortnight, which is most of the rows anyone would
want to retry. Nothing is deleted, ever — the local store is authoritative for
capture.

#### Reversal cost

**Low.** Expiry and the class→status mapping are constants and one `when` block
in `DeliveryCoordinator`. Removing `BLOCKED_AUTH` would need a Room migration
(status is a string column, so a data migration mapping `BLOCKED_AUTH` →
`PENDING`) — cheap, but it is a migration, so it is the one part of this ADR that
is not free to undo after v1 ships. `retryEpochMillis` is a non-null `Long`
column defaulting to `capturedAtMillis`; dropping it would likewise be a
migration, but it is added before v1 ships so no migration is needed to introduce
it.

---

## ADR-006

### `HELD_CONFIRM` is a fifth status, not a flag on `PENDING`; decline is a terminal `DECLINED`

**Status:** Accepted · **Consequence of:** PRP §8.5 Branch B (`00-design.md` §7)
**Raised by:** the Phase 0 hostile self-review (item 19)

#### Context

Under Branch B — the fallback if the BF720 exposes no user index — a reading more
than 1.5 kg from the last confirmed reading cannot be attributed to JD, so it is
withheld until the user confirms it. The design stated that hold as behaviour
("stored `PENDING` and held for confirmation") but gave it no mechanism: no
column, no state, and a drain query defined as "all `PENDING` rows".

That gap is not cosmetic. The drain would have delivered every held reading on
its next run, which is precisely the wrong-user delivery to Garmin that PRP §8.5
raises the question to prevent and that `00-design.md` §8.4 claims to handle. The
single highest-consequence gate in the design was the one enforced only by prose.

#### Decision

A held reading takes **`status = HELD_CONFIRM`**, a fifth status. The delivery
drain selects `status = 'PENDING'` and nothing else.

- Confirm ("Yes, that's me") → `PENDING`, with `retryEpochMillis = now` and
  `attemptCount = 0` (ADR-005). The confirmation is the attribution, so the row
  is never re-gated, and it becomes the new Branch B baseline.
- Decline ("Not me") → **`DECLINED`**, a sixth status that is terminal and offers
  no Retry. Kept locally — PRP §2 makes the local store authoritative for
  capture — never delivered, never a baseline, and excluded from the dedup corpus
  (`00-design.md` §3.3) so it cannot suppress JD's own next reading. Routing
  decline to `FAILED_PERMANENT` instead was the first draft of this ADR and was
  wrong: ADR-005 promises every `FAILED_PERMANENT` row a HistoryScreen "Retry"
  button, so a single tap would have delivered the household member's weight to
  Garmin — the hold defeated by an affordance defined in another section.
- No response → held indefinitely. `HELD_CONFIRM` runs **no expiry clock**: a
  reading nobody answered is not a delivery failure and must not age into
  `FAILED_PERMANENT` pretending to be one.

#### Why a status and not a boolean on `PENDING`

`PENDING` means "owed to VitalForge, deliver on the next drain". A held reading
means the opposite. Encoding it as `PENDING` + `confirmationRequired = true`
makes the wrong-user gate depend on every present and future drain query
remembering to `AND NOT confirmationRequired` — and the first query that forgets
delivers a household member's weight to Garmin silently, with no error and no
log. A status makes the safe behaviour structural: a query that forgets
`HELD_CONFIRM` exists simply does not select those rows.

**Qualified in Phase 2 (O-06).** The sentence above is true **for allowlist
predicates** and false for denylist ones, and this design uses both. An allowlist
(`WHERE status = 'PENDING'` — the drain query, which is the query this ADR is
about) is fail-safe: a new status is excluded by default, and the claim holds
exactly as written. A denylist is fail-**open**: a new status is *included* by
default. `00-design.md` §3.3's dedup corpus is one — "all rows in the window
regardless of status, **except `DECLINED`**" — and it is load-bearing, because a
seventh status that can hold another person's weight would silently suppress one
of JD's genuine readings within 0.20 kg and 5 minutes. That is the exact loss
mode self-review item 23 identified and fixed for `DECLINED`, reappearing by
default for every status added after it. The hazard is live rather than
theoretical: this design's status set went from **3** to **6** in a single
self-review pass.

The corrected claim, and the rule that follows from it: **a status makes the safe
behaviour structural for allowlist predicates; the design must keep its
status-filtering predicates allowlist-shaped, or test them exhaustively.**
`DedupPolicyTest.everyStatusHasAnExplicitCorpusMembershipDecision` and
`...dedupCorpusMembershipIsExplicitPerStatus` enforce this for the one denylist
that exists today — adding a seventh status now fails a test rather than
inheriting a decision nobody made. The §3.3 denylist itself is *not* wrong and is
not being changed: a dedup check that misses rows creates duplicates, so
defaulting to inclusion is the right shape there. What was wrong was the
unqualified word "structural", which claims a safety property the design has in
only one of the two shapes it uses — and an unqualified claim is how the next
reviewer stops looking.

The cost is one extra value in a string column and one extra HistoryScreen
branch. Bad Garmin weight history is materially harder to clean up than a missed
weigh-in is to redo (`00-design.md` §8.4), so the asymmetry favours the stricter
encoding.

#### Scope

`HELD_CONFIRM` is only ever written on the Branch B path. If milestone 1 resolves
PRP §8.5 in favour of Branch A, wrong-user readings are dropped at the
persistence boundary on an unambiguous index mismatch and no row ever enters this
status — the status remains defined and unused, which costs nothing and keeps the
two branches on one schema.

**Superseded by `04-scale-admin-and-automation-plan.md` (P19).** The paragraph
above is the one part of this ADR that no longer describes the code, and a reader
trusting it will conclude `HELD_CONFIRM` is dead and that `HistoryViewModel`'s
confirm/decline path is unreachable UI. It is not: ADR-007 did resolve §8.5 in
favour of Branch A, but the multi-profile admin work then made holding — rather
than dropping — the Branch A behaviour too. `04-…` §1 states it directly:
"Readings from another slot are stored as `HELD_CONFIRM`; confirming uploads that
reading once without changing the active profile", and its §3 names
"`PENDING` versus `HELD_CONFIRM` routing" as delivered scope.

The actual predicate is in `ReadingIngestor.ingest`, and it is **wider** than
"another registered profile": a reading is `PENDING` only when it matches a
registered profile for this device *and* that profile is the active one.
Everything else is `HELD_CONFIRM` — including a reading whose `userIndex` is null
or matches no profile at all. So the status covers three cases, not one: another
registered profile, an unrecognised slot, and an unattributable reading.

What survives unchanged is the reasoning this ADR exists for. Held rows are still
excluded from the drain by an allowlist predicate, decline is still terminal
`DECLINED` and still outside the dedup corpus, and holding is still the strict
side of `00-design.md` §8.4's asymmetry — dropping an unattributable reading
loses a real weigh-in, where holding it costs one confirmation tap. Branch A
narrowed what needs holding; it did not remove the need.

#### Reversal cost

**Low before v1 ships, migration-gated after.** The status is added pre-v1, so
introducing it is free. Removing it later means a data migration mapping
`HELD_CONFIRM` rows to some other status — and choosing which one is a policy
decision (deliver them, or fail them), not a mechanical one. Reverting to a
boolean flag is possible but is a strict downgrade for the reason above and is
not contemplated.

---

## ADR-007

### PRP §8.5 resolved (Branch A): user attribution requires a UDS registration+consent handshake, not just connect+subscribe

**Status:** Accepted · **Evidence:** `03-hardware-validation.md`, live capture 2026-08-22
**Modifies:** `00-design.md` §2.6 (`ScaleDecoder`/handshake model), §7 (multi-user branch selection)

#### What the live capture showed

A throwaway diagnostic probe (`tools/hw-probe/`, not part of the Bascule
codebase) connected to the physical BF720 and found it implements the
standard Bluetooth SIG Weight/Body-Composition/User-Data profile (services
`0x181D`/`0x181B`/`0x181C`), not the fully-proprietary opcode protocol
openScale's older wiki page documents for other Beurer/Sanitas family
members. Enabling notifications and simply waiting produced **zero**
measurement traffic across multiple complete weigh-ins — the scale visibly
completed weight + bioimpedance on its own display each time, but sent
nothing to the connected, subscribed phone.

The missing step, found by reading openScale's current
`StandardWeightProfileHandler.kt` / `StandardBeurerSanitasHandler.kt`
(GPL-3.0, reimplemented here from protocol understanding per ADR-002 — not
copied): the scale gates Weight/Body-Composition indications behind the
**User Data Service User Control Point** (`0x2A9F`), a Bluetooth SIG
mechanism, not a Beurer-specific one. The working sequence:

1. Write `[0x01, consentLo, consentHi]` (**Register New User**) to `2A9F`
   with an app-chosen 16-bit consent code → scale indicates
   `[0x20, 0x01, 0x01, scaleIndex]` (success, assigned index).
2. Write `[0x02, scaleIndex, consentLo, consentHi]` (**Consent**) to `2A9F`
   → scale indicates `[0x20, 0x02, 0x01]` (accepted).
3. Only after step 2 succeeds does a subsequent weigh-in produce Weight
   Measurement (`2A9D`) and Body Composition Measurement (`2A9C`)
   indications, and the Weight Measurement frame carries the registered
   **User ID** (confirmed: `scaleIndex=2` came back embedded in the live
   weight frame, byte-for-byte matching the index assigned in step 1).

This is privacy-by-design at the firmware level (standard practice for UDS
scales, not a Beurer quirk): a scale storing multiple household members'
body composition should not push any of them to an unauthenticated
subscriber.

#### Decision

**Branch A of PRP §8.5 is confirmed live**, not just theoretically
preferred: the BF720 exposes a real user index, delivered inside the
Weight Measurement characteristic once a session is consented. `00-design.md`
§7's Branch B (weight-range sanity gate) is now confirmed unnecessary for
this device — it remains defined for portability to a future scale that
lacks UDS, per PRP's own pluggable-decoder goal, but is dead code for v1's
target hardware.

**Design consequence — the handshake belongs in the connect sequence, not
the decoder's `initSequence`.** `00-design.md` §2.6 modeled `HANDSHAKING`
as a fixed, stateless `List<GattOp>` returned once by the decoder. The real
handshake is stateful and conditional: register only if no local mapping
exists yet for JD's app-level identity; otherwise send Consent directly
using a **persisted** `scaleIndex → consentCode` pair. This mapping must be
stored — EncryptedSharedPreferences per the agent prompt's ground rule on
credential storage, since a consent code is a shared secret with the scale
in the same sense a token is a shared secret with VitalForge. `GattSession`
needs a new dependency (a small consent-store interface) and `DecodeEvent`
needs two new cases (`UserRegistered(scaleIndex)`, `UserConsented`) so the
session can react to indications on `2A9F` the same way it already reacts
to notifications on `2A9D`/`2A9C`. Full interface revision is Phase 2 work
(this ADR records the requirement and evidence; it does not redesign
`ScaleDecoder` — that redesign should happen with the devil's advocate
pass in the room, not bolted on post-hoc here).

**Second consequence — Body Composition frames do not self-identify.** The
live Body Composition Measurement frame carried no timestamp or user ID
(its own flags said so); only the paired Weight Measurement frame did.
`DecodeEvent.Stable` as currently modeled (00-design.md §2.6) assumes each
notification is independently a complete, attributable reading. It is not:
the two characteristics must be correlated within one session before
either is treated as complete and attributable. This is the same class of
gap as the P1-A finding `01-plan.md` already flagged for Phase 2 (§8.8 vs.
the capture tool) — another place where the connect-and-subscribe mental
model undersold the real protocol's statefulness.

#### Reversal cost

**Low for the fact itself** (§8.5 is now evidence, not a coin flip — nothing
to reverse). **Moderate for the design consequence**: the `ScaleDecoder`
interface and `GattSession` handshake modeling need a real revision pass in
Phase 2, informed by this ADR, before Phase 3 work packages WP-06/WP-07/WP-09
are implemented against it. Treat `00-design.md` §2.6 as provisional until
that revision lands — a note has been added there.

#### Amendment (Phase 2) — the staleness was wider than §2.6

The revision landed as `02-interface-revision.md`, and the Phase 2 devil's
advocate pass (`02-devils-advocate-findings.md`, O-05) established that scoping
this ADR's consequence to **one section** was itself the defect. ADR-007 is not a
decoder-interface problem; it is a **change of protocol family**, and its
consequences reach the Room schema (O-01), the emission model and the persist
rule (O-02), the multi-user machinery (O-03), the transport's notify/indicate
vocabulary (O-04), the constants table, the fixture corpus, the merge order
(O-05), the credential inventory (O-08), and the risk ranking (O-07). Provisional
banners now sit on `00-design.md` §2.7, §3.1 and §9 as well as §2.6, and the
per-objection record of what was done about each is
`02-phase2-dispositions.md`. Nothing in the decision above changes; what changes
is the blast radius this ADR claimed for itself.

---

## Deferred — not decisions, tracked here so they are not lost

| PRP §8 | Question | Why not decided in Phase 0 | Owner / when |
|---|---|---|---|
| 2 | LAN-only vs Tailscale base URL from day one | No design impact — base URL is a validated config string either way | JD, any time before Phase 5 |
| 6 | Body-comp trend handling (7-day moving average, keep out of recommendations) | Out of Bascule's scope in v1 — Bascule stores and delivers; presentation and the recommendations engine are VitalForge's | VitalForge side, milestone 7 |

**Resolved:** PRP §8.5 (user index) — see ADR-007. Confirmed via live
hardware capture, not deferred any further.

**Resolved:** PRP §8.4 (repo location) — `bearyjd/bascule` (JD's personal
account, matching VitalForge's precedent), public, per the AGPL-3.0 license's
own point of making source available. Pushed 2026-08-22:
https://github.com/bearyjd/bascule.
