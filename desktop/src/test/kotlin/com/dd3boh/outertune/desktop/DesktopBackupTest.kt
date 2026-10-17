package com.dd3boh.outertune.desktop

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class DesktopBackupTest {
    @Test
    fun `a backup restores the library it was made from`() = runBlocking {
        val home = Files.createTempDirectory("backup-home").toFile()
        val library = LibraryStore(home)
        library.toggleLiked(StoredSong("a", "Song A", "Artist"))
        library.createPlaylist("Mix")
        val backup = DesktopBackup.export(library, Files.createTempDirectory("backup-out").toFile())
        assertTrue(DesktopBackup.isDesktopBackup(backup))

        // A different library, then the restore staged into it and applied as at a restart.
        library.toggleLiked(StoredSong("a", "Song A", "Artist"))
        library.close()
        DesktopBackup.stageRestore(backup, home)
        val restored = LibraryStore(home)
        assertEquals(listOf("a"), restored.liked.value.map { it.id })
        assertEquals(listOf("Mix"), restored.playlists.value.map { it.name })
        assertTrue(java.io.File(home, "library-before-restore.db").isFile)
        restored.close()
    }
}
