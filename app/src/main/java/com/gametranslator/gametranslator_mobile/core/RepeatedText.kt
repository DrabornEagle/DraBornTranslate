// SPDX-License-Identifier: AGPL-3.0-only
package com.gametranslator.gametranslator_mobile.core

/**
 * Altyazı bölgesine düşen arayüz yazılarını davranışından tanır (Windows: RepeatedText.cs, aynı kurallar): konuşma
 * satırı bir kez söylenir, arayüz yazısı (envanter çubuğu "Pick up Select Actions Sort Close", savaş günlüğü, gösterge)
 * kısa sürede aynen yinelenir ve cümle gibi bitmez. Aynı satır [TIMES] kez çevrildiyse bir süre okumalardan çıkarılır.
 * Cümle sonu işaretiyle biten ya da "Ad:" ile başlayan satırlar yinelense de konuşmadır ("Behind you!"). Birden çok iş
 * parçacığından çağrılabilir.
 */
class RepeatedText(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    companion object {
        /** Bu kadar yinelenen satır arayüzdür. */
        const val TIMES = 3
        /** Yinelemeler bu süre içinde olmalı. */
        const val WINDOW_MS = 5 * 60 * 1000L
        /** Arayüz sayılan satır bu kadar süre okunmaz. */
        const val BLOCK_MS = 10 * 60 * 1000L

        /** Konuşma gibi mi: cümle sonu işaretiyle biter ya da "Ad: söz" biçimindedir. */
        private fun looksSpoken(line: String): Boolean {
            // Tümü büyük harfli satır (reklam panosu "GET YOURS: TODAY!", jenerik) ünlemle bitse de konuşma sayılmaz.
            val letters = line.filter { it.isLetter() }
            if (letters.length >= 4 && letters.all { it.isUpperCase() }) return false
            if (SubtitleStabilizer.endsSentence(line)) return true
            val colon = line.indexOf(": ")
            return colon in 2..30 && line[0].isUpperCase()
        }
    }

    private val lock = Any()
    private val shown = HashMap<String, MutableList<Long>>()
    private val blockedUntil = HashMap<String, Long>()

    /** Çevrilip gösterilen kaynak metni kaydeder; yeni engellenen satır varsa onu döner (günlük için). */
    fun note(source: String): String? {
        var blocked: String? = null
        val at = clock()
        synchronized(lock) {
            for (raw in source.split('\n')) {
                val line = raw.trim()
                if (line.isEmpty() || looksSpoken(line)) continue
                val key = TextMatch.normalize(line)
                if (key.length < 4) continue
                val times = shown.getOrPut(key) { mutableListOf() }
                times.removeAll { at - it > WINDOW_MS }
                times.add(at)
                if (times.size < TIMES) continue
                blockedUntil[key] = at + BLOCK_MS
                shown.remove(key)
                if (blocked == null) blocked = line
            }
            if (shown.size > 200) shown.clear()
        }
        return blocked
    }

    /** Arayüz sayılan satırlar çıkarılmış okuma (satırlar yukarıdan aşağı). */
    fun without(lines: List<String>): List<String> {
        val at = clock()
        synchronized(lock) {
            if (blockedUntil.isEmpty()) return lines
            blockedUntil.entries.removeAll { it.value <= at }
            if (blockedUntil.isEmpty() || lines.none { TextMatch.normalize(it) in blockedUntil }) return lines
            return lines.filter { TextMatch.normalize(it) !in blockedUntil }
        }
    }
}
