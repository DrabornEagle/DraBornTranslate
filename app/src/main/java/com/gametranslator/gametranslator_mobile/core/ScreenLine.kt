// SPDX-License-Identifier: AGPL-3.0-only
package com.gametranslator.gametranslator_mobile.core

/**
 * Telefonun kendi ekranında ("Bu telefonda göster") gösterilen satırın olayları. TV'ye giden `subtitle` ve `clear`
 * ile aynı bilgiyi taşır; ekran sonradan açılırsa o an duran satır kalan süresiyle yeniden verilir.
 * Olay: `{"type":"subtitle","text":..,"duration":ms,"replace":bool}` ya da `{"type":"clear"}`.
 */
class ScreenLine {
    private var text: String? = null
    private var until = 0L

    /** Yeni satırın olayı. [now]: artan saat (ms). */
    @Synchronized
    fun show(text: String, durationMs: Int, replaces: Boolean, now: Long): Map<String, Any?> {
        this.text = text
        until = now + durationMs
        return mapOf("type" to "subtitle", "text" to text, "duration" to durationMs, "replace" to replaces)
    }

    @Synchronized
    fun clear(): Map<String, Any?> {
        text = null
        return mapOf("type" to "clear")
    }

    /** Ekranda duran satır, kalan süresiyle; satır yoksa ya da süresi dolduysa null. */
    @Synchronized
    fun current(now: Long): Map<String, Any?>? {
        val line = text ?: return null
        val left = until - now
        if (left <= 0) return null
        return mapOf("type" to "subtitle", "text" to line, "duration" to left.toInt(), "replace" to false)
    }
}
