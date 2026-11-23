// SPDX-License-Identifier: AGPL-3.0-only
package com.gametranslator.gametranslator_mobile.core

import java.util.Locale

/**
 * Arayüz dili, Android tarafı. Kullanıcıya gösterilen metinlerin çoğu Flutter'dadır (lib/l10n/); çekirdek durum ve hata
 * metinlerini [code] ile kod olarak yollar, metni Flutter seçer. Yalnız Flutter'dan geçmeden gösterilenler (bildirim,
 * deneme altyazısı, Samsung giriş sayfasının son ekranı) buradaki tablolardan gelir. Yeni bir arayüz dili için
 * [tables]'a bir tablo eklenir.
 */
object UiText {
    private val TR = mapOf(
        "notification.channel" to "Çeviri",
        "notification.title" to "GameTranslator çalışıyor",
        "notification.text" to "Oyun altyazıları çevriliyor",
        "notification.stop" to "Durdur",
        "test.subtitle" to "Bu bir deneme altyazısıdır. Oyunun üstünde görüyor musunuz?",
        "samsung.done.title" to "Giriş tamam",
        "samsung.done.text" to "GameTranslator'a dönebilirsin; kurulum kendiliğinden sürüyor.",
    )

    private val EN = mapOf(
        "notification.channel" to "Translation",
        "notification.title" to "GameTranslator is running",
        "notification.text" to "Translating game subtitles",
        "notification.stop" to "Stop",
        "test.subtitle" to "This is a test subtitle. Can you see it over the game?",
        "samsung.done.title" to "Signed in",
        "samsung.done.text" to "You can go back to GameTranslator; the installation continues on its own.",
    )

    val tables: Map<String, Map<String, String>> = mapOf("tr" to TR, "en" to EN)

    const val FALLBACK = "en"
    const val AUTO = "auto"

    /** Şu an kullanılan dil (tablo kodu). [use] ile ayarlanır. */
    @Volatile var language: String = resolve(AUTO, Locale.getDefault().language)
        private set

    /** Ayardaki değer ([AUTO] ya da bir tablo kodu) ve telefonun dili → kullanılacak tablo. Flutter'daki kuralla aynı. */
    fun resolve(setting: String, system: String): String = when {
        setting in tables -> setting
        system.lowercase() in tables -> system.lowercase()
        else -> FALLBACK
    }

    /** Ayardaki arayüz dilini uygular. */
    fun use(setting: String) {
        language = resolve(setting, Locale.getDefault().language)
    }

    fun t(key: String): String = tables[language]?.get(key) ?: tables.getValue(FALLBACK)[key] ?: key

    /**
     * Flutter'da yerelleştirilecek metnin kodu: `@anahtar` ve U+001F ile ayrılmış değerler (lib/l10n.dart: `nt`,
     * tablolarda `n.anahtar`). Günlükte de bu kod görünür.
     */
    fun code(key: String, vararg args: Any?): String = "@" + key + args.joinToString("") { "\u001f$it" }
}
