package com.realitylock.app.certificate

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.realitylock.app.core.config.CertificateConfig
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The BSA 2023 s.63 draft annexure, rendered on a real device.
 *
 * Instrumented for the same reason [CertificateRendererInstrumentedTest] is:
 * `PdfDocument` and `Canvas` have no JVM stand-in, and the claim worth testing is
 * that the real Android PDF writer produced a real, openable document.
 *
 * The assertion that matters most here is **pagination**. This document's content
 * is unbounded — device particulars, production method, matters requiring
 * attestation, and one block per signatory — and the single-page renderer next
 * door would simply have drawn the overflow past the bottom edge, where it is
 * invisible in the PDF while appearing to have been included. On a statutory form
 * that is the worst available failure, so it is asserted directly rather than
 * assumed from a non-empty byte array.
 */
@RunWith(AndroidJUnit4::class)
class StatutoryAnnexureRendererInstrumentedTest {

    private fun content(
        signatories: List<SignatoryBlock> = defaultSignatories,
        productionMethod: List<String> = defaultMethod,
        matters: List<String> = defaultMatters,
    ) = StatutoryAnnexureContent(
        title = "Draft Technical Annexure — Bharatiya Sakshya Adhiniyam 2023, s.63",
        draftNotice = "DRAFT — NOT A CERTIFICATE. This document is generated automatically " +
            "by software and has no legal force as produced. Under BSA 2023 s.63(4) a " +
            "certificate must be signed by a natural person in a responsible position in " +
            "relation to the device, and additionally by an independent expert.",
        eventId = EVENT_ID,
        capturedAtIso = "2026-07-23T13:12:25.678Z",
        merkleRoot = "c".repeat(64),
        hashAlgorithm = "SHA-256",
        mediaSha256 = "a".repeat(64),
        signatureAlgorithm = "SHA256withECDSA",
        deviceParticulars = listOf(
            "Make" to "OnePlus",
            "Model" to "CPH2591",
            "Android API level" to "35",
            "Application install identifier" to "8fc78a09-a8c9-4f61-a08b-3b9d5e2efc52",
            "Application version" to "0.1.0 (1)",
        ),
        productionMethod = productionMethod,
        mattersRequiringHumanAttestation = matters,
        signatories = signatories,
        generatedAtIso = "2026-08-07T09:00:00.000Z",
    )

    private fun renderToPages(content: StatutoryAnnexureContent): Int {
        val bytes = StatutoryAnnexureRenderer().render(content)
        assertTrue("the renderer produced no bytes", bytes.isNotEmpty())

        val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val file = File.createTempFile("annexure", ".pdf", dir)
        return try {
            file.writeBytes(bytes)
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                // Opening it back is the real assertion: a renderer that emitted a
                // corrupt file would satisfy any weaker check and fail in a
                // courtroom rather than here.
                PdfRenderer(fd).use { renderer ->
                    assertTrue("the PDF reported no pages", renderer.pageCount >= 1)
                    renderer.openPage(0).use { page ->
                        assertEquals(CertificateConfig.PAGE_WIDTH_POINTS, page.width)
                        assertEquals(CertificateConfig.PAGE_HEIGHT_POINTS, page.height)
                    }
                    renderer.pageCount
                }
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun theAnnexureRendersToAnOpenablePdf() {
        assertTrue(renderToPages(content()) >= 1)
    }

    @Test
    fun theShippingAnnexureCurrentlyFitsOnOnePage() {
        // Recorded because it was predicted wrong. The realistic document — five
        // production steps, five attestation matters, two signature blocks — was
        // expected to need two A4 pages and in fact needs one.
        //
        // That is a fact about content volume, NOT evidence that pagination is
        // absent: [manySignatoriesAddPagesRatherThanOverflowing] shows the break
        // engaging as soon as there is more to draw. Because every write reserves
        // its space first, a one-page result means everything fit — the renderer
        // cannot draw past the bottom and report one page.
        //
        // Left as an equality so that adding prose to the annexure trips this
        // test and prompts a look at the printed document, rather than silently
        // changing what a signatory is handed.
        assertEquals(1, renderToPages(content()))
    }

    @Test
    fun everyPageIsOpenableNotJustTheFirst() {
        val bytes = StatutoryAnnexureRenderer().render(content())
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val file = File.createTempFile("annexure-pages", ".pdf", dir)
        try {
            file.writeBytes(bytes)
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { renderer ->
                    // A broken page-finish sequence can produce a document whose
                    // first page opens and whose later pages do not.
                    for (index in 0 until renderer.pageCount) {
                        renderer.openPage(index).use { page ->
                            assertEquals(CertificateConfig.PAGE_WIDTH_POINTS, page.width)
                        }
                    }
                }
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun manySignatoriesAddPagesRatherThanOverflowing() {
        // Ten blocks cannot share a page with the rest of the document. Growth in
        // page count is what proves the reserve-before-draw path is load-bearing
        // rather than incidental.
        val many = (1..10).map { SignatoryBlock("Signatory $it", "Basis for signatory $it.") }
        val withMany = renderToPages(content(signatories = many))
        val withTwo = renderToPages(content())

        assertTrue(
            "ten signature blocks ($withMany pages) should need more room than two ($withTwo)",
            withMany > withTwo,
        )
    }

    @Test
    fun aSignatoryBlockCarriesNoPlaceToAutoFillAName() {
        // Enforced by the type, and asserted here so a future field addition has
        // to break a test that says why it must not exist: this app generates the
        // material a certifier relies on and must never present itself as having
        // certified anything (research/06 §1.3).
        val fields = SignatoryBlock::class.java.declaredFields
            .map { it.name.lowercase() }
            .filterNot { it.startsWith("$") }

        assertEquals(listOf("role", "basis").sorted(), fields.sorted())
        for (forbidden in listOf("name", "signature", "date", "signedby", "signedat")) {
            assertTrue(
                "SignatoryBlock must not carry a '$forbidden' field — nothing in this " +
                    "app may fill a signature in",
                fields.none { it.contains(forbidden) },
            )
        }
    }

    private companion object {
        const val EVENT_ID = "3f2504e0-4f89-41d3-9a0c-0305e82c3301"

        val defaultSignatories = listOf(
            SignatoryBlock(
                role = "1. Person in charge of the device (device custodian)",
                basis = "Required by BSA 2023 s.63(4). Courts require the certificate to " +
                    "originate from whoever controls the device.",
            ),
            SignatoryBlock(
                role = "2. Independent expert",
                basis = "Required by BSA 2023 s.63(4), which introduced mandatory dual " +
                    "certification.",
            ),
        )

        val defaultMethod = listOf(
            "The image was captured by the Reality Lock application directly from the " +
                "device camera, with no import path from storage.",
            "Location, timestamp, motion-sensor and device metadata were recorded at " +
                "capture and canonicalised using RFC 8785.",
            "SHA-256 digests were computed over the image and the canonicalised metadata " +
                "and combined into a two-leaf Merkle root.",
            "The Merkle root was signed with a non-exportable ECDSA P-256 keystore key.",
            "A hardware key-attestation chain was recorded where the device supports it.",
        )

        val defaultMatters = listOf(
            "That the device was operating properly throughout the relevant period.",
            "That the information was fed into the device in the ordinary course of activity.",
            "That the signatory occupies a responsible position in relation to the device.",
            "Chain of custody after the record left the device.",
            "That the scene depicted is what it is asserted to be.",
        )
    }
}
