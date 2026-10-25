package com.dd3boh.outertune.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** What the updater will and will not take from GitHub's releases list. */
class DesktopUpdaterTest {

    private val current = listOf(0, 3, 0)

    private fun asset(name: String, url: String = "https://github.com/notK50BML/OuterTune/releases/download/x/$name", size: Long = 1000, digest: String? = null) =
        """{"name":"$name","browser_download_url":"$url","size":$size${digest?.let { ",\"digest\":\"$it\"" } ?: ""}}"""

    private fun release(name: String, vararg assets: String, draft: Boolean = false, prerelease: Boolean = false) =
        """{"name":"$name","tag_name":"$name","draft":$draft,"prerelease":$prerelease,"assets":[${assets.joinToString(",")}]}"""

    private fun list(vararg releases: String) = "[${releases.joinToString(",")}]"

    @Test
    fun `a newer installer is found`() {
        val update = DesktopUpdater.pickUpdate(list(release("v0.4b", asset("OuterTune-setup-0.4.0.exe", digest = "sha256:ABC"))), current)!!
        assertEquals("0.4b", update.name)
        assertEquals(listOf(0, 4, 0), update.version)
        assertEquals("abc", update.sha256)
    }

    @Test
    fun `only the installer is taken - never an apk, a zip or anything else`() {
        val json = list(
            release(
                "v0.9",
                asset("OuterTune-0.9.0-full-universal-99.apk"),
                asset("OuterTune-windows.zip"),
                asset("OuterTune-setup-0.9.0.exe.zip"),
                asset("OuterTune-setup-0.9.0.msi"),
                asset("evil-OuterTune-setup-0.9.0.exe"),
            ),
        )
        assertNull(DesktopUpdater.pickUpdate(json, current))
    }

    @Test
    fun `an asset hosted anywhere but GitHub is refused`() {
        val json = list(release("v0.4", asset("OuterTune-setup-0.4.0.exe", url = "https://example.com/OuterTune-setup-0.4.0.exe")))
        assertNull(DesktopUpdater.pickUpdate(json, current))
    }

    @Test
    fun `drafts, prereleases and older versions are passed over`() {
        val json = list(
            release("v0.5", asset("OuterTune-setup-0.5.0.exe"), draft = true),
            release("v0.6", asset("OuterTune-setup-0.6.0.exe"), prerelease = true),
            release("v0.2", asset("OuterTune-setup-0.2.9.exe")),
            release("v0.3", asset("OuterTune-setup-0.3.0.exe")),
        )
        assertNull(DesktopUpdater.pickUpdate(json, current))
    }

    @Test
    fun `the newest of several is chosen`() {
        val json = list(
            release("v0.4", asset("OuterTune-setup-0.4.0.exe")),
            release("v0.10", asset("OuterTune-setup-0.10.0.exe")),
            release("v0.9", asset("OuterTune-setup-0.9.1.exe")),
        )
        assertEquals(listOf(0, 10, 0), DesktopUpdater.pickUpdate(json, current)!!.version)
    }

    @Test
    fun `only a file that starts like a Windows program counts as one`() {
        val exe = Files.createTempFile("ot", ".exe").toFile().apply { writeBytes(byteArrayOf('M'.code.toByte(), 'Z'.code.toByte(), 0, 0)) }
        val html = Files.createTempFile("ot", ".exe").toFile().apply { writeText("<html>not found</html>") }
        assertTrue(DesktopUpdater.isWindowsExecutable(exe))
        assertFalse(DesktopUpdater.isWindowsExecutable(html))
        exe.delete(); html.delete()
    }
}
