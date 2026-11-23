// SPDX-License-Identifier: AGPL-3.0-only
package com.gametranslator.gametranslator_mobile.core

/**
 * Paket çevirisini yalnız oynanan oyunun paketi varken kullandırır (Windows: PackGate.cs, aynı kurallar). Paketler
 * bütün oyunlarda aranır; başka oyunun paketindeki kısa ve genel satırlar ("Yeah,", "Nope...", "Miles?") paketi olmayan
 * oyunda da eşleşip o oyunun çevirisini basıyordu ("Miles?" → "mil"). Kısa süre içinde birkaç uzun satır eşleşmedikçe
 * oyunun paketi yok sayılır ve eşleşmeler kullanılmaz; satır çeviri motoruna gider. Uzun eşleşmeler kesilirse (başka
 * oyuna geçildi) kapı yeniden kapanır. Aynı satır için birden çok kez çağrılabilir (sayım farklı satırlar üzerindendir).
 * Oyun belliyse (PS5 bildirdi ve paketi var) kapıya bakılmaz.
 */
class PackGate(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    companion object {
        /** Bu kadar sözcükten kısa satır genel sayılır; oyunu tanıtmaz. */
        const val LONG_LINE_WORDS = 5
        /** Kapının açılması için [WINDOW_MS] içinde eşleşmesi gereken farklı uzun satır sayısı. */
        const val LINES_TO_OPEN = 3
        const val WINDOW_MS = 5 * 60 * 1000L
        /** Son uzun eşleşmeden bu kadar sonra kapı kapanır. */
        const val CLOSE_AFTER_MS = 15 * 60 * 1000L
    }

    private val lock = Any()
    private val recent = mutableListOf<Pair<String, Long>>()
    private var lastLong = Long.MIN_VALUE / 2
    private var open = false

    /** Oynanan oyunun paketi var sayılıyor mu. */
    val isOpen: Boolean get() = synchronized(lock) { open && clock() - lastLong <= CLOSE_AFTER_MS }

    /** [match] kullanılacaksa kendisi, oyunun paketi henüz belli değilse null. */
    fun filter(source: String, match: PackMatch?): PackMatch? {
        if (match == null) return null
        synchronized(lock) {
            val at = clock()
            if (open && at - lastLong > CLOSE_AFTER_MS) {
                open = false
                recent.clear()
            }
            // Konuşmacı adı sayılmaz: "Spider-Man: what got into you?" dört sözcüklük genel bir cümledir.
            val colon = source.indexOf(':')
            val spoken = if (colon in 2..30 && !source.substring(0, colon).contains('\n')) source.substring(colon + 1) else source
            val line = TextMatch.normalize(spoken)
            if (line.split(' ').count { it.isNotEmpty() } >= LONG_LINE_WORDS) {
                lastLong = at
                recent.removeAll { at - it.second > WINDOW_MS || it.first == line }
                recent.add(line to at)
                if (recent.size >= LINES_TO_OPEN) open = true
            }
            return if (open) match else null
        }
    }
}
