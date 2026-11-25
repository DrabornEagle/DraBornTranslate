// SPDX-License-Identifier: AGPL-3.0-only
package com.gametranslator.gametranslator_mobile.core

import java.util.Locale

/**
 * PS5'te o an çalışan oyun: keşif yanıtındaki `running-app-titleid` ve `running-app-name`. Ortak havuza giden satıra
 * eklenir; yeni bir oyunun satırları ayrı toplanıp hızla paket yapılır. Yalnız oyunun kimliği ve adı gider.
 * Windows'taki RunningGame.cs ve Mac'teki RunningGame.swift ile aynı kurallar (sunucu da aynısını doğrular).
 */
data class RunningGame(val titleId: String, val name: String?) {
    companion object {
        const val MAX_NAME = 120
        private val titleIdPattern = Regex("^[A-Z]{4}\\d{5}(_\\d{2})?$")

        /** Kimlik PS5 biçiminde değilse null. */
        fun create(titleId: String?, name: String?): RunningGame? {
            val id = titleId?.trim()?.uppercase(Locale.ROOT) ?: return null
            if (!titleIdPattern.matches(id)) return null
            return RunningGame(id, normalizedName(name))
        }

        /** Denetim karakterleri ve fazla boşluk atılır, en çok 120 karakter; boşsa null. */
        fun normalizedName(value: String?): String? {
            if (value == null) return null
            val builder = StringBuilder(value.length)
            var index = 0
            while (index < value.length) {
                val point = value.codePointAt(index)
                val type = Character.getType(point).toByte()
                if (type == Character.CONTROL || type == Character.FORMAT) builder.append(' ') else builder.appendCodePoint(point)
                index += Character.charCount(point)
            }
            // C#'taki string.Split(null): her türlü boşluk karakteri ayırır.
            val words = StringBuilder()
            var word = StringBuilder()
            fun flush() {
                if (word.isEmpty()) return
                if (words.isNotEmpty()) words.append(' ')
                words.append(word)
                word = StringBuilder()
            }
            for (c in builder) if (Character.isWhitespace(c) || Character.isSpaceChar(c)) flush() else word.append(c)
            flush()
            val all = words.toString()
            val trimmed = (if (all.length > MAX_NAME) all.substring(0, MAX_NAME) else all).trim()
            return trimmed.ifEmpty { null }
        }
    }
}
