package com.dd3boh.outertune.utils

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.dd3boh.outertune.db.InternalDatabase
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.extensions.div
import com.dd3boh.outertune.extensions.zipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.OutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.Deflater
import java.util.zip.ZipEntry

/**
 * Filename the settings DataStore's physical Preferences file is stored under, and the zip entry
 * name it's stored under in a backup - shared between [writeBackup] and
 * [com.dd3boh.outertune.viewmodels.BackupRestoreViewModel]'s restore path, which needs the exact
 * same name to recognise the entry.
 */
const val BACKUP_SETTINGS_FILENAME = "settings.preferences_pb"

private const val TAG = "BackupUtil"
private const val AUTO_BACKUP_PREFIX = "outertune_auto_"
private const val AUTO_BACKUP_RELATIVE_DIR = "OuterTune Backup"

/**
 * Writes a full backup - the settings DataStore file and a checkpointed copy of the whole song
 * database, every table, exactly as it exists live - to [outputStream] as a zip. This is the
 * format the manual Backup action in Settings > Backup and Restore produces and Restore reads
 * back, and [writeAutoBackup] below writes the exact same thing on its own: one backup format,
 * one restore path, nothing selective to keep in sync between them.
 *
 * The file copy runs inside [MusicDatabase.runInTransaction], which holds the database's single
 * writer connection for its duration. `PRAGMA wal_checkpoint(FULL)` on its own only guarantees the
 * WAL is flushed into the main file *at the moment it runs* - nothing stops another write landing
 * in that file a moment later, mid-copy, since WAL mode exists specifically to let writers proceed
 * without waiting on a reader. A raw file copy caught mid-write isn't a smaller backup, it's a
 * torn one: restoring it can fail integrity checks or, worse, silently load a partially-written
 * database. Holding the writer connection for the copy (typically well under a second for this
 * app's database sizes) blocks other writes just long enough to guarantee the bytes on disk don't
 * move while they're being read; reads elsewhere are unaffected, since WAL mode never blocks those
 * on a writer.
 *
 * The checkpoint itself has to run *before* that transaction starts, not inside it: SQLite refuses
 * `PRAGMA wal_checkpoint` while a transaction is already open on the same connection (it has
 * nothing to do there - a checkpoint moves committed WAL frames into the main file, and a
 * transaction, committed or not, can't be checkpointed mid-flight), so calling it inside
 * runInTransaction threw on every backup rather than actually checkpointing anything. A write that
 * lands in the gap between this checkpoint and the transaction starting just stays in the WAL,
 * untouched by the copy below (which only ever reads the main file) - not torn, merely not yet
 * included, exactly as if the backup had been taken a moment earlier.
 */
suspend fun writeBackup(context: Context, database: MusicDatabase, outputStream: OutputStream) {
    withContext(Dispatchers.IO) {
        outputStream.buffered().zipOutputStream().use { out ->
            out.setLevel(Deflater.BEST_COMPRESSION)
            (context.filesDir / "datastore" / BACKUP_SETTINGS_FILENAME).inputStream().buffered().use { input ->
                out.putNextEntry(ZipEntry(BACKUP_SETTINGS_FILENAME))
                input.copyTo(out)
            }
            database.checkpoint()
            database.runInTransaction {
                FileInputStream(database.openHelper.writableDatabase.path).use { input ->
                    out.putNextEntry(ZipEntry(InternalDatabase.DB_NAME))
                    input.copyTo(out)
                }
            }
        }
    }
}

/**
 * Writes the exact same backup [writeBackup] does, on its own, into Downloads/OuterTune Backup -
 * created there automatically the first time, via [MediaStore] rather than a folder the user has
 * to pick, so this can actually run unattended. Only available from Android 10 (Q) on: below that
 * this silently does nothing rather than reach for the broad legacy storage permission this app
 * otherwise avoids entirely (see the manifest) just for a background convenience feature.
 *
 * Prunes down to the newest [keepCount] automatic backups afterward (matched by
 * [AUTO_BACKUP_PREFIX], so a manual backup someone drops in the same folder is never touched).
 * Returns true on success; false (never throws) on any failure, since this runs unattended and
 * has nothing to show a user.
 */
suspend fun writeAutoBackup(
    context: Context,
    database: MusicDatabase,
    keepCount: Int,
): Boolean = withContext(Dispatchers.IO) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@withContext false

    runCatching {
        val formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
        val fileName = "$AUTO_BACKUP_PREFIX${LocalDateTime.now().format(formatter)}.backup"

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$AUTO_BACKUP_RELATIVE_DIR")
        }
        val destUri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return@runCatching false

        val wrote = context.contentResolver.openOutputStream(destUri)?.use { out ->
            writeBackup(context, database, out)
            true
        } ?: false

        if (!wrote) {
            context.contentResolver.delete(destUri, null, null)
            return@runCatching false
        }

        pruneOldAutoBackups(context, keepCount)
        true
    }.onFailure {
        Log.e(TAG, "Automatic backup failed", it)
    }.getOrDefault(false)
}

/** Deletes every automatic backup past the newest [keepCount], oldest first. */
private fun pruneOldAutoBackups(context: Context, keepCount: Int) {
    val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME)
    val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
    val args = arrayOf("${Environment.DIRECTORY_DOWNLOADS}/$AUTO_BACKUP_RELATIVE_DIR/", "$AUTO_BACKUP_PREFIX%")

    val toDelete = mutableListOf<Uri>()
    context.contentResolver.query(
        MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, selection, args,
        "${MediaStore.MediaColumns.DISPLAY_NAME} DESC"
    )?.use { cursor ->
        val idIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
        var seen = 0
        while (cursor.moveToNext()) {
            seen++
            if (seen > keepCount) {
                toDelete += ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(idIdx))
            }
        }
    }
    toDelete.forEach { context.contentResolver.delete(it, null, null) }
}
