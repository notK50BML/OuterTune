package com.dd3boh.outertune.desktop

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Imports a real backup into a throwaway library, when OT_BACKUP names one. */
class BackupProbe {
    @Test
    fun importRealBackup() = runBlocking {
        val path = System.getenv("OT_BACKUP")
        assumeTrue(path != null)
        val library = LibraryStore(Files.createTempDirectory("backup-probe").toFile())
        val summary = BackupImport.importFrom(File(path!!), library)
        println("PROBE summary: $summary")
        println("PROBE describe: ${summary.describe()}")
        println("PROBE liked now: ${library.liked.value.size}, playlists: ${library.playlists.value.size}")
        library.close()
    }
}
