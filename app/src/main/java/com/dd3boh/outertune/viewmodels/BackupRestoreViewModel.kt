package com.dd3boh.outertune.viewmodels

import android.content.Context
import android.content.Intent
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
import kotlinx.coroutines.withContext
import java.io.File
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
        // The file work runs on IO - copying a database of any size on the main thread froze the
        // screen long enough to trip an ANR. The Toasts and the restart come back to the main thread.
        viewModelScope.launch {
            runCatching {
                val compatible = withContext(Dispatchers.IO) { restoreFiles(uri) }
                if (!compatible) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.err_restore_incompatible_database),
                        Toast.LENGTH_SHORT
                    ).show()
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
    }

    /** Unpacks the backup at [uri]. Returns false if it held a database this build cannot open. */
    private fun restoreFiles(uri: Uri): Boolean {
        var compatible = true
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
                            // Resolved before closing. It used to be read from
                            // database.openHelper.writableDatabase.path after close(), which
                            // quietly opened the database again - with the old file, its WAL and
                            // its connection all live while the new file was copied over it.
                            val dbFile = context.getDatabasePath(InternalDatabase.DB_NAME)
                            // TRUNCATE rather than FULL: FULL copies the WAL into the database but
                            // leaves its frames in the file, where they could be replayed onto the
                            // restored database.
                            database.checkpointTruncate()
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
                                Log.i(TAG, "Found valid database, proceeding with restore")
                                destFile.inputStream().use { inputStream ->
                                    FileOutputStream(dbFile).use { outputStream ->
                                        inputStream.copyTo(outputStream)
                                    }
                                }
                                // The old database's WAL and shared-memory index describe pages of
                                // a file that no longer exists. Left behind, SQLite would apply
                                // them to the restored one on the next open and corrupt it.
                                File(dbFile.path + "-wal").delete()
                                File(dbFile.path + "-shm").delete()
                            } else {
                                Log.e(TAG, "Incompatible database, aborting restore")
                                compatible = false
                            }
                        }
                    }
                    entry = inputStream.nextEntry
                }
            }
        }
        return compatible
    }
}
