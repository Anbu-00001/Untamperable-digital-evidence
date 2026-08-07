package com.realitylock.app.export

/**
 * The plain-text documents that ship inside an evidence bundle: `README.txt`,
 * written for a non-technical recipient, and `MANIFEST.txt`, the hash list.
 *
 * ## Why this prose is Kotlin constants and not `strings.xml`
 *
 * Every other piece of user-facing prose in this app lives in `res/values`, and
 * the certificate types go out of their way to take theirs as parameters rather
 * than hold literals. This file deliberately breaks that pattern, for two
 * reasons:
 *
 * 1. **These documents travel.** A `README.txt` inside an exported archive is
 *    read on a lawyer's laptop or a court clerk's desktop, long after it leaves
 *    the phone, by someone who never chose this app's locale. Localizing it
 *    would mean the exhibit's own explanation of itself changes depending on a
 *    setting on a device the reader has never seen — and two bundles for the
 *    same event would then disagree in their wording. An exhibit should read the
 *    same to everyone who receives it.
 * 2. **It has to stay stable.** The wording below states what the bundle does
 *    and does not establish. That is an evidentiary claim, so it should change
 *    through a code review, not through a translation pass.
 *
 * The claims themselves are **not invented here**. They are the same claims the
 * exported certificate makes, taken from `certificate_framing_1..3` in
 * `res/values/strings.xml` and re-worded for a reader who has no proof package
 * in front of them:
 *
 * - it establishes that the media and metadata are unaltered since capture and
 *   were signed by one specific key held in the capturing device's keystore;
 * - it does NOT prove the depicted event was real, unstaged or correctly
 *   described;
 * - it is NOT a standalone legal certificate — under the Bharatiya Sakshya
 *   Adhiniyam 2023 s.63 electronic evidence requires certification by a person.
 *
 * That file is read, never edited, by this feature.
 *
 * ## Line endings
 *
 * `\r\n` throughout. These are `.txt` files opened by whoever is handed the
 * archive, and a meaningful share of that audience is on Windows. The cost is
 * that `sha256sum -c MANIFEST.txt` will not consume the file directly, so
 * [MANIFEST_HOW_TO] tells the reader to compute a hash and compare it by eye
 * instead — instructions that work identically on Windows, macOS and Linux.
 * A hardcoded separator also keeps output identical across machines, which
 * `System.lineSeparator()` would not.
 */
internal object EvidenceBundleText {

    const val EOL: String = "\r\n"

    const val README_ENTRY_NAME: String = "README.txt"
    const val MANIFEST_ENTRY_NAME: String = "MANIFEST.txt"

    /**
     * Renders `README.txt`.
     *
     * Ordering is deliberate and matches the certificate's: what the bundle does
     * **not** establish appears before the technical detail, so a reader who
     * skims sees the limits before they see a signature and a hash.
     */
    fun readme(bundle: EvidenceBundle): String = buildDocument {
        heading("REALITY LOCK — EVIDENCE BUNDLE")
        line("Event ID:            ${bundle.eventId}")
        line("Captured (UTC):      ${bundle.capturedAtIso}")
        line("Exported (UTC):      ${bundle.exportedAtIso}")
        blank()

        section("1. WHAT THIS ARCHIVE IS")
        paragraph(
            "This archive is a complete copy of one piece of evidence recorded with " +
                "the Reality Lock app on an Android phone. It contains the original " +
                "files as they were stored on that phone — not a report about them, " +
                "and not a re-saved version of them.",
        )
        blank()
        paragraph("Two of the files are the evidence itself:")
        blank()
        line("  ${bundle.mediaEntryName}")
        line("      The photograph, exactly as the camera produced it.")
        blank()
        line("  ${bundle.packageEntryName}")
        line("      The \"proof package\": a machine-readable record of when and where")
        line("      the photograph was taken, on what device, together with the")
        line("      cryptographic fingerprints and the digital signature that were")
        line("      created at the moment of capture.")
        blank()
        paragraph(
            "Neither file was edited, re-compressed or re-saved on its way into this " +
                "archive. They are the original bytes. That is what makes the checks " +
                "in section 4 possible: altering even a single byte of either file " +
                "would change its fingerprint, and the signature would stop matching.",
        )
        blank()
        paragraph("The other two files describe the archive:")
        blank()
        line("  $MANIFEST_ENTRY_NAME")
        line("      The fingerprint (SHA-256) of every other file here, so you can")
        line("      confirm nothing was altered in transit — in an email, on a USB")
        line("      stick, or in a file-sharing system.")
        blank()
        line("  $README_ENTRY_NAME")
        line("      This document.")
        blank()

        section("2. WHAT IT ESTABLISHES")
        paragraph(
            "If the checks in section 4 pass, they establish that the photograph and " +
                "its accompanying metadata are unaltered since capture, and that they " +
                "were signed by one specific cryptographic key held in the capturing " +
                "device's keystore — a key that cannot be copied off that device.",
        )
        blank()
        paragraph(
            "In ordinary language: the file you are looking at is the file that came " +
                "out of that phone's camera at the recorded moment, and nobody has " +
                "changed it since.",
        )
        blank()

        section("3. WHAT IT DOES NOT PROVE")
        paragraph(
            "This is the part most often over-read, so it is stated plainly:",
        )
        blank()
        bullet(
            "It does NOT prove that the depicted event was real, unstaged, or " +
                "correctly described. A photograph of a staged scene, or of a screen " +
                "showing another image, would carry exactly the same cryptographic " +
                "guarantees. The signature attests to the file, not to the truth of " +
                "what the file shows.",
        )
        blank()
        bullet(
            "It is NOT a standalone legal certificate. Under the Bharatiya Sakshya " +
                "Adhiniyam 2023 s.63, electronic evidence requires certification by a " +
                "person. This archive is produced automatically by software; it " +
                "supplies the technical material a certifier relies on, and it cannot " +
                "certify anything itself. A separate signed certificate from a " +
                "responsible person, and from an independent expert, is still required.",
        )
        blank()
        bullet(
            "It says nothing about what happened to the files AFTER they left the " +
                "phone. That is chain of custody, and it is a matter for the people " +
                "who handled the archive, not for the software that wrote it. The " +
                "hashes in $MANIFEST_ENTRY_NAME are what let you pick that story up " +
                "from the moment of export.",
        )
        blank()

        section("4. HOW TO CHECK THIS ARCHIVE YOURSELF")
        paragraph(
            "You do not have to take this archive's word for anything, and you do not " +
                "need the Reality Lock app to check it. Everything needed is inside.",
        )
        blank()
        line("STEP A — confirm the archive arrived intact. Anyone can do this.")
        blank()
        paragraph(
            "  Extract the archive, then compute the SHA-256 fingerprint of each " +
                "file and compare it, character by character, with the value listed " +
                "in $MANIFEST_ENTRY_NAME. Use whichever command matches your computer:",
        )
        blank()
        line("    Windows :  certutil -hashfile \"${bundle.mediaEntryName}\" SHA256")
        line("    macOS   :  shasum -a 256 \"${bundle.mediaEntryName}\"")
        line("    Linux   :  sha256sum \"${bundle.mediaEntryName}\"")
        blank()
        paragraph(
            "  If every fingerprint matches, the files are exactly as they were when " +
                "the archive was written. If any differs, the file was altered or " +
                "corrupted after export, and you should ask for a fresh copy.",
        )
        blank()
        line("STEP B — confirm the photograph is the one the proof covers.")
        blank()
        paragraph(
            "  Open ${bundle.packageEntryName} in any text editor and find the field " +
                "\"sha256\" inside the \"media\" section. It should read:",
        )
        blank()
        line("    ${bundle.mediaSha256}")
        blank()
        paragraph(
            "  That is the same fingerprint you computed in step A for the " +
                "photograph. It was recorded at capture time and is covered by the " +
                "signature, so it ties this specific image to this specific proof.",
        )
        blank()
        line("STEP C — verify the signature. This step needs a technical examiner.")
        blank()
        paragraph(
            "  Give ${bundle.packageEntryName} to someone comfortable with " +
                "cryptography, or to any Reality Lock verifier. The package is " +
                "self-contained and the procedure is written down in the project's " +
                "proof-package specification. In outline:",
        )
        blank()
        line("    1. Canonicalise the \"metadata\" object (RFC 8785) and hash it with")
        line("       ${bundle.hashAlgorithm}; it must equal merkle.leaves.metadata.")
        line("    2. Hash the photograph with ${bundle.hashAlgorithm}; it must equal")
        line("       merkle.leaves.media.")
        line("    3. Combine the two leaves as described by merkle.scheme; the result")
        line("       must equal merkle.root, which is:")
        line("         ${bundle.merkleRoot}")
        line("    4. Verify signature.value over those root bytes using")
        line("       ${bundle.signatureAlgorithm} and the public key in")
        line("       signature.publicKey.")
        line("    5. If signature.attestationCertificateChain is present, check that it")
        line("       chains to a Google hardware-attestation root. That is what shows")
        line("       the signing key really was generated inside the phone's secure")
        line("       hardware and never left it.")
        blank()
        paragraph(
            "  A failure at any step means the archive does not stand up. A failure " +
                "is informative: it tells you the files no longer match what was " +
                "signed, not merely that a check was skipped.",
        )
        blank()

        section("5. IF SOMETHING IS MISSING")
        paragraph(
            "This archive is written all-or-nothing. The exporter refuses to produce " +
                "a bundle for an unsigned capture, and refuses to produce one whose " +
                "photograph is missing or no longer matches its recorded fingerprint. " +
                "So a bundle that exists is a bundle that was complete at the moment " +
                "it was written. If a file is absent now, it was removed after export.",
        )
        blank()
        rule()
        line("Produced by Reality Lock ${bundle.exportingAppVersion}.")
        line("Captured by Reality Lock ${bundle.capturingAppVersion}.")
    }

    /**
     * Renders `MANIFEST.txt` from [entries], the archive's other files in the
     * order they are written.
     *
     * `MANIFEST.txt` cannot list itself — a file cannot contain its own hash —
     * and the document says so rather than leaving the omission to be noticed.
     */
    fun manifest(bundle: EvidenceBundle, entries: List<HashedEntry>): String = buildDocument {
        heading("REALITY LOCK — EVIDENCE BUNDLE MANIFEST")
        line("Event ID:            ${bundle.eventId}")
        line("Captured (UTC):      ${bundle.capturedAtIso}")
        line("Exported (UTC):      ${bundle.exportedAtIso}")
        line("Exported by:         Reality Lock ${bundle.exportingAppVersion}")
        line("Captured by:         Reality Lock ${bundle.capturingAppVersion}")
        blank()

        section("FILE HASHES")
        paragraph(MANIFEST_HOW_TO)
        blank()
        paragraph(
            "$MANIFEST_ENTRY_NAME is not listed below, because a file cannot contain " +
                "its own hash. Every other file in the archive is listed. Each line is " +
                "the ${bundle.hashAlgorithm} hash in lowercase hexadecimal, two spaces, " +
                "then the file name.",
        )
        blank()
        for (entry in entries) {
            line("${entry.sha256Hex}  ${entry.name}")
        }
        blank()

        section("PROOF SUMMARY")
        paragraph(
            "Reproduced here so this page can be cross-checked against a Reality Lock " +
                "certificate or s.63 annexure for the same event without opening the " +
                "proof package.",
        )
        blank()
        line("Hash algorithm:      ${bundle.hashAlgorithm}")
        line("Signature algorithm: ${bundle.signatureAlgorithm}")
        line("Media SHA-256:       ${bundle.mediaSha256}")
        line("Merkle root:         ${bundle.merkleRoot}")
        blank()
        rule()
        paragraph(
            "These hashes cover transit only: they show the files are unchanged since " +
                "this archive was written. What the evidence itself does and does not " +
                "establish is set out in $README_ENTRY_NAME, section 3.",
        )
    }

    /** One archive entry as the manifest describes it. */
    data class HashedEntry(val name: String, val sha256Hex: String)

    private const val MANIFEST_HOW_TO: String =
        "To check a file, compute its SHA-256 hash and compare it with the matching " +
            "line below: `certutil -hashfile <file> SHA256` on Windows, " +
            "`shasum -a 256 <file>` on macOS, `sha256sum <file>` on Linux."

    private const val RULE_WIDTH = 74

    // ---- tiny text builder -------------------------------------------------
    // Wrapping is done here rather than left to the reader's viewer: these files
    // are opened in Notepad as often as in anything else, and Notepad does not
    // soft-wrap by default, so an unwrapped paragraph becomes one line the
    // reader has to scroll sideways through.

    private class DocumentBuilder {
        private val out = StringBuilder()

        fun line(text: String) {
            out.append(text).append(EOL)
        }

        fun blank() = line("")

        fun rule() = line("-".repeat(RULE_WIDTH))

        fun heading(text: String) {
            line(text)
            line("=".repeat(RULE_WIDTH))
            blank()
        }

        fun section(text: String) {
            line(text)
            line("-".repeat(text.length))
        }

        fun paragraph(text: String) = wrap(text, indent = "").forEach(::line)

        /** A `*` bullet whose continuation lines hang under the text, not the mark. */
        fun bullet(text: String) = wrap("  * " + text.trim(), indent = "    ").forEach(::line)

        fun build(): String = out.toString()

        /**
         * Greedy word wrap. [indent] prefixes every line after the first; when it
         * is empty, continuation lines inherit the input's own leading spaces.
         */
        private fun wrap(text: String, indent: String): List<String> {
            val leading = text.takeWhile { it == ' ' }
            val continuation = indent.ifEmpty { leading }
            val words = text.trim().split(' ').filter { it.isNotEmpty() }
            if (words.isEmpty()) return listOf("")

            val lines = mutableListOf<String>()
            var current = StringBuilder(leading).append(words.first())
            for (word in words.drop(1)) {
                if (current.length + 1 + word.length > RULE_WIDTH) {
                    lines += current.toString()
                    current = StringBuilder(continuation).append(word)
                } else {
                    current.append(' ').append(word)
                }
            }
            lines += current.toString()
            return lines
        }
    }

    private fun buildDocument(block: DocumentBuilder.() -> Unit): String =
        DocumentBuilder().apply(block).build()
}
