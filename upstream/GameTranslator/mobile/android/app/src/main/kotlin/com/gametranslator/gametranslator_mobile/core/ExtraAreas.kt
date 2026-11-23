// SPDX-License-Identifier: AGPL-3.0-only
package com.gametranslator.gametranslator_mobile.core

/**
 * Sürekli okunan ek alanın hazır seçimleri: oyunların çoğu bu yazıları ekranın köşelerinde gösterir (Windows:
 * ExtraAreaPreset, aynı bölgeler; CouchSubs / Nino ile aynı). [key] ayar dosyasında ve arayüzde kullanılan addır.
 * Bölgeler karenin kenarlarına oranladır (sol, üst, sağ, alt).
 */
enum class ExtraAreaPreset(val key: String, val region: TextBox) {
    /** Sağ üstteki görev yazısı. */
    QUEST("quest", TextBox(0.66, 0.03, 0.98, 0.30)),
    /** Sol alttaki bildirim. */
    NOTIFICATION("notification", TextBox(0.02, 0.58, 0.30, 0.88)),
    /** Sağ alttaki telefon mesajı ya da e-posta. */
    PHONE("phone", TextBox(0.72, 0.50, 0.98, 0.95)),
    /**
     * En alttaki ortalı görev yazısı (GTA 6: altyazının hemen altında, %94–97). Sağ alttaki tuş çubuğu ("PHOTO STUDIO
     * ✕ SELECT ○ BACK") karışmasın diye alan ortada dardır.
     */
    BOTTOM_OBJECTIVE("bottom_objective", TextBox(0.28, 0.925, 0.72, 0.99)),
    /** Sol üstteki ipucu kutusu (GTA 6: "Check the map index to view…", %26–37 yükseklikte, %1–23 genişlikte). */
    HELP_BOX("help_box", TextBox(0.01, 0.22, 0.27, 0.46)),
    /**
     * Sağ alttaki telefon mesajı kartı (GTA 6: gönderenin adı ve mesaj). Sağ alttaki tuş çubuğunun başı ("PHOTO
     * STUDIO") kesilmesin diye biraz geniş.
     */
    PHONE_MESSAGE("phone_message", TextBox(0.70, 0.78, 0.995, 0.99));

    companion object {
        fun byKey(key: String?): ExtraAreaPreset? = entries.firstOrNull { it.key == key }

        /** Bölge bir hazır seçimle aynıysa o. */
        fun matching(region: TextBox): ExtraAreaPreset? = entries.firstOrNull { it.region == region }
    }
}

/**
 * Sürekli okunan ek alan (görev yazısı, telefon mesajı, bildirim). [id]: TV'deki kutunun kimliği; [name]: günlükte
 * görünen ad; [region]: karenin kenarlarına oranla.
 */
data class WatchedArea(val id: String, val name: String, val region: TextBox)

object ExtraAreas {
    /** Ayarlardaki ek alan yuvası sayısı (Windows: UserSettings.ExtraRegionCount). */
    const val SLOTS = 3

    /**
     * Sürekli okunan ek alanların içine düşen satırlar altyazı değildir (GTA 6'da altyazı şeridi %76–100, alttaki görev
     * yazısı %94–97: görev yazısı altyazı gibi de çevriliyordu). Satırın ortası bir ek alanın içindeyse atılır. Satır
     * kutuları karenin tamamına oranladır (Windows: AppController.WithoutWatchedAreas).
     */
    fun withoutWatchedAreas(lines: List<TextLine>, watched: List<WatchedArea>): List<TextLine> {
        if (watched.isEmpty() || lines.isEmpty()) return lines
        val kept = lines.filter { line ->
            val box = line.box ?: return@filter true
            val cx = (box.left + box.right) / 2
            val cy = (box.top + box.bottom) / 2
            watched.none { w -> cx >= w.region.left && cx <= w.region.right && cy >= w.region.top && cy <= w.region.bottom }
        }
        return if (kept.size == lines.size) lines else kept
    }

    /** GTA 6 mı oynanıyor: PS5'in bildirdiği oyun adı ya da eşleşen paketin kimliği / adı. */
    fun isGta6(runningGame: String?, packGame: String?): Boolean {
        val names = listOfNotNull(runningGame, packGame).map { it.lowercase(java.util.Locale.ROOT) }
        return names.any { it.contains("grand theft auto vi") || it.contains("gta vi") || it.contains("gta 6") || it == "gta-6" || it.startsWith("gta-6-") || it == "gta6" }
    }

    /**
     * GTA 6'da alttaki görev yazısı ("Speak to Cal", "Drive the boat") ek alan açık olmasa da altyazıdan çıkarılır
     * (çevrilmez, yalnız altyazıya karışmaz). iOS'taki GTNative ile aynı kural.
     */
    fun withoutGta6Objective(lines: List<TextLine>, gta6: Boolean): List<TextLine> =
        if (!gta6) lines else withoutWatchedAreas(lines, listOf(WatchedArea("gta6-objective", "GTA 6 görev yazısı", ExtraAreaPreset.BOTTOM_OBJECTIVE.region)))

    /**
     * Ek alanda çevrilmeyecek satır: altyazının alana düşen satırı (konuşmacı adlı ya da o an gösterilen altyazıya
     * benzeyen) ve uzun altyazı satırının alana taşan ucu ("ot it?", "nd..."). [shownSource]: son gösterilen
     * altyazının aslı (yoksa null). Windows: AppController.UpdateRegionWatcher içindeki SkipLine.
     */
    fun skipLine(text: String, shownSource: String?): Boolean {
        if (hasSpeakerLabel(text)) return true
        if (shownSource.isNullOrEmpty()) return false
        val shown = TextMatch.normalize(shownSource)
        val part = TextMatch.normalize(text)
        return TextMatch.isSimilar(shown, part, 0.8) || (part.length >= 3 && shown.contains(part))
    }

    /** Satır "Ad: söz" biçiminde mi (ad büyük harfle başlar, en çok üç sözcüktür). */
    fun hasSpeakerLabel(text: String): Boolean {
        val line = text.trimStart()
        val colon = line.indexOf(": ")
        if (colon !in 2..30 || !line[0].isUpperCase()) return false
        val name = line.substring(0, colon)
        return name.split(' ').size <= 3 && name.all { it.isLetter() || it == ' ' || it == '-' || it == '.' || it == '\'' || it == '’' }
    }
}
