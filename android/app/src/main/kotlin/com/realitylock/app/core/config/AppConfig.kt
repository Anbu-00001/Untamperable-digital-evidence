package com.realitylock.app.core.config

import com.realitylock.app.BuildConfig

/**
 * Runtime configuration, read exclusively from BuildConfig fields that the
 * build script populated from gradle.properties / local.properties. No
 * environment-specific literal (backend host, project number, …) is ever
 * hardcoded in feature code — it all flows through here.
 */
object AppConfig {

    /** Base URL of the verification/storage backend (trailing slash included). */
    val backendBaseUrl: String = BuildConfig.BACKEND_BASE_URL

    /** Google Cloud project number for Play Integrity; 0 means "not configured". */
    val playIntegrityCloudProjectNumber: Long = BuildConfig.PLAY_INTEGRITY_CLOUD_PROJECT_NUMBER

    /** True once a real Play Integrity project number has been supplied. */
    val isPlayIntegrityConfigured: Boolean = playIntegrityCloudProjectNumber != 0L

    val isDebug: Boolean = BuildConfig.DEBUG
    val applicationId: String = BuildConfig.APPLICATION_ID
    val versionName: String = BuildConfig.VERSION_NAME
    val versionCode: Int = BuildConfig.VERSION_CODE
}
