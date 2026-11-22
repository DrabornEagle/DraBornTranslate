// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import com.gametranslator.gametranslator_mobile.core.SubtitleSegmenter
import java.util.Locale

/** Gives short English answers their omitted subject matter before sentence-level translation. */
internal object DialogueContext {
    private const val PRONOUN = "i|you|we|he|she|it|they"
    private const val AUXILIARY = "do|does|did|can|could|will|would|am|is|are|was|were|have|has|had"
    private const val NEGATIVE = "don't|doesn't|didn't|can't|couldn't|won't|wouldn't|isn't|aren't|wasn't|weren't|haven't|hasn't|hadn't"
    private const val STATES = "open|closed|locked|unlocked|ready|empty|safe|broken|blocked|gone|awake|all right|on|off"
    private const val PERSON_ROLE = "the (?:driver|owner|guard|mechanic|clerk|bartender|manager|passenger)"
    private val answer = Regex(
        """((?:yes|no),? )?($PRONOUN) ($AUXILIARY|$NEGATIVE)(?:,? ((?:until|before) .+?))?([.!]?)""",
        RegexOption.IGNORE_CASE,
    )
    private val question = Regex("""($AUXILIARY|$NEGATIVE) ($PRONOUN|(?-i:$NAME)|$PERSON_ROLE) (.+)\?""", RegexOption.IGNORE_CASE)
    private val negativeAuxiliary = Regex(NEGATIVE, RegexOption.IGNORE_CASE)
    private val stateQuestion = Regex(
        """is (it|he|she|(?:the|that|this) (?:[a-z-]+ ){0,3}[a-z-]+) ($STATES)\?""",
        RegexOption.IGNORE_CASE,
    )
    private val neither = Regex("""neither (do|does|did|can|could|have|has|had) ($PRONOUN)([.!]?)""", RegexOption.IGNORE_CASE)
    private val negativeStatement = Regex("""(?:$PRONOUN) (?:still )?($NEGATIVE) (.+)""", RegexOption.IGNORE_CASE)
    private val state = Regex(
        """\b(?:am|is|are|was|were) ($STATES|going to .+|the one who .+)$""",
        RegexOption.IGNORE_CASE,
    )
    private val expectedState = Regex("""\b(?:is|are|'s) (?:supposed|meant) to be ($STATES)$""", RegexOption.IGNORE_CASE)
    private val expectedAction = Regex("""\b(?:am|is|are|was|were) ((?:supposed|meant) to .+)$""", RegexOption.IGNORE_CASE)
    private val subordinateClause = Regex("""\b(?:while|because|after|before|until|when|if|although)\b""", RegexOption.IGNORE_CASE)
    private val statedObject = Regex(
        """\b(?:the|a|an|this|that) ((?:[a-z-]+ ){0,2}[a-z-]+) (?:is|was) ($STATES)$""",
        RegexOption.IGNORE_CASE,
    )
    private val stateReference = Regex("""\bwhen it is(?=[.!?]?$)""", RegexOption.IGNORE_CASE)
    private val speakerDependent = Regex("""\b(?:i|me|my|mine|you|your|yours|we|us|our|ours)\b""", RegexOption.IGNORE_CASE)
    private val observedEvent = Regex("""(see|hear|notice) what happened to (?:my|your|our) .+""", RegexOption.IGNORE_CASE)
    private val observedPerson = Regex("""(see|hear|notice) (?:my|your|our) (brother|sister|father|mother|parents|kids)""", RegexOption.IGNORE_CASE)
    private val standalone = Regex("""\bon your own$""", RegexOption.IGNORE_CASE)
    private val reportedDetail = Regex("""(tell (?:him|her|them)) (?:where|what|when|why|how) .+""", RegexOption.IGNORE_CASE)
    private val waitForSpeaker = Regex("""wait for me((?: (?:at|by|near|outside) .+)?)""", RegexOption.IGNORE_CASE)
    private val requestObject = Regex("""(get|find|bring|make) (?:me|us) ((?:the|a|an|some) .+)""", RegexOption.IGNORE_CASE)
    private val showedObject = Regex("""show (?:me|you|us) ((?:the|a|an|some) .+)""", RegexOption.IGNORE_CASE)
    private val modalAnswer = Regex(
        """($PRONOUN) (would've|would have|haven't|hasn't|could|should)( yet| by now)?(, but .+?)?([.!]?)""",
        RegexOption.IGNORE_CASE,
    )
    private val handledObject = Regex("""(open|close|lock|unlock) the (?:safe|door|gate|window)""", RegexOption.IGNORE_CASE)
    private val shortResponse = Regex("""(not yet|already did|not anymore|i will be|so am i)([.!]?)""", RegexOption.IGNORE_CASE)
    private val alignedBelief = Regex("""i (thought|figured|assumed|expected) (.+)""", RegexOption.IGNORE_CASE)
    private val matchingBelief = Regex("""so did i([.!]?)""", RegexOption.IGNORE_CASE)
    private val mustHave = Regex("""(he|she|they) must have([.!]?)""", RegexOption.IGNORE_CASE)
    private val reportedAction = Regex("""(he|she|they) said (he|she|they) (was|were)([.!]?)""", RegexOption.IGNORE_CASE)
    private const val NAME = """\p{Lu}[\p{L}'-]+(?: \p{Lu}[\p{L}'-]+){0,2}"""
    private val personName = Regex(NAME)
    private val ongoingActionQuestion = Regex(
        """(?i:is|are) ($NAME|(?i:he|she|they)) ((?i:coming|leaving|waiting|driving|staying)(?: .+)?)\?""",
    )
    private val namedAnswer = Regex("""($NAME) (?i:did)([.!]?)""")
    private val pastParticiples = mapOf(
        "take" to "taken", "see" to "seen", "leave" to "left", "go" to "gone", "put" to "put",
        "drive" to "driven", "get" to "gotten", "call" to "called", "lock" to "locked", "open" to "opened",
        "tell" to "told", "break" to "broken", "hear" to "heard", "read" to "read", "send" to "sent",
        "check" to "checked", "find" to "found", "bring" to "brought", "pick" to "picked", "show" to "shown",
    )

    fun mightNeedPrevious(text: String): Boolean {
        val first = SubtitleSegmenter.split(text).firstOrNull { it.translate }?.text?.replace('’', '\'') ?: return false
        return answer.matches(first) || neither.matches(first) || shortResponse.matches(first) ||
            matchingBelief.matches(first) || mustHave.matches(first) || reportedAction.matches(first) ||
            namedAnswer.matches(first) || modalAnswer.matches(first) || stateReference.containsMatchIn(first) ||
            AmericanDialogue.needsContext(first) ||
            refersToPreviousPassage(first)
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
            if (family(auxiliary) != family(negative)) return text
            val complement = handledObject.matchEntire(action)?.let { "${it.groupValues[1]} it" }
                ?: retainAction(action) ?: return text
            val pronoun = subject.lowercase(Locale.ROOT).replaceFirstChar { it.uppercaseChar() }
            val singular = pronoun in listOf("He", "She", "It")
            val verb = when (family(negative)) {
                "do" -> if (singular) "doesn't" else "don't"
                "have" -> if (singular) "hasn't" else "haven't"
                else -> negative
            }
            return "$pronoun $verb $complement either$ending"
        }

        val precedingQuestion = question.matchEntire(prior)
        expandShortResponse(source, prior, precedingQuestion)?.let { return it }
        val (prefix, subject, auxiliary, condition, ending) = answer.matchEntire(source)?.destructured ?: return text
        val auxiliaryFamily = family(auxiliary)
        val complement = when {
            precedingQuestion != null && family(precedingQuestion.groupValues[1]) == auxiliaryFamily -> precedingQuestion.groupValues[3]
            precedingQuestion == null && !prior.endsWith('?') && auxiliaryFamily in listOf("be", "past be") ->
                statementComplement(priorBody)
            else -> null
        }?.let(::retainAction) ?: return text
        val tail = if (condition.isEmpty()) "" else " $condition"
        val action = if (condition.isEmpty() && auxiliary.matches(negativeAuxiliary) &&
            complement.startsWith("still ", ignoreCase = true)
        ) {
            complement.substringAfter(' ') + " anymore"
        } else complement
        return "$prefix$subject $auxiliary $action$tail$ending"
    }

    private fun statementComplement(statement: String): String? {
        val primary = subordinateClause.find(statement)?.let { statement.substring(0, it.range.first).trimEnd(' ', ',') } ?: statement
        return (state.find(primary) ?: expectedState.find(primary) ?: expectedAction.find(primary))?.groupValues?.get(1)
    }

    private fun expandShortResponse(source: String, prior: String, question: MatchResult?): String? {
        modalAnswer.matchEntire(source)?.destructured?.let { (subject, auxiliary, timing, tail, ending) ->
            val (askedAuxiliary, _, action) = question?.destructured ?: return null
            val complement = retainAction(action) ?: return null
            val askedFamily = family(askedAuxiliary)
            val modal = auxiliary.lowercase(Locale.ROOT)
            val actionTaken = when {
                modal in listOf("would've", "would have") && askedFamily == "did" -> asParticiple(complement)
                modal in listOf("haven't", "hasn't") && askedFamily == "did" -> asParticiple(complement)
                modal in listOf("haven't", "hasn't") && askedFamily == "have" -> complement
                modal == "could" && askedFamily in listOf("can", "could") -> complement
                modal == "should" && askedFamily == "do" && timing.equals(" by now", ignoreCase = true) -> complement
                else -> null
            } ?: return null
            return "$subject $auxiliary $actionTaken$timing$tail$ending"
        }
        reportedAction.matchEntire(source)?.destructured?.let { (speaker, subject, auxiliary, ending) ->
            val (askedSubject, action) = ongoingActionQuestion.matchEntire(prior)?.destructured ?: return null
            if (!speaker.equals(subject, ignoreCase = true)) return null
            val named = personName.matches(askedSubject)
            if (!named && !subject.equals(askedSubject, ignoreCase = true)) return null
            return retainAction(action)?.let { "$speaker said $subject $auxiliary $it$ending" }
        }
        shortResponse.matchEntire(source)?.destructured?.let { (response, ending) ->
            val auxiliary = question?.groupValues?.get(1).orEmpty()
            val subject = question?.groupValues?.get(2).orEmpty()
            val complement = question?.groupValues?.get(3)?.let(::retainAction)
            return when (response.lowercase(Locale.ROOT)) {
                "not yet" -> when {
                    complement.isNullOrBlank() || !subject.equals("you", ignoreCase = true) -> null
                    family(auxiliary) == "have" -> "I haven't $complement yet$ending"
                    family(auxiliary) == "did" -> "I didn't $complement yet$ending"
                    else -> null
                }
                "already did" -> complement?.takeIf { family(auxiliary) == "did" && subject.equals("you", ignoreCase = true) }
                    ?.let { "I already did $it$ending" }
                "not anymore" -> stateQuestion.matchEntire(prior)?.destructured?.let { (noun, condition) ->
                    val pronoun = when (noun.lowercase(Locale.ROOT)) {
                        "he" -> "He"
                        "she" -> "She"
                        else -> "It"
                    }
                    val descriptor = if (condition.equals("blocked", ignoreCase = true) &&
                        noun.substringAfterLast(' ').lowercase(Locale.ROOT) in listOf("bridge", "road", "passage")
                    ) "closed" else condition
                    "$pronoun is not $descriptor anymore$ending"
                }
                "i will be" -> complement?.takeIf { subject.equals("you", ignoreCase = true) && family(auxiliary) == "be" }
                    ?.takeIf { it.lowercase(Locale.ROOT) in listOf("all right", "ready", "awake", "safe") }
                    ?.let { "I will be $it$ending" }
                else -> if (prior.endsWith('?')) null else state.find(prior.trimEnd('.', '!'))?.groupValues?.get(1)
                    ?.takeIf { it.lowercase(Locale.ROOT) in listOf("all right", "ready", "awake", "safe") }
                    ?.let { "I am $it too$ending" }
            }
        }
        namedAnswer.matchEntire(source)?.destructured?.let { (name, ending) ->
            val who = actionQuestion.matchEntire(prior)?.groupValues?.get(1)
            if (who != null) return retainAction(who)?.let { "$name $it$ending" }
            val action = question?.takeIf { family(it.groupValues[1]) == "did" }?.groupValues?.get(3)
            return action?.let(::retainAction)?.let { "$name did $it$ending" }
        }
        matchingBelief.matchEntire(source)?.let { response ->
            val (verb, action) = alignedBelief.matchEntire(prior.trimEnd('.', '!'))?.destructured ?: return null
            return retainAction(action)?.let { "I $verb so too${response.groupValues[1]}" }
        }
        mustHave.matchEntire(source)?.destructured?.let { (subject, ending) ->
            val (auxiliary, askedSubject, action) = question?.destructured ?: return null
            if (!auxiliary.equals("did", ignoreCase = true) || !subject.equals(askedSubject, ignoreCase = true)) return null
            val complement = retainAction(action) ?: return null
            val participle = asParticiple(complement) ?: return null
            return "$subject must have $participle$ending"
        }
        return null
    }

    private fun asParticiple(action: String): String? {
        val verb = action.substringBefore(' ')
        val participle = pastParticiples[verb.lowercase(Locale.ROOT)] ?: return null
        return participle + action.removePrefix(verb)
    }

    private fun retainAction(action: String): String? {
        // Drop a viewpoint-dependent modifier only when the shared event/person remains unambiguous.
        if (standalone.containsMatchIn(action)) return standalone.replace(action, "alone").takeUnless(speakerDependent::containsMatchIn)
        waitForSpeaker.matchEntire(action)?.let { return "wait${it.groupValues[1]}" }
        requestObject.matchEntire(action)?.destructured?.let { (verb, value) ->
            return "$verb $value".takeUnless(speakerDependent::containsMatchIn)
        }
        showedObject.matchEntire(action)?.let {
            return ("show " + it.groupValues[1]).takeUnless(speakerDependent::containsMatchIn)
        }
        if (action.equals("know where we're going", ignoreCase = true) || action.equals("know where we are going", ignoreCase = true)) {
            return "know the destination"
        }
        observedEvent.matchEntire(action)?.let { return "${it.groupValues[1]} what happened" }
        observedPerson.matchEntire(action)?.destructured?.let { (verb, relative) ->
            val person = when (relative.lowercase(Locale.ROOT)) {
                "brother", "father" -> "him"
                "sister", "mother" -> "her"
                else -> "them"
            }
            return "$verb $person"
        }
        if (!speakerDependent.containsMatchIn(action)) return action
        return reportedDetail.matchEntire(action)?.groupValues?.get(1)
    }

    private fun family(auxiliary: String): String = when (val form = auxiliary.lowercase(Locale.ROOT)) {
        "am", "is", "are", "isn't", "aren't" -> "be"
        "was", "were", "wasn't", "weren't" -> "past be"
        "do", "does", "don't", "doesn't" -> "do"
        "have", "has", "haven't", "hasn't" -> "have"
        "can't" -> "can"
        "won't" -> "will"
        else -> form.removeSuffix("n't")
    }
}
