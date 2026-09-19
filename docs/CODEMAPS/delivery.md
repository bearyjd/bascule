<!-- Generated: 2026-09-08 | Files scanned: 17 (delivery/, network/) | Token estimate: ~850 -->

# Delivery

From a `PENDING` row to a row on the VitalForge server.

## Flow

```
ReadingIngestor inserts PENDING
  → DeliveryScheduler.triggerImmediateDrain()  # WorkManager, KEEP (dedupes against a running drain)
  → DeliveryWorker
      → RuntimeApiFactory.create()         # re-reads config every run
      → DeliveryDrainer.drain()
          self-heal: requeue rows stamped under another contract (422 only)
          for each due row: DedupPolicy → VitalForgeHttpClient.submitReading
          → ResponseClassifier → status + backoff
      → FAILED: DeliveryScheduler.scheduleRetryKick   # delivery-retry-kick, REPLACE; soonest waiting row, or the ladder base if a row is due
  DeliveryPeriodicKickWorker                # periodic safety net + retry kick; both only enqueue the one drain
```

`DeliveryWorker` never returns `Result.retry()`. A retrying request sits under
`delivery-drain` in WorkManager's own backoff (30 s doubling to a 5 h cap), and
`triggerImmediateDrain`'s KEEP drops every trigger — a new capture, a saved
token, the periodic kick — for as long as it sits there. Retry pacing is the
per-row §3.4 ladder alone: after a failed drain the worker reads
`ReadingDao.earliestFutureAttemptMillis` (and, when nothing is waiting, whether
anything is still due, which kicks at the ladder's 30 s base — a `Retry-After: 0` leaves
rows due with nothing in the future) and schedules one delayed kick under its
own name, so the drain name never holds a delayed request.

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
404        → TransientFailure   # same argument: only collection POSTs, so never a verdict
408/429/5xx→ TransientFailure   # honours Retry-After, clamped to 1h
400/409/413/422 → PermanentRejection
other 4xx  → PermanentRejection
```

**Formerly a sharp edge, closed 2026-09-18:** 404 used to be permanent, so a
wrong base URL or slug cost the reading on its first attempt (it happened on
hardware, 2026-09-07). The "legitimately permanent for other causes" reasoning
did not survive contact with what this client actually sends — only
collection POSTs, where a 404 can only mean the route is missing. Now
transient, on the same ladder and 14-day expiry as a redirect. The one
remaining edge is that correcting the base URL does not kick a drain, so
rows wait out their current backoff (≤15 min) or the periodic drain.

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
