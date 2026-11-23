// SPDX-License-Identifier: AGPL-3.0-only
package com.gametranslator.gametranslator_mobile.core

/**
 * Çevirinin yapılabildiği bir dil. [code] ISO 639-1'dir (`zh`: Basitleştirilmiş Çince). [rtl]: sağdan sola yazılır.
 */
data class TargetLanguage(
    val code: String,
    val nativeName: String,
    val englishName: String,
    val turkishName: String,
    val rtl: Boolean = false,
)

/**
 * Çeviri dillerinin tek tanımı (Windows uygulamasıyla aynı liste ve sıra) ve her çeviri motorunun bu dillerdeki
 * karşılığı. Arayüz listeyi köprüden alır (`getLanguages`); çekirdek motoru buna göre kurar.
 */
object Languages {
    val targets: List<TargetLanguage> = listOf(
        TargetLanguage("tr", "Türkçe", "Turkish", "Türkçe"),
        TargetLanguage("en", "English", "English", "İngilizce"),
        TargetLanguage("ar", "العربية", "Arabic", "Arapça", rtl = true),
        TargetLanguage("ja", "日本語", "Japanese", "Japonca"),
        TargetLanguage("de", "Deutsch", "German", "Almanca"),
        TargetLanguage("fr", "Français", "French", "Fransızca"),
        TargetLanguage("es", "Español", "Spanish", "İspanyolca"),
        TargetLanguage("it", "Italiano", "Italian", "İtalyanca"),
        TargetLanguage("pt", "Português", "Portuguese", "Portekizce"),
        TargetLanguage("ru", "Русский", "Russian", "Rusça"),
        TargetLanguage("zh", "简体中文", "Chinese (Simplified)", "Çince (Basitleştirilmiş)"),
        TargetLanguage("ko", "한국어", "Korean", "Korece"),
        TargetLanguage("pl", "Polski", "Polish", "Lehçe"),
        TargetLanguage("nl", "Nederlands", "Dutch", "Felemenkçe"),
        TargetLanguage("id", "Bahasa Indonesia", "Indonesian", "Endonezce"),
        TargetLanguage("hi", "हिन्दी", "Hindi", "Hintçe"),
        TargetLanguage("fa", "فارسی", "Persian", "Farsça", rtl = true),
        TargetLanguage("uk", "Українська", "Ukrainian", "Ukraynaca"),
        TargetLanguage("el", "Ελληνικά", "Greek", "Yunanca"),
        TargetLanguage("hu", "Magyar", "Hungarian", "Macarca"),
        TargetLanguage("ro", "Română", "Romanian", "Rumence"),
        TargetLanguage("cs", "Čeština", "Czech", "Çekçe"),
        TargetLanguage("sv", "Svenska", "Swedish", "İsveççe"),
        TargetLanguage("th", "ไทย", "Thai", "Tayca"),
        TargetLanguage("vi", "Tiếng Việt", "Vietnamese", "Vietnamca"),
        TargetLanguage("he", "עברית", "Hebrew", "İbranice", rtl = true),
        TargetLanguage("az", "Azərbaycanca", "Azerbaijani", "Azerbaycanca"),
    )

    /** Oyunun dili seçicisindeki diller (bugünkü liste; çeviri dilleriyle aynı adları kullanır). */
    val sources: List<String> = listOf("tr", "en", "de", "fr", "es", "it", "pt", "ru", "ar", "ja")

    private val byCode = targets.associateBy { it.code }

    fun find(code: String): TargetLanguage? = byCode[code]

    /**
     * İlk kurulumda çeviri dili: telefonun dili listedeyse o, değilse İngilizce. Android bazı dilleri eski kodlarıyla
     * bildirir (`iw` İbranice, `in` Endonezce).
     */
    fun defaultTarget(systemLanguage: String): String {
        val code = when (val lower = systemLanguage.lowercase()) {
            "iw" -> "he"
            "in" -> "id"
            else -> lower
        }
        return if (code in byCode) code else "en"
    }

    /** Google'ın ucundaki dil kodu. `zh` yalın hâliyle de kabul edilir; Basitleştirilmiş olduğu açıkça yazılır. */
    fun googleCode(code: String): String = if (code == "zh") "zh-CN" else code

    /**
     * DeepL'in hedef dil kodu; DeepL bu dile çevirmiyorsa null. İngilizce, Portekizce ve Çince için DeepL lehçe ister:
     * Portekizce, telefonun bölgesi Portekiz ise `PT-PT`, değilse `PT-BR`.
     * (Liste: developers.deepl.com/docs/getting-started/supported-languages, Kasım 2026.)
     */
    fun deepLTarget(code: String, region: String = ""): String? = when (code) {
        "en" -> "EN-US"
        "pt" -> if (region.equals("PT", ignoreCase = true)) "PT-PT" else "PT-BR"
        "zh" -> "ZH-HANS"
        in DEEPL -> code.uppercase()
        else -> null
    }

    /** DeepL'in çevirebildiği hedef diller (lehçe isteyenler [deepLTarget]'ta). */
    private val DEEPL = setOf(
        "tr", "ar", "ja", "de", "fr", "es", "it", "ru", "ko", "pl", "nl", "id", "hi", "fa", "uk", "el", "hu", "ro", "cs", "sv",
        "th", "vi", "he", "az",
    )

    /** Cihaz üstü çevirinin (ML Kit `TranslateLanguage`) bildiği diller; listemizden yalnız Azerbaycanca yok. */
    private val ML_KIT = setOf(
        "af", "ar", "be", "bg", "bn", "ca", "cs", "cy", "da", "de", "el", "en", "eo", "es", "et", "fa", "fi", "fr", "ga", "gl",
        "gu", "he", "hi", "hr", "ht", "hu", "id", "is", "it", "ja", "ka", "kn", "ko", "lt", "lv", "mk", "mr", "ms", "mt", "nl",
        "no", "pl", "pt", "ro", "ru", "sk", "sl", "sq", "sv", "sw", "ta", "te", "th", "tl", "tr", "uk", "ur", "vi", "zh",
    )

    fun mlKitSupports(code: String): Boolean = code in ML_KIT

    /** Seçilen motor ([engine]: google, deepl, mlkit) [target] diline çevirebiliyor mu? */
    fun supports(engine: String, target: String): Boolean = when (engine) {
        "deepl" -> deepLTarget(target) != null
        "mlkit" -> mlKitSupports(target)
        else -> true
    }

    /** Gerçekte çalışacak motor: seçilen motor bu dile çeviremiyorsa Google. */
    fun effectiveEngine(engine: String, target: String): String = if (supports(engine, target)) engine else "google"

    /** Arayüz için: her dilin adları ve hangi motorların o dile çevirebildiği. */
    fun toMaps(): Map<String, Any> = mapOf(
        "targets" to targets.map {
            mapOf(
                "code" to it.code, "native" to it.nativeName, "english" to it.englishName, "turkish" to it.turkishName, "rtl" to it.rtl,
                "deepl" to supports("deepl", it.code), "mlkit" to supports("mlkit", it.code),
            )
        },
        "sources" to sources,
    )
}
