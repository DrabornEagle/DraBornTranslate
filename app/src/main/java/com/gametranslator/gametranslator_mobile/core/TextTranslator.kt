// SPDX-License-Identifier: AGPL-3.0-only
// GameTranslator v1.8.48 SubtitlePipeline.kt
package com.gametranslator.gametranslator_mobile.core

/** Metni çevirir. Çevrilemezse null döndürür. */
fun interface TextTranslator {
    fun translate(text: String): String?
}
