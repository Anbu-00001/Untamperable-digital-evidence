package com.realitylock.app.sync

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Round-trip and resilience of the sync-state sidecar. */
class SyncStateStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun store() = SyncStateStore(File(temp.root, "sync"))

    @Test
    fun `an unknown event reads as pending rather than null`() {
        // Callers must never have to distinguish "no file yet" from "nothing sent
        // yet" — they are the same situation.
        val state = store().get(EVENT_ID)

        assertEquals(SyncStage.PENDING, state.stage)
        assertEquals(EVENT_ID, state.eventId)
        assertEquals(0, state.attempts)
        assertNull(state.lastError)
    }

    @Test
    fun `state round-trips through the file, including nulls`() {
        val store = store()
        val written = SyncState(
            eventId = EVENT_ID,
            stage = SyncStage.PACKAGE_STORED,
            attempts = 3,
            lastAttemptWallClockMillis = 1_784_812_345_678L,
            lastError = null,
            storageRef = null,
        )
        store.put(written)

        assertEquals(written, store.get(EVENT_ID))
    }

    @Test
    fun `state round-trips with every field populated`() {
        val store = store()
        val written = SyncState(
            eventId = EVENT_ID,
            stage = SyncStage.COMPLETE,
            attempts = 1,
            lastAttemptWallClockMillis = 42L,
            lastError = "a previous failure worth remembering",
            storageRef = "sha256:" + "a".repeat(64),
        )
        store.put(written)

        assertEquals(written, store.get(EVENT_ID))
    }

    @Test
    fun `a corrupt state file degrades to pending, not to complete`() {
        val dir = File(temp.root, "sync").apply { mkdirs() }
        File(dir, "$EVENT_ID.json").writeText("{ this is not json")

        // Reading it as COMPLETE would silently drop the event from sync forever;
        // PENDING costs one idempotent re-upload at worst.
        assertEquals(SyncStage.PENDING, store().get(EVENT_ID).stage)
    }

    @Test
    fun `an unrecognised stage name degrades to pending`() {
        val dir = File(temp.root, "sync").apply { mkdirs() }
        File(dir, "$EVENT_ID.json").writeText(
            """{"eventId":"$EVENT_ID","stage":"TELEPORTED","attempts":2}""",
        )

        val state = store().get(EVENT_ID)
        assertEquals(SyncStage.PENDING, state.stage)
        assertEquals(2, state.attempts)
    }

    @Test
    fun `all() returns every recorded state keyed by event`() {
        val store = store()
        store.put(SyncState(EVENT_ID, SyncStage.COMPLETE))
        store.put(SyncState(OTHER_EVENT_ID, SyncStage.FAILED))

        val all = store.all()

        assertEquals(2, all.size)
        assertEquals(SyncStage.COMPLETE, all[EVENT_ID]?.stage)
        assertEquals(SyncStage.FAILED, all[OTHER_EVENT_ID]?.stage)
    }

    @Test
    fun `delete removes the state file`() {
        val store = store()
        store.put(SyncState(EVENT_ID, SyncStage.COMPLETE))

        assertTrue(store.delete(EVENT_ID))
        assertEquals(SyncStage.PENDING, store.get(EVENT_ID).stage)
    }

    @Test
    fun `retryability distinguishes the four stages`() {
        assertTrue(SyncState(EVENT_ID, SyncStage.PENDING).isRetryable)
        assertTrue(SyncState(EVENT_ID, SyncStage.PACKAGE_STORED).isRetryable)
        // Neither a finished nor an abandoned event should be picked up again by
        // an automatic pass.
        assertFalse(SyncState(EVENT_ID, SyncStage.COMPLETE).isRetryable)
        assertFalse(SyncState(EVENT_ID, SyncStage.FAILED).isRetryable)
        assertTrue(SyncState(EVENT_ID, SyncStage.COMPLETE).isComplete)
    }

    private companion object {
        const val EVENT_ID = "3f2504e0-4f89-41d3-9a0c-0305e82c3301"
        const val OTHER_EVENT_ID = "11111111-1111-4111-8111-111111111111"
    }
}
