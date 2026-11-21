// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import com.gametranslator.gametranslator_mobile.core.SubtitleSegmenter
import java.util.Locale

/** Gives short English answers their omitted subject matter before sentence-level translation. */
internal object DialogueContext {
    private const val PRONOUN = "i|you|we|he|she|it|they"
    private const val AUXILIARY = "do|does|did|can|could|will|would|am|is|are|was|were"
    private const val NEGATIVE = "don't|doesn't|didn't|can't|couldn't|won't|wouldn't|isn't|aren't|wasn't|weren't"
    private const val STATES = "open|closed|locked|unlocked|ready|empty|safe|broken|on|off"
    private val answer = Regex(
        """((?:yes|no),? )?($PRONOUN) ($AUXILIARY|$NEGATIVE)(?:,? ((?:until|before) .+?))?([.!]?)""",
        RegexOption.IGNORE_CASE,
    )
    private val question = Regex("""($AUXILIARY) (?:$PRONOUN) (.+)\?""", RegexOption.IGNORE_CASE)
    private val neither = Regex("""neither (do|does|did|can|could) ($PRONOUN)([.!]?)""", RegexOption.IGNORE_CASE)
    private val negativeStatement = Regex("""(?:$PRONOUN) ($NEGATIVE) (.+)""", RegexOption.IGNORE_CASE)
    private val state = Regex(
        """\b(?:am|is|are|was|were) ($STATES|going to .+|the one who .+)$""",
        RegexOption.IGNORE_CASE,
    )
    private val statedObject = Regex(
        """\b(?:the|a|an|this|that) ((?:[a-z-]+ ){0,2}[a-z-]+) (?:is|was) ($STATES)$""",
        RegexOption.IGNORE_CASE,
    )
    private val stateReference = Regex("""\bwhen it is(?=[.!?]?$)""", RegexOption.IGNORE_CASE)
    private val speakerDependent = Regex("""\b(?:i|me|my|mine|you|your|yours|we|us|our|ours)\b""", RegexOption.IGNORE_CASE)

    fun mightNeedPrevious(text: String): Boolean {
        val first = SubtitleSegmenter.split(text).firstOrNull { it.translate }?.text?.replace('’', '\'') ?: return false
        return answer.matches(first) || neither.matches(first) || stateReference.containsMatchIn(first)
    }

    fun lastSentence(text: String?): String? =
        text?.let(SubtitleSegmenter::split)?.lastOrNull { it.translate }?.text

    fun expand(text: String, previous: String?): String {
        val prior = previous?.replace('’', '\'') ?: return text
        val source = text.replace('’', '\'')
        val priorBody = prior.trimEnd('.', '!', '?')
        if (stateReference.containsMatchIn(source)) {
            val (objectName, condition) = statedObject.find(priorBody)?.destructured ?: return text
            return stateReference.replace(source) { "when the $objectName is $condition" }
        }
        neither.matchEntire(source)?.destructured?.let { (auxiliary, subject, ending) ->
            val (negative, action) = negativeStatement.matchEntire(prior.trimEnd('.', '!'))?.destructured ?: return text
            if (family(auxiliary) != family(negative) || speakerDependent.containsMatchIn(action)) return text
            val pronoun = subject.lowercase(Locale.ROOT).replaceFirstChar { it.uppercaseChar() }
            val verb = if (family(negative) == "do") {
                if (pronoun in listOf("He", "She", "It")) "doesn't" else "don't"
            } else negative
            return "$pronoun $verb $action either$ending"
        }

        val (prefix, subject, auxiliary, condition, ending) = answer.matchEntire(source)?.destructured ?: return text
        val precedingQuestion = question.matchEntire(prior)
        val auxiliaryFamily = family(auxiliary)
        val complement = when {
            precedingQuestion != null && family(precedingQuestion.groupValues[1]) == auxiliaryFamily -> precedingQuestion.groupValues[2]
            precedingQuestion == null && (auxiliaryFamily == "be" || auxiliaryFamily == "past be") ->
                state.find(priorBody)?.groupValues?.get(1)
            else -> null
        }?.takeUnless(speakerDependent::containsMatchIn) ?: return text
        val tail = if (condition.isEmpty()) "" else " $condition"
        return "$prefix$subject $auxiliary $complement$tail$ending"
    }

    private fun family(auxiliary: String): String = when (val form = auxiliary.lowercase(Locale.ROOT)) {
        "am", "is", "are", "isn't", "aren't" -> "be"
        "was", "were", "wasn't", "weren't" -> "past be"
        "do", "does", "don't", "doesn't" -> "do"
        "can't" -> "can"
        "won't" -> "will"
        else -> form.removeSuffix("n't")
    }
}
