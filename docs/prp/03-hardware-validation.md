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

- The two proprietary services (`0xFFFF`, `0xFF00`) — not exercised. Unknown
  whether Bascule ever needs them (the standard-profile path above was
  sufficient to get a full reading).
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
