package com.realitylock.app.capture.store

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validates real [EventSerializer] output against the **actual**
 * `docs/design/proof-package.schema.json` — the same file the backend verifier
 * compiles.
 *
 * This replaces an earlier test that only asserted `json.has("latitude")`-style
 * key presence. That test was named for this contract but never loaded the
 * schema, so it passed while the serializer emitted three schema-violating
 * shapes (a `mediaFilePath` the schema forbids, `location: null` against a
 * non-nullable field, and `gyroscope: []` against `minItems: 3`). A contract
 * test that cannot fail on a contract breach is worse than no test, because it
 * is read as assurance.
 *
 * Phase 2 does not yet emit `merkle`/`signature`/`media.sha256`, so the positive
 * cases graft those from the validated example package: the assertion is that
 * Phase-2 output is a valid **prefix** of a finished proof package — every field
 * it emits conforms, and it emits nothing the schema forbids.
 */
class EventSerializerSchemaTest {

    private val mapper = ObjectMapper()

    private val schema: JsonSchema =
        JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
            .getSchema(readRequiredFile(SCHEMA_PATH_PROPERTY).readText())

    private val example: ObjectNode =
        mapper.readTree(readRequiredFile(EXAMPLE_PATH_PROPERTY)) as ObjectNode

    /** Serializer output with the Phase-3 fields grafted on from the example. */
    private fun completedPackage(event: com.realitylock.app.capture.model.CapturedEvent): ObjectNode {
        val doc = mapper.readTree(EventSerializer.toJson(event)) as ObjectNode
        doc.set<JsonNode>(KEY_MERKLE, example.get(KEY_MERKLE))
        doc.set<JsonNode>(KEY_SIGNATURE, example.get(KEY_SIGNATURE))
        (doc.get(KEY_MEDIA) as ObjectNode)
            .set<JsonNode>(KEY_SHA256, example.get(KEY_MEDIA).get(KEY_SHA256))
        return doc
    }

    private fun errorsFor(node: JsonNode): String =
        schema.validate(node).joinToString("; ") { it.message }

    private fun assertValid(node: JsonNode) {
        val errors = errorsFor(node)
        assertTrue("expected a schema-valid package but got: $errors", errors.isEmpty())
    }

    private fun assertInvalid(node: JsonNode, expectedSubstring: String) {
        val errors = errorsFor(node)
        assertFalse("expected schema violations but the package validated", errors.isEmpty())
        assertTrue(
            "expected an error mentioning '$expectedSubstring' but got: $errors",
            errors.contains(expectedSubstring),
        )
    }

    // ---- positive cases: every branch the capture pipeline can produce ----

    @Test
    fun `a fully populated capture is a valid proof package`() {
        assertValid(completedPackage(CapturedEventFixtures.sampleEvent()))
    }

    @Test
    fun `a capture with no location fix is still valid`() {
        // This is the branch the emulator exercised — and the one that was
        // silently producing schema-invalid output.
        assertValid(completedPackage(CapturedEventFixtures.sampleEvent(location = null)))
    }

    @Test
    fun `a capture with no motion sample in tolerance is still valid`() {
        assertValid(completedPackage(CapturedEventFixtures.sampleEvent(motion = null)))
    }

    @Test
    fun `a capture with an accelerometer but no gyroscope is still valid`() {
        val event = CapturedEventFixtures.sampleEvent(
            motion = com.realitylock.app.capture.model.MotionData(
                accelerometer = listOf(0.12f, 9.79f, 0.34f),
                gyroscope = null,
                sampleElapsedRealtimeNanos = 894_511_998_000_000L,
            ),
        )
        assertValid(completedPackage(event))
    }

    // ---- the serializer must not emit device-local state ----

    @Test
    fun `the proof document does not carry the on-device media path`() {
        val doc = mapper.readTree(EventSerializer.toJson(CapturedEventFixtures.sampleEvent()))
        assertFalse(
            "mediaFilePath is device-local state and must stay out of the shared proof package",
            doc.has(KEY_MEDIA_FILE_PATH),
        )
    }

    @Test
    fun `injecting the media path makes the package invalid`() {
        // Proves the schema genuinely rejects it, so the test above is load-bearing.
        val doc = completedPackage(CapturedEventFixtures.sampleEvent())
        doc.put(KEY_MEDIA_FILE_PATH, "/data/user/0/com.realitylock.app/files/captures/x.jpg")

        assertInvalid(doc, KEY_MEDIA_FILE_PATH)
    }

    // ---- negative controls: prove the validator actually bites ----
    // Without these, every assertion above could pass vacuously.

    @Test
    fun `an empty gyroscope array is rejected`() {
        val doc = completedPackage(CapturedEventFixtures.sampleEvent())
        (doc.get(KEY_METADATA).get(KEY_MOTION) as ObjectNode).set<JsonNode>(
            KEY_GYROSCOPE,
            mapper.createArrayNode(),
        )

        assertInvalid(doc, KEY_GYROSCOPE)
    }

    @Test
    fun `an out-of-range latitude is rejected`() {
        val doc = completedPackage(CapturedEventFixtures.sampleEvent())
        (doc.get(KEY_METADATA).get(KEY_LOCATION) as ObjectNode).put(KEY_LATITUDE, 999.0)

        assertInvalid(doc, KEY_LATITUDE)
    }

    @Test
    fun `a package missing the signature is rejected`() {
        val doc = completedPackage(CapturedEventFixtures.sampleEvent())
        doc.remove(KEY_SIGNATURE)

        assertInvalid(doc, KEY_SIGNATURE)
    }

    private companion object {
        /** Injected by the Gradle test task; see android/app/build.gradle.kts. */
        const val SCHEMA_PATH_PROPERTY = "realitylock.proofSchemaPath"
        const val EXAMPLE_PATH_PROPERTY = "realitylock.proofExamplePath"

        const val KEY_MEDIA = "media"
        const val KEY_METADATA = "metadata"
        const val KEY_MEDIA_FILE_PATH = "mediaFilePath"
        const val KEY_MERKLE = "merkle"
        const val KEY_SIGNATURE = "signature"
        const val KEY_SHA256 = "sha256"
        const val KEY_MOTION = "motion"
        const val KEY_GYROSCOPE = "gyroscope"
        const val KEY_LOCATION = "location"
        const val KEY_LATITUDE = "latitude"

        fun readRequiredFile(property: String): File {
            val path = requireNotNull(System.getProperty(property)) {
                "System property '$property' was not set — the Gradle test task must supply it."
            }
            return File(path).also {
                require(it.exists()) { "Expected file at ${it.absolutePath} (from '$property')" }
            }
        }
    }
}
