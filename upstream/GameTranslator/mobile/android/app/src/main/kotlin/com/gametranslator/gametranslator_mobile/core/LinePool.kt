// SPDX-License-Identifier: AGPL-3.0-only
package com.gametranslator.gametranslator_mobile.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Ortak havuz (varsayılan açık, ayarlardan kapatılabilir): hiçbir pakette bulunmayan İngilizce oyun satırları biriktirilir
 * ve beş dakikada bir (ve çeviri durunca) GameTranslator'ın havuzuna gönderilir; yeni paketler bunlardan hazırlanır.
 * Yalnız satırın metni, anahtarı, ilk görüldüğü an ve biliniyorsa oyunun kimliğiyle adı gider; kimlik olarak kuruluma
 * özgü rastgele bir UUID kullanılır (IP, kullanıcı adı, PSN bilgisi yok). Sunucuya ulaşılamazsa kuyruk diskte bekler.
 * Windows'taki LinePool.cs ile aynı kurallar.
 */
class LinePool(
    private val file: File,
    private val app: String,
    private val endpoint: String = OWN_ENDPOINT,
    enabled: Boolean = true,
    private val post: (String, String) -> Response = ::httpPost,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Sunucu yanıtı: durum kodu ve (429'da) Retry-After başlığı. */
    data class Response(val status: Int, val retryAfter: String? = null)

    data class Line(val text: String, val key: String, val firstSeen: String, val titleId: String?, val game: String?)

    companion object {
        const val OWN_ENDPOINT = "https://gametranslator-pool.gametranslator-pool.workers.dev/v1/lines"
        const val BATCH_SIZE = 200
        const val MAX_QUEUE = 5000
        const val MAX_LENGTH = 300
        const val MIN_WORDS = 2
        const val FLUSH_INTERVAL_MS = 5 * 60_000L
        internal const val REMEMBERED_SENT = 20000
        internal const val SAVE_INTERVAL_MS = 30_000L

        /** Havuza uygun satır: en az iki sözcük, en çok 300 karakter, çöp değil, Türkçe / sistem yazısı / yarım değil. */
        fun accepts(raw: String): Boolean {
            val text = raw.trim()
            if (text.isEmpty() || text.length > MAX_LENGTH) return false
            val words = text.split(Regex("\\s+")).count { w -> w.any { it.isLetter() } }
            return words >= MIN_WORDS && !looksLikeNoise(text) && PoolFilter.accepts(text)
        }

        /** Yazı tanıma çöpü içeren ya da harften çok rakam ve simge taşıyan satır. */
        fun looksLikeNoise(text: String): Boolean {
            if (PackKey.canon(OcrNoise.clean(text)) != PackKey.canon(text)) return true
            val visible = text.filter { !it.isWhitespace() }
            return visible.count { it.isLetter() } * 2 < visible.length
        }

        fun timestamp(millis: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(millis))

        /** Retry-After: saniye; yoksa ya da okunamazsa bir dakika, en çok bir gün. */
        fun retryDelayMs(header: String?): Long =
            ((header?.trim()?.toLongOrNull() ?: 60L).coerceIn(1L, 86400L)) * 1000L

        private fun httpPost(url: String, body: String): Response {
            val connection = URL(url).openConnection() as HttpURLConnection
            return try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 15000
                connection.readTimeout = 20000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val status = connection.responseCode
                runCatching { (if (status < 400) connection.inputStream else connection.errorStream)?.use { it.readBytes() } }
                Response(status, connection.getHeaderField("Retry-After"))
            } finally {
                connection.disconnect()
            }
        }
    }

    private val lock = Any()
    private val sending = Any()
    private var install: String = UUID.randomUUID().toString()
    private val queue = ArrayList<Line>()
    private val sent = ArrayList<String>()
    private val queuedKeys = HashSet<String>()
    private val sentKeys = HashSet<String>()
    private var retryAfterMs: Long? = null
    private var lastSave = 0L
    private var dirty = false
    private var enabledNow = enabled
    private var titleId: String? = null
    private var game: String? = null

    init { load() }

    val installId: String get() = install
    val queued: List<Line> get() = synchronized(lock) { ArrayList(queue) }
    val isEnabled: Boolean get() = synchronized(lock) { enabledNow }

    /** Kapatılınca bekleyen satırlar da silinir: kullanıcı vazgeçtiyse hiçbir şey gitmez. */
    fun setEnabled(value: Boolean) = synchronized(lock) {
        enabledNow = value
        if (!value && queue.isNotEmpty()) {
            queue.clear()
            queuedKeys.clear()
            dirty = true
            saveLocked()
        }
    }

    /** O an oynanan oyun (PS5 kimliği ve adı; bilinmiyorsa null). Bundan sonra kuyruğa giren satırlar bu oyunla gider. */
    fun setGame(titleId: String?, name: String?) = synchronized(lock) {
        this.titleId = titleId?.takeIf { it.isNotBlank() }
        game = name?.takeIf { it.isNotBlank() }
    }

    /** Okunan (pakette bulunmayan) satırı kuyruğa ekler; kapalıysa, filtreye takılırsa ya da zaten varsa bir şey yapmaz. */
    fun offer(raw: String) {
        val text = raw.trim()
        if (!accepts(text)) return
        val key = PackKey.key(text) ?: return
        synchronized(lock) {
            if (!enabledNow || key in queuedKeys || key in sentKeys) return
            queue.add(Line(text, key, timestamp(clock()), titleId, game))
            queuedKeys.add(key)
            if (queue.size > MAX_QUEUE) {
                val dropped = queue.size - MAX_QUEUE
                repeat(dropped) { queuedKeys.remove(queue.removeAt(0).key) }
            }
            dirty = true
            if (clock() - lastSave >= SAVE_INTERVAL_MS) saveLocked()
        }
    }

    /** Kuyruğu 200'lük parçalar hâlinde gönderir (bloklar; arka planda çağrılır). 429'da bekler; ulaşılamazsa kuyruk kalır. */
    fun flush() {
        synchronized(sending) {
            try {
                while (sendNext()) { /* sıradaki parça */ }
            } finally {
                save()
            }
        }
    }

    /** Bir parçayı gönderir; devam edilecekse true. */
    private fun sendNext(): Boolean {
        val batch: List<Line>
        val body: String
        synchronized(lock) {
            if (!enabledNow || queue.isEmpty()) return false
            val retry = retryAfterMs
            if (retry != null && clock() < retry) return false
            batch = queue.take(BATCH_SIZE)
            body = payload(batch)
        }
        val response = try { post(endpoint, body) } catch (error: Exception) { return false }
        synchronized(lock) {
            return when {
                response.status in 200..299 -> { retryAfterMs = null; remove(batch, sent = true); true }
                response.status == 429 -> {
                    retryAfterMs = clock() + retryDelayMs(response.retryAfter)
                    dirty = true
                    false
                }
                // Sunucu bu parçayı kabul etmiyor; yeniden göndermek düzeltmez.
                response.status in 400..499 -> { remove(batch, sent = false); true }
                else -> false
            }
        }
    }

    fun save() = synchronized(lock) { saveLocked() }

    private fun payload(batch: List<Line>): String {
        val lines = JSONArray()
        for (line in batch) {
            val item = JSONObject().put("text", line.text).put("key", line.key).put("firstSeen", line.firstSeen)
            line.titleId?.let { item.put("titleId", it) }
            line.game?.let { item.put("game", it) }
            lines.put(item)
        }
        return JSONObject().put("install", install).put("app", app).put("platform", "android").put("lines", lines).toString()
    }

    private fun remove(batch: List<Line>, sent: Boolean) {
        val keys = batch.map { it.key }.toHashSet()
        queue.removeAll { it.key in keys }
        queuedKeys.removeAll(keys)
        if (sent) {
            this.sent.addAll(batch.map { it.key })
            sentKeys.addAll(keys)
            if (this.sent.size > REMEMBERED_SENT) {
                val dropped = this.sent.size - REMEMBERED_SENT
                repeat(dropped) { sentKeys.remove(this.sent.removeAt(0)) }
            }
        }
        dirty = true
    }

    private fun saveLocked() {
        if (!dirty) return
        try {
            file.parentFile?.mkdirs()
            val json = JSONObject().put("install", install)
                .put("queue", JSONArray().apply {
                    for (line in queue) put(JSONObject().put("text", line.text).put("key", line.key).put("firstSeen", line.firstSeen)
                        .put("titleId", line.titleId ?: JSONObject.NULL).put("game", line.game ?: JSONObject.NULL))
                })
                .put("sent", JSONArray(sent))
                .put("retryAfter", retryAfterMs ?: JSONObject.NULL)
            val temporary = File(file.path + ".tmp")
            temporary.writeText(json.toString())
            if (!temporary.renameTo(file)) { file.delete(); temporary.renameTo(file) }
        } catch (_: Exception) {
        }
        dirty = false
        lastSave = clock()
    }

    private fun load() {
        val json = try { JSONObject(file.readText()) } catch (_: Exception) { return }
        json.optString("install").takeIf { it.length == 36 }?.let { install = it.lowercase(Locale.ROOT) }
        json.optJSONArray("queue")?.let { list ->
            for (i in 0 until list.length()) {
                val item = list.optJSONObject(i) ?: continue
                val key = item.optString("key").takeIf { it.isNotEmpty() } ?: continue
                queue.add(Line(item.optString("text"), key, item.optString("firstSeen"),
                    item.optString("titleId").takeIf { !item.isNull("titleId") && it.isNotEmpty() },
                    item.optString("game").takeIf { !item.isNull("game") && it.isNotEmpty() }))
                queuedKeys.add(key)
            }
        }
        json.optJSONArray("sent")?.let { list -> for (i in 0 until list.length()) list.optString(i).takeIf { it.isNotEmpty() }?.let { sent.add(it); sentKeys.add(it) } }
        if (!json.isNull("retryAfter")) json.optLong("retryAfter").takeIf { it > 0 }?.let { retryAfterMs = it }
    }
}
