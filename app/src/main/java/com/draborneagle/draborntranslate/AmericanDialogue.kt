// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import java.util.Locale

/** Resolves colloquial English before the offline engine translates a subtitle. */
internal object AmericanDialogue {
    private fun pattern(value: String) = Regex(value, RegexOption.IGNORE_CASE)

    private fun phrase(source: String, meaning: String): Pair<Regex, (MatchResult) -> String> =
        pattern("""\b$source\b""") to { meaning }

    private data class ContextRule(val line: Regex, val context: Regex, val meaning: String)

    private fun contextual(line: String, context: String, meaning: String) =
        ContextRule(pattern(line), pattern(context), meaning)

    private val contextualPhrases = listOf(
        contextual("""\b(?:i'm|i am) running on fumes\b""", """\b(?:gas station|fuel|tank|out of gas)\b""", "I'm almost out of gas"),
        contextual("""\b(?:i'm|i am) running on fumes\b""", """\b(?:been up|haven't slept|no sleep|worked all night)\b""", "I'm exhausted"),
        contextual("""^we finally lost them[.!]?$""", """\b(?:cops|police)\b.*\b(?:chasing|after us|following us)\b""", "We finally got away from the police."),
        contextual("""^we lost them[.!]?$""", """\b(?:cops|police)\b.*\b(?:chasing|after us|following us)\b""", "We got away from the police."),
        contextual("""^i already covered it[.!]?$""", """\b(?:fee|bill|payment|cost)\b""", "I already paid it."),
        contextual("""^he got held up[.!]?$""", """\b(?:supposed to be here|running late|an hour ago|late for)\b""", "He was delayed."),
        contextual("""^he's clean[.!]?$""", """\b(?:checked|pulled|looked up)\b.*\b(?:record|rap sheet)\b""", "He has no criminal record."),
        contextual("""^he's clean[.!]?$""", """\bsearched his (?:car|van|truck)\b.*\bfound nothing\b""", "There is nothing suspicious in his vehicle."),
        contextual("""^depends on when i get off[.!]?$""", """\b(?:at the bar later|after work|your shift|get off work)\b""", "It depends on when I finish work."),
        contextual("""^i'll move it[.!]?$""", """\b(?:truck|car|van)\b.*\bblocking (?:the|our|my) (?:driveway|entrance|gate)\b""", "I'll move the vehicle out of the way."),
        contextual("""^it is now[.!]?$""", """\bradio(?:'s| is) not playing\b""", "The radio is playing now."),
        contextual("""^he charged us twice that[.!]?$""", """\$\d|\b(?:cost|fee|bill|charged)\b""", "He made us pay twice as much."),
        contextual("""^nothing he didn't already know[.!]?$""", """\bwhat did you tell\b""", "I didn't tell him anything he didn't already know."),
        contextual("""^want some\?$""", """\b(?:coffee)\b""", "Would you like some coffee?"),
        contextual("""\bbreak in(?=[.!?,]|$)""", """\blet (?:them|him|her|us) in\b""", "enter by force"),
    )

    fun needsContext(line: String): Boolean {
        val text = line.replace('’', '\'')
        return contextualPhrases.any { it.line.containsMatchIn(text) }
    }

    fun normalize(line: String, previous: String?, following: String?): String {
        val nearby = "${previous.orEmpty()} $line ${following.orEmpty()}".replace('’', '\'')
        val contextual = contextualPhrases.firstOrNull {
            it.line.containsMatchIn(line) && it.context.containsMatchIn(nearby)
        }
        val understood = if (contextual != null) contextual.line.replace(line, contextual.meaning) else line
        return phrases.fold(understood) { text, (regex, replace) ->
            regex.replace(text) { match ->
                replace(match).replaceFirstChar { first ->
                    if (match.range.first == 0 && match.value.first().isUpperCase()) first.uppercaseChar() else first
                }
            }
        }
    }

    private val phrases = listOf(
        phrase("whatcha gonna", "what are you going to"),
        phrase("^you tryna", "are you trying to"),
        phrase("oughta", "ought to"),
        phrase("outta here", "out of here"),
        phrase("don't gotta", "don't have to"),
        phrase("i(?:'ve)? got half a mind to", "I'm tempted to"),
        phrase("you better believe", "you can be sure that"),
        phrase("don't fall for that", "don't believe that trick"),
        phrase("get wind of", "find out about"),
        phrase("gets wind of", "finds out about"),
        phrase("got wind of", "found out about"),
        phrase("getting wind of", "finding out about"),
        phrase("get off our backs", "stop pressuring us"),
        phrase("take the rap for", "take the blame for"),
        phrase("hold that thought", "wait a second and we will talk about that"),
        phrase("running his mouth", "talking nonsense"),
        phrase("running her mouth", "talking nonsense"),
        phrase("it's all coming back to me now", "now I remember it all"),
        phrase("i(?:'ve)? got a lot riding on this", "this is very important to me"),
        phrase("having second thoughts", "starting to have doubts"),
        phrase("in the home stretch", "almost finished"),
        phrase("beat around the bush", "avoid saying what you mean"),
        phrase("get blown off", "be ignored"),
        phrase("go out on a limb for", "take a risk for"),
        phrase("get my head straight", "think clearly again"),
        phrase("get your head straight", "think clearly again"),
        phrase("it's just a heads-up", "I just wanted to warn you"),
        phrase("sitting on (this|that|the) information", "keeping the information secret"),
        phrase("hang a right", "turn right"),
        phrase("hang a left", "turn left"),
        phrase("four-way stop", "intersection with stop signs on all four sides"),
        phrase("""floor it(?=\s*(?:[.!?,]|$|until |when |once |just because ))""", "press the gas pedal hard"),
        phrase("call (?:this|it) even", "say that the debt is settled"),
        phrase("in the clear(?=[.!?,]|$| now| yet)", "safe"),
        phrase("on lockdown", "closed to entry and exit"),
        phrase("at two places at once", "in two places at the same time"),
        phrase("somebody let them in", "somebody allowed them inside"),
        phrase("i wasn't born yesterday(?=[.!?,]|$)", "I am not naive"),
        phrase("it'll blow over", "things will calm down"),
        phrase("keep watch", "stand guard"),
        phrase("phone was dead", "phone's battery had run out"),
        phrase("turn down the offer", "reject the offer"),
        phrase("you didn't have to rub it in", "you did not have to keep reminding me of my mistake"),
        phrase("got the green light from", "got permission from"),
        phrase("information on the side(?=[.!?,]|$)", "information in secret"),
        phrase("turn the radio down", "lower the radio volume"),
        phrase("turn down the radio", "lower the radio volume"),
        phrase("got cold feet and bailed", "got scared and backed out"),
        pattern("""\b(pay(?:ing)? (?:\$[\d,.]+|(?:[\d,.]+|[a-z-]+) dollars)) up front\b""") to
            { match: MatchResult -> "${match.groupValues[1]} in advance" },
        pattern("""\b(i|we|you|they|y'all) ain't gotta\b""") to { match: MatchResult -> "${match.groupValues[1]} don't have to" },
        pattern("""\b(he|she|it) ain't gotta\b""") to { match: MatchResult -> "${match.groupValues[1]} doesn't have to" },
        pattern("""\b(i'm|you're|he's|she's|we're|they're) finna\b""") to { match: MatchResult -> "${match.groupValues[1]} about to" },
        pattern("""^bet you ([a-z]+|\d+) bucks (he|she|they) never shows?[.!]?$""") to
            { match: MatchResult -> "I bet you ${match.groupValues[1]} dollars that ${match.groupValues[2]} will not come." },
        pattern("""\byou(?:'ve)? got some nerve (showing up|coming) here\b""") to
            { _: MatchResult -> "how dare you come here" },
        pattern("""\b(he|she) just wants you off (?:his|her) back\b""") to
            { match: MatchResult -> "${match.groupValues[1]} just wants you to leave ${if (match.groupValues[1].equals("he", true)) "him" else "her"} alone" },
        pattern("""\bthrew (me|you|him|her|us|them) under the bus(?= (?:the moment|when|as soon as) .*(?:feds|cops|police|investigators)\b)""") to
            { match: MatchResult -> "put the blame on ${match.groupValues[1]}" },
        pattern("""\b(he|she|they) flipped on (me|you|him|her|us|them) to (?:stay out of (?:prison|jail)|avoid (?:prison|jail))\b""") to
            { match: MatchResult ->
                val (subject, person) = match.destructured
                "$subject testified against $person so $subject wouldn't have to go to prison"
            },
        pattern("""\bget the jump on (me|you|him|her|us|them)\b""") to { match: MatchResult -> "catch ${match.groupValues[1]} by surprise" },
        pattern("""\btip (my|your|his|her|our|their) hand\b""") to { match: MatchResult -> "reveal ${match.groupValues[1]} plans" },
        pattern("""\b(give|gives|gave) (me|you|him|her|us|them) a hard time\b""") to
            { match: MatchResult ->
                val verb = when (match.groupValues[1].lowercase(Locale.ROOT)) {
                    "gives" -> "makes"
                    "gave" -> "made"
                    else -> "make"
                }
                "$verb things difficult for ${match.groupValues[2]}"
            },
        pattern("""\bleft (me|you|him|her|us|them) holding the bag(?= when (?:things|it|the deal) went (?:sideways|wrong))""") to
            { match: MatchResult -> "left ${match.groupValues[1]} with all the problems" },
        pattern("""\bput in a good word with (the (?:owner|manager|boss))(?= if you want)""") to
            { match: MatchResult -> "say something in your favor to ${match.groupValues[1]}" },
        pattern("""\broad(?:'s| is) closed past\b""") to { _: MatchResult -> "road is closed beyond" },
        pattern("""\bhook (me|you|him|her|us|them) up with a ride\b""") to
            { match: MatchResult -> "arrange a vehicle for ${match.groupValues[1]}" },
        pattern("""\bbail on (me|you|him|her|us|them)\b""") to { match: MatchResult -> "abandon ${match.groupValues[1]}" },
        pattern("""\bbailed(?= (?:right before |at the last minute))""") to { _: MatchResult -> "backed out" },
        pattern("""\bgave (me|you|him|her|us|them) the slip\b""") to
            { match: MatchResult -> "managed to escape from ${match.groupValues[1]}" },
        pattern("""\bburner(?= (?:out of|from|in) (?:the |your |my )?(?:glove box|glove compartment))""") to
            { _: MatchResult -> "disposable phone" },
        pattern("""\b(yanking|pulling) (my|your|his|her|our|their) chain\b""") to { match: MatchResult ->
            val target = when (match.groupValues[2].lowercase(Locale.ROOT)) {
                "my" -> "me"
                "your" -> "you"
                "his" -> "him"
                "her" -> "her"
                "our" -> "us"
                else -> "them"
            }
            "messing with $target"
        },
    )
}
