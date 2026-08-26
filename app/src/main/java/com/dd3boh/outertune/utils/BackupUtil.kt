package com.dd3boh.outertune.utils

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
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

/** How many automatic backups to keep before pruning the oldest - "automatic" isn't "unbounded disk use". */
private const val AUTO_BACKUP_KEEP = 5

/**
 * Writes a full backup - the settings DataStore file and a checkpointed copy of the whole song
 * database (every table, not just "stats" ones - there's no reason to maintain a second, partial
 * export format) - to [outputStream] as a zip. This is the same format the manual Backup action
 * in Settings > Backup and Restore produces and Restore reads back; shared so an automatic
 * backup (see [writeAutoBackup]) stays restorable the exact same way.
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
 * Writes an automatic backup into the folder at [treeUriString] - a SAF tree URI the user granted
 * persistent access to once (see AutoBackupUriKey) - then prunes down to the newest
 * [AUTO_BACKUP_KEEP] files this function itself wrote (matched by [AUTO_BACKUP_PREFIX], so a
 * manual backup dropped into the same folder is never touched). Returns true on success; false
 * (never throws) on any failure, since this runs unattended and has nothing to show a user.
 */
suspend fun writeAutoBackup(context: Context, database: MusicDatabase, treeUriString: String): Boolean =
    withContext(Dispatchers.IO) {
        runCatching {
            val treeDoc = DocumentFile.fromTreeUri(context, Uri.parse(treeUriString)) ?: return@runCatching false
            val formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
            val fileName = "$AUTO_BACKUP_PREFIX${LocalDateTime.now().format(formatter)}.backup"
            val newFile = treeDoc.createFile("application/octet-stream", fileName) ?: return@runCatching false
            val wrote = context.contentResolver.openOutputStream(newFile.uri)?.use { out ->
                writeBackup(context, database, out)
                true
            } ?: false
            if (!wrote) {
                newFile.delete()
                return@runCatching false
            }

            treeDoc.listFiles()
                .filter { it.name?.startsWith(AUTO_BACKUP_PREFIX) == true }
                .sortedByDescending { it.name }
                .drop(AUTO_BACKUP_KEEP)
                .forEach { it.delete() }

            true
        }.onFailure {
            Log.e(TAG, "Automatic backup failed", it)
        }.getOrDefault(false)
    }
