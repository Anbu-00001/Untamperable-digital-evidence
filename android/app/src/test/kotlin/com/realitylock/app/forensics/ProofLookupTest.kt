package com.realitylock.app.forensics

import com.realitylock.app.capture.model.PublicKeyData
import com.realitylock.app.capture.model.SignatureData
import com.realitylock.app.crypto.Hashing
import com.realitylock.app.sync.FakeEventRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/**
 * The Analyze screen's headline answer.
 *
 * These are behaviour tests for a claim shown to a user who may be anxious about
 * a photograph, so each one pins a distinction that is easy to collapse and
 * expensive to get wrong:
 *
 *  - a match is byte-exact, never approximate;
 *  - "no proof" and "could not check" are different answers;
 *  - a stored-but-unsigned capture is not reported as proven.
 */
class ProofLookupTest {

    private val bytes = "the exact bytes that were captured".toByteArray()
    private val digest = Hashing.toHex(Hashing.sha256(bytes))

    private fun streamOf(data: ByteArray): () -> InputStream? = { ByteArrayInputStream(data) }

    private fun repositoryHolding(
        mediaSha256: String?,
        signed: Boolean = true,
        eventId: String = "9966a6b3-f4d5-4b8b-bf6e-2dcf5b2f6ff6",
    ): FakeEventRepository {
        val base = FakeEventRepository.event(eventId, "/data/captures/$eventId.jpg")
        val event = base.copy(
            media = base.media.copy(sha256 = mediaSha256),
            signature = if (signed) {
                SignatureData(
                    algorithm = "SHA256withECDSA",
                    value = "c2ln",
                    publicKey = PublicKeyData(
                        format = "X.509",
                        curve = "P-256",
                        value = "cHVibGljS2V5",
                    ),
                )
            } else {
                null
            },
        )
        return FakeEventRepository(mutableListOf(event))
    }

    @Test
    fun `identifies an image whose bytes match a signed capture`() {
        val result = ProofLookup(repositoryHolding(digest)).lookup(streamOf(bytes))

        assertTrue("expected Matched, got $result", result is ProofLookup.Result.Matched)
        result as ProofLookup.Result.Matched
        assertEquals("9966a6b3-f4d5-4b8b-bf6e-2dcf5b2f6ff6", result.event.eventId)
        assertTrue(result.signed)
    }

    @Test
    fun `reports no proof for an image this app never captured`() {
        val result = ProofLookup(repositoryHolding(digest)).lookup(streamOf("a different photo".toByteArray()))

        assertEquals(ProofLookup.Result.NoProof, result)
    }

    @Test
    fun `a single altered byte is no longer a match`() {
        // The property that makes a match worth stating at all. A re-encoded or
        // resized copy is NOT the bytes that were signed, so calling it verified
        // would be false — the signature covers a Merkle root those bytes no
        // longer produce. There is deliberately no similarity score: a fuzzy
        // match would carry no cryptographic meaning while reading like one.
        val altered = bytes.copyOf().also { it[0] = (it[0] + 1).toByte() }

        val result = ProofLookup(repositoryHolding(digest)).lookup(streamOf(altered))

        assertEquals(ProofLookup.Result.NoProof, result)
    }

    @Test
    fun `a matching but unsigned capture is not reported as proven`() {
        val result = ProofLookup(repositoryHolding(digest, signed = false)).lookup(streamOf(bytes))

        result as ProofLookup.Result.Matched
        assertTrue("the bytes still match the stored capture", result.event.media.sha256 == digest)
        assertEquals("an unsigned capture must not read as proven", false, result.signed)
    }

    @Test
    fun `an unreadable image is not reported as having no proof`() {
        // The distinction the whole result type exists for: "we looked and found
        // nothing" versus "we never managed to look". Collapsing them would let a
        // read failure be shown to the user as a finding about their image.
        val result = ProofLookup(repositoryHolding(digest)).lookup { throw IOException("permission denied") }

        assertTrue("expected Unreadable, got $result", result is ProofLookup.Result.Unreadable)
        assertTrue((result as ProofLookup.Result.Unreadable).reason.contains("permission denied"))
    }

    @Test
    fun `a null stream is unreadable rather than no proof`() {
        val result = ProofLookup(repositoryHolding(digest)).lookup { null }

        assertTrue("expected Unreadable, got $result", result is ProofLookup.Result.Unreadable)
    }

    @Test
    fun `a capture stored without a media digest cannot be matched into`() {
        // Pre-Phase-3 events carry no media SHA-256. Null must never compare
        // equal to a computed digest, or every unhashed capture would match the
        // first image the user picked.
        val result = ProofLookup(repositoryHolding(mediaSha256 = null)).lookup(streamOf(bytes))

        assertEquals(ProofLookup.Result.NoProof, result)
    }

    @Test
    fun `the right capture is found among several`() {
        val wanted = FakeEventRepository.event("11111111-1111-4111-8111-111111111111", "/a.jpg")
            .let { it.copy(media = it.media.copy(sha256 = digest)) }
        val other = FakeEventRepository.event("22222222-2222-4222-8222-222222222222", "/b.jpg")
            .let { it.copy(media = it.media.copy(sha256 = "f".repeat(64))) }
        val repository = FakeEventRepository(mutableListOf(other, wanted))

        val result = ProofLookup(repository).lookup(streamOf(bytes))

        result as ProofLookup.Result.Matched
        assertEquals("11111111-1111-4111-8111-111111111111", result.event.eventId)
    }

    @Test
    fun `an empty store reports no proof rather than failing`() {
        val result = ProofLookup(FakeEventRepository()).lookup(streamOf(bytes))

        assertEquals(ProofLookup.Result.NoProof, result)
    }
}
