package com.realitylock.app.certificate

import com.realitylock.app.capture.model.CapturedEvent
import com.realitylock.app.verify.VerificationReport

/**
 * Everything that appears on the exported certificate, assembled as plain data.
 *
 * Separated from the drawing code so the *content* — which is the part with
 * evidentiary and legal consequences — is decided and tested independently of
 * fonts and page geometry.
 */
data class CertificateContent(
    val title: String,
    val eventId: String,
    val capturedAtIso: String,
    val merkleRoot: String,
    val mediaSha256: String,
    val hashAlgorithm: String,
    val signatureAlgorithm: String,
    val deviceDescription: String,
    val locationSummary: String,
    val verdictLabel: String,
    /** Label/outcome pairs, in the spec's check order. */
    val checkRows: List<Pair<String, String>>,
    /**
     * Printed in place of the check table when [checkRows] is empty, so an absent
     * breakdown is stated rather than left as a gap under the verdict heading.
     * Carried as content, not a literal in the renderer, because it is user-facing
     * prose and belongs with the rest of the translatable text.
     */
    val checksAbsentNotice: String,
    val advisories: List<String>,
    /**
     * What this document does and does not establish. Non-empty by construction —
     * see [requireFraming].
     */
    val framing: List<String>,
    val verificationUrl: String,
    val generatedAtIso: String,
) {
    init {
        requireFraming(framing)
    }

    companion object {
        /**
         * A certificate without its framing must not exist.
         *
         * `research/06` §7 is explicit that the document may not present itself as
         * self-sufficient proof, and BSA 2023 s.63 requires human certification. A
         * bug that dropped the caveats would produce a page that *looks* like an
         * official authenticity certificate and claims more than the system can
         * support — so this is enforced in the constructor rather than trusted to
         * whoever assembles the content.
         */
        fun requireFraming(framing: List<String>) {
            require(framing.isNotEmpty()) {
                "a certificate must carry its what-this-proves framing; " +
                    "see research/06 §7 and ADR-0006"
            }
        }

        /**
         * Builds the content for [event] and its [report].
         *
         * Location is summarised to **three decimal places** (~100 m). The exact
         * coordinate is in the proof package for a verifier who needs it; a printed
         * page that may be shared, filed or photographed does not need to pin
         * someone's position to the metre.
         *
         * ## Why the verdict arrives as a labeller rather than a string
         *
         * This function previously took a pre-resolved `verdictLabel: String`
         * alongside [report], with nothing tying the two together. The caller
         * resolved it once from whichever report happened to be on screen and
         * reused it for every event, so exporting a certificate for a *different,
         * never-verified* event printed the other event's verdict — a bold
         * "VERIFIED" heading above no check table at all, which is the single most
         * misleading document this system could emit.
         *
         * Taking a labeller instead makes that unrepresentable: the verdict and the
         * check rows are now both derived from [report] here, so they cannot
         * disagree, and a caller has no way to supply a verdict that did not come
         * from the report being printed. [notVerifiedLabel] covers the genuinely
         * unverified case, which is a real state and not an error.
         */
        fun from(
            event: CapturedEvent,
            report: VerificationReport?,
            title: String,
            verdictLabeller: (VerificationReport.Verdict) -> String,
            notVerifiedLabel: String,
            checksAbsentNotice: String,
            framing: List<String>,
            verificationUrl: String,
            generatedAtIso: String,
            checkLabeller: (String) -> String = { it },
        ): CertificateContent {
            val location = event.metadata.location
            return CertificateContent(
                title = title,
                eventId = event.eventId,
                capturedAtIso = event.metadata.timestamp.iso8601,
                merkleRoot = event.merkle?.root.orEmpty(),
                mediaSha256 = event.media.sha256.orEmpty(),
                hashAlgorithm = event.merkle?.algorithm.orEmpty(),
                signatureAlgorithm = event.signature?.algorithm.orEmpty(),
                deviceDescription = buildString {
                    append(event.metadata.device.manufacturer)
                    append(' ')
                    append(event.metadata.device.model)
                    append(" (API ")
                    append(event.metadata.device.sdkInt)
                    append(')')
                },
                locationSummary = if (location == null) {
                    "not recorded"
                } else {
                    val lat = String.format("%.3f", location.latitude)
                    val lon = String.format("%.3f", location.longitude)
                    val mock = if (location.isMock) " — REPORTED AS MOCK" else ""
                    "$lat, $lon (±${location.accuracyMeters.toInt()} m)$mock"
                },
                // Both of the next two lines read from the same `report`. That is the
                // invariant: a verdict is printed only when the evidence for it is
                // printed alongside.
                verdictLabel = report?.let { verdictLabeller(it.verdict) } ?: notVerifiedLabel,
                checkRows = report?.checks?.map { checkLabeller(it.name) to it.outcome.name }
                    ?: emptyList(),
                checksAbsentNotice = checksAbsentNotice,
                advisories = report?.advisories.orEmpty(),
                framing = framing,
                verificationUrl = verificationUrl,
                generatedAtIso = generatedAtIso,
            )
        }
    }
}
