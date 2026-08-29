# PR Review: #1 — Findings: Security

**Reviewed**: 2026-08-25
**Branch**: `vitalforge-connectivity-and-login` → `main`
**Scope**: security dimension only, verified against the working tree at review
time (`git diff main...HEAD`, 95 files / ~9,161 insertions).

**Outcome: no CRITICAL — 3 HIGH / 6 MEDIUM / 6 LOW.** Nothing here is remotely
exploitable without either local device access or the user being socially
engineered into importing a file. The credential-handling core is genuinely
well built (see "What this branch gets right"); the HIGHs are all in the new
settings-backup import path and in unguarded Keystore initialization.

---

## HIGH

### S1. A crafted settings backup can permanently crash the app at launch (unrecoverable without clearing app data)

**Files**: `data/ScaleProfileStore.kt:108-111`, `data/ScaleProfileCodec.kt:57-67`,
`ui/ConfigViewModel.kt:444-445`, `ble/ScaleScanner.kt:29-32`,
`BasculeApplication.kt:88`

A validation gate that `saveProfile` enforces is silently bypassed by
`replaceAll`, which is the path an imported backup file takes:

```kotlin
// ScaleProfileStore.kt:96-99 — enforced
override fun saveProfile(profile: ScaleProfile) {
    require(profile.scaleIndex in 0..255 && profile.consentCode in 0..0xFFFF)
    persist(ScaleProfileCodec.upsertEnforcingSingleActive(mutableProfiles.value, profile))
}

// ScaleProfileStore.kt:108-111 — NOT enforced
override fun replaceAll(profiles: List<ScaleProfile>) {
    require(profiles.count { it.active } <= 1)   // only invariant checked
    persist(profiles.distinctBy { it.id })
}
```

`ScaleProfileCodec.decodeOne` (`:57-67`) validates neither the index/code
ranges nor the `deviceAddress` format — it reads `obj.getValue("address")
.jsonPrimitive.content` verbatim. Contrast `ConfigViewModel.linkExistingScale`
(`:384`, `:469`), which enforces a `(?:[0-9A-F]{2}:){5}[0-9A-F]{2}` regex on
the *same field* when the user types it by hand. The trust boundary is
inverted: hand-typed input is validated, file input is not.

`importSettings` then feeds decoded profiles straight into the unvalidated
path (`ConfigViewModel.kt:444-445`):

```kotlin
if (imported.profiles.isNotEmpty() && scaleProfileStore != null) {
    scaleProfileStore.replaceAll(imported.profiles)
}
```

**The crash chain.** `ScaleScanner.arm()` builds the scan filter *outside* its
`runCatching`:

```kotlin
// ScaleScanner.kt:29-32
val filter = ScanFilter.Builder().setDeviceAddress(profile.deviceAddress)   // throws here
    .setServiceUuid(ParcelUuid(SigWeightProfile.WEIGHT_SCALE_SERVICE)).build()
val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build()
return runCatching { scanner?.startScan(listOf(filter), settings, pendingIntent) == 0 }.getOrDefault(false)
```

`ScanFilter.Builder.setDeviceAddress()` throws `IllegalArgumentException` on a
malformed address. The `runCatching` on the next line never sees it. `arm()`
is called from `BasculeApplication.onCreate` (`:88`) inside
`applicationScope.launch { ... }` — an uncaught throwable in that coroutine
takes down the process.

Both gates that reach this line are attacker-controlled from the same file:
`automatic_capture_enabled` (`ConfigViewModel.kt:442`) and `active` on the
malformed profile (`ScaleProfileCodec`'s only constraint is `count{active} <= 1`).

**`BridgeForegroundService.kt:46-49` has the identical scoping bug** — filter
built outside, `runCatching` only around `startScan`.

**Exploit scenario**: attacker sends the victim a "Bascule settings backup"
(support-forum post, "here's my working config") with the passphrase, containing
one profile with `"address": "not-an-address"`, `"active": true`, and
`automatic_capture_enabled: true`. On import the profile persists. The app
crashes on the next launch, and on every launch after — `onCreate` runs before
any UI, so the user cannot reach the settings screen to undo it. Recovery
requires clearing app data, which destroys all stored readings.

**Fix**: (a) move the `ScanFilter` construction inside the `runCatching` in both
`ScaleScanner.kt:29-32` and `BridgeForegroundService.kt:46-49`; (b) apply
`saveProfile`'s bounds `require` inside `replaceAll` (or better, inside
`persist`, so every write path is covered); (c) validate `deviceAddress`
against the existing `BLUETOOTH_ADDRESS` regex in `ScaleProfileCodec.decodeOne`
— the regex already exists at `ConfigViewModel.kt:469` and should be hoisted
somewhere both can use it.

---

### S2. Importing a settings backup silently repoints the server and immediately flushes the reading backlog to it

**File**: `ui/ConfigViewModel.kt:431-466`

`importSettings` applies a decrypted file's contents with no confirmation step
and no display of what is being applied:

```kotlin
configStore.saveBaseUrl(imported.baseUrl)          // :438
...
authTokenStore.clear(); sessionCookieStore.clear() // :452-453
when (imported.credentialType) {                    // :454-458
    BackupCredentialType.TOKEN   -> authTokenStore.save(requireNotNull(imported.credentialValue))
    BackupCredentialType.SESSION -> sessionCookieStore.save(requireNotNull(imported.credentialValue))
}
...
if (imported.credentialType != BackupCredentialType.NONE) unblockAuthRowsAndDrain()  // :464
```

The only check on the incoming URL is `validateBaseUrl` (`:434`), which
enforces `https` and a non-blank host (`:484-485`) — it does not constrain the
host to anything the user has previously trusted. So a backup file sets both
"which server" and "which credential", and the two are consistent with each
other, meaning the swap produces no auth errors the user would notice.

Line 464 is what makes this immediate rather than theoretical:
`unblockAuthRowsAndDrain()` flips every `BLOCKED_AUTH` row back to `PENDING`
and triggers a drain right away (`:314-317`). Every pending *and* previously
auth-blocked reading in the local database — weight, and under the V2 contract
body fat, body water, muscle %, bone mass, BMI, BMR, AMR (`network/VitalForgeApi.kt:8-18`)
— is POSTed to the attacker's host within seconds of the import, and every
future weigh-in follows.

**Exploit scenario**: same delivery as S1 (attacker-supplied backup + passphrase,
framed as a config to copy). Post-import the app looks entirely healthy: the
base URL field shows the new host but nothing draws attention to the change,
"Test connection" succeeds against the attacker's server, and the user's
complete body-composition history has already been exfiltrated. This is
interaction-gated — the attacker must get the victim to choose the file *and*
enter the passphrase — but there is no security decision presented at any point
in that flow.

**Fix**: before applying, show a confirmation dialog naming the base URL being
imported and whether a credential is included, and require explicit
confirmation. At minimum, warn when `imported.baseUrl` differs from the
currently-saved one. Consider not carrying the credential in the backup at all
(export config, re-authenticate on the new device) — that removes both the
plaintext-credential-at-rest concern in S5 and the silent-credential-swap half
of this one.

---

### S3. `EncryptedSharedPreferences` initialization has no failure path — a Keystore fault is an unrecoverable launch crash and total credential loss

**File**: `network/AuthTokenStore.kt:53-63`, used by `AuthTokenStore.kt:31`,
`SessionCookieStore.kt:25`, `EncryptedConsentStore.kt:18`, `ScaleProfileStore.kt:42`

```kotlin
internal fun encryptedPreferences(context: Context, fileName: String): SharedPreferences {
    val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    return EncryptedSharedPreferences.create(...)
}
```

No `try`/`catch`, no recreate-on-failure. Both `MasterKey.Builder.build()` and
`EncryptedSharedPreferences.create()` throw (`GeneralSecurityException`,
`IOException`, and in practice `KeyStoreException` /
`InvalidProtocolBufferException`) when the Keystore entry backing the master key
is invalidated or the encrypted prefs file is corrupt. Every call site is a `by
lazy` in `BasculeApplication` (`:52-55`), and `onCreate` (`:87`) dereferences
`scaleProfileStore` inside `applicationScope.launch` — so the throw lands in an
uncaught coroutine at process start, on every start, with no way for the user to
reach a reset control.

**On the deprecation specifically** (which the brief asked to assess rather than
restate): `androidx.security:security-crypto` 1.1.0 is a stable release and its
crypto is sound — AES256-SIV for keys, AES256-GCM for values, master key in the
hardware-backed Keystore. There is no *confidentiality* weakness here, and no
known exploit. The deprecation matters for two concrete reasons, neither of them
"the encryption is broken": the library is unmaintained, so the Keystore-fault
failure modes above will never be fixed upstream; and it will eventually stop
compiling against a future compileSdk, forcing a migration that has to carry
existing users' stored secrets across. **The finding is the missing recovery
path, not the deprecation** — the deprecation is why that path will not arrive
on its own.

Note that `allowBackup="false"` plus `res/xml/data_extraction_rules.xml`
(excludes `sharedpref`, `database`, `file` from both cloud-backup and
device-transfer) correctly rules out the most common trigger, restore-onto-a-
new-device. Remaining triggers: Keystore corruption, some OEM behaviour on
screen-lock removal, and app-data migration edge cases.

**Fix**: wrap `encryptedPreferences()` in a `runCatching` that, on failure,
deletes the prefs file and the master key alias and recreates. Losing the token
and forcing re-login/re-registration is a bad day; an app that cannot start is a
worse one. Surface it in the UI as "credentials had to be reset" rather than
failing silently.

---

## MEDIUM

### S4. `BridgeForegroundService` calls `startForeground` before checking Bluetooth permission — `ScaleSessionWorker` gets the same sequence right

**File**: `service/BridgeForegroundService.kt:28-44` vs
`ble/session/ScaleSessionWorker.kt:22-26`

```kotlin
// BridgeForegroundService — startForeground FIRST
override fun onCreate() {
    super.onCreate()
    createChannel()
    startForeground(NOTIFICATION_ID, ...)   // :31, foregroundServiceType=connectedDevice
    startActiveScan()                        // :37
}
private fun startActiveScan() {
    if (Build.VERSION.SDK_INT >= 31 &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != GRANTED) return  // :42-44
```

```kotlin
// ScaleSessionWorker — permission check FIRST, then setForeground
if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(BLUETOOTH_CONNECT) != GRANTED) return Result.failure()  // :22-25
setForeground(foregroundInfo())  // :26
```

On API 34+, `startForeground` with `FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE`
requires the app to actually hold a Bluetooth runtime permission; without it the
call throws `SecurityException`. The check that would have prevented this runs
six lines too late.

The crash reaches the user via `BasculeApplication.onCreate:89-99` (always-on
bridging enabled) — and note that the `runCatching` there wraps only
`startForegroundService`, which returns before the service's `onCreate` runs, so
it cannot catch this. `ScaleViewModel.factory`'s `onBridgeChange`
(`ui/ScaleViewModel.kt:98-101`) calls `startForegroundService` with no
`runCatching` at all.

**Failure scenario**: user enables always-on bridging, later revokes Bluetooth
permission from system settings (or is on a device where it was never granted).
App crashes on next launch, and again whenever the toggle is flipped.

**Fix**: move the permission check to the top of `onCreate` and `stopSelf()` if
it fails, mirroring the worker. Wrap `onBridgeChange`'s `startForegroundService`
in `runCatching`.

### S5. Settings backup writes the live VitalForge credential and every consent code to user-chosen storage behind an 8-character passphrase

**Files**: `ui/ConfigViewModel.kt:403-429`, `data/SettingsBackupCodec.kt:41-53`,
`:139`, `ui/ConfigScreen.kt:650-658`, `:752-759`

The export payload includes the live bearer token or session cookie
(`ConfigViewModel.kt:406-420`), the paired device address, and every scale
profile's consent code (`SettingsBackupCodec.kt:84-90`). It is written to a
user-chosen SAF destination (`ConfigScreen.kt:650`, `CreateDocument
("application/octet-stream")`) — Downloads, a cloud-synced folder, anywhere.

**The crypto itself is good and should not be changed**: PBKDF2-HMAC-SHA256 at
210,000 iterations (`:148`, matching current OWASP guidance), AES-256-GCM
(`:72-73`), 16-byte random salt and 12-byte random IV from `SecureRandom`
(`:43-44`), magic bytes bound as AAD (`:74`) so the header is authenticated,
and `spec.clearPassword()` (`:71`). This is a better-than-typical construction.

The weakness is the key material, not the algorithm: `MIN_PASSPHRASE_LENGTH = 8`
(`:139`) with no composition or strength requirement, and the UI enforces
exactly that floor and nothing more (`ConfigScreen.kt:752`, `:759` — "Use at
least 8 characters"). An 8-character lowercase-alphabetic passphrase is ~2^38
candidates; 210k PBKDF2 iterations make that expensive but not out of reach for
an attacker who obtains the file offline and wants a health-data server
credential. The file is at rest, indefinitely, wherever the user put it.

**Fix**: raise the minimum meaningfully (12+), add a strength indicator, and
warn on the export dialog that the file contains a live credential and should
not be placed in synced storage. The stronger option is to stop exporting the
credential at all — see S2's fix.

### S6. `ScaleProfile` and `ScaleCredential` are `data class`es whose generated `toString()` prints the consent code, contradicting the ground rule the rest of the module enforces

**Files**: `data/ScaleProfileStore.kt:12-24`, `ble/session/ConsentStore.kt:11`,
`ui/ScaleViewModel.kt:20-28`

The codebase states the rule explicitly — `ConsentStore.kt:5-9`: *"This is
credential material in the same sense the VitalForge token is … never a log
line"* — and every store implements it: `AuthTokenStore.kt:45`,
`SessionCookieStore.kt:39`, `EncryptedConsentStore.kt:45`,
`ScaleProfileStore.kt:123` all override `toString()` to omit secrets. The
authors were deliberately guarding this vector.

The two data classes that actually *carry* the secret were missed.
`ScaleProfile.toString()` renders `consentCode=41207`, and it is reachable
through UI state: `ScaleUiState` (`ScaleViewModel.kt:20-28`) is a data class
holding `List<ScaleProfile>`, so `ScaleUiState.toString()` transitively prints
every stored consent code.

**Honest scoping: I found no live sink.** There is no logging anywhere in
`app/src/main` (verified: zero `Log.`/`println`/`printStackTrace`), no crash
reporting SDK in the dependency set, and the `require(...)` calls at
`ScaleProfileStore.kt:97`/`:104`/`:109` are bare — they emit "Failed
requirement." without the object, so that path does not leak either. This is a
latent hazard and a consistency gap, not an active leak. It is worth fixing now
precisely because the fix is two lines and the next person to add a debug log or
a crash reporter will not know the rule exists.

**Fix**: override `toString()` on both `ScaleProfile` and `ScaleCredential` to
omit `consentCode`, same as the four stores already do.

### S7. No `network_security_config.xml` — the TLS posture is correct today only by accident of `targetSdk`

**Files**: `AndroidManifest.xml:37-44` (no `networkSecurityConfig` attribute),
`gradle/libs.versions.toml` (`targetSdk = "37"`)

**Current actual risk is low**, and the brief asked for the real assessment
rather than the checklist answer:

- Cleartext is blocked, because `targetSdk >= 28` defaults
  `cleartextTrafficPermitted` to false. `ConfigViewModel.validateBaseUrl:484`
  additionally rejects any non-`https` scheme at input time, and the comment
  there (`:481-483`) shows the author knew about the manifest gap.
- User-installed CAs are not trusted, because apps targeting API 24+ exclude the
  user trust store by default. So the standard "MITM proxy / malicious enterprise
  cert" scenario does not apply.

What is missing is that none of this is *stated*. The posture is inherited from
two SDK defaults, and a future `targetSdk` change or a debug-variant override
would silently relax it with nothing in the repo to catch it. There is also no
`CertificatePinner`, which for a self-hosted single-host backend is a
cheaper-than-usual win.

**Fix**: add `res/xml/network_security_config.xml` with an explicit
`<base-config cleartextTrafficPermitted="false" />` and reference it from the
manifest, so the guarantee is declared rather than inferred. If the VitalForge
host is stable, add an OkHttp `CertificatePinner` in
`VitalForgeHttpClient.defaultClient()` (`:246-253`).

### S8. Every body-composition value from the scale reaches the database and VitalForge with no plausibility check — including the SIG "value not available" sentinel

**Files**: `ble/decoders/BodyCompositionMeasurement.kt:57-97`,
`ble/decoders/WeightMeasurement.kt:48-86`,
`ble/decoders/MeasurementCorrelator.kt:118-141`,
`data/ReadingIngestor.kt:22-26`, `data/ReadingMapper.kt:31-58`

The BLE frame decoders validate **length only**. `BodyCompositionMeasurementParser
.parse()` bounds-checks via a `FrameReader` underrun flag and returns on that
alone (`BodyCompositionMeasurement.kt:96`: `return if (reader.underrun) null
else measurement`); every field is an unbounded scale multiply:

```kotlin
// BodyCompositionMeasurement.kt:78, :82
bodyFatPct = (bodyFat ?: 0) * SigWeightProfile.PERCENT_PER_LSB,
musclePct  = reader.field(flags, FLAG_MUSCLE_PERCENTAGE) { it * SigWeightProfile.PERCENT_PER_LSB },
```

A u16 percentage field therefore decodes to anything up to `0xFFFF * 0.1 =
6553.5` %. `WeightMeasurementParser.parse()` is the same (`WeightMeasurement
.kt:53-58`, `:76`) — no check on `weightKg`, `bmi`, or `heightM`.

Nothing downstream closes the gap. `MeasurementCorrelator.merge`
(`:118-141`) is a pure field copy. `ReadingIngestor.kt:22-26` is **the only
range gate in the entire pipeline** and it covers `weightKg` alone:

```kotlin
if (!measurement.weightKg.isFinite() || measurement.weightKg !in 20.0..300.0) {
    return IngestResult.Rejected("implausible weight")
}
```

`ReadingMapper.map` (`:31-58`) copies and converts without validating or
clamping. So `bodyFatPct`, `bodyWaterPct`, `musclePct`, `bmi`, `bmr`, `amr`,
`impedanceOhms`, and `softLeanMassKg` all reach `ReadingEntity` — and, under the
V2 contract (`network/VitalForgeApi.kt:8-18`), get POSTed to VitalForge —
completely unvalidated.

**Two concrete failure paths, and the mundane one is the likelier:**

1. **`0xFFFF` is the Bluetooth SIG "value not available" sentinel and it is not
   filtered.** It decodes as a real measurement. If the BF720 emits the sentinel
   for a field it could not measure, rather than clearing that field's flag bit
   and omitting it, then a poor-contact weigh-in writes 6553.5 % body fat into
   the user's health history and ships it upstream — no attacker involved.
   *Which of the two the BF720 actually does is not verified here* — worth
   confirming against `docs/prp/03-hardware-validation.md`, which is where
   real-device frame captures are recorded. The parser gap itself is verified
   either way; only the likelihood of it firing in normal use depends on this.
2. **A hostile device can inject arbitrary values.** Reaching the notification
   path requires clearing the address-bound scan filter (`ScaleScanner.kt:29`)
   and the UDS consent handshake, so it is not open to any nearby radio — but
   BLE MAC addresses are trivially spoofable, and a user re-registering against
   a rogue device (`AndroidScaleRegistrar.findScale` filters on the service UUID
   only, `ScaleRegistrar.kt:134-136`) hands over the consent code voluntarily.

Also note `BodyCompositionMeasurement.kt:78` coerces an *absent* body-fat field
to `0.0` rather than leaving it null, so "not measured" and "measured as zero"
become indistinguishable downstream.

**Fix**: filter the `0xFFFF` sentinel in the parser (this one is worth doing
regardless of the security framing), and extend `ReadingIngestor`'s plausibility
gate past `weightKg` — percentages to `0.0..100.0`, BMI/BMR/AMR to sane ranges,
all with an `isFinite()` check. Reject or null the field rather than the whole
reading, so one bad field does not discard a valid weight.

### S9. Migrated consent codes are left behind in the legacy encrypted store

**Files**: `data/ScaleProfileStore.kt:48-64`, `BasculeApplication.kt:54-56`, `:87`

`EncryptedScaleProfileStore.credentialFor` migrates a legacy credential into the
new profile registry (`:51-62`) but never calls `legacy.clear(deviceAddress)`.
The consent code therefore exists in two encrypted preference files —
`bascule_scale_consent` and `bascule_scale_profiles` — indefinitely. Both are
encrypted, so this is a duplicate-copy / minimized-footprint concern rather than
a disclosure: it widens the blast radius of any future decryption weakness and
leaves a stale copy that `clear()` on the new store does eventually cover
(`:88-91` does call `legacy?.clear`), but only on explicit deletion, never after
migration.

**Fix**: clear the legacy entry once the migrated profile has been persisted at
`:62`.

---

## LOW

### S10. `BootReceiver` is exported and acts on any intent, unlike its sibling receiver

**Files**: `service/BootReceiver.kt:26-31`, `AndroidManifest.xml:74-80`

```kotlin
override fun onReceive(context: Context, intent: Intent) {
    val pending = goAsync()
    CoroutineScope(Dispatchers.IO).launch {
        try { arm(context) } finally { pending.finish() }
    }
}
```

No `intent.action` check. `ScanBroadcastReceiver.kt:23` — added in this same
branch — does exactly the right thing: `if (intent.action != ScaleScanner
.ACTION_SCAN) return`. The inconsistency is the point.

Any installed app can `sendBroadcast(Intent().setComponent(ComponentName
("com.ventouxlabs.bascule", "com.ventouxlabs.bascule.service.BootReceiver")))`
and force the re-arm path to run.

**Impact is genuinely small**, which is why this is LOW rather than MEDIUM:
`ScaleScanner.arm()` gates on `automaticCaptureEnabled` (`:27`) and a non-null
`activeProfile` (`:28`) before doing anything, so a spurious trigger at worst
re-registers an already-registered scan. No state is corrupted and nothing
leaks.

`android:exported="true"` is *required* here — a `BOOT_COMPLETED` receiver
cannot receive the system broadcast otherwise — so the fix is the action check,
not un-exporting:

```kotlin
if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
```

### S11. Base URL is validated with one URL parser and used with a different one

**Files**: `ui/ConfigViewModel.kt:475-487` (`java.net.URI`),
`network/VitalForgeHttpClient.kt:217-218` (OkHttp `toHttpUrlOrNull`)

`validateBaseUrl` parses with `java.net.URI`; `resolve()` string-concatenates
the path onto the base URL and re-parses with OkHttp's `HttpUrl`. Two parsers
with different tolerances validating and then using the same value is a shape
that has produced real bypasses in other codebases.

**I probed for an exploitable divergence and could not construct one** — the
candidates I tried (backslash-in-authority, userinfo-with-`@`, fragment and
query injection) either fail `URI`'s host check first or resolve to the same
host under both parsers. Reporting it as defense-in-depth, not as a bypass.

**Fix**: validate with `toHttpUrlOrNull()` — the same parser that builds the
request — and replace the concatenation in `resolve()` with
`HttpUrl.newBuilder().addPathSegments(...)`. That also fixes the unrelated
correctness wart where a base URL carrying a query string (`https://host?x=1`)
silently swallows the API path.

### S12. `linkExistingScale` accepts consent code 0, which `newConsentCode()` never generates

**Files**: `ui/ConfigViewModel.kt:472` (`MIN_CONSENT_CODE = 0`),
`ble/session/EncryptedConsentStore.kt:43`, `data/ScaleProfileStore.kt:93-94`

`newConsentCode()` returns `random.nextInt(0xFFFF) + 1` — deliberately non-zero,
per its own KDoc ("16-bit, non-zero"). The manual-entry path allows 0. Trivial,
but the ranges should agree; if 0 is a sentinel to the scale, allowing it as a
stored credential is a foot-gun.

### S13. `newConsentCode()` constructs a fresh `SecureRandom` per call

**File**: `data/ScaleProfileStore.kt:93-94`

```kotlin
override fun newConsentCode(): Int = legacy?.newConsentCode()
    ?: java.security.SecureRandom().nextInt(0xFFFF) + 1
```

Not a weakness — `SecureRandom` self-seeds correctly on Android — but
`EncryptedConsentStore.kt:19` holds one as a field and this fallback should do
the same. Per-call construction is a pattern worth not copying.

### S14. Credential writes use `apply()` rather than `commit()`

**Files**: `network/AuthTokenStore.kt:38`, `:42`,
`network/SessionCookieStore.kt:32`, `:36`

`apply()` is asynchronous. A process death immediately after a successful login
can lose the just-obtained session cookie, leaving the user apparently logged in
(the UI already advanced) but unauthenticated on the next request. Note that
`EncryptedScaleProfileStore.persist` (`ScaleProfileStore.kt:114`) already uses
`commit()` for exactly this reason — the credential stores should match.

Availability, not confidentiality.

### S15. `importSettings` is not atomic

**File**: `ui/ConfigViewModel.kt:431-466`

Config fields are written one at a time (`:438-443`), then credentials are
cleared (`:452-453`) and re-saved (`:454-458`). A throw or process death between
452 and 458 leaves the user with the imported server URL and no credential. The
`require` calls that could throw are mostly pre-validated in
`SettingsBackupCodec.decode`, so this is narrow — but the ordering (clear before
save) is backwards regardless. Save the new credential first, then clear the
other store.

---

## What this branch gets right

Worth recording, both because it is unusual and because a reviewer skimming only
the findings above would get the wrong impression of the code:

- **Redirects are disabled twice, deliberately.** `VitalForgeHttpClient.kt:45-50`
  re-applies `followRedirects(false)`/`followSslRedirects(false)` via
  `newBuilder()` on top of `defaultClient()`'s own settings (`:250-251`), so an
  injected `OkHttpClient` cannot re-enable them. The comment (`:46-47`) names the
  exact risk: a redirect would forward the bearer token to another host. This is
  the single most commonly missed token-leak vector and it is closed properly.
- **No credential ever reaches an error message.** `execute()` (`:191-205`)
  collapses every throwable to `"network error"` or `"unreadable response"` —
  the URL, the request, and the `Authorization` header never appear. Surfaced
  failure strings (`ConnectionTestUiState.Failure`) carry only those constants or
  `"server returned $code"`.
- **Zero logging in `app/src/main`.** No `Log.`, `println`, `printStackTrace`, or
  logging interceptor anywhere. The "never a log line" rule is actually held.
- **The token is never held in a field.** `tokenProvider: () -> String?`
  (`:37`, `:42`) reads from the store at request-build time, with the reasoning
  documented at `:30-33` (heap dumps, `toString()`). `AuthTokenStore` deliberately
  exposes `isSet()` so the UI never needs the value.
- **Response handling is hardened against a hostile server.** 64 KiB body cap
  peeked one byte past the limit without buffering the whole body (`:211-215`),
  status code treated as authoritative over body size (`:88-96`), and lenient
  JSON parsing that `mapNotNull`s malformed entries rather than throwing
  (`:177-182`).
- **The permission matrix is tight and correctly SDK-branched.**
  `AndroidManifest.xml:10-33` caps `BLUETOOTH`/`BLUETOOTH_ADMIN`/
  `ACCESS_FINE_LOCATION`/`ACCESS_BACKGROUND_LOCATION` at `maxSdkVersion="30"`
  and declares `BLUETOOTH_SCAN` with `usesPermissionFlags="neverForLocation"`.
  Location is not requested on any API level that does not require it. The
  in-app rationale text (`ConfigScreen.kt:174-176`) explains *why* location is
  asked for, which is more than most apps manage.
- **Backup and device-transfer are fully excluded.** `allowBackup="false"` plus
  `data_extraction_rules.xml` excluding `database`, `sharedpref`, and `file`
  from both `<cloud-backup>` and `<device-transfer>`.
- **Exported surface is minimal.** Only `MainActivity` and `BootReceiver` are
  exported; `BridgeForegroundService` and `ScanBroadcastReceiver` are explicitly
  `exported="false"`.
- **`ScaleSessionWorker` cross-checks the scan-supplied address.**
  `ScaleSessionWorker.kt:31` verifies the address from the intent extra against
  `activeProfile.deviceAddress` before connecting, so untrusted-by-construction
  intent data cannot steer the GATT connection. (`ScaleScanner`'s
  `PendingIntent.FLAG_MUTABLE` at `:22` is required by the BLE scan API and the
  wrapped intent is explicit, so it is not a finding.)
- **BLE frame decoding is length-checked before every read.**
  `BeurerDecoder.kt:179`, `:189`, `:203`, `:212`, `:215` all bounds-check before
  indexing, and unknown characteristics/opcodes fall through to
  `DecodeEvent.Ignored` (`:175`, `:198`) rather than throwing.
- **`ReadingIngestor` range-validates the scale's weight.**
  `ReadingIngestor.kt:22` rejects non-finite and out-of-range (`20.0..300.0`)
  values from the device. This is the right instinct — it just stops at
  `weightKg`, which is finding **S8**.
- **No hardcoded secrets.** Grepped `app/src` (main and test) for assigned
  key/secret/password/token literals — nothing. Test fixtures use obvious
  placeholders.

---

## Suggested fix order

1. **S1** — two-line `runCatching` scoping fix in `ScaleScanner.kt:29-32` and
   `BridgeForegroundService.kt:46-49`, plus the bounds `require` in
   `replaceAll`. Cheapest fix, worst outcome prevented.
2. **S3** — wrap `encryptedPreferences()` with delete-and-recreate recovery.
3. **S2** — confirmation dialog on import showing the incoming base URL.
4. **S4** — move the permission check above `startForeground`.
5. **S8** — extend the plausibility gate past `weightKg`. The `0xFFFF` sentinel
   half is worth fixing on correctness grounds independent of the security
   framing; check `docs/prp/03-hardware-validation.md` first to see whether it
   fires in normal use.
6. **S6** — two `toString()` overrides.
7. **S5**, **S7**, **S9**, then the LOWs.
