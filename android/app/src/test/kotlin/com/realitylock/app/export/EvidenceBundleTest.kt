package com.realitylock.app.export

import com.realitylock.app.export.EvidenceBundleFixtures.EVENT_ID
import com.realitylock.app.export.EvidenceBundleFixtures.MEDIA_BYTES
import com.realitylock.app.export.EvidenceBundleFixtures.MEDIA_SHA256
import com.realitylock.app.export.EvidenceBundleFixtures.OTHER_EVENT_ID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Refusals and content assembly for [EvidenceBundle].
 *
 * These are the tests that matter most, because every one of them describes a
 * bundle this app must decline to produce. An evidence exporter's failure mode
 * is not a crash — it is a plausible-looking archive that quietly proves less
 * than its recipient assumes.
 */
class EvidenceBundleTest {

    // ---- happy path --------------------------------------------------------

    @Test
    fun `builds a bundle from a signed event and its stored bytes`() {
        val bundle = EvidenceBundleFixtures.bundle()

        assertEquals(EVENT_ID, bundle.eventId)
        assertEquals("$EVENT_ID.json", bundle.packageEntryName)
        assertEquals("$EVENT_ID.jpg", bundle.mediaEntryName)
        assertEquals("2026-07-23T13:12:25.678Z", bundle.capturedAtIso)
        assertEquals(EvidenceBundleFixtures.MERKLE_ROOT, bundle.merkleRoot)
        assertEquals("SHA-256", bundle.hashAlgorithm)
        assertEquals("SHA256withECDSA", bundle.signatureAlgorithm)
        assertEquals(MEDIA_SHA256, bundle.mediaSha256)
        assertEquals(EvidenceBundleFixtures.CAPTURING_APP_VERSION, bundle.capturingAppVersion)
        assertEquals(EvidenceBundleFixtures.EXPORTING_APP_VERSION, bundle.exportingAppVersion)
        assertEquals(EvidenceBundleFixtures.EXPORTED_AT_ISO, bundle.exportedAtIso)
    }

    @Test
    fun `carries the caller's arrays through untouched`() {
        val stored = EvidenceBundleFixtures.packageBytes()
        val media = MEDIA_BYTES.copyOf()

        val bundle = EvidenceBundleFixtures.bundle(packageBytes = stored, mediaBytes = media)

        // Reference identity, not just content equality: nothing on this path may
        // copy-with-transform, because a transform is exactly what would break the
        // signature the bytes carry.
        assertSame(stored, bundle.packageBytes)
        assertSame(media, bundle.mediaBytes)
    }

    // ---- an unsigned event is not evidence ---------------------------------

    @Test
    fun `refuses an event that was never signed`() {
        val message = refusal {
            EvidenceBundleFixtures.bundle(
                event = EvidenceBundleFixtures.signedEvent(signature = null),
            )
        }
        assertTrue(message, message.contains("never signed"))
        assertTrue(message, message.contains("proves nothing"))
    }

    @Test
    fun `refuses an event with no Merkle root`() {
        val message = refusal {
            EvidenceBundleFixtures.bundle(
                event = EvidenceBundleFixtures.signedEvent(merkle = null),
            )
        }
        assertTrue(message, message.contains("no Merkle root"))
    }

    @Test
    fun `refuses stored bytes that carry no signature block`() {
        // The in-memory event claims a signature but the sidecar on disk has
        // none: the bytes that would ship are not the document that was signed.
        val message = refusal {
            EvidenceBundleFixtures.bundle(
                packageBytes = EvidenceBundleFixtures.packageBytes(includeSignature = false),
            )
        }
        assertTrue(message, message.contains("carries no signature block"))
    }

    // ---- a missing file must be loud ---------------------------------------

    @Test
    fun `refuses a missing media file rather than writing a smaller archive`() {
        val message = refusal { EvidenceBundleFixtures.bundle(mediaBytes = null) }

        assertTrue(message, message.contains("media file"))
        assertTrue(message, message.contains("missing or unreadable"))
        assertTrue(message, message.contains("quietly omits"))
    }

    @Test
    fun `refuses an empty media file`() {
        val message = refusal { EvidenceBundleFixtures.bundle(mediaBytes = ByteArray(0)) }
        assertTrue(message, message.contains("media file") && message.contains("is empty"))
    }

    @Test
    fun `refuses a missing proof package`() {
        val message = refusal { EvidenceBundleFixtures.bundle(packageBytes = null) }
        assertTrue(message, message.contains("proof package sidecar"))
        assertTrue(message, message.contains("no signed bytes"))
    }

    @Test
    fun `refuses an empty proof package`() {
        val message = refusal { EvidenceBundleFixtures.bundle(packageBytes = ByteArray(0)) }
        assertTrue(message, message.contains("is empty"))
    }

    // ---- the package and the media must belong to each other ---------------

    @Test
    fun `refuses media that does not match the hash in the signed package`() {
        val message = refusal {
            EvidenceBundleFixtures.bundle(
                mediaBytes = EvidenceBundleFixtures.TAMPERED_MEDIA_BYTES,
            )
        }

        assertTrue(message, message.contains("does not match the hash"))
        assertTrue(message, message.contains(MEDIA_SHA256))
        assertTrue(
            message,
            message.contains(
                EvidenceBundleFixtures.sha256Hex(EvidenceBundleFixtures.TAMPERED_MEDIA_BYTES),
            ),
        )
    }

    @Test
    fun `refuses a proof package belonging to a different event`() {
        val message = refusal {
            EvidenceBundleFixtures.bundle(
                packageBytes = EvidenceBundleFixtures.packageBytes(eventId = OTHER_EVENT_ID),
            )
        }
        assertTrue(message, message.contains(OTHER_EVENT_ID))
        assertTrue(message, message.contains("do not belong to each other"))
    }

    @Test
    fun `refuses a proof package that records no media hash`() {
        val message = refusal {
            EvidenceBundleFixtures.bundle(
                packageBytes = EvidenceBundleFixtures.packageBytes(mediaSha256 = ""),
            )
        }
        assertTrue(message, message.contains("records no media hash"))
    }

    @Test
    fun `refuses a proof package that is not readable JSON`() {
        val message = refusal {
            EvidenceBundleFixtures.bundle(packageBytes = "not json at all".toByteArray())
        }
        assertTrue(message, message.contains("not readable"))
    }

    // ---- archive-safety ----------------------------------------------------

    @Test
    fun `refuses an event id that would escape the extraction directory`() {
        // Zip Slip. A doctored sidecar is the only way to get here, and the
        // person who pays for it is whoever extracts the archive.
        for (hostileId in listOf("../../etc/passwd", "..", "a/b", "a\\b", ".hidden", "")) {
            val message = refusal {
                EvidenceBundleFixtures.bundle(
                    event = EvidenceBundleFixtures.signedEvent(eventId = hostileId),
                    packageBytes = EvidenceBundleFixtures.packageBytes(eventId = hostileId),
                )
            }
            assertTrue(
                "expected a refusal naming archive safety for \"$hostileId\", got: $message",
                message.contains("not a safe archive entry name"),
            )
        }
    }

    // ---- caller inputs -----------------------------------------------------

    @Test
    fun `refuses an export timestamp that is not UTC`() {
        for (bad in listOf("2026-08-07T11:22:33+05:30", "2026-08-07 11:22:33", "", "yesterday")) {
            val message = refusal { EvidenceBundleFixtures.bundle(exportedAtIso = bad) }
            assertTrue(
                "expected a refusal for timestamp \"$bad\", got: $message",
                message.contains("must be ISO-8601 in UTC"),
            )
        }
    }

    @Test
    fun `accepts an export timestamp with sub-second precision`() {
        val bundle = EvidenceBundleFixtures.bundle(exportedAtIso = "2026-08-07T11:22:33.456Z")
        assertEquals("2026-08-07T11:22:33.456Z", bundle.exportedAtIso)
    }

    @Test
    fun `refuses a blank app version`() {
        val message = refusal { EvidenceBundleFixtures.bundle(exportingAppVersion = "  ") }
        assertTrue(message, message.contains("which app version produced it"))
    }

    // ---- media naming ------------------------------------------------------

    @Test
    fun `takes the media extension from the stored path rather than assuming jpeg`() {
        val bundle = EvidenceBundleFixtures.bundle(
            event = EvidenceBundleFixtures.signedEvent(
                mediaFilePath = "/data/user/0/com.realitylock.app/files/captures/$EVENT_ID.mp4",
            ),
        )
        assertEquals("$EVENT_ID.mp4", bundle.mediaEntryName)
    }

    @Test
    fun `falls back to the configured stills extension when the path has none`() {
        val bundle = EvidenceBundleFixtures.bundle(
            event = EvidenceBundleFixtures.signedEvent(
                mediaFilePath = "/data/user/0/com.realitylock.app/files/captures/$EVENT_ID",
            ),
        )
        assertEquals("$EVENT_ID.jpg", bundle.mediaEntryName)
    }

    @Test
    fun `never lets a hostile media path shape the archive entry name`() {
        val bundle = EvidenceBundleFixtures.bundle(
            event = EvidenceBundleFixtures.signedEvent(
                mediaFilePath = "/captures/$EVENT_ID.j g",
            ),
        )
        assertEquals("$EVENT_ID.jpg", bundle.mediaEntryName)
    }

    /** Runs [block], requiring it to refuse, and returns the refusal's message. */
    private fun refusal(block: () -> Unit): String {
        try {
            block()
        } catch (expected: IllegalStateException) {
            val message = expected.message
            assertNotNull("a refusal must explain itself", message)
            return message!!
        }
        fail("expected the exporter to refuse, but it produced a bundle")
        error("unreachable")
    }
}
