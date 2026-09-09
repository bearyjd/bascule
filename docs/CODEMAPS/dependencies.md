<!-- Generated: 2026-09-08 | Files scanned: gradle/libs.versions.toml, app/build.gradle.kts | Token estimate: ~600 -->

# Dependencies

AndroidX + kotlinx only. No DI framework, no mocking framework, no networking
framework beyond OkHttp. Versions are centralised in
`gradle/libs.versions.toml`.

## Platform

| | |
|---|---|
| AGP / Kotlin | 9.3.1 / 2.4.10 |
| compileSdk / targetSdk | 37 |
| minSdk | 26 |
| JDK | 17 |

## Runtime libraries

| Library | Version | Used for |
|---|---|---|
| Compose BOM | 2026.08.00 | UI, Material 3, icons-extended, navigation |
| Room | 2.8.4 | `readings` table, KSP compiler, `exportSchema` |
| WorkManager | 2.11.2 | `ScaleSessionWorker`, `DeliveryWorker`, periodic kick |
| security-crypto | 1.1.0 | `EncryptedSharedPreferences` + AndroidKeyStore MasterKey |
| datastore-preferences | 1.2.1 | `ConfigStore` |
| OkHttp | 5.5.0 | `VitalForgeHttpClient` |
| kotlinx-coroutines | — | Structured concurrency throughout |
| kotlinx-serialization-json | — | Payload shaping, response parsing |

`security-crypto` APIs are deprecated upstream with no drop-in replacement.
Migrating means a key-rotation story for credentials that **cannot be
recreated without the physical scale** — deliberately deferred.

## Test libraries

JUnit 4 · Robolectric · Turbine · `okhttp-mockwebserver` ·
`androidx-work-testing` · `androidx-room-testing`

`androidx-test-junit` / `androidx-test-runner` are declared but there is **no
`app/src/androidTest`**. The instrumented deps and `testInstrumentationRunner`
were removed in Aug 2026 as dead weight implying coverage that never existed;
do not cite instrumented tests as coverage.

Fakes over mocks, by convention. See `ui/fake/`, `ble/fake/`.

## External services

| Service | Role |
|---|---|
| **VitalForge** (`weight.grepon.cc`) | Delivery target. Tailnet-fronted by an openresty proxy; the app is HTTPS-only (`network_security_config.xml`, `cleartextTrafficPermitted=false`) and `validateBaseUrl` rejects non-https at input |
| **Garmin Connect** | Downstream of VitalForge, not touched by this app |
| **Beurer BF720** | The scale. SIG Weight Scale (`0x181D`), Body Composition (`0x181B`), User Data (`0x181C`), Current Time (`0x1805`), Battery (`0x180F`) |

Two proprietary services on the scale, `0x0000FFFF` and `0x0000FF00`, have
never been exercised. They are the only candidate source for a measured AMR
and for stored-measurement fetch — see `docs/prp/05-retrospective.md` §3.

## Build

- Signing config reads `keystore.properties`; a dangling path fails loudly
  rather than silently producing an unsigned APK.
- R8 needs four `-dontwarn` rules for Tink's ErrorProne annotations.
- Release APK ~2.4 MB against ~22.6 MB debug.
- CI publishes a GitHub Release on a `v*` tag and refuses to publish an
  unsigned APK.
