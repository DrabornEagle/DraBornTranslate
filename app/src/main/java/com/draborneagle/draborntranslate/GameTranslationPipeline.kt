// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import com.gametranslator.gametranslator_mobile.core.Glossary
import com.gametranslator.gametranslator_mobile.core.GlossaryTranslator
import com.gametranslator.gametranslator_mobile.core.PackKey
import com.gametranslator.gametranslator_mobile.core.PackMatch
import com.gametranslator.gametranslator_mobile.core.SubtitleSegmenter
import com.gametranslator.gametranslator_mobile.core.TextMatch
import com.gametranslator.gametranslator_mobile.core.TextTranslator
import java.util.Locale

internal data class TranslationInput(
    val text: String,
    val readingArea: ReadingArea = ReadingArea.SUBTITLES,
    val previousSubtitle: String? = null,
)
internal data class TranslationReply(val text: String? = null, val issue: String? = null)

/** Gives the sentence-level model complete sentences and keeps menu lines and speaker turns separate. */
internal class GameTranslationPipeline(
    private val packLookup: (String) -> PackMatch?,
    model: TextTranslator,
) {
    private val withTerms = GlossaryTranslator(GAME_TERMS, model)

    fun translate(input: TranslationInput): TranslationReply {
        val text = input.text.trim()
        if (text.isEmpty()) return TranslationReply()
        val rendered = if (input.readingArea == ReadingArea.SUBTITLES) {
            findPack(text) ?: translateLine(text, previousSentence = DialogueContext.lastSentence(input.previousSubtitle))
        } else {
            text.lines().mapNotNull(::translateMenuLine).joinToString("\n").ifBlank { null }
        }
        return TranslationReply(rendered)
    }

    private fun findPack(text: String): String? =
        runCatching { packLookup(text)?.text?.trim()?.takeIf(String::isNotEmpty) }.getOrNull()
            ?.takeIf { preservesQuestion(text, it) }

    private fun translateMenuLine(line: String): String? {
        findPack(line)?.let { return it }
        controllerHint(line)?.let { return it }
        checkpointJump.matchEntire(line)?.groupValues?.get(1)?.let { return "$it. kontrol noktasına geç" }

        val title = line.substringBefore(':').trim()
        val header = PackKey.key(title)?.let(menuHeaders::get)
        if (header != null) {
            if (':' !in line) return header
            val detail = line.substringAfter(':').trim()
            if (detail.isEmpty()) return "$header:"
            val translation = if (detail.none(Char::isLetter) || displaySize.matches(detail)) detail
            else translateLine(detail, preserveSpeakers = false)
            return translation?.let { "$header: $it" }
        }
        if (line.equals("New Game+", ignoreCase = true)) return "Yeni Oyun+"
        return translateLine(line, preserveSpeakers = false)
    }

    private fun translateLine(text: String, preserveSpeakers: Boolean = true, previousSentence: String? = null): String? {
        val pieces = SubtitleSegmenter.split(text, preserveSpeakers = preserveSpeakers)
        if (pieces.none { it.translate }) return null
        return buildString {
            var previous = previousSentence
            for (piece in pieces) {
                if (!piece.translate) {
                    append(piece.text)
                    continue
                }
                val source = piece.text
                val direct = PackKey.key(source)?.let(commonLines::get)?.takeIf { preservesQuestion(source, it) }
                val translation = findPack(source) ?: direct ?: shortAnswer(source, previous)
                    ?: translateSentence(DialogueContext.expand(resolveDirection(source, previous), previous))
                if (translation.isNullOrBlank()) return null
                append(translation)
                previous = source
            }
        }.trim().takeIf(String::isNotEmpty)
    }

    private fun translateSentence(source: String): String? {
        val sentence = SubtitleSegmenter.normalizeCase(source, knownNames).replace('’', '\'')
        val input = idioms.fold(normalizeTime(sentence)) { text, (phrase, replacement) ->
            phrase.replace(text) { match ->
                replacement(match).replaceFirstChar { if (match.value[0].isUpperCase()) it.uppercaseChar() else it }
            }
        }
        val translated = withTerms.translate(input)?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val sentences = SubtitleSegmenter.split(translated).filter { it.translate }.map { it.text }
        val filtered = sentences.distinctBy { PackKey.key(it) }
        val natural = if (filtered.size < sentences.size) filtered.joinToString(" ") else translated
        return when (val end = source.trimEnd('"', '”').lastOrNull()) {
            '?', '!' -> natural.trimEnd('.', '?', '!') + end
            else -> natural
        }
    }
}

private fun preservesQuestion(source: String, translation: String): Boolean =
    TextMatch.endsQuestion(source) == TextMatch.endsQuestion(translation)

private val possessionQuestion = Regex(
    """^who(?: (?:has(?: got)?|holds|is holding)|['’]s got) (?:it|them|one|(?:the|a|an|your|my|his|her|our|their|this|that|these|those) .+)\?$""",
    RegexOption.IGNORE_CASE,
)
private val actionQuestion = Regex("""^who (?:opened|closed|locked|unlocked|moved|took|called|sent|left|broke|told|put|gave|drove|said|turned|cut) .+\?$""", RegexOption.IGNORE_CASE)

private fun shortAnswer(source: String, previous: String?): String? {
    val question = previous?.trim() ?: return null
    val answer = when (source.trimEnd('.', '!').lowercase(Locale.ROOT)) {
        "i do" -> "Bende".takeIf { possessionQuestion.matches(question) }
        "i did" -> "Ben".takeIf { actionQuestion.matches(question) }
        else -> null
    } ?: return null
    return answer + if (source.endsWith('!')) "!" else "."
}

private val directionalPassage = Regex("""\b(?:take|use|go through) the (left|right)(?:-hand)? (door|exit)\b""", RegexOption.IGNORE_CASE)
private val passageReference = Regex("""((?:actually, )?)(?:take|use|go through) the (left|right) one([.!]?)""", RegexOption.IGNORE_CASE)

private fun resolveDirection(source: String, previous: String?): String {
    val (lead, side, punctuation) = passageReference.matchEntire(source)?.destructured ?: return source
    val passage = previous?.let(directionalPassage::find)?.groupValues?.get(2) ?: return source
    val verb = if (lead.isEmpty()) "Go" else "go"
    return "$lead$verb through the $passage on the $side$punctuation"
}

private val clockTime = Regex("""\b(0?[1-9]|1[0-2])(?::([0-5]\d))?\s*([ap])\.?m\b""", RegexOption.IGNORE_CASE)

private fun normalizeTime(text: String): String = clockTime.replace(text) { match ->
    val (hour, minute, period) = match.destructured
    val hour24 = hour.toInt() % 12 + if (period.equals("p", ignoreCase = true)) 12 else 0
    "${hour24.toString().padStart(2, '0')}:${minute.ifEmpty { "00" }}"
}

private val names = listOf("Vice City", "Leonida", "Los Santos", "Liberty City", "San Andreas", "Lucia", "Jason")
private val knownNames = names.flatMap { it.lowercase(Locale.ROOT).split(' ') }.toSet()
private val GAME_TERMS = Glossary.of(
    terms = mapOf(
        "my wanted level" to "aranma seviyem",
        "wanted level" to "aranma seviyesi",
        "side mission" to "yan görev",
        "safe house" to "sığınak",
        "save point" to "kayıt noktası",
        "fast travel" to "hızlı seyahat",
        "my health bar" to "can çubuğum",
        "health bar" to "can çubuğu",
        "title screen" to "başlangıç ekranı",
        "glove compartment" to "torpido gözü",
        "call button" to "çağrı düğmesi",
    ),
    keep = names,
)

private val commonLines = mapOf(
    "Lose the cops!" to "Polisleri atlat!",
    "We are being tailed." to "Takip ediliyoruz.",
    "We're being tailed." to "Takip ediliyoruz.",
    "They're on our tail." to "Peşimizdeler.",
    "Take cover!" to "Siper al!",
    "Take your time." to "Acele etme.",
    "Not everyone made it out." to "Bazıları dışarı çıkamadı.",
    "Get in the car!" to "Arabaya bin!",
    "Stay out of sight." to "Görünme.",
    "Hold your fire!" to "Ateş etme!",
    "Keep your head down." to "Başını kaldırma.",
    "Let's get out of here." to "Hadi buradan çıkalım.",
    "Meet me at the bridge." to "Köprüde buluşalım.",
    "I'll cover you." to "Seni korurum.",
    "I should've seen it coming." to "Bunu tahmin etmeliydim.",
    "I should have seen it coming." to "Bunu tahmin etmeliydim.",
    "You set me up." to "Bana tuzak kurdun.",
    "I owe you one." to "Sana bir iyilik borçluyum.",
    "You made it." to "Başardın.",
    "Get down!" to "Yere yat!",
    "Run the plates." to "Plakayı sorgula.",
    "Go around the back." to "Arkadan dolaş.",
    "We're not out of the woods yet." to "Tehlike henüz geçmedi.",
    "Don't make me regret it." to "Beni pişman etme.",
    "The coast is clear." to "Ortalık temiz.",
    "Make a run for it." to "Koş, kaç!",
    "Got it." to "Anladım.",
    "I got this." to "Ben hallederim.",
    "Something's off." to "Bir terslik var.",
    "I don't buy it." to "Buna inanmıyorum.",
    "I'm not buying it." to "Buna inanmıyorum.",
    "Keep still." to "Kıpırdama.",
    "All right." to "Peki.",
    "Let's hear your side of the story." to "Bir de senden dinleyelim.",
    "I won't be long." to "Çok sürmez.",
    "Did we miss the turn?" to "Sapağı kaçırdık mı?",
    "We missed the turn." to "Sapağı kaçırdık.",
    "We'll cross that bridge when we come to it." to "O sorun çıkınca düşünürüz.",
    "Yeah." to "Evet.",
    "No." to "Hayır.",
    "Okay." to "Tamam.",
    "Thanks." to "Sağ ol.",
    "Come on!" to "Hadi!",
    "Go!" to "Git!",
    "Wait!" to "Bekle!",
    "Look out!" to "Dikkat et!",
    "Move!" to "Çabuk!",
    "Run!" to "Koş!",
    "Stop!" to "Dur!",
    "Keep it down." to "Sesini alçalt.",
    "Stick with me." to "Yanımdan ayrılma.",
    "I've got your back." to "Arkanı kollarım.",
    "We're on our own." to "Başımızın çaresine bakacağız.",
    "We're in over our heads." to "Bu iş boyumuzu aştı.",
    "We're in over our heads this time." to "Bu sefer iş boyumuzu aştı.",
    "We're running out of time." to "Vaktimiz daralıyor.",
    "Don't leave me hanging." to "Beni yarı yolda bırakma.",
    "Don't leave me hanging this time." to "Bu sefer beni yarı yolda bırakma.",
    "Where'd you get that ride?" to "O arabayı nereden buldun?",
    "Change of plans." to "Plan değişti.",
    "Easy does it." to "Yavaş, dikkatli ol.",
    "He's got a point." to "Bir bakıma haklı.",
    "He's all talk." to "O anca konuşur.",
    "Don't let the door close." to "Kapının kapanmasına izin verme.",
    "Who cut the power?" to "Elektriği kim kesti?",
    "This isn't what I signed up for." to "Kabul ettiğim iş bu değildi.",
    "[glass breaks]" to "[cam kırılır]",
    "Continue" to "Devam et",
    "Settings" to "Ayarlar",
    "Resume" to "Oyuna dön",
    "New game" to "Yeni oyun",
    "Load game" to "Oyun yükle",
    "Tracked missions" to "Takip edilen görevler",
    "Game saved" to "Oyun kaydedildi",
    "Saving…" to "Kaydediliyor…",
    "Mission complete" to "Görev tamamlandı",
    "Return to title screen" to "Başlangıç ekranına dön",
    "Reload checkpoint" to "Son kontrol noktasını yükle",
    "Exit to desktop" to "Masaüstüne dön",
    "Audio settings" to "Ses ayarları",
    "Aim assist" to "Nişan yardımı",
    "Controls" to "Kontroller",
    "Wanted level" to "Aranma seviyesi",
    "Fast travel" to "Hızlı seyahat",
    "Health bar" to "Can çubuğu",
).mapKeys { (source, _) -> checkNotNull(PackKey.key(source)) }

private val menuHeaders = mapOf(
    "Inventory" to "Envanter",
    "Save slot" to "Kayıt yuvası",
    "Display resolution" to "Ekran çözünürlüğü",
    "Objective" to "Hedef",
).mapKeys { (source, _) -> checkNotNull(PackKey.key(source)) }

private val checkpointJump = Regex("""jump to checkpoint (\d+)""", RegexOption.IGNORE_CASE)
private val displaySize = Regex("""\d+(?:\s*[x×X]\s*\d+){1,2}""")
private val controllerAction = Regex(
    """(press and hold|hold|press|tap) ([LR][1-3]) to (aim|accelerate|sprint)([.!]?)""",
    RegexOption.IGNORE_CASE,
)

private fun controllerHint(line: String): String? {
    val match = controllerAction.matchEntire(line) ?: return null
    val (action, key, goal, ending) = match.destructured
    val button = key.uppercase(Locale.ROOT)
    val purpose = when (goal.lowercase(Locale.ROOT)) {
        "aim" -> "Nişan almak"
        "accelerate" -> "Hızlanmak"
        else -> "Depar atmak"
    }
    val command = if (action.endsWith("hold", ignoreCase = true)) {
        val suffix = when (button.last()) {
            '2' -> "yi"
            '3' -> "ü"
            else -> "i"
        }
        "$button'$suffix basılı tut"
    } else {
        val suffix = if (button.endsWith('2')) "ye" else "e"
        "$button'$suffix bas"
    }
    return "$purpose için $command$ending"
}

private fun idiom(phrase: String, replacement: String): Pair<Regex, (MatchResult) -> String> =
    Regex("""\b$phrase\b""", RegexOption.IGNORE_CASE) to { replacement }

private val objectPronouns = mapOf(
    "my" to "me", "your" to "you", "his" to "him", "her" to "her", "our" to "us", "their" to "them",
)

private val idioms = listOf(
    idiom("sit this one out", "skip this one"),
    idiom("sit the next round out", "skip the next round"),
    idiom("stick around(?=[.!?,]|$| until | and | for | to )", "stay here"),
    idiom("(?:it's|it is|that's|that is) a long shot", "it is unlikely to work"),
    idiom("don't read too much into", "don't give too much meaning to"),
    idiom("(?:didn't|did not) add up", "did not make sense"),
    idiom("reading me loud and clear", "hearing me clearly"),
    idiom("in the middle of nowhere", "far from any town"),
    idiom("turned the power off", "cut off the electricity"),
    idiom("has power but no running water", "has electricity but no water from the taps"),
    idiom("alley behind the laundry", "alley behind the laundromat"),
    idiom("halfway down the block", "near the middle of the street"),
    Regex("""^park facing the (exit|entrance)\b""", RegexOption.IGNORE_CASE) to
        { match: MatchResult -> "park the vehicle facing the ${match.groupValues[1]}" },
    Regex("""\b(i|you|we|they|he|she) read too much into\b""", RegexOption.IGNORE_CASE) to
        { match: MatchResult -> "${match.groupValues[1]} gave too much meaning to" },
    Regex("""\b(i|you|we|they|he|she) could use (a hand|a break)\b""", RegexOption.IGNORE_CASE) to
        { match: MatchResult ->
            val (subject, expression) = match.destructured
            val verb = if (subject.lowercase(Locale.ROOT) in listOf("he", "she")) "needs" else "need"
            val complement = if (expression.equals("a hand", ignoreCase = true)) "some help" else "a short break"
            "$subject $verb $complement"
        },
    Regex("""\bpin (it|this) on (you|him|her|me|us|them)\b""", RegexOption.IGNORE_CASE) to
        { match: MatchResult -> "blame ${match.groupValues[2]} for ${match.groupValues[1]}" },
    Regex("""\bget under (my|your|his|her|our|their) skin\b""", RegexOption.IGNORE_CASE) to
        { match: MatchResult -> "annoy " + objectPronouns.getValue(match.groupValues[1].lowercase(Locale.ROOT)) },
    Regex("""\b(?:come|came) through for (me|you|him|her|us|them)\b""", RegexOption.IGNORE_CASE) to
        { match: MatchResult ->
            val verb = if (match.value.startsWith("came", ignoreCase = true)) "helped" else "help"
            "$verb ${match.groupValues[1]}"
        },
    Regex("""\bgive (me|you|him|her|us|them) the benefit of the doubt\b""", RegexOption.IGNORE_CASE) to
        { match: MatchResult -> "trust ${match.groupValues[1]} for now" },
    Regex("""\bin over (my|your|his|her|our|their) heads?\b""", RegexOption.IGNORE_CASE) to
        { match: MatchResult -> "in a situation too difficult for " + objectPronouns.getValue(match.groupValues[1].lowercase(Locale.ROOT)) },
    Regex("""\blet (me|you|him|her|us|them) off the hook this time\b""", RegexOption.IGNORE_CASE) to
        { match: MatchResult -> "let ${match.groupValues[1]} go without punishment this time" },
    Regex("""\b(?:pull in|pull into) at the (first|second|next) lot\b""", RegexOption.IGNORE_CASE) to
        { match: MatchResult -> "drive into the ${match.groupValues[1]} parking area" },
    Regex("""\b(first|second|third|next) set of lights\b""", RegexOption.IGNORE_CASE) to
        { match: MatchResult -> "${match.groupValues[1]} traffic lights" },
    Regex("""\btake gate ([a-z]?\d+)\b""", RegexOption.IGNORE_CASE) to
        { match: MatchResult -> "go through gate ${match.groupValues[1]}" },
    Regex("""\bwe (?:only )?have less than (\d+) (seconds?|minutes?|hours?) before\b""", RegexOption.IGNORE_CASE) to
        { match: MatchResult -> "there are fewer than ${match.groupValues[1]} ${match.groupValues[2]} left before" },
    idiom("keep the engine running", "keep the engine switched on"),
    idiom("get our story straight", "agree on what to say"),
    Regex("""\b((?:the|this|our|their) deal) fell through\b""", RegexOption.IGNORE_CASE) to
        { match: MatchResult -> match.groupValues[1] + " failed" },
    directionalPassage to { match: MatchResult -> "go through the ${match.groupValues[2]} on the ${match.groupValues[1]}" },
    Regex("""\b(?:go|head|turn) (left|right)(?= (?:after|at) (?:the |that |next )?(?:intersection|junction|traffic lights?|roundabout|corner|bridge)\b)""", RegexOption.IGNORE_CASE) to
        { match: MatchResult -> "turn to the " + match.groupValues[1] },
    Regex("""\b((?:I|we|you) (?:don't|do not)) buy ((?:that|this|his|her|their|your) story)\b""", RegexOption.IGNORE_CASE) to
        { match: MatchResult -> match.groupValues[1] + " believe " + match.groupValues[2] },
    Regex("""\bblow (my|your|his|her|our|their) cover\b""", RegexOption.IGNORE_CASE) to
        { match: MatchResult -> "reveal " + match.groupValues[1] + " real identity" },
    idiom("shoot (?:my|your|his|her|our|their) way out", "shoot to escape"),
    idiom("(?:lay|lie) low", "stay hidden"),
    idiom("until the heat dies down", "until things calm down"),
    idiom("until things cool off", "until things calm down"),
    idiom("pull over", "stop by the side of the road"),
    idiom("county line", "county boundary"),
    idiom("lose the cops", "escape from the police"),
    idiom("lost the cops", "escaped from the police"),
    idiom("getaway car", "escape vehicle"),
    idiom("ditch the car", "leave the car behind"),
    idiom("leave me hanging", "let me down"),
    idiom("quit messing around", "stop wasting time"),
    idiom("keep your eyes on the road", "watch the road"),
    idiom("keep an eye on", "watch"),
    idiom("we've got to move(?=\\s*(?:[.!?,…]|$))", "we have to leave now"),
    idiom("i thought you said(?: that)?(?= the | he | she | you | we | it | they | this | i )", "I thought you told me that"),
    idiom("wait by the car", "wait next to the car"),
    idiom("wait by the elevator", "wait next to the elevator"),
    idiom("out of the woods yet", "safe yet"),
    Regex("""\bthe coast (is|was) clear\b""", RegexOption.IGNORE_CASE) to
        { match: MatchResult -> "it " + match.groupValues[1] + " safe to go" },
    idiom("be in the clear", "be safe"),
    idiom("bleed out", "die from blood loss"),
    idiom("bleeds out", "dies from blood loss"),
    idiom("bled out", "died from blood loss"),
    idiom("take the service stairs", "use the service stairs"),
    idiom("hear him out", "listen until he finishes speaking"),
    idiom("hear her out", "listen until she finishes speaking"),
    idiom("hear them out", "listen until they finish speaking"),
    idiom("the alley gets narrow up ahead", "the alley gets narrower as you go ahead"),
    idiom("we haven't lost the cops yet", "the police are still chasing us"),
    idiom("he said he'd meet us", "he said he would meet us"),
    idiom("turn around before you reach (?=(?:route|highway) \\d)", "go back the way you came before you reach "),
    Regex("""\bdrop (?:off )?((?:the |this |that )?(?:cash|money))(?= at )\b""", RegexOption.IGNORE_CASE) to
        { match: MatchResult -> "leave " + match.groupValues[1] },
    Regex("""\b(?:leave|keep) (the (?:[a-z-]+ ){0,2}(?:door|gate|window)) unlocked\b""", RegexOption.IGNORE_CASE) to
        { match: MatchResult -> "make sure " + match.groupValues[1] + " isn't locked" },
    Regex("""\bkeep (\$[\d,.]+) for (gas|fuel)\b""", RegexOption.IGNORE_CASE) to
        { match: MatchResult -> "set aside " + match.groupValues[1] + " for " + match.groupValues[2] },
    Regex("""\bif you hit (the bridge|the highway|the border)(?=,? you(?:'ve| have) gone too far)\b""", RegexOption.IGNORE_CASE) to
        { match: MatchResult -> "if you reach " + match.groupValues[1] },
    Regex("""\b(?:take|taking) (?:the )?(\d+) bus\b""", RegexOption.IGNORE_CASE) to
        { match: MatchResult ->
            val verb = if (match.value.startsWith("taking", ignoreCase = true)) "riding" else "ride"
            "$verb bus number " + match.groupValues[1]
        },
    Regex("""\btake (?:the )?(first|second|third|next) (left|right)\b""", RegexOption.IGNORE_CASE) to
        { match: MatchResult -> "turn " + match.groupValues[2] + " at the " + match.groupValues[1] + " junction" },
    Regex("""\btipped (them|him|her|us|you|me) off\b""", RegexOption.IGNORE_CASE) to
        { match: MatchResult -> "secretly warned " + match.groupValues[1] },
    Regex("""\b(?:cut|cut off) the power\b""", RegexOption.IGNORE_CASE) to
        { _: MatchResult -> "cut off the electricity" },
    Regex("""\bthe driver gets out,? then move in\b""", RegexOption.IGNORE_CASE) to
        { _: MatchResult -> "the driver leaves the vehicle, then approach" },
    Regex("""\b((?:drag|move|pull|push) (it|this|that|him|her|them)) out of sight\b""", RegexOption.IGNORE_CASE) to
        { match: MatchResult -> match.groupValues[1] + " somewhere nobody can see " + match.groupValues[2] },
    Regex("""\bdon't let (the (?:door|gate|window)) (open|close)\b""", RegexOption.IGNORE_CASE) to
        { match: MatchResult ->
            val action = if (match.groupValues[2].equals("open", ignoreCase = true)) "opening" else "closing"
            "prevent " + match.groupValues[1] + " from $action"
        },
    Regex("""\b(is|was|are|were) unlocked\b""", RegexOption.IGNORE_CASE) to { match: MatchResult ->
        match.groupValues[1] + " not locked"
    },
)
