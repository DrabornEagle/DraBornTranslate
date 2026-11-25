// SPDX-License-Identifier: AGPL-3.0-only
package com.gametranslator.gametranslator_mobile.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Çevrilen altyazılar, en yenisi başta: bu oturumun ve önceki oturumların son [limit] satırı. Hızlı geçen bir satır
 * sonradan yeniden okunabilsin diye tutulur ("Altyazı geçmişi" sayfası). Windows'taki GameTranslator.Core/SubtitleHistory.cs
 * ile aynı kurallar ve aynı dosya biçimi (history.json). Yalnız altyazı satırları eklenir; ek alanların çevirileri girmez
 * (bilgisayarda da öyle). İş parçacığı güvenlidir.
 */
class SubtitleHistory(limit: Int = DEFAULT_LIMIT) {
    /** [date]: satırın çevrildiği an (ms, UTC). [fromPack]: satır oyun paketinden mi geldi (değilse çeviri motoru). */
    data class Entry(val id: Int, val date: Long, val source: String, val translation: String, val fromPack: Boolean)

    companion object {
        /** Varsayılan satır sayısı (Windows ile aynı; dosya en çok ~200 KB olur). */
        const val DEFAULT_LIMIT = 200
        /** Bir satırın kaydedilen en uzun hâli (dosya şişmesin). */
        const val MAX_TEXT_LENGTH = 500

        private fun clip(text: String) = if (text.length > MAX_TEXT_LENGTH) text.substring(0, MAX_TEXT_LENGTH) else text

        /** Tarih saniyesine kadar ve UTC olarak yazılır ("2026-11-12T10:00:00Z"); Windows ve Mac aynı biçimi okur. */
        private fun formatter() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }

        private fun parseDate(text: String): Long? {
            // Kesirli saniyeli ya da saat dilimli ISO 8601 da kabul edilir (Windows'un eski dosyaları).
            val patterns = listOf("yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
            for (pattern in patterns) {
                val format = SimpleDateFormat(pattern, Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC"); isLenient = false }
                runCatching { format.parse(text) }.getOrNull()?.let { return it.time }
            }
            return null
        }

        /** Dosyadaki geçmiş; dosya yoksa ya da bozuksa boş geçmiş. */
        fun load(file: File, limit: Int = DEFAULT_LIMIT): SubtitleHistory {
            val history = SubtitleHistory(limit)
            try {
                if (!file.isFile) return history
                val stored = JSONObject(file.readText(Charsets.UTF_8)).optJSONArray("entries") ?: return history
                for (i in 0 until stored.length()) {
                    if (history.entries.size >= history.limit) break
                    val item = stored.optJSONObject(i) ?: continue
                    if (!item.has("source") || !item.has("translation")) continue
                    val date = parseDate(item.optString("date")) ?: continue
                    history.entries.add(Entry(item.optInt("id"), date, item.optString("source"), item.optString("translation"),
                        item.optString("origin") == "pack"))
                }
                history.nextId = (history.entries.maxOfOrNull { it.id } ?: -1) + 1
            } catch (_: Exception) {
                history.entries.clear()
            }
            return history
        }
    }

    val limit = maxOf(1, limit)
    private val entries = ArrayList<Entry>()
    private var nextId = 0

    /** Satırlar, en yenisi başta (kopya). */
    @Synchronized
    fun entries(): List<Entry> = entries.toList()

    /**
     * Yeni çeviriyi başa ekler. Aynı satırın yeniden okuması (benzer kaynak metin) yeni kayıt açmaz, son kaydı günceller.
     * Boş çeviri eklenmez. Geçmiş değiştiyse true.
     */
    @Synchronized
    fun add(source: String, translation: String, fromPack: Boolean = false, date: Long = System.currentTimeMillis()): Boolean {
        val text = clip(translation.trim())
        if (text.isEmpty()) return false
        val clipped = clip(source)
        val first = entries.firstOrNull()
        if (first != null && TextMatch.isSimilar(TextMatch.normalize(first.source), TextMatch.normalize(clipped))) {
            entries[0] = Entry(first.id, date, clipped, text, fromPack)
            return true
        }
        entries.add(0, Entry(nextId++, date, clipped, text, fromPack))
        while (entries.size > limit) entries.removeAt(entries.size - 1)
        return true
    }

    @Synchronized
    fun clear() = entries.clear()

    /** Windows'taki history.json ile aynı JSON metni. */
    @Synchronized
    fun toJson(): String {
        val format = formatter()
        val list = JSONArray()
        for (entry in entries) list.put(JSONObject()
            .put("id", entry.id)
            .put("date", format.format(Date(entry.date)))
            .put("source", entry.source)
            .put("translation", entry.translation)
            .put("origin", if (entry.fromPack) "pack" else "engine"))
        return JSONObject().put("version", 1).put("entries", list).toString()
    }

    /** Geçmişi dosyaya yazar (önce geçici dosyaya, sonra yerine koyarak; yarım dosya kalmaz). */
    fun save(file: File) {
        val text = toJson()
        file.parentFile?.mkdirs()
        val temporary = File(file.path + ".tmp")
        temporary.writeText(text, Charsets.UTF_8)
        if (!temporary.renameTo(file)) {
            file.delete()
            if (!temporary.renameTo(file)) {
                file.writeText(text, Charsets.UTF_8)
                temporary.delete()
            }
        }
    }
}
