package com.realitylock.app.export

import com.realitylock.app.crypto.Hashing
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Packs an [EvidenceBundle] into a single self-contained ZIP archive and returns
 * it as bytes.
 *
 * ## What this is for
 *
 * Until now the app could export two PDFs *about* an event — the verification
 * certificate and the BSA 2023 s.63 draft annexure — but there was no way to get
 * the evidence itself off the phone: no `ZipOutputStream`, no `FileProvider` and
 * no `ACTION_SEND` existed anywhere in the codebase. Combined with an ephemeral
 * backend that had already lost everything previously synced (a probe on
 * 2026-08-06 returned `events: 0`), that left the capturing device as the only
 * reliable copy of every proof package it held. This class is the way out: one
 * file that can be attached to an email to a lawyer, filed with a court, or
 * copied somewhere safe before the phone is lost, damaged or seized.
 *
 * ## Archive layout
 *
 * ```
 *   README.txt        plain-language explanation for a non-technical recipient
 *   MANIFEST.txt      SHA-256 of every other entry, plus export time and version
 *   <eventId>.json    the proof package, byte-identical to the signed original
 *   <eventId>.jpg     the media, byte-identical to what the camera produced
 * ```
 *
 * `README.txt` is written first because that is the order most archive viewers
 * list entries in, and the first thing a court clerk opens should be the
 * document that explains what they are looking at.
 *
 * ## Byte-identity
 *
 * The two evidence entries are written with a single `write` of the arrays the
 * bundle carries. There is no parse, no re-encode, no charset conversion and no
 * line-ending translation on that path — the proof package's metadata hash and
 * the ECDSA signature cover those exact bytes, and a re-serialization differing
 * by one space would break both in a way indistinguishable from tampering
 * (ADR-0006 §2). DEFLATE is lossless, so compression does not touch this: what
 * comes out of the recipient's unzip is what went in.
 *
 * `MANIFEST.txt` is hashed from the *same* arrays that are written, so it cannot
 * describe an archive other than the one it ships in.
 *
 * ## Return bytes, do not write files
 *
 * Follows [com.realitylock.app.certificate.CertificateRenderer]: the caller
 * decides where the archive goes, normally by handing it to the system
 * "save as"/share dialog. Nothing here touches the filesystem, launches an
 * intent, or needs a `Context` — which is also why the whole feature is
 * exercisable in plain JVM unit tests.
 */
class EvidenceBundleExporter {

    /**
     * Builds the archive.
     *
     * Entry order is fixed and content is fully determined by [bundle], so two
     * exports of the same event produce identical bytes apart from the export
     * timestamp that the bundle itself carries. Entry modification times are
     * pinned to [FIXED_ENTRY_TIME_MILLIS] rather than "now" for the same reason:
     * a clock reading buried in ZIP headers would make two otherwise identical
     * archives differ, and an unexplained difference between two copies of an
     * exhibit is a question nobody wants to have to answer.
     *
     * @return the complete ZIP, ready to be handed to a save/share target.
     */
    fun export(bundle: EvidenceBundle): ByteArray {
        val readme = EvidenceBundleText.readme(bundle).toByteArray(Charsets.UTF_8)

        // The manifest covers every entry except itself, in write order. Built
        // from the byte arrays that are about to be written — not re-read from
        // the finished ZIP — so the listed hashes and the archived bytes have a
        // single common source.
        val hashedEntries = listOf(
            EvidenceBundleText.HashedEntry(EvidenceBundleText.README_ENTRY_NAME, hex(readme)),
            EvidenceBundleText.HashedEntry(bundle.packageEntryName, hex(bundle.packageBytes)),
            EvidenceBundleText.HashedEntry(bundle.mediaEntryName, hex(bundle.mediaBytes)),
        )
        val manifest =
            EvidenceBundleText.manifest(bundle, hashedEntries).toByteArray(Charsets.UTF_8)

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            writeEntry(zip, EvidenceBundleText.README_ENTRY_NAME, readme)
            writeEntry(zip, EvidenceBundleText.MANIFEST_ENTRY_NAME, manifest)
            // Verbatim from here down. Whatever else changes in this method, these
            // two arrays must reach `write` untouched.
            writeEntry(zip, bundle.packageEntryName, bundle.packageBytes)
            writeEntry(zip, bundle.mediaEntryName, bundle.mediaBytes)
        }
        return out.toByteArray()
    }

    private fun writeEntry(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        val entry = ZipEntry(name).apply {
            time = FIXED_ENTRY_TIME_MILLIS
            // Declared so a recipient's tooling can sanity-check the entry before
            // inflating it; DEFLATE fills in the compressed size itself.
            size = bytes.size.toLong()
        }
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun hex(bytes: ByteArray): String = Hashing.toHex(Hashing.sha256(bytes))

    private companion object {
        /**
         * Fixed modification time stamped on every entry: 1980-01-02T00:00:00Z.
         *
         * A constant rather than "now", so nothing but the bundle's own contents
         * can vary between two exports. The day chosen sits just inside the ZIP
         * format's DOS timestamp range (which starts at 1980-01-01) with enough
         * margin that no UTC offset from -12 to +14 pushes it below the floor and
         * triggers a silent clamp — `ZipEntry.setTime` converts through the
         * default zone, so the stored field is machine-local either way.
         *
         * The export time a reader should rely on is the ISO-8601 UTC instant
         * written into `MANIFEST.txt` and `README.txt`, not a field in a
         * container header that copying the file around can rewrite.
         */
        const val FIXED_ENTRY_TIME_MILLIS: Long = 315_619_200_000L
    }
}
