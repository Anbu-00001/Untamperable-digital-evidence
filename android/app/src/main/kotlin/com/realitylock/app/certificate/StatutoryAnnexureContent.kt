package com.realitylock.app.certificate

import com.realitylock.app.capture.model.CapturedEvent

/**
 * A space left for a human signature, and nothing else.
 *
 * Deliberately carries **no name, signature, date or credential field**. The
 * point of this document is that a person — not this app — attests to it, so the
 * type is built so that populating a signature is not merely discouraged but
 * unrepresentable. A future refactor cannot accidentally auto-fill what does not
 * exist.
 */
data class SignatoryBlock(
    /** Who must sign, e.g. the person in charge of the device. */
    val role: String,
    /** Why the law requires this particular signatory. */
    val basis: String,
)

/**
 * A **draft technical annexure** shaped like a Bharatiya Sakshya Adhiniyam 2023
 * s.63(4)/Schedule certificate — hash value, algorithm, device particulars and
 * production method — for a human certifier to reference, complete and sign.
 *
 * This is `research/06` §8 item 3, and its framing is the whole feature. From
 * `research/06` §1.3:
 *
 * - a s.63(4) certificate must be signed by a **natural person** in a responsible
 *   position (the device custodian), and the BSA additionally requires an
 *   **independent expert** — dual certification;
 * - the certificate must originate from whoever **controls the device**, not from
 *   a third-party app vendor;
 * - it must speak to matters this system cannot know — that the device "was
 *   operating properly throughout", that information was fed in the ordinary
 *   course of activity, and chain-of-custody facts *after* the file leaves the
 *   phone.
 *
 * So Reality Lock generates the evidentiary material a certifier relies on; it
 * cannot be the certifier. This class produces the annexure and is built so it
 * cannot present itself as an executed certificate — see the invariants in
 * [init].
 *
 * Prose arrives as parameters rather than literals because it is user-facing,
 * translatable text and a model layer has no `Context` to resolve resources
 * with — the same reason [CertificateContent] takes its framing from the caller.
 */
data class StatutoryAnnexureContent(
    val title: String,
    /** States, on the document itself, that it is a draft and not a certificate. */
    val draftNotice: String,
    val eventId: String,
    val capturedAtIso: String,
    /** The Schedule's "hash value" — this project's Merkle root over media+metadata. */
    val merkleRoot: String,
    /** The Schedule's stated algorithm, e.g. SHA-256. */
    val hashAlgorithm: String,
    val mediaSha256: String,
    val signatureAlgorithm: String,
    /** Label/value pairs describing the device the record was produced on. */
    val deviceParticulars: List<Pair<String, String>>,
    /** How the record was produced, in plain language. */
    val productionMethod: List<String>,
    /**
     * Matters the signatories must attest to from their own knowledge, because
     * this system cannot establish them. Non-empty by construction: an annexure
     * that listed no such matters would imply the technical output covers the
     * whole statutory test, which is the exact overclaim this document exists to
     * avoid.
     */
    val mattersRequiringHumanAttestation: List<String>,
    /** Blank blocks, one per required signatory. Never populated by this app. */
    val signatories: List<SignatoryBlock>,
    val generatedAtIso: String,
) {
    init {
        require(draftNotice.isNotBlank()) {
            "a statutory annexure must state on its face that it is a draft requiring " +
                "countersignature; see research/06 §8 item 3"
        }
        require(mattersRequiringHumanAttestation.isNotEmpty()) {
            "a statutory annexure must list what its signatories, not this system, " +
                "have to attest to; see research/06 §1.3"
        }
        require(signatories.size >= REQUIRED_SIGNATORIES) {
            "BSA 2023 s.63(4) requires dual certification — a device custodian AND an " +
                "independent expert — so an annexure with fewer than " +
                "$REQUIRED_SIGNATORIES signature blocks understates what the law asks for; " +
                "see research/06 §1.2"
        }
        // The hash value IS the document. An annexure asserting an empty hash would
        // be a form with the one legally load-bearing field missing, which is worse
        // than no annexure at all.
        require(merkleRoot.isNotBlank() && hashAlgorithm.isNotBlank()) {
            "a statutory annexure cannot be produced without a hash value and its algorithm"
        }
    }

    companion object {
        /** Device custodian + independent expert, per BSA 2023 s.63(4). */
        const val REQUIRED_SIGNATORIES: Int = 2

        /**
         * Builds the annexure for [event].
         *
         * Throws when the event carries no Merkle root — i.e. it was never signed.
         * That is deliberate and mirrors `ForensicAnalyzer`'s handling of an
         * unreadable image: the honest response to "there is no hash" is to refuse,
         * not to emit a statutory-looking form with a blank where the hash belongs.
         */
        fun from(
            event: CapturedEvent,
            title: String,
            draftNotice: String,
            deviceParticularLabels: DeviceParticularLabels,
            productionMethod: List<String>,
            mattersRequiringHumanAttestation: List<String>,
            signatories: List<SignatoryBlock>,
            generatedAtIso: String,
        ): StatutoryAnnexureContent {
            val merkle = event.merkle
                ?: error("this event has no hash yet, so no statutory annexure can be drawn up for it")

            return StatutoryAnnexureContent(
                title = title,
                draftNotice = draftNotice,
                eventId = event.eventId,
                capturedAtIso = event.metadata.timestamp.iso8601,
                merkleRoot = merkle.root,
                hashAlgorithm = merkle.algorithm,
                mediaSha256 = event.media.sha256.orEmpty(),
                signatureAlgorithm = event.signature?.algorithm.orEmpty(),
                deviceParticulars = with(event.metadata.device) {
                    listOf(
                        deviceParticularLabels.make to manufacturer,
                        deviceParticularLabels.model to model,
                        deviceParticularLabels.platform to sdkInt.toString(),
                        // The install UUID, NOT IMEI/ANDROID_ID (research/03 §5). It
                        // identifies the installation that produced the record without
                        // being a hardware identifier the user cannot rotate.
                        deviceParticularLabels.installId to installId,
                        deviceParticularLabels.software to "$appVersionName ($appVersionCode)",
                    )
                },
                productionMethod = productionMethod,
                mattersRequiringHumanAttestation = mattersRequiringHumanAttestation,
                signatories = signatories,
                generatedAtIso = generatedAtIso,
            )
        }
    }

    /**
     * Labels for the device-particulars table. Passed in for the same reason the
     * prose is: they are printed, translatable text.
     */
    data class DeviceParticularLabels(
        val make: String,
        val model: String,
        val platform: String,
        val installId: String,
        val software: String,
    )
}
