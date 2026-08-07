package com.realitylock.app.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The engine's rules, exercised without a device.
 *
 * Every test here is about a claim the UI will make to someone who is relying on
 * it. The one that matters most is that "backed up" is unreachable except by
 * bytes that were written and verified at the *current* destination.
 */
class EvidenceBackupEngineTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val eventA = "aaaaaaaa-0000-4000-8000-000000000001"
    private val eventB = "bbbbbbbb-0000-4000-8000-000000000002"

    private class FakeDestination(
        override var destinationId: String? = "tree://folder-one",
        var failure: BackupFailure? = null,
    ) : EvidenceBackupEngine.DestinationStatus {
        override fun currentFailure(): BackupFailure? = failure
    }

    private class FakeTarget(
        var outcome: (String) -> BackupOutcome = { eventId ->
            BackupOutcome.Written("$eventId-evidence.zip", 4, "digest")
        },
    ) : BackupTarget {
        val writes = mutableListOf<String>()
        val deletes = mutableListOf<String>()
        override fun writeBundle(eventId: String, bytes: ByteArray): BackupOutcome {
            writes += eventId
            return outcome(eventId)
        }
        override fun deleteBundle(eventId: String): Boolean {
            deletes += eventId
            return true
        }
    }

    private fun engine(
        destination: FakeDestination = FakeDestination(),
        target: FakeTarget = FakeTarget(),
        bundles: EvidenceBundleSource = EvidenceBundleSource { byteArrayOf(1, 2, 3, 4) },
        store: BackupStateStore = BackupStateStore(temp.newFolder()),
    ) = Triple(
        EvidenceBackupEngine(store, destination, target, bundles) { 1_000L },
        store,
        target,
    )

    @Test
    fun `writes a bundle and records a verified backup`() {
        val (engine, store, target) = engine()

        val result = engine.runPass(listOf(eventA))

        assertEquals(1, result.backedUp)
        assertEquals(listOf(eventA), target.writes)
        val state = store.get(eventA)
        assertEquals(BackupStage.BACKED_UP, state.stage)
        assertTrue(state.isBackedUpTo("tree://folder-one"))
    }

    @Test
    fun `an already backed up event is not rewritten`() {
        val (engine, _, target) = engine()
        engine.runPass(listOf(eventA))
        target.writes.clear()

        val second = engine.runPass(listOf(eventA))

        assertEquals(1, second.alreadyBackedUp)
        assertEquals(0, second.backedUp)
        assertTrue("a verified copy was pointlessly rewritten", target.writes.isEmpty())
    }

    @Test
    fun `a copy in a previous folder does not count as backed up in the new one`() {
        // The central honesty rule. The bytes still exist somewhere, but they are
        // not in the folder the user is now being told holds their backups.
        val destination = FakeDestination()
        val store = BackupStateStore(temp.newFolder())
        val (engine, _, _) = engine(destination = destination, store = store)
        engine.runPass(listOf(eventA))
        assertTrue(store.get(eventA).isBackedUpTo("tree://folder-one"))

        destination.destinationId = "tree://folder-two"

        assertFalse(store.get(eventA).isBackedUpTo("tree://folder-two"))
    }

    @Test
    fun `switching folders gives an event a fresh attempt budget`() {
        // An event that exhausted its attempts against a removed SD card must not
        // arrive at a newly chosen folder already marked permanently failed.
        val destination = FakeDestination()
        val target = FakeTarget { BackupOutcome.Failed(BackupFailure.WRITE_FAILED, "nope") }
        val store = BackupStateStore(temp.newFolder())
        val (engine, _, _) = engine(destination, target, store = store)

        repeat(BackupConfig.MAX_ATTEMPTS) { engine.runPass(listOf(eventA)) }
        assertEquals(BackupStage.FAILED, store.get(eventA).stage)

        destination.destinationId = "tree://folder-two"
        target.outcome = { BackupOutcome.Written("ok.zip", 4, "digest") }
        val result = engine.runPass(listOf(eventA))

        assertEquals(1, result.backedUp)
        assertEquals(BackupStage.BACKED_UP, store.get(eventA).stage)
    }

    @Test
    fun `a destination problem blocks the pass without blaming every capture`() {
        val destination = FakeDestination(failure = BackupFailure.DESTINATION_PERMISSION_LOST)
        val store = BackupStateStore(temp.newFolder())
        val (engine, _, target) = engine(destination = destination, store = store)

        val result = engine.runPass(listOf(eventA, eventB))

        assertEquals(BackupFailure.DESTINATION_PERMISSION_LOST, result.blockedBy)
        assertEquals(0, result.failed)
        assertTrue(target.writes.isEmpty())
        // No per-event state written: one revoked folder is one problem, and
        // recording it against every capture would bury it.
        assertEquals(BackupStage.NOT_BACKED_UP, store.get(eventA).stage)
        assertNull(store.get(eventA).failure)
    }

    @Test
    fun `retries a retryable failure until the attempt budget is spent`() {
        val target = FakeTarget { BackupOutcome.Failed(BackupFailure.WRITE_FAILED, "disk hiccup") }
        val store = BackupStateStore(temp.newFolder())
        val (engine, _, _) = engine(target = target, store = store)

        repeat(BackupConfig.MAX_ATTEMPTS - 1) { engine.runPass(listOf(eventA)) }
        assertEquals(BackupStage.NOT_BACKED_UP, store.get(eventA).stage)

        engine.runPass(listOf(eventA))

        assertEquals(BackupStage.FAILED, store.get(eventA).stage)
        assertEquals(BackupConfig.MAX_ATTEMPTS, store.get(eventA).attempts)
    }

    @Test
    fun `a non-retryable failure stops immediately`() {
        // Retrying a name conflict only mints more copies under names nobody is
        // looking for, so it goes straight to FAILED on the first attempt.
        val target = FakeTarget { BackupOutcome.Failed(BackupFailure.NAME_CONFLICT, "renamed") }
        val store = BackupStateStore(temp.newFolder())
        val (engine, _, _) = engine(target = target, store = store)

        engine.runPass(listOf(eventA))

        assertEquals(BackupStage.FAILED, store.get(eventA).stage)
        assertEquals(1, store.get(eventA).attempts)
    }

    @Test
    fun `a failure clears any previously recorded copy details`() {
        // Otherwise a FAILED state would still advertise a size and digest, and
        // the UI would have every reason to render it as a real copy.
        val target = FakeTarget()
        val store = BackupStateStore(temp.newFolder())
        val destination = FakeDestination()
        val (engine, _, _) = engine(destination, target, store = store)
        engine.runPass(listOf(eventA))
        assertEquals("digest", store.get(eventA).sha256)

        // Same destination, so the state carries forward rather than resetting.
        target.outcome = { BackupOutcome.Failed(BackupFailure.NAME_CONFLICT, "renamed") }
        // Force another attempt on an event that is currently BACKED_UP by
        // clearing the stage the way a destination re-verification would.
        store.put(store.get(eventA).copy(stage = BackupStage.NOT_BACKED_UP))
        engine.runPass(listOf(eventA))

        val state = store.get(eventA)
        assertEquals(BackupStage.FAILED, state.stage)
        assertNull("a failed backup still claimed a digest", state.sha256)
        assertNull(state.sizeBytes)
        assertNull(state.fileName)
        assertNull(state.backedUpAtWallClockMillis)
    }

    @Test
    fun `an exporter that throws is recorded against the event, not the pass`() {
        val store = BackupStateStore(temp.newFolder())
        val (engine, _, _) = engine(
            bundles = EvidenceBundleSource { error("media file is missing") },
            store = store,
        )

        val result = engine.runPass(listOf(eventA))

        assertNull(result.blockedBy)
        assertEquals(1, result.failed)
        assertEquals(BackupFailure.BUNDLE_UNAVAILABLE, store.get(eventA).failure)
        assertTrue(store.get(eventA).lastError!!.contains("media file is missing"))
    }

    @Test
    fun `an empty bundle is refused rather than written`() {
        // An empty archive writes and verifies perfectly happily, and proves
        // nothing while sitting in the folder looking like a backup.
        val target = FakeTarget()
        val store = BackupStateStore(temp.newFolder())
        val (engine, _, _) = engine(
            target = target,
            bundles = EvidenceBundleSource { ByteArray(0) },
            store = store,
        )

        engine.runPass(listOf(eventA))

        assertTrue("an empty bundle reached the destination", target.writes.isEmpty())
        assertEquals(BackupFailure.BUNDLE_UNAVAILABLE, store.get(eventA).failure)
    }

    @Test
    fun `forget removes both the copy and its bookkeeping`() {
        val target = FakeTarget()
        val store = BackupStateStore(temp.newFolder())
        val (engine, _, _) = engine(target = target, store = store)
        engine.runPass(listOf(eventA))

        engine.forget(eventA)

        assertEquals(listOf(eventA), target.deletes)
        assertEquals(BackupStage.NOT_BACKED_UP, store.get(eventA).stage)
    }

    @Test
    fun `a pass over several events counts each outcome separately`() {
        val target = FakeTarget { eventId ->
            if (eventId == eventA) BackupOutcome.Written("a.zip", 4, "d")
            else BackupOutcome.Failed(BackupFailure.NAME_CONFLICT, "renamed")
        }
        val (engine, _, _) = engine(target = target)

        val result = engine.runPass(listOf(eventA, eventB))

        assertEquals(1, result.backedUp)
        assertEquals(1, result.failed)
        assertEquals(0, result.alreadyBackedUp)
    }

    @Test
    fun `the last pass result survives a new store instance`() {
        val dir = temp.newFolder()
        val (engine, _, _) = engine(store = BackupStateStore(dir))
        engine.runPass(listOf(eventA))

        val reopened = BackupStateStore(dir)

        assertEquals(1, reopened.lastPass()!!.backedUp)
    }

    @Test
    fun `pass bookkeeping is not mistaken for an event`() {
        // `_last-pass.json` lives in the same directory. Event ids are UUIDs and
        // never start with the internal prefix, so listing must skip it.
        val dir = temp.newFolder()
        val (engine, store, _) = engine(store = BackupStateStore(dir))
        engine.runPass(listOf(eventA))

        assertEquals(setOf(eventA), store.all().keys)
    }
}
