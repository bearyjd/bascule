<!-- Generated: 2026-09-08 | Files scanned: 17 (delivery/, network/) | Token estimate: ~850 -->

# Delivery

From a `PENDING` row to a row on the VitalForge server.

## Flow

```
ReadingIngestor inserts PENDING
  → DeliveryScheduler.trigger()            # WorkManager, APPEND_OR_REPLACE
  → DeliveryWorker
      → RuntimeApiFactory.create()         # re-reads config every run
      → DeliveryDrainer.drain()
          self-heal: requeue rows stamped under another contract (422 only)
          for each due row: DedupPolicy → VitalForgeHttpClient.submitReading
          → ResponseClassifier → status + backoff
  DeliveryPeriodicKickWorker                # periodic safety net
```

## HTTP surface

| Path | Built from | Notes |
|---|---|---|
| `POST {baseUrl}/api/weight` | `WEIGHT_PATH` | base URL carries the person prefix |
| `GET {baseUrl}/api/weight/recent` | `RECENT_PATH` | dedup + connection test |
| `POST {origin}/auth/login` | `LOGIN_PATH` | resolved at **origin**, not baseUrl |

VitalForge serves weight routes under `/p/{slug}/` and auth at the root.
`resolve()` appends to `baseUrl`; `resolveAtOrigin()` discards its path. So
the Base URL setting must be `https://host/p/<slug>`; a bare host reproduces
the pre-prefix paths exactly.

## Contracts

`ContractVersion` → `ReadingPayloadShaper`:

- `V1_WEIGHT_ONLY` → `V1Shaper` — exactly `{weight, unit}`.
- `V2_BODY_COMP` → `V2Shaper` — adds `client_id`, `captured_at` (ISO-8601),
  body composition, `bmi`/`bmr`/`amr`. Server uses `extra="forbid"`, so an
  unknown field rejects the **whole** payload.

## Classification (ResponseClassifier)

```
2xx        → Accepted(deliveredFields)
401/403    → AuthRejected
3xx        → TransientFailure   # a moved endpoint is config, not a verdict
408/429/5xx→ TransientFailure   # honours Retry-After, clamped to 1h
400/404/409/413/422 → PermanentRejection
other 4xx  → PermanentRejection
```

**Known sharp edge:** 404 is permanent, so a wrong base URL or slug costs the
reading on its first attempt rather than deferring it. `submitReading`'s KDoc
argues against exactly this for an invalid URL. Not changed — 404 is
legitimately permanent for other causes.

## Recovery

`ReadingDao.failedPermanentlyUnderOtherContract(wire)` matches
`FAILED_PERMANENT` rows with `permanentRejectionHttpCode = 422` stamped under
a **different** contract. `saveContractVersion` requeues them and drains;
`requeueForReplay` clears status, attempt count, error and both provenance
columns.

A row stamped under the *current* contract never matches — recovery fires on a
contract switch, not on a server fix.

## Replay (WP-22, not wired)

`ReplayEligibility` + `ReplayMigrationWorker` exist and nothing schedules
them. Blockers and the residual capture-time gap:
`docs/prp/05-retrospective.md` §3.

## Key files

`delivery/DeliveryDrainer.kt` (drain loop, self-heal) ·
`delivery/DedupPolicy.kt` (remote-duplicate gate) ·
`network/VitalForgeHttpClient.kt` (OkHttp, no redirects, 64 KB body cap) ·
`network/ResponseClassifier.kt` (the table above) ·
`network/RuntimeApiFactory.kt` (per-run config read)
