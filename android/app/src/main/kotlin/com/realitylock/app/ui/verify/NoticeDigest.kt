package com.realitylock.app.ui.verify

/**
 * Turns the verifier's long limitation and advisory sentences into a short label
 * and an icon, for [com.realitylock.app.ui.components.NoticeChip].
 *
 * The sentences themselves come from the backend (or from
 * [com.realitylock.app.verify.OfflineProofVerifier]) and are shown in full, word
 * for word, when the chip is tapped — this only picks a headline for them. A
 * sentence nobody here has seen before still gets a headline (its first clause,
 * clipped) and keeps its full text, so a new backend caveat can never go missing;
 * it just reads less polished until a rule is added.
 *
 * Rules are keyed on distinctive phrases, not exact strings, so a rewording that
 * keeps the meaning keeps the label.
 */
object NoticeDigest {

    enum class Glyph {
        UNCHANGED, HARDWARE, BOOT_STATE, NOT_REAL, LEGAL, PHONE_ONLY, NO_ROOT, NO_VERIFIED,
        NO_ATTESTATION, LOCATION, MOCK, GENERIC,
    }

    data class Digest(val glyph: Glyph, val short: String)

    private val limitationRules: List<Pair<String, Digest>> = listOf(
        "ENTIRELY ON THIS DEVICE" to Digest(Glyph.PHONE_ONLY, "Checked on this phone only"),
        "did NOT anchor" to Digest(Glyph.NO_ROOT, "No Google-root or revocation check"),
        "can never return" to Digest(Glyph.NO_VERIFIED, "Can't say VERIFIED offline"),
        "unaltered since capture" to Digest(Glyph.UNCHANGED, "Unchanged since capture"),
        "Hardware backing is supported only" to Digest(Glyph.HARDWARE, "Hardware claim: read Attestation"),
        "Verified Boot" to Digest(Glyph.BOOT_STATE, "Boot state doesn't change the verdict"),
        "does NOT prove the depicted event" to Digest(Glyph.NOT_REAL, "Doesn't prove the event was real"),
        "legal certificate" to Digest(Glyph.LEGAL, "Not a legal certificate"),
    )

    private val advisoryRules: List<Pair<String, Digest>> = listOf(
        "No key attestation chain" to Digest(Glyph.NO_ATTESTATION, "No hardware attestation"),
        "mock" to Digest(Glyph.MOCK, "Mock location flagged"),
        "cross-checked" to Digest(Glyph.LOCATION, "Location not cross-checked"),
    )

    fun limitation(text: String): Digest = match(limitationRules, text)

    fun advisory(text: String): Digest = match(advisoryRules, text)

    private fun match(rules: List<Pair<String, Digest>>, text: String): Digest =
        rules.firstOrNull { (phrase, _) -> text.contains(phrase, ignoreCase = true) }?.second
            ?: Digest(Glyph.GENERIC, headline(text))

    /** First sentence or clause, clipped on a word boundary. */
    internal fun headline(text: String, max: Int = MAX_HEADLINE): String {
        val first = text.trim().split(Regex("""(?<=[.:;—–])\s+|\s[—–]\s"""), limit = 2)[0].trimEnd('.', ':', ';')
        if (first.length <= max) return first
        val cut = first.take(max).substringBeforeLast(' ', first.take(max))
        return "$cut…"
    }

    private const val MAX_HEADLINE = 44
}
