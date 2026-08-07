package com.realitylock.app.verify

import java.security.cert.X509Certificate

/**
 * Reads the Android Key Attestation extension — OID `1.3.6.1.4.1.11129.2.1.17`
 * — out of a leaf certificate, **on the device, with no network**.
 *
 * This is the Kotlin counterpart of `backend/src/services/attestationExtension.js`
 * and deliberately mirrors it field for field, so the answer a phone computes
 * offline and the answer the backend computes are the same answer. It exists
 * because reading the extension is the one attestation question that *can* be
 * settled without a server: the bytes are already in the package.
 *
 * ### Why a hand-written DER reader
 * The JDK exposes `getExtensionValue(oid)` — which is how the extension is
 * located here, structurally, rather than by scanning the certificate for the
 * OID's bytes — but nothing that decodes the `KeyDescription` inside it. The
 * alternatives were a new ASN.1 dependency on the app's release classpath or a
 * small parser for one fixed, well-documented structure. The backend rejected a
 * dependency for the same reason and the app has the stronger case: this code
 * ships to users' phones.
 *
 * The reader is therefore minimal and defensive, matching the backend's rules:
 * every read is bounds-checked before it happens; nesting is depth-limited so a
 * crafted certificate cannot drive it into unbounded recursion; nothing is
 * allocated from an attacker-controlled length; and **any** malformed input
 * throws, which the caller turns into `unavailable`. A parse failure must never
 * be able to invent a security level, because a fabricated "StrongBox" is worse
 * than no answer at all.
 *
 * Schema (source.android.com/docs/security/features/keystore/attestation):
 * ```
 * KeyDescription ::= SEQUENCE {
 *   attestationVersion INTEGER, attestationSecurityLevel SecurityLevel,
 *   keyMintVersion INTEGER, keyMintSecurityLevel SecurityLevel,
 *   attestationChallenge OCTET_STRING, uniqueId OCTET_STRING,
 *   softwareEnforced AuthorizationList, hardwareEnforced AuthorizationList }
 * SecurityLevel ::= ENUMERATED { Software(0), TrustedEnvironment(1), StrongBox(2) }
 * RootOfTrust ::= SEQUENCE { verifiedBootKey OCTET_STRING, deviceLocked BOOLEAN,
 *   verifiedBootState VerifiedBootState, verifiedBootHash OCTET_STRING }
 * VerifiedBootState ::= ENUMERATED { Verified(0), SelfSigned(1), Unverified(2), Failed(3) }
 * ```
 */
object AttestationExtensionParser {

    /** The Android Key Attestation extension OID. */
    const val OID: String = "1.3.6.1.4.1.11129.2.1.17"

    /** Thrown when the extension is present but does not decode. Never swallowed. */
    class MalformedExtensionException(message: String) : IllegalArgumentException(message)

    /**
     * What the leaf certificate says about the key, as far as it says anything.
     *
     * [deviceLocked] and the verified-boot fields are null when the extension
     * carries no `rootOfTrust` — reported as "unknown", never defaulted to the
     * reassuring value.
     */
    data class KeyDescription(
        val attestationVersion: Int,
        val securityLevelValue: Int,
        /**
         * A value this build does not recognise is surfaced as
         * `Unknown(<n>)` rather than mapped to a friendly name, so a future
         * security level is visibly unrecognised instead of silently becoming
         * "Software".
         */
        val securityLevel: String,
        val deviceLocked: Boolean?,
        val verifiedBootStateValue: Int?,
        val verifiedBootState: String?,
    )

    /**
     * The [KeyDescription] carried by [certificate], or null when it carries no
     * attestation extension at all — an ordinary state for the CA certificates
     * in a chain, and not an error.
     *
     * @throws MalformedExtensionException when the extension exists but does not
     *   parse, so a damaged or forged structure can never yield a security level.
     */
    fun parse(certificate: X509Certificate): KeyDescription? {
        // getExtensionValue returns the DER encoding of the extnValue OCTET
        // STRING, so one layer has to come off before the KeyDescription starts.
        val wrapped = certificate.getExtensionValue(OID) ?: return null
        return parseKeyDescription(unwrapOctetString(wrapped))
    }

    /** Exposed for tests and for callers holding raw extension bytes. */
    internal fun parseKeyDescription(extensionValue: ByteArray): KeyDescription {
        val keyDescription = readTlv(extensionValue, 0)
        if (!keyDescription.constructed) throw malformed("KeyDescription is not a SEQUENCE")

        val fields = children(extensionValue, keyDescription.valueStart, keyDescription.valueEnd)
        if (fields.size <= IDX_HARDWARE_ENFORCED) {
            throw malformed("KeyDescription has only ${fields.size} fields")
        }

        val securityLevelValue =
            readSmallInteger(extensionValue, fields[IDX_ATTESTATION_SECURITY_LEVEL])
        val base = KeyDescription(
            attestationVersion = readSmallInteger(extensionValue, fields[0]),
            securityLevelValue = securityLevelValue,
            securityLevel = SECURITY_LEVELS.getOrNull(securityLevelValue)
                ?: "Unknown($securityLevelValue)",
            deviceLocked = null,
            verifiedBootStateValue = null,
            verifiedBootState = null,
        )

        // rootOfTrust is read ONLY from hardwareEnforced. The same tag can appear
        // in softwareEnforced, where it is whatever the OS chose to assert —
        // precisely the claim a compromised OS would forge.
        val hardware = fields[IDX_HARDWARE_ENFORCED]
        val holder = children(extensionValue, hardware.valueStart, hardware.valueEnd)
            .firstOrNull { it.constructed && it.tagNumber == TAG_ROOT_OF_TRUST }
            ?: return base

        val rootOfTrust = children(extensionValue, holder.valueStart, holder.valueEnd).firstOrNull()
        if (rootOfTrust == null || !rootOfTrust.constructed) {
            throw malformed("rootOfTrust is not a SEQUENCE")
        }

        val rotFields = children(extensionValue, rootOfTrust.valueStart, rootOfTrust.valueEnd)
        if (rotFields.size <= IDX_VERIFIED_BOOT_STATE) {
            throw malformed("RootOfTrust has only ${rotFields.size} fields")
        }

        val locked = rotFields[IDX_DEVICE_LOCKED]
        val stateValue = readSmallInteger(extensionValue, rotFields[IDX_VERIFIED_BOOT_STATE])
        return base.copy(
            deviceLocked = extensionValue[locked.valueStart].toInt() != 0x00,
            verifiedBootStateValue = stateValue,
            verifiedBootState = VERIFIED_BOOT_STATES.getOrNull(stateValue)
                ?: "Unknown($stateValue)",
        )
    }

    // --- minimal DER reader --------------------------------------------------

    private fun unwrapOctetString(der: ByteArray): ByteArray {
        val tlv = readTlv(der, 0)
        if (tlv.tagNumber != TAG_OCTET_STRING || tlv.constructed) {
            throw malformed("extnValue is not a primitive OCTET STRING")
        }
        return der.copyOfRange(tlv.valueStart, tlv.valueEnd)
    }

    private data class Tlv(
        val tagNumber: Int,
        val constructed: Boolean,
        val valueStart: Int,
        val valueEnd: Int,
    )

    /**
     * Reads one TLV at [pos]. Throws on any truncation rather than returning a
     * partial read.
     */
    private fun readTlv(buf: ByteArray, pos: Int, depth: Int = 0): Tlv {
        if (depth > MAX_DEPTH) throw malformed("DER nesting too deep")
        if (pos + 2 > buf.size) throw malformed("truncated DER: no room for tag and length")

        val first = buf[pos].toInt() and 0xFF
        val constructed = (first and 0x20) != 0
        var tagNumber = first and 0x1F
        var cursor = pos + 1

        // High-tag-number form: 0x1f means the tag continues in base-128 bytes.
        // Needed here because rootOfTrust is [704], far above the 30 the short
        // form holds.
        if (tagNumber == 0x1F) {
            tagNumber = 0
            while (true) {
                if (cursor >= buf.size) throw malformed("truncated DER: unterminated tag")
                val byte = buf[cursor].toInt() and 0xFF
                cursor += 1
                tagNumber = tagNumber * TAG_CONTINUATION_BASE + (byte and 0x7F)
                if ((byte and 0x80) == 0) break
                if (tagNumber > MAX_TAG_NUMBER) throw malformed("implausible DER tag number")
            }
        }

        if (cursor >= buf.size) throw malformed("truncated DER: no length byte")
        var length = buf[cursor].toInt() and 0xFF
        cursor += 1

        if ((length and 0x80) != 0) {
            val lengthBytes = length and 0x7F
            // Indefinite length is not valid DER, and multi-megabyte lengths in a
            // certificate extension are not plausible input.
            if (lengthBytes == 0 || lengthBytes > MAX_LENGTH_BYTES) {
                throw malformed("unsupported DER length form")
            }
            if (cursor + lengthBytes > buf.size) throw malformed("truncated DER length")
            length = 0
            for (i in 0 until lengthBytes) {
                length = length * BYTE_BASE + (buf[cursor + i].toInt() and 0xFF)
                if (length < 0) throw malformed("implausible DER length")
            }
            cursor += lengthBytes
        }

        val valueEnd = cursor + length
        if (valueEnd > buf.size || valueEnd < cursor) {
            throw malformed("DER value runs past the end of the buffer")
        }
        return Tlv(tagNumber, constructed, cursor, valueEnd)
    }

    /** Every direct child TLV of a constructed value spanning `[start, end)`. */
    private fun children(buf: ByteArray, start: Int, end: Int, depth: Int = 0): List<Tlv> {
        val out = mutableListOf<Tlv>()
        var pos = start
        while (pos < end) {
            val tlv = readTlv(buf, pos, depth + 1)
            if (tlv.valueEnd > end) throw malformed("DER child overruns its parent")
            out += tlv
            pos = tlv.valueEnd
        }
        return out
    }

    /** An INTEGER/ENUMERATED value as an Int. Rejects oversized encodings. */
    private fun readSmallInteger(buf: ByteArray, tlv: Tlv): Int {
        val length = tlv.valueEnd - tlv.valueStart
        if (length < 1 || length > MAX_INTEGER_BYTES) throw malformed("unexpected integer width")
        var value = 0
        for (i in tlv.valueStart until tlv.valueEnd) {
            value = value * BYTE_BASE + (buf[i].toInt() and 0xFF)
        }
        return value
    }

    private fun malformed(message: String) = MalformedExtensionException(message)

    /** `rootOfTrust [704] EXPLICIT` inside an AuthorizationList. */
    private const val TAG_ROOT_OF_TRUST = 704
    private const val TAG_OCTET_STRING = 0x04

    /** KeyDescription field positions. */
    private const val IDX_ATTESTATION_SECURITY_LEVEL = 1
    private const val IDX_HARDWARE_ENFORCED = 7

    /** RootOfTrust field positions. */
    private const val IDX_DEVICE_LOCKED = 1
    private const val IDX_VERIFIED_BOOT_STATE = 2

    private val SECURITY_LEVELS = listOf("Software", "TrustedEnvironment", "StrongBox")
    private val VERIFIED_BOOT_STATES = listOf("Verified", "SelfSigned", "Unverified", "Failed")

    /** `Software(0)` — the level that contradicts the presence of a chain. */
    const val SECURITY_LEVEL_SOFTWARE: Int = 0
    const val SECURITY_LEVEL_TRUSTED_ENVIRONMENT: Int = 1
    const val SECURITY_LEVEL_STRONGBOX: Int = 2

    private const val MAX_DEPTH = 24
    private const val MAX_LENGTH_BYTES = 4
    private const val MAX_INTEGER_BYTES = 4
    private const val MAX_TAG_NUMBER = 0xFFFFFF
    private const val TAG_CONTINUATION_BASE = 128
    private const val BYTE_BASE = 256
}
