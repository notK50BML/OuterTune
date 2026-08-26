package com.dd3boh.outertune.constants

/** Default values and bounds for automatic backup. */
object AutoBackupDefaults {
    const val ENABLED = false

    /** How often to write a new automatic backup, in days. */
    const val INTERVAL_DAYS = 1
    val INTERVAL_DAYS_RANGE = 1..30
}
