package com.realitylock.app.verify

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Parsing of the verifier's response, driven against a real server.
 *
 * The parsing rules under test are the honest-reporting ones: a check the app does
 * not recognise must never be guessed at, and an unreachable verifier must be
 * distinguishable from a failed verification.
 */
class VerificationClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: VerificationClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = VerificationClient(OkHttpClient(), server.url("/").toString())
    }

    @After
    fun tearDown() = server.close()

    private fun respond(code: Int, body: String) =
        server.enqueue(MockResponse.Builder().code(code).body(body).build())

    @Test
    fun `a verified response is parsed with checks in the spec display order`() {
        respond(
            200,
            """
            {
              "verdict": "verified",
              "checks": {
                "schemaValid": "pass",
                "mediaHashMatch": "pass",
                "metadataHashMatch": "pass",
                "merkleRootMatch": "pass",
                "signatureValid": "pass",
                "attestationPresent": "unavailable",
                "timestampPlausible": "pass",
                "locationPlausible": "unavailable"
              },
              "notes": ["a note"],
              "advisories": ["No key attestation chain"],
              "limitations": ["Does NOT prove the depicted event was real."],
              "merkleRoot": "${"c".repeat(64)}"
            }
            """.trimIndent(),
        )

        val result = client.verify("{}".toByteArray())

        val report = (result as VerificationClient.Result.Ok).report
        assertEquals(VerificationReport.Verdict.VERIFIED, report.verdict)
        // Sorted into the spec's sequence. NOT the response's key order: org.json
        // enumerates keys arbitrarily, and depending on that produced a different
        // order on every run — which this assertion originally caught.
        assertEquals(
            listOf(
                "schemaValid", "mediaHashMatch", "metadataHashMatch", "merkleRootMatch",
                "signatureValid", "attestationPresent", "timestampPlausible", "locationPlausible",
            ),
            report.checks.map { it.name },
        )
        assertEquals(VerificationReport.Outcome.PASS, report.checks.first().outcome)
        assertEquals(
            VerificationReport.Outcome.UNAVAILABLE,
            report.checks.first { it.name == "attestationPresent" }.outcome,
        )
        assertEquals(1, report.advisories.size)
        assertEquals(1, report.limitations.size)
        assertEquals("c".repeat(64), report.merkleRoot)
    }

    @Test
    fun `a failed verdict and its failing check are parsed`() {
        respond(
            200,
            """{"verdict":"failed","checks":{"mediaHashMatch":"fail"},"notes":["media digest differs"]}""",
        )

        val report = (client.verify("{}".toByteArray()) as VerificationClient.Result.Ok).report

        assertEquals(VerificationReport.Verdict.FAILED, report.verdict)
        assertEquals(VerificationReport.Outcome.FAIL, report.checks.single().outcome)
        assertTrue(report.notes.single().contains("media digest differs"))
    }

    @Test
    fun `an unrecognised outcome is UNKNOWN, never guessed as pass or fail`() {
        respond(200, """{"verdict":"verified","checks":{"someFutureCheck":"probably_fine"}}""")

        val report = (client.verify("{}".toByteArray()) as VerificationClient.Result.Ok).report

        // A newer backend reporting something we do not understand must not be
        // silently interpreted in either direction.
        assertEquals(VerificationReport.Outcome.UNKNOWN, report.checks.single().outcome)
    }

    @Test
    fun `an unrecognised verdict is UNKNOWN`() {
        respond(200, """{"verdict":"probably_ok","checks":{}}""")

        val report = (client.verify("{}".toByteArray()) as VerificationClient.Result.Ok).report

        assertEquals(VerificationReport.Verdict.UNKNOWN, report.verdict)
    }

    @Test
    fun `a 400 invalid_format response is still a usable report, not an outage`() {
        // The server rejected the document, which is an answer — quite different
        // from being unable to ask.
        respond(400, """{"verdict":"invalid_format","errors":[{"message":"missing merkle"}]}""")

        val report = (client.verify("{}".toByteArray()) as VerificationClient.Result.Ok).report

        assertEquals(VerificationReport.Verdict.INVALID_FORMAT, report.verdict)
    }

    @Test
    fun `an empty body is reported as unreachable rather than as a verdict`() {
        respond(200, "")

        val result = client.verify("{}".toByteArray())

        assertTrue(result is VerificationClient.Result.Unreachable)
    }

    @Test
    fun `unparseable JSON is reported as unreachable`() {
        respond(200, "<html>gateway error</html>")

        val result = client.verify("{}".toByteArray())

        assertTrue(result is VerificationClient.Result.Unreachable)
    }

    @Test
    fun `a dead server is unreachable, not a failed verification`() {
        server.close()

        val result = client.verify("{}".toByteArray())

        // Conflating these would let a network outage read as "your evidence is
        // tampered", which is the worst possible false alarm for this app.
        assertTrue(result is VerificationClient.Result.Unreachable)
    }

    @Test
    fun `the verification URL is built from the configured base`() {
        val url = client.verificationUrl(EVENT_ID)

        assertTrue(url.startsWith(server.url("/").toString().removeSuffix("/")))
        assertTrue(url.endsWith("/verify/$EVENT_ID"))
    }

    @Test
    fun `the exact package bytes are what gets sent`() {
        val bytes = """{"eventId":"$EVENT_ID","exact":"bytes"}""".toByteArray()
        respond(200, """{"verdict":"verified","checks":{}}""")

        client.verify(bytes)

        // Same guarantee as the sync path: a signed document is forwarded, never
        // re-encoded (ADR-0006 §2).
        assertEquals(bytes.toList(), server.takeRequest().body!!.toByteArray().toList())
    }

    @Test
    fun `a malformed base URL fails loudly at construction`() {
        val error = runCatching { VerificationClient(OkHttpClient(), "not a url") }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun `an unknown check is appended rather than dropped`() {
        respond(
            200,
            """{"verdict":"verified","checks":{"someFutureCheck":"pass","mediaHashMatch":"pass"}}""",
        )

        val report = (client.verify("{}".toByteArray()) as VerificationClient.Result.Ok).report

        // Known checks first, in spec order; anything the app does not know about
        // still surfaces at the end instead of vanishing.
        assertEquals(listOf("mediaHashMatch", "someFutureCheck"), report.checks.map { it.name })
    }

    private companion object {
        const val EVENT_ID = "3f2504e0-4f89-41d3-9a0c-0305e82c3301"
    }
}
