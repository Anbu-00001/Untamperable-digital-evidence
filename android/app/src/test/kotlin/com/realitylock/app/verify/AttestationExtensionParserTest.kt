package com.realitylock.app.verify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Android Key Attestation extension reader.
 *
 * The security property under test is negative: a malformed, truncated or
 * unexpected structure must **throw**, never yield a security level. A
 * fabricated "StrongBox" is worse than no answer at all, so every failure mode
 * here asserts a refusal rather than a value.
 */
class AttestationExtensionParserTest {

    private val keyPair = TestCertificates.newKeyPair()

    private fun certWith(extension: ByteArray) = TestCertificates.certificate(
        subjectCn = "Android Keystore Key",
        subjectPublicKey = keyPair.public,
        issuerCn = "Android Keystore Key",
        issuerPrivateKey = keyPair.private,
        extensions = listOf(extension),
    )

    // ---- the levels ---------------------------------------------------------

    @Test
    fun `a TrustedEnvironment key is read from a real certificate`() {
        val description = AttestationExtensionParser.parse(
            certWith(
                TestCertificates.attestationExtension(
                    AttestationExtensionParser.SECURITY_LEVEL_TRUSTED_ENVIRONMENT,
                ),
            ),
        )

        assertEquals(
            AttestationExtensionParser.SECURITY_LEVEL_TRUSTED_ENVIRONMENT,
            description?.securityLevelValue,
        )
        assertEquals("TrustedEnvironment", description?.securityLevel)
        assertEquals(300, description?.attestationVersion)
    }

    @Test
    fun `a StrongBox key is read`() {
        val description = AttestationExtensionParser.parse(
            certWith(
                TestCertificates.attestationExtension(
                    AttestationExtensionParser.SECURITY_LEVEL_STRONGBOX,
                ),
            ),
        )

        assertEquals("StrongBox", description?.securityLevel)
    }

    @Test
    fun `a Software key is read as Software, not silently upgraded`() {
        val description = AttestationExtensionParser.parse(
            certWith(
                TestCertificates.attestationExtension(
                    AttestationExtensionParser.SECURITY_LEVEL_SOFTWARE,
                ),
            ),
        )

        assertEquals(AttestationExtensionParser.SECURITY_LEVEL_SOFTWARE, description?.securityLevelValue)
        assertEquals("Software", description?.securityLevel)
    }

    /**
     * A level a future Android introduces must be visibly unrecognised rather
     * than falling off the end of the name table into "Software" — which would
     * turn an unknown into a specific, wrong, and alarming claim.
     */
    @Test
    fun `an unrecognised security level is surfaced as Unknown, not mapped`() {
        val description = AttestationExtensionParser.parseKeyDescription(
            TestCertificates.keyDescription(securityLevel = 7),
        )

        assertEquals(7, description.securityLevelValue)
        assertEquals("Unknown(7)", description.securityLevel)
    }

    // ---- root of trust ------------------------------------------------------

    @Test
    fun `verified boot state and bootloader lock are read from hardwareEnforced`() {
        val description = AttestationExtensionParser.parseKeyDescription(
            TestCertificates.keyDescription(
                securityLevel = AttestationExtensionParser.SECURITY_LEVEL_STRONGBOX,
                deviceLocked = true,
                verifiedBootState = 0,
            ),
        )

        assertEquals(true, description.deviceLocked)
        assertEquals("Verified", description.verifiedBootState)
    }

    @Test
    fun `an unlocked bootloader is reported as unlocked`() {
        val description = AttestationExtensionParser.parseKeyDescription(
            TestCertificates.keyDescription(
                securityLevel = AttestationExtensionParser.SECURITY_LEVEL_TRUSTED_ENVIRONMENT,
                deviceLocked = false,
                verifiedBootState = 2,
            ),
        )

        assertEquals(false, description.deviceLocked)
        assertEquals("Unverified", description.verifiedBootState)
    }

    /**
     * No rootOfTrust means "unknown", and unknown must stay null rather than
     * defaulting to the reassuring `deviceLocked = true`.
     */
    @Test
    fun `an absent rootOfTrust leaves boot state unknown rather than defaulted`() {
        val description = AttestationExtensionParser.parseKeyDescription(
            TestCertificates.keyDescription(
                securityLevel = AttestationExtensionParser.SECURITY_LEVEL_TRUSTED_ENVIRONMENT,
                rootOfTrust = false,
            ),
        )

        assertNull(description.deviceLocked)
        assertNull(description.verifiedBootState)
        assertNull(description.verifiedBootStateValue)
        // The level itself is still readable — the two are independent claims.
        assertEquals("TrustedEnvironment", description.securityLevel)
    }

    // ---- refusals -----------------------------------------------------------

    @Test
    fun `a certificate with no attestation extension yields null, not an error`() {
        val plain = TestCertificates.certificate(
            subjectCn = "Ordinary CA",
            subjectPublicKey = keyPair.public,
            issuerCn = "Ordinary CA",
            issuerPrivateKey = keyPair.private,
        )

        assertNull(AttestationExtensionParser.parse(plain))
    }

    @Test
    fun `a truncated KeyDescription throws rather than returning a partial reading`() {
        val full = TestCertificates.keyDescription(
            securityLevel = AttestationExtensionParser.SECURITY_LEVEL_STRONGBOX,
        )

        assertThrows(AttestationExtensionParser.MalformedExtensionException::class.java) {
            AttestationExtensionParser.parseKeyDescription(full.copyOf(full.size / 2))
        }
    }

    @Test
    fun `a KeyDescription with too few fields throws`() {
        val short = TestCertificates.Der.sequence(
            TestCertificates.Der.integer(300),
            TestCertificates.Der.enumerated(
                AttestationExtensionParser.SECURITY_LEVEL_STRONGBOX,
            ),
        )

        val error = assertThrows(AttestationExtensionParser.MalformedExtensionException::class.java) {
            AttestationExtensionParser.parseKeyDescription(short)
        }
        assertTrue(error.message!!.contains("only 2 fields"))
    }

    @Test
    fun `a KeyDescription that is not a SEQUENCE throws`() {
        val notASequence = TestCertificates.Der.octetString(ByteArray(16))

        assertThrows(AttestationExtensionParser.MalformedExtensionException::class.java) {
            AttestationExtensionParser.parseKeyDescription(notASequence)
        }
    }

    /**
     * A length header claiming more bytes than the buffer holds is the classic
     * way to walk a naive DER reader off the end of its input.
     */
    @Test
    fun `a length that overruns the buffer throws`() {
        // SEQUENCE, length 0x7f, but only three bytes of content follow.
        val overrun = byteArrayOf(0x30, 0x7F, 0x02, 0x01, 0x01)

        assertThrows(AttestationExtensionParser.MalformedExtensionException::class.java) {
            AttestationExtensionParser.parseKeyDescription(overrun)
        }
    }

    @Test
    fun `an oversized integer width is rejected instead of being silently narrowed`() {
        // attestationVersion encoded in nine bytes — not a plausible version, and
        // narrowing it would quietly produce some other number.
        val wide = TestCertificates.Der.sequence(
            TestCertificates.Der.tlv(0x02, ByteArray(9) { 0x01 }),
            TestCertificates.Der.enumerated(1),
            TestCertificates.Der.integer(300),
            TestCertificates.Der.enumerated(1),
            TestCertificates.Der.octetString(ByteArray(4)),
            TestCertificates.Der.octetString(ByteArray(0)),
            TestCertificates.Der.sequence(),
            TestCertificates.Der.sequence(),
        )

        assertThrows(AttestationExtensionParser.MalformedExtensionException::class.java) {
            AttestationExtensionParser.parseKeyDescription(wide)
        }
    }
}
