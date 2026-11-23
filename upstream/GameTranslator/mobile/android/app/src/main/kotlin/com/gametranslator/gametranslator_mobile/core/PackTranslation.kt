// SPDX-License-Identifier: AGPL-3.0-only
package com.gametranslator.gametranslator_mobile.core

/**
 * Hattın oyun paketi araması: baştaki konuşmacı işareti ("- ", ">>") [PrefixTranslator]'daki gibi ayrılır, gövde
 * pakette aranır ([PackLookup]: etiket, satır, cümle ve bulanık arama), işaret Türkçenin başına olduğu gibi geri konur.
 * Paket anahtarı noktalamayı yok saydığı için ">>" ayrılmazsa satır bulunur ama işaret Türkçeden düşerdi.
 */
object PackTranslation {
    /** Paketler yalnız İngilizce oyunlar ve Türkçe çeviri içindir. */
    fun applies(sourceLanguage: String, targetLanguage: String) = sourceLanguage == "en" && targetLanguage == "tr"

    /** Paketi olan çeviri dilleri (sunucudaki paketlerin "lang" alanı). */
    val PACK_LANGUAGES = setOf("tr", "ar")

    /** Oyun paketlerinin dili: İngilizce oyun ve paketi olan bir çeviri dili (Türkçe, Arapça); yoksa null (paket yok). */
    fun packLanguage(sourceLanguage: String, targetLanguage: String): String? =
        targetLanguage.lowercase().takeIf { sourceLanguage == "en" && it in PACK_LANGUAGES }

    fun match(text: String, lookup: (String) -> PackMatch?): PackMatch? {
        val (prefix, rest) = SpeakerPrefix.split(text)
        if (prefix.isEmpty()) return lookup(text.trim())
        if (rest.isEmpty()) return null
        val found = lookup(rest) ?: return null
        return found.copy(text = prefix + found.text)
    }

    fun match(library: GamePackLibrary, text: String): PackMatch? = match(text) { PackLookup.match(library, it) }
}
