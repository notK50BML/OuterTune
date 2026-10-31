package com.dd3boh.qqmusic

import com.dd3boh.betterlyrics.QrcConverter
import com.dd3boh.qqmusic.models.LrcResponse
import com.dd3boh.qqmusic.models.SearchResponse
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import kotlin.io.encoding.Base64
import kotlin.math.abs

/**
 * QQ Music, asked directly: its desktop search finds the song, then `lyric_download.fcg` hands over
 * the word-timed QRC (encrypted - see [QrcDecrypter]), which is turned into TTML by [QrcConverter] so
 * the rest of the app draws it word by word exactly like a BetterLyrics result. When QQ has no QRC
 * for the song, or it will not open, its plain line-timed LRC is returned instead.
 *
 * None of these endpoints wants a cookie or a key; they do want a browser-shaped User-Agent and the
 * y.qq.com Referer. BetterLyrics' `/qq/getLyrics` relay used to do this for us, but it now demands
 * an API key.
 *
 * One search and one lyric download per song, plus at most the one LRC request when the QRC fails -
 * no retry loops.
 */
object QQMusic {
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private const val SEARCH_URL = "https://u.y.qq.com/cgi-bin/musicu.fcg"
    private const val QRC_URL = "https://c.y.qq.com/qqmusic/fcgi-bin/lyric_download.fcg"
    private const val LRC_URL = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg"

    private const val PAGE_SIZE = 10

    /** How far QQ's length may be from the track's and still count as the same recording, in seconds. */
    internal const val DURATION_TOLERANCE = 4

    /** Lyric downloads a manual search may make - each is a request to QQ, so only a handful. */
    private const val MAX_ALL_LYRICS = 3

    // QQ's stand-in for "this track has no words"; it is not lyrics.
    private const val INSTRUMENTAL = "此歌曲为没有填词的纯音乐"

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
    }

    private val client by lazy {
        HttpClient {
            // Non-2xx responses throw, so a refused request is a failure, never "no lyrics".
            expectSuccess = true

            install(ContentEncoding) {
                gzip()
                deflate()
            }

            // Without a timeout a stalled connection blocks the whole lyric fetch chain, so the
            // song ends up with no lyrics from any provider.
            install(HttpTimeout) {
                requestTimeoutMillis = 15000
                connectTimeoutMillis = 10000
                socketTimeoutMillis = 15000
            }

            defaultRequest {
                header(HttpHeaders.UserAgent, USER_AGENT)
                header(HttpHeaders.Referrer, "https://y.qq.com/")
            }
        }
    }

    /**
     * Look up lyrics. Returns success with the text when a match is found - TTML when QQ had word-timed
     * QRC for it, otherwise LRC - success with null when the search succeeded but nothing matched or the
     * song has no lyrics (a definitive absence), and a failure when a request itself failed.
     */
    suspend fun getLyrics(title: String, artist: String, duration: Int): Result<String?> = runCatching {
        checkNotResting()
        val song = rankCandidates(search(title, artist), title, artist, duration, strict = true).firstOrNull()
            ?: return@runCatching null
        lyricsFor(song, title)
    }.onFailure { if (it is CancellationException) throw it }

    /**
     * Every candidate's lyrics, best first, for a manual search - at most [MAX_ALL_LYRICS] downloads.
     * A candidate whose download fails is skipped; the failure is only thrown when none came through.
     */
    suspend fun getAllLyrics(title: String, artist: String, duration: Int, callback: (String) -> Unit) {
        checkNotResting()
        val candidates = rankCandidates(search(title, artist), title, artist, duration, strict = false)
            .take(MAX_ALL_LYRICS)
        var delivered = false
        var firstError: Exception? = null
        for (song in candidates) {
            try {
                lyricsFor(song, title)?.let {
                    callback(it)
                    delivered = true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (firstError == null) firstError = e
            }
        }
        if (!delivered) firstError?.let { throw it }
    }

    /**
     * Until when QQ is left alone after refusing a search. It turns a client away (code 2001) once
     * it has searched a lot in a short while, and asking again straight away only prolongs that - so
     * for a while every lookup fails at once, without a request, and the other providers answer.
     */
    @Volatile
    private var restingUntil = 0L

    /** How long QQ is left alone after a refusal. */
    private const val REST_AFTER_REFUSAL_MS = 15 * 60 * 1000L

    private fun checkNotResting() {
        if (System.currentTimeMillis() < restingUntil) throw IOException("QQ Music refused a search recently; not asking yet")
    }

    // ---- requests ----

    internal suspend fun search(title: String, artist: String): List<SearchResponse.Song> {
        val query = listOf(normalizeTitle(title).trim().ifEmpty { title }, artistParts(artist, keepCase = true).joinToString(" "))
            .filter { it.isNotBlank() }.joinToString(" ")
        val body = buildJsonObject {
            putJsonObject("comm") {
                put("ct", "19")
                put("cv", "1859")
                put("uin", "0")
            }
            putJsonObject("req") {
                put("method", "DoSearchForQQMusicDesktop")
                put("module", "music.search.SearchCgiService")
                putJsonObject("param") {
                    put("grp", 1)
                    put("num_per_page", PAGE_SIZE)
                    put("page_num", 1)
                    put("query", query)
                    put("search_type", 0)
                }
            }
        }
        val text = client.post(SEARCH_URL) {
            setBody(TextContent(body.toString(), ContentType.Application.Json))
        }.bodyAsText()
        val response = json.decodeFromString<SearchResponse>(text)
        // A non-zero code is QQ refusing the request (rate limit, changed API), not an empty result.
        if (response.code != 0 || response.req == null || response.req.code != 0) {
            restingUntil = System.currentTimeMillis() + REST_AFTER_REFUSAL_MS
            throw IOException("QQ Music search refused: code ${response.code}/${response.req?.code}")
        }
        return response.req.data?.body?.song?.list.orEmpty().flatMap { listOf(it) + it.grp }
    }

    /**
     * The song's lyrics: its QRC as TTML when there is one, otherwise the LRC. A QRC that fails to
     * download or open falls through to the LRC request; that one's failure is the song's failure.
     */
    internal suspend fun lyricsFor(song: SearchResponse.Song, title: String): String? {
        val qrc = try {
            downloadQrc(song.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (qrc != null) {
            if (INSTRUMENTAL in qrc) return null
            QrcConverter.toTtml(withoutTitleLine(qrc, listOf(title, song.name, song.title)), title)?.let { return it }
            // Songs with no word timing can come back as the plain LRC, encrypted the same way.
            if (!qrc.trimStart().startsWith("<") && LRC_LINE.containsMatchIn(qrc)) return qrc.trim()
        }
        return downloadLrc(song.mid)
    }

    /**
     * [qrc] without QQ's header line - the song's own name and singer, timed as the first line, e.g.
     * `等你下课(with 杨瑞代) -周杰伦 (Jay Chou)`. [QrcConverter] only spots it when it reads exactly
     * "<title> - ...", and QQ's spacing and bracketed extras often differ from the track's title, so
     * here it is matched loosely: the first timed line, starting with one of [titles], with a dash.
     */
    internal fun withoutTitleLine(qrc: String, titles: List<String>): String {
        val first = FIRST_TIMED_LINE.find(qrc) ?: return qrc
        val text = first.groupValues[1].replace(WORD_TIMING, "")
            .replace("&apos;", "'").replace("&quot;", "\"").replace("&amp;", "&")
        if ('-' !in text) return qrc
        val line = comparable(text)
        val isHeader = titles.any { title ->
            val core = comparable(normalizeTitle(title)).ifEmpty { comparable(title) }
            core.isNotEmpty() && line.startsWith(core)
        }
        if (!isHeader) return qrc
        val end = (first.range.last + 1).let { if (it < qrc.length && qrc[it] == '\n') it + 1 else it }
        return qrc.removeRange(first.range.first, end)
    }

    /** The decrypted QRC document, or null when QQ has none for [musicId]. */
    private suspend fun downloadQrc(musicId: Long): String? {
        val text = client.get(QRC_URL) {
            parameter("version", 15)
            parameter("miniversion", 82)
            parameter("lrctype", 4)
            parameter("musicid", musicId)
        }.bodyAsText()
        val hex = QRC_CONTENT.find(text)?.groupValues?.get(1)?.takeIf { it.isNotEmpty() } ?: return null
        return QrcDecrypter.decrypt(hex)
    }

    /** The line-timed LRC for [songMid], or null when QQ has none. */
    private suspend fun downloadLrc(songMid: String): String? {
        if (songMid.isEmpty()) return null
        val text = client.get(LRC_URL) {
            parameter("songmid", songMid)
            parameter("format", "json")
            parameter("nobase64", 0)
        }.bodyAsText()
        val response = json.decodeFromString<LrcResponse>(text)
        if (response.retcode != 0) return null
        val lrc = response.lyric?.let { Base64.Default.decode(it).decodeToString() }?.trim()
        return lrc?.takeIf { it.isNotEmpty() && INSTRUMENTAL !in it && LRC_LINE.containsMatchIn(it) }
    }

    // ---- matching ----

    /**
     * Search results that may be the track, best first. Each must be within [DURATION_TOLERANCE] of
     * [duration] (when it is known) and, when [strict], have a matching title - or, failing that, a
     * matching artist with the length to back it up (a title written in another script, say).
     * Matching titles come first, then matching artists, the original over a version ("Remix",
     * "Live"), the closest length, and finally QQ's own relevance order.
     */
    internal fun rankCandidates(
        songs: List<SearchResponse.Song>,
        title: String,
        artist: String,
        duration: Int,
        strict: Boolean,
    ): List<SearchResponse.Song> {
        val wantedTitle = comparable(title)
        return songs.asSequence()
            .distinctBy { it.id }
            .filter { duration <= 0 || abs(it.duration - duration) <= DURATION_TOLERANCE }
            .map { song ->
                val songTitle = song.title.ifEmpty { song.name }
                Match(
                    song = song,
                    title = titleMatches(title, songTitle) || titleMatches(title, song.name),
                    artist = artistMatches(artist, song.singer.map { it.name }),
                    exact = comparable(songTitle) == wantedTitle,
                    distance = if (duration > 0) abs(song.duration - duration) else 0,
                )
            }
            .filter { !strict || it.title || (it.artist && duration > 0) }
            .sortedWith(
                compareByDescending<Match> { it.title }
                    .thenByDescending { it.artist }
                    .thenByDescending { it.exact }
                    .thenBy { it.distance },
            )
            .map { it.song }
            .toList()
    }

    private class Match(
        val song: SearchResponse.Song,
        val title: Boolean,
        val artist: Boolean,
        val exact: Boolean,
        val distance: Int,
    )

    /**
     * Whether two titles name the same song once the bracketed extras ("(feat. Latto)", "(Live)"), a
     * trailing " - Explicit Ver." and an unbracketed "feat. ..." are gone. What is left must be the
     * same - "Golden" is not "Golden Hour".
     */
    internal fun titleMatches(query: String, candidate: String): Boolean {
        val a = coreTitle(query)
        return a.isNotEmpty() && a == coreTitle(candidate)
    }

    private fun coreTitle(title: String) =
        comparable(normalizeTitle(title).substringBefore(" - ").replace(TITLE_FEATURING, ""))

    /** Whether any of the track's artists is one of QQ's singers. */
    internal fun artistMatches(query: String, singers: List<String>): Boolean {
        val wanted = artistParts(query)
        val got = singers.flatMap { artistParts(it) }
        return wanted.any { w -> got.any { g -> w == g || (minOf(w.length, g.length) >= 2 && (w in g || g in w)) } }
    }

    /** The individual names in an artist string - "Jung Kook feat. Latto", "A, B & C", "周杰伦/杨瑞代". */
    private fun artistParts(artist: String, keepCase: Boolean = false): List<String> =
        normalizeArtist(artist).split('、', '/', ',', '&', '，')
            .flatMap { it.split(FEATURING) }
            .map { if (keepCase) it.trim() else comparable(it) }
            .filter { it.isNotEmpty() }

    /** Lower case, letters and digits only, so punctuation and spacing differences do not count. */
    private fun comparable(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    // normalizeTitle / normalizeArtist are copied from KuGou.kt (the :kugou module), so a title and
    // artist are cleaned up the same way for both Chinese providers without depending on it.
    private fun normalizeTitle(title: String) =
        title.replace("\\(.*\\)".toRegex(), "").replace("（.*）".toRegex(), "")
            .replace("「.*」".toRegex(), "").replace("『.*』".toRegex(), "")
            .replace("<.*>".toRegex(), "").replace("《.*》".toRegex(), "")
            .replace("〈.*〉".toRegex(), "").replace("＜.*＞".toRegex(), "")

    private fun normalizeArtist(artist: String) =
        artist.replace(", ", "、").replace(" & ", "、").replace(".", "").replace("和", "、")
            .replace("\\(.*\\)".toRegex(), "").replace("（.*）".toRegex(), "")

    private val FEATURING = Regex("""(?i)\s+(?:feat|ft|featuring|with|x)\s+""")
    private val TITLE_FEATURING = Regex("""(?i)\s+(?:feat\.?|ft\.?|featuring|with)\s.*$""")

    // The main lyric's <content> only - not <contentts> (translation) or <contentroma>.
    private val QRC_CONTENT = Regex("""<content\b[^>]*><!\[CDATA\[([0-9A-Fa-f]*)]]></content>""")

    private val LRC_LINE = Regex("""\[\d{1,3}:\d{2}[.:]\d{2,3}]""")

    private val FIRST_TIMED_LINE = Regex("""(?m)^\[\d+,\d+](.*)$""")
    private val WORD_TIMING = Regex("""\(\d+,\d+\)""")
}
