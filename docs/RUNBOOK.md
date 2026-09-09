# Runbook

Operating a single-user Android app that talks to one scale and one server.
Deployment is "install an APK on the phone", not a fleet rollout.

## Configuration

<!-- AUTO-GENERATED: derived from app/build.gradle.kts and .github/workflows/ci.yml -->

No `.env`. Release signing is the only external configuration.

**`keystore.properties`** — first readable of `$KEYSTORE_PROPERTIES`,
`~/.config/bascule/keystore.properties`, then repo root. Gitignored.

| Key | Required | Purpose |
|---|---|---|
| `storeFile` | Yes | Path to the `.jks` |
| `storePassword` | Yes | Keystore password |
| `keyAlias` | Yes | `bascule` |
| `keyPassword` | Yes | Key password |

**Repo secrets** (CI materialises a properties file from these):
`BASCULE_KEYSTORE_B64`, `BASCULE_STORE_PASSWORD`, `BASCULE_KEY_PASSWORD`.

Absent any of it, `assembleRelease` produces an **unsigned** APK rather than
failing, so a fork still proves R8 compiles. The tag publish step refuses to
release an unsigned APK.

<!-- END AUTO-GENERATED -->

> **Back the keystore up somewhere durable.** Lose it and the app can never be
> updated in place again — a differently-signed APK cannot replace an
> installed one.

## Release

1. Land everything on `main`; confirm CI is green.
2. Update `versionCode` / `versionName` in `app/build.gradle.kts` if bumping.
3. Add the release's section to `CHANGELOG.md`.
4. Tag and push:
   ```
   git tag -a v0.1.0 -m "v0.1.0"
   git push origin v0.1.0
   ```
5. CI takes over: builds, signs, **verifies the signature**, and publishes a
   GitHub Release with the APK attached and auto-generated notes.

If the publish step fails, the failure mode is a red workflow and no release
— not a bad release. The most likely cause is missing or renamed
`BASCULE_KEYSTORE_*` secrets; the step says so explicitly.

## Install on the phone

```
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
```

> **Never uninstall to test something.** `EncryptedPreferences` builds its
> MasterKey in the AndroidKeyStore, so the key dies with the install: a pulled
> copy of the consent file becomes permanently undecryptable, the scale
> registration cannot be recreated without the physical scale, and
> re-registering burns one of its 8 slots. `install -r` fails safely on a
> signature mismatch rather than wiping.

Debug and release builds are signed differently, so moving between them needs
export → uninstall → install → import via the app's settings backup.

## Health checks

**Is the installed build current?**
```
adb shell dumpsys package com.ventouxlabs.bascule | grep lastUpdateTime
```
Compare against `git log`. The phone's APK silently lags `main`, and early
hypotheses have more than once been about code that was not running.

**Did capture work?** (debug builds only — `run-as` is unavailable on release)
```
adb shell run-as com.ventouxlabs.bascule cat shared_prefs/capture_attempts.xml
adb shell run-as com.ventouxlabs.bascule cat shared_prefs/scan_enqueue_cooldown.xml
adb exec-out run-as com.ventouxlabs.bascule tar -czf - databases > db.tgz
adb logcat -d -v time -s ScaleSessionWorker:*
```

`IDLE` means connected, subscribed, waited out the listen window with nobody
on the scale — the success path for an idle scale, not a failure.

**Did delivery work?** Query the server's `fitness.db` over SSH rather than
the HTTP API; the read endpoints need a credential that lives in the phone's
encrypted store.

## Common issues

| Symptom | Cause | Fix |
|---|---|---|
| Capture silently stopped, everything looks healthy | Bluetooth adapter cycled; the scan registration died with the stack | `AdapterStateReceiver` handles this from v0.1.0. Older builds: reopen the app or toggle a capture switch |
| Every delivery 404s | Base URL missing the person path | Set it to `https://host/p/<slug>`. "Test connection" names this explicitly |
| Every delivery 422s | Server rejects an unknown field (`extra="forbid"` rejects the whole payload) | Server is behind the client's contract; update the server or drop to v1 |
| Reading stuck `FAILED_PERMANENT` | 404/422 classified permanent | Switching contract version requeues rows stamped under the *other* contract. A row stamped under the current one never self-heals |
| Nothing captured for hours | Phone was away from the scale | Expected. Sessions resume on the next advertisement |
| Weigh-in missed while standing on it | No session was listening at that moment; the scale indicates only live | Tap "Weigh now", wait ~30 s, step on again |
| `adb` shows no device mid-session | This phone drops off USB on long sessions | Re-check `adb devices` before reading an empty result as "no data"; keep captures short |

## Rollback

- **Bad release** — `gh release delete vX.Y.Z` and delete the tag; installed
  copies are unaffected until someone installs.
- **Bad build on the phone** — `adb install -r` the previous APK. Same
  signing key, so data survives. Never uninstall.
- **Server-side** — `docker compose -f docker-compose.prod.yml` on the
  VitalForge host; the data volume is separate from the containers, and the
  DB should be backed up before any migration.

## Escalation

Single-operator project; there is no on-call. The durable record is
`HANDOFF.md` (session history and open threads) and `docs/prp/` (design,
decisions, hardware validation, retrospective). Read
`docs/prp/05-retrospective.md` before trusting `00-design.md` or `01-plan.md`
— several of their premises are known-wrong.
