// SPDX-License-Identifier: AGPL-3.0-only
package com.gametranslator.gametranslator_mobile.core

/** A failed pack lookup can be an OCR reread of a subtitle already translated by that pack. */
object PackCaptionContinuity {
    private val label = Regex("""^([\p{L}][\p{L}\p{M} &.'’\-]{0,29}):\s+""")
    private val changesMeaning = Regex(
        """\b(?:\d+\w*|zero|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|left|right|north|south|east|west|yes|no|not|never|cannot|\w+n t)\b""",
    )

    fun isReread(reading: String, packedSource: String?): Boolean {
        if (packedSource.isNullOrBlank()) return false
        val a = label.find(reading)
        val b = label.find(packedSource)
        if (a != null && b != null && TextMatch.normalize(a.groupValues[1]) != TextMatch.normalize(b.groupValues[1])) return false
        val raw = reading.drop(a?.value?.length ?: 0)
        val previous = packedSource.drop(b?.value?.length ?: 0)
        if (TextMatch.endsQuestion(raw) != TextMatch.endsQuestion(previous)) return false
        val current = TextMatch.normalize(raw)
        val known = TextMatch.normalize(previous)
        if (current.isBlank()) return false
        if (current == known) return true
        if (minOf(current.length, known.length) < 10 || maxOf(current.length, known.length) > 320) return false
        if (TextMatch.isGrowth(current, known) || TextMatch.isGrowth(known, current)) return false
        if (markers(current) != markers(known)) return false
        val compact = current.replace(" ", "")
        val compactKnown = known.replace(" ", "")
        if (!TextMatch.isSimilar(compact, compactKnown, 0.86)) return false
        return current.count { it == ' ' } != known.count { it == ' ' } ||
            !TextMatch.hasMeaningfulChange(current, known)
    }

    private fun markers(text: String) = changesMeaning.findAll(text).map(MatchResult::value).toList()
}
