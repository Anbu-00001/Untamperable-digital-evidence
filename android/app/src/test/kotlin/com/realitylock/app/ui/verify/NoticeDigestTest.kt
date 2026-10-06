package com.realitylock.app.ui.verify

import com.realitylock.app.ui.verify.NoticeDigest.Glyph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shortening a caveat must never change what it says: every known sentence maps
 * to its own label, and an unknown one still gets a headline (never dropped).
 */
class NoticeDigestTest {

    @Test
    fun `the backend's shipped limitations each get their own short label`() {
        val expected = mapOf(
            "Proves the media and metadata are unaltered since capture and were signed by one specific key held in the capturing device keystore." to Glyph.UNCHANGED,
            "Hardware backing is supported only when `attestationRootTrusted`, `attestationNotRevoked` and `attestationSecurityLevel` all pass — read them." to Glyph.HARDWARE,
            "The device’s Verified Boot state and bootloader lock are reported in the notes but do NOT affect the verdict." to Glyph.BOOT_STATE,
            "Does NOT prove the depicted event was real, unstaged, or correctly described." to Glyph.NOT_REAL,
            "Not a standalone legal certificate; BSA 2023 s.63 requires human certification." to Glyph.LEGAL,
        )
        for ((sentence, glyph) in expected) {
            assertEquals(sentence, glyph, NoticeDigest.limitation(sentence).glyph)
        }
    }

    @Test
    fun `the offline limitations are recognised too`() {
        assertEquals(Glyph.PHONE_ONLY, NoticeDigest.limitation("This verification ran ENTIRELY ON THIS DEVICE, with no network.").glyph)
        assertEquals(Glyph.NO_ROOT, NoticeDigest.limitation("It did NOT anchor the attestation chain to Google's roots").glyph)
        assertEquals(Glyph.NO_VERIFIED, NoticeDigest.limitation("an offline check can never return `verified`").glyph)
    }

    @Test
    fun `an unseen sentence still gets a headline and is never dropped`() {
        val digest = NoticeDigest.limitation("A brand new caveat that nobody wrote a rule for yet, long enough to need clipping.")
        assertEquals(Glyph.GENERIC, digest.glyph)
        assertTrue(digest.short.isNotBlank())
        assertTrue("headline too long: ${digest.short}", digest.short.length <= 46)
    }

    @Test
    fun `advisories map by phrase`() {
        assertEquals(Glyph.NO_ATTESTATION, NoticeDigest.advisory("No key attestation chain: the signature proves the bundle is unaltered").glyph)
        assertEquals(Glyph.LOCATION, NoticeDigest.advisory("Location could not be cross-checked against an earlier capture").glyph)
    }
}
