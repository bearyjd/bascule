# Scale Administration and Hands-Off Measurement Pipeline

## Summary

Implement a complete vertical path from a BF720 advertisement to durable local capture and eventual delivery to `weight.grepon.cc`, plus a top-level **Scale** tab for safe profile administration.

Use one active scale profile with the current VitalForge account. Readings from another slot are stored as `HELD_CONFIRM`; confirming uploads that reading once without changing the active profile. Background capture requires explicit opt-in, uses OS wake scanning by default, and keeps the persistent foreground service as an optional fallback.

## Work packages

1. Checkpoint registration, real GATT transport, login fixes, and encrypted settings import/export together.
2. Add a process-wide BLE operation coordinator, explicit session purposes, an encrypted multi-profile registry, slot-2 migration, and portable-settings v2 with v1 compatibility.
3. Complete measurement subscriptions and correlation, bounded session/reconnect behavior, synchronous ingestion, local deduplication, and `PENDING` versus `HELD_CONFIRM` routing.
4. Add runtime VitalForge API construction and WorkManager delivery of pending rows with remote duplicate checks, auth blocking, per-row retry, expiration, and held-reading decisions.
5. Add opt-in PendingIntent wake scanning, short advertisement handling, expedited unique session work, foreground BLE execution, re-arming triggers, and actionable diagnostics. Keep always-on bridging optional.
6. Add **Scale** as the fourth top-level destination with status, diagnostics, capability probing, safe profile creation/linking/editing/verification/deletion, and no consent-code display or logging.
7. Add protocol, session, repository, ViewModel, Compose, backup migration, Room migration, WorkManager, receiver, race, and process-death tests, then run all JVM and instrumentation gates available locally.

## Public interfaces and data changes

- `ScaleOperationCoordinator` is the only entry point for owning a scale GATT connection.
- `ScaleProfileStore` provides profile enumeration, active-profile flow, credential lookup by address/index, and targeted save/delete operations.
- `ScaleSessionPurpose` identifies existing-user consent, new-user registration, measurement, and administration; only `REGISTER_NEW` permits a Register New User write.
- `ReadingIngestor` returns `Inserted`, `Held`, `Duplicate`, or `Rejected`.
- `DeliveryScheduler` owns immediate and periodic unique work.
- Room schema v2 adds nullable `scaleProfileId`; legacy rows remain valid with `null`.
- Portable settings payload v2 exports all profiles while retaining the encrypted envelope and v1 reader.

## Acceptance criteria

- A screen-off weigh-in with no app UI creates exactly one durable row and reaches VitalForge.
- Swiping the app away does not prevent capture; Android force-stop disables background work until the app is reopened.
- Offline rows remain pending and drain after connectivity returns.
- Expired authentication blocks delivery without deleting data; logging in drains the backlog.
- Duplicate advertisements, frames, and sessions do not create duplicate deliveries.
- Another profile's reading is held and never uploaded without confirmation.
- Registration, administration, and measurement never overlap or silently allocate profiles.
- Reboot restores scanning only after explicit opt-in.
- Existing settings backups and the BF720 slot-2 registration survive migration.
- Physical delete validation never targets slot 2; destructive testing requires a sacrificial profile and explicit confirmation.
- Notifications and logs expose no weight, body composition, credentials, or consent codes.

## Defaults

- One active BF720 profile maps to the current VitalForge login.
- Confirming a held reading uploads it once without switching profiles.
- Automatic capture defaults to off; OS wake scanning is primary and always-on bridging is optional.
- Labels are local metadata. Date of birth, gender, and height are read/written live and are not cached.
- Commits remain local until pushing is explicitly requested.
