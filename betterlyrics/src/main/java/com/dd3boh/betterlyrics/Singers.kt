package com.dd3boh.betterlyrics

/*
 * Who sings which line - shared by both apps so a duet reads the same on each.
 *
 * Here rather than in either app because both need exactly the same answers: which voice a TTML
 * agent is, which "Name: " at the start of a line is a singer's name rather than lyrics, and what
 * colour each voice is drawn in. Two copies would drift, and a duet would be coloured one way on
 * the phone and another on the desktop.
 */

/**
 * The "Singer names in lyrics" setting, the same three choices on both apps.
 *
 * Only changes how lyrics are drawn. What is stored is the provider's own text, names and all, so
 * switching between these never needs the lyrics fetched again.
 */
enum class SingerNames {
    /** No name text; each singer's lines in a colour of their own, with a small legend of who is who. */
    COLOUR,

    /** No name text and no colours: plain lyrics. */
    HIDE,

    /** The lines exactly as the provider wrote them, "Name: " and all. */
    TEXT,
}

/**
 * Which singer a line belongs to, as far as colouring it goes.
 *
 * Three, because that is what the phone's own TTML reader distinguishes (its Voice1, Voice2 and
 * Group): a third person in a trio shares the first voice's colour there, so they share it here too.
 */
enum class Voice { ONE, TWO, GROUP }

/** A named singer and the voice their lines are drawn in - one name in the legend. */
data class Singer(val voice: Voice, val name: String)

object Singers {

    /** A `<ttm:agent>`: its `xml:id`, its `type` (person, group, ...) and its `<ttm:name>`, if it has one. */
    data class Agent(val id: String, val type: String, val name: String?)

    /**
     * Each agent's voice, by the phone's own rule - kept identical so that a document read by the
     * phone's strict TTML reader and one read by [TTMLParser] colour the same singer the same way.
     *
     * A group is [Voice.GROUP]. Everyone else alternates through the agents of their own type in
     * the order the head declares them: the first is [Voice.ONE], the second [Voice.TWO], the third
     * [Voice.ONE] again.
     */
    fun voices(agents: List<Agent>): Map<String, Voice> {
        val byType = agents.groupBy { it.type }.mapValues { (_, list) -> list.map { it.id } }
        return agents.associate { agent ->
            agent.id to when {
                agent.type == "group" -> Voice.GROUP
                byType.getValue(agent.type).indexOf(agent.id) % 2 == 1 -> Voice.TWO
                else -> Voice.ONE
            }
        }
    }

    /** Whether lines in [voices] are sung by more than one voice - the only time colouring them says anything. */
    fun isMultiVoice(voices: Iterable<Voice?>): Boolean = voices.filterNotNull().distinct().size > 1

    /**
     * The legend, one entry per voice in voice order: the voice and its singers' names. Names that
     * share a voice - a trio's first and third singer - are joined with " & " under the one colour.
     */
    fun legend(singers: List<Singer>): List<Pair<Voice, String>> =
        Voice.entries.mapNotNull { voice ->
            singers.filter { it.voice == voice }.map { it.name }.distinct()
                .takeIf { it.isNotEmpty() }
                ?.let { voice to it.joinToString(" & ") }
        }

    // region Names written into the lyrics

    /**
     * A name and its colon at the very start of a line: "Latto: Uh, yeah", "男：我爱你", or a line
     * that is nothing but "Jung Kook:". An ASCII colon must be followed by a space or the end of the
     * line, which keeps "3:45" and "http://" out; the full-width colon CJK lyrics use needs nothing
     * after it.
     */
    private val NAME_PREFIX = Regex("""^\s*([^:：\n]{1,40}?)\s*(?::(?=\s|$)|：)\s*""")

    /** Characters a singer's name does not contain, but a sentence often does. */
    private const val NOT_IN_NAMES = "\"“”!?;()[]<>…*#"

    /** How long a name may be - a duet's "Jung Kook & Latto" fits; a clause does not. */
    private const val MAX_NAME_LENGTH = 30
    private const val MAX_NAME_WORDS = 4

    /** Lower-case words a name may still contain: "Jung Kook x Latto", "Bad Bunny y Jhay Cortez". */
    private val JOINERS = setOf("and", "x", "y", "e", "et", "und", "feat.", "ft.", "with", "the", "of", "de", "da", "la", "le", "van", "von")

    /**
     * What comes before a colon at the start of a line but is not anyone's name: section labels,
     * credits - in English and in the Chinese, Japanese and Korean that QQ Music writes them in -
     * and the few words a lyric itself is likely to open with and follow with a colon.
     */
    private val NOT_NAMES = setOf(
        "break", "breakdown", "drop", "instrumental", "spoken", "ad-lib", "ad-libs",
        "adlib", "adlibs", "note", "notes", "translation", "lyrics", "music", "composer", "composed",
        "producer", "produced", "written", "writer", "writers", "arranged", "arranger", "mixed", "mastered",
        "words", "vocals", "vocal", "title", "artist", "album", "source", "repeat", "remember", "listen",
        "look", "wait", "stop", "oh", "ooh", "yeah", "hey", "baby", "no", "yes", "now", "and", "but",
        "词", "曲", "作词", "作曲", "编曲", "词曲", "制作", "制作人", "监制", "混音", "母带", "录音", "和声",
        "吉他", "贝斯", "鼓", "弦乐", "键盘", "统筹", "策划", "出品", "发行", "演唱", "原唱", "翻唱", "歌手",
        "作詞", "編曲", "製作", "監製", "歌詞", "작사", "작곡", "편곡",
    )

    /** Song sections, which are labelled with a number as often as not: "Verse 2", "Chorus x2". */
    private val SECTIONS = setOf(
        "chorus", "verse", "hook", "bridge", "intro", "outro", "pre-chorus", "prechorus", "post-chorus",
        "refrain", "interlude", "part", "section",
    )

    /** Names that stand for everyone singing together, rather than for one singer. */
    private val GROUP_NAMES = setOf(
        "all", "both", "everyone", "everybody", "together", "group", "choir", "tutti",
        "合", "合唱", "全员", "全員", "全体", "모두", "다같이", "함께",
    )

    /** Joins inside a name that make it two singers at once: "Jung Kook & Latto". */
    private val TOGETHER = Regex("""\s(?:&|and|x)\s|/""", RegexOption.IGNORE_CASE)

    /** Whether [name], a singer's name written into the lyrics, means more than one person singing. */
    fun isGroupName(name: String): Boolean = name.lowercase() in GROUP_NAMES || TOGETHER.containsMatchIn(name)

    /**
     * Whether [name] - whatever came before a line's leading colon - is shaped like a singer's name.
     *
     * Deliberately strict, because getting it wrong eats the start of a lyric: short, no sentence
     * punctuation, some letter in it, and every word capitalised apart from a joiner such as "and"
     * or "x" - "Listen to me: ..." is a sentence, "Jung Kook: ..." is a name. Scripts without case
     * (Chinese, Japanese, Korean) pass that last test on their own.
     */
    internal fun isNameShaped(name: String): Boolean {
        if (name.isEmpty() || name.length > MAX_NAME_LENGTH) return false
        if (name.any { it in NOT_IN_NAMES }) return false
        if (!name.first().isLetterOrDigit() || name.none { it.isLetter() }) return false
        val words = name.split(Regex("""\s+""")).filter { it.isNotEmpty() }
        if (words.size > MAX_NAME_WORDS) return false
        for (word in words) {
            val first = word.first()
            if (first.isLetter() && first.isLowerCase() && word.lowercase() !in JOINERS) return false
        }
        val key = name.lowercase()
        if (key in NOT_NAMES || key in SECTIONS) return false
        // "Verse 2", "Chorus x2": a label with a number after it is still a label.
        if (words.first().lowercase().trimEnd(',', '.') in SECTIONS) return false
        if (words.any { it.lowercase() == "by" }) return false // "Lyrics By", "Produced By"
        return true
    }

    /** The name [text] opens with, if it opens with something name-shaped and a colon. */
    fun leadingName(text: String): String? =
        NAME_PREFIX.find(text)?.groupValues?.get(1)?.trim()?.takeIf(::isNameShaped)

    /**
     * The singers' names written at the start of lines in [texts] - the lines of one song - or
     * nothing when the song does not look labelled at all.
     *
     * One name-shaped prefix is not enough: a lyric can open "Mama: ..." once. A song counts as
     * labelled when at least two different names lead lines and at least one of them leads two
     * different lines - which is what a duet written out with names looks like, and what a chorus
     * repeated word for word ("Remember: ..." four times over) does not.
     */
    fun namePrefixes(texts: List<String>): Set<String> {
        val seen = LinkedHashMap<String, MutableSet<String>>()
        for (text in texts) {
            val match = NAME_PREFIX.find(text) ?: continue
            val name = match.groupValues[1].trim().takeIf(::isNameShaped) ?: continue
            seen.getOrPut(name) { mutableSetOf() } += text.substring(match.range.last + 1).trim()
        }
        if (seen.size < 2 || seen.values.none { it.size > 1 }) return emptySet()
        return seen.keys
    }

    /**
     * How many characters at the start of [text] are a singer's name and its colon, taking [names]
     * from [namePrefixes] - 0 when the line does not start with one of them.
     */
    fun namePrefixLength(text: String, names: Set<String>): Int {
        if (names.isEmpty()) return 0
        val match = NAME_PREFIX.find(text) ?: return 0
        return if (match.groupValues[1].trim() in names) match.range.last + 1 else 0
    }

    /**
     * Voices for lyrics whose singers are named only in the text, as "Name: " at the start of
     * lines - which is how QQ Music, and so BetterLyrics' QQ relay, writes a duet.
     *
     * Each line is given the voice of the last name seen, so a name written once at the head of a
     * verse holds for the whole verse. Lines before any name have none. Names get voices in the
     * order they first appear, by the same alternation [voices] uses for a TTML head; names that
     * stand for everyone ([isGroupName]) are the group.
     *
     * Null when the lines are not labelled ([namePrefixes] finds nothing); otherwise each line's
     * agent id (one per name, in [Agent] form so it fits beside a TTML head's) and the agents.
     */
    fun agentsFromNames(texts: List<String>): Pair<List<String?>, List<Agent>>? {
        val names = namePrefixes(texts)
        if (names.isEmpty()) return null
        val agents = LinkedHashMap<String, Agent>()
        var current: String? = null
        val perLine = texts.map { text ->
            leadingName(text)?.takeIf { it in names }?.let { name ->
                current = agents.getOrPut(name) {
                    val group = isGroupName(name)
                    // Ids in Apple's style - v1, v2 for people and v1000 up for groups - so nothing
                    // downstream can tell these apart from a real head's.
                    val id = if (group) "v${1000 + agents.values.count { it.type == "group" }}"
                    else "v${1 + agents.values.count { it.type != "group" }}"
                    Agent(id, if (group) "group" else "person", name)
                }.id
            }
            current
        }
        return perLine to agents.values.toList()
    }

    /**
     * [agentsFromNames] as each line's [Voice] and the named [Singer]s for the legend - for lyrics
     * that have nowhere to keep agents, such as LRC from KuGou, LRCLIB or QQ Music's line-timed
     * lyrics, which write a duet as "Name: " at the start of lines just as QQ's word-timed ones do.
     * TTML gets the same from [TTMLParser.parse]; this is the same reading for every other format.
     *
     * Null when the lines are not labelled.
     */
    fun voicesFromNames(texts: List<String>): Pair<List<Voice?>, List<Singer>>? {
        val (perLine, agents) = agentsFromNames(texts) ?: return null
        val voices = voices(agents)
        return perLine.map { id -> id?.let(voices::get) } to
            agents.mapNotNull { agent -> agent.name?.let { Singer(voices.getValue(agent.id), it) } }
    }

    // endregion
}

/**
 * The colour each voice is drawn in, worked out the same way on both apps.
 *
 * Colours are plain ARGB ints so this can be shared by modules with no UI of their own; each app
 * wraps them in its own colour type.
 *
 * Every voice colour starts from the theme's accents - the first voice from one, the second from
 * another, and the group from halfway round the colour wheel between the two - and is then moved
 * toward the colour ordinary lyrics are drawn in until it is as readable. That colour was already
 * chosen to stand out from whatever is behind the lyrics (the theme's surface, or the player's
 * album-art backdrop), so a voice colour no darker than it on a dark background, and no lighter on a
 * light one, stands out just as well, with its hue still telling the singers apart.
 */
object SingerColours {

    /** The colour of [voice]'s lines, from the accents [first] and [second], readable where [on] is. */
    fun forVoice(voice: Voice, first: Int, second: Int, on: Int): Int {
        val (a, b) = distinctPair(first, second)
        val accent = when (voice) {
            Voice.ONE -> a
            Voice.TWO -> b
            Voice.GROUP -> between(a, b)
        }
        return readable(accent, on)
    }

    /**
     * [first] and [second], or [first] and a turned [second] when the two are too alike in hue to
     * tell apart - a static theme's accents are sometimes shades of one colour.
     */
    internal fun distinctPair(first: Int, second: Int): Pair<Int, Int> {
        val a = hsl(first)
        val b = hsl(second)
        val apart = hueDistance(a[0], b[0])
        // Greys have no hue to tell apart by, so they are given one.
        if (apart >= MIN_HUE_APART && b[1] >= MIN_SATURATION) return first to second
        val saturation = maxOf(b[1], a[1], MIN_SATURATION)
        return first to fromHsl((a[0] + SECOND_VOICE_TURN) % 360f, saturation, b[2])
    }

    /** Halfway between [a] and [b] round the colour wheel - mixing them straight can land on grey. */
    internal fun between(a: Int, b: Int): Int {
        val x = hsl(a)
        val y = hsl(b)
        var delta = y[0] - x[0]
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        return fromHsl((x[0] + delta / 2f + 360f) % 360f, (x[1] + y[1]) / 2f, (x[2] + y[2]) / 2f)
    }

    /**
     * [accent], moved toward [on] just far enough to be as readable as it: on a light lyric colour
     * (a dark background) at least as light as [LIGHT_FLOOR] or [on] itself, whichever is less; on
     * a dark one no lighter than [DARK_CEILING] or [on]. Always opaque.
     */
    fun readable(accent: Int, on: Int): Int {
        val onLuminance = luminance(on)
        val light = onLuminance >= 0.35
        val floor = minOf(LIGHT_FLOOR, onLuminance)
        val ceiling = maxOf(DARK_CEILING, onLuminance)
        fun ok(colour: Int) = if (light) luminance(colour) >= floor else luminance(colour) <= ceiling
        val opaque = accent or OPAQUE
        if (ok(opaque)) return opaque
        var low = 0f
        var high = 1f
        repeat(18) {
            val mid = (low + high) / 2f
            if (ok(mix(opaque, on or OPAQUE, mid))) high = mid else low = mid
        }
        return mix(opaque, on or OPAQUE, high)
    }

    /** WCAG relative luminance, 0 for black to 1 for white. */
    fun luminance(argb: Int): Double {
        fun channel(c: Int): Double {
            val s = c / 255.0
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(argb shr 16 and 0xFF) + 0.7152 * channel(argb shr 8 and 0xFF) + 0.0722 * channel(argb and 0xFF)
    }

    /** WCAG contrast ratio between two colours, 1 to 21. */
    fun contrast(a: Int, b: Int): Double {
        val x = luminance(a)
        val y = luminance(b)
        return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        fun lerp(x: Int, y: Int) = (x + (y - x) * t).toInt().coerceIn(0, 255)
        return OPAQUE or
            (lerp(a shr 16 and 0xFF, b shr 16 and 0xFF) shl 16) or
            (lerp(a shr 8 and 0xFF, b shr 8 and 0xFF) shl 8) or
            lerp(a and 0xFF, b and 0xFF)
    }

    private fun hueDistance(a: Float, b: Float): Float {
        val d = Math.abs(a - b) % 360f
        return if (d > 180f) 360f - d else d
    }

    /** Hue in degrees, saturation and lightness 0 to 1. */
    private fun hsl(argb: Int): FloatArray {
        val r = (argb shr 16 and 0xFF) / 255f
        val g = (argb shr 8 and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val l = (max + min) / 2f
        if (max == min) return floatArrayOf(0f, 0f, l)
        val d = max - min
        val s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)
        val h = when (max) {
            r -> ((g - b) / d + (if (g < b) 6f else 0f))
            g -> (b - r) / d + 2f
            else -> (r - g) / d + 4f
        } * 60f
        return floatArrayOf(h, s, l)
    }

    private fun fromHsl(h: Float, s: Float, l: Float): Int {
        val c = (1f - Math.abs(2f * l - 1f)) * s
        val x = c * (1f - Math.abs((h / 60f) % 2f - 1f))
        val m = l - c / 2f
        val (r, g, b) = when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        fun channel(v: Float) = ((v + m) * 255f).toInt().coerceIn(0, 255)
        return OPAQUE or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
    }

    private const val OPAQUE = 0xFF shl 24

    /** The lightest a voice colour must be over a dark background - about a Material tone 70. */
    private const val LIGHT_FLOOR = 0.42

    /** The darkest-reading limit over a light background - about a Material tone 45. */
    private const val DARK_CEILING = 0.14

    /** Accents closer than this round the colour wheel read as one colour. */
    private const val MIN_HUE_APART = 40f

    /** Below this an accent is near enough grey that its hue says nothing. */
    private const val MIN_SATURATION = 0.25f

    /** How far round the wheel the second voice is turned when the theme's two accents are alike. */
    private const val SECOND_VOICE_TURN = 150f
}
