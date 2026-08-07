package com.realitylock.app.backup

/**
 * How far one captured event has got towards having a second copy in the folder
 * the user nominated.
 *
 * Three states, not two, because "we tried and it did not work" is genuinely
 * different from "we have not tried yet" and the user needs to be able to tell
 * them apart. A backup that silently fails is worse than no backup at all: the
 * user believes they are protected and stops thinking about it.
 */
enum class BackupStage {

    /** No verified copy exists at the current destination. */
    NOT_BACKED_UP,

    /**
     * A copy exists at the current destination and its bytes were re-read and
     * checked after writing. This is the ONLY stage that may be shown to the
     * user as "backed up".
     */
    BACKED_UP,

    /**
     * An attempt was made and did not produce a verified copy. Automatic retries
     * have stopped (see [BackupConfig.MAX_ATTEMPTS]); the reason is kept so the
     * user is told what happened rather than being left with a silent stall.
     */
    FAILED,
}

/**
 * Why a backup did not happen.
 *
 * Every one of these is a real, observed failure mode of writing to a
 * user-nominated SAF tree, not a defensive catch-all:
 *
 * - the user revokes the folder grant in system settings, or the app is
 *   reinstalled and the persisted permission is gone;
 * - the folder is deleted, renamed, or lives on an SD card that is now out of
 *   the phone;
 * - the volume is full;
 * - a document with the target name already exists and the provider silently
 *   renames the new one to `… (1).zip`;
 * - the write throws partway and leaves a truncated archive.
 *
 * [retryable] answers only "is another automatic attempt worth making". A
 * non-retryable failure is not a lost cause — it needs the *user* to do
 * something (re-pick the folder, free space, put the card back), and the UI says
 * so.
 */
enum class BackupFailure(val retryable: Boolean) {

    /** No folder has been chosen yet. Not a failure of any single event. */
    NO_DESTINATION(retryable = false),

    /** The persisted grant on the chosen folder is gone. Only the user can fix it. */
    DESTINATION_PERMISSION_LOST(retryable = false),

    /** Folder deleted, storage unmounted, provider unavailable. May come back. */
    DESTINATION_UNREACHABLE(retryable = true),

    /** The volume is full. May clear if the user frees space. */
    OUT_OF_SPACE(retryable = true),

    /** The exporter could not produce a complete bundle for this event. */
    BUNDLE_UNAVAILABLE(retryable = true),

    /** The write itself threw, or the stream failed to close cleanly. */
    WRITE_FAILED(retryable = true),

    /**
     * Bytes were written, but reading them back produced a different length or a
     * different SHA-256. The partial file is deleted and the event stays
     * NOT_BACKED_UP — reporting success here is precisely the failure this
     * feature exists to prevent.
     */
    VERIFICATION_FAILED(retryable = true),

    /**
     * The destination refused the requested file name, or handed back a
     * different one (a provider de-duplicating against an existing document).
     * Not retried automatically: repeating it just creates more copies under
     * names nobody is looking for.
     */
    NAME_CONFLICT(retryable = false),
}

/**
 * Mutable backup bookkeeping for one event.
 *
 * Stored as a sidecar under [BackupConfig.STATE_SUBDIR], never inside the proof
 * package and never inside `captures/`. The package is a signed document written
 * once at capture and never rewritten, so anything that changes afterwards lives
 * elsewhere (ADR-0006 §3).
 *
 * [destinationId] is what makes "backed up" an honest claim rather than a
 * hopeful one. A copy written to a folder the user has since replaced is not a
 * backup *to the current folder*, and the UI must not present it as one — so the
 * only query exposed is [isBackedUpTo], which requires naming the destination
 * being asked about.
 */
data class BackupState(
    val eventId: String,
    val stage: BackupStage = BackupStage.NOT_BACKED_UP,
    /** Attempts against the CURRENT destination; reset when the folder changes. */
    val attempts: Int = 0,
    val lastAttemptWallClockMillis: Long? = null,
    /** Human-readable reason for the most recent failure; null when fine. */
    val lastError: String? = null,
    val failure: BackupFailure? = null,
    /** Identity of the destination this state describes (the SAF tree URI). */
    val destinationId: String? = null,
    /** Display name of the written document, as the destination actually named it. */
    val fileName: String? = null,
    /** Verified byte length of the written bundle. */
    val sizeBytes: Long? = null,
    /** Verified lowercase-hex SHA-256 of the written bundle. */
    val sha256: String? = null,
    val backedUpAtWallClockMillis: Long? = null,
) {

    /**
     * True only when a verified copy exists at exactly this destination.
     *
     * There is deliberately no `isBackedUp` without an argument: it would be
     * trivially misused to show a green tick for a copy sitting in a folder the
     * user no longer has.
     */
    fun isBackedUpTo(destinationId: String?): Boolean =
        stage == BackupStage.BACKED_UP &&
            this.destinationId != null &&
            this.destinationId == destinationId

    /** True when another automatic attempt against the same destination is worth making. */
    val isRetryable: Boolean
        get() = stage != BackupStage.BACKED_UP && stage != BackupStage.FAILED
}

/**
 * The result of the most recent backup pass, persisted so the UI can still tell
 * the user "backups are failing" after the app has been restarted.
 *
 * [blockedBy] carries the failures that are properties of the destination rather
 * than of any one event — no folder chosen, grant revoked, card removed. Those
 * would otherwise be invisible, because a blocked pass writes no per-event state
 * at all (blaming every capture individually for one revoked permission would
 * bury the actual problem in noise).
 */
data class BackupPassResult(
    val finishedAtWallClockMillis: Long,
    val backedUp: Int = 0,
    val alreadyBackedUp: Int = 0,
    val failed: Int = 0,
    val blockedBy: BackupFailure? = null,
    val detail: String? = null,
)
