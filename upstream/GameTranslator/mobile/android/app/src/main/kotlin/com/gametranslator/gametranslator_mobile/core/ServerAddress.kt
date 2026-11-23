// SPDX-License-Identifier: AGPL-3.0-only
package com.gametranslator.gametranslator_mobile.core

/**
 * Ekran olarak bağlanılan GameTranslator sunucusunun adresi ("Bilgisayarın altyazısını göster"). Yalnız yerel ağ
 * adresleri kabul edilir (10.x, 172.16–31.x, 192.168.x, 169.254.x): elle yazılan adres internete istek göndertemez.
 */
data class ServerAddress(val host: String, val port: Int = DEFAULT_PORT) {
    override fun toString() = "$host:$port"

    companion object {
        const val DEFAULT_PORT = SubtitleServer.DEFAULT_PORT

        /**
         * Elle yazılan adresi çözer: "192.168.1.20", "192.168.1.20:8787" ya da uygulamanın gösterdiği tam adres
         * ("http://192.168.1.20:8787/ekran"). Yerel ağ adresi değilse ya da port geçersizse null.
         */
        fun parse(text: String): ServerAddress? {
            val trimmed = text.trim().removePrefix("http://").substringBefore('/')
            val host = trimmed.substringBefore(':')
            val port = if (trimmed.contains(':')) trimmed.substringAfter(':').toIntOrNull() ?: return null else DEFAULT_PORT
            if (port !in 1..65535 || !isLocal(host)) return null
            return ServerAddress(host, port)
        }

        /** Noktalı IPv4 ve özel (yerel ağ) aralıklarından biri mi? */
        internal fun isLocal(host: String): Boolean {
            val parts = host.split('.')
            if (parts.size != 4 || parts.any { it.isEmpty() || it.length > 3 || !it.all(Char::isDigit) }) return false
            val n = parts.map { it.toInt() }
            if (n.any { it > 255 }) return false
            return n[0] == 10 || (n[0] == 172 && n[1] in 16..31) || (n[0] == 192 && n[1] == 168) || (n[0] == 169 && n[1] == 254)
        }

        /**
         * Bağlanılacak sunucu ve çeviri yapıp yapmadığı. Sıra: en son bağlanılan adres ([last]) çeviri yapıyorsa o
         * (ağ taranmaz); değilse taramada ([scan]) bulunan çeviri yapan sunucu; o da yoksa yanıt veren son adres,
         * sonra taramada bulunan herhangi biri. Hiçbiri yoksa null. [probe]: sunucuysa `active`, değilse null.
         */
        fun choose(last: ServerAddress?, probe: (ServerAddress) -> Boolean?, scan: () -> OtherServers.Found?): Pair<ServerAddress, Boolean>? {
            val lastActive = last?.let(probe)
            if (last != null && lastActive == true) return Pair(last, true)
            val scanned = scan()
            return when {
                scanned != null && scanned.active -> Pair(ServerAddress(scanned.host), true)
                last != null && lastActive != null -> Pair(last, false)
                scanned != null -> Pair(ServerAddress(scanned.host), false)
                else -> null
            }
        }
    }
}
