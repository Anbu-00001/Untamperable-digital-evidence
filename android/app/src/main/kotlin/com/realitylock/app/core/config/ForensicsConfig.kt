package com.realitylock.app.core.config

/**
 * Tuning for the Phase-4 forensic analysis tool (ELA + EXIF).
 *
 * These heuristics run on a user-selected *candidate* image the user wants to
 * scrutinise — never on the app's own signed captures, which are already proven
 * by the cryptographic pipeline. The whole layer is a triage aid, and the config
 * comments say so, because overclaiming it is the real risk here.
 */
object ForensicsConfig {

    /**
     * JPEG quality for the ELA re-save. 95 is the FotoForensics convention and
     * the value published ELA implementations use; changing it changes what the
     * map means, so it lives here as one named constant.
     */
    const val ELA_RESAVE_QUALITY: Int = 95

    /**
     * Display gain applied to the (usually tiny) per-pixel differences so the
     * map is visible. Auto-scaling to the observed maximum is used when that is
     * larger; this is the floor so a near-uniform authentic image still renders
     * as mostly dark rather than being blown up into noise.
     */
    const val ELA_MIN_DISPLAY_GAIN: Int = 15

    /** Colour channels compared per pixel (R,G,B; alpha ignored). */
    const val RGB_CHANNELS: Int = 3
    const val MAX_CHANNEL_VALUE: Int = 255

    /**
     * Longest edge the candidate image is scaled to before ELA. Full-resolution
     * phone photos are large; ELA is a qualitative map, so a bounded working size
     * keeps it fast and memory-safe without changing the conclusion.
     */
    const val ELA_MAX_WORKING_EDGE_PX: Int = 1024

    // --- EXIF editor-software fingerprints (substring match, case-insensitive) ---
    // Presence is only *suggestive* (any of these can be forged or stripped);
    // absence proves nothing. Named here rather than inline so the list is one
    // reviewable place.
    val EDITOR_SOFTWARE_MARKERS: List<String> = listOf(
        "photoshop", "gimp", "lightroom", "snapseed", "affinity",
        "pixlr", "paint.net", "picsart", "faststone", "photoscape",
    )
}
