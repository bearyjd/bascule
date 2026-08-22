# PRP: Bascule (Ventouxlabs)

Android BLE bridge that reads weight from a Bluetooth scale and pushes it to a
VitalForge instance (`POST /api/weight`) with no manual entry.

- **Org/brand:** Ventouxlabs
- **License:** AGPL-3.0
- **Language:** Kotlin, Jetpack Compose UI
- **Min SDK:** 26 (BLE background scan support baseline)
- **Companion service:** VitalForge (`vitalforge-weight`, port 8085, MIT) —
  https://github.com/bearyjd/vitalforge. Bascule is a separate AGPL-3.0 repo;
  changes contributed back to VitalForge are MIT.

---

## 1. Problem statement

VitalForge's current intended workflow is NFC-tap + manual weight entry. This
app removes the manual step entirely: it listens for BLE advertisements from a
supported Bluetooth scale, decodes the stabilized weight reading, and POSTs it
to VitalForge automatically. Functions as the mobile-side counterpart to the
Atlas-hosted `ble-scale-sync` listener — same outcome, different bridge.

## 2. Scope

### Target hardware (v1)

**Beurer BF720** (Amazon ASIN B0BZJRB9T7). Selected after reviewing openScale's
supported-scale matrix:

- Xiaomi Mi Body Composition Scale 2 — best-supported decoder in the ecosystem
  (clean ✓ across initialisation / history / body metrics, no remarks), but
  effectively discontinued in the US; only available at ~$75 markup or via
  counterfeit-risk channels. Rejected on sourcing.
- Beurer BF700 — equally clean matrix row, but out of stock.
- Renpho ES-CS20M — cheapest and in stock, but openScale notes it borrows the
  Trisa Body Analyze body-measurement library pending full reverse engineering
  of the QN scale library, and marks history support as incomplete. Acceptable
  fallback (we only consume weight), but built on partially-understood protocol.
- **Beurer BF720 — chosen.** In stock, Amazon-fulfilled first-party. Part of the
  Beurer/Sanitas protocol family (BF105/600/850/915/950, Silvercrest SBF76/77,
  Sanitas SBF72) with supported initialisation and body metrics and no listed
  known issues. openScale maintains a dedicated Beurer/Sanitas wiki page, so the
  protocol is documented rather than needing fresh reverse engineering.
  History-data support is absent for this model — irrelevant here, since Bascule
  reads live measurements, not stored on-device history.

### In scope (v1)
- BLE scan + decode for Beurer BF720 (Beurer/Sanitas protocol family)
- **Full BIA payload capture** — weight plus body fat %, body water %, muscle %,
  bone mass, BMI, BMR, AMR. All decoded fields are stored locally in Room even
  where VitalForge cannot yet accept them (see below), so nothing is discarded
  at the point of measurement.
- **Local store is authoritative for capture.** Bascule persists the complete
  reading before any network attempt; VitalForge is a delivery target, not the
  system of record for what the scale produced.
- **Partial delivery.** `POST /api/weight` currently accepts only
  `{"weight", "unit"}`. Bascule sends what the endpoint accepts and marks the
  remaining fields `undelivered` in Room. When VitalForge's schema is extended,
  a replay path re-sends historical readings with the full payload — so the
  body-comp history isn't lost to the gap between the two projects shipping.
- Foreground service with persistent notification while actively bridging
- Background scan triggered by BLE advertisement match (Android `ScanFilter` +
  `PendingIntent`-based background scan, not a naive always-on foreground scan,
  to preserve battery)
- Stable-reading debounce logic (ignore transient/fluctuating values)
- POST to VitalForge `/api/weight` with `{"weight": <float>, "unit": "lbs"|"kg"}`
- Token-based auth against VitalForge (see §5 — requires a small upstream change)
- Manual entry fallback screen (parity with the PWA, for travel/away-from-scale use)
- Config screen: VitalForge base URL (LAN vs. Tailscale/public), unit preference,
  auth token storage (EncryptedSharedPreferences)
- Local log of last N pushes with success/failure status, retry-on-failure queue

### Out of scope (v1)
- Multi-user support. The BF720 does auto-recognition across 8 profiles, but
  VitalForge is single-tenant (one Garmin account). Bascule filters to JD's
  user index and drops other household members' readings — it does not attempt
  to route them anywhere. See §8 open question 5.
- Pushing to Garmin directly from the app (VitalForge owns that integration —
  see conversation rationale: Garmin has no public write API, and a second
  reverse-engineered client would double the maintenance surface)
- Support for scales beyond the Beurer/Sanitas family (design the decoder as a
  pluggable interface so more can be added later, mirroring ble-scale-sync's and
  openScale's adapter model — Renpho and Xiaomi adapters are obvious v2 additions)
- Wear OS companion

## 3. Architecture

```
app/
├── ble/
│   ├── ScaleScanner.kt          # BLE scan setup, ScanFilter, background PendingIntent scan
│   ├── decoders/
│   │   ├── ScaleDecoder.kt      # interface: fun decode(advertisement): ScaleReading?
│   │   ├── BeurerDecoder.kt     # Beurer/Sanitas family (BF720 target)
│   │   └── RenphoDecoder.kt     # optional fallback adapter (ES-CS20M)
│   └── ScaleReading.kt          # weight, bodyFatPct, bodyWaterPct, musclePct,
│                                # boneMassKg, bmi, bmr, amr, userIndex,
│                                # unit, isStable, timestampMillis
├── service/
│   └── BridgeForegroundService.kt  # owns scanner lifecycle, notification, wake locks
├── data/
│   ├── ReadingDao.kt            # Room DAO — queued, sent, undelivered-fields
│   ├── ReadingEntity.kt         # full BIA payload + delivery state per field set
│   └── BasculeDatabase.kt       # Room database (readings + push history)
├── network/
│   ├── VitalForgeClient.kt      # POST /api/weight, persistent retry/backoff queue
│   └── AuthTokenStore.kt        # EncryptedSharedPreferences wrapper
├── ui/
│   ├── ManualEntryScreen.kt     # Compose fallback UI, mirrors PWA quick-entry
│   ├── ConfigScreen.kt          # base URL, unit, token, scale selection
│   └── HistoryScreen.kt         # last N push attempts + status
└── MainActivity.kt
```

### Data flow
1. `BridgeForegroundService` starts (on boot if enabled, or manually from UI)
2. `ScaleScanner` registers a `ScanFilter` for the Renpho's manufacturer ID / service UUID
3. On matching advertisement → `RenphoDecoder.decode()` → `ScaleReading`
4. If `isStable == true` and reading differs from last pushed value → `VitalForgeClient.post()`
5. On success: log entry, notification update ("Logged 185.4 lbs — Garmin synced")
6. On failure (network/auth): persist reading to Room (`PendingReadingEntity`)
   immediately — before any retry attempt — so a killed process or dead battery
   never loses a reading that was already decoded off the scale. Retry is then
   just "drain the pending table," not "hope the in-memory attempt succeeds."

## 4. BLE decode reference

Primary references, in order of preference:

1. **openScale's Beurer/Sanitas wiki page and source** —
   github.com/oliexdev/openScale/wiki/Beurer-Sanitas plus the corresponding
   handler in the openScale repo. openScale is GPL-3.0; AGPL-3.0 is compatible
   for reuse in this direction, but confirm before copying source verbatim.
2. **openScale's "How to support a new scale" guide** — documents the modern
   Kotlin `ScaleDeviceHandler` architecture (`supportFor(device)` dispatch on
   advertised name / service UUIDs), which maps almost directly onto Bascule's
   `ScaleDecoder` interface. Also documents capturing BT traffic against the
   vendor app if any protocol gaps need filling.
3. `ble-scale-sync` (github.com/KristianP26/ble-scale-sync) as a secondary
   cross-check — many of its adapters are themselves ported from openScale.

Key elements to replicate:
- Device identification via advertised name / service UUID (Beurer family uses
  a consistent naming prefix — confirm exact string against a live scan)
- GATT connect + characteristic subscribe (Beurer is **not** a pure broadcast
  scale like the Xiaomi — expect a connection-oriented flow, which means
  `BLUETOOTH_CONNECT` permission is required, not optional)
- Initialisation handshake (openScale marks Beurer init as fully supported —
  no one-time vendor-app pairing needed, unlike e.g. the Digoo DG-S038H)
- Stabilized-measurement detection before emitting a `ScaleReading`
- Unit handling (scale supports kg/lb/st switching; normalise to the user's
  configured VitalForge unit before POSTing)

**Note the architectural implication:** because Beurer requires a GATT
connection rather than passive advertisement listening, the Atlas-side
`ble-scale-sync` bridge and Bascule cannot both hold a connection to the scale
simultaneously. See §8 open question 3 — dual-bridge operation likely needs one
designated primary, or acceptance that whichever connects first wins that
weigh-in.

## 5. Persistent retry queue (Room-backed)

Rationale: an in-memory retry queue is lost if the OS kills the process (likely,
since this runs as a background BLE service on a phone with aggressive battery
management) or the phone reboots/dies before a retry succeeds. Every decoded
weight reading is precious — it required you to physically step on a scale —
so the write-ahead step is: **decode → persist to Room → attempt push → mark
sent on success.** The POST is never the only copy of the data.

- **Schema** (`ReadingEntity`): `id`, `capturedAtMillis`, `userIndex`,
  `weightValue`, `unit`, `bodyFatPct`, `bodyWaterPct`, `musclePct`,
  `boneMassKg`, `bmi`, `bmr`, `amr` (all body-comp fields nullable — the scale
  may not report every field on every reading), `attemptCount`,
  `lastAttemptMillis`, `lastError`, `status` (`PENDING` / `SENT` /
  `FAILED_PERMANENT`), and `deliveredFields` (which subset actually made it to
  VitalForge — enables the replay path once the endpoint is extended)
- **Write path**: `BeurerDecoder` emits `ScaleReading` → full payload inserted
  as `PENDING` → `VitalForgeClient` POSTs the fields the endpoint accepts → on
  2xx, `status = SENT` and `deliveredFields` records what was sent; on failure,
  `attemptCount++`, `lastError` recorded, row stays `PENDING`
- **Retry trigger**: `WorkManager` periodic + constraint-based job (network
  connected) drains all `PENDING` rows on a backoff schedule (e.g. exponential,
  capped at 15 min interval), rather than relying on the foreground service
  alone — this survives the service being killed and restarted
- **De-dup guard**: before inserting a new `PENDING` row, compare against the
  most recent `SENT` row's `weightValue` + a short time window, to avoid double-
  logging if the scale re-broadcasts the same stable reading (common — most BLE
  scales repeat the final advertisement several times before powering off)
- **Cap on retries**: after N attempts (e.g. 10) or M days, mark `FAILED_PERMANENT`
  and surface prominently in `HistoryScreen` rather than retrying silently forever
  — auth token rotation or a renamed endpoint shouldn't retry into a black hole
- **HistoryScreen** reads directly from Room (`PENDING` / `SENT` / `FAILED_PERMANENT`
  all visible), so "did my weigh-in make it to Garmin" is answerable from the
  app even if VitalForge/Atlas was down at the time

This makes Bascule resilient to exactly the failure mode that motivated asking
about a local Garmin integration — VitalForge being temporarily unreachable —
without taking on a second, fragile Garmin auth implementation.

## 6. VitalForge-side change required

Current auth is cookie-based session (30-day expiry), documented for Tasker use
via a static `Cookie: vf_session=...` header. That's brittle for an unattended
background service — session can expire silently with no re-auth path.

**Recommended addition to VitalForge** (small, backward-compatible):
- New env var `VITALFORGE_API_TOKEN` (long-lived static token, separate from
  session auth)
- `/api/weight` accepts `Authorization: Bearer <token>` as an alternative to the
  session cookie
- No expiry, revocable by rotating the env var

This is a ~10-line change to `shared/auth.py` and the weight route's auth
dependency. Worth doing before Bascule v1 ships rather than working around it
client-side.

## 7. Permissions (Android manifest)

- `BLUETOOTH_SCAN` (`neverForLocation` if service-UUID filtering is sufficient —
  avoids needing `ACCESS_FINE_LOCATION`)
- `BLUETOOTH_CONNECT` — **required**, since Beurer uses a connection-oriented
  GATT flow rather than passive advertisement broadcast
- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`
- `POST_NOTIFICATIONS` (Android 13+)
- `RECEIVE_BOOT_COMPLETED` (optional, to restart bridging after reboot)

## 8. Open questions for JD

1. Confirm openScale (GPL-3.0) / `ble-scale-sync` license terms permit reusing
   decoder logic in an AGPL-3.0 repo, or reimplement from protocol docs
2. LAN-only initially, or Tailscale-routed base URL from day one?
3. **Dual-bridge contention.** Because Beurer is connection-oriented, Atlas
   (`ble-scale-sync`) and Bascule can't both connect for the same weigh-in.
   Options: (a) designate Atlas primary and run Bascule only when travelling,
   (b) first-to-connect wins and rely on `/api/weight/recent` dedup on the
   VitalForge side, (c) Bascule checks `/api/weight/recent` before pushing to
   avoid double-logging. Decide before milestone 3.
4. Repo location: new `ventouxlabs/bascule` GitHub org/namespace, or under your
   personal `bearyjd` account like VitalForge?
5. **Multi-user filtering.** Confirm on first live scan whether the BF720
   payload exposes a user-index field. If yes, filter on it. If not, fall back
   to a weight-range sanity gate (accept only readings within N lbs of the last
   confirmed reading) — cruder, and it will misfire if two household members
   are close in weight. Worth checking before writing the decoder, since it
   determines whether household use is safe or a source of bad Garmin data.
6. Body-comp trend handling: BIA readings move several percent day-to-day on
   hydration alone. Recommend surfacing only the 7-day moving average in any
   UI, and not letting body-comp deltas feed VitalForge's recommendations
   engine until there's enough real data to judge the noise floor.

## 9. Milestones

1. BLE connect + Beurer decode working standalone (log full BIA payload to
   console, no network) — also answers open question 5 re: user index
2. VitalForge token-auth change shipped
3. Room schema + full-payload persistence, weight-only POST integration
4. Manual entry fallback UI
5. WorkManager drain job + history screen
6. Config screen, polish, PWA-parity pass
7. *(gated on VitalForge endpoint extension)* full-payload delivery + replay of
   historical readings' undelivered body-comp fields
