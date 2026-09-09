<!-- Generated: 2026-09-08 | Files scanned: 26 (ble/) | Token estimate: ~900 -->

# Capture (BLE)

The app's "backend": everything from an advertisement to a decoded reading.

## Session flow

```
ScanBroadcastReceiver / BridgeForegroundService.enqueueOnce
  → ScanEnqueueCooldown.claim(address)      # one session per window
  → ScaleSessionEnqueuer → ScaleSessionWorker (WorkManager, expedited)
      → ScaleOperationCoordinator            # serialises session vs registration
      → GattSession.run()
          ConnectLadder    E1 connect · E2 discover · E3 stale-drain
          handshake        2A2B current time → UDS User Control Point consent
          MeasurementPhase E7 subscribe · E17 listen · E8 reconnect · ceiling
      → DecodeEvent.Stable → ReadingIngestor
```

## Key files

| File | Role |
|---|---|
| `ble/ScaleScanner.kt` | LOW_POWER `PendingIntent` scan; gated on automatic-capture + active profile |
| `ble/ScanBroadcastReceiver.kt` | Wakes on scan result; `enqueuerFactory` is the test seam |
| `ble/ScaleRegistrar.kt` | UDS register/consent; burns one of the scale's 8 slots |
| `ble/session/GattSession.kt` | Session state machine (652 lines after the split) |
| `ble/session/ConnectLadder.kt` | Connect/discover/drain half |
| `ble/session/MeasurementPhase.kt` | Subscribe/listen/emit half, ceiling memory |
| `ble/session/AndroidGattTransport.kt` | Real GATT; bond + adapter receiver, `RECEIVER_EXPORTED` |
| `ble/session/SessionBudget.kt` | All timing constants; ceiling derived, not chosen |
| `ble/session/ScaleSessionWorker.kt` | Worker shell; reports a terminal disposition from *every* exit |

## Decoding

```
0x2A9D Weight Measurement      → WeightMeasurement.kt      (weight, BMI, height, user, timestamp)
0x2A9C Body Composition        → BodyCompositionMeasurement.kt (fat, water, muscle, lean, impedance, BMR)
        ↓ both                   MeasurementCorrelator.kt  → ScaleReading
```

`BeurerDecoder` implements `ScaleDecoder`. `FrameReader` is the bounds-checked
cursor; a flagged-but-unknown field is still *read* so following offsets stay
correct.

**Not in the SIG profile:** bone mass and AMR. Neither arrives on any frame.
AMR is derived downstream in `ReadingMapper`; bone mass stays null.

## Timing (SessionBudget)

- Listen window: 8 min. `HARD_SESSION_CEILING` and `BONDING_SESSION_BUDGET`
  are *derived* from it so the chained-handshake worst case still overruns the
  ceiling.
- Bond wait: 30 s — the scale starts pairing on the first write and a human
  must accept.
- Reconnect: up to 3 drops per session, window = `CONNECT_ATTEMPT_TIMEOUT`
  (this scale needs 4-6 s; a 5 s window nearly always "failed").
- Idle exit → `PAUSE` (flat 20 s, streak-neutral). Failure → escalating
  backoff. Conflating the two opened ever-longer coverage gaps.

## Outcomes

`SessionOutcome` → `MissReason` (`NO_MEASUREMENT`, `IDLE`, `DROPPED`,
`ADAPTER_OFF`, …) → `CaptureAttemptLog`, which persists the last outcome
across processes so "nothing happened" has an answer.
