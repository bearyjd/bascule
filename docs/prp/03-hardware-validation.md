# Hardware Validation — Milestone 1 (early capture)

**Status:** Partial — captured ahead of schedule during Phase 1, using a
throwaway diagnostic probe (`tools/hw-probe/`), not the Bascule app itself.
This document will be extended by the formal Phase 3 hardware checklist
(`01-plan.md` §5, checklist rows HW-01…HW-24); it is seeded here because the
evidence directly resolves a Phase 0 open branch (PRP §8.5) and corrects a
Phase 0 assumption about the connect sequence (see ADR-007).

Device under test: Beurer BF720, MAC `E7:DB:51:F1:36:91`, advertised name
`BF720`, advertised service UUID `0000181d` (Weight Scale Service).
Test host: Pixel 9 Pro Fold, Android 17 (API 37), 2026-08-22.

## What was confirmed

1. **The BF720 implements the standard Bluetooth SIG "Weight Profile"
   family of services**, not a fully proprietary protocol:
   - `0x1800` Generic Access, `0x1801` Generic Attribute
   - `0x180A` Device Information (Manufacturer/Model — `2A00` reads `"BF720"`)
   - `0x180F` Battery Service (`2A19` — read `100%` at test time)
   - `0x1805` Current Time Service (`2A2B`, write, standard 10-byte payload)
   - `0x181C` User Data Service (`2A85` DoB, `2A8C` Gender, `2A8E` Height,
     `2A99` Database Change Increment, `2A9A` User Index, **`2A9F` User
     Control Point**, write+indicate)
   - `0x181D` Weight Scale Service (`2A9E` Weight Scale Feature — read
     `b7 00 00 00`; `2A9D` Weight Measurement, indicate)
   - `0x181B` Body Composition Service (`2A9B` Body Composition Feature —
     read `cf 31 00 00`; `2A9C` Body Composition Measurement, indicate)
   - Two proprietary services, `0x0000FFFF` and `0x0000FF00` (custom
     Beurer channels; not exercised in this capture — see §9 in
     `00-design.md`, still symbolic)

   **Provenance:** cross-checked against openScale's modern
   `ScaleDeviceHandler` architecture, specifically
   `StandardWeightProfileHandler.kt` (GPL-3.0, public repo
   `oliexdev/openScale`) and its subclass `StandardBeurerSanitasHandler.kt`.
   Reimplemented from protocol understanding in the probe tool; no source
   copied (ADR-002 convention followed).

2. **PRP §8.5 resolved: the BF720 exposes a user index — Branch A applies.**
   See ADR-007 for the mechanism and its design consequence.

3. **Weight Scale Feature (`2A9E` = `b7 00 00 00`) decodes to:** Time Stamp
   supported, **Multiple Users Supported**, BMI supported, weight resolution
   0.01 kg, height resolution 0.01 m.

4. **Body Composition Feature (`2A9B` = `cf 31 00 00`) decodes to:** Time
   Stamp supported, **Multiple Users Supported**, Basal Metabolism, Muscle
   Percentage, Soft Lean Mass, Body Water Mass, and Impedance all supported;
   Muscle Mass and Fat-Free Mass are **not** supported fields on this unit.
   Mass resolution 0.01 kg.

5. **A live weigh-in produced a real, decodable Weight Measurement and Body
   Composition Measurement pair**, captured after completing the User
   Control Point registration+consent handshake (ADR-007):

   ```
   NOTIFY 2a9d (Weight Measurement), 15 bytes:
     0e f4 46 ea 07 08 16 10 33 01 02 3a 01 a4 06

   NOTIFY 2a9c (Body Composition Measurement), 14 bytes:
     98 03 a6 01 7a 1a 30 01 58 26 e0 1c 12 11
   ```

   Decoded (Bluetooth SIG Weight Measurement / Body Composition Measurement
   characteristic formats):

   | Field | Value | Source bytes |
   |---|---|---|
   | Weight flags | kg, timestamp present, user-ID present, BMI+height present | `0e` |
   | Weight | 90.82 kg | `f4 46` × 0.005 |
   | Timestamp | 2026-08-22 16:51:01 | `ea 07 08 16 10 33 01` |
   | User ID | **2** | `02` |
   | BMI | 31.4 | `3a 01` × 0.1 |
   | Height | 1.700 m | `a4 06` × 0.001 |
   | Body comp flags | kg, no timestamp/user-ID in this frame, BMR+muscle%+soft-lean+water+impedance present | `98 03` |
   | Body fat | 42.2% | `a6 01` × 0.1 |
   | Basal metabolism | ≈1620 kcal (6778 kJ) | `7a 1a` |
   | Muscle % | 30.4% | `30 01` × 0.1 |
   | Soft lean mass | 49.08 kg | `58 26` × 0.005 |
   | Body water mass | 36.96 kg | `e0 1c` × 0.005 |
   | Impedance | 437.0 Ω | `12 11` × 0.1 |

   **Internal consistency check:** BMI from weight/height
   (90.82 / 1.70²  = 31.43) matches the scale's own reported BMI (31.4).
   Fat mass (90.82 × 0.422 = 38.33 kg) subtracted from weight gives a lean
   mass (52.49 kg) consistent with soft lean mass (49.08 kg) plus a
   plausible bone-mineral remainder (~3.4 kg). The timestamp matches the
   Current Time value written moments earlier to the second. All of this
   corroborates that the decode (units, resolutions, field order) is
   correct, not coincidental.

   The Body Composition frame carries **no** timestamp/user-ID of its own
   (its flags said so) — it relies on being correlated with the Weight
   Measurement frame from the same session, exactly as openScale's
   `handleNewMeasurement` merge logic assumes. `00-design.md`'s `DecodeEvent`
   model (§2.6) needs a merge/pairing step for this, not just two
   independent `Stable` emissions — flagged as a Phase 2 finding, not fixed
   here.

## What was NOT yet confirmed

- ~~The two proprietary services (`0xFFFF`, `0xFF00`) — not exercised.~~
  **Enumerated 2026-09-12** — see "Proprietary services" below. Still unknown
  whether Bascule *needs* them; the standard-profile path remains sufficient
  for a full reading.
- Multiple weigh-ins / repeat-session stability, disconnect-mid-measurement
  behavior, and the other E1–E16 failure edges — none of these were
  exercised by this probe. They remain Phase 3 hardware-checklist items
  (HW-01…HW-24 per `01-plan.md` §5).
- Stabilization-flag vs quiescence-heuristic question (`00-design.md` §2.4)
  — the standard Weight Measurement characteristic has no explicit
  "final/stable" flag in the Bluetooth SIG spec; it simply notifies once
  when the scale has a result. This likely **replaces** the need for the
  quiescence heuristic entirely for this device — worth revisiting in
  Phase 2.
- Whether the BF720 disconnects/times out the BLE link after a fixed idle
  period, or on some other trigger, was observed informally (~2–15 minutes,
  inconsistent) but not characterized rigorously. Relevant to E-series
  session-lifetime assumptions in `00-design.md` §2.5 — flagged, not
  resolved.

## Proprietary services, enumerated 2026-09-12

Read-only reconnaissance. No writes were issued, so nothing here changed
scale state or consumed one of its eight user slots.

Device: Pixel 10 Pro Fold `57211FDCG0023C` (the spare — it is bonded to the
same BF720 and hw-probe is a separate package, so which phone probes does not
matter). Scale advertising at −63 dBm, battery `0x64` = 100%.

**The scale documents its own proprietary service.** Every characteristic in
`0xFFFF` carries a `0x2901` Characteristic User Description, and the firmware
fills them in:

| Char | `0x2901` name (verbatim, typo included) | Props | Value read |
|---|---|---|---|
| `0x0000` | `Scale Setting` | R/W | `ff 01 ff ff 1e 00 ff ff` |
| `0x0001` | `User List` | R/W/N | `08` |
| `0x0002` | `Initials` | R/W | `ff ff ff` |
| `0x0004` | `Acitivity Level` | R/W | `ff` |
| `0x0005` | `Copy User Measurement List From MCU to BT-Module` | R/W | `ff` |
| `0x0006` | `Take Measurement` | R/W/N | `ff` |
| `0x000b` | `Refer Weight/BF` | R | `ff ff ff ff` |

`0xFF00` holds a single characteristic `0xFF01` (R/W/WRITE_NR, no description)
reading `00 00`.

The scale accepted NOTIFY subscriptions on `0x0001` and `0x0006` (both
`status=0`), so the proprietary channels are live, not vestigial.

### Why two of these matter

- **`0x0004` "Acitivity Level"** is the input to the AMR coefficient
  `ReadingMapper.ACTIVITY_FACTOR` encodes. That constant is currently pinned
  by inference — a measured BMR/AMR pair bounds it to 1.8492..1.8507 — and the
  level itself rests on the user's recollection ("level 4"). A successful read
  here would make the level **authoritative** and, across levels, recover the
  rest of Beurer's table rather than the single point we have.
- **`0x0005` "Copy User Measurement List From MCU to BT-Module"** is a
  stored-measurement fetch. It is the mechanism behind the standing hypothesis
  that a session could be seconds rather than minutes, and that a weigh-in
  missed by a sleeping phone need not be lost at all.

### The blocker, and what it costs

Every user-scoped characteristic read back `0xff`. **The gate is consent
specifically, not UDS interaction in general** — tested rather than assumed:

`listusers` (`UCP LIST_ALL_USERS`, a plain write to `0x2A9F` that needs no
consent code) was accepted with `status=0` and then produced **no indication at
all**, and a re-read of all seven characteristics came back byte-for-byte
identical. A UCP operation that answers nothing without an authenticated user
is standard UDS behaviour, and it rules out the looser hypothesis that any
handshake traffic would populate these.

`0x0001 User List` returning `08` while everything user-specific reads unset
fits: the slot count is device-scoped, the rest is per-user.

Testing that needs a consent code. Bascule holds one (the Scale screen reports
"Registered as user slot 1") but it lives in `EncryptedPreferences` and is not
readable off the device. hw-probe's `register` command would mint a new user
**and burn one of the eight slots**, so it was not run. That is a deliberate
stop, not an unexplored path: the next step costs a slot and is the user's
call.

`0x0001` returning `08` is also ambiguous on its own — a count of eight slots,
or a bitmask with slot 4 set. Not resolved.

### `0x0000 Scale Setting` is populated, and is not the level

`ff 01 ff ff 1e 00 ff ff` is the only proprietary value carrying real data, so
it was worth checking as a shortcut to the activity level. It is not one:
`0x1e` is 30, which does not fit a 1–5 scale, and the characteristic actually
*named* `Acitivity Level` is `0x0004`, which is user-scoped and reads `ff`.
What the `01`, `1e` and `00` bytes mean is unknown — device-scoped settings of
some kind, since they survive with no user selected.

### Live weigh-in, no consent: the scale sends nothing at all

Run 2026-09-12, and it is the decisive result. hw-probe held the connection
with every relevant channel subscribed — standard Weight `0x2A9D` and Body
Composition `0x2A9C` indications, proprietary `0x0001` and `0x0006` notifies,
all confirmed `status=0`. Two weigh-ins were taken: one shod, one barefoot.

**Zero `onCharacteristicChanged` events. Not one, on any channel.**

The link was demonstrably live rather than quietly dead, which is what makes
this a finding instead of a failed setup:

- `BF720` did **not** appear in a scan taken mid-experiment, and a connected
  device does not advertise.
- `dumpsys bluetooth_manager` reported `ACL LE:Y` with encryption established
  (`keySize=16`) and four GATT connections held.

The shod attempt alone would have been confounded — no impedance path, so the
scale might simply never have completed a measurement. The barefoot attempt
removes that: the scale had everything it needed and still transmitted nothing.

**Conclusion: UDS consent gates the measurement path itself, not merely the
user-scoped reads.** Without it the BF720 will connect, answer device-scoped
reads, accept subscriptions, accept a time sync — and then never send a
measurement.

### The scale hangs up on an unconsented client

A second behavioural fact, not previously recorded: at 16:53:01, about ten
minutes after connecting, the scale terminated the link itself with
`status=19` — `0x13`, `GATT_CONN_TERMINATE_PEER_USER`. It tolerates an
unauthenticated client for a while and then drops it. Reconnecting worked
immediately.

### What this validates

Bascule's existing design. The UDS register/consent handshake in `GattSession`
is not defensive politeness that could be trimmed for speed — it is the only
reason any measurement arrives at all. Anyone tempted to shorten the session
by skipping it should read this section first.

### The one experiment left, and what it costs

**Register hw-probe as its own user.** It is now the only route to the
user-scoped values, including `0x0004 Acitivity Level` and the
`0x0005` stored-measurement fetch, because every cheaper probe is exhausted:
device-scoped reads are done, a bare UCP write changes nothing, and the
measurement path is consent-gated.

It **burns one of the scale's eight user slots**, and slots are not recoverable
without a factory reset. Not run. That is the user's call.

Cost of the experiments above, stated plainly: two weigh-ins were spent and
neither was delivered, because hw-probe owned the GATT link and Bascule could
not capture through it.

### Reproducing

`tools/hw-probe` gained a `dumpprop` command for this: it reads the `0x2901`
descriptor of every characteristic in `0xFFFF`/`0xFF00` and then every
readable value, one operation at a time because GATT allows a single
outstanding request.

```
adb shell am broadcast -a com.ventouxlabs.hwprobe.CMD --es cmd connect --es addr E7:DB:51:F1:36:91
adb shell am broadcast -a com.ventouxlabs.hwprobe.CMD --es cmd dumpprop
adb shell cat /sdcard/Android/data/com.ventouxlabs.hwprobe/files/capture.txt
```

Note `tools/hw-probe` has **no Gradle wrapper of its own**; build it with the
root one and give it an SDK path:

```
echo "sdk.dir=$ANDROID_HOME" > tools/hw-probe/local.properties
./gradlew -p tools/hw-probe assembleDebug
```

## Tooling used

`tools/hw-probe/` — a standalone, throwaway Android app (not part of the
Bascule module tree, not gated by the phase process) built solely to do
raw BLE reconnaissance: scan, connect, enumerate services/characteristics,
enable notifications/indications, issue reads, drive the User Control Point
handshake, and log every byte to both Logcat and an on-device file. Driven
remotely via a debug-only `adb shell am broadcast` command channel
(`com.ventouxlabs.hwprobe.CMD`) so the only manual step required was
physically stepping on the scale. Not intended to ship or to seed
`BeurerDecoder` — Phase 3's `WP-05`/`WP-09` still implement the real
decoder inside the Bascule codebase, now with confirmed byte-level ground
truth instead of symbolic placeholders.
