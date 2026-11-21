// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.Executors

internal object OfflineModelStore {
    const val MODEL_BYTES = 806_057_408L
    const val TERMS = "https://ai.google.dev/gemma/terms"
    const val MODEL_NAME = "MiLMMT 1B"
    const val MODEL_SIZE = "806 MB"
    const val MODEL_LICENSE = "Gemma"
    private const val name = "MiLMMT-46-1B-v1.0.Q4_K_M.gguf"
    private const val checksum = "74d38ba75108d455326e9deeaf9ab01bb266dfa665eae9c4aa84e84485d4fdf9"
    private const val modelUrl = "https://huggingface.co/mradermacher/MiLMMT-46-1B-v1.0-GGUF/" +
        "resolve/34df5efbe6592773ec168cc7b307728c08623472/$name?download=true"
    private const val downloadKey = "milmmt-download-id"
    private const val messageKey = "milmmt-download-message"
    private val worker = Executors.newSingleThreadExecutor()
    private var verifying: Long? = null

    enum class Stage { MISSING, DOWNLOADING, VERIFYING, READY }
    data class State(val stage: Stage, val message: String)

    fun installedFile(context: Context): File? = file(context, name)
        ?.takeIf { it.isFile && it.length() == MODEL_BYTES }

    @Synchronized
    fun start(context: Context) {
        val app = context.applicationContext
        val prefs = preferences(app)
        if (installedFile(app) != null || prefs.contains(downloadKey) || verifying != null) return
        val pending = file(app, "$name.part") ?: throw IOException("Telefon depolamasına erişilemedi.")
        val space = pending.parentFile?.usableSpace ?: 0L
        if (space in 1 until MODEL_BYTES) throw IOException("Model için telefonda $MODEL_SIZE boş alan gerekiyor.")
        if (pending.exists() && !pending.delete()) throw IOException("Önceki geçici indirme kaldırılamadı.")
        val request = DownloadManager.Request(Uri.parse(modelUrl))
            .setTitle("DraBornTranslate Türkçe modeli")
            .setDescription(MODEL_NAME)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setMimeType("application/octet-stream")
            .setDestinationInExternalFilesDir(app, Environment.DIRECTORY_DOWNLOADS, "$name.part")
        val id = manager(app).enqueue(request)
        if (!prefs.edit().putLong(downloadKey, id).remove(messageKey).commit()) {
            manager(app).remove(id)
            throw IOException("İndirme başlatılamadı. Tekrar dene.")
        }
    }

    @Synchronized
    fun cancel(context: Context) {
        val prefs = preferences(context)
        val id = prefs.getLong(downloadKey, -1)
        if (id < 0 || verifying == id) return
        manager(context).remove(id)
        prefs.edit().remove(downloadKey).remove(messageKey).apply()
    }

    @Synchronized
    fun status(context: Context): State {
        if (installedFile(context) != null) return State(Stage.READY, "Türkçe model hazır. Çeviri telefonda çalışır.")
        val prefs = preferences(context)
        val id = prefs.getLong(downloadKey, -1)
        if (id < 0) return State(Stage.MISSING, prefs.getString(messageKey, null)
            ?: "Canlı çeviri çevrimdışı çalışır. İstersen gelişmiş modeli de ekleyebilirsin.")
        if (verifying == id) return State(Stage.VERIFYING, "Model dosyası doğrulanıyor…")
        val downloads = manager(context)
        downloads.query(DownloadManager.Query().setFilterById(id))?.use { row ->
            if (row.moveToFirst()) {
                when (row.getInt(row.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        verifyDownloaded(context.applicationContext, id)
                        return State(Stage.VERIFYING, "Model dosyası doğrulanıyor…")
                    }
                    DownloadManager.STATUS_FAILED -> return fail(context, id)
                    DownloadManager.STATUS_PAUSED -> return State(Stage.DOWNLOADING, "İndirme bağlantı bekliyor…")
                }
                val bytes = row.getLong(row.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val percent = (bytes * 100 / MODEL_BYTES).coerceIn(0, 100)
                return State(Stage.DOWNLOADING, "Türkçe model indiriliyor · %$percent")
            }
        }
        return fail(context, id)
    }

    private fun fail(context: Context, id: Long): State {
        val message = "İndirme tamamlanamadı. Bağlantıyı ve boş alanı kontrol edip tekrar dene."
        manager(context).remove(id)
        preferences(context).edit().remove(downloadKey).putString(messageKey, message).apply()
        return State(Stage.MISSING, message)
    }

    private fun verifyDownloaded(context: Context, id: Long) {
        verifying = id
        worker.execute {
            val success = runCatching {
                val pending = checkNotNull(file(context, "$name.part"))
                val target = checkNotNull(file(context, name))
                verifyModelFile(pending, MODEL_BYTES, checksum) && pending.renameTo(target)
            }.getOrDefault(false)
            synchronized(this) {
                try {
                    manager(context).remove(id)
                    val edit = preferences(context).edit().remove(downloadKey)
                    if (success) edit.remove(messageKey)
                    else edit.putString(messageKey, "Model dosyası doğrulanamadı. Tekrar indir.")
                    edit.apply()
                } finally {
                    verifying = null
                }
            }
        }
    }

    private fun file(context: Context, filename: String): File? =
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let { File(it, filename) }

    private fun manager(context: Context): DownloadManager = context.getSystemService(DownloadManager::class.java)

    private fun preferences(context: Context) =
        context.getSharedPreferences("translation-model", Context.MODE_PRIVATE)
}

internal fun verifyModelFile(file: File, size: Long, sha256: String): Boolean {
    if (!file.isFile || file.length() != size) return false
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered(128 * 1024).use { input ->
        val buffer = ByteArray(128 * 1024)
        while (true) {
            val length = input.read(buffer)
            if (length < 0) break
            digest.update(buffer, 0, length)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == sha256
}
