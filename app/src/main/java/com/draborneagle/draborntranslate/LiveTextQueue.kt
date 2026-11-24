// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

/** Only numeric RFC1918 hosts are accepted; no public servers, DNS, or redirects. */
internal object LiveTextEndpoint {
    fun url(host: String, port: String): String? {
        val h = host.trim()
        val parts = h.split('.')
        if (parts.size != 4 || parts.any { it.isEmpty() || it.length > 3 ||
                it.any { c -> c !in '0'..'9' } || (it.length > 1 && it.startsWith('0')) }) return null
        val a = parts.map { it.toIntOrNull() ?: return null }
        if (a.any { it !in 0..255 }) return null
        val local = a[0] == 10 || (a[0] == 172 && a[1] in 16..31) ||
            (a[0] == 192 && a[1] == 168)
        val p = port.trim().toIntOrNull() ?: return null
        if (!local || p !in 1..65535) return null
        return "ws://$h:$p/api/ws/text/origin"
    }
}

/** Main-thread only: keep at most the latest pending subtitle; stale replies never render. */
internal class LiveTextQueue {
    data class Cue(val id: Long, val text: String)
    private var serial = 0L
    private var current: String? = null
    private var pending: Cue? = null

    fun submit(raw: String): Boolean {
        val text = raw.trim().replace(Regex("\\s+"), " ").take(500)
        if (text.isBlank()) { clear(); return true }
        if (text == current) return false
        current = text
        pending = Cue(++serial, text)
        return true
    }

    fun next(): Cue? = pending.also { pending = null }
    fun isLatest(cue: Cue): Boolean = cue.id == serial && current == cue.text
    fun clear() { serial++; current = null; pending = null }
}
