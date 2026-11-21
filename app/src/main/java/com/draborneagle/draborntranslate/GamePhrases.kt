// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import com.gametranslator.gametranslator_mobile.core.TextMatch
import java.util.Locale

internal object GamePhrases {
    private val dialogue = mapOf(
        "Lose the cops!" to "Polisleri atlat!",
        "We are being tailed." to "Takip ediliyoruz.",
        "We're being tailed." to "Takip ediliyoruz.",
        "They're on our tail." to "Peşimizdeler.",
        "Take cover!" to "Siper al!",
        "Get in the car!" to "Arabaya bin!",
        "Stay out of sight." to "Görünme.",
        "Hold your fire!" to "Ateş etme!",
        "Keep your head down." to "Başını kaldırma.",
        "Let's get out of here." to "Hadi buradan çıkalım.",
        "Meet me at the bridge." to "Köprüde buluşalım.",
        "I'll cover you." to "Seni korurum.",
        "I've got your back." to "Arkanı kollarım.",
        "I should've seen it coming." to "Bunun olacağını anlamalıydım.",
        "I should have seen it coming." to "Bunun olacağını anlamalıydım.",
        "You set me up." to "Bana tuzak kurdun.",
        "I owe you one." to "Sana bir iyilik borçluyum.",
        "You made it." to "Başardın.",
        "Get down!" to "Yere yat!",
        "Run the plates." to "Plakayı sorgula.",
        "Go around the back." to "Arkadan dolaş.",
        "I don't buy it." to "Buna inanmıyorum.",
        "He's got a point." to "Bir bakıma haklı.",
        "Don't leave me hanging." to "Beni yarı yolda bırakma.",
        "Don't leave me hanging this time." to "Bu sefer beni yarı yolda bırakma.",
        "We're not out of the woods yet." to "Tehlike henüz geçmedi.",
        "We're on our own." to "Başımızın çaresine bakacağız.",
        "Don't make me regret it." to "Beni pişman etme.",
        "The coast is clear." to "Ortalık temiz.",
        "Make a run for it." to "Koş, kaç!",
        "Got it." to "Anladım.",
        "I got this." to "Ben hallederim.",
        "Something's off." to "Bir terslik var.",
        "Keep still." to "Kıpırdama.",
        "Keep it down." to "Sesini alçalt.",
        "Speak up." to "Daha yüksek sesle konuş.",
        "Speak up!" to "Daha yüksek sesle konuş!",
        "Easy does it." to "Yavaş, dikkatli ol.",
        "Change of plans." to "Plan değişti.",
        "Yeah." to "Evet.",
        "No." to "Hayır.",
        "No!" to "Hayır!",
        "Yes." to "Evet.",
        "Yes!" to "Evet!",
        "What?" to "Ne?",
        "Why?" to "Neden?",
        "Huh?" to "Ha?",
        "Okay." to "Tamam.",
        "OK." to "Tamam.",
        "Thanks." to "Sağ ol.",
        "Come on!" to "Hadi!",
        "Go!" to "Git!",
        "Run!" to "Koş!",
        "Stop!" to "Dur!",
        "Help!" to "Yardım edin!",
        "Wait!" to "Bekle!",
        "Look out!" to "Dikkat et!",
        "Move!" to "Çabuk!",
        "Follow me." to "Beni takip et.",
        "Stay here." to "Burada kal.",
        "Stop here." to "Burada dur.",
        "Don't move." to "Kıpırdama.",
        "Don't shoot!" to "Ateş etme!",
        "Keep your hands where I can see them." to "Ellerini görebileceğim yerde tut.",
        "Stick with me." to "Yanımdan ayrılma.",
        "Don't let go." to "Bırakma.",
        "Don't let the door close." to "Kapının kapanmasına izin verme.",
        "Don't let the gate close." to "Kapının kapanmasına izin verme.",
        "Where'd you get that ride?" to "O arabayı nereden buldun?",
        "I couldn't have done it without you." to "Sen olmasaydın bunu başaramazdım.",
        "We're in over our heads." to "Bu iş boyumuzu aştı.",
        "We're in over our heads this time." to "Bu sefer iş boyumuzu aştı.",
        "We're running out of time." to "Vaktimiz daralıyor.",
        "We're safe." to "Güvendeyiz.",
        "We're good to go." to "Hazırız, gidebiliriz.",
        "I'll be right back." to "Hemen döneceğim.",
        "Do you copy?" to "Beni duyuyor musun?",
        "Copy that." to "Anlaşıldı.",
        "Start the engine." to "Motoru çalıştır.",
        "Get the engine started." to "Motoru çalıştır.",
        "Cut the engine." to "Motoru kapat.",
        "I couldn't hear you over the engine." to "Motorun sesinden seni duyamadım.",
        "I can't hear you over the engine." to "Motorun sesinden seni duyamıyorum.",
        "They've got the wrong guy." to "Yanlış kişiyi yakaladılar.",
    ).mapKeys { (english, _) -> key(english) }

    private val menu = mapOf(
        "aim assist" to "Nişan yardımı",
        "apply changes" to "Değişiklikleri uygula",
        "audio" to "Ses",
        "back" to "Geri",
        "checkpoint" to "Kontrol noktası",
        "continue" to "Devam et",
        "controller" to "Kumanda",
        "controls" to "Kontroller",
        "difficulty" to "Zorluk",
        "display" to "Görüntü",
        "display resolution" to "Ekran çözünürlüğü",
        "exit" to "Çıkış",
        "exit to desktop" to "Masaüstüne dön",
        "exit to main menu" to "Ana menüye dön",
        "fast travel" to "Hızlı seyahat",
        "game saved" to "Oyun kaydedildi",
        "graphics" to "Grafikler",
        "health bar" to "Can çubuğu",
        "hold r2 to accelerate." to "Hızlanmak için R2'yi basılı tut.",
        "hold r2 to accelerate" to "Hızlanmak için R2'yi basılı tut",
        "hold to skip" to "Atlamak için basılı tut",
        "inventory" to "Envanter",
        "language" to "Dil",
        "load game" to "Oyun yükle",
        "map" to "Harita",
        "mission complete" to "Görev tamamlandı",
        "mission failed" to "Görev başarısız",
        "missions" to "Görevler",
        "new game" to "Yeni oyun",
        "new game+" to "Yeni Oyun+",
        "new objective" to "Yeni hedef",
        "no" to "Hayır",
        "objective" to "Hedef",
        "objectives" to "Hedefler",
        "off" to "Kapalı",
        "on" to "Açık",
        "pause menu" to "Duraklatma menüsü",
        "photo mode" to "Fotoğraf modu",
        "reload checkpoint" to "Son kontrol noktasını yükle",
        "restart mission" to "Görevi yeniden başlat",
        "resume" to "Oyuna dön",
        "return to title screen" to "Başlangıç ekranına dön",
        "save game" to "Oyunu kaydet",
        "save point" to "Kayıt noktası",
        "save slot" to "Kayıt yuvası",
        "saving…" to "Kaydediliyor…",
        "settings" to "Ayarlar",
        "side mission" to "Yan görev",
        "subtitles" to "Altyazılar",
        "tracked missions" to "Takip edilen görevler",
        "wanted level" to "Aranma seviyesi",
        "weapons" to "Silahlar",
        "yes" to "Evet",
    )
    private val checkpointJump = Regex("jump to checkpoint (\\d+)", RegexOption.IGNORE_CASE)
    private val controllerSprint = Regex("(?:tap|press) ([LR][1-3]) to sprint([.!]?)", RegexOption.IGNORE_CASE)

    fun direct(text: String, area: ReadingArea): String? {
        if (area == ReadingArea.SUBTITLES) {
            val line = key(text)
            return dialogue[line] ?: if (line.lastOrNull()?.isLetterOrDigit() == true) {
                (dialogue["$line."] ?: dialogue["$line!"])?.trimEnd('.', '!')
            } else null
        }
        controllerSprint.matchEntire(text)?.let { match ->
            val button = match.groupValues[1].uppercase(Locale.ROOT)
            val ending = if (button.endsWith('2')) "ye" else "e"
            return "Depar atmak için $button'$ending bas${match.groupValues[2]}"
        }
        return menu[key(text)] ?: checkpointJump.matchEntire(text.trim())?.let {
            "${it.groupValues[1]}. kontrol noktasına geç"
        }
    }

    private fun key(text: String) = TextMatch.literal(text).lowercase(Locale.ROOT)

    // Rewrite expressions with a specific figurative meaning in game dialogue before running the model.
    private val idioms = listOf(
        "((?:I|We|You) (?:don't|do not)) buy (that story|this story|your story|his story|her story|that excuse)" to "$1 believe $2",
        "blow (my|your|his|her|our|their) cover" to "reveal $1 real identity",
        "shoot (?:my|your|his|her|our|their) way out" to "shoot to get out",
        "((?:they're|they are|he's|he is|she's|she is) (?:not )?)after (me|you|him|her|us|them)" to "$1trying to catch $2",
        "cover for (me|us)(?= until (?:i|we) (?:get|come) back)" to "take the place of $1",
        "don't hang up(?=[.!?…]|$)" to "don't end the phone call",
        "before anyone gets hurt" to "before someone is injured",
        "lay low" to "stay hidden",
        "until the heat dies down" to "until things calm down",
        "until things cool off" to "until things calm down",
        "pull over" to "stop by the side of the road",
        "county line" to "county boundary",
        "lose the cops" to "get away from the police",
        "lost the cops" to "got away from the police",
        "getaway car" to "escape vehicle",
        "ditch the car" to "leave the car",
        "leave (me|us) hanging" to "let $1 down",
        "quit messing around" to "stop wasting time",
        "keep your eyes on the road" to "watch the road ahead",
        "keep an eye on" to "watch",
        "we've got to move(?=[,.!?…]|$)" to "we have to leave now",
        "^meet me (outside|inside|in front of|at|by|near|in|on) " to "Let's meet $1 ",
        "i thought you said(?: that)?(?= (?:the|he|she|you|we|it|they|this|i) )" to "I thought you told me that",
        "(is|was|are|were) unlocked(?! by)" to "$1 not locked",
        "wait by the (car|elevator|door)" to "wait next to the $1",
        "out of the woods yet" to "safe yet",
        "in over (?:my|your|his|her|our|their) heads?" to "in a situation too difficult to handle",
        "if you hit (the bridge|the highway|the border)(?=,? you(?:'ve| have) gone too far)" to "if you reach $1",
        "take (?:the )?(first|second|third|next) (left|right)" to "turn $2 at the $1 junction",
        "the (\\d+) bus" to "bus number $1",
        "bleed out" to "bleed to death",
        "bleeds out" to "bleeds to death",
        "bled out" to "bled to death",
        "bleeding out" to "bleeding to death",
        "tipped (them|him|her|us|you|me) off" to "secretly warned $1",
        "cut (?:off )?the power" to "cut off the electricity",
        "((?:can't|cannot|couldn't|could not) hear (?:you|me|him|her|them|it)) over the (?:sound of the )?engine" to
            "$1 because of the engine noise",
        "((?:drag|move|pull|push) (it|this|that|him|her|them)) out of sight" to "$1 somewhere nobody can see $2",
        "turn around (?=before you reach (?:route|highway|exit)\\b)" to "turn back ",
    ).map { (pattern, replacement) -> Regex("\\b${pattern}\\b", RegexOption.IGNORE_CASE) to replacement }

    private val menuPrompt = Regex("^hold (?=(?:[LR][1-3]|[△○×□]|the (?:button|trigger))\\b)", RegexOption.IGNORE_CASE)
    private val controllerTap = Regex("^tap (?=[LR][1-3]\\b)", RegexOption.IGNORE_CASE)
    private val groupReference = Regex("\\$([1-9])")
    private val recipientBeforeLeaving = Regex(
        """^what did you say to ([\p{L} '-]{1,50}) before (he|she|they) left([?!.]?)$""",
        RegexOption.IGNORE_CASE,
    )

    fun contextualize(text: String, area: ReadingArea): String {
        if (area == ReadingArea.GAME_TEXT) {
            return menuPrompt.replace(controllerTap.replace(text, "Press "), "Press and hold ")
        }
        recipientBeforeLeaving.matchEntire(text)?.let { match ->
            val (recipient, person, end) = match.destructured
            return "Before $person left, what did you tell $recipient$end"
        }
        return idioms.fold(text) { current, (phrase, replacement) ->
            phrase.replace(current) { match ->
                val rewritten = groupReference.replace(replacement) { group ->
                    match.groupValues[group.groupValues[1].toInt()]
                }
                rewritten.replaceFirstChar { if (match.value.first().isUpperCase()) it.uppercaseChar() else it }
            }
        }
    }
}
