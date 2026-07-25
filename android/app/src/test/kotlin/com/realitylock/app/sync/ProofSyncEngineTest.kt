package com.realitylock.app.sync

import com.realitylock.app.core.config.SyncConfig
import java.io.File
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The sync engine, driven against a **real HTTP server on localhost**.
 *
 * MockWebServer rather than a mocked uploader interface: the behaviour worth
 * testing here is how real status codes and real request bodies are handled, and
 * a mock would only ever agree with whatever the client happened to send.
 */
class ProofSyncEngineTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var repository: FakeEventRepository
    private lateinit var syncStateStore: SyncStateStore
    private lateinit var engine: ProofSyncEngine
    private lateinit var mediaFile: File

    private val packageJson =
        """{"eventId":"$EVENT_ID","schemaVersion":"1.0.0","note":"exact bytes"}"""

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()

        mediaFile = temp.newFile("$EVENT_ID.jpg").apply { writeBytes(MEDIA_BYTES) }
        repository = FakeEventRepository()
        repository.add(
            FakeEventRepository.event(EVENT_ID, mediaFile.absolutePath),
            packageJson.toByteArray(),
        )
        syncStateStore = SyncStateStore(File(temp.root, "sync"))
        engine = ProofSyncEngine(
            repository = repository,
            syncStateStore = syncStateStore,
            uploader = ProofUploader(OkHttpClient(), server.url("/").toString()),
            nowMillis = { FIXED_NOW },
        )
    }

    @After
    fun tearDown() = server.close()

    private fun ok(code: Int, body: String = "{}") =
        server.enqueue(MockResponse.Builder().code(code).body(body).build())

    @Test
    fun `a successful pass sends the package then the media and completes`() {
        ok(201)
        ok(201, """{"storageRef":"sha256:${"a".repeat(64)}"}""")

        val outcome = engine.syncAll()

        assertEquals(listOf(EVENT_ID), outcome.synced)
        assertFalse(outcome.retryable)
        assertEquals(SyncStage.COMPLETE, syncStateStore.get(EVENT_ID).stage)

        // Two requests, in order, at the paths the backend actually exposes.
        val first = server.takeRequest()
        assertEquals("POST", first.method)
        assertEquals("/${SyncConfig.PROOF_PATH}", first.target)

        val second = server.takeRequest()
        assertEquals("POST", second.method)
        assertEquals(
            "/${SyncConfig.PROOF_PATH}/$EVENT_ID/${SyncConfig.MEDIA_PATH_SEGMENT}",
            second.target,
        )
    }

    @Test
    fun `the package is transmitted as the exact stored bytes`() {
        ok(201)
        ok(201)

        engine.syncAll()

        // The bytes on the wire must equal the bytes on disk, byte for byte. Those
        // bytes are what the metadata hash and signature cover, so any re-encoding
        // in between would fail verification in a way that looks like tampering
        // (ADR-0006 §2). This is the assertion that guards it.
        val sent = server.takeRequest().body!!.toByteArray()
        assertArrayEquals(packageJson.toByteArray(), sent)
    }

    @Test
    fun `the media is transmitted as raw bytes, not base64`() {
        ok(201)
        ok(201)

        engine.syncAll()

        server.takeRequest()
        val mediaRequest = server.takeRequest()
        assertArrayEquals(MEDIA_BYTES, mediaRequest.body!!.toByteArray())
        assertEquals(
            SyncConfig.CONTENT_TYPE_OCTET_STREAM,
            mediaRequest.headers["Content-Type"],
        )
    }

    @Test
    fun `a server error is retryable and does not lose the event`() {
        ok(503, """{"error":"unavailable"}""")

        val outcome = engine.syncAll()

        assertTrue("a 5xx may simply pass", outcome.retryable)
        assertTrue(outcome.synced.isEmpty())
        assertTrue(outcome.failed.isEmpty())

        val state = syncStateStore.get(EVENT_ID)
        assertEquals(SyncStage.PENDING, state.stage)
        assertEquals(1, state.attempts)
        assertTrue(state.isRetryable)
        assertTrue(state.lastError!!.contains("503"))
    }

    @Test
    fun `a rejected package is permanent and is not retried`() {
        // No number of retries makes a schema-invalid package valid.
        ok(400, """{"validated":false,"message":"schema violation"}""")

        val outcome = engine.syncAll()

        assertEquals(listOf(EVENT_ID), outcome.failed)
        assertFalse(outcome.retryable)

        val state = syncStateStore.get(EVENT_ID)
        assertEquals(SyncStage.FAILED, state.stage)
        assertFalse(state.isRetryable)
        assertTrue(state.lastError!!.contains("schema violation"))
    }

    @Test
    fun `media rejected for a hash mismatch is permanent`() {
        ok(201)
        ok(409, """{"error":"media_hash_mismatch","message":"digest does not match"}""")

        val outcome = engine.syncAll()

        assertEquals(listOf(EVENT_ID), outcome.failed)
        assertEquals(SyncStage.FAILED, syncStateStore.get(EVENT_ID).stage)
    }

    @Test
    fun `an already-stored package is not sent twice`() {
        // A previous pass got the package across but not the media.
        syncStateStore.put(SyncState(EVENT_ID, SyncStage.PACKAGE_STORED, attempts = 1))
        ok(201)

        val outcome = engine.syncAll()

        assertEquals(listOf(EVENT_ID), outcome.synced)
        // Exactly one request, and it is the media one.
        assertEquals(1, server.requestCount)
        assertEquals(
            "/${SyncConfig.PROOF_PATH}/$EVENT_ID/${SyncConfig.MEDIA_PATH_SEGMENT}",
            server.takeRequest().target,
        )
    }

    @Test
    fun `a completed event is skipped entirely`() {
        syncStateStore.put(SyncState(EVENT_ID, SyncStage.COMPLETE))

        val outcome = engine.syncAll()

        assertEquals(1, outcome.alreadyComplete)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a failed event is not retried automatically`() {
        syncStateStore.put(SyncState(EVENT_ID, SyncStage.FAILED, lastError = "gave up"))

        val outcome = engine.syncAll()

        assertEquals(listOf(EVENT_ID), outcome.failed)
        assertFalse(outcome.retryable)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `retrying stops after the attempt limit rather than forever`() {
        syncStateStore.put(
            SyncState(EVENT_ID, SyncStage.PENDING, attempts = SyncConfig.MAX_ATTEMPTS - 1),
        )
        ok(503)

        val outcome = engine.syncAll()

        // The attempt counter reached the limit, so this becomes permanent — a
        // capture that can never sync must not drain the battery trying.
        assertEquals(listOf(EVENT_ID), outcome.failed)
        val state = syncStateStore.get(EVENT_ID)
        assertEquals(SyncStage.FAILED, state.stage)
        assertTrue(state.lastError!!.contains("gave up after"))
    }

    @Test
    fun `an explicit retry clears the failed stage`() {
        syncStateStore.put(
            SyncState(EVENT_ID, SyncStage.FAILED, attempts = 9, lastError = "gave up"),
        )

        engine.resetForRetry(EVENT_ID)

        val state = syncStateStore.get(EVENT_ID)
        // Giving up automatically must not be permanent from the user's side.
        assertEquals(SyncStage.PENDING, state.stage)
        assertEquals(0, state.attempts)
        assertEquals(null, state.lastError)
    }

    @Test
    fun `a missing package sidecar is permanent, not an endless retry`() {
        val orphan = "22222222-2222-4222-8222-222222222222"
        repository.add(
            FakeEventRepository.event(orphan, temp.newFile("$orphan.jpg").absolutePath),
            bytes = null,
        )
        ok(201)
        ok(201)

        val outcome = engine.syncAll()

        assertTrue(outcome.failed.contains(orphan))
        assertEquals(SyncStage.FAILED, syncStateStore.get(orphan).stage)
    }

    @Test
    fun `a missing media file is permanent, not an endless retry`() {
        mediaFile.delete()
        ok(201)

        val outcome = engine.syncAll()

        assertEquals(listOf(EVENT_ID), outcome.failed)
        assertTrue(syncStateStore.get(EVENT_ID).lastError!!.contains("media file is missing"))
    }

    @Test
    fun `a backlog drains oldest first`() {
        val older = "11111111-1111-4111-8111-111111111111"
        repository.add(
            FakeEventRepository.event(
                older,
                temp.newFile("$older.jpg").apply { writeBytes(MEDIA_BYTES) }.absolutePath,
                wallClockMillis = 1_000L,
            ),
            """{"eventId":"$older"}""".toByteArray(),
        )
        repeat(4) { ok(201) }

        val outcome = engine.syncAll()

        // Capture order, so a history view fills in the direction a user expects.
        assertEquals(listOf(older, EVENT_ID), outcome.synced)
    }

    private companion object {
        const val EVENT_ID = "3f2504e0-4f89-41d3-9a0c-0305e82c3301"
        const val FIXED_NOW = 1_784_812_400_000L
        val MEDIA_BYTES = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())
    }
}
