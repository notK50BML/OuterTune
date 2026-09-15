package com.dd3boh.outertune.utils

/**
 * Fits text to what Discord will actually accept in a presence.
 *
 * Discord validates every activity string and rejects the *whole* presence when one is out of
 * range - it does not trim for you and it does not tell the user. So a track whose title is one
 * character, or one of those titles that carries three features and a remix credit, produces no card
 * at all and looks exactly like rich presence being broken.
 *
 * The limits are Discord's own: two to a hundred and twenty-eight characters for `details`, `state`,
 * `large_text` and `small_text`, and thirty-two for a button label.
 */
internal object RpcText {

    const val MIN = 2
    const val MAX = 128
    const val BUTTON_MAX = 32

    /**
     * [value] trimmed into range, or [fallback] when there is nothing usable.
     *
     * A string that is too short is padded rather than replaced. A song really is called "4", and
     * showing its name with an invisible space after it is truer than substituting something else.
     */
    fun fit(value: String?, fallback: String? = null): String? {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isEmpty()) return fallback?.let { fit(it) }
        if (trimmed.length in MIN..MAX) return trimmed
        if (trimmed.length < MIN) return trimmed.padEnd(MIN, ' ')
        return ellipsise(trimmed)
    }

    /** A button label, truncated without an ellipsis - a label is a name, not a sentence. */
    fun fitButton(label: String): String {
        val trimmed = label.trim()
        return if (trimmed.length <= BUTTON_MAX) trimmed else trimmed.take(safeCut(trimmed, BUTTON_MAX)).trim()
    }

    /**
     * Cut to [MAX], ending with an ellipsis.
     *
     * Cut at a space where one is close to the end, so a title does not break mid-word. Only when it
     * is close: falling back to a word boundary a third of the way through would throw away more of
     * the title than it saves.
     */
    private fun ellipsise(value: String): String {
        val limit = safeCut(value, MAX - 1)
        val lastSpace = value.lastIndexOf(' ', limit - 1)
        val cut = if (lastSpace >= limit - 16 && lastSpace > MIN) lastSpace else limit
        return value.take(cut).trimEnd() + "…"
    }

    /**
     * The largest cut at or below [limit] that does not split a character in two.
     *
     * Kotlin's length counts UTF-16 units, so an emoji or any character outside the basic plane is
     * two of them. Cutting between the halves leaves a lone surrogate, which is not a character at
     * all - Discord rejects the string outright, which is the failure this whole file exists to
     * avoid, and it would happen only for the handful of titles that end in one.
     */
    private fun safeCut(value: String, limit: Int): Int {
        if (limit >= value.length) return value.length
        return if (Character.isHighSurrogate(value[limit - 1])) limit - 1 else limit
    }
}
