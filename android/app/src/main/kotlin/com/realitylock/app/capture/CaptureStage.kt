package com.realitylock.app.capture

/**
 * The stages of one capture, in the order they happen.
 *
 * Reported by [CaptureCoordinator] as each stage genuinely STARTS, so a UI that
 * animates them is drawing real events. Nothing about the order or the timing is
 * decided by the UI — a stage that took 400 ms shows as 400 ms, and one that took
 * 2 ms shows as 2 ms.
 */
enum class CaptureStage {
    /** The camera takes the frame. */
    SHUTTER,

    /** A GNSS fix is requested (skipped entirely when location is not granted). */
    LOCATE,

    /** Media is written and hashed, metadata canonicalised and hashed, root composed. */
    HASH,

    /** The root is signed by the Keystore-held key. */
    SIGN,

    /** The signed package is persisted. */
    SEAL,
}

/** One stage's outcome. [durationMillis] is null while it is still running. */
data class StageTiming(
    val stage: CaptureStage,
    val durationMillis: Long?,
    val active: Boolean,
)

/**
 * Turns "stage X began now" calls into per-stage durations.
 *
 * A stage ends when the next one begins, or when [finish] is called. Kept as a
 * plain class over an injected clock so the arithmetic is unit-testable without a
 * device, and so no timing logic lives in a composable.
 */
class StageTracker(private val nowMillis: () -> Long) {

    private val starts = LinkedHashMap<CaptureStage, Long>()
    private var finishedAt: Long? = null

    @Synchronized
    fun begin(stage: CaptureStage) {
        if (stage !in starts) starts[stage] = nowMillis()
    }

    @Synchronized
    fun finish() {
        if (finishedAt == null) finishedAt = nowMillis()
    }

    @Synchronized
    fun snapshot(): List<StageTiming> {
        val entries = starts.entries.toList()
        return entries.mapIndexed { index, (stage, start) ->
            val end = entries.getOrNull(index + 1)?.value ?: finishedAt
            StageTiming(stage, durationMillis = end?.let { it - start }, active = end == null)
        }
    }

    /** First stage start to [finish], or null while still running. */
    @Synchronized
    fun totalMillis(): Long? {
        val first = starts.values.firstOrNull() ?: return null
        return finishedAt?.let { it - first }
    }
}
