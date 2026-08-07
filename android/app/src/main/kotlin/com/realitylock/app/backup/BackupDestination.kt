package com.realitylock.app.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/**
 * The folder the user nominated for evidence backups, and whether it is still
 * usable.
 *
 * ## Why a folder the user picks, rather than a path the app chooses
 *
 * The app's own `filesDir` copy is deleted when the app is uninstalled or its
 * data cleared — including by a "clear data" tap that a user under pressure to
 * destroy evidence would be told to make. A second copy is only meaningfully a
 * backup if it survives the app, which means it must live somewhere the app does
 * not own. Since Android 10's scoped storage that means the Storage Access
 * Framework, and SAF only grants access to a tree the user explicitly picked.
 *
 * ## Exactly one grant is held
 *
 * The persisted-permission table is capped per app — 512 entries on API 30+, 128
 * below — and exceeding it makes future `takePersistableUriPermission` calls
 * fail. Taking one grant per *file* would march toward that ceiling one capture
 * at a time and then start failing silently. One grant on the tree, and every
 * document created inside it, stays at one entry forever.
 *
 * The previous grant is released when a new folder is chosen, so replacing the
 * destination repeatedly cannot leak entries either.
 */
class BackupDestination(context: Context) {

    private val appContext = context.applicationContext
    private val prefs =
        appContext.getSharedPreferences(BackupConfig.PREFS_NAME, Context.MODE_PRIVATE)

    /** The chosen tree URI, or null when the user has never picked a folder. */
    val treeUri: Uri?
        get() = prefs.getString(BackupConfig.KEY_TREE_URI, null)?.let(Uri::parse)

    /**
     * Stable identity of the current destination, as recorded in
     * [BackupState.destinationId]. The URI string itself: it changes when the
     * user picks a different folder, which is exactly when previously written
     * copies stop being backups *to the current destination*.
     */
    val destinationId: String?
        get() = prefs.getString(BackupConfig.KEY_TREE_URI, null)

    /** The flags SAF needs for a durable read/write grant on a tree. */
    val takeFlags: Int =
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    /**
     * Records a newly picked folder and takes a persistable grant on it.
     *
     * Returns false when the system refuses the grant, which is a real outcome
     * rather than a defensive branch: the persisted-permission table can be full,
     * and a provider can decline. Recording the URI anyway would leave the app
     * pointing at a folder it cannot write to and reporting "no backups yet"
     * instead of "the folder was refused".
     */
    fun remember(uri: Uri): Boolean {
        val resolver = appContext.contentResolver
        val previous = treeUri
        return runCatching {
            resolver.takePersistableUriPermission(uri, takeFlags)
            prefs.edit().putString(BackupConfig.KEY_TREE_URI, uri.toString()).apply()
            // Only after the new grant is secured. Releasing first would leave the
            // app with no destination at all if the new grant then failed.
            if (previous != null && previous != uri) {
                runCatching { resolver.releasePersistableUriPermission(previous, takeFlags) }
            }
            true
        }.getOrDefault(false)
    }

    /** Forgets the destination and releases its grant. */
    fun clear() {
        treeUri?.let { uri ->
            runCatching {
                appContext.contentResolver.releasePersistableUriPermission(uri, takeFlags)
            }
        }
        prefs.edit().remove(BackupConfig.KEY_TREE_URI).apply()
    }

    /**
     * Why the destination cannot be written to right now, or null when it can.
     *
     * Checked before every pass rather than trusting the stored URI. A grant can
     * be revoked in system settings, and a folder can be deleted or live on a
     * card that is no longer in the phone — all of which look identical to a
     * stale preference until someone asks the provider.
     */
    fun currentFailure(): BackupFailure? {
        val uri = treeUri ?: return BackupFailure.NO_DESTINATION

        val stillGranted = appContext.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isWritePermission
        }
        if (!stillGranted) return BackupFailure.DESTINATION_PERMISSION_LOST

        val tree = runCatching { DocumentFile.fromTreeUri(appContext, uri) }.getOrNull()
        if (tree == null || !tree.isDirectory) return BackupFailure.DESTINATION_UNREACHABLE
        // `canWrite` consults the provider, so an unmounted SD card or a deleted
        // folder is caught here rather than at the first failed write.
        if (!tree.canWrite()) return BackupFailure.DESTINATION_UNREACHABLE
        return null
    }

    /** Human-readable folder name for the UI, or null when none is set. */
    fun displayName(): String? {
        val uri = treeUri ?: return null
        return runCatching { DocumentFile.fromTreeUri(appContext, uri)?.name }.getOrNull()
    }
}
