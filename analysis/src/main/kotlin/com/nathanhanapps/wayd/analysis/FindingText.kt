package com.nathanhanapps.wayd.analysis

/**
 * Findings store a language-neutral title: an English fallback sentence plus the raw arguments, separated by U+001F.
 * The UI renders it from string resources keyed by rule id (en + zh); the fallback is used for exports and unknown rules.
 */
object FindingText {
    const val SEP = '\u001F'

    fun of(fallback: String, vararg args: Any): String = (listOf(fallback) + args.map { it.toString() }).joinToString(SEP.toString())

    class Parsed(val fallback: String, val args: List<String>)

    fun parse(title: String): Parsed {
        val parts = title.split(SEP)
        return Parsed(parts.first(), parts.drop(1))
    }
}
