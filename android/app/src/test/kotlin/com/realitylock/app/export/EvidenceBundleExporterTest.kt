package com.realitylock.app.export

import com.realitylock.app.export.EvidenceBundleFixtures.EVENT_ID
import com.realitylock.app.export.EvidenceBundleFixtures.MEDIA_BYTES
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end checks on the exported ZIP.
 *
 * The archive is always read back with [ZipInputStream] rather than `ZipFile`,
 * because it preserves the order entries were written in — and stable order is
 * one of the properties under test.
 */
class EvidenceBundleExporterTest {

    private val exporter = EvidenceBundleExporter()

    private val readmeName = "README.txt"
    private val manifestName = "MANIFEST.txt"
    private val packageName = "$EVENT_ID.json"
    private val mediaName = "$EVENT_ID.jpg"

    // ---- byte-identity: the whole reason this feature exists ---------------

    @Test
    fun `proof package inside the archive is byte-identical to the stored bytes`() {
        val stored = EvidenceBundleFixtures.packageBytes()

        val entries = unzip(exporter.export(EvidenceBundleFixtures.bundle(packageBytes = stored)))

        assertArrayEquals(
            "the exported package must be the signed bytes verbatim — a single " +
                "changed byte breaks the metadata hash and the signature",
            stored,
            entries.getValue(packageName),
        )
    }

    @Test
    fun `the archived package is not a re-serialization`() {
        // The fixture's on-disk formatting is deliberately scruffy. Any parse-and-
        // re-encode would normalise it, so these artefacts surviving the round trip
        // is direct evidence that no re-encode happened.
        val roundTripped = String(
            unzip(exporter.export(EvidenceBundleFixtures.bundle())).getValue(packageName),
            Charsets.UTF_8,
        )

        assertTrue("irregular spacing was normalised", roundTripped.contains("\"eventId\":\""))
        assertTrue("irregular spacing was normalised", roundTripped.contains("\"schemaVersion\" :"))
        assertTrue("padding inside media was normalised", roundTripped.contains("\"byteLength\":16"))
        assertTrue("a non-ASCII character was lost or re-escaped", roundTripped.contains("—"))
        assertTrue("trailing whitespace was trimmed", roundTripped.endsWith("\n        "))
        assertEquals(EvidenceBundleFixtures.packageJson(), roundTripped)
    }

    @Test
    fun `media inside the archive is byte-identical to the stored bytes`() {
        val entries = unzip(exporter.export(EvidenceBundleFixtures.bundle()))

        assertArrayEquals(MEDIA_BYTES, entries.getValue(mediaName))
    }

    @Test
    fun `media bytes survive CR LF NUL and high bytes untouched`() {
        // Guards against anything on the write path treating media as text.
        val archived = unzip(exporter.export(EvidenceBundleFixtures.bundle())).getValue(mediaName)

        assertEquals(MEDIA_BYTES.size, archived.size)
        assertEquals(0x0D.toByte(), archived[4])
        assertEquals(0x0A.toByte(), archived[5])
        assertEquals(0x00.toByte(), archived[6])
        assertEquals(0xFF.toByte(), archived[0])
    }

    // ---- layout ------------------------------------------------------------

    @Test
    fun `archive holds exactly the four documented entries`() {
        val names = order(exporter.export(EvidenceBundleFixtures.bundle()))

        assertEquals(listOf(readmeName, manifestName, packageName, mediaName), names)
    }

    @Test
    fun `entry order is stable across two exports of the same event`() {
        val bundle = EvidenceBundleFixtures.bundle()

        assertEquals(order(exporter.export(bundle)), order(exporter.export(bundle)))
    }

    @Test
    fun `two exports of the same bundle are byte-identical archives`() {
        // Stronger than stable order: nothing in the container may vary run to
        // run, so two copies of an exhibit can never differ for an unexplained
        // reason. Only the export timestamp — carried by the bundle itself — can.
        val bundle = EvidenceBundleFixtures.bundle()

        assertArrayEquals(exporter.export(bundle), exporter.export(bundle))
    }

    @Test
    fun `entry modification times are pinned, not read from the clock`() {
        // The determinism test above cannot see this on its own: DOS timestamps
        // have two-second resolution, so two exports in one test run would share
        // a wall-clock stamp anyway and the archives would still match. Asserting
        // the stamp is a fixed pre-2000 constant is what actually pins it.
        val times = read(exporter.export(EvidenceBundleFixtures.bundle())).map { it.timeMillis }

        assertEquals(4, times.size)
        assertEquals("every entry must carry the same pinned stamp", 1, times.distinct().size)
        assertTrue(
            "entry time ${times.first()} looks like a clock reading, not a constant",
            times.first() < MILLIS_AT_YEAR_2000,
        )
    }

    @Test
    fun `two exports at different times differ only in the timestamped documents`() {
        val first = unzip(exporter.export(EvidenceBundleFixtures.bundle()))
        val second = unzip(
            exporter.export(
                EvidenceBundleFixtures.bundle(exportedAtIso = "2026-09-01T00:00:00Z"),
            ),
        )

        assertArrayEquals(first.getValue(packageName), second.getValue(packageName))
        assertArrayEquals(first.getValue(mediaName), second.getValue(mediaName))
        assertFalse(first.getValue(readmeName).contentEquals(second.getValue(readmeName)))
    }

    // ---- manifest ----------------------------------------------------------

    @Test
    fun `manifest hash of every entry matches that entry's actual bytes`() {
        val entries = unzip(exporter.export(EvidenceBundleFixtures.bundle()))
        val manifest = text(entries.getValue(manifestName))

        for (name in listOf(readmeName, packageName, mediaName)) {
            val expected = EvidenceBundleFixtures.sha256Hex(entries.getValue(name))
            assertTrue(
                "MANIFEST.txt must list $name as $expected; it said:\n$manifest",
                manifest.contains("$expected  $name"),
            )
        }
    }

    @Test
    fun `manifest hashes change when an entry changes`() {
        // Cheap, but it is the assertion that proves the previous test is not
        // passing on a manifest that merely re-hashes whatever it is handed.
        val original = text(
            unzip(exporter.export(EvidenceBundleFixtures.bundle())).getValue(manifestName),
        )
        val other = EvidenceBundleFixtures.MEDIA_BYTES.copyOf() + byteArrayOf(0x21)
        val altered = text(
            unzip(
                exporter.export(
                    EvidenceBundleFixtures.bundle(
                        packageBytes = EvidenceBundleFixtures.packageBytes(
                            mediaSha256 = EvidenceBundleFixtures.sha256Hex(other),
                        ),
                        mediaBytes = other,
                    ),
                ),
            ).getValue(manifestName),
        )

        assertFalse(original == altered)
        assertTrue(altered.contains(EvidenceBundleFixtures.sha256Hex(other)))
    }

    @Test
    fun `manifest does not list itself and says why`() {
        val manifest = text(
            unzip(exporter.export(EvidenceBundleFixtures.bundle())).getValue(manifestName),
        )

        assertFalse(
            "a file cannot contain its own hash",
            Regex("""^[0-9a-f]{64} {2}MANIFEST\.txt$""", RegexOption.MULTILINE)
                .containsMatchIn(manifest),
        )
        assertTrue(manifest, flatten(manifest).contains("cannot contain its own hash"))
    }

    @Test
    fun `manifest records the export timestamp and both app versions`() {
        val manifest = text(
            unzip(exporter.export(EvidenceBundleFixtures.bundle())).getValue(manifestName),
        )

        assertTrue(manifest.contains(EvidenceBundleFixtures.EXPORTED_AT_ISO))
        assertTrue(manifest.contains(EvidenceBundleFixtures.EXPORTING_APP_VERSION))
        assertTrue(manifest.contains(EvidenceBundleFixtures.CAPTURING_APP_VERSION))
        assertTrue(manifest.contains(EVENT_ID))
    }

    @Test
    fun `manifest reproduces the proof summary for cross-checking a certificate`() {
        val manifest = text(
            unzip(exporter.export(EvidenceBundleFixtures.bundle())).getValue(manifestName),
        )

        assertTrue(manifest.contains(EvidenceBundleFixtures.MERKLE_ROOT))
        assertTrue(manifest.contains(EvidenceBundleFixtures.MEDIA_SHA256))
        assertTrue(manifest.contains("SHA256withECDSA"))
    }

    // ---- readme ------------------------------------------------------------

    @Test
    fun `readme states what the bundle does not prove`() {
        val flat = flatten(
            text(unzip(exporter.export(EvidenceBundleFixtures.bundle())).getValue(readmeName)),
        )

        // The three claims are the certificate's, from certificate_framing_1..3.
        assertTrue(flat, flat.contains("unaltered since capture"))
        assertTrue(flat, flat.contains("device's keystore"))
        assertTrue(flat, flat.contains("does NOT prove that the depicted event was real"))
        assertTrue(flat, flat.contains("unstaged, or correctly described"))
        assertTrue(flat, flat.contains("is NOT a standalone legal certificate"))
        assertTrue(flat, flat.contains("Bharatiya Sakshya Adhiniyam 2023 s.63"))
        assertTrue(flat, flat.contains("requires certification by a person"))
    }

    @Test
    fun `readme tells the recipient how to verify the archive on any platform`() {
        val readme = text(
            unzip(exporter.export(EvidenceBundleFixtures.bundle())).getValue(readmeName),
        )

        assertTrue(readme.contains("certutil -hashfile"))
        assertTrue(readme.contains("shasum -a 256"))
        assertTrue(readme.contains("sha256sum"))
        assertTrue(readme.contains(EvidenceBundleFixtures.MEDIA_SHA256))
        assertTrue(readme.contains(EvidenceBundleFixtures.MERKLE_ROOT))
        assertTrue(readme.contains(mediaName))
        assertTrue(readme.contains(packageName))
    }

    @Test
    fun `readme is wrapped and uses CRLF so it opens correctly on Windows`() {
        val readme = text(
            unzip(exporter.export(EvidenceBundleFixtures.bundle())).getValue(readmeName),
        )

        assertTrue(readme.contains("\r\n"))
        assertFalse("a bare LF would mean an inconsistent line ending", readme.contains("\n\n"))
        val longest = readme.split("\r\n").maxOf { it.length }
        assertTrue("longest line was $longest characters", longest <= 80)
    }

    // ---- refusals reach the exporter's callers ------------------------------

    @Test
    fun `no archive is produced for an unsigned event`() {
        val failure = runCatching {
            exporter.export(
                EvidenceBundleFixtures.bundle(
                    event = EvidenceBundleFixtures.signedEvent(signature = null),
                ),
            )
        }.exceptionOrNull()

        assertNotNull("an unsigned event must not yield an archive", failure)
        assertTrue(failure is IllegalStateException)
    }

    @Test
    fun `no archive is produced when the media is missing`() {
        val failure = runCatching {
            exporter.export(EvidenceBundleFixtures.bundle(mediaBytes = null))
        }.exceptionOrNull()

        assertNotNull("a missing media file must not yield a smaller archive", failure)
        assertTrue(failure is IllegalStateException)
    }

    // ---- helpers -----------------------------------------------------------

    private fun text(bytes: ByteArray) = String(bytes, Charsets.UTF_8)

    /**
     * Collapses a wrapped document to a single line, so a prose assertion is not
     * quietly defeated by the line break the wrapper happened to choose.
     */
    private fun flatten(document: String) =
        document.replace("\r\n", " ").replace(Regex(" +"), " ")

    /** Entry names in the order they appear in the archive. */
    private fun order(zipBytes: ByteArray): List<String> = read(zipBytes).map { it.name }

    private fun unzip(zipBytes: ByteArray): Map<String, ByteArray> =
        read(zipBytes).associate { it.name to it.bytes }

    private fun read(zipBytes: ByteArray): List<ArchiveEntry> {
        val out = mutableListOf<ArchiveEntry>()
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                // Read the bytes before consulting `time`: for a streamed entry the
                // header fields are only settled once the data has been consumed.
                val bytes = zip.readBytes()
                out += ArchiveEntry(entry.name, bytes, entry.time)
                zip.closeEntry()
            }
        }
        return out
    }

    private class ArchiveEntry(val name: String, val bytes: ByteArray, val timeMillis: Long)

    private companion object {
        /** 2000-01-01T00:00:00Z. Any pinned constant must sit well below this. */
        const val MILLIS_AT_YEAR_2000 = 946_684_800_000L
    }
}
