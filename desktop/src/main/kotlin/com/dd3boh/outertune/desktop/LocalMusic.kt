/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jetbrains.skia.Image
import java.io.File
import java.security.MessageDigest
import java.util.logging.Level
import java.util.logging.Logger

/** One music file the library knows about. */
data class LocalTrack(
    val song: StoredSong,
    val file: File,
    val album: String,
    val durationMs: Long,
)

/**
 * Music from folders on this computer: scanning them, reading their tags, and finding their art and
 * lyrics - the phone's local library, for the desktop.
 *
 * A local song lives in the library like any other, under an id of its own ("local:" and a hash of
 * its path), so liking it, adding it to a playlist and counting its plays all work unchanged. What
 * only a local song has - where its file is - is kept beside it (see Database.localFiles).
 *
 * **Artists come from the tags, every one of them.** The multi-value ARTISTS field where the file
 * has one, otherwise ARTIST split on the separators taggers use between names - ";", " / ", and
 * "feat." / "ft." - but never on "&", "," or an unspaced "/", which are as often part of one name
 * ("Simon & Garfunkel", "Tyler, The Creator", "AC/DC") as between two.
 */
object LocalMusic {

    /** Extensions the decoders here can play. */
    val EXTENSIONS = setOf("mp3", "flac", "ogg", "oga", "m4a", "mp4", "aac", "wav", "aif", "aiff")

    private const val ID_PREFIX = "local:"
    private const val ART_PREFIX = "local-art:"

    /** True for a song id that points at a file rather than at YouTube. */
    fun isLocal(id: String) = id.startsWith(ID_PREFIX)

    /** The art URL for a local song, which ArtworkCache hands to [artwork]. */
    fun isLocalArt(url: String) = url.startsWith(ART_PREFIX)

    val scanning = MutableStateFlow(false)
    val status = MutableStateFlow<String?>(null)

    /** Where each local song's file is, filled from the library; read by the player and by art. */
    @Volatile
    private var paths: Map<String, LocalTrack> = emptyMap()

    /** Every local track, by title - what the Library's "On this computer" list shows. */
    val tracks = MutableStateFlow<List<LocalTrack>>(emptyList())

    init {
        // jaudiotagger reports every tag it reads at INFO, which is thousands of lines per scan.
        Logger.getLogger("org.jaudiotagger").level = Level.OFF
    }

    fun idFor(file: File): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(file.absolutePath.lowercase().toByteArray())
        return ID_PREFIX + digest.take(10).joinToString("") { "%02x".format(it) }
    }

    fun track(id: String): LocalTrack? = paths[id]

    /**
     * Files removed from the library (Settings.localRemoved), lower-cased: a scan passes over them.
     * Set from the settings at start and whenever the list changes.
     */
    @Volatile
    var removed: Set<String> = emptySet()

    fun removedFrom(stored: String): Set<String> = stored.lines().filter { it.isNotBlank() }.map { it.lowercase() }.toSet()

    /**
     * Takes the file behind [id] out of the library - not off the disk - and returns its path for
     * the settings to keep, or null when it is not a known local file. Out of every list at once;
     * kept out of later scans by [removed].
     */
    fun remove(id: String, library: LibraryStore): String? {
        val path = paths[id]?.file?.absolutePath ?: return null
        removed = removed + path.lowercase()
        library.removeLocalFile(path)
        load(library)
        return path
    }

    /** Loads what the library already knows, so local songs play without a rescan. */
    fun load(library: LibraryStore) {
        val all = library.localTracks()
        paths = all.associateBy { it.song.id }
        tracks.value = all
    }

    /**
     * Walks [folders] and brings every music file into the library, reading tags only for files
     * that are new or changed since the last scan. Files that have gone are dropped from the folder
     * list; the songs stay (they may be liked, or in playlists) but no longer play.
     */
    suspend fun scan(folders: List<File>, library: LibraryStore) = withContext(Dispatchers.IO) {
        if (scanning.value) return@withContext
        scanning.value = true
        try {
            val known = library.localTracks().associateBy { it.file.absolutePath }
            val modified = library.localModifiedTimes()
            val found = mutableListOf<File>()
            folders.filter { it.isDirectory }.forEach { root ->
                root.walkTopDown()
                    .onEnter { !it.name.startsWith(".") }
                    .filter { it.isFile && it.extension.lowercase() in EXTENSIONS }
                    // Removed from the library by hand: still on disk, not in the library.
                    .filter { it.absolutePath.lowercase() !in removed }
                    .forEach { found += it }
            }
            var added = 0
            var updated = 0
            // Written in batches, each one transaction: a commit per file is a disk flush per file,
            // which is what made the phone-backup import take most of an hour.
            val batch = mutableListOf<Pair<LocalTrack, Long>>()
            fun flush() {
                if (batch.isEmpty()) return
                library.putLocalTracks(batch.toList())
                batch.clear()
            }
            found.forEachIndexed { index, file ->
                currentCoroutineContext().ensureActive()
                if (index % 25 == 0) status.value = "Scanning ${index + 1} of ${found.size}…"
                val path = file.absolutePath
                if (modified[path] == file.lastModified() && path in known) return@forEachIndexed
                val track = readTrack(file) ?: return@forEachIndexed
                batch += track to file.lastModified()
                if (path in known) updated++ else added++
                if (batch.size >= BATCH) flush()
            }
            flush()
            val present = found.map { it.absolutePath }.toSet()
            val removed = library.removeLocalFilesNotIn(present)
            load(library)
            status.value = "${found.size} ${if (found.size == 1) "song" else "songs"} on this computer" +
                listOfNotNull(
                    "$added new".takeIf { added > 0 },
                    "$updated updated".takeIf { updated > 0 },
                    "$removed gone".takeIf { removed > 0 },
                ).joinToString(", ").let { if (it.isNotEmpty()) " ($it)" else "" }
        } finally {
            scanning.value = false
            // Then, in the background, the artists found are matched to YouTube Music - see
            // ArtistLinks. Names already known cost nothing, so a rescan only searches new ones.
            linking.launch { runCatching { ArtistLinks.enrichLocal(library) } }
        }
    }

    private val linking = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)


    /** A file's tags as a track, or null if the file cannot be read at all. */
    fun readTrack(file: File): LocalTrack? = runCatching {
        val audio = AudioFileIO.read(file)
        val tag = audio.tag
        val title = tag?.getFirst(FieldKey.TITLE)?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension
        val names = artistsFrom(
            multi = tag?.getAll(FieldKey.ARTISTS).orEmpty(),
            single = tag?.getAll(FieldKey.ARTIST).orEmpty(),
            albumArtist = tag?.getFirst(FieldKey.ALBUM_ARTIST).orEmpty(),
        )
        val artists = names.map { StoredArtist.unlinked(it) }
        val id = idFor(file)
        LocalTrack(
            song = StoredSong(
                id = id,
                title = title,
                artists = names.joinToString(", ").ifBlank { "Unknown artist" },
                thumbnail = ART_PREFIX + id,
                artistList = artists,
            ),
            file = file,
            album = tag?.getFirst(FieldKey.ALBUM).orEmpty(),
            durationMs = (audio.audioHeader.preciseTrackLength * 1000).toLong(),
        )
    }.getOrNull()

    /**
     * Every credited artist, in order, without repeats - see the class doc for which separators
     * split a name and which do not.
     */
    internal fun artistsFrom(multi: List<String>, single: List<String>, albumArtist: String): List<String> {
        val raw = multi.filter { it.isNotBlank() }.ifEmpty { single.filter { it.isNotBlank() } }
            .ifEmpty { listOf(albumArtist).filter { it.isNotBlank() } }
        return raw.flatMap { value ->
            value.split(SEPARATORS).map { it.trim() }.filter { it.isNotEmpty() }
        }.distinctBy { it.lowercase() }
    }

    private const val BATCH = 200

    // "/" only with a space either side: bare, it is part of too many names - "AC/DC", "Au/Ra".
    private val SEPARATORS = Regex("\\s*(?:;|\\u0000|\\s/\\s|\\s+feat\\.?\\s+|\\s+ft\\.?\\s+|\\s+featuring\\s+)\\s*", RegexOption.IGNORE_CASE)

    /**
     * A local song's cover: the art embedded in the file, or failing that the image sitting beside
     * it - cover, folder or front, in the formats people actually save.
     */
    fun artwork(url: String): ImageBitmap? {
        val track = paths[url.removePrefix(ART_PREFIX).substringBefore('=')] ?: return null
        val bytes = runCatching { AudioFileIO.read(track.file).tag?.firstArtwork?.binaryData }.getOrNull()
            ?: track.file.parentFile?.let(::folderImage)?.readBytes()
            ?: return null
        return runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
    }

    private fun folderImage(dir: File): File? {
        val names = listOf("cover", "folder", "front", "album", "albumart")
        return dir.listFiles()?.firstOrNull { f ->
            f.isFile && f.nameWithoutExtension.lowercase() in names && f.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp")
        }
    }

    /**
     * Lyrics that came with the file: an .lrc beside it with the same name, or lyrics embedded in
     * its tags. Null when there are none, and the online providers are asked as for any song.
     */
    fun bundledLyrics(id: String): String? {
        val track = paths[id] ?: return null
        val lrc = File(track.file.parentFile, track.file.nameWithoutExtension + ".lrc")
        if (lrc.isFile) return runCatching { lrc.readText() }.getOrNull()
        return runCatching { AudioFileIO.read(track.file).tag?.getFirst(FieldKey.LYRICS) }
            .getOrNull()?.takeIf { it.isNotBlank() }
    }
}
