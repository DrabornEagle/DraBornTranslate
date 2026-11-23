// SPDX-License-Identifier: AGPL-3.0-only
package com.gametranslator.gametranslator_mobile.core

import java.util.Locale

/**
 * Oyunların tuş çubuğundaki ve etkileşim istemlerindeki kısa komutların ("OPEN ✕", "PUT DOWN ○", "SET DESTINATION ✕")
 * Türkçesi (Windows: UiPrompts.cs, aynı sözlük). Çeviri motoru bu tek sözcükleri bağlamsız görünce yanlış anlıyordu
 * ("OPEN" → "AÇIK", "DRINK" → "İÇECEK") ya da hiç çevirmiyordu; sözlükteki komut motora gitmez, tuş simgeleri yerinde
 * kalır. Ayrıca motorun düşürdüğü tuş simgeleri çeviriye geri konur ([keepIcons]).
 */
object UiPrompts {
    private val commands: Map<String, String> = mapOf(
        "ADD MARKER" to "İşaret ekle", "SET DESTINATION" to "Hedef belirle", "REMOVE DESTINATION" to "Hedefi kaldır",
        "RECENTER" to "Ortala", "2D VIEW" to "2B görünüm", "3D VIEW" to "3B görünüm", "ZOOM" to "Yakınlaştır",
        "EXIT" to "Çıkış", "BACK" to "Geri", "SELECT" to "Seç", "CLOSE" to "Kapat", "CLOSE MENU" to "Menüyü kapat",
        "MENU" to "Menü", "OPEN" to "Aç", "NEXT PAGE" to "Sonraki sayfa", "PREVIOUS PAGE" to "Önceki sayfa",
        "SHOW TEXT" to "Metni göster", "SHOW" to "Göster", "FLIP" to "Çevir", "PUT DOWN" to "Bırak", "PICK UP" to "Al",
        "DRINK" to "İç", "EAT" to "Ye", "WEAR" to "Giy", "TAKE OFF" to "Çıkar", "BUY" to "Satın al", "SELL" to "Sat",
        "PAY" to "Öde", "PAY LATER" to "Sonra öde", "PAY NOW" to "Şimdi öde", "ACCEPT" to "Kabul et",
        "DECLINE" to "Reddet", "YES" to "Evet", "NO" to "Hayır", "CONFIRM" to "Onayla", "CANCEL" to "İptal",
        "GOODBYE" to "Hoşça kal", "GREET" to "Selamla", "TALK" to "Konuş", "DEFUSE" to "Yatıştır",
        "PROVOKE" to "Kışkırt", "WARN" to "Uyar", "OFFER RIDE" to "Arabaya çağır", "ENTER" to "Gir",
        "LEAVE" to "Ayrıl", "SKIP" to "Geç", "CONTINUE" to "Devam et", "RETRY" to "Tekrar dene",
        "RESTORE ALL" to "Hepsini geri yükle", "RESTORE DEFAULT" to "Varsayılana döndür", "PREVIEW" to "Önizle",
        "PAUSE PREVIEW" to "Önizlemeyi durdur", "ADJUST" to "Ayarla", "SCROLL" to "Kaydır", "REFUEL" to "Yakıt al",
        "LIFT" to "Kaldır", "ORDER" to "Sipariş ver", "MAYBE LATER" to "Belki sonra", "PHOTO STUDIO" to "Fotoğraf stüdyosu",
        "REMINDERS" to "Hatırlatıcılar", "ROTATE" to "Döndür", "INTERACT" to "Etkileşim", "USE" to "Kullan",
        "KNOCK" to "Kapıyı çal", "CALL" to "Ara", "HANG UP" to "Telefonu kapat", "ANSWER" to "Cevapla",
        "IGNORE" to "Yoksay", "REPLY" to "Yanıtla", "DELETE" to "Sil", "SAVE" to "Kaydet", "VIEW" to "Görüntüle",
        "INSPECT" to "İncele", "SEARCH" to "Ara", "HIDE" to "Saklan", "JOIN" to "Katıl", "TERMS" to "Koşullar",
        "MARKERS" to "İşaretler", "DROP" to "Bırak", "EQUIP" to "Kuşan", "UNEQUIP" to "Çıkar", "STORE" to "Dükkân",
        "HELP" to "Yardım", "OPTIONS" to "Seçenekler", "CAMERA" to "Kamera", "TOGGLE" to "Aç / kapat", "GRAB" to "Tut",
        "SIT" to "Otur", "STAND UP" to "Kalk", "SLEEP" to "Uyu", "SHOWER" to "Duş al", "TURN OFF" to "Kapat",
        "TURN ON" to "Aç", "WATCH" to "İzle", "LISTEN" to "Dinle", "DRIVE" to "Sür", "GET IN" to "Bin",
        "GET OUT" to "İn", "STEAL" to "Çal", "HACK" to "Hackle", "LOOT" to "Yağmala", "COLLECT" to "Topla",
    )

    private val lowerShoulder = Regex("\\b([lr])([123])\\b")

    /** Sözcükler tuş çubuğu sözlüğündeki bir komut mu ("Talk", "BACK", "Pick up"; büyük-küçük harf fark etmez). */
    fun isCommand(words: String): Boolean = commands.containsKey(words.trim().uppercase(Locale.ROOT))

    private fun tokens(text: String) = text.split(' ').filter { it.isNotEmpty() }

    /**
     * Tuş çubuğundaki komutun Türkçesi: büyük harfli, simgesi bir yanında duran ("OPEN ✕", "✕ OPEN") ya da tek başına
     * büyük harfli komut ("REFUEL"). Sözlükte yoksa null.
     */
    fun translate(paragraph: String): String? {
        val tokens = tokens(paragraph)
        if (tokens.isEmpty()) return null
        var start = 0
        var end = tokens.size
        while (start < end && ButtonPrompts.isIcon(tokens[start])) start++
        while (end > start && ButtonPrompts.isIcon(tokens[end - 1])) end--
        if (end <= start) return null
        val words = tokens.subList(start, end).joinToString(" ").trim('.', ',', ':', '!', '?')
        // Yalnız büyük harfli komut: sıradan cümledeki "open" / "Back" sözlükle değişmez.
        if (words.none { it.isLetter() } || words != words.uppercase(Locale.ROOT)) return null
        val turkish = commands[words] ?: return null
        val before = tokens.subList(0, start).joinToString(" ")
        val after = tokens.subList(end, tokens.size).joinToString(" ")
        return listOf(before, turkish, after).filter { it.isNotEmpty() }.joinToString(" ")
    }

    /**
     * Çeviri motorunun düşürdüğü tuş simgelerini geri koyar: aslında başta duran simge başa, öteki simgeler sona
     * (sıralarıyla). Motorun küçülttüğü omuz tuşu ("l1") yeniden büyük harf olur.
     */
    fun keepIcons(source: String, translation: String): String {
        var fixedCase = lowerShoulder.replace(translation) { match -> match.groupValues[1].uppercase(Locale.ROOT) + match.groupValues[2] }
        // Xbox'ın omuz ve tetik tuşu ("LT") da küçültülebilir ("lt"); yalnız kaynakta geçen ad düzeltilir.
        for (name in tokens(source).map { it.trim('.', ',', ':') }.filter { it in ButtonTokens.xboxNames }.distinct())
            fixedCase = Regex("(?<![\\p{L}\\p{N}_])$name(?![\\p{L}\\p{N}_])", RegexOption.IGNORE_CASE).replace(fixedCase, name)
        val have = tokens(fixedCase).toMutableList()
        val leading = mutableListOf<String>()
        val trailing = mutableListOf<String>()
        var seenWord = false
        for (token in tokens(source)) {
            if (!ButtonPrompts.isIcon(token) || token == "O" || token == "X") {
                seenWord = true
                continue
            }
            if (have.remove(token)) continue
            // Omuz tuşu metin olarak kalmış olabilir ("L1"); yalnız simge gibi duranlar eklenir.
            if (seenWord) trailing.add(token) else leading.add(token)
        }
        if (leading.isEmpty() && trailing.isEmpty()) return fixedCase
        return (leading + fixedCase.trim() + trailing).joinToString(" ")
    }
}
