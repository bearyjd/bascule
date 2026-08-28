# PR Review: #1 — Type Safety dimension

**Reviewed**: 2026-08-25
**Branch**: `vitalforge-connectivity-and-login` → `main`
**Scope**: type safety only — one of a 7-way parallel dimension split.
**Companion file**: `.claude/PRPs/reviews/pr-1-review.md` (maintainability).
Finding IDs here are namespaced `TS-*` so they do not collide with that
file's `H1-H8` / `M1-M12` / `L1-L10`.

**Tally**: 0 CRITICAL / 5 HIGH / 5 MEDIUM / 5 LOW.

---

## ⚠️ Two caveats on how to read this

**1. The working tree was being modified while this review ran.** The
`fix-maintainability` teammate is actively editing several of the same files.
Confirmed drift observed mid-review:

- `GattSession.kt` grew by 2 lines (everything after ~line 320 shifted).
- `ScaleProfileCodec.kt` gained a `legacyMigrationProfile` function (+30 lines)
  and three new `require()` calls inside `decodeOne`.
- `ScaleProfileStore.kt` split `credentialFor` into a pure read plus a new
  `migrateLegacyCredential` (maintainability H5's fix), and gained
  `requireWithinBounds`.
- `ConfigViewModel.importSettings` moved ~13 lines and gained a
  `keepsSameHost` guard.

Every line number below was **re-verified with a direct read at the end of
this review** and was accurate at that moment. They will drift again. Each
finding therefore also names the enclosing function, which is the stable
anchor — resolve by function name, not by line number, if the two disagree.

**2. Shell/`git` access was unavailable in this session.** The diff could not
be re-derived; findings were verified against the working tree and
cross-checked against the `git diff main...HEAD --stat` file list captured at
session start. Where provenance ("did this branch introduce it?") could not be
established, it is stated explicitly — see TS-L5.

Deliberately excluded (other dimensions own them): bare `runCatching {}`
swallowing as a *correctness* concern, cleartext/auth/exported-receiver attack
surface, performance, missing tests. Where a finding touches those, it is
stated as a type-system failure only.

---

## Executive summary

The dominant type-safety theme is **`else ->` on sealed types**. The codebase
demonstrably knows better: `GattSession.kt` itself writes exhaustive,
`else`-free `when` over `MeasureStep` (`:469`, `:496`), `SubscriptionOutcome`
(`:384`, `:423`), `HandshakeDirective` (`:343`) and `ConnectPhaseResult`
(`:103`), and `HistoryScreen.kt:178-182` carries a comment explicitly
explaining why the six `ReadingStatus` cases are spelled out rather than
`else`-d. The `else` branches cluster on exactly three types —
`TransportEvent`, `DecodeEvent`, `SessionOutcome` — and **one of them has
already cost something**: TS-H1 documents a live behavioural divergence
between two sibling loops that the `else` allowed to compile.

The secondary theme is **hand-rolled untyped JSON** (`ScaleProfileCodec`,
`SettingsBackupCodec`) in a project that already depends on
kotlinx.serialization, where `@Serializable` on the existing `data class`es
would make the field mapping compile-checked. The two codecs have **opposite
and equally wrong error policies** for the same class of malformed input: one
throws on everything (TS-H3), the other silently swallows (TS-H5) — and both
routes end at code that destroys the user's scale registry.

---

## HIGH

### TS-H1. Sealed `TransportEvent`/`DecodeEvent` handled with `else ->` at 10 sites in `GattSession.kt` — and the two sibling measurement loops have already diverged because of it

`TransportEvent` (`ble/session/GattTransport.kt:44-57`) is a sealed interface
with **8** subtypes; `DecodeEvent` (`ble/session/DecodeEvent.kt:14-41`) is
sealed with **7**. `GattSession.kt` dispatches on them at ten sites, every one
with a catch-all:

| enclosing function | `when` at | subject | `else ->` at |
|---|---|---|---|
| `receiveConnectOutcome` | `:184` | `TransportEvent` | `:192  else -> continue` |
| `receiveDiscoveryOutcome` | `:277` | `TransportEvent` | `:284  else -> continue` |
| `awaitWriteComplete` | `:315` | `TransportEvent` | `:318  else -> continue` |
| `awaitSubscription` | `:400` | `TransportEvent` | `:410  else -> continue` |
| `awaitMeasurement` (first loop) | `:442` | `TransportEvent` | `:461  else -> Unit` |
| `awaitMeasurement` (first loop, inner) | `:448` | `DecodeEvent` | `:457  else -> Unit` |
| `awaitMeasurement` (correlation loop) | `:476` | `TransportEvent` | `:488  else -> Unit` |
| `awaitMeasurement` (correlation loop, inner) | `:481` | `DecodeEvent` | `:486  else -> Unit` |
| `finishEmission` | `:513` | `TransportEvent` | `:521  else -> Unit` |
| `awaitNonWaitDirective` | `:612` | `TransportEvent` | `:624  else -> continue` |

**This is not hypothetical — it has already happened.** The two `DecodeEvent`
loops inside `awaitMeasurement` are near-identical and have silently drifted:

- `:448-457` (first-indication loop) handles `DecodeEvent.SessionComplete`
  explicitly at `:451-456`, calling `decoder.flush()` and returning
  `MeasureStep.Reading` if the flush yields a stable reading.
- `:481-486` (body-composition correlation loop) handles only `Stable` and
  `Malformed`; `SessionComplete` falls into `else -> Unit` at `:486`.

**Failure scenario, current code, no future change required**: a scale that
emits its end-of-transmission marker *during* the correlation window has that
marker discarded. The loop keeps waiting on `events.receive()` until
`SessionBudget.BODY_COMPOSITION_CORRELATION_WINDOW` expires. Only then does
the `?:` at `:493` reach `decoder.flush()` — so the reading is recovered late,
or (if the flush yields nothing) the session reports
`SessionOutcome.Missed(NO_MEASUREMENT)` / `DecodeFailure` for a weigh-in the
scale actually completed. Had `:481` been exhaustive, adding `SessionComplete`
to `DecodeEvent` would have forced the author to make this decision at `:481`
too, in the same edit.

**Forward-looking failure**: a 9th `TransportEvent` (`PhyUpdated`,
`ReadRemoteRssi`, a new failure event) or an 8th `DecodeEvent` compiles clean
at all ten sites and is silently swallowed. The `TransportEvent` sites are the
worse half — they sit in `while (true)` loops whose only exits are an explicit
`return@withTimeoutOrNull` or the enclosing timeout, so a swallowed event does
not produce an error, it produces a **hang until the budget expires**,
surfaced to the user as a timeout/`NO_MEASUREMENT`. Note that
`TransportEvent.AdapterOff` is the designated escape hatch in all eight
`TransportEvent` loops; a new event that *should* have terminated the session
instead extends it to full budget.

**Recommendation**: replace each `else ->` with the explicit remaining
subtypes (the `HistoryScreen.kt:178-187` pattern — grouped cases arrowing to
`Unit`/`continue` with a comment). That is mechanical, changes no behaviour
today except the `:486` bug fix, and converts every future addition from a
silent stall into a compile error at all ten decision points.

---

### TS-H2. `ScaleSessionWorker.kt:46` — `else -> Result.failure()` collapses three of five `SessionOutcome` subtypes, erasing the retry classification the surrounding code is built on

In `ScaleSessionWorker.doWork`:

```
39	        return when (val outcome = app.scaleOperationCoordinator.withScale(ScaleSessionPurpose.MEASUREMENT) { session.run() }) {
40	            is SessionOutcome.Completed -> { … Result.success() }
45	            is SessionOutcome.Missed -> if (outcome.reason == MissReason.ADAPTER_OFF) Result.retry() else Result.success()
46	            else -> Result.failure()
47	        }
```

`SessionOutcome` (`ble/session/SessionOutcome.kt:6-12`) has five subtypes.
`else` absorbs **`Incompatible`, `HandshakeFailed`, `DecodeFailure`**.

**Failure scenario, current code**: `DecodeFailure(malformedCount)` — which
`GattSession.awaitMeasurement` produces from *transient* RF corruption
(malformed frames during an otherwise healthy session) — receives
`Result.failure()`, which in WorkManager is terminal: the work is not retried.
It gets exactly the same treatment as `SessionOutcome.Incompatible`, which
means "this is permanently the wrong device." Line `:45` immediately above
proves the code cares about this distinction (`ADAPTER_OFF` → `retry()`,
everything else → `success()`), and the `else` throws it away for 60% of the
outcome space.

**Forward-looking**: a sixth `SessionOutcome` compiles clean and becomes a
permanent, non-retried failure by default — the worst of the three available
`Result` values, chosen silently.

**Contrast in the same diff**: `ble/ScaleRegistrar.kt:108` dispatches on the
same `SessionOutcome` type with an exhaustive, `else`-free `when`.

---

### TS-H3. `ScaleProfileCodec.decodeOne` throws on every unexpected shape, and its caller turns any throw into a silent, permanent wipe of the user's scale registry

Type-system escapes in `data/ScaleProfileCodec.kt`:

```
33	    fun decode(array: JsonArray): List<ScaleProfile> = array.map { decodeOne(it as JsonObject) }
…
93	        val deviceAddress = obj.getValue("address").jsonPrimitive.content.uppercase()
94	        val scaleIndex = obj.getValue("index").jsonPrimitive.int
95	        val consentCode = obj.getValue("code").jsonPrimitive.int
96	        require(BLUETOOTH_ADDRESS.matches(deviceAddress)) { "Profile has an invalid Bluetooth address" }
97	        require(scaleIndex in SigWeightProfile.SCALE_INDEX_RANGE) { "Profile scale index out of range" }
98	        require(consentCode in SigWeightProfile.CONSENT_CODE_RANGE) { "Profile consent code out of range" }
…
105	            registeredAtMillis = obj.getValue("registered").jsonPrimitive.content.toLong(),
107	            lastVerifiedAtMillis = obj["verified"]?.jsonPrimitive?.content?.toLongOrNull(),
```

- `:33` `it as JsonObject` — a non-object array element is `ClassCastException`.
- `:93-95`, `:100-106` seven × `getValue` — `NoSuchElementException` on a
  missing key; `.jsonPrimitive` on each — `IllegalArgumentException` if the
  element is an object or array.
- `:105` `.toLong()` — `NumberFormatException`. Internal inconsistency:
  `registeredAtMillis` throws, while `lastVerifiedAtMillis` two lines below
  uses `.toLongOrNull()`. Nothing in the type system links the two.
- `:96-98` the three `require()` calls **added during this review** by the
  concurrent maintainability fix are correct as validation, but they widen the
  throw surface feeding the funnel below.

**What makes this HIGH is the consumer**, in `data/ScaleProfileStore.kt`:

```
137	    private fun persist(next: List<ScaleProfile>) {
138	        prefs.edit().putString(KEY_PROFILES, ScaleProfileCodec.encodeToString(next)).commit()
…
143	    private fun readProfiles(): List<ScaleProfile> = runCatching {
144	        prefs.getString(KEY_PROFILES, null)?.let(ScaleProfileCodec::decodeFromString).orEmpty()
145	    }.getOrDefault(emptyList())
```

Any of those exception classes → `getOrDefault(emptyList())` → the store
initialises with zero profiles, no log, no user-visible signal. The **next**
write of any kind calls `persist()` at `:137`, which overwrites the stored
blob with the empty-derived list. The original JSON is gone.

**Concrete failure scenario, live as of the concurrent fix**: `:96` now
requires every stored `address` to match `Regex("(?:[0-9A-F]{2}:){5}[0-9A-F]{2}")`.
Any profile already persisted by an earlier build whose address does not match
that exact form now throws at startup read → the entire registry silently
reads as empty → the first subsequent write erases it. A validation rule added
to protect the *import* path also applies to the *stored* path, and the
catch-all converts the difference into data loss.

**Second scenario**: a future version adds a required field to `decodeOne`.
A user on that version rolls back — every stored profile fails `getValue`, same
silent wipe. Consent codes are **not recoverable from the app**: re-establishing
them requires physically re-registering with the scale. A type error becomes
user-visible as "my scale forgot me."

**Why HIGH and not CRITICAL** (the project rubric makes "data-loss risk"
CRITICAL/BLOCK, so this needs stating rather than leaving implicit): the loss
is latent, not triggered by any input reachable today through the app's own
encoder — every path requires either a schema-shape change across versions or
a pre-existing store row that predates the new `require`. It is a
BLOCK-if-confirmed rather than a BLOCK-on-sight. If the team can confirm any
shipped build wrote addresses in a form `:96` now rejects, it becomes CRITICAL
immediately.

**Recommendation**: `ScaleProfile` is already a `data class` and
kotlinx.serialization is already on the classpath. `@Serializable` with
`@SerialName` for the short wire keys makes the key↔field mapping
compile-checked, narrows the failure to a single `SerializationException`, and
`ignoreUnknownKeys = true` handles the forward-compat case. Separately,
`readProfiles()` must distinguish "no blob stored yet" from "a blob exists but
did not parse" — only the first is safely empty; the second should refuse to
overwrite.

---

### TS-H4. `SettingsBackupCodec.decode` — three unguarded `Enum.valueOf` on imported data, in a diff that establishes the guarded pattern for the same two enums

```
data/SettingsBackupCodec.kt  (in `decode`)
96	        val version = obj.getValue("version").jsonPrimitive.int
97	        require(version in 1..FORMAT_VERSION) { "Unsupported backup version" }
101	        val credentialType = BackupCredentialType.valueOf(obj.getValue("credential_type").jsonPrimitive.content)
117	            displayUnit = WeightUnit.valueOf(obj.getValue("display_unit").jsonPrimitive.content),
118	            contractVersion = ContractVersion.valueOf(obj.getValue("contract_version").jsonPrimitive.content),
```

Same `getValue` / `.jsonPrimitive` hazards as TS-H3, plus three bare `valueOf`
calls, each of which throws `IllegalArgumentException` on a name the running
binary does not know.

**The version guard at `:97` does not protect these.** Adding a `WeightUnit`
or `ContractVersion` constant is a source-level change that does not *feel*
like a change to the backup format, so `FORMAT_VERSION` plausibly stays at 2 —
and a backup carrying the new name sails past
`require(version in 1..FORMAT_VERSION)` and dies at `valueOf`.

**Failure scenario end-to-end**: `ConfigViewModel.importSettings` (`:444`)
wraps the whole thing in `runCatching`, so the `IllegalArgumentException`
becomes `Result.failure`, which `ConfigScreen.kt:718` renders as **"Import
failed. Check the file and passphrase."** The user is told their passphrase is
wrong when the actual cause is a schema mismatch between two versions of the
same app. There is no path from that message to the real problem.

**Sharp contrast, same diff, same enums**:

```
data/ConfigStore.kt
56	        prefs[DISPLAY_UNIT]?.let { runCatching { WeightUnit.valueOf(it) }.getOrNull() } ?: WeightUnit.KILOGRAMS
60	        prefs[CONTRACT_VERSION]?.let { runCatching { ContractVersion.valueOf(it) }.getOrNull() }
61	            ?: ContractVersion.V1_WEIGHT_ONLY
```

The hardened pattern already exists in this branch for both enums. The backup
codec — which reads data from *outside* the app, i.e. the strictly less
trustworthy source — is the one that skipped it.

**Non-destructive**, unlike TS-H3 and TS-H5: `decrypt` runs before any
`configStore.save*` call (`:446` precedes `:452`), so a throw aborts the
import cleanly with no partial write.

---

### TS-H5. `SettingsBackupCodec.kt:110` — a *safe* cast makes a malformed `profiles` field silently delete the user's scale registration instead of failing the import

```
data/SettingsBackupCodec.kt  (in `decode`)
109	        val profiles = if (version >= 2) {
110	            (obj["profiles"] as? JsonArray)?.let(ScaleProfileCodec::decode).orEmpty()
111	        } else {
112	            emptyList()
113	        }
```

A `profiles` value that is **present but not a `JsonArray`** (an object, a
string, a number — a hand-edited backup, a truncated write, a future format
that nests profiles under a wrapper) makes `as?` yield `null`, `?.let` skip,
and `.orEmpty()` produce an empty list. No exception. The import proceeds as
though the backup legitimately contained zero profiles.

Then in `ui/ConfigViewModel.importSettings`:

```
458	            if (imported.profiles.isNotEmpty() && scaleProfileStore != null) {
459	                scaleProfileStore.replaceAll(imported.profiles)
460	            } else {
461	                if (previousAddress != null) consentStore.clear(previousAddress)
```

Empty profiles takes the legacy branch and calls `consentStore.clear(previousAddress)`.
For `EncryptedScaleProfileStore` that is `ScaleProfileStore.kt:99-102`:

```
 99	    override fun clear(deviceAddress: String) {
100	        replaceAll(mutableProfiles.value.filterNot { it.deviceAddress.equals(deviceAddress, true) })
101	        legacy?.clear(deviceAddress)
102	    }
```

→ `replaceAll` → `persist()` → **the user's existing registration for the
paired address is deleted from encrypted storage**, and is only re-saved if
the backup also carried `scale_index` + `consent_code` at top level.

**This is the sharpest instance of the report's central thesis.**
`ScaleProfileCodec.decode` throws loudly on exactly this class of bad input
(TS-H3); `SettingsBackupCodec.kt:110` swallows it silently — one file apart,
opposite policies for the same failure, and the silent one is the destructive
one. A malformed `profiles` field in an otherwise-valid, correctly-decrypted
v2 backup wipes registrations rather than aborting the import.

Note this also qualifies TS-H4's "non-destructive" note: that holds for the
*throwing* paths through `decode`, not for this one.

**Recommendation**: `obj["profiles"]` being present but not an array is a
corrupt backup — `require(element is JsonArray) { … }`, matching the
`require`-on-bad-input style used everywhere else in this same function
(`:97`, `:103`, `:106`, `:114`). Separately, `importSettings` should not treat
"backup contained no profiles" and "this backup predates profiles" as the same
branch when the consequence of the former is deletion.

---

## MEDIUM

### TS-M1. Platform-type null asymmetry: `getSystemService(...)` dereferenced with a hard `.` at two sites and `?.` at four

| Site | Enclosing | Deref |
|---|---|---|
| `ble/ScaleRegistrar.kt:47` (used `:54`, `:120`) | property | `?.` |
| `ble/ScaleScanner.kt:20` | `scanner` getter | `?.` |
| `ble/session/ScaleSessionWorker.kt:30` | `doWork` | `?.` + `?: return` |
| `service/BridgeForegroundService.kt:27` | `scanner` getter | `?.` |
| **`ble/session/ScaleSessionWorker.kt:51`** | **`foregroundInfo`** | **`.` — no null check** |
| **`service/BridgeForegroundService.kt:72`** | **`createChannel`** | **`.` — no null check** |

```
ScaleSessionWorker.kt
51	        applicationContext.getSystemService(NotificationManager::class.java).createNotificationChannel(
```

This compiles *only* because `Context.getSystemService(Class<T>)` is a Java
API returning the platform type `T!`, where Kotlin suspends null-checking
entirely. The compiler provides zero protection here — the two hard
dereferences and the four safe ones are indistinguishable to it. **Twenty-one
lines apart in the same file** (`:30` vs `:51`), the same call is treated as
nullable and as non-null.

**Failure scenario**: `ScaleSessionWorker.kt:51` is inside `foregroundInfo()`,
called from `setForeground(...)` at `:28` — i.e. on the very first instruction
of a background capture, before any user-visible work. A null there is an NPE
intrinsic inside a WorkManager worker: the capture dies, the user sees a
weigh-in that simply never appeared, and the branch's own `MissReason`
diagnostics never run.

**Recommendation**: `?.` + an explicit failure (`?: return Result.failure()`),
matching `:30` twenty-one lines up, or `requireNotNull(...) { … }` to make the
assumption a stated, attributable one rather than a platform-type accident.

### TS-M2. `AndroidGattTransport.kt:163` — legacy `getParcelableExtra` with neither a `Class` argument nor an explicit type argument

In `adapterReceiver.onReceive`:

```
159	                    val changedDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
160	                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
161	                    } else {
162	                        @Suppress("DEPRECATION")
163	                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
164	                    }
165	                    if (changedDevice?.address == device.address) {
```

The API-33+ branch at `:160` passes a `Class` and gets a genuine runtime type
check. The legacy branch at `:163` uses the bare generic overload; `T` is
inferred solely from the *other* branch of the enclosing `if`-expression, and
the compiler inserts an unchecked cast with no runtime verification. On
API < 33, a `BOND_STATE_CHANGED` intent carrying any other `Parcelable` under
the `EXTRA_DEVICE` key produces a `ClassCastException` at `:165`, not at
`:163` — the stack trace points at the comparison, not the extraction.

**Contrast in the same diff**: `ble/ScanBroadcastReceiver.kt:30` writes the
legacy call as `getParcelableArrayListExtra<ScanResult>(...)` with an explicit
type argument. Still unchecked, but the intended type is stated at the call
site rather than inferred from a sibling branch. The two legacy branches in
this diff disagree about how to spell the same thing.

*Type safety only — whether the receiver should trust intent extras at all
belongs to `review-security`. Note the receiver is registered
`RECEIVER_NOT_EXPORTED` (`:144`), which narrows but does not eliminate the
concern.*

### TS-M3. `BeurerDecoder.kt:108` — `else -> HandshakeDirective.Wait` over sealed `HandshakeState` makes a protocol-order violation indistinguishable from "not yet"

```
104	    override fun onHandshakeEvent(event: DecodeEvent): HandshakeDirective =
105	        when (val state = handshake) {
106	            is HandshakeState.AwaitingRegistration -> onRegistrationEvent(state, event)
107	            is HandshakeState.AwaitingConsent -> onConsentEvent(state, event)
108	            else -> HandshakeDirective.Wait
109	        }
```

`HandshakeState` (`:293-329`) is sealed with four subtypes; `else` absorbs
`NotStarted` and `Consented`.

**Failure scenario**: a `DecodeEvent` arriving while the decoder is in
`NotStarted` (a frame before the handshake began) or `Consented` (a duplicate
or late ack after it finished) returns `HandshakeDirective.Wait` — the exact
same directive that means "the ack I am waiting for has not arrived yet." The
session therefore stalls to `ACK_TIMEOUT` rather than reporting that it
received a frame it had no state to interpret. Given how much care the
surrounding KDoc (`:307-321`) puts into distinguishing stale responses from
genuine refusals via `staleResponseBudget`, collapsing two more states into
the same undifferentiated `Wait` runs against the file's own design intent. A
fifth handshake state compiles clean and stalls the same way.

### TS-M4. `ScaleViewModel` untyped `combine` — *verified; already reported as maintainability H6*

Confirmed exactly as described in `pr-1-review.md` H6. One type-safety detail
worth folding into that finding rather than duplicating it:

```
ui/ScaleViewModel.kt
47	        @Suppress("UNCHECKED_CAST")
48	        val all = values[0] as List<ScaleProfile>
```

The five sibling casts (`values[1] as Boolean`, `values[3] as Int`, …) are
*checked* — a reordering of the flows throws `ClassCastException` at the cast
itself, with the ViewModel in the stack trace. `values[0] as List<ScaleProfile>`
is the one that is genuinely unchecked: erasure means it succeeds against
**any** `List`, and the failure surfaces later at `all.firstOrNull { it.active }`
(`:51`) or downstream in `ScaleScreen`. So the single cast the author
suppressed is the single cast that does not fail where the mistake was made.

### TS-M5. `HistoryViewModel.statusRank` — *verified; already reported as maintainability M6*

Confirmed. One correction to M6's stated blast radius, which materially
understates it:

```
ui/HistoryViewModel.kt
56	            rows = readings.sortedWith(rowOrdering),
109	        val rowOrdering = compareBy<ReadingEntity> { statusRank.getValue(it.status) }
```

`rowOrdering` is consumed by `sortedWith` at `:56`, which is **inside the
`combine` transform** feeding `uiState`, which is shared via
`stateIn(viewModelScope, SharingStarted.Eagerly, …)` at `:65`. So a
`NoSuchElementException` from one unmapped `ReadingStatus` does not corrupt one
row's render (M6's framing) and does not merely stall the flow — it propagates
out of the transform into the sharing coroutine launched in `viewModelScope`.
`viewModelScope` uses a `SupervisorJob`, which stops the failure cancelling
siblings but does **not** handle it; with no `CoroutineExceptionHandler`
installed it reaches the thread's default uncaught handler. On Android that is
a process crash, not a degraded screen.

Worth noting for whoever fixes it: `HistoryScreen.kt:165` and `:226` both
enumerate all six `ReadingStatus` cases with no `else`, and `:178-182` carries
a comment specifically explaining that this is deliberate so a new status
forces a compile error. `statusRank` is the one site in this feature that opts
out of the discipline the file next door documents.

---

## LOW

### TS-L1. Eight unchecked `as BasculeApplication` downcasts, four of them in reflectively-instantiated components

`ScaleSessionWorker.kt:29`, `DeliveryWorker.kt:19`, `BootReceiver.kt:23`,
`BridgeForegroundService.kt:47`, `ConfigScreen.kt:72`, `HistoryScreen.kt:47`,
`ManualEntryScreen.kt:38`, `ScaleScreen.kt:35`.

The four Compose sites are effectively safe — a failure is immediate, visible,
and reproducible on first launch. The four in workers, the receiver and the
service are the ones worth naming: those components are instantiated by the
framework from a manifest string, and the cast is the only thing asserting
that `applicationContext` is the concrete type. A manifest `android:name`
change, or an instrumentation harness supplying its own `Application`
subclass, produces a `ClassCastException` in a background component where
nobody is watching. LOW because "there is exactly one Application class" is a
stable Android assumption; recorded because there is no compile-time link
between the manifest string and the type, and this diff adds four such sites
at once.

### TS-L2. `ConfigScreen.kt:658,670` — `!!` on `ContentResolver` streams

```
658	                        context.contentResolver.openOutputStream(uri, "w")!!.use { it.write(bytes) }
670	                        context.contentResolver.openInputStream(uri)!!.use(InputStream::readSettingsBackup)
```

Both nulls are legitimate, not defensive paranoia: a ContentProvider returns
null for a revoked URI grant or an offline cloud-storage document. Both are
inside `runCatching { withContext(Dispatchers.IO) { … } }` (`:654-662`,
`:665-673`), so the NPE is caught and surfaces as "Could not write the backup
file." / "Could not read the selected file." — the correct user-facing
outcome, which is why this is LOW.

Recorded because `?: error("…")` or `?: return@runCatching` produces identical
behaviour while stating the assumption, and because the project's own Kotlin
style rule (`rules/kotlin/coding-style.md`, "Null Safety") bans `!!` outright —
these are the only two occurrences in `app/src/main`, so the rule is otherwise
held.

### TS-L3. `GattSession.kt:413,464,491` — `@Suppress("UNREACHABLE_CODE")` disarms the warning that would flag a future `break`

```
410	                    else -> continue
411	                }
412	            }
413	            @Suppress("UNREACHABLE_CODE")
414	            SubscriptionOutcome.Failed
415	        } ?: SubscriptionOutcome.Failed
```

(Same shape at `:464-466` in `awaitMeasurement`'s first loop and `:491-493` in
its correlation loop.) The compiler has *proved* the trailing value
unreachable — the `while (true)` has no `break`, so the only exits are
`return@withTimeoutOrNull` or the timeout. The suppression exists purely to
give the lambda a well-typed tail.

**Failure scenario**: if anyone later adds a `break` to one of these loops, the
trailing `SubscriptionOutcome.Failed` / `MeasureStep.Pending` becomes genuinely
reachable and is returned as a **non-null** value — which means the `?:` on the
following line, i.e. the entire timeout-handling branch, is skipped. At `:466`
that branch is `if (malformed > 0) DecodeFailure(malformed) else Missed(NO_MEASUREMENT)`,
so "the loop broke out early" would be silently reported as a successful
`Pending`. The suppression is precisely what would prevent the compiler from
warning about the newly-reachable code at the moment the `break` is added.

Consider restructuring so the trailing value is unnecessary rather than
suppressing.

### TS-L4. `AndroidGattTransport.kt:151` — `when (intent?.action)` is string-typed dispatch with no `else`

```
141	            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED).apply {
142	                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
143	            },
…
151	            when (intent?.action) {
152	                BluetoothAdapter.ACTION_STATE_CHANGED -> …
158	                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> { … }
                     // no else
```

A statement-position `when` over `String?`, so exhaustiveness is neither
required nor checkable. The filter (`:141-143`) and the dispatch (`:151-158`)
are two hand-maintained lists.

Downgraded to LOW after verifying the filter: the two lists sit ten lines apart
in the same class and are built from the same two constants, so drift is
unlikely in practice, and a third action added to the filter would be silently
ignored rather than misbehaving. Recorded because the `ACTION_STATE_CHANGED`
branch is the sole producer of `TransportEvent.AdapterOff`, and per TS-H1 every
`TransportEvent` loop in `GattSession` uses `AdapterOff` as its early-exit
condition — so this is the one string-typed dispatch in the diff whose silent
failure would be expensive. Deriving the `IntentFilter` from the same list the
`when` dispatches on would remove the coupling entirely.

### TS-L5. Informational, outside this diff: `Converters.kt` uses bare `valueOf` for the same enum `statusRank` mishandles

```
data/Converters.kt
17	            // An unknown name means a downgrade after a contract change; drop it
18	            // rather than fail the read and strand the row.
19	            .mapNotNull { name -> ReadingField.entries.firstOrNull { it.name == name } }
26	    fun toStatus(value: String): ReadingStatus = ReadingStatus.valueOf(value)
32	    fun toSource(value: String): ReadingSource = ReadingSource.valueOf(value)
38	    fun toErrorClass(value: String?): ErrorClass? = value?.let(ErrorClass::valueOf)
```

`Converters.kt` is **not** in this branch's changed-file list, so this is out of
scope as a PR finding. Flagged only because it is a third instance of the TS-H4
pattern and the file argues against itself: `:17-19` documents the tolerant
approach for `ReadingField` — naming the downgrade scenario explicitly — and
then `:26`, `:32` and `:38` do the opposite for the other three enums, inside
Room's cursor mapping where a throw kills the whole query.

It matters *here* because `ReadingStatus` is the enum this diff's `statusRank`
(TS-M5) also handles unsafely: a future status constant would break the History
flow in two independent ways. This branch also bumps the Room schema to v2
(`BasculeDatabase.kt:16`) with a real `MIGRATION_1_2` and correctly no
`fallbackToDestructiveMigration`, so downgrade paths are a live consideration
for this codebase. Worth a follow-up ticket rather than a change in this PR.

*Provenance caveat: `git` was unavailable, so "not in the changed-file list"
rests on the `--stat` output captured at session start, which listed nine files
under `data/` and did not include `Converters.kt`.*

---

## Cross-references to the maintainability review

- **H6** (`ScaleViewModel` untyped `combine`) — verified. See TS-M4 for one
  additional detail; not re-reported.
- **M6** (`HistoryViewModel.statusRank`) — verified. See TS-M5 for a
  correction to its blast radius (process crash, not a render bug); not
  re-reported.
- **H8** (`GattSession.awaitMeasurement` duplicate event loops) —
  independently confirmed from the type-safety side. TS-H1 identifies the
  *mechanism* that let the duplication diverge (`else ->` on `DecodeEvent` at
  `:486`) and names the specific divergence (`SessionComplete` handled at
  `:457`, swallowed at `:486`). The two findings share a root cause;
  de-duplicating the loops (H8) and making the `when`s exhaustive (TS-H1) are
  alternative fixes for the same defect, and either one prevents recurrence.
- **H5** (`credentialFor` writing as a side effect) — observed being fixed
  during this review. The fix introduces the three new `require()` calls that
  TS-H3's first failure scenario now turns on; the two should be triaged
  together.

## Suggested fix order

1. **TS-H5** — smallest change, worst current consequence (silent deletion of
   a registration on a malformed-but-decryptable backup). One `require`.
2. **TS-H1 / TS-H2 / TS-M3** — mechanical `else ->` removal across
   `GattSession.kt` (10 sites), `ScaleSessionWorker.kt:46`,
   `BeurerDecoder.kt:108`. No behaviour change today except the
   `SessionComplete` divergence at `GattSession.kt:486`, which is a bug fix.
   Highest ratio of future defects prevented to risk taken.
3. **TS-H3** — `@Serializable` on `ScaleProfile`, and make
   `ScaleProfileStore.readProfiles()` distinguish "empty" from "unparseable" so
   a decode failure can never silently destroy the registry. Coordinate with
   whoever is landing the maintainability H5 fix.
4. **TS-H4** — guard the three `valueOf` calls using the `ConfigStore.kt:56`
   pattern, and give version/enum mismatches a distinct user-facing message
   from a bad passphrase.
5. **TS-M1 / TS-M2** — platform-null and Android-API type escapes.
6. LOW items at leisure; **TS-L5** as a separate ticket, not in this PR.
