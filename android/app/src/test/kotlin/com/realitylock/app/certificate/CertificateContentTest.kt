package com.realitylock.app.certificate

import com.realitylock.app.capture.model.CapturedEvent
import com.realitylock.app.capture.model.LocationData
import com.realitylock.app.sync.FakeEventRepository
import com.realitylock.app.verify.VerificationReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The *content* of the certificate — the part with evidentiary and privacy
 * consequences — tested independently of fonts and page geometry.
 */
class CertificateContentTest {

    private fun event(location: LocationData? = null): CapturedEvent =
        FakeEventRepository.event(EVENT_ID, "/tmp/$EVENT_ID.jpg").let { base ->
            base.copy(metadata = base.metadata.copy(location = location))
        }

    private fun content(
        event: CapturedEvent = event(),
        report: VerificationReport? = null,
        framing: List<String> = listOf("what this proves", "what it does not"),
    ) = CertificateContent.from(
        event = event,
        report = report,
        title = "Reality Lock — Event Proof Certificate",
        verdictLabel = "VERIFIED",
        framing = framing,
        verificationUrl = "https://example.org/verify/$EVENT_ID",
        generatedAtIso = "2026-07-25T10:00:00Z",
    )

    @Test
    fun `a certificate cannot be built without its framing`() {
        // research/06 §7 forbids the document presenting itself as self-sufficient
        // proof, so a bug that dropped the caveats must fail loudly rather than
        // produce an official-looking page that overclaims.
        val error = assertThrows(IllegalArgumentException::class.java) {
            content(framing = emptyList())
        }
        assertTrue(error.message!!.contains("framing"))
    }

    @Test
    fun `location is rounded to three decimal places, not published exactly`() {
        val precise = LocationData(
            latitude = 13.0827123,
            longitude = 80.2707456,
            accuracyMeters = 4.7f,
            isMock = false,
        )

        val summary = content(event(precise)).locationSummary

        // ~100 m. A printed page that may be shared, filed or photographed does not
        // need to pin someone's position to the metre; the exact coordinate stays in
        // the proof package for a verifier who needs it.
        assertTrue("was: $summary", summary.startsWith("13.083, 80.271"))
        assertTrue(summary.contains("±4 m"))
        assertTrue(!summary.contains("13.0827123"))
        assertTrue(!summary.contains("80.2707456"))
    }

    @Test
    fun `an absent location says so rather than showing zeros`() {
        assertEquals("not recorded", content(event(null)).locationSummary)
    }

    @Test
    fun `a mock location is called out in the summary`() {
        val mocked = LocationData(
            latitude = 13.0827,
            longitude = 80.2707,
            accuracyMeters = 5f,
            isMock = true,
        )

        assertTrue(content(event(mocked)).locationSummary.contains("REPORTED AS MOCK"))
    }

    @Test
    fun `cryptographic fields are carried across`() {
        val c = content()

        assertEquals("c".repeat(64), c.merkleRoot)
        assertEquals("a".repeat(64), c.mediaSha256)
        assertEquals("SHA-256", c.hashAlgorithm)
        assertEquals("OnePlus CPH2591 (API 35)", c.deviceDescription)
        assertEquals("2026-07-23T13:12:25.678Z", c.capturedAtIso)
    }

    @Test
    fun `check rows come from the report when one is supplied`() {
        val report = VerificationReport(
            verdict = VerificationReport.Verdict.VERIFIED,
            checks = listOf(
                VerificationReport.Check("mediaHashMatch", VerificationReport.Outcome.PASS),
                VerificationReport.Check("locationPlausible", VerificationReport.Outcome.UNAVAILABLE),
            ),
            notes = emptyList(),
            advisories = listOf("No key attestation chain"),
            limitations = emptyList(),
        )

        val c = content(report = report)

        assertEquals(
            listOf("mediaHashMatch" to "PASS", "locationPlausible" to "UNAVAILABLE"),
            c.checkRows,
        )
        assertEquals(listOf("No key attestation chain"), c.advisories)
    }

    @Test
    fun `without a report the checks section is simply empty`() {
        // Better an absent section than a fabricated "all clear".
        val c = content(report = null)

        assertTrue(c.checkRows.isEmpty())
        assertTrue(c.advisories.isEmpty())
    }

    private companion object {
        const val EVENT_ID = "3f2504e0-4f89-41d3-9a0c-0305e82c3301"
    }
}
