package com.realitylock.app.export

import com.realitylock.app.capture.model.CapturedEvent
import com.realitylock.app.core.config.CaptureConfig
import com.realitylock.app.crypto.Hashing
import org.json.JSONObject

/**
 * A validated, ready-to-write evidence bundle: the exact stored bytes of one
 * proof package and its media, plus the identifying facts the archive's
 * `MANIFEST.txt` and `README.txt` are written from.
 *
 * ## Why this exists at all
 *
 * Before this package, Reality Lock could export two PDFs *about* an event — the
 * verification certificate and the BSA 2023 s.63 draft annexure — and nothing
 * that contained the event itself. There was no `ZipOutputStream`, no
 * `FileProvider`, and no `ACTION_SEND` anywhere in the app: the proof package and
 * the photograph could be viewed on the phone and nowhere else.
 *
 * That is not a cosmetic gap. The backend is not a safe second copy — it runs on
 * a free tier with an ephemeral filesystem, and a live probe on 2026-08-06
 * returned `events: 0`, i.e. everything previously synced had been wiped. **The
 * capturing device is currently the only reliable copy of the evidence.** An
 * evidence tool that cannot produce the original — to hand a case file to a
 * lawyer, to lodge it with a court, or simply to back it up before the phone is
 * lost, damaged or seized — is not usable as an evidence tool.
 *
 * ## The load-bearing invariant
 *
 * [packageBytes] and [mediaBytes] are carried, and later written, **verbatim**.
 * The proof package's metadata hash and the ECDSA signature cover those exact
 * bytes; a re-serialization that changed one space of indentation, one number's
 * formatting, or one escape sequence would break the hash and the signature in a
 * way indistinguishable from tampering (ADR-0006 §2), and the export would be
 * worthless as evidence. So this type never parses-then-re-encodes. It parses
 * only to *check* (see [from]), and the bytes it hands on are the bytes it was
 * given.
 *
 * ## Separation of concerns
 *
 * Content is decided here, format is decided in [EvidenceBundleExporter] — the
 * same split [com.realitylock.app.certificate.CertificateContent] has against
 * its renderer, and for the same reason: the part with evidentiary consequences
 * is tested independently of the container it ships in.
 *
 * Deliberately **not** a `data class`: two of its properties are `ByteArray`s,
 * and a generated `equals`/`hashCode` would compare them by reference, quietly
 * reporting two identical bundles as different. The same trap
 * [CapturedEvent] avoids by holding motion vectors as `List<Float>`.
 */
class EvidenceBundle private constructor(
    val eventId: String,
    /** Archive entry name for the proof package, `<eventId>.json`. */
    val packageEntryName: String,
    /** The stored proof package, byte-identical to what was hashed and signed. */
    val packageBytes: ByteArray,
    /** Archive entry name for the media, normally `<eventId>.jpg`. */
    val mediaEntryName: String,
    /** The stored media, byte-identical to what the camera produced. */
    val mediaBytes: ByteArray,
    val capturedAtIso: String,
    val merkleRoot: String,
    val hashAlgorithm: String,
    val signatureAlgorithm: String,
    /** Lowercase hex SHA-256 of [mediaBytes], as recorded in the proof package. */
    val mediaSha256: String,
    /** Version of the app that captured the event, from the signed metadata. */
    val capturingAppVersion: String,
    /** Version of the app performing the export, supplied by the caller. */
    val exportingAppVersion: String,
    /** ISO-8601 UTC instant the export was requested. */
    val exportedAtIso: String,
) {

    companion object {

        /**
         * Assembles a bundle for [event] from its **stored** bytes, refusing
         * loudly rather than producing a degraded archive.
         *
         * [packageBytes] must be the sidecar exactly as it sits on disk — read it
         * with [com.realitylock.app.capture.store.EventRepository.readPackageBytes],
         * which exists for precisely this reason. [mediaBytes] must be the media
         * file's contents. Both are nullable because both reads can legitimately
         * come back empty (a deleted file, an unreadable one), and turning that
         * into an explicit refusal here is the entire point: a bundle that
         * silently dropped its photograph would be a smaller ZIP with no visible
         * sign that anything was missing.
         *
         * Every failure below throws [IllegalStateException] with a message
         * written for a human, mirroring
         * [com.realitylock.app.certificate.StatutoryAnnexureContent.from]'s
         * refusal to draw up an annexure for an event that has no hash. One
         * exception type, so the UI has one thing to catch.
         *
         * @throws IllegalStateException if the event is unsigned, either file is
         *   missing, or the package and the media do not belong together.
         */
        @Suppress("CyclomaticComplexMethod")
        fun from(
            event: CapturedEvent,
            packageBytes: ByteArray?,
            mediaBytes: ByteArray?,
            exportingAppVersion: String,
            exportedAtIso: String,
        ): EvidenceBundle {
            // ---- 1. the event must actually be evidence --------------------
            // A bundle with no signature is not evidence; it is a photograph and
            // a text file. Refusing is the honest answer, exactly as the s.63
            // annexure refuses an event with no Merkle root rather than emitting
            // a statutory-looking form with a blank where the hash belongs.
            val merkle = event.merkle
                ?: error(
                    "this event has no Merkle root, so there is nothing to export as " +
                        "evidence — an unsigned capture is a photograph, not a proof package",
                )
            val signature = event.signature
                ?: error(
                    "this event was never signed, so an evidence bundle cannot be built " +
                        "for it — a bundle with no signature proves nothing about the media " +
                        "it contains",
                )

            // ---- 2. both files must be present -----------------------------
            val storedPackage = packageBytes
                ?: error(
                    "the proof package sidecar for event ${event.eventId} is missing or " +
                        "unreadable, so there are no signed bytes to export",
                )
            check(storedPackage.isNotEmpty()) {
                "the proof package sidecar for event ${event.eventId} is empty"
            }
            val storedMedia = mediaBytes
                ?: error(
                    "the media file for event ${event.eventId} is missing or unreadable — " +
                        "refusing to export a bundle without it rather than produce an " +
                        "archive that quietly omits the evidence itself",
                )
            check(storedMedia.isNotEmpty()) {
                "the media file for event ${event.eventId} is empty"
            }

            // ---- 3. the caller's inputs must be self-consistent -------------
            check(exportingAppVersion.isNotBlank()) {
                "an evidence bundle must record which app version produced it"
            }
            check(UTC_ISO_8601.matches(exportedAtIso)) {
                "the export timestamp must be ISO-8601 in UTC (e.g. 2026-08-07T09:12:25Z), " +
                    "was \"$exportedAtIso\" — a local-time stamp on an exhibit invites a " +
                    "dispute about when the copy was actually taken"
            }
            // Entry names are built from the event id. A doctored sidecar could
            // carry an id like "../../evil", and a ZIP entry named that way is a
            // Zip Slip primitive aimed at whoever extracts the archive — a
            // lawyer or a court clerk, on someone else's machine. The schema says
            // a UUID; anything outside a conservative safe set is refused.
            check(SAFE_ENTRY_STEM.matches(event.eventId)) {
                "event id \"${event.eventId}\" is not a safe archive entry name; refusing " +
                    "to build a bundle whose file names could escape the extraction directory"
            }

            // ---- 4. the stored bytes must match the event they claim to be --
            // Parsed only to CHECK. The bytes handed on are untouched.
            val parsed = runCatching { JSONObject(String(storedPackage, Charsets.UTF_8)) }
                .getOrElse { cause ->
                    throw IllegalStateException(
                        "the stored proof package for event ${event.eventId} is not readable " +
                            "JSON, so it cannot be exported as evidence",
                        cause,
                    )
                }
            val storedEventId = parsed.optString(KEY_EVENT_ID)
            check(storedEventId == event.eventId) {
                "the stored proof package is for event \"$storedEventId\" but the export was " +
                    "requested for \"${event.eventId}\" — refusing to ship a package and a " +
                    "photograph that do not belong to each other"
            }
            check(parsed.optJSONObject(KEY_SIGNATURE) != null) {
                "the stored proof package for event ${event.eventId} carries no signature " +
                    "block; the in-memory event claims one, so the sidecar on disk is not " +
                    "the document that was signed"
            }

            // The single check that makes the archive self-proving: the media
            // going into the ZIP must be the media the signed package covers.
            // Without it, an exporter handed the wrong file would produce a
            // bundle that fails verification in the recipient's hands, weeks
            // later, with no way to tell an export bug from tampering.
            val recordedSha256 = parsed.optJSONObject(KEY_MEDIA)?.optString(KEY_SHA256).orEmpty()
            check(recordedSha256.isNotEmpty()) {
                "the stored proof package for event ${event.eventId} records no media hash, " +
                    "so the media in this bundle could not be tied to it"
            }
            val actualSha256 = Hashing.toHex(Hashing.sha256(storedMedia))
            check(actualSha256 == recordedSha256) {
                "the media file for event ${event.eventId} does not match the hash in its " +
                    "signed proof package (recorded $recordedSha256, found $actualSha256) — " +
                    "the file on disk has changed since capture, and exporting it would " +
                    "produce a bundle that fails verification"
            }

            return EvidenceBundle(
                eventId = event.eventId,
                packageEntryName = event.eventId + CaptureConfig.METADATA_EXTENSION_JSON,
                packageBytes = storedPackage,
                mediaEntryName = event.eventId + mediaExtensionOf(event),
                mediaBytes = storedMedia,
                capturedAtIso = event.metadata.timestamp.iso8601,
                merkleRoot = merkle.root,
                hashAlgorithm = merkle.algorithm,
                signatureAlgorithm = signature.algorithm,
                mediaSha256 = recordedSha256,
                capturingAppVersion = with(event.metadata.device) {
                    "$appVersionName ($appVersionCode)"
                },
                exportingAppVersion = exportingAppVersion,
                exportedAtIso = exportedAtIso,
            )
        }

        /**
         * The media's extension, taken from the on-disk file name rather than
         * assumed to be `.jpg`.
         *
         * The store owns the layout ([com.realitylock.app.capture.MediaFileStore]),
         * so reading the extension back off the path keeps the archive correct if
         * video capture ever lands, instead of shipping an MP4 named `.jpg`.
         * Falls back to the configured stills extension when the path carries
         * none.
         */
        private fun mediaExtensionOf(event: CapturedEvent): String {
            val fileName = event.mediaFilePath.substringAfterLast('/')
            val extension = fileName.substringAfterLast('.', "")
            return if (extension.isEmpty() || !SAFE_ENTRY_STEM.matches(extension)) {
                CaptureConfig.MEDIA_EXTENSION_JPEG
            } else {
                ".$extension"
            }
        }

        /** ISO-8601 with an explicit `Z`; anything else is not unambiguously UTC. */
        private val UTC_ISO_8601 =
            Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?Z$""")

        /**
         * Characters allowed in an archive entry stem. No `/`, no `\`, no `..`,
         * no leading dot — see the Zip Slip note in [from].
         */
        private val SAFE_ENTRY_STEM = Regex("""^[A-Za-z0-9][A-Za-z0-9_-]*$""")

        private const val KEY_EVENT_ID = "eventId"
        private const val KEY_SIGNATURE = "signature"
        private const val KEY_MEDIA = "media"
        private const val KEY_SHA256 = "sha256"
    }
}
