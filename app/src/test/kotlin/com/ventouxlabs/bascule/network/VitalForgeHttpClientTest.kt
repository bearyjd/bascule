package com.ventouxlabs.bascule.network

import com.ventouxlabs.bascule.data.WeightUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.OffsetDateTime
import kotlin.time.Duration.Companion.minutes

/**
 * Contract tests for the VitalForge client against a local fake HTTP server.
 *
 * They assert the exact request shape of 00-design.md §4.1 and the response
 * hardening of §4.5 / §8.7 — including the two properties whose absence is data
 * loss or credential leakage: a v1 body of exactly `{"weight","unit"}`, and a
 * redirect that is never followed.
 */
class VitalForgeHttpClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun client(
        contract: ContractVersion = ContractVersion.V1_WEIGHT_ONLY,
        shaper: ReadingPayloadShaper = V1Shaper,
        token: String? = TOKEN,
        sessionCookie: String? = null,
        baseUrl: String = server.url("/").toString().trimEnd('/'),
    ) = VitalForgeHttpClient(
        baseUrl = baseUrl,
        tokenProvider = { token },
        contract = contract,
        shaper = shaper,
        sessionCookieProvider = { sessionCookie },
    )

    private fun ok(body: String = "{}") = MockResponse.Builder().code(200).body(body).build()

    @Test
    fun postsToApiWeightPath() = runBlocking {
        server.enqueue(ok())

        client().submitReading(ReadingFixtures.captured(), WeightUnit.KILOGRAMS)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/weight", request.url.encodedPath)
    }

    @Test
    fun sendsAuthorizationBearerHeaderAndJsonContentType() = runBlocking {
        server.enqueue(ok())

        client().submitReading(ReadingFixtures.captured(), WeightUnit.KILOGRAMS)

        val request = server.takeRequest()
        assertEquals("Bearer $TOKEN", request.headers["Authorization"])
        assertTrue(request.headers["Content-Type"].orEmpty().startsWith("application/json"))
    }

    @Test
    fun v1BodyIsExactlyWeightAndUnit() = runBlocking {
        server.enqueue(ok())

        client().submitReading(ReadingFixtures.captured(), WeightUnit.KILOGRAMS)

        val body = Json.parseToJsonElement(server.takeRequest().body!!.utf8()) as JsonObject
        assertEquals(
            "an unknown field is a 422 against a strict route, and 422 is terminal",
            setOf("weight", "unit"),
            body.keys,
        )
        assertEquals(90.82, body.getValue("weight").jsonPrimitive.doubleOrNull!!, 1e-9)
        assertEquals("kg", body.getValue("unit").jsonPrimitive.content)
    }

    @Test
    fun v1DoesNotSendClientId() = runBlocking {
        server.enqueue(ok())

        client().submitReading(ReadingFixtures.captured(), WeightUnit.KILOGRAMS)

        val body = Json.parseToJsonElement(server.takeRequest().body!!.utf8()) as JsonObject
        assertFalse("client_id is gated on ContractVersion (§4.4)", "client_id" in body.keys)
    }

    @Test
    fun weightIsConvertedFromCanonicalKilogramsToTheDisplayUnit() = runBlocking {
        server.enqueue(ok())

        client().submitReading(ReadingFixtures.captured(), WeightUnit.POUNDS)

        val body = Json.parseToJsonElement(server.takeRequest().body!!.utf8()) as JsonObject
        assertEquals(200.22, body.getValue("weight").jsonPrimitive.doubleOrNull!!, 0.01)
        assertEquals("lbs", body.getValue("unit").jsonPrimitive.content)
    }

    @Test
    fun deliveredFieldsMatchesTheShaperThatActuallyRan() = runBlocking {
        server.enqueue(ok())

        val result = client().submitReading(ReadingFixtures.captured(), WeightUnit.KILOGRAMS)

        assertEquals(
            setOf(ReadingField.WEIGHT),
            (result as SubmitResult.Accepted).deliveredFields,
        )
    }

    @Test
    fun contractVersionSwitchChangesOnlyTheBody() = runBlocking {
        server.enqueue(ok())
        server.enqueue(ok())

        client().submitReading(ReadingFixtures.captured(), WeightUnit.KILOGRAMS)
        val v1 = server.takeRequest()

        client(ContractVersion.V2_BODY_COMP, V2Shaper)
            .submitReading(ReadingFixtures.captured(), WeightUnit.KILOGRAMS)
        val v2 = server.takeRequest()

        assertEquals(v1.method, v2.method)
        assertEquals(v1.url.encodedPath, v2.url.encodedPath)
        assertEquals(v1.headers["Authorization"], v2.headers["Authorization"])

        val v2Body = Json.parseToJsonElement(v2.body!!.utf8()) as JsonObject
        assertTrue("client_id", "client_id" in v2Body.keys)
        assertTrue("body_fat_pct", "body_fat_pct" in v2Body.keys)
    }

    @Test
    fun v2OmitsNullBodyCompositionFields() = runBlocking {
        server.enqueue(ok())

        client(ContractVersion.V2_BODY_COMP, V2Shaper)
            .submitReading(ReadingFixtures.captured(), WeightUnit.KILOGRAMS)

        val body = Json.parseToJsonElement(server.takeRequest().body!!.utf8()) as JsonObject
        // The BF720 reports neither bone mass nor AMR; sending nulls would let
        // a null overwrite a good server-side value on replay.
        assertFalse("bone_mass_kg" in body.keys)
        assertFalse("amr" in body.keys)
    }

    @Test
    fun redirectIsNotFollowed() = runBlocking {
        val attacker = MockWebServer()
        attacker.start()
        attacker.enqueue(ok())
        server.enqueue(
            MockResponse.Builder()
                .code(302)
                .setHeader("Location", attacker.url("/api/weight").toString())
                .build(),
        )

        val result = client().submitReading(ReadingFixtures.captured(), WeightUnit.KILOGRAMS)

        assertEquals(
            "following a redirect would send the bearer token to another host",
            0,
            attacker.requestCount,
        )
        // Not followed, but not fatal either (round-3 C2): a redirect is a
        // server-side configuration change, and failing it permanently marked
        // the whole pending queue FAILED_PERMANENT on its first attempt.
        assertTrue(result is SubmitResult.TransientFailure)
        attacker.close()
    }

    /**
     * Regression (correctness M7): a 2xx with an unreadably large body used to be
     * downgraded to `TransientFailure` on the reasoning that "a success we could
     * not read is not a success". But the server had already committed the write
     * — only the *response* was unreadable — so the drain left the row PENDING and
     * resubmitted it, duplicating the reading server-side. The status code is
     * authoritative; the body cannot un-commit it.
     */
    @Test
    fun anOversizedBodyOnATwoHundredIsStillAccepted() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("x".repeat((VitalForgeHttpClient.MAX_BODY_BYTES + 1024).toInt()))
                .build(),
        )

        val result = client().submitReading(ReadingFixtures.captured(), WeightUnit.KILOGRAMS)

        assertTrue(
            "the server committed the write; resubmitting would duplicate it",
            result is SubmitResult.Accepted,
        )
    }

    /**
     * An oversized body must still not crash, and must still not override a
     * non-2xx status — a 401 buried under 64 KiB is an auth rejection that has to
     * pause the drain, not a transient failure that keeps hammering it.
     */
    @Test
    fun anOversizedBodyNeverOverridesANonSuccessStatus() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(401)
                .body("x".repeat((VitalForgeHttpClient.MAX_BODY_BYTES + 1024).toInt()))
                .build(),
        )

        val result = client().submitReading(ReadingFixtures.captured(), WeightUnit.KILOGRAMS)

        assertEquals(SubmitResult.AuthRejected(401), result)
    }

    /**
     * Regression (correctness M8): `execute()` wraps the blocking call in
     * `runCatching`, which catches everything — including `CancellationException`.
     * A `DeliveryWorker` stopped by WorkManager mid-submit then surfaced as an
     * ordinary `TransientFailure`, so the drainer burned an `attemptCount` and
     * inflated §3.4's backoff exponent for an attempt the server never saw.
     * Cancellation must propagate; the project's Kotlin style rule says so too.
     */
    @Test
    fun cancellationPropagatesInsteadOfBecomingATransientFailure() {
        val cancelling = OkHttpClient.Builder()
            .addInterceptor { throw CancellationException("worker stopped mid-submit") }
            .build()
        val client = VitalForgeHttpClient(
            baseUrl = server.url("/").toString().trimEnd('/'),
            tokenProvider = { TOKEN },
            contract = ContractVersion.V1_WEIGHT_ONLY,
            shaper = V1Shaper,
            client = cancelling,
        )

        assertThrows(CancellationException::class.java) {
            runBlocking { client.submitReading(ReadingFixtures.captured(), WeightUnit.KILOGRAMS) }
        }
    }

    /**
     * C6: the base URL is empty until the user configures one — every call must fail closed, not throw.
     *
     * Regression (pr-1-review-security.md MEDIUM-2): an unresolvable base URL is a local
     * configuration problem, not a statement from the server — classifying it as
     * PermanentRejection marked the row FAILED_PERMANENT on the very first attempt for a
     * cause the server never weighed in on, with no path back once the URL was fixed.
     */
    @Test
    fun anUnconfiguredBaseUrlIsATransientFailureOnSubmitAndUnavailableElsewhere() = runBlocking {
        val unconfigured = client(baseUrl = "")

        val submit = unconfigured.submitReading(ReadingFixtures.captured(), WeightUnit.KILOGRAMS)
        assertEquals(SubmitResult.TransientFailure("base URL is not a valid http(s) URL", null), submit)

        assertTrue(unconfigured.recentReadings(1.minutes) is RecentResult.Unavailable)
        assertTrue(unconfigured.testConnection() is ConnectionTestResult.Unreachable)
        assertTrue(unconfigured.login("user", "pw") is LoginResult.Unreachable)

        assertEquals("nothing may reach the network without a configured base URL", 0, server.requestCount)
    }

    @Test
    fun nonJsonOnTwoHundredIsAccepted() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body("OK, logged").build())

        val result = client().submitReading(ReadingFixtures.captured(), WeightUnit.KILOGRAMS)

        assertTrue("the POST succeeded; the body is not needed", result is SubmitResult.Accepted)
    }

    @Test
    fun socketHangUpIsTransient() = runBlocking {
        server.close()

        val result = client().submitReading(ReadingFixtures.captured(), WeightUnit.KILOGRAMS)

        assertTrue(result is SubmitResult.TransientFailure)
    }

    @Test
    fun errorStringNeverContainsTheTokenOrTheResponseBody() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(422)
                .body("""{"detail":"bad token $TOKEN, weight 90.82"}""")
                .build(),
        )

        val result = client().submitReading(ReadingFixtures.captured(), WeightUnit.KILOGRAMS)

        val reason = (result as SubmitResult.PermanentRejection).reason
        assertFalse("the token must never reach a stored error string", TOKEN in reason)
        assertFalse("nor may the response body", "detail" in reason)
    }

    // The ADR-003 remote-duplicate check reads `GET /p/{slug}/api/weight/recent`.
    // VitalForge (`vitalforge_weight/weight_routes.py`) answers with the last ten
    // rows as `{id, weight_lbs, weight_kg, timestamp, synced_to_garmin}`, where
    // `timestamp` is `datetime.astimezone(utc).isoformat()` — an ISO-8601 string
    // with a `+00:00` offset and microseconds when non-zero. There is no
    // `captured_at` key. The old fake here carried one as a Long, which is how a
    // parser that never matched a real response stayed green for a month.

    /** One row exactly as the server serialises it; the parser ignores every field but two. */
    private fun recentRow(weightKg: Double, timestamp: String, id: Int = 1) =
        """{"id":$id,"weight_lbs":${weightKg * LBS_PER_KG},"weight_kg":$weightKg,""" +
            """"timestamp":"$timestamp","synced_to_garmin":true}"""

    private fun recentBody(vararg rows: String) = rows.joinToString(",", "[", "]")

    @Test
    fun recentReadingsParsesTheServersRealResponseShape() = runBlocking {
        val withMicros = "2026-09-19T14:42:02.123456+00:00"
        server.enqueue(
            ok(recentBody(recentRow(88.76, UTC_TIMESTAMP, id = 123), recentRow(88.31, withMicros, id = 122))),
        )

        val result = client().recentReadings(5.minutes)

        val readings = (result as RecentResult.Readings).readings
        assertEquals(2, readings.size)
        assertEquals(88.76, readings[0].weightKg, 1e-9)
        assertEquals(UTC_INSTANT_MILLIS, readings[0].capturedAtMillis)
        assertEquals(88.31, readings[1].weightKg, 1e-9)
        assertEquals(
            "the server's microseconds truncate to millis; a fixed-pattern formatter would reject them",
            OffsetDateTime.parse(withMicros).toInstant().toEpochMilli(),
            readings[1].capturedAtMillis,
        )
    }

    @Test
    fun aRecentTimestampWithANonUtcOffsetParsesToTheSameInstantAsItsUtcForm() = runBlocking {
        // Two spellings of one instant. Parsing either as a wall-clock time in
        // the JVM's zone puts at least one of them on the wrong instant whatever
        // that zone happens to be, so this cannot pass by luck of the test host.
        server.enqueue(ok(recentBody(recentRow(88.76, UTC_TIMESTAMP), recentRow(88.76, "2026-09-19T10:42:02-04:00"))))

        val result = client().recentReadings(5.minutes)

        val readings = (result as RecentResult.Readings).readings
        assertEquals(UTC_INSTANT_MILLIS, readings[0].capturedAtMillis)
        assertEquals(UTC_INSTANT_MILLIS, readings[1].capturedAtMillis)
    }

    @Test
    fun aRecentRowMissingItsTimestampMakesTheWholeResponseUnavailable() = runBlocking {
        val noTimestamp = """{"id":2,"weight_lbs":194.7,"weight_kg":88.31,"synced_to_garmin":false}"""
        server.enqueue(ok(recentBody(recentRow(88.76, UTC_TIMESTAMP), noTimestamp)))

        val result = client().recentReadings(5.minutes)

        assertEquals(
            "a response we cannot read is one we cannot trust for dedup; Unavailable makes the caller post anyway",
            UNEXPECTED_SHAPE,
            result,
        )
    }

    @Test
    fun aRecentRowMissingItsWeightMakesTheWholeResponseUnavailable() = runBlocking {
        val noWeight = """{"id":2,"weight_lbs":194.7,"timestamp":"$UTC_TIMESTAMP","synced_to_garmin":false}"""
        server.enqueue(ok(recentBody(recentRow(88.76, UTC_TIMESTAMP), noWeight)))

        val result = client().recentReadings(5.minutes)

        assertEquals("a defaulted weight would compare against nothing real", UNEXPECTED_SHAPE, result)
    }

    @Test
    fun aRecentRowWithABareLocalTimestampMakesTheWholeResponseUnavailable() = runBlocking {
        server.enqueue(ok(recentBody(recentRow(88.76, "2026-09-19T14:42:02"))))

        val result = client().recentReadings(5.minutes)

        assertEquals("a datetime with no offset would be silently mis-zoned, not compared", UNEXPECTED_SHAPE, result)
    }

    /**
     * Regression tripwire: this is the shape the old test faked and the old
     * parser required. No server sends it. If it ever parses again, the check
     * has gone back to reading a field that is not there.
     */
    @Test
    fun theOldCapturedAtLongShapeIsUnavailableNotASilentlyEmptyList() = runBlocking {
        server.enqueue(ok("""[{"weight_kg":90.8,"captured_at":1787000000000}]"""))

        val result = client().recentReadings(5.minutes)

        assertEquals(UNEXPECTED_SHAPE, result)
    }

    @Test
    fun aRecentRowWithAnImplausibleEpochIsDroppedWithoutSpoilingTheRest() = runBlocking {
        // A hostile value, not a shape problem: the row is well-formed, its
        // instant just cannot be a weigh-in. It is dropped alone so the rest of
        // the response still protects against a duplicate.
        server.enqueue(ok(recentBody(recentRow(88.76, "1900-01-01T00:00:00+00:00"), recentRow(88.31, UTC_TIMESTAMP))))

        val result = client().recentReadings(5.minutes)

        val readings = (result as RecentResult.Readings).readings
        assertEquals(listOf(RemoteReading(88.31, UTC_INSTANT_MILLIS)), readings)
    }

    /**
     * Year 999999999 is the far end of what ISO-8601 can spell. It parses, but
     * `Instant.toEpochMilli()` overflows a Long on it, and a parser that guards
     * the conversion with the same catch as the parse would call that a shape
     * problem and refuse the whole response — while year 9999, just as
     * impossible, took the per-row path. One rule: malformed is a shape
     * problem, out of range is a dropped row, however far out of range.
     */
    @Test
    fun aRecentRowWithAnAbsurdYearIsDroppedLikeAnyOtherOutOfRangeEpoch() = runBlocking {
        val body = recentBody(recentRow(88.76, "+999999999-12-31T23:59:59+00:00"), recentRow(88.31, UTC_TIMESTAMP))
        server.enqueue(ok(body))

        val result = client().recentReadings(5.minutes)

        val readings = (result as RecentResult.Readings).readings
        assertEquals(listOf(RemoteReading(88.31, UTC_INSTANT_MILLIS)), readings)
    }

    @Test
    fun aRecentEnvelopeObjectIsUnavailableNotUnwrapped() = runBlocking {
        // The parser used to also accept `{"readings": [...]}`. Nothing sends it;
        // a second accepted shape is one more place for a mismatch to hide.
        server.enqueue(ok("""{"readings":[${recentRow(88.76, UTC_TIMESTAMP)}]}"""))

        val result = client().recentReadings(5.minutes)

        assertEquals(UNEXPECTED_SHAPE, result)
    }

    @Test
    fun missingRecentEndpointIsUnavailableSoTheCallerPostsAnyway() = runBlocking {
        server.enqueue(MockResponse.Builder().code(404).build())

        val result = client().recentReadings(5.minutes)

        assertTrue(
            "a failed dedup check must never block a delivery (ADR-003 step 3)",
            result is RecentResult.Unavailable,
        )
    }

    @Test
    fun malformedRecentResponseNeverThrows() = runBlocking {
        server.enqueue(ok("not json at all"))

        val result = client().recentReadings(5.minutes)

        assertTrue("unreadable is Unavailable, so the caller posts anyway", result is RecentResult.Unavailable)
    }

    @Test
    fun noAuthorizationHeaderIsSentWhenNoTokenIsConfigured() = runBlocking {
        server.enqueue(ok())

        client(token = null).submitReading(ReadingFixtures.captured(), WeightUnit.KILOGRAMS)

        assertEquals(null, server.takeRequest().headers["Authorization"])
    }

    @Test
    fun testConnectionIsAuthorizedOn2xx() = runBlocking {
        server.enqueue(ok())

        val result = client().testConnection()

        assertEquals(ConnectionTestResult.Authorized, result)
    }

    @Test
    fun testConnectionIsUnauthorizedOn401() = runBlocking {
        server.enqueue(MockResponse.Builder().code(401).build())

        val result = client().testConnection()

        assertEquals(ConnectionTestResult.Unauthorized(401), result)
    }

    @Test
    fun testConnectionIsUnauthorizedOn403() = runBlocking {
        server.enqueue(MockResponse.Builder().code(403).build())

        val result = client().testConnection()

        assertEquals(
            "a bad-token signal must be distinguishable from a generic unreachable failure",
            ConnectionTestResult.Unauthorized(403),
            result,
        )
    }

    @Test
    fun testConnectionIsUnreachableOnSocketHangUp() = runBlocking {
        server.close()

        val result = client().testConnection()

        assertTrue(result is ConnectionTestResult.Unreachable)
    }

    @Test
    fun testConnectionIsUnreachableOnServerError() = runBlocking {
        server.enqueue(MockResponse.Builder().code(500).build())

        val result = client().testConnection()

        assertTrue(
            "a 500 is neither an auth rejection nor a success — it must not be reported as Authorized",
            result is ConnectionTestResult.Unreachable,
        )
    }

    @Test
    fun testConnectionUsesGetNotPost() = runBlocking {
        server.enqueue(ok())

        client().testConnection()

        assertEquals("GET", server.takeRequest().method)
    }

    @Test
    fun testConnectionSendsAuthorizationHeader() = runBlocking {
        server.enqueue(ok())

        client().testConnection()

        assertEquals("Bearer $TOKEN", server.takeRequest().headers["Authorization"])
    }

    @Test
    fun testConnectionHitsRecentPathNeverWeightPath() = runBlocking {
        server.enqueue(ok())

        client().testConnection()

        assertEquals(
            "must never risk submitting a fake reading just to check connectivity",
            "/api/weight/recent",
            server.takeRequest().url.encodedPath,
        )
    }

    @Test
    fun loginPostsUsernameAndPasswordAsJson() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"success":true}""")
                .addHeader("Set-Cookie", "vf_session=$SESSION_COOKIE_VALUE; HttpOnly; SameSite=Lax")
                .build(),
        )

        client().login("alice", "hunter2")

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/auth/login", request.url.encodedPath)
        val body = Json.parseToJsonElement(request.body!!.utf8()) as JsonObject
        assertEquals("alice", body.getValue("username").jsonPrimitive.content)
        assertEquals("hunter2", body.getValue("password").jsonPrimitive.content)
    }

    @Test
    fun loginExtractsTheSessionCookieValueFromSetCookie() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"success":true}""")
                .addHeader("Set-Cookie", "vf_session=$SESSION_COOKIE_VALUE; HttpOnly; SameSite=Lax")
                .build(),
        )

        val result = client().login("alice", "hunter2")

        assertEquals(LoginResult.Success(SESSION_COOKIE_VALUE), result)
    }

    @Test
    fun loginIsInvalidCredentialsOn401() = runBlocking {
        server.enqueue(MockResponse.Builder().code(401).body("""{"detail":"Invalid credentials"}""").build())

        val result = client().login("alice", "wrong-password")

        assertEquals(LoginResult.InvalidCredentials, result)
    }

    // VitalForge moved its weight routes under a per-person prefix
    // (`/p/{slug}/api/weight`) while keeping auth server-global at `/auth/login`.
    // A base URL carrying the prefix has to reach both, and a base URL without
    // one must behave exactly as it did before the prefix existed.

    private fun prefixedClient() = client(baseUrl = server.url("/p/bash6632").toString().trimEnd('/'))

    @Test
    fun testConnectionNamesTheMissingPersonPathOn404() = runBlocking {
        server.enqueue(MockResponse.Builder().code(404).body("""{"detail":"Not Found"}""").build())

        val result = client().testConnection()

        assertEquals(
            "a 404 is the one failure the user can fix from the settings screen",
            ConnectionTestResult.Unreachable(ResponseClassifier.NO_SUCH_ENDPOINT_REASON),
            result,
        )
    }

    /**
     * Regression (hardware, 2026-09-07): a base URL missing the person prefix
     * authenticated fine, POSTed to a route that was not there, and the 404 was
     * classified permanent — a real weigh-in went FAILED_PERMANENT on its first
     * attempt for a local configuration error the server never weighed in on.
     * A collection POST cannot be 404'd on the merits of the reading, so it
     * retries until the URL is corrected, carrying the same person-path hint
     * that "Test connection" shows.
     */
    @Test
    fun aNotFoundOnSubmitIsTransientNotPermanent() = runBlocking {
        server.enqueue(MockResponse.Builder().code(404).body("""{"detail":"Not Found"}""").build())

        val result = client().submitReading(ReadingFixtures.captured(), WeightUnit.KILOGRAMS)

        assertTrue(
            "a missing endpoint is a base-URL problem, not a verdict on the reading",
            result is SubmitResult.TransientFailure,
        )
        assertEquals(ResponseClassifier.NO_SUCH_ENDPOINT_REASON, (result as SubmitResult.TransientFailure).reason)
    }

    @Test
    fun testConnectionKeepsTheGenericReasonForOtherPermanentCodes() = runBlocking {
        server.enqueue(MockResponse.Builder().code(422).body("""{"detail":"Unprocessable"}""").build())

        val result = client().testConnection()

        assertEquals(
            "only 404 gets the person-path hint; 422 is not a routing problem",
            ConnectionTestResult.Unreachable("rejected by server"),
            result,
        )
    }

    @Test
    fun submitPostsUnderThePersonPrefixWhenTheBaseUrlCarriesOne() = runBlocking {
        server.enqueue(ok())

        prefixedClient().submitReading(ReadingFixtures.captured(), WeightUnit.KILOGRAMS)

        assertEquals("/p/bash6632/api/weight", server.takeRequest().url.encodedPath)
    }

    @Test
    fun recentReadsUnderThePersonPrefixWhenTheBaseUrlCarriesOne() = runBlocking {
        server.enqueue(ok("[]"))

        prefixedClient().recentReadings(5.minutes)

        assertEquals("/p/bash6632/api/weight/recent", server.takeRequest().url.encodedPath)
    }

    @Test
    fun loginStaysAtTheServerRootUnderAPersonPrefix() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"success":true}""")
                .addHeader("Set-Cookie", "vf_session=$SESSION_COOKIE_VALUE; HttpOnly; SameSite=Lax")
                .build(),
        )

        prefixedClient().login("alice", "hunter2")

        assertEquals(
            "auth is server-global; prefixing it with the person slug 404s",
            "/auth/login",
            server.takeRequest().url.encodedPath,
        )
    }

    @Test
    fun aBaseUrlWithoutAPersonPrefixKeepsThePreExistingPaths() = runBlocking {
        server.enqueue(ok())
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"success":true}""")
                .addHeader("Set-Cookie", "vf_session=$SESSION_COOKIE_VALUE; HttpOnly; SameSite=Lax")
                .build(),
        )

        client().submitReading(ReadingFixtures.captured(), WeightUnit.KILOGRAMS)
        client().login("alice", "hunter2")

        assertEquals("/api/weight", server.takeRequest().url.encodedPath)
        assertEquals("/auth/login", server.takeRequest().url.encodedPath)
    }

    @Test
    fun loginIsUnreachableOnSocketHangUp() = runBlocking {
        server.close()

        val result = client().login("alice", "hunter2")

        assertTrue(result is LoginResult.Unreachable)
    }

    @Test
    fun loginErrorStringNeverContainsThePassword() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(422)
                .body("""{"detail":"validation failed for password hunter2"}""")
                .build(),
        )

        val result = client().login("alice", "hunter2")

        val reason = (result as LoginResult.Unreachable).reason
        assertFalse("the password must never reach a stored error string", "hunter2" in reason)
        assertFalse("nor may the response body", "detail" in reason)
    }

    @Test
    fun submitReadingPrefersBearerTokenWhenBothCredentialsAreConfigured() = runBlocking {
        server.enqueue(ok())

        client(token = TOKEN, sessionCookie = SESSION_COOKIE_VALUE)
            .submitReading(ReadingFixtures.captured(), WeightUnit.KILOGRAMS)

        val request = server.takeRequest()
        assertEquals("Bearer $TOKEN", request.headers["Authorization"])
        assertEquals(
            "the app enforces mutual exclusivity, but the client must still pick a single winner if it's ever violated",
            null,
            request.headers["Cookie"],
        )
    }

    @Test
    fun submitReadingFallsBackToSessionCookieWhenNoTokenIsConfigured() = runBlocking {
        server.enqueue(ok())

        client(token = null, sessionCookie = SESSION_COOKIE_VALUE)
            .submitReading(ReadingFixtures.captured(), WeightUnit.KILOGRAMS)

        val request = server.takeRequest()
        assertEquals(null, request.headers["Authorization"])
        assertEquals("vf_session=$SESSION_COOKIE_VALUE", request.headers["Cookie"])
    }

    private companion object {
        const val TOKEN = "vf_test_token_do_not_leak"

        /** Realistic itsdangerous `URLSafeTimedSerializer` shape (payload.timestamp.signature). */
        const val SESSION_COOKIE_VALUE = "eyJ1c2VyIjoiYWRtaW4ifQ.aBcD3f.xyz123-abc_DEF456"

        /** As the server writes it: `datetime.astimezone(timezone.utc).isoformat()`. */
        const val UTC_TIMESTAMP = "2026-09-19T14:42:02+00:00"

        /** Derived from the string, not hand-typed, so a wrong-zone parse cannot coincide with it. */
        val UTC_INSTANT_MILLIS: Long = OffsetDateTime.parse(UTC_TIMESTAMP).toInstant().toEpochMilli()

        /** The server's own `weight_lbs` derivation; only there to keep the fixture shape honest. */
        const val LBS_PER_KG = 2.20462

        /** Spelled here, not read from production: the exact reason is part of what is pinned. */
        val UNEXPECTED_SHAPE = RecentResult.Unavailable("unexpected recent-readings shape")
    }
}
