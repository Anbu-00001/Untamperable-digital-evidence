package com.realitylock.app.core.device

import android.content.Context
import java.util.UUID

/**
 * Supplies the stable per-installation identifier bound into every proof
 * package.
 *
 * Deliberately a locally-generated UUID rather than IMEI or `ANDROID_ID`
 * (research/03 §5): IMEI is unreadable by Play Store apps since Android 10, and
 * `ANDROID_ID` is a non-resettable device-scoped identifier — a heavier privacy
 * commitment than this use case needs. An install-scoped UUID proves "this
 * installation produced this event", which is exactly the claim the proof
 * package makes, and it resets naturally on uninstall.
 *
 * Backed by SharedPreferences rather than DataStore: it is a single value read
 * once at capture time, so the synchronous API is a better fit and avoids an
 * extra dependency.
 */
class InstallIdProvider(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Returns the install ID, generating and persisting one on first call. */
    fun getOrCreate(): String {
        prefs.getString(KEY_INSTALL_ID, null)?.let { return it }
        val generated = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_INSTALL_ID, generated).apply()
        return generated
    }

    private companion object {
        const val PREFS_NAME = "reality_lock_identity"
        const val KEY_INSTALL_ID = "install_id"
    }
}
