// SPDX-License-Identifier: AGPL-3.0-only
package com.gametranslator.gametranslator_mobile.core

import java.util.Locale

/**
 * Oyunlarda sık geçen kısa ünlemlerin İngilizce→Türkçe karşılıkları (Windows: ShortPhrases.cs, aynı tablo ve kurallar).
 * Bağlamsız kısa satırı çevirmenler sözlük anlamıyla çeviriyor: "Well?" → "Kuyu?", "See?" → "Görmek?", "Brother?" →
 * "Erkek kardeş?", "Hurry!" → "Acele etmek!", "Okay." → "Tamam aşkım.", "Morning!" → "Sabah!". Satırın her cümlesi
 * tablodaysa satır buradan çevrilir; biri bile değilse satırın tamamı her zamanki gibi çevirmene gider (yarı yarıya
 * çeviri yapılmaz). Cümlenin sonundaki noktalama kaynaktaki gibi kalır. Oyuna özgü değildir.
 */
object ShortPhrases {
    private val turkish = Locale("tr", "TR")

    /** Cümle sonu "?" ise kullanılan karşılık önce, değilse ikinci. Anahtarlar küçük harflidir. */
    private val table: Map<String, Pair<String, String>> = mapOf(
        "okay" to ("Tamam mı" to "Tamam"), "ok" to ("Tamam mı" to "Tamam"), "alright" to ("Tamam mı" to "Peki"),
        "all right" to ("Tamam mı" to "Peki"), "fine" to ("Peki" to "Peki"), "good" to ("İyi mi" to "Güzel"),
        "yes" to ("Evet mi" to "Evet"), "yeah" to ("Öyle mi" to "Evet"), "yep" to ("Evet" to "Evet"), "yup" to ("Evet" to "Evet"),
        "no" to ("Hayır mı" to "Hayır"), "nope" to ("Hayır" to "Hayır"), "nah" to ("Yok" to "Yok"),
        "sure" to ("Emin misin" to "Tabii"), "of course" to ("Elbette" to "Elbette"), "right" to ("Değil mi" to "Doğru"),
        "well" to ("Ee" to "Şey"), "see" to ("Gördün mü" to "Gördün mü"), "huh" to ("Ha" to "Ha"), "what" to ("Ne" to "Ne"),
        "why" to ("Neden" to "Neden"), "how" to ("Nasıl" to "Nasıl"), "where" to ("Nerede" to "Nerede"), "who" to ("Kim" to "Kim"),
        "really" to ("Gerçekten mi" to "Gerçekten"), "what now" to ("Şimdi ne olacak" to "Şimdi ne olacak"),
        "wait" to ("Bekle" to "Bekle"), "hurry" to ("Acele et" to "Acele et"), "hurry up" to ("Acele et" to "Acele et"),
        "come" to ("Gel" to "Gel"), "come on" to ("Hadi" to "Hadi"), "come here" to ("Buraya gel" to "Buraya gel"),
        "go" to ("Git" to "Git"), "run" to ("Koş" to "Koş"), "stop" to ("Dur" to "Dur"), "look" to ("Bak" to "Bak"),
        "listen" to ("Dinle" to "Dinle"), "help" to ("İmdat" to "İmdat"), "move" to ("Çekil" to "Çekil"),
        "get down" to ("Eğil" to "Eğil"), "look out" to ("Dikkat et" to "Dikkat et"), "watch out" to ("Dikkat et" to "Dikkat et"),
        "let's go" to ("Gidelim mi" to "Hadi gidelim"), "quiet" to ("Sessiz ol" to "Sessiz ol"), "enough" to ("Yeter" to "Yeter"),
        "behind you" to ("Arkanda" to "Arkanda"), "got it" to ("Anladın mı" to "Anladım"), "understood" to ("Anlaşıldı mı" to "Anlaşıldı"),
        "thanks" to ("Teşekkürler" to "Teşekkürler"), "thank you" to ("Teşekkür ederim" to "Teşekkür ederim"),
        "sorry" to ("Pardon" to "Özür dilerim"), "please" to ("Lütfen" to "Lütfen"), "hello" to ("Merhaba" to "Merhaba"),
        "hi" to ("Selam" to "Selam"), "hey" to ("Hey" to "Hey"), "morning" to ("Günaydın" to "Günaydın"),
        "good morning" to ("Günaydın" to "Günaydın"), "good night" to ("İyi geceler" to "İyi geceler"), "bye" to ("Hoşça kal" to "Hoşça kal"),
        "damn" to ("Lanet olsun" to "Lanet olsun"), "damn it" to ("Kahretsin" to "Kahretsin"), "shit" to ("Kahretsin" to "Kahretsin"),
        "oh" to ("Ah" to "Ah"), "ah" to ("Ah" to "Ah"), "hmm" to ("Hmm" to "Hmm"), "uh" to ("Ee" to "Ee"),
        "brother" to ("Kardeşim" to "Kardeşim"), "father" to ("Baba" to "Baba"), "mother" to ("Anne" to "Anne"),
        "dad" to ("Baba" to "Baba"), "mom" to ("Anne" to "Anne"), "boy" to ("Evlat" to "Evlat"), "kid" to ("Evlat" to "Evlat"),
        "sir" to ("Efendim" to "Efendim"), "boss" to ("Patron" to "Patron"),
        // Ara sahne düğmesi: "Skip" → "Atlamak" çıkıyordu.
        "skip" to ("Geç" to "Geç"),
        // Telsiz dili: "Over!" → "Üzerinde!", "Roger that" → "Roger bu" çıkıyordu.
        "over" to ("Tamam" to "Tamam"), "over and out" to ("Tamam, kapatıyorum" to "Tamam, kapatıyorum"),
        "roger" to ("Anlaşıldı" to "Anlaşıldı"), "roger that" to ("Anlaşıldı" to "Anlaşıldı"),
        "copy" to ("Duydun mu" to "Anlaşıldı"), "copy that" to ("Anlaşıldı" to "Anlaşıldı"),
    )

    private val radioTail = Regex("^(?<main>.*\\S[.!?…])\\s+(?<tail>(?:Over|Over and out)[.!]*)$")
    private val sentenceBreak = Regex("(?<=[.!?…])\\s+")
    private val parts = Regex("^(?<lead>[.…—\\-\\s]*)(?<body>.*?)(?<end>[.!?…,]*)$")

    /**
     * Telsiz konuşmasının sonundaki "Over!" ayrılır: ("Base, we got him!", "Tamam!"). Çevirmen onu cümleyle birlikte
     * "Üzerinde!" diye çeviriyordu. Sonunda yoksa null.
     */
    fun splitRadioTail(text: String): Pair<String, String>? {
        val match = radioTail.find(text.trim()) ?: return null
        val main = match.groups["main"]!!.value
        if (main.count { it.isLetter() } < 3) return null
        return main to (translate(match.groups["tail"]!!.value) ?: "Tamam.")
    }

    /** Satırın Türkçesi (her cümlesi tablodaysa); değilse null. */
    fun translate(input: String): String? {
        val text = input.trim()
        if (text.isEmpty() || text.length > 40 || text.contains('\n')) return null
        val result = mutableListOf<String>()
        for (sentence in text.split(sentenceBreak)) {
            val match = parts.find(sentence) ?: return null
            val body = match.groups["body"]!!.value.trim().replace('’', '\'')
            val end = match.groups["end"]!!.value
            val pair = table[body.lowercase(Locale.ROOT)] ?: return null
            val phrase = if (end.contains('?')) pair.first else pair.second
            // "Tamam mı?" gibi karşılık soru ekini içeriyor; kaynakta soru işareti yoksa bile soru olarak kalır.
            result.add(match.groups["lead"]!!.value.trim() + phrase + end)
        }
        return result.joinToString(" ")
    }

    /**
     * Kaynak normal yazılmışken çevirmenin bütünüyle büyük harfle döndürdüğü çeviri ("No..." → "HAYIR...") normal
     * yazıma çevrilir: küçük harf, ilk harf büyük. Kaynak da büyük harfle yazılmışsa (bağırma, başlık) dokunulmaz.
     */
    fun fixShouting(source: String, translation: String): String {
        val sourceLetters = source.filter { it.isLetter() }
        val letters = translation.filter { it.isLetter() }
        if (letters.length < 3 || !letters.all { it.isUpperCase() } || sourceLetters.isEmpty() || sourceLetters.all { it.isUpperCase() })
            return translation
        val lower = translation.lowercase(turkish)
        val first = lower.indexOfFirst { it.isLetter() }
        return lower.substring(0, first) + lower.substring(first, first + 1).uppercase(turkish) + lower.substring(first + 1)
    }
}
