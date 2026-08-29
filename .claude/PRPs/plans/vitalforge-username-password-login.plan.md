# Plan: Username/password login as an alternative to a pasted VitalForge token

## Summary
Add a second, mutually-exclusive way to authenticate Bascule against VitalForge: a username/password login that exchanges credentials for a session cookie (`vf_session`), stored and sent exactly like the existing bearer token is today. No new server endpoint is needed — VitalForge's own auth middleware already accepts either credential type on the same `/api/*` routes.

## User Story
As a Bascule user, I want to sign in with my VitalForge username and password instead of hunting down and pasting a raw API token, so that connecting the app is as easy as logging into any other service.

## Problem → Solution
Today `ConfigScreen`'s "VitalForge token" card only accepts a pre-generated bearer token pasted in by hand. → A "VitalForge credentials" card offers two paths — "Use a token" (existing flow) or "Log in" (new) — and whichever one succeeds becomes the app's single active credential; setting one always clears the other.

## Metadata
- **Complexity**: Large
- **Source PRD**: N/A (ad hoc user request via `/prp-plan`)
- **PRD Phase**: N/A
- **Estimated Files**: 9 (2 new, 7 updated)

---

## Ground Truth: How VitalForge Auth Actually Works

Read directly from the real server source at `/var/home/user/Documents/vibe-code/vitalforge/shared/auth.py` (a sibling project on this machine — **not** part of this repo, read-only reference) plus black-box probing of `https://health.grepon.cc`:

```python
# shared/auth.py:14-19
_SECRET = os.environ.get("VITALFORGE_SECRET", "default-dev-secret")
_USER = os.environ.get("VITALFORGE_USER", "admin")
_PASS = os.environ.get("VITALFORGE_PASS", "")
_API_TOKEN = os.environ.get("VITALFORGE_API_TOKEN", "").strip()
_COOKIE_NAME = "vf_session"
_MAX_AGE = 30 * 24 * 3600  # 30 days
```

```python
# shared/auth.py:51-59 — the key fact: EITHER credential authenticates the SAME routes
def get_current_user(request: Request) -> str | None:
    if not _is_auth_configured():
        return "anonymous"
    if _bearer_token_valid(request):   # Authorization: Bearer <token> header
        return "api-token"
    cookie = request.cookies.get(_COOKIE_NAME)   # Cookie: vf_session=<value> header
    if not cookie:
        return None
    return validate_session(cookie)
```

```python
# shared/auth.py:184-194 — POST /auth/login: JSON body in, Set-Cookie out, NOT a token
@app.post("/auth/login")
async def login(request: Request):
    body = await request.json()
    username = body.get("username", "")
    password = body.get("password", "")
    if not check_credentials(username, password):
        raise HTTPException(status_code=401, detail="Invalid credentials")
    cookie = create_session_cookie(username)
    response = JSONResponse({"success": True})
    response.set_cookie(_COOKIE_NAME, cookie, max_age=_MAX_AGE, httponly=True, samesite="lax")
    return response
```

**What this rules out**: there is no per-user token-minting endpoint to build toward — `VITALFORGE_API_TOKEN` is one static, server-wide secret, unrelated to any individual login. A "login to get a token" design is not implementable against this server.

**What this makes possible**: the session cookie *is* a real, independent credential the same `/api/*` routes already accept. Bascule can authenticate with it directly — as a `Cookie` header, never as a `Bearer` value (the two are not interchangeable strings; `_bearer_token_valid` and `validate_session` are separate code paths with separate formats).

`/auth/login` and `/auth/logout` are exempted from the auth middleware itself (`path.startswith("/auth/")`), so no existing credential is needed to call login.

## User Decisions (already resolved, do not re-ask)
1. **Mutually exclusive.** Saving a token clears any stored session cookie; a successful login clears any stored token. Exactly one credential is ever active.
2. **Cookie only, never the password.** The raw username/password is never persisted — only the resulting session cookie, in `EncryptedSharedPreferences`, exactly like the token today. An expired/rejected cookie requires the user to log in again through the UI (same UX shape as a rejected token today).

---

## UX Design

### Before
```
┌──────────────────────────────┐
│ VitalForge token               │
│ Token is not set               │
│ [ Set token ]                  │
└──────────────────────────────┘
```

### After
```
┌──────────────────────────────┐
│ VitalForge credentials         │
│ Not signed in                  │
│ [ Use a token ]  [ Log in ]    │
└──────────────────────────────┘
        │ tap "Log in"
        ▼
┌──────────────────────────────┐
│ VitalForge credentials         │
│ Not signed in                  │
│ [ Username______________ ]     │
│ [ Password______________ ]     │
│ [ Sign in ]  [ Cancel ]         │
└──────────────────────────────┘
        │ success
        ▼
┌──────────────────────────────┐
│ VitalForge credentials         │
│ Signed in via username/password│
│ [ Use a token ]  [ Log in ]  [ Clear ] │
└──────────────────────────────┘
```

### Interaction Changes
| Touchpoint | Before | After | Notes |
|---|---|---|---|
| Config → token card | "Set token"/"Replace token"/"Clear" | Renamed "VitalForge credentials"; "Use a token" and "Log in" both available; "Clear" removes whichever is active | Token sub-flow UI unchanged, just relocated under the new mode switch |
| Config → token card (new) | N/A | Username + password fields, "Sign in", inline error text on 401/network failure | Mirrors `ManualEntryScreen`'s save-then-dismiss pattern via a one-shot `loginSucceeded` event |

---

## Mandatory Reading

| Priority | File | Lines | Why |
|---|---|---|---|
| P0 | `/var/home/user/Documents/vibe-code/vitalforge/shared/auth.py` | 1-223 | Ground truth for the server's actual auth contract — read this before touching any Kotlin |
| P0 | `app/src/main/kotlin/com/ventouxlabs/bascule/network/AuthTokenStore.kt` | 1-65 | Exact interface/impl shape `SessionCookieStore` mirrors |
| P0 | `app/src/main/kotlin/com/ventouxlabs/bascule/network/VitalForgeHttpClient.kt` | 1-200 (post `testConnection()`) | Where `applyCredential()` and `login()` are added; every existing `.apply { tokenProvider()... }` call site must switch to it |
| P0 | `app/src/main/kotlin/com/ventouxlabs/bascule/ui/ConfigViewModel.kt` | 1-236 (post connection-test work) | `_tokenVersion` becomes `_credentialVersion`; `saveToken`/`clearToken` gain mutual-exclusion clearing; `login()` is added |
| P1 | `app/src/main/kotlin/com/ventouxlabs/bascule/ui/ManualEntryViewModel.kt` | all | `savedEvents: SharedFlow<Unit>` — exact pattern `loginSucceeded` mirrors for a one-shot "dismiss the form" signal |
| P1 | `app/src/main/kotlin/com/ventouxlabs/bascule/ui/ConfigScreen.kt` | `TokenSection` (currently ~260-303) | Being replaced by `CredentialsSection` with a `CredentialEditMode` local state machine |
| P1 | `app/src/main/kotlin/com/ventouxlabs/bascule/BasculeApplication.kt` | all | New `sessionCookieStore` lazy property, mirroring `authTokenStore` |
| P2 | `app/src/test/kotlin/com/ventouxlabs/bascule/network/VitalForgeHttpClientTest.kt` | all | Test structure to mirror for `login()` and credential-precedence tests |
| P2 | `app/src/test/kotlin/com/ventouxlabs/bascule/ui/fake/FakeAuthTokenStore.kt` | all | Pattern to mirror for `FakeSessionCookieStore` |

## External Documentation
No external research needed — the exact request/response contract came from reading the real server's source directly (see Ground Truth above), which is strictly more reliable than API docs.

---

## Patterns to Mirror

### CREDENTIAL_STORE_INTERFACE
```kotlin
// SOURCE: network/AuthTokenStore.kt:19-27
interface AuthTokenStore {
    fun isSet(): Boolean
    fun token(): String?
    fun save(token: String)
    fun clear()
}
```

### ENCRYPTED_STORE_IMPL
```kotlin
// SOURCE: network/AuthTokenStore.kt:29-51 (own prefs file, own key — never share a file across concerns)
class EncryptedAuthTokenStore(context: Context) : AuthTokenStore {
    private val prefs: SharedPreferences = encryptedPreferences(context, FILE_NAME)
    override fun isSet(): Boolean = !prefs.getString(KEY_TOKEN, null).isNullOrEmpty()
    override fun token(): String? = prefs.getString(KEY_TOKEN, null)
    override fun save(token: String) { prefs.edit().putString(KEY_TOKEN, token).apply() }
    override fun clear() { prefs.edit().remove(KEY_TOKEN).apply() }
    private companion object { const val FILE_NAME = "bascule_auth"; const val KEY_TOKEN = "vitalforge_token" }
}
```

### ONE_SHOT_UI_EVENT
```kotlin
// SOURCE: ui/ManualEntryViewModel.kt — SharedFlow, not a sticky boolean field,
// so it survives Navigation Compose's saveState/restoreState without re-firing
private val _savedEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
val savedEvents: SharedFlow<Unit> = _savedEvents.asSharedFlow()
```

### RESULT_CLASSIFICATION_NEVER_LEAKS_CREDENTIALS
```kotlin
// SOURCE: test/network/VitalForgeHttpClientTest.kt:222-235 — the invariant the new login() must also satisfy
fun errorStringNeverContainsTheTokenOrTheResponseBody() = runBlocking {
    // reason strings are built from status code + fixed phrase only, never response body/headers
}
```

### VIEWMODEL_GUARD_AGAINST_DOUBLE_TAP
```kotlin
// SOURCE: ui/ConfigViewModel.kt testConnection() (added this session)
fun testConnection() {
    if (_connectionTest.value == ConnectionTestUiState.Testing) return
    _connectionTest.value = ConnectionTestUiState.Testing
    viewModelScope.launch { /* ... */ }
}
```

---

## Files to Change

| File | Action | Justification |
|---|---|---|
| `app/src/main/kotlin/com/ventouxlabs/bascule/network/SessionCookieStore.kt` | CREATE | `SessionCookieStore` interface + `EncryptedSessionCookieStore` impl, mirroring `AuthTokenStore` exactly, own prefs file |
| `app/src/main/kotlin/com/ventouxlabs/bascule/network/VitalForgeApi.kt` | UPDATE | Add `LoginResult` sealed interface + `login(username, password)` to the interface |
| `app/src/main/kotlin/com/ventouxlabs/bascule/network/VitalForgeHttpClient.kt` | UPDATE | Add `sessionCookieProvider` constructor param, `applyCredential()` helper (token-first, cookie-fallback — mirrors server precedence), `login()` impl, update `submitReading`/`recentReadings`/`testConnection` to use `applyCredential()` |
| `app/src/main/kotlin/com/ventouxlabs/bascule/ui/ConfigViewModel.kt` | UPDATE | New `sessionCookieStore` dependency, `sessionIsSet`/`loginError`/`isLoggingIn` state, `_credentialVersion` (renamed from `_tokenVersion`, now bumped by both token and login flows), `login()`, `clearCredentials()` (renamed from `clearToken()`, clears both stores), `loginSucceeded: SharedFlow<Unit>` |
| `app/src/main/kotlin/com/ventouxlabs/bascule/ui/ConfigScreen.kt` | UPDATE | Replace `TokenSection` with `CredentialsSection` (mode switch: token entry vs. login form) |
| `app/src/main/kotlin/com/ventouxlabs/bascule/BasculeApplication.kt` | UPDATE | Add `sessionCookieStore: SessionCookieStore by lazy { EncryptedSessionCookieStore(this) }` |
| `app/src/test/kotlin/com/ventouxlabs/bascule/ui/fake/FakeSessionCookieStore.kt` | CREATE | In-memory fake mirroring `FakeAuthTokenStore` |
| `app/src/test/kotlin/com/ventouxlabs/bascule/ui/fake/FakeVitalForgeApi.kt` | UPDATE | Add `login()` stub (configurable `LoginResult`) |
| `app/src/test/kotlin/com/ventouxlabs/bascule/network/VitalForgeHttpClientTest.kt` | UPDATE | Login contract tests + credential-precedence tests |
| `app/src/test/kotlin/com/ventouxlabs/bascule/ui/ConfigViewModelTest.kt` | UPDATE | `login()`/mutual-exclusion tests; update existing `clearToken()` references to `clearCredentials()` |

## NOT Building
- No CookieJar / automatic cookie handling in OkHttp — the session cookie is read once from `Set-Cookie` on login and sent explicitly as a `Cookie` header on every subsequent request, exactly like the token, for the same explicit-control reasons this app already avoids `followRedirects`.
- No password persistence, no auto-relogin on cookie expiry (user decision above).
- No "logged in as `<username>`" display — the username is never stored after login completes, so there is nothing to show beyond a boolean signed-in state (matches the token's own "set"/"not set" display, not a regression).
- No change to `/auth/logout` — nothing server-side needs to be told the app "logged out"; clearing the local cookie is sufficient (the cookie simply stops being sent).
- No live automated CI test against `https://health.grepon.cc` — same rule as the connectivity-test plan: `MockWebServer` only in committed tests; live verification is a manual on-device step in this session.

---

## Step-by-Step Tasks

### Task 1: `SessionCookieStore`
- **ACTION**: CREATE `network/SessionCookieStore.kt`
- **IMPLEMENT**:
  ```kotlin
  interface SessionCookieStore {
      fun isSet(): Boolean
      fun cookie(): String?
      fun save(cookie: String)
      fun clear()
  }

  class EncryptedSessionCookieStore(context: Context) : SessionCookieStore {
      private val prefs: SharedPreferences = encryptedPreferences(context, FILE_NAME)
      override fun isSet(): Boolean = !prefs.getString(KEY_COOKIE, null).isNullOrEmpty()
      override fun cookie(): String? = prefs.getString(KEY_COOKIE, null)
      override fun save(cookie: String) { prefs.edit().putString(KEY_COOKIE, cookie).apply() }
      override fun clear() { prefs.edit().remove(KEY_COOKIE).apply() }
      private companion object { const val FILE_NAME = "bascule_session"; const val KEY_COOKIE = "vitalforge_session_cookie" }
  }
  ```
- **MIRROR**: CREDENTIAL_STORE_INTERFACE, ENCRYPTED_STORE_IMPL
- **IMPORTS**: `android.content.Context`, `android.content.SharedPreferences`, `com.ventouxlabs.bascule.network.encryptedPreferences` (already `internal`, same package — no import needed)
- **GOTCHA**: A separate prefs file from the token, not a second key in the same file — matches how `ConsentStore` and `AuthTokenStore` already each get their own file, so clearing one during testing/debugging never touches the other.
- **VALIDATE**: `./gradlew :app:compileDebugKotlin`

### Task 2: `LoginResult` + interface method
- **ACTION**: UPDATE `network/VitalForgeApi.kt`
- **IMPLEMENT**:
  ```kotlin
  sealed interface LoginResult {
      /** The session cookie's value only — never the raw itsdangerous-signed string's origin, just its value for the `Cookie` header. */
      data class Success(val sessionCookie: String) : LoginResult
      data object InvalidCredentials : LoginResult
      data class Unreachable(val reason: String) : LoginResult
  }
  ```
  Add `suspend fun login(username: String, password: String): LoginResult` to `VitalForgeApi`.
- **MIRROR**: SEALED_RESULT_TYPE (from the connectivity-test plan, same file)
- **GOTCHA**: `VitalForgeApi` again has exactly one implementer — confirm with `grep -rln ": VitalForgeApi\b" app/src` before assuming the interface change is safe (it was true earlier this session; re-check, since `FakeVitalForgeApi` also implements it and must be updated in Task 8).
- **VALIDATE**: `./gradlew :app:compileDebugKotlin` (fails until Task 3 and Task 8 — expected)

### Task 3: `login()` + `applyCredential()` in `VitalForgeHttpClient`
- **ACTION**: UPDATE `network/VitalForgeHttpClient.kt`
- **IMPLEMENT**:
  ```kotlin
  class VitalForgeHttpClient(
      private val baseUrl: String,
      private val tokenProvider: () -> String?,
      override val contract: ContractVersion,
      private val shaper: ReadingPayloadShaper,
      client: OkHttpClient = defaultClient(),
      private val sessionCookieProvider: () -> String? = { null },
  ) : VitalForgeApi {
      // ...
      private fun Request.Builder.applyCredential(): Request.Builder = apply {
          val token = tokenProvider()
          val cookie = sessionCookieProvider()
          when {
              // Token first, matching shared/auth.py's own get_current_user() precedence —
              // the app enforces mutual exclusivity so only one is ever actually set, but
              // this order stays correct even if that invariant is ever violated.
              token != null -> header("Authorization", "Bearer $token")
              cookie != null -> header("Cookie", "$SESSION_COOKIE_NAME=$cookie")
          }
      }

      override suspend fun login(username: String, password: String): LoginResult {
          val url = resolve(LOGIN_PATH)
              ?: return LoginResult.Unreachable("base URL is not a valid http(s) URL")
          val body = buildJsonObject {
              put("username", JsonPrimitive(username))
              put("password", JsonPrimitive(password))
          }
          val request = Request.Builder()
              .url(url)
              .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
              .header("Content-Type", JSON_CONTENT_TYPE)
              .build()

          return execute(request, onFailure = { LoginResult.Unreachable(it) }) { response ->
              when {
                  response.code == 401 -> LoginResult.InvalidCredentials
                  !response.isSuccessful -> LoginResult.Unreachable("server returned ${response.code}")
                  else -> {
                      val setCookie = response.headers("Set-Cookie")
                          .firstOrNull { it.startsWith("$SESSION_COOKIE_NAME=") }
                      val cookieValue = setCookie?.let { okhttp3.Cookie.parse(url, it) }?.value
                      cookieValue?.let { LoginResult.Success(it) }
                          ?: LoginResult.Unreachable("no session cookie in response")
                  }
              }
          }
      }
  ```
  Replace every `.apply { tokenProvider()?.let { header("Authorization", "Bearer $it") } }` in `submitReading`, `recentReadings`, and `testConnection` with `.applyCredential()`.
  Add constants: `LOGIN_PATH = "/auth/login"`, `SESSION_COOKIE_NAME = "vf_session"` (must match `shared/auth.py:18`'s `_COOKIE_NAME` exactly).
- **MIRROR**: existing `submitReading`/`resolve`/`execute` structure; `buildJsonObject` usage from `ReadingPayloadShaper.kt`
- **IMPORTS**: `kotlinx.serialization.json.buildJsonObject`, `kotlinx.serialization.json.JsonPrimitive` (already imported via `Json`? check — `VitalForgeHttpClient.kt` currently imports `kotlinx.serialization.json.Json` and a few `JsonObject`/`JsonArray` members, add `buildJsonObject`/`JsonPrimitive`), `okhttp3.Cookie`
- **GOTCHA**: `followRedirects(false)`/`followSslRedirects(false)` are already set on the client and must stay — the login response is a direct 200/401 JSON body, never a redirect, so this doesn't interact with login at all, but don't "fix" it thinking it might.
- **GOTCHA**: Check 401 *before* `!response.isSuccessful` — 401 is also not successful, and checking success first would misclassify invalid credentials as a generic `Unreachable`.
- **VALIDATE**: `./gradlew :app:compileDebugKotlin`

### Task 4: `ConfigViewModel` — dependency, state, `login()`, mutual exclusion
- **ACTION**: UPDATE `ui/ConfigViewModel.kt`
- **IMPLEMENT**:
  - Add constructor param `private val sessionCookieStore: SessionCookieStore` (positioned with the other stores, before `dao`).
  - Rename `_tokenVersion` → `_credentialVersion`; every place that bumped `_tokenVersion` (currently just `saveToken`/`clearToken`) keeps bumping it, and `login()`/the new `clearCredentials()` bump it too.
  - Add to `ConfigUiState`: `sessionIsSet: Boolean = false`, `loginError: String? = null`, `isLoggingIn: Boolean = false`.
  - Update `apiFactory` default to also wire `sessionCookieProvider = sessionCookieStore::cookie`.
  - Add:
    ```kotlin
    private val _loginError = MutableStateFlow<String?>(null)
    private val _isLoggingIn = MutableStateFlow(false)
    private val _loginSucceeded = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val loginSucceeded: SharedFlow<Unit> = _loginSucceeded.asSharedFlow()

    fun login(username: String, password: String) {
        if (_isLoggingIn.value) return
        val trimmedUser = username.trim()
        val trimmedPass = password.trim()
        if (trimmedUser.isEmpty() || trimmedPass.isEmpty()) {
            _loginError.value = "Enter a username and password"
            return
        }
        _isLoggingIn.value = true
        _loginError.value = null
        viewModelScope.launch {
            when (val result = apiFactory(uiState.value.baseUrl).login(trimmedUser, trimmedPass)) {
                is LoginResult.Success -> {
                    sessionCookieStore.save(result.sessionCookie)
                    authTokenStore.clear()
                    _credentialVersion.value++
                    _loginSucceeded.emit(Unit)
                }
                LoginResult.InvalidCredentials -> _loginError.value = "Invalid username or password"
                is LoginResult.Unreachable -> _loginError.value = result.reason
            }
            _isLoggingIn.value = false
        }
    }

    /** Clears whichever credential is active — mutual exclusion means at most one ever is. */
    fun clearCredentials() {
        authTokenStore.clear()
        sessionCookieStore.clear()
        _credentialVersion.value++
    }
    ```
  - `saveToken()` gains `sessionCookieStore.clear()` alongside its existing `authTokenStore.save(trimmed)`.
  - `combine()` grows to include `sessionCookieStore.isSet()` in its `ConfigUiState(...)` construction (read inside the same `flowOn(ioDispatcher)`-covered block as `authTokenStore.isSet()` — same synchronous-storage-read justification already documented there).
  - Delete the old `clearToken()`; update its call sites (`ConfigScreen.kt`, tests).
- **MIRROR**: ONE_SHOT_UI_EVENT, VIEWMODEL_GUARD_AGAINST_DOUBLE_TAP, existing `saveToken`'s trim-then-blank-check pattern
- **IMPORTS**: `com.ventouxlabs.bascule.network.SessionCookieStore`, `com.ventouxlabs.bascule.network.LoginResult`, `kotlinx.coroutines.flow.MutableSharedFlow`, `kotlinx.coroutines.flow.SharedFlow`, `kotlinx.coroutines.flow.asSharedFlow`
- **GOTCHA**: `_credentialVersion`'s purpose is purely "something changed, recombine" — the combine's lambda still reads `authTokenStore.isSet()` / `sessionCookieStore.isSet()` fresh each time, it does not read the version's own integer value for anything but forcing recombination (mirrors `_tokenVersion`'s original `{ stored, urlError, _, _, connectionTest -> ... }` discard-with-underscore pattern).
- **VALIDATE**: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --tests "*ConfigViewModelTest*"` (test file needs Task 9 first for a clean pass, but should compile)

### Task 5: `BasculeApplication`
- **ACTION**: UPDATE `BasculeApplication.kt`
- **IMPLEMENT**: `val sessionCookieStore: SessionCookieStore by lazy { EncryptedSessionCookieStore(this) }`, and update `ConfigViewModel.factory()`'s construction call to pass it.
- **MIRROR**: existing `authTokenStore`/`consentStore` lazy properties
- **VALIDATE**: `./gradlew :app:compileDebugKotlin`

### Task 6: `ConfigScreen` — `CredentialsSection`
- **ACTION**: UPDATE `ui/ConfigScreen.kt`
- **IMPLEMENT**: Replace `TokenSection` with `CredentialsSection` per the UX Design section above — a local `CredentialEditMode { NONE, TOKEN, LOGIN }` enum, `LaunchedEffect(viewModel) { viewModel.loginSucceeded.collect { mode = NONE; usernameText = ""; passwordText = "" } }`, inline error text under the login form driven by `state.loginError`, `Sign in` button `enabled = !state.isLoggingIn`.
- **MIRROR**: `ManualEntryScreen.kt`'s `LaunchedEffect` + `savedEvents.collect` pattern; existing `TokenSection`'s edit-mode toggle for the token half
- **IMPORTS**: none new (all Compose primitives already imported in this file)
- **GOTCHA**: Update the `ConfigScreen` call site — `TokenSection(...)` becomes `CredentialsSection(state, onSaveToken = viewModel::saveToken, onLogin = viewModel::login, onClearCredentials = viewModel::clearCredentials)`, and the composable must be passed `viewModel` (or `viewModel.loginSucceeded` directly) for the `LaunchedEffect`.
- **VALIDATE**: `./gradlew :app:compileDebugKotlin :app:lintDebug`

### Task 7: `FakeSessionCookieStore`
- **ACTION**: CREATE `test/.../ui/fake/FakeSessionCookieStore.kt`
- **IMPLEMENT**:
  ```kotlin
  class FakeSessionCookieStore(initialCookie: String? = null) : SessionCookieStore {
      private var stored: String? = initialCookie
      override fun isSet(): Boolean = !stored.isNullOrEmpty()
      override fun cookie(): String? = stored
      override fun save(cookie: String) { stored = cookie }
      override fun clear() { stored = null }
  }
  ```
- **MIRROR**: `FakeAuthTokenStore.kt` verbatim structure
- **VALIDATE**: `./gradlew :app:compileDebugKotlin`

### Task 8: `FakeVitalForgeApi.login()`
- **ACTION**: UPDATE `test/.../ui/fake/FakeVitalForgeApi.kt`
- **IMPLEMENT**: Add a settable `loginResult: LoginResult` field (default `LoginResult.Success("fake-session-cookie")`), `override suspend fun login(username: String, password: String): LoginResult = loginResult`, and a `setLoginResult(result: LoginResult)` mutator mirroring the existing `setResult`/`connectionResult` pattern.
- **MIRROR**: existing `connectionResult`/`setResult` on the same class
- **VALIDATE**: `./gradlew :app:compileDebugKotlin`

### Task 9: Tests
- **ACTION**: UPDATE `VitalForgeHttpClientTest.kt` and `ConfigViewModelTest.kt`
- **IMPLEMENT** (`VitalForgeHttpClientTest`):
  - `loginPostsUsernameAndPasswordAsJson`
  - `loginExtractsTheSessionCookieValueFromSetCookie`
  - `loginIsInvalidCredentialsOn401`
  - `loginIsUnreachableOnSocketHangUp`
  - `loginErrorStringNeverContainsThePassword` (mirrors `errorStringNeverContainsTheTokenOrTheResponseBody`)
  - `submitReadingPrefersBearerTokenWhenBothCredentialsAreConfigured` (proves the `applyCredential()` precedence, using a `client()` overload that sets both a token and a session cookie)
  - `submitReadingFallsBackToSessionCookieWhenNoTokenIsConfigured` (asserts the outgoing `Cookie` header is exactly `vf_session=<value>`)
- **IMPLEMENT** (`ConfigViewModelTest`):
  - `loginSavesTheSessionCookieAndClearsAnyStoredToken`
  - `loginClearsAnyPreviouslyStoredTokenEvenOnFirstLogin` (mutual exclusion, empty-token case too)
  - `savingATokenClearsAnyStoredSessionCookie` (mutual exclusion, the other direction)
  - `loginSurfacesInvalidCredentialsAsAFailureMessage`
  - `loginRejectsBlankUsernameOrPassword`
  - `secondLoginTapWhileInFlightIsIgnored`
  - `clearCredentialsClearsBothStores`
  - Update every existing `vm.clearToken()` reference to `vm.clearCredentials()`.
- **MIRROR**: CONTRACT_TEST_STRUCTURE and FAKE_OVER_MOCK (from the connectivity-test plan), `errorStringNeverContainsTheTokenOrTheResponseBody`
- **VALIDATE**: `./gradlew :app:testDebugUnitTest --tests "*VitalForgeHttpClientTest*" --tests "*ConfigViewModelTest*"`

---

## Testing Strategy

### Unit Tests
| Test | Input | Expected Output | Edge Case? |
|---|---|---|---|
| `loginExtractsTheSessionCookieValueFromSetCookie` | `Set-Cookie: vf_session=abc123; HttpOnly; SameSite=Lax` | `LoginResult.Success("abc123")` | No — the core mechanism |
| `loginIsInvalidCredentialsOn401` | MockWebServer 401 | `LoginResult.InvalidCredentials`, distinct from `Unreachable` | Yes |
| `submitReadingPrefersBearerTokenWhenBothCredentialsAreConfigured` | both token and cookie set | `Authorization: Bearer` header sent, no `Cookie` header | Yes — proves precedence even though the app itself should never produce this state |
| `savingATokenClearsAnyStoredSessionCookie` | login succeeds, then `saveToken()` is called | `sessionCookieStore.isSet() == false` afterward | Yes — the whole point of mutual exclusion |
| `loginErrorStringNeverContainsThePassword` | 422 response echoing the password in its body | failure message never contains the password | Yes — security invariant |

### Edge Cases Checklist
- [x] Invalid credentials (401) — distinct from unreachable
- [x] Network failure — mirrors `socketHangUpIsTransient`
- [x] Both credentials configured at once (defensive precedence, even though unreachable via the UI)
- [x] Blank username/password
- [x] Double-tap while logging in
- [x] Credential never leaked into an error string

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
EXPECT: No regressions (baseline: 175/175 passing before this change)

### Manual Validation (this session, on the connected Pixel 9 Pro Fold)
- [ ] Tap "Log in", enter a deliberately wrong password → observe "Invalid username or password"
- [ ] Enter the real VitalForge username/password (once available) → observe "Signed in via username/password"
- [ ] Tap "Test connection" (from the earlier connectivity-test work) → observe "✓ Connected — token accepted" while authenticated via the *cookie*, not a token — proving `applyCredential()`'s fallback path really works end-to-end
- [ ] Tap "Use a token" and save any token → observe the credentials card now reads "Signed in with an API token" and a fresh `cookie()` read returns null

---

## Acceptance Criteria
- [ ] All 9 tasks completed
- [ ] All validation commands pass
- [ ] Mutual exclusion holds in both directions, proven by tests, not just by reading the code
- [ ] No type errors, no lint/detekt errors
- [ ] Matches UX design
- [ ] Live-verified against `https://health.grepon.cc` once real credentials are available

## Completion Checklist
- [ ] Code follows discovered patterns (sealed results, fakes-over-mocks, one-shot `SharedFlow` events, exhaustive `when`)
- [ ] Password never persisted, never logged, never present in an error string
- [ ] `SESSION_COOKIE_NAME` matches the server's `_COOKIE_NAME` exactly (`vf_session`) — a mismatch here fails silently (cookie sent under the wrong name, server just doesn't recognize it, no error to debug from)
- [ ] No unnecessary scope additions (no CookieJar, no auto-relogin, no username display, no server-side logout call)
- [ ] Self-contained — no questions needed during implementation

## Risks
| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Real login credentials still not available | Confirmed (blocking) | Live on-device validation of the *actual* server round-trip can't run yet | Everything else (Tasks 1-9) is independently completable and testable against MockWebServer now; live check runs the moment credentials are available |
| `Cookie.parse(url, setCookieHeader)` behaves unexpectedly for `itsdangerous`-signed cookie values (they contain `.` and base64url characters) | Low | `login()` misclassifies a real success as `Unreachable("no session cookie in response")` | Covered by `loginExtractsTheSessionCookieValueFromSetCookie` using a realistic-shaped signed-cookie value in the mock response, not a trivial `abc123` |
| Detekt `MaxLineLength`/function-length on the now-larger `ConfigViewModel.kt`/`ConfigScreen.kt` | Medium (hit twice already this session) | Build failure | Run `./gradlew :app:detekt` after each task, not just at the end |

## Notes
This plan supersedes the "NOT Building" line in the prior connectivity-test plan that read *"No login/auth flow inside the Android app itself... the app only ever accepts a pre-generated bearer token."* That scoping was correct given what was known at the time (only black-box probing, no server source). Reading the actual server source changed the answer: a login flow **is** buildable, just not as "login mints a token" — as "login mints a second, independent credential type."
