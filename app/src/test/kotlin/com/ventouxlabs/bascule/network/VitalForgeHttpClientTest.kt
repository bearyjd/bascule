package com.ventouxlabs.bascule.network

import com.ventouxlabs.bascule.data.WeightUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
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
        baseUrl: String = server.url("/").toString().trimEnd('/'),
    ) = VitalForgeHttpClient(
        baseUrl = baseUrl,
        tokenProvider = { token },
        contract = contract,
        shaper = shaper,
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
        assertTrue(result is SubmitResult.PermanentRejection)
        attacker.close()
    }

    @Test
    fun oversizedBodyIsTransientNotACrash() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("x".repeat((VitalForgeHttpClient.MAX_BODY_BYTES + 1024).toInt()))
                .build(),
        )

        val result = client().submitReading(ReadingFixtures.captured(), WeightUnit.KILOGRAMS)

        assertEquals(
            SubmitResult.TransientFailure(VitalForgeHttpClient.OVERSIZED_BODY_REASON, null),
            result,
        )
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

    @Test
    fun recentReadingsParsesTheContentionCheckResponse() = runBlocking {
        server.enqueue(ok("""[{"weight_kg":90.8,"captured_at":1787000000000}]"""))

        val result = client().recentReadings(5.minutes)

        val readings = (result as RecentResult.Readings).readings
        assertEquals(1, readings.size)
        assertEquals(90.8, readings.single().weightKg, 1e-9)
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

        assertNotNull(client().recentReadings(5.minutes))
    }

    @Test
    fun noAuthorizationHeaderIsSentWhenNoTokenIsConfigured() = runBlocking {
        server.enqueue(ok())

        client(token = null).submitReading(ReadingFixtures.captured(), WeightUnit.KILOGRAMS)

        assertEquals(null, server.takeRequest().headers["Authorization"])
    }

    private companion object {
        const val TOKEN = "vf_test_token_do_not_leak"
    }
}
