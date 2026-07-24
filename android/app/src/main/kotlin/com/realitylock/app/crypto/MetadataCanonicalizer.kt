package com.realitylock.app.crypto

import org.erdtman.jcs.JsonCanonicalizer

/**
 * RFC 8785 (JSON Canonicalization Scheme) over the proof package's `metadata`
 * object.
 *
 * Why this exists: two JSON documents can be logically identical yet differ in
 * key order, whitespace, or number formatting. Hashing the raw serialization
 * would then produce different digests for the same facts, and every signature
 * would fail verification for no real reason. JCS defines one byte sequence per
 * JSON value — object keys sorted by UTF-16 code unit, minimal number forms, no
 * insignificant whitespace — so producer and verifier necessarily agree.
 *
 * Only `metadata` is canonicalized. The media leaf hashes raw bytes, which need
 * no canonical form.
 */
object MetadataCanonicalizer {

    /**
     * Canonicalizes [json] (a serialized JSON object) per RFC 8785 and returns
     * the canonical string.
     *
     * @throws java.io.IOException if [json] is not well-formed JSON.
     */
    fun canonicalize(json: String): String = JsonCanonicalizer(json).encodedString

    /**
     * Canonical form of [json], hashed. This is the `merkle.leaves.metadata`
     * value and one of the two inputs to [MerkleTree.root2Leaf].
     */
    fun canonicalHashHex(json: String): String =
        Hashing.toHex(Hashing.sha256(canonicalize(json)))
}
