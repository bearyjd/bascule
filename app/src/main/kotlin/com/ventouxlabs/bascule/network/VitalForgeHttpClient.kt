package com.ventouxlabs.bascule.network

import com.ventouxlabs.bascule.data.ReadingEntity
import com.ventouxlabs.bascule.data.WeightUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

/**
 * OkHttp implementation of [VitalForgeApi] with the response hardening of
 * 00-design.md §4.5 and §8.7.
 *
 * [tokenProvider] is a function rather than a stored string so the token is read
 * from `AuthTokenStore` at call time and never held in a field that could reach
 * a heap dump or a `toString()`.
 */
class VitalForgeHttpClient(
    private val baseUrl: String,
    private val tokenProvider: () -> String?,
    override val contract: ContractVersion,
    private val shaper: ReadingPayloadShaper,
    client: OkHttpClient = defaultClient(),
    /** The `vf_session` cookie value — an alternative, mutually exclusive credential to [tokenProvider]. */
    private val sessionCookieProvider: () -> String? = { null },
) : VitalForgeApi {

    private val client = client.newBuilder()
        // Following a redirect can send the bearer token to another host
        // (00-design.md §8.7); a moved endpoint is a config error instead.
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /**
     * Token first, matching `shared/auth.py`'s own `get_current_user()`
     * precedence — the app enforces mutual exclusivity so only one credential
     * is ever actually set, but this order stays correct even if that
     * invariant is ever violated.
     */
    private fun Request.Builder.applyCredential(): Request.Builder = apply {
        val token = tokenProvider()
        val cookie = sessionCookieProvider()
        when {
            token != null -> header("Authorization", "Bearer $token")
            cookie != null -> header("Cookie", "$SESSION_COOKIE_NAME=$cookie")
        }
    }

    override suspend fun submitReading(reading: ReadingEntity, unit: WeightUnit): SubmitResult {
        val payload = shaper.shape(reading, unit)
        val url = resolve(WEIGHT_PATH)
            // Transient, not permanent: this is a local configuration problem,
            // not a statement from the server about this reading. Classifying
            // it as PermanentRejection marks the row FAILED_PERMANENT on the
            // first attempt for a cause the server never weighed in on — the
            // row is recoverable the moment the base URL is fixed, which a
            // permanent failure does not communicate.
            ?: return SubmitResult.TransientFailure("base URL is not a valid http(s) URL", null)

        val request = Request.Builder()
            .url(url)
            .post(payload.json.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Content-Type", JSON_CONTENT_TYPE)
            .applyCredential()
            .build()

        return execute(
            request = request,
            onFailure = { SubmitResult.TransientFailure(it, null) },
        ) { response ->
            // The status code alone decides. A 2xx means the server already
            // committed the write; an unreadable or oversized response body does
            // not un-commit it, and downgrading to TransientFailure would leave
            // the row PENDING and resubmit it, duplicating the reading remotely.
            ResponseClassifier.classify(
                httpCode = response.code,
                deliveredFields = payload.fields,
                retryAfterHeader = response.header("Retry-After"),
            )
        }
    }

    override suspend fun recentReadings(within: Duration): RecentResult {
        val url = resolve(RECENT_PATH)?.newBuilder()
            ?.addQueryParameter("within_seconds", within.inWholeSeconds.toString())
            ?.build()
            ?: return RecentResult.Unavailable("base URL is not a valid http(s) URL")

        val request = Request.Builder()
            .url(url)
            .get()
            .applyCredential()
            .build()

        return execute(request, onFailure = { RecentResult.Unavailable(it) }) { response ->
            if (!response.isSuccessful || response.bodyExceedsCap()) {
                return@execute RecentResult.Unavailable("server returned ${response.code}")
            }
            parseRecent(response.body.string())
        }
    }

    override suspend fun testConnection(): ConnectionTestResult {
        val url = resolve(RECENT_PATH)?.newBuilder()
            ?.addQueryParameter("within_seconds", TEST_CONNECTION_WINDOW_SECONDS)
            ?.build()
            ?: return ConnectionTestResult.Unreachable("base URL is not a valid http(s) URL")

        val request = Request.Builder()
            .url(url)
            .get()
            .applyCredential()
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

    override suspend fun login(username: String, password: String): LoginResult {
        val url = resolve(LOGIN_PATH) ?: return LoginResult.Unreachable("base URL is not a valid http(s) URL")
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
                // Checked before isSuccessful: 401 is also unsuccessful, and checking
                // success first would misclassify invalid credentials as unreachable.
                response.code == HTTP_UNAUTHORIZED -> LoginResult.InvalidCredentials
                !response.isSuccessful -> LoginResult.Unreachable("server returned ${response.code}")
                else -> {
                    val setCookie = response.headers("Set-Cookie")
                        .firstOrNull { it.startsWith("$SESSION_COOKIE_NAME=") }
                    val cookieValue = setCookie?.let { Cookie.parse(url, it) }?.value
                    cookieValue?.let { LoginResult.Success(it) }
                        ?: LoginResult.Unreachable("no session cookie in response")
                }
            }
        }
    }

    private fun parseRecent(body: String): RecentResult {
        val element = LENIENT_JSON.parseToJsonElement(body)
        val array = (element as? JsonArray)
            ?: (element as? JsonObject)?.get("readings") as? JsonArray
            ?: return RecentResult.Unavailable("unrecognised response shape")

        val readings = array.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val weight = obj["weight_kg"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val at = obj["captured_at"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            // captured_at is server-controlled input compared via DedupPolicy's
            // abs(timeA - timeB): at exactly Long.MIN_VALUE, abs() overflows back
            // to a negative number and the tolerance check passes unconditionally
            // regardless of how far apart the two timestamps actually are.
            // Bounding to a plausible epoch range here, at the trust boundary,
            // keeps every downstream subtraction well inside Long's range.
            if (at !in PLAUSIBLE_EPOCH_MILLIS_RANGE) return@mapNotNull null
            RemoteReading(weight, at)
        }
        return RecentResult.Readings(readings)
    }

    /**
     * Runs the call off the main thread and guarantees no throwable escapes: a
     * malformed or hostile response may delay a delivery, never crash the app
     * (00-design.md §8.7).
     *
     * Cancellation is the one exception and is rethrown. A `DeliveryWorker`
     * stopped by WorkManager mid-submit made no attempt the server ever saw;
     * recording it as a transient failure would burn an `attemptCount` and
     * inflate the §3.4 backoff exponent for an attempt that never happened.
     */
    private suspend fun <T> execute(
        request: Request,
        onFailure: (String) -> T,
        handle: (Response) -> T,
    ): T = withContext(Dispatchers.IO) {
        runCatching { client.newCall(request).execute().use(handle) }
            .getOrElse { throwable ->
                if (throwable is CancellationException) throw throwable
                onFailure(
                    when (throwable) {
                        is IOException -> "network error"
                        else -> "unreadable response"
                    },
                )
            }
    }

    /**
     * Peeks one byte past the cap. The body is never buffered whole, so an
     * endless response cannot exhaust memory.
     */
    private fun Response.bodyExceedsCap(): Boolean {
        val source = body.source()
        source.request(MAX_BODY_BYTES + 1)
        return source.buffer.size > MAX_BODY_BYTES
    }

    private fun resolve(path: String): HttpUrl? =
        baseUrl.trimEnd('/').plus(path).toHttpUrlOrNull()

    companion object {
        const val WEIGHT_PATH = "/api/weight"
        const val RECENT_PATH = "/api/weight/recent"
        const val LOGIN_PATH = "/auth/login"

        /** Must match `shared/auth.py`'s `_COOKIE_NAME` exactly — a mismatch fails silently, not with an error. */
        const val SESSION_COOKIE_NAME = "vf_session"

        private const val HTTP_UNAUTHORIZED = 401

        /**
         * A remote `captured_at` outside this range cannot be a real weigh-in and
         * is rejected rather than compared — see [parseRecent].
         */
        private val PLAUSIBLE_EPOCH_MILLIS_RANGE = 946_684_800_000L..4_102_444_800_000L // 2000-01-01 .. 2100-01-01

        /** Arbitrary and unused by callers — `testConnection()` only reads the status code, never the body. */
        private const val TEST_CONNECTION_WINDOW_SECONDS = "60"

        /** 00-design.md §8.7. */
        const val MAX_BODY_BYTES = 64L * 1024

        private const val JSON_CONTENT_TYPE = "application/json"
        private val JSON_MEDIA_TYPE = JSON_CONTENT_TYPE.toMediaType()

        private val LENIENT_JSON = Json { ignoreUnknownKeys = true; isLenient = true }

        private const val CONNECT_TIMEOUT_SECONDS = 15L
        private const val READ_TIMEOUT_SECONDS = 10L
        private const val WRITE_TIMEOUT_SECONDS = 10L

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
    }
}
