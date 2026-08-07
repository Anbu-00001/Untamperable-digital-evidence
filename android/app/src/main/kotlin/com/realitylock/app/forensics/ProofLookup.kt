package com.realitylock.app.forensics

import com.realitylock.app.capture.model.CapturedEvent
import com.realitylock.app.capture.store.EventRepository
import com.realitylock.app.crypto.Hashing
import java.io.InputStream

/**
 * Answers the one question about an arbitrary image that this app can answer
 * with certainty: **is this a Reality Lock capture?**
 *
 * ## Why this outranks the ELA heat-map
 *
 * The Analyze screen used to open straight onto an error-level-analysis image
 * and a table of EXIF flags. Those are genuinely useful to someone who already
 * knows what compression artefacts look like, and close to meaningless to
 * everyone else — they invite the reading "high error score = fake", which is
 * exactly the inference ADR-0005 refuses to support.
 *
 * Meanwhile the app was sitting on a fact it never mentioned. Every capture it
 * made is stored with the SHA-256 of its media bytes. So for any image the user
 * picks, the app can say either:
 *
 *  - *"this is capture <id>, taken at <time>, and the bytes are unchanged"* — a
 *    definite, checkable statement, or
 *  - *"this has no Reality Lock proof"* — equally definite, and the honest lead
 *    for the overwhelmingly common case.
 *
 * Neither statement is a verdict on whether the image is real. That distinction
 * is the whole point: "we cannot vouch for this" is not "this is fake", and the
 * screen has to say the first without implying the second.
 *
 * ## Byte-exact matching, deliberately
 *
 * A digest match is all-or-nothing, and that is the correct behaviour here. An
 * image that has been re-encoded, resized, or stripped of metadata by a
 * messaging app is *not* the bytes that were signed — the signature over the
 * Merkle root does not cover it, and calling it "verified" would be false. The
 * screen says so plainly rather than trying to be helpful with a similarity
 * score that would have no cryptographic meaning.
 */
class ProofLookup(private val repository: EventRepository) {

    sealed interface Result {

        /**
         * The bytes match a stored capture exactly.
         *
         * [signed] distinguishes a capture that carries a signature from one
         * stored before signing completed. Reporting an unsigned capture as
         * proven would be the same overclaim the verifier refuses.
         */
        data class Matched(val event: CapturedEvent, val signed: Boolean) : Result

        /** No stored capture has these bytes. The common, unalarming case. */
        data object NoProof : Result

        /**
         * The image could not be read to hash it. Distinct from [NoProof]: one
         * says the app looked and found nothing, the other says it never looked.
         */
        data class Unreadable(val reason: String) : Result
    }

    fun lookup(openStream: () -> InputStream?): Result {
        val digest = try {
            val stream = openStream() ?: return Result.Unreadable("the image could not be opened")
            stream.use { Hashing.toHex(Hashing.sha256Stream(it)) }
        } catch (e: Exception) {
            return Result.Unreadable(e.message ?: "the image could not be read")
        }

        val match = repository.list().firstOrNull { it.media.sha256 == digest }
            ?: return Result.NoProof

        return Result.Matched(event = match, signed = match.signature != null)
    }
}
