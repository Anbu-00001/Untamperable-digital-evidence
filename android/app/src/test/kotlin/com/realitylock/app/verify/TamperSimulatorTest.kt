package com.realitylock.app.verify

import com.realitylock.app.crypto.Hashing
import com.realitylock.app.crypto.MerkleTree
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The simulator is only worth showing if its answers are computed: an honest
 * package must verify, and one flipped bit must break the chain for real.
 */
class TamperSimulatorTest {

    private val media = ByteArray(4096) { (it * 31 + 7).toByte() }
    private val canonical = """{"device":{"model":"test"},"timestamp":{"wallClockMillis":1786776597383}}"""

    private fun claims(): TamperSimulator.Claims {
        val mediaLeaf = Hashing.toHex(Hashing.sha256(media))
        val metaLeaf = Hashing.toHex(Hashing.sha256(canonical))
        val root = MerkleTree.root2Leaf(mediaLeaf, metaLeaf)
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val sig = Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private); update(Hashing.fromHex(root)); sign()
        }
        return TamperSimulator.Claims(
            mediaLeafHex = mediaLeaf,
            metadataLeafHex = metaLeaf,
            rootHex = root,
            signatureBase64 = Base64.getEncoder().encodeToString(sig),
            publicKeyBase64 = Base64.getEncoder().encodeToString(pair.public.encoded),
        )
    }

    @Test
    fun `an honest package re-verifies on every link`() {
        val b = TamperSimulator.baseline(claims(), media, canonical)
        assertEquals(true, b.mediaOk)
        assertTrue(b.metadataOk)
        assertTrue(b.rootOk)
        assertTrue(b.signatureOk)
    }

    @Test
    fun `a missing photo is unknown, not a pass and not a fail`() {
        assertNull(TamperSimulator.baseline(claims(), null, canonical).mediaOk)
    }

    @Test
    fun `flipping one bit of the photo breaks the leaf, the root and the signature`() {
        val c = claims()
        val sim = TamperSimulator.simulate(TamperSimulator.Target.PHOTO, c, media, canonical, Random(42))!!
        assertNotEquals(c.mediaLeafHex, sim.tamperedLeafHex)
        assertEquals(c.mediaLeafHex, sim.recomputedLeafHex)
        assertFalse(sim.rootStillMatches)
        assertFalse(sim.signatureStillValid)
        assertTrue("avalanche: roughly 60 of 64 digits should differ", sim.changedDigits > 40)
    }

    @Test
    fun `flipping one bit of the record breaks the chain the same way`() {
        val sim = TamperSimulator.simulate(TamperSimulator.Target.RECORD, claims(), media, canonical, Random(7))!!
        assertFalse(sim.rootStillMatches)
        assertFalse(sim.signatureStillValid)
    }

    @Test
    fun `the simulation never alters the caller's bytes`() {
        val before = media.copyOf()
        TamperSimulator.simulate(TamperSimulator.Target.PHOTO, claims(), media, canonical, Random(1))
        assertTrue(before.contentEquals(media))
    }

    @Test
    fun `no photo means nothing to flip`() {
        assertNull(TamperSimulator.simulate(TamperSimulator.Target.PHOTO, claims(), null, canonical))
    }

    @Test
    fun `a signature from a different key does not verify`() {
        val c = claims()
        val other = claims()
        assertFalse(TamperSimulator.verifySignature(c.rootHex, c.signatureBase64, other.publicKeyBase64))
    }
}
