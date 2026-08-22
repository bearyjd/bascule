# Bascule — Phase 2 CI and test-lane notes

Companion to `02-interface-revision.md`. Records how the Phase 2 exit gate's two
conflicting requirements — "CI green on skeleton" and "contract tests exist and
are red" — are satisfied at the same time.

## Two lanes, one suite

The contract tests are red on purpose. Left in the main test task they would make
CI red on every run, and a genuine regression would be indistinguishable from the
expected failures. They are therefore selected by a Gradle property:

| Command | Runs | Expected |
|---|---|---|
| `./gradlew testDebugUnitTest` | everything except `*ContractTest` | **green** |
| `./gradlew testDebugUnitTest -Pbascule.contractTests=true` | only `*ContractTest` | **3 of 4 red** |

Both lanes compile the same source set, so a contract test that stops compiling
breaks the build in either lane. That is the property that matters: "red" here
means an assertion failed, never that the skeleton failed to build.

`.github/workflows/ci.yml` runs assemble → unit tests → lint → detekt as blocking
steps, then the contract lane as a `continue-on-error` step labelled with what it
is. **Phase 3 flips that step to blocking** once WP-10 lands; the filter and the
CI step are both removed at that point, and the contract tests rejoin the main
task.

## What is red, and why that is the right red

`ScaleSessionContractTest` drives a whole session against `FakeGattTransport`
with the real captured BF720 bytes and asserts the end-to-end property: bytes in,
one correctly attributed `ScaleReading` out.

```
aWeighInProducesExactlyOneAttributedReading
  → expected Completed, got Missed(reason=NO_MEASUREMENT)
aRegisteredScaleIndexIsPersistedForTheNextSession
  → without a persisted mapping every weigh-in registers a new user slot
    expected:<2> but was:<null>
theSessionSubscribesOnlyAfterConsentIsGranted
  → the session never sent Consent
```

Three assertion failures, no exceptions, no `NotImplementedError`. `GattSession`
returns `SessionOutcome.Missed(NO_MEASUREMENT)` — an outcome the sealed type
already has, so nothing was invented to produce the failure and nothing has to be
deleted when WP-06/07/10 land.

The fourth test in that class, `everyTerminalPathClosesGattExactlyOnce`, passes:
the stub does close the transport exactly once. It is left in the contract lane
because it belongs to the same invariant and will be re-proven against the real
run loop.

The layer beneath is green, which is what makes the red meaningful: the byte
decoding, the correlation, and the UDS handshake are all implemented and covered
(`BeurerDecoderCaptureTest`, `BeurerHandshakeTest`) against the real capture in
`03-hardware-validation.md`. The failures above are session orchestration and
nothing else.

## One test-authoring rule worth keeping

The HTTP contract tests use `runBlocking`, not `runTest`. `runTest` applies a
60-second wall-clock watchdog to the test body and `VitalForgeHttpClient` does
real blocking IO on `Dispatchers.IO`, which virtual time cannot advance. The
result was a suite that took **7m12s** and failed intermittently with
`UncompletedCoroutinesError: After waiting for 1m, the test body did not run to
completion` — a flake that looks exactly like a client bug. The same suite under
`runBlocking` runs in **11s**.

`runTest` stays where it belongs: the fake-transport and future session tests,
which have virtual delays worth advancing and no real IO.

## Toolchain

Pinned in `gradle/libs.versions.toml`: AGP 9.3.1, Gradle 9.6.1 (wrapper),
Kotlin 2.4.10, KSP 2.3.11, Room 2.8.4, WorkManager 2.11.2, OkHttp 5.5.0,
Compose BOM 2026.08.00, detekt 1.23.8, Robolectric 4.16.1, JUnit 4.13.2.

Two toolchain facts worth recording because they are not free choices:

- **AGP 9 has built-in Kotlin support.** Applying
  `org.jetbrains.kotlin.android` alongside it is a hard error, so it is not
  applied. `org.jetbrains.kotlin.plugin.compose` still is.
- **compileSdk 37 is a floor, not a preference.** `androidx.core` 1.19,
  `androidx.lifecycle` 2.11 and `okhttp-android` 5.5 all refuse to compile
  against 36. minSdk stays at 26 per PRP.

## Verified locally

Counts as of the Phase 2 reconciliation
(`02-phase2-dispositions.md`), from a `--rerun-tasks` run of both lanes:

| Lane | Result |
|---|---|
| `testDebugUnitTest` | **61 tests, 0 failures, 0 errors** across 7 classes — green |
| `testDebugUnitTest -Pbascule.contractTests=true` | **4 tests, 3 failures, 0 errors** — the same three documented reds below, no new ones |
| `build` (assemble + R8) | green |
| `lintDebug`, `detekt` | green, no issues |

The main lane grew from 54 to 61: five tests added by the reconciliation
(`DedupPolicyTest`'s four, `FakeGattTransportTest.notifyAndIndicateAreDistinguishableSubscriptions`)
plus `BeurerDecoderCaptureTest`'s two correlation-misattribution tests. Commands:

```
./gradlew build                                          # includes assembleRelease + R8
./gradlew testDebugUnitTest                              # main lane, must be green
./gradlew testDebugUnitTest -Pbascule.contractTests=true # contract lane, expected red
./gradlew lintDebug detekt
```

Not verified locally: the GitHub Actions workflow itself, and
`connectedDebugAndroidTest` — no emulator was started in this session, so
`AuthTokenStoreTest` and the other instrumented tests `01-plan.md` names remain
unwritten and are Phase 3 work.
