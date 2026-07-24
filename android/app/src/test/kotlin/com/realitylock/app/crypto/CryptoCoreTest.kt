package com.realitylock.app.crypto

import com.realitylock.app.core.config.CryptoConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the hash/canonicalize/Merkle contract — the part the
 * Android producer and the Node verifier must agree on byte-for-byte.
 *
 * Expected values are computed independently (Python `hashlib`) and written in
 * as literals. A test that derived them from the code under test would pass no
 * matter what the code did.
 */
class CryptoCoreTest {

    // ---- hashing ----

    @Test
    fun `sha256 matches the known digest of the empty input`() {
        // The canonical SHA-256 of zero bytes.
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Hashing.toHex(Hashing.sha256(ByteArray(0))),
        )
    }

    @Test
    fun `sha256 matches the known digest of abc`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Hashing.toHex(Hashing.sha256("abc")),
        )
    }

    @Test
    fun `streamed hashing equals one-shot hashing across buffer boundaries`() {
        // Larger than HASH_STREAM_BUFFER_BYTES, so the chunking loop runs more
        // than once — the case where a naive implementation silently truncates.
        val bytes = ByteArray(CryptoConfig.HASH_STREAM_BUFFER_BYTES * 3 + 17) { (it % 251).toByte() }

        assertEquals(
            Hashing.toHex(Hashing.sha256(bytes)),
            Hashing.toHex(Hashing.sha256Stream(bytes.inputStream())),
        )
    }

    @Test
    fun `hex round-trips`() {
        val bytes = ByteArray(CryptoConfig.HASH_SIZE_BYTES) { it.toByte() }
        assertTrue(bytes.contentEquals(Hashing.fromHex(Hashing.toHex(bytes))))
    }

    @Test
    fun `hex output is lowercase and 64 characters, as the schema requires`() {
        val hex = Hashing.toHex(Hashing.sha256("anything"))

        assertEquals(64, hex.length)
        assertTrue("schema pattern is ^[0-9a-f]{64}$, got $hex", hex.matches(Regex("^[0-9a-f]{64}$")))
    }

    // ---- Merkle ----

    @Test
    fun `merkle root matches the cross-implementation test vector`() {
        // If this fails, the Android producer has drifted from the documented
        // rule. The backend asserts the SAME vector, so both are checked against
        // a fixed known answer rather than against each other.
        val vector = MerkleTree.CROSS_IMPL_TEST_VECTOR

        assertEquals(vector.MEDIA_HASH, Hashing.toHex(Hashing.sha256(vector.MEDIA_INPUT)))
        assertEquals(vector.METADATA_HASH, Hashing.toHex(Hashing.sha256(vector.METADATA_INPUT)))
        assertEquals(vector.ROOT, MerkleTree.root2Leaf(vector.MEDIA_HASH, vector.METADATA_HASH))
    }

    @Test
    fun `merkle composition is positional, not order-independent`() {
        val vector = MerkleTree.CROSS_IMPL_TEST_VECTOR
        val swapped = MerkleTree.root2Leaf(vector.METADATA_HASH, vector.MEDIA_HASH)

        assertEquals(vector.ROOT_WITH_LEAVES_SWAPPED, swapped)
        assertNotEquals(
            "swapping media and metadata must change the root, or the tree proves nothing about which is which",
            vector.ROOT,
            swapped,
        )
    }

    @Test
    fun `merkle root concatenates raw digest bytes, not hex text`() {
        // Pins the interpretation of the spec's ambiguous `‖`. Hashing the
        // concatenated hex STRINGS gives a different answer; if someone
        // "simplifies" the implementation that way, this fails.
        val vector = MerkleTree.CROSS_IMPL_TEST_VECTOR
        val hexTextInterpretation =
            Hashing.toHex(Hashing.sha256(vector.MEDIA_HASH + vector.METADATA_HASH))

        assertNotEquals(hexTextInterpretation, MerkleTree.root2Leaf(vector.MEDIA_HASH, vector.METADATA_HASH))
        assertEquals(vector.ROOT, MerkleTree.root2Leaf(vector.MEDIA_HASH, vector.METADATA_HASH))
    }

    @Test
    fun `merkle rejects a digest of the wrong length`() {
        val short = "00".repeat(CryptoConfig.HASH_SIZE_BYTES - 1)
        val ok = MerkleTree.CROSS_IMPL_TEST_VECTOR.METADATA_HASH

        val error = runCatching { MerkleTree.root2Leaf(short, ok) }.exceptionOrNull()

        assertTrue("expected a rejection, got $error", error is IllegalArgumentException)
    }

    @Test
    fun `changing one byte of the media changes the root`() {
        val media = ByteArray(64) { it.toByte() }
        val metadataHash = MerkleTree.CROSS_IMPL_TEST_VECTOR.METADATA_HASH
        val before = MerkleTree.root2Leaf(Hashing.toHex(Hashing.sha256(media)), metadataHash)

        media[31] = (media[31] + 1).toByte()
        val after = MerkleTree.root2Leaf(Hashing.toHex(Hashing.sha256(media)), metadataHash)

        assertNotEquals(before, after)
    }

    // ---- canonicalization (RFC 8785) ----

    @Test
    fun `canonicalization is insensitive to key order and whitespace`() {
        val a = """{"b":2,"a":1}"""
        val b = """{  "a" : 1 ,
                       "b" : 2  }"""

        assertEquals(
            MetadataCanonicalizer.canonicalHashHex(a),
            MetadataCanonicalizer.canonicalHashHex(b),
        )
    }

    @Test
    fun `canonicalization sorts object keys`() {
        assertEquals("""{"a":1,"b":2}""", MetadataCanonicalizer.canonicalize("""{"b":2,"a":1}"""))
    }

    @Test
    fun `canonicalization preserves array order`() {
        // Arrays are ordered data — JCS must not sort them, or motion vectors
        // would be silently reordered.
        assertEquals("""[3,1,2]""", MetadataCanonicalizer.canonicalize("""[3, 1, 2]"""))
    }

    @Test
    fun `a changed metadata value changes the canonical hash`() {
        val before = MetadataCanonicalizer.canonicalHashHex("""{"latitude":12.9716}""")
        val after = MetadataCanonicalizer.canonicalHashHex("""{"latitude":12.9717}""")

        assertNotEquals(before, after)
    }

    @Test
    fun `null is distinct from an absent key`() {
        // The producer writes location:null when there is no fix; that must not
        // hash the same as omitting the field.
        assertNotEquals(
            MetadataCanonicalizer.canonicalHashHex("""{"location":null}"""),
            MetadataCanonicalizer.canonicalHashHex("""{}"""),
        )
    }
}
