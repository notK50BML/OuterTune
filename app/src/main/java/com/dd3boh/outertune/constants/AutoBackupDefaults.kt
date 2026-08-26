package com.dd3boh.outertune.constants

/** A unit [AutoBackupIntervalValueKey]/[AutoBackupKeepCountKey]-style counts count in. */
enum class BackupIntervalUnit(val days: Int) {
    DAYS(1),
    WEEKS(7),

    /** Not a real calendar month - just a fixed 30 days, which is all a background interval like this needs. */
    MONTHS(30),
}

/** Default values and bounds for automatic backup. */
object AutoBackupDefaults {
    const val ENABLED = false

    val INTERVAL_UNIT = BackupIntervalUnit.WEEKS
    const val INTERVAL_VALUE = 1
    val INTERVAL_VALUE_RANGE = 1..52

    /** How many automatic backups to keep before deleting the oldest. */
    const val KEEP_COUNT = 8
    val KEEP_COUNT_RANGE = 1..52
}
