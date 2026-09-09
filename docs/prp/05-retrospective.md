# 05 — Retrospective

Written at the Phase 5 gate, against `v0.1.0`. Answers the three questions
`bascule-agent-prompt.md` §Phase 5 item 4 asks, and nothing else.

---

## 1. What the fake layer got wrong about the real device

The fakes were good at protocol *shape* and blind to device *behaviour*. Every
one of the following passed a green suite for weeks and was wrong on hardware.

### The scale does not store and forward — this was the expensive one

Every design doc assumed a session could begin, connect, and collect a
measurement the scale had already taken. It cannot. The BF720 advertises
continuously while awake and indicates a measurement **only live, to a client
already connected, consented and subscribed at the moment someone steps on**.

The fake transport happily replayed a frame whenever a session asked for one,
so nothing in the suite could distinguish "collect a stored reading" from
"be present for a live one". Confirmed on hardware only by counting: 105
consecutive idle sessions overnight, every one reaching the radio and
completing the handshake, none carrying a measurement.

The cost was not one constant. `FIRST_INDICATION_TIMEOUT = 45 s` was *derived*
from the wrong model — it assumed a session began when the user stepped on,
when sessions actually begin whenever the phone reconnects. A step-on had to
land inside a 45-second window that opened every five or six minutes: about
one chance in seven. That is the whole of "works sometimes, at random", and it
was a modelling error wearing a timing constant's clothes.

### A fake link is always encrypted; a real one is not

The bond requirement was invisible to the fakes. E5 (pairing) was specified in
the design, named in the plan, and **never implemented** — `createBond` existed
in the transport and nothing called it. Three review rounds and a design doc
that names the case did not catch it, because the only phone in the project had
been bonded since the August hardware session. The first genuinely new phone
failed three times out of three with `HandshakeFailed`, two seconds in, with a
pairing dialog sitting unanswered in the notification shade.

A fake cannot be un-bonded, so no test could have gone red.

### Real timing is not fake timing

- E8's reconnect ran in a 5-second window. This scale takes 4-6 seconds to
  connect, so the reconnect nearly always "failed", the session ended
  `DROPPED`, and escalating backoff turned the scale's own idle timer into
  20/40/80-second coverage gaps.
- The `2 s` opening-write timeout was fine against a fake and far too short
  against a stack that pauses the operation to pair.

### The platform is not modelled either, and that bit twice

- **A BLE scan registration does not survive the Bluetooth stack restarting.**
  `00-design.md` §8.2 says this about reboot and `BootReceiver` handles it; an
  adapter cycle has the identical effect and nothing handled it at all.
  Capture was dead for eleven hours while every diagnostic — service running,
  both toggles on, cooldown empty, config correct — read healthy. Only the
  registration inside the stack was gone.
- **`RECEIVER_NOT_EXPORTED` silently drops Bluetooth broadcasts** on Android
  14+, because they come from the stack's uid rather than the app's or the
  system's. This made the bond receiver deaf and the `ADAPTER_OFF` path
  unreachable in production.
- **Robolectric does not model `stopSelf(int)`'s newer-start no-op.** It
  records the id and stops regardless, so a test asserting "the service
  stopped" passes whatever id you pass. The honest JVM claim is that the code
  passes the *right* id; the platform behaviour is untestable here.

### The lesson worth carrying

The suite proved the code did what the design said. It could not test whether
the design described the device. Both hardware sessions that produced real
findings did so within minutes, and neither needed a new test to *find*
anything — they needed a scale, a phone, and someone standing on it.

---

## 2. Which devil's-advocate objections turned out load-bearing

Eleven objections were raised in `02-devils-advocate-findings.md`. Judged now
against what hardware and production actually did:

### Load-bearing, confirmed by evidence

**O-04 — "The transport models notifications; the device uses indications."**
Correct, and structural. The stack log shows `GATTC_SendHandleValueConfirm`
immediately after the User Control Point subscription: that confirm *is* the
consent acknowledgement. A notification-shaped transport would have had no
place to put it.

**O-07 — "RISK-1 is scheduled first against a risk the project's own evidence
says may not exist."** The sharpest call in the document. Connect and handshake
reliability were assumed to be the problem and were never the problem: every
one of those 105 idle sessions reached the radio and completed the handshake.
The real defect was the listening model. Effort went to the risk that felt
dangerous rather than the one the evidence pointed at.

**O-08 — "The consent code is an unowned credential, and `allowBackup=false`
guarantees it is lost on every device change."** Load-bearing in the most
literal sense: it now constrains daily operations. `EncryptedPreferences`
builds its `MasterKey` in the `AndroidKeyStore`, so the key dies with the
install and a pulled copy of the consent file is permanently undecryptable.
The registration cannot be recreated without the physical scale, and
re-registering burns one of eight slots. "Never uninstall to test something"
is a standing rule because of this objection.

**O-10 — "Two constants whose stated justifications are factually wrong."**
Right about the class of error, and the specific instance was worse than
alleged: `FIRST_INDICATION_TIMEOUT`'s justification was not merely wrong, it
encoded a false model of the device (see §1).

**O-11 — "E7 has no counter, no streak guard, and no diagnosis."** Directly
responsible for the two things that made every later diagnosis possible: the
`fails:` streak counter (which made 105 idle sessions *countable*) and
`CaptureAttemptLog` (which gave "I stepped on the scale and nothing happened"
an answer that survives the worker process).

### Real but smaller than argued

**O-01 — "The schema cannot hold the payload that was actually captured."**
True in direction. The gaps were narrower than claimed and mostly server-side:
`bmi`/`bmr`/`amr` had no home in VitalForge at all until PR #40, and `amr`
still has nothing to fill it (§3).

**O-05, O-06, O-09** — argued about documentation accuracy and CI matrix
coverage. Correct on the facts, but nothing downstream turned on them.

### Overtaken by events

**O-02 and O-03** (correlation timeout; two users in one session) described
failure modes of a merge model that the "no store-and-forward" finding
reshaped. Worth re-reading against the current `MeasurementCorrelator` rather
than treating the dispositions as still binding.

### The pattern

The objections that paid off attacked **premises** — what the device does,
which risk is real, who owns a credential. The ones that did not attacked
**wording**. That is a usable filter for the next round.

---

## 3. What milestone 7 needs from VitalForge before it can start

Milestone 7 is full-payload delivery plus replay (WP-22). Bascule's side
exists: `ReplayEligibility` and `ReplayMigrationWorker` are implemented and
deliberately wired to nothing.

### Delivered

- **`client_id`** — exact-identity idempotency, checked before the dedup
  window (`vitalforge` PR #39).
- **`captured_at`** — the dedup window anchors on capture time instead of
  receipt time, so a replay months later still matches (same PR).
- **`bmi` / `bmr` / `amr` columns** (`vitalforge` PR #40), now live on
  `192.168.1.21`.

### Blocking, and new since the last handoff

**Root-level compatibility routes, or a person-slug in the client.**
VitalForge moved every weight route under `/p/{slug}/` and removed the root
ones. Bascule was fixed to carry the prefix in its base URL, but **replay has
the same exposure**: any client posting to a root route now 404s, and
`ResponseClassifier` maps 404 to `PermanentRejection`, so a replay batch would
mark every row permanently failed on its first attempt. Two real weigh-ins
were lost this way before the cause was found.

VitalForge should offer root-level routes resolving to
`users.default_person_id` — its own code already contemplates "pwa/tasker/
legacy bascule" callers — and must do so without reintroducing
`get_primary_person_id()` into request paths or weakening the deliberate
404-not-403 rule that keeps household membership private.

**An endpoint that tells a client its own slug.** Today the only ways to
discover it are reading the browser URL or querying the database. A
misconfigured client has no feedback loop.

**A distinguishable 404.** A missing route currently looks identical to
`require_person`'s deliberate no-grant 404, so a client cannot tell "you are
not allowed" from "that endpoint does not exist".

### Known residual, not to be silently reopened

A legacy row whose *original* delivery was itself delayed past the dedup
window has no reliable capture-time proxy for a later replay to match against
— VitalForge never had the chance to record one for that row. `captured_at`
fixes every row captured from here forward, not retroactively for that
specific backlog shape. This is recorded in `00-design.md` §4.4 and
`01-plan.md`'s WP-22 section; it was allowed to drift back to "unknown" once
already.

### Bascule-side item that is not VitalForge's problem

`amr` can never be populated from the SIG Body Composition profile — no such
field exists. `v0.1.0` derives it as `bmr × 1.85`, a coefficient measured from
this device, encoding one activity level and reading a few kcal above the
scale's display. If milestone 7 wants a measured AMR rather than a derived
one, that is a reconnaissance project against the proprietary `0xFFFF` /
`0xFF00` services, which have never been exercised — the same investigation
that would answer the unmeasured battery cost of holding a link open for
minutes.
