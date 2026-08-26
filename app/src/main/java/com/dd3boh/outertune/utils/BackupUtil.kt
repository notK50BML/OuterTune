package com.dd3boh.outertune.utils

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.dd3boh.outertune.constants.BackupIntervalUnit
import com.dd3boh.outertune.db.InternalDatabase
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.extensions.div
import com.dd3boh.outertune.extensions.zipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
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
 * Which optional tables an automatic backup includes, beyond the library itself (songs, artists,
 * albums, downloads, queue, settings - always included, since e.g. a playlist without the songs
 * it references would be useless on its own). Maps directly onto real tables in [applyCategoryFilter];
 * see that function for exactly what each one keeps or drops.
 */
data class AutoBackupCategories(
    val history: Boolean = true,
    val localPlaylists: Boolean = true,
    val libraryPlaylists: Boolean = true,
    val stats: Boolean = true,
) {
    val isEverything: Boolean
        get() = history && localPlaylists && libraryPlaylists && stats
}

/**
 * Writes a full backup - the settings DataStore file and a checkpointed copy of the whole song
 * database - to [outputStream] as a zip. This is the format the manual Backup action in
 * Settings > Backup and Restore produces and Restore reads back; always the full database,
 * regardless of what an automatic backup (see [writeAutoBackup]) might be configured to leave out
 * - restoring from a partial backup should never look like data silently went missing from a
 * *manual* one, which is what someone reaches for specifically to be sure they have everything.
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
            FileInputStream(database.openHelper.writableDatabase.path).use { input ->
                out.putNextEntry(ZipEntry(InternalDatabase.DB_NAME))
                input.copyTo(out)
            }
        }
    }
}

/**
 * Deletes whatever [categories] leaves unchecked from the (already-copied, not-live) database at
 * [dbFile] - a join table is cleared explicitly rather than relying on Room's CASCADE foreign keys
 * firing on a bare framework connection (they need `PRAGMA foreign_keys = ON`, which is enabled
 * here too, but there's no reason to depend on both when being explicit is one extra statement).
 */
private fun applyCategoryFilter(dbFile: File, categories: AutoBackupCategories) {
    if (categories.isEverything) return
    SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
        // The copied bytes carry the live (WAL-mode) database's header, so a connection here would
        // otherwise create its own -wal/-shm sidecars next to dbFile - forcing rollback-journal
        // mode instead keeps every change (and its cleanup) inside the one file that gets zipped.
        db.execSQL("PRAGMA journal_mode = DELETE")
        db.execSQL("PRAGMA foreign_keys = ON")
        if (!categories.history) {
            db.execSQL("DELETE FROM event")
            db.execSQL("DELETE FROM search_history")
            db.execSQL("DELETE FROM recent_activity")
        }
        if (!categories.localPlaylists) {
            db.execSQL("DELETE FROM playlist_song_map WHERE playlistId IN (SELECT id FROM playlist WHERE isLocal = 1)")
            db.execSQL("DELETE FROM playlist WHERE isLocal = 1")
        }
        if (!categories.libraryPlaylists) {
            db.execSQL("DELETE FROM playlist_song_map WHERE playlistId IN (SELECT id FROM playlist WHERE isLocal = 0)")
            db.execSQL("DELETE FROM playlist WHERE isLocal = 0")
        }
        if (!categories.stats) {
            db.execSQL("DELETE FROM playCount")
        }
    }
}

/**
 * Writes an automatic backup into Downloads/OuterTune Backup - created on its own the first time,
 * via [MediaStore] rather than a folder the user has to pick, so this can actually run
 * unattended. Only available from Android 10 (Q) on: below that this silently does nothing rather
 * than reach for the broad legacy storage permission this app otherwise avoids entirely (see the
 * manifest) just for a background convenience feature.
 *
 * [categories] controls which optional tables come along - see [applyCategoryFilter]. Prunes down
 * to the newest [keepCount] automatic backups afterward (matched by [AUTO_BACKUP_PREFIX], so a
 * manual backup someone drops in the same folder is never touched). Returns true on success;
 * false (never throws) on any failure, since this runs unattended and has nothing to show a user.
 */
suspend fun writeAutoBackup(
    context: Context,
    database: MusicDatabase,
    categories: AutoBackupCategories,
    keepCount: Int,
): Boolean = withContext(Dispatchers.IO) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@withContext false

    runCatching {
        val formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
        val fileName = "$AUTO_BACKUP_PREFIX${LocalDateTime.now().format(formatter)}.backup"

        val tempDbFile = File(context.cacheDir, "auto_backup_${System.currentTimeMillis()}.db")
        try {
            database.checkpoint()
            FileInputStream(database.openHelper.writableDatabase.path).use { input ->
                tempDbFile.outputStream().use { output -> input.copyTo(output) }
            }
            applyCategoryFilter(tempDbFile, categories)

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$AUTO_BACKUP_RELATIVE_DIR")
            }
            val destUri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return@runCatching false

            val wrote = context.contentResolver.openOutputStream(destUri)?.use { out ->
                out.buffered().zipOutputStream().use { zip ->
                    zip.setLevel(Deflater.BEST_COMPRESSION)
                    (context.filesDir / "datastore" / BACKUP_SETTINGS_FILENAME).inputStream().buffered().use { input ->
                        zip.putNextEntry(ZipEntry(BACKUP_SETTINGS_FILENAME))
                        input.copyTo(zip)
                    }
                    tempDbFile.inputStream().use { input ->
                        zip.putNextEntry(ZipEntry(InternalDatabase.DB_NAME))
                        input.copyTo(zip)
                    }
                }
                true
            } ?: false

            if (!wrote) {
                context.contentResolver.delete(destUri, null, null)
                return@runCatching false
            }

            pruneOldAutoBackups(context, keepCount)
            true
        } finally {
            tempDbFile.delete()
        }
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
