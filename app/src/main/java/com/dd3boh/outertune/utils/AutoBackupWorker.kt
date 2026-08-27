package com.dd3boh.outertune.utils

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.dd3boh.outertune.constants.AutoBackupDefaults
import com.dd3boh.outertune.constants.AutoBackupKeepCountKey
import com.dd3boh.outertune.constants.BackupIntervalUnit
import com.dd3boh.outertune.db.InternalDatabase
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

private const val AUTO_BACKUP_WORK_NAME = "auto_backup"

/**
 * Writes an automatic backup on WorkManager's own schedule - never from anything on the playback
 * path. [writeAutoBackup] itself (checkpoint + a full copy of the live database) used to run from
 * a coroutine [com.dd3boh.outertune.playback.MusicService] launched every time it started, which
 * meant the one moment this could stall - contending with the database for the checkpoint, then
 * copying the whole file - was exactly the moment someone had just pressed play. WorkManager runs
 * this on the system's own background job scheduler instead: never while the app is the thing the
 * user is actively waiting on, and it survives process death and reboots on its own.
 *
 * Opens its own short-lived [MusicDatabase][com.dd3boh.outertune.db.MusicDatabase] instance
 * (same `song.db` file, same migration chain as the app's real one - see
 * [InternalDatabase.newInstance]) rather than trying to share the live singleton, since a
 * background worker has no reason to hold a database connection open past this one run.
 */
class AutoBackupWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val keepCount = applicationContext.dataStore.data.first()[AutoBackupKeepCountKey]
            ?: AutoBackupDefaults.KEEP_COUNT

        val database = InternalDatabase.newInstance(applicationContext)
        val wrote = try {
            writeAutoBackup(applicationContext, database, keepCount)
        } finally {
            database.close()
        }

        return if (wrote) Result.success() else Result.retry()
    }
}

/**
 * Enqueues or cancels [AutoBackupWorker] to match the current auto-backup setting. Called once at
 * cold start and again every time the setting changes - see the [com.dd3boh.outertune.App]
 * collector that calls this. [ExistingPeriodicWorkPolicy.UPDATE] means calling this again with a
 * new interval reschedules the existing job instead of leaving the old period running alongside
 * a second one.
 */
fun scheduleAutoBackup(
    context: Context,
    enabled: Boolean,
    intervalValue: Int,
    intervalUnit: BackupIntervalUnit,
) {
    val workManager = WorkManager.getInstance(context)
    if (!enabled) {
        workManager.cancelUniqueWork(AUTO_BACKUP_WORK_NAME)
        return
    }

    val periodDays = (intervalValue * intervalUnit.days).coerceAtLeast(1)
    val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(periodDays.toLong(), TimeUnit.DAYS)
        .setConstraints(
            Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()
        )
        .build()
    workManager.enqueueUniquePeriodicWork(AUTO_BACKUP_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
}
