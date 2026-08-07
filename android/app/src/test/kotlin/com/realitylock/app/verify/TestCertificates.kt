package com.realitylock.app.verify

import java.io.ByteArrayInputStream
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec

/**
 * Builds real, signature-verifiable X.509 certificates inside a JVM unit test.
 *
 * ### Why this is hand-rolled
 * The JDK can *read* certificates but offers no public API to *write* one, and
 * the project has no BouncyCastle dependency — adding one to ship a test fixture
 * would widen the app's supply chain for no runtime benefit. The alternative was
 * to skip the attestation tests entirely, which would leave the chain-linkage and
 * key-binding logic (the part an attacker attacks) covered by nothing.
 *
 * These are genuine certificates: `CertificateFactory` parses them and
 * `X509Certificate.verify()` checks their signatures for real, so a test that
 * says "this chain does not link" is asserting against actual ECDSA
 * verification rather than a stub.
 *
 * The DER writer below is the minimal subset those certificates need. It is test
 * code and makes no attempt to be a general encoder.
 */
object TestCertificates {

    // ---- key material -------------------------------------------------------

    /** A fresh P-256 key pair, the same curve the signing key uses. */
    fun newKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    // ---- certificates -------------------------------------------------------

    /**
     * A certificate binding [subjectPublicKey] to [subjectCn], signed by
     * [issuerPrivateKey].
     *
     * Pass the same key pair as subject and issuer to get a self-signed root.
     */
    fun certificate(
        subjectCn: String,
        subjectPublicKey: PublicKey,
        issuerCn: String,
        issuerPrivateKey: PrivateKey,
        serial: Long = 1L,
        extensions: List<ByteArray> = emptyList(),
    ): X509Certificate {
        val tbs = Der.sequence(
            Der.explicit(0, Der.integer(X509_V3)),
            Der.integer(serial),
            ECDSA_SHA256_ALGORITHM_ID,
            name(issuerCn),
            Der.sequence(
                Der.generalizedTime(NOT_BEFORE),
                Der.generalizedTime(NOT_AFTER),
            ),
            name(subjectCn),
            // PublicKey.getEncoded() is already a DER SubjectPublicKeyInfo.
            subjectPublicKey.encoded,
            *(if (extensions.isEmpty()) {
                emptyArray<ByteArray>()
            } else {
                arrayOf(Der.explicit(EXTENSIONS_TAG, Der.sequence(*extensions.toTypedArray())))
            }),
        )

        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(issuerPrivateKey)
            update(tbs)
            sign()
        }

        val der = Der.sequence(tbs, ECDSA_SHA256_ALGORITHM_ID, Der.bitString(signature))
        return CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(der)) as X509Certificate
    }

    /** `Name ::= SEQUENCE OF RDN`, with a single CN. */
    private fun name(commonName: String): ByteArray = Der.sequence(
        Der.set(Der.sequence(Der.oid(OID_COMMON_NAME), Der.utf8String(commonName))),
    )

    // ---- the key attestation extension --------------------------------------

    /**
     * An `Extension` carrying a `KeyDescription`, ready to hand to [certificate].
     *
     * @param rootOfTrust when false the `hardwareEnforced` list is left empty,
     *   which is the shape that must leave boot state reported as unknown rather
     *   than defaulted to the reassuring value.
     */
    fun attestationExtension(
        securityLevel: Int,
        attestationVersion: Int = ATTESTATION_VERSION,
        rootOfTrust: Boolean = true,
        deviceLocked: Boolean = true,
        verifiedBootState: Int = 0,
    ): ByteArray = Der.sequence(
        Der.oid(AttestationExtensionParser.OID),
        Der.octetString(
            keyDescription(
                securityLevel = securityLevel,
                attestationVersion = attestationVersion,
                rootOfTrust = rootOfTrust,
                deviceLocked = deviceLocked,
                verifiedBootState = verifiedBootState,
            ),
        ),
    )

    /** The bare `KeyDescription` DER, for parser tests that skip the certificate. */
    fun keyDescription(
        securityLevel: Int,
        attestationVersion: Int = ATTESTATION_VERSION,
        rootOfTrust: Boolean = true,
        deviceLocked: Boolean = true,
        verifiedBootState: Int = 0,
    ): ByteArray {
        val hardwareEnforced = if (rootOfTrust) {
            Der.sequence(
                Der.explicit(
                    TAG_ROOT_OF_TRUST,
                    Der.sequence(
                        Der.octetString(ByteArray(VERIFIED_BOOT_KEY_BYTES)),
                        Der.boolean(deviceLocked),
                        Der.enumerated(verifiedBootState),
                        Der.octetString(ByteArray(VERIFIED_BOOT_KEY_BYTES)),
                    ),
                ),
            )
        } else {
            Der.sequence()
        }

        return Der.sequence(
            Der.integer(attestationVersion.toLong()),
            Der.enumerated(securityLevel),
            Der.integer(KEYMINT_VERSION),
            Der.enumerated(securityLevel),
            Der.octetString(ByteArray(CHALLENGE_BYTES) { it.toByte() }),
            Der.octetString(ByteArray(0)),
            Der.sequence(),
            hardwareEnforced,
        )
    }

    // ---- a very small DER writer -------------------------------------------

    object Der {

        fun sequence(vararg parts: ByteArray): ByteArray = tlv(TAG_SEQUENCE, concat(*parts))

        fun set(vararg parts: ByteArray): ByteArray = tlv(TAG_SET, concat(*parts))

        fun integer(value: Long): ByteArray {
            require(value >= 0) { "only non-negative integers are needed here" }
            var remaining = value
            val digits = ArrayDeque<Byte>()
            do {
                digits.addFirst((remaining and 0xFF).toByte())
                remaining = remaining ushr 8
            } while (remaining > 0)
            val body = digits.toList().toByteArray()
            // A leading bit set would read as negative in two's complement.
            return tlv(TAG_INTEGER, if (body[0].toInt() and 0x80 != 0) byteArrayOf(0) + body else body)
        }

        fun enumerated(value: Int): ByteArray = tlv(TAG_ENUMERATED, byteArrayOf(value.toByte()))

        fun boolean(value: Boolean): ByteArray =
            tlv(TAG_BOOLEAN, byteArrayOf(if (value) 0xFF.toByte() else 0x00))

        fun octetString(value: ByteArray): ByteArray = tlv(TAG_OCTET_STRING, value)

        /** BIT STRING with zero unused trailing bits, which is all a signature needs. */
        fun bitString(value: ByteArray): ByteArray = tlv(TAG_BIT_STRING, byteArrayOf(0) + value)

        fun utf8String(value: String): ByteArray = tlv(TAG_UTF8_STRING, value.toByteArray(Charsets.UTF_8))

        fun generalizedTime(value: String): ByteArray =
            tlv(TAG_GENERALIZED_TIME, value.toByteArray(Charsets.US_ASCII))

        fun oid(dotted: String): ByteArray {
            val parts = dotted.split('.').map(String::toInt)
            require(parts.size >= 2) { "not an OID: $dotted" }
            val body = mutableListOf<Byte>()
            body += (parts[0] * OID_FIRST_MULTIPLIER + parts[1]).toByte()
            for (part in parts.drop(2)) {
                body += base128(part)
            }
            return tlv(TAG_OID, body.toByteArray())
        }

        /** `[tagNumber] EXPLICIT`, context class, constructed. */
        fun explicit(tagNumber: Int, value: ByteArray): ByteArray {
            val tagBytes = if (tagNumber < HIGH_TAG_THRESHOLD) {
                byteArrayOf((CONTEXT_CONSTRUCTED or tagNumber).toByte())
            } else {
                byteArrayOf((CONTEXT_CONSTRUCTED or HIGH_TAG_THRESHOLD).toByte()) +
                    base128(tagNumber).toByteArray()
            }
            return tagBytes + length(value.size) + value
        }

        /** Raw TLV, for building deliberately malformed input in tests. */
        fun tlv(tag: Int, value: ByteArray): ByteArray =
            byteArrayOf(tag.toByte()) + length(value.size) + value

        private fun base128(value: Int): List<Byte> {
            var remaining = value
            val digits = ArrayDeque<Byte>()
            digits.addFirst((remaining and 0x7F).toByte())
            remaining = remaining ushr 7
            while (remaining > 0) {
                digits.addFirst(((remaining and 0x7F) or 0x80).toByte())
                remaining = remaining ushr 7
            }
            return digits.toList()
        }

        private fun length(size: Int): ByteArray {
            if (size < LONG_FORM_THRESHOLD) return byteArrayOf(size.toByte())
            var remaining = size
            val digits = ArrayDeque<Byte>()
            while (remaining > 0) {
                digits.addFirst((remaining and 0xFF).toByte())
                remaining = remaining ushr 8
            }
            return byteArrayOf((LONG_FORM_THRESHOLD or digits.size).toByte()) +
                digits.toList().toByteArray()
        }

        private fun concat(vararg parts: ByteArray): ByteArray {
            val out = ByteArray(parts.sumOf { it.size })
            var offset = 0
            for (part in parts) {
                part.copyInto(out, offset)
                offset += part.size
            }
            return out
        }

        private const val TAG_BOOLEAN = 0x01
        private const val TAG_INTEGER = 0x02
        private const val TAG_BIT_STRING = 0x03
        private const val TAG_OCTET_STRING = 0x04
        private const val TAG_OID = 0x06
        private const val TAG_ENUMERATED = 0x0A
        private const val TAG_UTF8_STRING = 0x0C
        private const val TAG_SEQUENCE = 0x30
        private const val TAG_SET = 0x31
        private const val TAG_GENERALIZED_TIME = 0x18

        private const val CONTEXT_CONSTRUCTED = 0xA0
        private const val HIGH_TAG_THRESHOLD = 0x1F
        private const val LONG_FORM_THRESHOLD = 0x80
        private const val OID_FIRST_MULTIPLIER = 40
    }

    /** `AlgorithmIdentifier` for ecdsa-with-SHA256; parameters are absent by spec. */
    private val ECDSA_SHA256_ALGORITHM_ID = Der.sequence(Der.oid("1.2.840.10045.4.3.2"))

    private const val OID_COMMON_NAME = "2.5.4.3"
    private const val X509_V3 = 2L
    private const val EXTENSIONS_TAG = 3
    private const val TAG_ROOT_OF_TRUST = 704
    private const val ATTESTATION_VERSION = 300
    private const val KEYMINT_VERSION = 300L
    private const val CHALLENGE_BYTES = 32
    private const val VERIFIED_BOOT_KEY_BYTES = 32

    // Wide enough that these fixtures never expire out from under the suite.
    private const val NOT_BEFORE = "20200101000000Z"
    private const val NOT_AFTER = "20500101000000Z"
}
