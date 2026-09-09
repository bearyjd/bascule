<!-- Generated: 2026-09-08 | Files scanned: 86 main sources | Token estimate: ~700 -->

# Architecture

Single-module Android app. Bridges a Beurer BF720 BLE scale to a VitalForge
server. No DI framework — `BasculeApplication` is a lazy service locator.

## Pipeline

```
BF720 advertises
  ├─ ScaleScanner (LOW_POWER PendingIntent scan)  ──┐
  └─ BridgeForegroundService (BALANCED callback)  ──┤
                                                    ▼
                                    ScanEnqueueCooldown.claim(address)
                                                    ▼
                                          ScaleSessionWorker
                                                    ▼
                     GattSession ── ConnectLadder ── MeasurementPhase
                                                    ▼
                              BeurerDecoder → MeasurementCorrelator
                                                    ▼
                            ReadingIngestor → ReadingMapper → Room
                                                    ▼
                        DeliveryScheduler → DeliveryWorker → DeliveryDrainer
                                                    ▼
                          VitalForgeHttpClient → https://host/p/{slug}
```

## Boundaries

| Package | Owns |
|---|---|
| `ble/` | Scanning, session lifecycle, GATT transport, frame decoding |
| `data/` | Room persistence, config, encrypted profile/credential stores |
| `delivery/` | Queueing, dedup, retry/backoff, replay eligibility |
| `network/` | HTTP client, payload shaping, response classification |
| `service/` | Foreground service, boot + adapter receivers, cooldown |
| `ui/` | Compose screens and ViewModels |
| `diagnostics/` | Capture-attempt log, attention notifications, counters |

## Two facts that shape everything

**The scale does not store and forward.** It indicates a measurement only
live, to a client already connected, consented and subscribed. Sessions
therefore listen for minutes (`SessionBudget`), not seconds. See
`docs/prp/05-retrospective.md` §1.

**Scan registrations die with the Bluetooth stack.** Reboot is handled by
`BootReceiver`; an adapter cycle by `AdapterStateReceiver`. Without both,
capture stops silently while every diagnostic reads healthy.

## Entry points

- `BasculeApplication.kt` — composition root; arms the scan, starts bridging,
  registers the adapter receiver.
- `MainActivity.kt` → `ui/BasculeApp.kt` — Compose navigation host.
- `ScaleSessionWorker` / `DeliveryWorker` — WorkManager entry points.

## Test lane

`./gradlew testDebugUnitTest` (JUnit + Robolectric) is the **only** automated
lane. There is no `androidTest` and no Compose lane; UI and BLE behaviour are
verified on hardware. See `docs/prp/05-retrospective.md` §1 for what that
misses.
