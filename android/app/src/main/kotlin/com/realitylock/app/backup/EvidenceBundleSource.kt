package com.realitylock.app.backup

/**
 * Supplies the bytes this package copies to the user's chosen folder.
 *
 * ## Why this interface exists at all
 *
 * The backup feature is a *destination and delivery* layer. It decides where a
 * copy goes, when it is written, whether the write actually landed, and what to
 * tell the user when it did not. It deliberately does **not** decide what an
 * evidence bundle contains — that belongs to
 * `com.realitylock.app.export.EvidenceBundleExporter`, which was built
 * separately. Depending on this one-method interface rather than on the exporter
 * keeps the two independently testable and lets the delivery logic run in plain
 * JVM unit tests against a fake that returns four bytes.
 *
 * ## What implementations MUST return
 *
 * The **complete evidence bundle** — proof package + media + manifest — never a
 * bare JPEG.
 *
 * That restriction is the whole point of the feature and not a stylistic
 * preference. A loose photo sitting in a backup folder carries no proof of
 * anything: nothing binds it to a signature, a Merkle root, a capture time or a
 * device. It is worse than an empty folder, because it is a decoy — someone can
 * find it years later, believe it is the evidence, and present it. The bundle is
 * what makes a copy independently verifiable, so the bundle is what gets
 * written.
 *
 * Implementations should throw if they cannot produce a complete bundle. A
 * thrown exception is recorded as a per-event failure and surfaced; returning a
 * partial or empty array would be recorded as a successful backup of nothing.
 */
fun interface EvidenceBundleSource {

    /**
     * The full evidence bundle for [eventId] as a self-contained archive.
     *
     * @throws Exception if the bundle cannot be produced — media missing, proof
     *         package unreadable, event unknown. Never return a partial bundle.
     */
    fun bundleFor(eventId: String): ByteArray
}
