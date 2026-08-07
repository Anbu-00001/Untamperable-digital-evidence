package com.realitylock.app.backup

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.realitylock.app.crypto.Hashing
import java.io.IOException

/**
 * Writes evidence bundles into the user's chosen SAF tree.
 *
 * ## Write, verify, then name
 *
 * Bytes go to a `.zip.part` staging document first, are read back and compared,
 * and only then is the document renamed to `<event>-evidence.zip`. The ordering
 * is the point: if the process is killed mid-write or the card is pulled, what
 * survives is an obviously-incomplete `.part` file rather than a truncated
 * archive that a human browsing the folder would reasonably take for a backup.
 *
 * The read-back is not belt-and-braces. A `write()` that returns without
 * throwing has told you the bytes reached a `ParcelFileDescriptor`, not that they
 * reached storage — a full volume, an unmounted card, or a provider that buffers
 * can all produce a clean write and a short file. Since the entire value of this
 * feature is a copy the user can rely on without checking, the check happens here.
 *
 * ## Names are compared, not assumed
 *
 * `createFile` is a *request*. A provider that already holds a document with the
 * same name will happily return one called `… (1).zip`, and reporting success
 * would record a state pointing at a file that does not exist under that name.
 * A mismatch is [BackupFailure.NAME_CONFLICT] and is not retried, because
 * retrying only mints more copies nobody is looking for.
 */
class SafBackupTarget(
    context: Context,
    private val destination: BackupDestination,
) : BackupTarget {

    private val appContext = context.applicationContext

    override fun writeBundle(eventId: String, bytes: ByteArray): BackupOutcome {
        val treeUri = destination.treeUri
            ?: return BackupOutcome.Failed(BackupFailure.NO_DESTINATION, "no folder chosen")
        val tree = runCatching { DocumentFile.fromTreeUri(appContext, treeUri) }.getOrNull()
            ?: return BackupOutcome.Failed(
                BackupFailure.DESTINATION_UNREACHABLE,
                "the chosen folder could not be opened",
            )

        val finalName = BackupConfig.bundleFileName(eventId)
        val stagingName = BackupConfig.stagingFileName(eventId)

        // A leftover from an interrupted attempt. Removed rather than reused: its
        // length is unknown and appending to it would produce a corrupt archive.
        tree.findFile(stagingName)?.delete()

        // A completed bundle from a previous attempt whose state was lost. Removed
        // so the rename below cannot collide with it and be silently renumbered.
        tree.findFile(finalName)?.delete()

        val staging = runCatching {
            tree.createFile(BackupConfig.STAGING_MIME_TYPE, stagingName)
        }.getOrNull()
            ?: return BackupOutcome.Failed(
                BackupFailure.WRITE_FAILED,
                "the folder refused to create a file",
            )

        try {
            appContext.contentResolver.openOutputStream(staging.uri, "wt").use { out ->
                if (out == null) {
                    staging.delete()
                    return BackupOutcome.Failed(
                        BackupFailure.WRITE_FAILED,
                        "the folder could not be opened for writing",
                    )
                }
                var offset = 0
                while (offset < bytes.size) {
                    val length = minOf(BackupConfig.WRITE_CHUNK_BYTES, bytes.size - offset)
                    out.write(bytes, offset, length)
                    offset += length
                }
                // Without this, bytes can still be sitting in a buffer when the
                // descriptor closes, and a full volume reports its error late.
                out.flush()
            }
        } catch (e: IOException) {
            staging.delete()
            return BackupOutcome.Failed(failureFor(e), e.message ?: "write failed")
        } catch (e: SecurityException) {
            staging.delete()
            return BackupOutcome.Failed(
                BackupFailure.DESTINATION_PERMISSION_LOST,
                e.message ?: "permission denied",
            )
        }

        // --- verify by reading the bytes back ---------------------------------
        val readBack = runCatching {
            appContext.contentResolver.openInputStream(staging.uri)?.use { it.readBytes() }
        }.getOrNull()

        if (readBack == null || readBack.size != bytes.size) {
            staging.delete()
            return BackupOutcome.Failed(
                BackupFailure.VERIFICATION_FAILED,
                "wrote ${bytes.size} bytes, read back ${readBack?.size ?: 0}",
            )
        }
        val expectedDigest = Hashing.toHex(Hashing.sha256(bytes))
        val actualDigest = Hashing.toHex(Hashing.sha256(readBack))
        if (expectedDigest != actualDigest) {
            staging.delete()
            return BackupOutcome.Failed(
                BackupFailure.VERIFICATION_FAILED,
                "the copy at the destination does not match what was written",
            )
        }

        // --- only now does it get the real name -------------------------------
        val renamed = runCatching { staging.renameTo(finalName) }.getOrDefault(false)
        if (!renamed) {
            staging.delete()
            return BackupOutcome.Failed(
                BackupFailure.NAME_CONFLICT,
                "the folder would not accept the name $finalName",
            )
        }

        // `renameTo` mutates the DocumentFile in place on success, but a provider
        // is free to have chosen a different name. Trusting the request here is
        // exactly the assumption this check exists to refuse.
        val actualName = staging.name
        if (actualName != finalName) {
            return BackupOutcome.Failed(
                BackupFailure.NAME_CONFLICT,
                "asked for $finalName, the folder created $actualName",
            )
        }

        return BackupOutcome.Written(
            fileName = actualName,
            sizeBytes = bytes.size.toLong(),
            sha256 = expectedDigest,
        )
    }

    override fun deleteBundle(eventId: String): Boolean {
        val treeUri = destination.treeUri ?: return false
        val tree = runCatching { DocumentFile.fromTreeUri(appContext, treeUri) }.getOrNull()
            ?: return false
        val deleted = tree.findFile(BackupConfig.bundleFileName(eventId))?.delete() ?: false
        tree.findFile(BackupConfig.stagingFileName(eventId))?.delete()
        return deleted
    }

    /**
     * A full volume is worth separating from a generic write error: it is
     * retryable and the user can actually do something about it, whereas
     * "write failed" tells them nothing. Matched on the message because SAF
     * surfaces ENOSPC as a plain IOException with no distinct type.
     */
    private fun failureFor(e: IOException): BackupFailure {
        val message = e.message.orEmpty().lowercase()
        return if ("space" in message || "enospc" in message) BackupFailure.OUT_OF_SPACE
        else BackupFailure.WRITE_FAILED
    }
}
