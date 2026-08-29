# Plan: VitalForge connectivity test + in-app "Test connection" token check

## Summary
Add a read-only `testConnection()` probe to the VitalForge network client (contract-tested against MockWebServer, matching this repo's existing HTTP client test pattern) plus a "Test connection" button in the Config screen's VitalForge server section that lets the user verify their saved base URL and token actually work against a real server — without ever submitting a fake reading.

## User Story
As a Bascule user configuring the app, I want a way to confirm my VitalForge server URL and token are both correct, so that I don't discover a typo or expired token only when a real weigh-in silently fails to sync.

## Problem → Solution
Today, `ConfigScreen` lets a user save a base URL and a token, but there is no way to know either one actually works until a real reading gets stuck in `BLOCKED_AUTH` or `PENDING`. → A `VitalForgeApi.testConnection()` method (GET `/api/weight/recent`, the same read-only dedup-check endpoint already used elsewhere — no new server route needed) plus a UI button surface a clear pass/fail/bad-token result on demand.

## Metadata
- **Complexity**: Small–Medium
- **Source PRD**: N/A (ad hoc user request via `/prp-plan`)
- **PRD Phase**: N/A
- **Estimated Files**: 6 (2 main network, 1 main ViewModel, 1 main UI, 2 test)

---

## UX Design

### Before
```
┌──────────────────────────────┐
│ VitalForge server             │
│ [ Base URL______________ ]    │
│ [ Save ]                      │
└──────────────────────────────┘
```
No way to know if the URL/token actually work until a real sync fails.

### After
```
┌──────────────────────────────┐
│ VitalForge server             │
│ [ Base URL______________ ]    │
│ [ Save ]  [ Test connection ] │
│ ✓ Connected — token accepted  │
└──────────────────────────────┘
```
Button disabled until both a base URL and a token are saved. Tapping it shows
"Testing…", then one of: "✓ Connected — token accepted", "✗ Server rejected
the token (HTTP 401)", or "✗ <network/server failure reason>".

### Interaction Changes
| Touchpoint | Before | After | Notes |
|---|---|---|---|
| Config → VitalForge server card | Save button only | Save + Test connection buttons | Test button disabled until `tokenIsSet && baseUrl.isNotBlank()` |

---

## Mandatory Reading

| Priority | File | Lines | Why |
|---|---|---|---|
| P0 | `app/src/main/kotlin/com/ventouxlabs/bascule/network/VitalForgeApi.kt` | 1-59 | Where `ConnectionTestResult` and the new interface method are added |
| P0 | `app/src/main/kotlin/com/ventouxlabs/bascule/network/VitalForgeHttpClient.kt` | 1-176 | `resolve()`, `execute()`, `RECENT_PATH` — the probe reuses this exact request-building machinery |
| P0 | `app/src/main/kotlin/com/ventouxlabs/bascule/network/ResponseClassifier.kt` | 1-58 | `classify()` already maps 401/403 → `AuthRejected`; reused rather than re-implemented |
| P0 | `app/src/main/kotlin/com/ventouxlabs/bascule/ui/ConfigViewModel.kt` | 1-196 | `_baseUrlError`/`_tokenVersion` pattern to mirror for `_connectionTest`; `combine()` already at 4 flows, becomes 5 |
| P1 | `app/src/main/kotlin/com/ventouxlabs/bascule/ui/ConfigScreen.kt` | 168-196 | `ConnectionSection` composable being extended |
| P1 | `app/src/main/kotlin/com/ventouxlabs/bascule/network/AuthTokenStore.kt` | 19-27 | `token()` is the exact shape `VitalForgeHttpClient`'s `tokenProvider` expects |
| P2 | `app/src/test/kotlin/com/ventouxlabs/bascule/network/VitalForgeHttpClientTest.kt` | 1-279 | Test structure/naming to mirror for the new `testConnection()` tests |
| P2 | `app/src/test/kotlin/com/ventouxlabs/bascule/ui/ConfigViewModelTest.kt` | all | Test structure to mirror for `testConnection()` ViewModel tests |
| P2 | `app/src/test/kotlin/com/ventouxlabs/bascule/ui/fake/FakeAuthTokenStore.kt` | all | Fake pattern to mirror for `FakeVitalForgeApi` |

## External Documentation
No external research needed — feature uses established internal patterns (OkHttp MockWebServer, existing `VitalForgeApi` sealed-result style).

---

## Patterns to Mirror

### SEALED_RESULT_TYPE
```kotlin
// SOURCE: network/VitalForgeApi.kt:43-56
sealed interface SubmitResult {
    data class Accepted(val deliveredFields: Set<ReadingField>) : SubmitResult
    data class TransientFailure(val reason: String, val retryAfter: Duration?) : SubmitResult
    data class AuthRejected(val httpCode: Int) : SubmitResult
    data class PermanentRejection(val httpCode: Int, val reason: String) : SubmitResult
}
```

### RESPONSE_CLASSIFICATION_REUSE
```kotlin
// SOURCE: network/ResponseClassifier.kt:19-37 — reuse classify(), don't re-derive status-code buckets
fun classify(httpCode: Int, deliveredFields: Set<ReadingField>, retryAfterHeader: String? = null): SubmitResult
```

### VIEWMODEL_ONE_SHOT_STATE_FIELD
```kotlin
// SOURCE: ui/ConfigViewModel.kt:68-72
private val _baseUrlError = MutableStateFlow<String?>(null)
private val _tokenVersion = MutableStateFlow(0)
private val _consentVersion = MutableStateFlow(0)
```

### VIEWMODEL_COMBINE_INTO_UISTATE
```kotlin
// SOURCE: ui/ConfigViewModel.kt:84-103
val uiState: StateFlow<ConfigUiState> = combine(
    storedConfig, _baseUrlError, _tokenVersion, _consentVersion,
) { stored, urlError, _, _ -> ConfigUiState(...) }
    .flowOn(ioDispatcher).stateIn(viewModelScope, SharingStarted.Eagerly, ConfigUiState())
```

### CONTRACT_TEST_STRUCTURE
```kotlin
// SOURCE: test/network/VitalForgeHttpClientTest.kt:57-66
@Test
fun postsToApiWeightPath() = runBlocking {
    server.enqueue(ok())
    client().submitReading(ReadingFixtures.captured(), WeightUnit.KILOGRAMS)
    val request = server.takeRequest()
    assertEquals("POST", request.method)
    assertEquals("/api/weight", request.url.encodedPath)
}
```

### FAKE_OVER_MOCK
```kotlin
// SOURCE: test/ui/fake/FakeAuthTokenStore.kt — hand-written fake, not a mocking framework
class FakeAuthTokenStore(private var stored: String? = null) : AuthTokenStore {
    override fun isSet() = stored != null
    override fun token() = stored
    override fun save(token: String) { stored = token }
    override fun clear() { stored = null }
}
```

---

## Files to Change

| File | Action | Justification |
|---|---|---|
| `app/src/main/kotlin/com/ventouxlabs/bascule/network/VitalForgeApi.kt` | UPDATE | Add `ConnectionTestResult` sealed interface + `testConnection()` to the interface |
| `app/src/main/kotlin/com/ventouxlabs/bascule/network/VitalForgeHttpClient.kt` | UPDATE | Implement `testConnection()` via GET `RECENT_PATH` + `ResponseClassifier.classify()` |
| `app/src/main/kotlin/com/ventouxlabs/bascule/ui/ConfigViewModel.kt` | UPDATE | Add `apiFactory` constructor param (default builds a real `VitalForgeHttpClient`), `ConnectionTestUiState`, `_connectionTest`, `testConnection()` |
| `app/src/main/kotlin/com/ventouxlabs/bascule/ui/ConfigScreen.kt` | UPDATE | `ConnectionSection` gains a "Test connection" button + result text |
| `app/src/test/kotlin/com/ventouxlabs/bascule/network/VitalForgeHttpClientTest.kt` | UPDATE | Add `testConnection()` contract tests (authorized / unauthorized / unreachable / GET-not-POST / no side effect) |
| `app/src/test/kotlin/com/ventouxlabs/bascule/ui/fake/FakeVitalForgeApi.kt` | CREATE | Fake implementing `VitalForgeApi` with a settable `testConnection()` result |
| `app/src/test/kotlin/com/ventouxlabs/bascule/ui/ConfigViewModelTest.kt` | UPDATE | ViewModel-level `testConnection()` tests (idle→testing→success/failure transitions, double-tap guard) |

## NOT Building
- No login/auth flow inside the Android app itself — the VitalForge test server's dashboard login is out of scope; the app only ever accepts a pre-generated bearer token (existing `saveToken` flow), consistent with `00-design.md` §8.8.
- No new server-side endpoint — reuses the existing `/api/weight/recent` route.
- No change to `recentReadings()`'s own error handling (it must stay a permissive "unavailable, caller posts anyway" per ADR-003 step 3) — `testConnection()` is a deliberately separate, stricter-classifying method, not a refactor of `recentReadings()`.
- No automated CI test hits the live `https://health.grepon.cc` server — the committed test suite uses `MockWebServer` exclusively (matches every existing test in `VitalForgeHttpClientTest`); live verification against the real test server is a manual/on-device step run once in this session, not a permanent CI dependency.
- No WP-08 (scale registration) work — tracked separately per the user's explicit choice to defer it.

---

## Step-by-Step Tasks

### Task 1: Add `ConnectionTestResult` + interface method
- **ACTION**: Edit `VitalForgeApi.kt`
- **IMPLEMENT**:
  ```kotlin
  sealed interface ConnectionTestResult {
      /** Reachable and the configured token was accepted. */
      data object Authorized : ConnectionTestResult
      /** Reachable, but the server rejected the token (HTTP 401/403). */
      data class Unauthorized(val httpCode: Int) : ConnectionTestResult
      /** Unreachable, or reachable but returned something other than success/auth-rejection. */
      data class Unreachable(val reason: String) : ConnectionTestResult
  }
  ```
  Add `suspend fun testConnection(): ConnectionTestResult` to `VitalForgeApi`.
- **MIRROR**: SEALED_RESULT_TYPE
- **IMPORTS**: none new
- **GOTCHA**: `VitalForgeApi` has exactly one implementer (`VitalForgeHttpClient`, confirmed via grep) — safe to add a non-default interface method.
- **VALIDATE**: `./gradlew :app:compileDebugKotlin` fails until Task 2 implements it — expected, confirms the interface change is real.

### Task 2: Implement `testConnection()` in `VitalForgeHttpClient`
- **ACTION**: Edit `VitalForgeHttpClient.kt`
- **IMPLEMENT**:
  ```kotlin
  override suspend fun testConnection(): ConnectionTestResult {
      val url = resolve(RECENT_PATH)?.newBuilder()
          ?.addQueryParameter("within_seconds", "60")
          ?.build()
          ?: return ConnectionTestResult.Unreachable("base URL is not a valid http(s) URL")

      val request = Request.Builder()
          .url(url)
          .get()
          .apply { tokenProvider()?.let { header("Authorization", "Bearer $it") } }
          .build()

      return execute(request, onFailure = { ConnectionTestResult.Unreachable(it) }) { response ->
          when (val classified = ResponseClassifier.classify(response.code, emptySet())) {
              is SubmitResult.Accepted -> ConnectionTestResult.Authorized
              is SubmitResult.AuthRejected -> ConnectionTestResult.Unauthorized(classified.httpCode)
              is SubmitResult.TransientFailure -> ConnectionTestResult.Unreachable(classified.reason)
              is SubmitResult.PermanentRejection -> ConnectionTestResult.Unreachable(classified.reason)
          }
      }
  }
  ```
- **MIRROR**: `recentReadings()` (same `resolve`/`execute`/query-param shape), RESPONSE_CLASSIFICATION_REUSE
- **IMPORTS**: none new (`ResponseClassifier` already in the same package)
- **GOTCHA**: Do not follow `recentReadings()`'s habit of collapsing every non-2xx into `Unavailable` — this method's whole purpose is to distinguish "bad token" from "unreachable," so 401/403 must map to `Unauthorized`, not `Unreachable`.
- **VALIDATE**: `./gradlew :app:compileDebugKotlin`

### Task 3: Wire `ConfigViewModel`
- **ACTION**: Edit `ConfigViewModel.kt`
- **IMPLEMENT**:
  ```kotlin
  sealed interface ConnectionTestUiState {
      data object Idle : ConnectionTestUiState
      data object Testing : ConnectionTestUiState
      data object Success : ConnectionTestUiState
      data class Failure(val message: String) : ConnectionTestUiState
  }
  ```
  Add `connectionTest: ConnectionTestUiState = ConnectionTestUiState.Idle` to `ConfigUiState`.
  Add constructor param:
  ```kotlin
  private val apiFactory: (baseUrl: String) -> VitalForgeApi = { baseUrl ->
      VitalForgeHttpClient(baseUrl, authTokenStore::token, ContractVersion.V1_WEIGHT_ONLY, V1Shaper)
  },
  ```
  Add `_connectionTest` and fold into the `combine()` (now 5 flows). Add:
  ```kotlin
  fun testConnection() {
      if (_connectionTest.value == ConnectionTestUiState.Testing) return
      _connectionTest.value = ConnectionTestUiState.Testing
      viewModelScope.launch {
          _connectionTest.value = when (val result = apiFactory(uiState.value.baseUrl).testConnection()) {
              ConnectionTestResult.Authorized -> ConnectionTestUiState.Success
              is ConnectionTestResult.Unauthorized ->
                  ConnectionTestUiState.Failure("Server rejected the token (HTTP ${result.httpCode})")
              is ConnectionTestResult.Unreachable -> ConnectionTestUiState.Failure(result.reason)
          }
      }
  }
  ```
- **MIRROR**: VIEWMODEL_ONE_SHOT_STATE_FIELD, VIEWMODEL_COMBINE_INTO_UISTATE, `ManualEntryViewModel.save()`'s `isSaving`-guard-against-double-tap pattern
- **IMPORTS**: `com.ventouxlabs.bascule.network.VitalForgeApi`, `VitalForgeHttpClient`, `ConnectionTestResult`, `V1Shaper`
- **GOTCHA**: The shaper/contract passed to the factory are unused by `testConnection()` (it never calls `shape()`) — hardcoding `V1_WEIGHT_ONLY`/`V1Shaper` here is deliberate, not a bug; do not wire the user's saved `contractVersion` through for this call, that would be over-engineering a value nothing reads.
- **VALIDATE**: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --tests "*ConfigViewModelTest*"`

### Task 4: Add the button to `ConfigScreen`
- **ACTION**: Edit `ConfigScreen.kt`
- **IMPLEMENT**: Extend `ConnectionSection` with an `onTestConnection: () -> Unit` param, an `OutlinedButton` next to Save (`enabled = state.tokenIsSet && state.baseUrl.isNotBlank() && state.connectionTest != ConnectionTestUiState.Testing`), and a result `Text` below via an exhaustive `when` on `state.connectionTest` (no `else` branch, matching this file's existing `ReadingRow` exhaustive-`when` convention).
- **MIRROR**: Existing `TokenSection`'s `Row` of two buttons; `HistoryScreen.kt`'s exhaustive-`when`-on-sealed-status pattern
- **IMPORTS**: none new (all Compose imports already present)
- **GOTCHA**: Update the `ConfigScreen` call site too — `ConnectionSection(...)` needs the new `onTestConnection = viewModel::testConnection` argument.
- **VALIDATE**: `./gradlew :app:compileDebugKotlin :app:lintDebug`

### Task 5: Contract tests for `VitalForgeHttpClient.testConnection()`
- **ACTION**: Edit `VitalForgeHttpClientTest.kt`
- **IMPLEMENT**: Add tests: `testConnectionIsAuthorizedOn2xx`, `testConnectionIsUnauthorizedOn401`, `testConnectionIsUnauthorizedOn403`, `testConnectionIsUnreachableOnSocketHangUp`, `testConnectionUsesGetNotPost`, `testConnectionSendsAuthorizationHeader`, `testConnectionHitsRecentPathNotWeightPath` (proves it never risks a fake reading).
- **MIRROR**: CONTRACT_TEST_STRUCTURE, existing `recentReadingsParsesTheContentionCheckResponse` / `socketHangUpIsTransient` tests
- **IMPORTS**: none new
- **GOTCHA**: Reuse the existing `client()` helper and `TOKEN` constant already in this file — do not invent a second token constant.
- **VALIDATE**: `./gradlew :app:testDebugUnitTest --tests "*VitalForgeHttpClientTest*"`

### Task 6: `FakeVitalForgeApi` + `ConfigViewModel` tests
- **ACTION**: CREATE `FakeVitalForgeApi.kt`; UPDATE `ConfigViewModelTest.kt`
- **IMPLEMENT**:
  ```kotlin
  class FakeVitalForgeApi(
      private var connectionResult: ConnectionTestResult = ConnectionTestResult.Authorized,
  ) : VitalForgeApi {
      override val contract = ContractVersion.V1_WEIGHT_ONLY
      var lastBaseUrl: String? = null
      override suspend fun submitReading(reading: ReadingEntity, unit: WeightUnit): SubmitResult =
          error("not used by ConfigViewModel tests")
      override suspend fun recentReadings(within: Duration): RecentResult =
          error("not used by ConfigViewModel tests")
      override suspend fun testConnection(): ConnectionTestResult = connectionResult
      fun setResult(result: ConnectionTestResult) { connectionResult = result }
  }
  ```
  Tests: `testConnectionUpdatesUiStateToTestingThenSuccess`, `testConnectionSurfacesUnauthorizedAsAFailureMessage`, `testConnectionSurfacesUnreachableReasonVerbatim`, `secondTapWhileTestingIsIgnored` (mirrors `ManualEntryViewModelTest.saveIgnoresASecondCallWhileTheFirstInsertIsStillInFlight`).
- **MIRROR**: FAKE_OVER_MOCK, `ManualEntryViewModelTest.saveIgnoresASecondCallWhileTheFirstInsertIsStillInFlight`
- **IMPORTS**: `com.ventouxlabs.bascule.network.*`, `kotlin.time.Duration`
- **GOTCHA**: `ConfigViewModel`'s test construction must now pass `apiFactory = { FakeVitalForgeApi(...) }` (or a shared instance captured in the test) — existing `ConfigViewModelTest` construction helper needs updating, not every individual test.
- **VALIDATE**: `./gradlew :app:testDebugUnitTest --tests "*ConfigViewModelTest*"`

---

## Testing Strategy

### Unit Tests

| Test | Input | Expected Output | Edge Case? |
|---|---|---|---|
| `testConnectionIsAuthorizedOn2xx` | MockWebServer 200 | `ConnectionTestResult.Authorized` | No |
| `testConnectionIsUnauthorizedOn401` | MockWebServer 401 | `Unauthorized(401)` | Yes — the whole point of the feature |
| `testConnectionIsUnreachableOnSocketHangUp` | server closed | `Unreachable(...)` | Yes — network failure |
| `testConnectionHitsRecentPathNotWeightPath` | any | request path is `/api/weight/recent`, never `/api/weight` | Yes — proves no fake reading is ever submitted |
| `secondTapWhileTestingIsIgnored` | two `testConnection()` calls before the first resolves | only one `apiFactory` invocation | Yes — double-tap guard |

### Edge Cases Checklist
- [x] Bad token (401/403) — distinct from network failure
- [x] Network failure (socket hang up) — mirrors existing `socketHangUpIsTransient`
- [x] Double-tap while a test is in flight
- [ ] Empty input — N/A, button is disabled when `baseUrl`/`token` are unset, so this state is unreachable by construction
- [ ] Concurrent access — N/A, single-user local UI action

---

## Validation Commands

### Static Analysis
```bash
./gradlew :app:detekt
```
EXPECT: Zero new violations

### Unit Tests
```bash
./gradlew :app:testDebugUnitTest --tests "*VitalForgeHttpClientTest*" --tests "*ConfigViewModelTest*"
```
EXPECT: All pass, including new tests

### Full Test Suite
```bash
./gradlew assembleDebug testDebugUnitTest detekt lint
```
EXPECT: No regressions (baseline: 163/163 passing before this change)

### Manual Validation (this session, on the connected Pixel 9 Pro Fold)
- [ ] Enter `https://health.grepon.cc` as the base URL, Save
- [ ] Enter the real test token (once the user provides it via the dashboard's own login), Save
- [ ] Tap "Test connection" → observe "✓ Connected — token accepted"
- [ ] Clear the token, tap "Test connection" again is impossible (button disabled) — instead verify with a deliberately wrong token that the UI shows "✗ Server rejected the token (HTTP 401)"

---

## Acceptance Criteria
- [ ] All 6 tasks completed
- [ ] All validation commands pass
- [ ] New tests written and passing (contract tests + ViewModel tests)
- [ ] No type errors, no lint/detekt errors
- [ ] Matches UX design (button + result text in the VitalForge server card)
- [ ] Live-verified against `https://health.grepon.cc` once a real token is available

## Completion Checklist
- [ ] Code follows discovered patterns (sealed results, fakes-over-mocks, exhaustive `when`, `flowOn(IO)` for synchronous secure-storage reads)
- [ ] Error handling matches codebase style (never leaks the token or response body into a reason string, per `errorStringNeverContainsTheTokenOrTheResponseBody`)
- [ ] No hardcoded values beyond the existing `RECENT_PATH` constant already in `VitalForgeHttpClient`
- [ ] No unnecessary scope additions (no login flow, no new server endpoint, no CI dependency on a live server)
- [ ] Self-contained — no questions needed during implementation

## Risks
| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Real test token not yet available | Confirmed (blocking) | Live on-device validation step can't run yet | Everything else (Tasks 1-6) is independently completable and testable now; live check runs the moment the user shares the token |
| `combine()` 5-flow overload behaves differently under `SharingStarted.Eagerly` re: recombination cost | Low | Two extra `Dispatchers.IO` reads (`authTokenStore.isSet()`, `consentStore.credentialFor()`) per test tap | Negligible — same cost already paid by the existing `_tokenVersion`/`_consentVersion` bumps |

## Notes
The server was probed directly (with the user's explicit go-ahead, using the URL they supplied) to confirm the request shape this plan relies on:
- `GET /health` → `200 {"status":"ok","service":"vitalforge-dashboard"}`, no auth required (not used by this plan, but confirms the service identity)
- `GET /api/weight/recent?within_seconds=3600` → `401`, `WWW-Authenticate: Bearer`, unauthenticated — confirms the exact endpoint `testConnection()` reuses is live and enforces bearer auth as `VitalForgeApi`'s contract already assumes
- `/auth/login` is a browser session login (302 redirects), separate from the Bearer-token API — confirms tokens are minted via the dashboard's own user-auth flow, not by this app, which is why "no login flow in the app" is explicitly out of scope above
