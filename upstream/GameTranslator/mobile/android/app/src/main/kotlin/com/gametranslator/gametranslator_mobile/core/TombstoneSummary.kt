// SPDX-License-Identifier: AGPL-3.0-only
package com.gametranslator.gametranslator_mobile.core

/**
 * Android 12+ yerel çökmenin dökümünü (tombstone) protobuf olarak verir (ApplicationExitInfo.getTraceInputStream).
 * Burada yalnız günlüğe yazılacak özet çıkarılır: sinyal, varsa abort iletisi, çöken iş parçacığının adı ve yığının
 * ilk kareleri (kitaplık + işlev). Alan numaraları AOSP'deki system/core/debuggerd/proto/tombstone.proto'dan; biçim
 * tanınmazsa dökümün içindeki ".so" geçen metinler yazılır (hangi kitaplık olduğu yine görülür).
 */
object TombstoneSummary {
    private const val MAX_FRAMES = 14
    private const val MAX_LOG_LINES = 16

    fun summarize(data: ByteArray): List<String> {
        val lines = ArrayList<String>()
        try {
            structured(data, lines)
        } catch (error: Exception) {
            lines.clear()
        }
        if (lines.none { it.startsWith("  #") }) {
            // Yapı okunamadı: metinlerden kitaplık adı geçenler
            val seen = LinkedHashSet<String>()
            for (text in strings(data)) {
                if (!text.contains(".so") && !text.startsWith("SIG") && !text.contains("abort", ignoreCase = true)) continue
                seen += text.take(160)
                if (seen.size >= MAX_FRAMES + 4) break
            }
            if (seen.isNotEmpty()) {
                lines += "çökme dökümü (ham):"
                seen.forEach { lines += "  $it" }
            }
        }
        return lines
    }

    private class Field(val number: Int, val wire: Int, val value: Long, val bytes: ByteArray?)

    private fun fields(data: ByteArray): List<Field> {
        val out = ArrayList<Field>()
        var at = 0
        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                if (at >= data.size || shift > 63) throw IllegalArgumentException("varint")
                val byte = data[at++].toInt()
                result = result or ((byte and 0x7f).toLong() shl shift)
                if (byte and 0x80 == 0) return result
                shift += 7
            }
        }
        while (at < data.size) {
            val key = varint()
            val number = (key ushr 3).toInt()
            when (val wire = (key and 7).toInt()) {
                0 -> out += Field(number, wire, varint(), null)
                1 -> { if (at + 8 > data.size) throw IllegalArgumentException("fixed64"); at += 8; out += Field(number, wire, 0, null) }
                5 -> { if (at + 4 > data.size) throw IllegalArgumentException("fixed32"); at += 4; out += Field(number, wire, 0, null) }
                2 -> {
                    val length = varint()
                    if (length < 0 || at + length > data.size) throw IllegalArgumentException("length")
                    out += Field(number, wire, 0, data.copyOfRange(at, at + length.toInt()))
                    at += length.toInt()
                }
                else -> throw IllegalArgumentException("wire $wire")
            }
        }
        return out
    }

    private fun text(field: Field?): String? = field?.bytes?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() }

    private fun structured(data: ByteArray, lines: MutableList<String>) {
        val top = fields(data)
        val tid = top.firstOrNull { it.number == 6 && it.wire == 0 }?.value
        top.firstOrNull { it.number == 10 && it.bytes != null }?.let { signal ->
            val parts = fields(signal.bytes!!)
            val name = text(parts.firstOrNull { it.number == 2 && it.wire == 2 })
            val code = text(parts.firstOrNull { it.number == 4 && it.wire == 2 })
            if (name != null) lines += "sinyal: $name" + (code?.let { " ($it)" } ?: "")
        }
        text(top.firstOrNull { it.number == 14 && it.wire == 2 })?.let { lines += "abort: ${it.take(300)}" }
        for (entry in top.filter { it.number == 16 && it.bytes != null }) {
            val pair = fields(entry.bytes!!)
            val key = pair.firstOrNull { it.number == 1 && it.wire == 0 }?.value
            if (tid != null && key != tid) continue
            val thread = fields(pair.firstOrNull { it.number == 2 && it.bytes != null }?.bytes ?: continue)
            text(thread.firstOrNull { it.number == 2 && it.wire == 2 })?.let { lines += "iş parçacığı: $it" }
            var index = 0
            for (frame in thread.filter { it.number == 4 && it.bytes != null }) {
                if (index >= MAX_FRAMES) break
                val parts = fields(frame.bytes!!)
                val function = text(parts.firstOrNull { it.number == 4 && it.wire == 2 })
                val file = text(parts.firstOrNull { it.number == 6 && it.wire == 2 })?.substringAfterLast('/') ?: "?"
                val pc = parts.firstOrNull { it.number == 1 && it.wire == 0 }?.value ?: 0
                lines += "  #%02d %s %s".format(index, file, function?.take(110) ?: "+0x" + java.lang.Long.toHexString(pc))
                index++
            }
            if (tid != null) break
        }
        logTail(top, tid, lines)
    }

    /**
     * Dökümdeki günlük kayıtlarından (log_buffers) çöken iş parçacığının son satırları ve hata düzeyindekiler. Yerel
     * kitaplık bir Java hatası yüzünden durduysa hatanın adı ve izi buradadır (System.err), yığında yalnız "abort" görünür.
     */
    private fun logTail(top: List<Field>, tid: Long?, lines: MutableList<String>) {
        val picked = ArrayList<String>()
        for (buffer in top.filter { it.number == 18 && it.bytes != null }) {
            for (entry in fields(buffer.bytes!!).filter { it.number == 2 && it.bytes != null }) {
                val parts = fields(entry.bytes!!)
                val entryTid = parts.firstOrNull { it.number == 3 && it.wire == 0 }?.value
                val priority = parts.firstOrNull { it.number == 4 && it.wire == 0 }?.value ?: 0
                val tag = text(parts.firstOrNull { it.number == 5 && it.wire == 2 }) ?: ""
                val message = text(parts.firstOrNull { it.number == 6 && it.wire == 2 }) ?: continue
                if (tag == "DEBUG" || tag == "libc") continue   // dökümün kendi çıktısı
                if ((tid != null && entryTid == tid) || priority >= 6) picked += "$tag: ${message.trim().take(200)}"
            }
        }
        if (picked.isEmpty()) return
        lines += "çökmeden önceki günlük:"
        picked.takeLast(MAX_LOG_LINES).forEach { lines += "  > $it" }
    }

    /** Verideki okunabilir (ASCII) metin parçaları, en az 5 karakter. */
    private fun strings(data: ByteArray): Sequence<String> = sequence {
        val current = StringBuilder()
        for (byte in data) {
            val c = byte.toInt()
            if (c in 0x20..0x7e) current.append(c.toChar())
            else {
                if (current.length >= 5) yield(current.toString())
                current.setLength(0)
            }
        }
        if (current.length >= 5) yield(current.toString())
    }
}
