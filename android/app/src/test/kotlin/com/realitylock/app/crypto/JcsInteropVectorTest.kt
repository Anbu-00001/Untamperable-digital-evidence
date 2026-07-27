package com.realitylock.app.crypto

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cross-implementation regression test for RFC 8785 canonicalization.
 *
 * ## The failure this exists to prevent
 *
 * Two *different* JCS implementations sit on the two ends of every signature:
 * the app canonicalizes metadata with `io.github.erdtman:java-json-canonicalization`
 * before signing, and the Node verifier re-canonicalizes with the `canonicalize`
 * npm package before checking. If those two ever disagree by a single byte, every
 * `metadataHashMatch` fails and every proof package in existence becomes
 * unverifiable — including ones already signed and shipped, which cannot be
 * re-signed because the key is non-exportable and the wall-clock moment has
 * passed.
 *
 * Nothing about the two libraries forces them to agree. They agree today because
 * both implement the spec correctly; a version bump on either side could change
 * that silently, and every existing test would still pass, because each side only
 * ever tested itself.
 *
 * ## Why a shared file rather than literals in each suite
 *
 * [VECTOR_PATH] is read by this test **and** by `backend/test/jcsInterop.test.js`.
 * One artifact, two independent implementations asserting against it. Copying the
 * expected values into each suite would let someone "fix" a red test by editing
 * the copy next to it, which is exactly the drift this is meant to catch.
 *
 * The hard cases are in the vectors deliberately: RFC 8785 §3.2.2.3 mandates ES6
 * number serialization, which is where implementations actually diverge — `-0`
 * collapsing to `0`, the `1e+21` exponent threshold, `1e-7` versus `0.000001`,
 * subnormals, and the 2^53 integer boundary. The `float-widened-to-double` vector
 * covers this project's own specific exposure: sensor and accuracy values are
 * Android `Float`s widened to `Double` on the way into JSON, which turns a clean
 * `15.006f` into `15.005999565124512` — a 17-significant-digit value whose
 * shortest-round-trip form is precisely what a weak implementation gets wrong.
 */
class JcsInteropVectorTest {

    @Test
    fun `every shared vector canonicalizes and hashes identically to the reference`() {
        val doc = JSONObject(vectorFile().readText())
        val vectors = doc.getJSONArray("vectors")

        assertTrue("the vector file must actually contain vectors", vectors.length() > 0)

        for (i in 0 until vectors.length()) {
            val vector = vectors.getJSONObject(i)
            val name = vector.getString("name")
            val input = vector.getString("inputJson")

            assertEquals(
                "canonical form drifted for vector '$name' — the Java and Node JCS " +
                    "implementations no longer agree, which invalidates every signature",
                vector.getString("canonical"),
                MetadataCanonicalizer.canonicalize(input),
            )
            assertEquals(
                "canonical hash drifted for vector '$name'",
                vector.getString("sha256"),
                MetadataCanonicalizer.canonicalHashHex(input),
            )
        }
    }

    @Test
    fun `the vector file covers the number forms that actually break implementations`() {
        // A vector file that quietly lost its hard cases would keep passing while
        // protecting nothing, so the coverage itself is asserted.
        val doc = JSONObject(vectorFile().readText())
        val vectors = doc.getJSONArray("vectors")
        val names = (0 until vectors.length()).map { vectors.getJSONObject(it).getString("name") }

        listOf("es6-number-edge-cases", "float-widened-to-double").forEach { required ->
            assertTrue("the '$required' vector is missing from ${VECTOR_PATH}", required in names)
        }
    }

    /**
     * Locates the shared vector by walking up from the working directory.
     *
     * Gradle's working directory for unit tests is the module directory, but that
     * is a default rather than a guarantee, and it differs between a Gradle run
     * and an IDE run. Walking up to the repository root is stable under both.
     */
    private fun vectorFile(): File {
        val workingDir = requireNotNull(System.getProperty("user.dir")) {
            "user.dir is unset, so the repository root cannot be located"
        }
        var dir: File? = File(workingDir).absoluteFile
        while (dir != null) {
            val candidate = File(dir, VECTOR_PATH)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        throw AssertionError("could not find $VECTOR_PATH by walking up from $workingDir")
    }

    private companion object {
        const val VECTOR_PATH = "docs/design/examples/jcs-interop-vector.json"
    }
}
