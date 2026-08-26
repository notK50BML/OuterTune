package com.dd3boh.outertune.viewmodels

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.MainActivity
import com.dd3boh.outertune.R
import com.dd3boh.outertune.db.InternalDatabase
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.extensions.div
import com.dd3boh.outertune.extensions.zipInputStream
import com.dd3boh.outertune.playback.MusicService
import com.dd3boh.outertune.utils.BACKUP_SETTINGS_FILENAME
import com.dd3boh.outertune.utils.reportException
import com.dd3boh.outertune.utils.writeBackup
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import javax.inject.Inject
import kotlin.system.exitProcess

@HiltViewModel
class BackupRestoreViewModel @Inject constructor(
    // TODO: make restore() non-blocking too
    @ApplicationContext val context: Context,
    val database: MusicDatabase,
) : ViewModel() {
    val TAG = BackupRestoreViewModel::class.simpleName.toString()
    fun backup(uri: Uri) {
        // The Toast calls below need the main thread's Looper, so only the actual file copying
        // moves to IO - not the whole coroutine.
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.applicationContext.contentResolver.openOutputStream(uri)?.use { outputStream ->
                        writeBackup(context, database, outputStream)
                    }
                }
            }.onSuccess {
                Toast.makeText(context, R.string.backup_create_success, Toast.LENGTH_SHORT).show()
            }.onFailure {
                reportException(it)
                Toast.makeText(context, R.string.backup_create_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun restore(uri: Uri) {
        runCatching {
            context.applicationContext.contentResolver.openInputStream(uri)?.use {
                it.zipInputStream().use { inputStream ->
                    var entry = inputStream.nextEntry
                    while (entry != null) {
                        when (entry.name) {
                            BACKUP_SETTINGS_FILENAME -> {
                                (context.filesDir / "datastore" / BACKUP_SETTINGS_FILENAME).outputStream()
                                    .use { outputStream ->
                                        inputStream.copyTo(outputStream)
                                    }
                            }

                            InternalDatabase.DB_NAME -> {
                                Log.i(TAG, "Starting database restore")
                                runBlocking(Dispatchers.IO) {
                                    database.checkpoint()
                                }
                                database.close()

                                Log.i(TAG, "Testing new database for compatibility...")
                                val destFile = context.getDatabasePath(InternalDatabase.TEST_DB_NAME)
                                destFile.parentFile?.apply {
                                    if (!exists()) mkdirs()
                                }
                                FileOutputStream(destFile).use { outputStream ->
                                    inputStream.copyTo(outputStream)
                                }

                                val status = try {
                                    val t = InternalDatabase.newTestInstance(context, InternalDatabase.TEST_DB_NAME)
                                    t.openHelper.writableDatabase.isDatabaseIntegrityOk
                                    t.close()
                                    true
                                } catch (e: Exception) {
                                    Log.e(TAG, "DB validation failed", e)
                                    false
                                }

                                if (status) {
                                    Log.i(TAG, "Found valid database, merging into the live one")
                                    // Merges rather than replaces the live file outright: an
                                    // automatic backup can be a deliberately partial one (see
                                    // AutoBackupCategories) with some tables left empty by
                                    // design, and a straight file swap would read that as "the
                                    // user wants this data gone" rather than "this backup never
                                    // touched it". Every row the backup DOES have overwrites the
                                    // live row with the same primary key; nothing the backup
                                    // doesn't have is removed from what's already live - so
                                    // restoring a stats-only backup updates play counts and
                                    // leaves playlists/history exactly as they were, and restoring
                                    // a full one still can't erase anything added to the live
                                    // library since the backup was taken.
                                    mergeDatabaseFile(
                                        liveDbPath = database.openHelper.writableDatabase.path,
                                        backupDbPath = destFile.path
                                    )
                                } else {
                                    Log.e(TAG, "Incompatible database, aborting restore")
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.err_restore_incompatible_database),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                        entry = inputStream.nextEntry
                    }
                }
            }

            val stopIntent = Intent(context, MusicService::class.java)
            context.stopService(stopIntent)
            val startIntent = Intent(context, MainActivity::class.java)
            startIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(startIntent)
            exitProcess(0)
        }.onFailure {
            reportException(it)
            Toast.makeText(context, it.message, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Copies every row [backupDbPath] has into [liveDbPath], table by table, via SQLite's own
     * ATTACH rather than a Kotlin-side row loop - table names come from the attached database's
     * own sqlite_master, not a hardcoded list, so this keeps working if the schema grows a table
     * without needing to come back and update it. `INSERT OR REPLACE` means a row the backup has
     * overwrites the live row sharing its primary key; a row the backup DOESN'T have (an empty
     * table from an AutoBackupCategories exclusion, or simply a row added to the live library
     * since the backup was taken) is left alone rather than deleted - see the call site's own doc
     * for why that matters here specifically. By the time this runs, both files have already been
     * opened by Room at least once under the current app's migrations (this one when it was first
     * created, the backup's copy via the newTestInstance validation just before this call), so
     * both are on the same schema version and this never needs a migration of its own.
     */
    private fun mergeDatabaseFile(liveDbPath: String, backupDbPath: String) {
        val escapedBackupPath = backupDbPath.replace("'", "''")
        SQLiteDatabase.openDatabase(liveDbPath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("ATTACH DATABASE '$escapedBackupPath' AS backup")
            try {
                val tables = mutableListOf<String>()
                db.rawQuery(
                    "SELECT name FROM backup.sqlite_master " +
                        "WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'room_%' " +
                        "AND name != 'android_metadata'",
                    null
                ).use { cursor ->
                    while (cursor.moveToNext()) tables += cursor.getString(0)
                }

                db.beginTransaction()
                try {
                    for (table in tables) {
                        db.execSQL("INSERT OR REPLACE INTO main.\"$table\" SELECT * FROM backup.\"$table\"")
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            } finally {
                db.execSQL("DETACH DATABASE backup")
            }
        }
    }
}
