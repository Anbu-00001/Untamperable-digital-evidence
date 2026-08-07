package com.realitylock.app.backup

/**
 * Where a bundle's bytes actually go, behind one method.
 *
 * This is the seam that keeps [EvidenceBackupEngine] — all the decisions about
 * what to attempt, when to stop retrying and what to tell the user — testable in
 * plain JVM unit tests. Everything on the far side of it needs a real
 * DocumentsProvider and can only be exercised on a device.
 */
interface BackupTarget {

    /**
     * Writes [bytes] for [eventId], verifies them by reading them back, and gives
     * the result its final name.
     *
     * Implementations must not report [BackupOutcome.Written] unless the bytes at
     * the destination have been re-read and match. This interface exists to make
     * a copy the user can rely on; a write that was merely *issued* is exactly
     * the silent failure [BackupStage] splits FAILED out to avoid.
     *
     * Never throws — a failure is a value here, because the engine has to record
     * *which* failure it was to decide whether retrying is worth anything.
     */
    fun writeBundle(eventId: String, bytes: ByteArray): BackupOutcome

    /** Drops a previously written bundle, e.g. when its event is deleted. */
    fun deleteBundle(eventId: String): Boolean
}

/** The result of one write attempt against a [BackupTarget]. */
sealed interface BackupOutcome {

    /**
     * Bytes are at the destination and were read back intact.
     *
     * [fileName] is the name the destination *actually* used, not the one that
     * was requested. A provider that de-duplicated the name to `… (1).zip` has
     * not written the file this state describes, and the engine treats a
     * mismatch as [BackupFailure.NAME_CONFLICT] rather than recording success
     * under a name nobody will look for.
     */
    data class Written(
        val fileName: String,
        val sizeBytes: Long,
        val sha256: String,
    ) : BackupOutcome

    data class Failed(val failure: BackupFailure, val message: String) : BackupOutcome
}
