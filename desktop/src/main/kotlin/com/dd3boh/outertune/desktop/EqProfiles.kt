/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

/**
 * A named, savable curve - what a preset chip loads and what Save writes back to.
 *
 * Without this a preset was only a hardcoded set of gains, which made a reasonable-looking sequence
 * of actions lose work: tap "Rock", nudge a band, tap "Rock" again, and the nudge was gone. That is
 * the intended behaviour of a *constant* and indistinguishable from a bug. Every preset name now
 * doubles as a profile name - [factoryDefault] supplies the built-in curve, and saving writes an
 * override that wins over it from then on. A name someone types has no factory curve at all, only a
 * saved one.
 *
 * The desktop had no equaliser persistence whatsoever before this: closing the window discarded the
 * switch, the curve and any AutoEQ correction that had been fetched for it.
 */
data class EqProfile(val name: String, val bands: List<EqBand>)

/**
 * Where the equaliser's state is kept between runs.
 *
 * An interface, so [EqualizerPanel] stays testable and previewable without a settings file, and so
 * that a panel handed no store behaves exactly as the panel did before there was one.
 */
interface EqStore {
    var enabled: Boolean
    var bands: List<EqBand>
    /** The profile whose name is highlighted, or null when the curve is not any saved profile's. */
    var activeProfile: String?
    var profiles: List<EqProfile>
    /** The compressor's own dials. Null until something has been changed. */
    var compressor: CompressorPrefs?
}

/**
 * The compressor's five dials and its switch.
 *
 * Kept beside the equaliser's state because they are lost together - they share a drawer, and
 * closing the window discarded both. Deliberately *not* folded into [EqProfile]: on this layout the
 * compressor is its own section with its own meter, and a preset chip silently changing how hard
 * the track is compressed would be a surprise from a control that appears to be about tone. (The
 * phone does fold it in, where the two share a card.)
 *
 * Not validated here. Every one of these is coerced into range by [Compressor] on the way in, so a
 * value out of a corrupt file lands as the nearest legal one rather than as a broken filter - which
 * is why this can be a plain snapshot while a band cannot.
 */
data class CompressorPrefs(
    val enabled: Boolean,
    val thresholdDb: Float,
    val ratio: Float,
    val attackMs: Float,
    val releaseMs: Float,
    val makeupGainDb: Float,
) {
    fun applyTo(compressor: Compressor) {
        compressor.thresholdDb = thresholdDb
        compressor.ratio = ratio
        compressor.attackMs = attackMs
        compressor.releaseMs = releaseMs
        compressor.makeupGainDb = makeupGainDb
        compressor.enabled = enabled
    }

    companion object {
        fun of(compressor: Compressor) = CompressorPrefs(
            enabled = compressor.enabled,
            thresholdDb = compressor.thresholdDb,
            ratio = compressor.ratio,
            attackMs = compressor.attackMs,
            releaseMs = compressor.releaseMs,
            makeupGainDb = compressor.makeupGainDb,
        )
    }
}

/**
 * Puts a stored curve back into the filter bank.
 *
 * Called once at startup rather than when the equaliser panel first opens, which is the whole
 * difference between a curve that is in effect from the first note and one that only appears if
 * someone happens to go looking for it. The panel is a view of this state, not where it lives.
 */
fun EqStore.applyTo(equalizer: Equalizer, compressor: Compressor? = null) {
    equalizer.setBands(bands)
    equalizer.enabled = enabled
    // Only when something was actually stored. Applying a default snapshot would be the same as
    // applying nothing, except that it would also switch the compressor on for anyone who had
    // never touched it.
    compressor?.let { this.compressor?.applyTo(it) }
}

/**
 * Reading and writing curves as text.
 *
 * The settings store holds strings, so these have to survive a round trip through one. The format
 * is this app's own rather than JSON: there is no JSON in the desktop module's own code, the shape
 * is two levels deep, and a codec small enough to read in one sitting is easier to be confident
 * about than a dependency.
 *
 * Nothing here throws. A value that will not parse is dropped and the default stands, for the same
 * reason [Settings] falls back rather than failing: these files are written by this app alone, so a
 * bad one means something already went wrong, and refusing to start because a stored curve is
 * malformed would turn a lost preference into a broken install.
 */
object EqProfiles {

    private const val VERSION = "v1"

    /** The built-in preset's own curve, or null for a name someone typed. */
    fun factoryDefault(name: String): List<EqBand>? {
        val gains = Equalizer.PRESETS[name] ?: return null
        return Equalizer.DEFAULT_BANDS.mapIndexed { i, band -> band.copy(gainDb = gains.getOrElse(i) { 0f }) }
    }

    // ---- one curve ---------------------------------------------------------------------------

    /**
     * Fields rather than twelve gains, because a curve is not always twelve gains. An AutoEQ
     * correction arrives with its own frequencies, its own Q per band and a shelf at each end, and
     * storing only the gains would quietly turn the correction someone fetched into a set of
     * numbers applied to the wrong frequencies.
     */
    fun encodeBands(bands: List<EqBand>): String =
        bands.joinToString(";") { "${it.freqHz},${it.gainDb},${it.q},${it.type.name}" }

    fun decodeBands(text: String): List<EqBand>? {
        if (text.isBlank()) return null
        val bands = text.split(";").map { record ->
            val parts = record.split(",")
            if (parts.size != 4) return null
            val freq = parts[0].toFloatOrNull() ?: return null
            val gain = parts[1].toFloatOrNull() ?: return null
            val q = parts[2].toFloatOrNull() ?: return null
            val type = EqBandType.entries.firstOrNull { it.name == parts[3] } ?: return null
            // A band the filter bank cannot build is worse than no saved curve: setPeaking divides
            // by the sample rate and by Q, and a zero or a NaN from a corrupt file would reach the
            // audio thread as a filter that outputs silence or noise.
            if (!freq.isFinite() || !gain.isFinite() || !q.isFinite()) return null
            if (freq <= 0f || q <= 0f) return null
            EqBand(freqHz = freq, gainDb = gain, q = q, type = type)
        }
        return bands.ifEmpty { null }
    }

    // ---- the compressor ----------------------------------------------------------------------

    fun encodeCompressor(prefs: CompressorPrefs): String = listOf(
        prefs.enabled.toString(),
        prefs.thresholdDb.toString(),
        prefs.ratio.toString(),
        prefs.attackMs.toString(),
        prefs.releaseMs.toString(),
        prefs.makeupGainDb.toString(),
    ).joinToString(",")

    fun decodeCompressor(text: String): CompressorPrefs? {
        val parts = text.split(",")
        if (parts.size != 6) return null
        val enabled = parts[0].toBooleanStrictOrNull() ?: return null
        val numbers = parts.drop(1).map { it.toFloatOrNull() ?: return null }
        // Range is Compressor's business - it coerces every one of these on the way in. Finiteness
        // is not: a NaN coerces to NaN, and there is no sensible nearest legal value for one.
        if (numbers.any { !it.isFinite() }) return null
        return CompressorPrefs(
            enabled = enabled,
            thresholdDb = numbers[0],
            ratio = numbers[1],
            attackMs = numbers[2],
            releaseMs = numbers[3],
            makeupGainDb = numbers[4],
        )
    }

    // ---- the saved list ----------------------------------------------------------------------

    fun encode(profiles: List<EqProfile>): String =
        (listOf(VERSION) + profiles.map { "${escape(it.name)}\t${encodeBands(it.bands)}" })
            .joinToString("\n")

    fun decode(text: String): List<EqProfile> {
        if (text.isBlank()) return emptyList()
        val lines = text.split("\n")
        // An unknown version means a newer build wrote this. Its curves are not readable here and
        // guessing at them would silently apply the wrong ones, so the saved list reads as empty -
        // which loses the overrides but not the file, since nothing rewrites it until a Save.
        if (lines.firstOrNull() != VERSION) return emptyList()
        return lines.drop(1).mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            val tab = line.indexOf('\t')
            if (tab <= 0) return@mapNotNull null
            val name = unescape(line.substring(0, tab)).takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val bands = decodeBands(line.substring(tab + 1)) ?: return@mapNotNull null
            EqProfile(name, bands)
        }
    }

    /**
     * Names are typed by the user, and the record separators are a tab and a newline.
     *
     * Escaped rather than rejected at the point of entry. A name is a label; refusing one because of
     * the character the storage format happens to use would be the format's problem showing through
     * to someone who cannot see it.
     */
    private fun escape(name: String): String = name
        .replace("\\", "\\\\")
        .replace("\t", "\\t")
        .replace("\n", "\\n")
        .replace("\r", "\\r")

    private fun unescape(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c != '\\' || i == text.lastIndex) {
                out.append(c)
                i++
                continue
            }
            when (val next = text[i + 1]) {
                't' -> out.append('\t')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                '\\' -> out.append('\\')
                else -> out.append(next)
            }
            i += 2
        }
        return out.toString()
    }
}
