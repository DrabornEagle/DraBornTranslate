// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import com.gametranslator.gametranslator_mobile.core.PackMatch
import com.gametranslator.gametranslator_mobile.core.TurkishPostEdit
import java.util.Locale

/**
 * Conservative quality gate: only repairs mechanical errors, never invents
 * dialogue. A corrupt/mismatched hit falls through to the device translator;
 * a good exact pack hit still bypasses it immediately.
 */
internal object PackQualityGate {
    private val html = mapOf(
        "&amp;" to "&", "&quot;" to "\"", "&#39;" to "'", "&apos;" to "'",
        "&lt;" to "<", "&gt;" to ">",
    )
    private val whitespace = Regex("""[ \t\u00a0]{2,}""")
    private val tags = Regex("""<[^<>]{1,40}>""")
    private val unfinished = Regex("""(?:\uFFFD|\u0000|(?:\?\?){3,})""")
    // Sentence-labels and location/business names can legitimately stay in English.
    private val speakerOrLabel = Regex("""^[\p{L}\p{N}'’&.\- ]{2,55}:\s*$""")
    private val englishSourceWords = Regex(
        """\b(?:don't|didn't|won't|can't|shouldn't|never|not|isn't|aren't|wasn't|weren't)\b""",
        RegexOption.IGNORE_CASE
    )
    private val turkishNegation = Regex(
        """(?:\b(?:değil|yok|hayır|asla|sakın|hiç|olmaz|olmadı|etme|yapma|gitme|gelme|durma|bırakma|istemiyorum|bilmiyorum|yapamam)\b|(?:mıyor|miyor|muyor|müyor|madı|medi|maz|mez|mayın|meyin|mayacak|meyecek|mam|mem|mamak|memek)\b)""",
        RegexOption.IGNORE_CASE
    )
    private val englishFunctional = Regex(
        """\b(?:the|and|you|your|with|about|from|because|have|would|should|we're|they're|there|don't)\b""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * String substitutions are constrained to visible formatting and common HTML
     * escapes. Never attempt to "improve" colloquial meaning without a human
     * reviewed replacement.
     */
    fun normalize(source: String, translation: String): String {
        var cleaned = translation.trim()
        for ((entity, decoded) in html) cleaned = cleaned.replace(entity, decoded, ignoreCase = true)
        cleaned = tags.replace(cleaned, "")
        cleaned = whitespace.replace(cleaned, " ")
        return TurkishPostEdit.apply(source, cleaned)
    }

    fun select(source: String, hit: PackMatch?): PackMatch? {
        val candidate = hit?.text ?: return null
        val cleaned = normalize(source, candidate)
        if (!acceptable(source, cleaned)) return null
        return hit.copy(text = cleaned)
    }

    fun acceptable(source: String, candidate: String): Boolean {
        val text = candidate.trim()
        if (text.isEmpty() || text.length > 4000 || unfinished.containsMatchIn(text)) return false
        // Preserve label-only and character names such as "Kolt & Associates:".
        if (speakerOrLabel.matches(source.trim())) return true
        if (source.trim().endsWith('?') && !text.trimEnd('\"', '”', '\'').endsWith('?')) return false
        if (source.length >= 18 && source.trim().equals(text, ignoreCase = true) &&
            englishFunctional.containsMatchIn(source)) return false
        if (source.length >= 12 && englishSourceWords.containsMatchIn(source) &&
            !turkishNegation.containsMatchIn(text) &&
            !text.contains("n't", ignoreCase = true) &&
            englishFunctional.containsMatchIn(text)
        ) return false
        return true
    }
}
