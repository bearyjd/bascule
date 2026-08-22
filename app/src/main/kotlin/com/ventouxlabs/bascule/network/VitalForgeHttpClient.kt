package com.ventouxlabs.bascule.network

import com.ventouxlabs.bascule.data.ReadingEntity
import com.ventouxlabs.bascule.data.WeightUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
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
) : VitalForgeApi {

    private val client = client.newBuilder()
        // Following a redirect can send the bearer token to another host
        // (00-design.md §8.7); a moved endpoint is a config error instead.
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    override suspend fun submitReading(reading: ReadingEntity, unit: WeightUnit): SubmitResult {
        val payload = shaper.shape(reading, unit)
        val url = resolve(WEIGHT_PATH)
            ?: return SubmitResult.PermanentRejection(0, "base URL is not a valid http(s) URL")

        val request = Request.Builder()
            .url(url)
            .post(payload.json.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Content-Type", JSON_CONTENT_TYPE)
            .apply { tokenProvider()?.let { header("Authorization", "Bearer $it") } }
            .build()

        return execute(
            request = request,
            onFailure = { SubmitResult.TransientFailure(it, null) },
        ) { response ->
            val classified = ResponseClassifier.classify(
                httpCode = response.code,
                deliveredFields = payload.fields,
                retryAfterHeader = response.header("Retry-After"),
            )
            // The status code is authoritative: a 401 with an oversized body is
            // still an auth rejection and must still pause the drain. The cap
            // only downgrades a response we would otherwise have accepted,
            // because a success we could not read is not a success.
            if (classified is SubmitResult.Accepted && response.bodyExceedsCap()) {
                SubmitResult.TransientFailure(OVERSIZED_BODY_REASON, null)
            } else {
                classified
            }
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
            .apply { tokenProvider()?.let { header("Authorization", "Bearer $it") } }
            .build()

        return execute(request, onFailure = { RecentResult.Unavailable(it) }) { response ->
            if (!response.isSuccessful || response.bodyExceedsCap()) {
                return@execute RecentResult.Unavailable("server returned ${response.code}")
            }
            parseRecent(response.body.string())
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
            RemoteReading(weight, at)
        }
        return RecentResult.Readings(readings)
    }

    /**
     * Runs the call off the main thread and guarantees no throwable escapes: a
     * malformed or hostile response may delay a delivery, never crash the app
     * (00-design.md §8.7).
     */
    private suspend fun <T> execute(
        request: Request,
        onFailure: (String) -> T,
        handle: (Response) -> T,
    ): T = withContext(Dispatchers.IO) {
        runCatching { client.newCall(request).execute().use(handle) }
            .getOrElse { throwable ->
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

        /** 00-design.md §8.7. */
        const val MAX_BODY_BYTES = 64L * 1024
        const val OVERSIZED_BODY_REASON = "response body exceeded 64 KiB cap"

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
