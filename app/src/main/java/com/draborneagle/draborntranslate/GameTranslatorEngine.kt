// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import com.gametranslator.gametranslator_mobile.core.PackTranslation
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class GameTranslatorEngine(
    context: Context,
    private val preferQuality: Boolean = false,
) : CaptionTranslator {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val quickWorker = Executors.newSingleThreadExecutor()
    private val qualityWorker = Executors.newSingleThreadExecutor()
    private val packs = ImportedGamePack.library(app)
    private val known = if (ImportedGamePack.hasPack(app)) null else GameTranslationPipeline(packLookup = { null })
    private val quickRequest = AtomicReference<AtomicBoolean>()
    private val qualityRequest = AtomicReference<AtomicBoolean>()
    private var quickModel: TozTranslationModel? = null
    private var qualityModel: LocalTranslationModel? = null
    private var quickReady = false
    private var qualityReady = false
    private var optionalModel: File? = null
    @Volatile private var closed = false

    fun translateKnown(input: TranslationInput): String? = known?.translate(input.copy(allowPartial = false))?.text

    override fun prepare(done: (Boolean) -> Unit) {
        if (closed) return
        val file = OfflineModelStore.installedFile(app)
        if (preferQuality && file != null) {
            loadQuality(file) { ready ->
                if (ready) done(true) else loadQuick(done)
            }
        } else {
            loadQuick { ready ->
                if (ready) {
                    optionalModel = file.takeUnless { preferQuality }
                    done(true)
                } else if (file != null) {
                    loadQuality(file, done)
                } else {
                    done(false)
                }
            }
        }
    }

    private fun loadQuick(done: (Boolean) -> Unit) {
        if (closed) return
        quickWorker.execute {
            if (closed) return@execute
            val ready = runCatching {
                quickModel = TozTranslationModel(unpackQuickModel(app))
            }.onFailure { Log.w(TAG, "Hızlı yerel çeviri modeli açılamadı", it) }.isSuccess
            main.post {
                if (!closed) {
                    quickReady = ready
                    done(ready)
                }
            }
        }
    }

    private fun loadQuality(file: File, done: (Boolean) -> Unit) {
        if (closed) return
        val background = quickReady && !preferQuality
        qualityWorker.execute {
            if (closed) return@execute
            if (background) Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            val ready = runCatching {
                qualityModel = LocalTranslationModel(file, app.applicationInfo.nativeLibraryDir)
            }.onFailure { Log.w(TAG, "Gelişmiş yerel çeviri modeli açılamadı", it) }.isSuccess
            main.post {
                if (!closed) {
                    qualityReady = ready
                    done(ready)
                }
            }
        }
    }

    override fun translate(input: TranslationInput, done: (TranslationReply) -> Unit) {
        if (closed) return
        val useQuality = qualityReady && (preferQuality || !quickReady)
        if ((quickReady || useQuality) && submit(input, useQuality, done)) return
        main.post { if (!closed) done(TranslationReply(issue = "Çevrimdışı çeviri henüz hazır değil.")) }
    }

    fun refine(input: TranslationInput, done: (TranslationReply) -> Unit): Boolean {
        if (!quickReady || preferQuality) return false
        if (qualityReady) return submit(input, true, done)
        optionalModel?.let { file ->
            optionalModel = null
            loadQuality(file) {}
        }
        return false
    }

    private fun submit(input: TranslationInput, useQuality: Boolean, done: (TranslationReply) -> Unit): Boolean {
        if (closed) return false
        val request = if (useQuality) qualityRequest else quickRequest
        val cancellation = AtomicBoolean()
        if (!request.compareAndSet(null, cancellation)) return false
        val worker = if (useQuality) qualityWorker else quickWorker
        worker.execute {
            if (closed) return@execute
            val pipeline = GameTranslationPipeline(
                packLookup = { text -> PackTranslation.match(text, packs::findWithPack) },
                selectCandidate = { query, accept ->
                    if (useQuality) qualityModel?.translate(query, cancellation)?.let(accept)
                    else quickModel?.select(query.text, cancellation::get, accept)
                },
                canceled = cancellation::get,
            )
            val reply = runCatching { pipeline.translate(input) }
                .onFailure { Log.w(TAG, "Altyazı çevrilemedi", it) }
                .getOrDefault(TranslationReply())
            val result = if (reply.text != null) reply.copy(refined = useQuality)
            else TranslationReply(issue = "Bu replik için geçerli bir Türkçe karşılık bulunamadı.")
            main.post {
                request.compareAndSet(cancellation, null)
                if (!closed) done(result)
            }
        }
        return true
    }

    override fun cancelCurrent() {
        quickRequest.get()?.set(true)
        qualityRequest.get()?.set(true)
    }

    fun cancelRefinement() {
        if (quickReady && !preferQuality) qualityRequest.get()?.set(true)
    }

    override fun close() {
        if (closed) return
        closed = true
        cancelCurrent()
        quickWorker.execute { quickModel?.close() }
        qualityWorker.execute { qualityModel?.close() }
        quickWorker.shutdown()
        qualityWorker.shutdown()
    }

    private companion object {
        const val TAG = "DraBornTranslate"
        val modelFiles = mapOf(
            "encoder_q8.onnx" to 52_533_066L,
            "decoder_q8.onnx" to 55_974_004L,
            "tokenizer.model" to 830_090L,
        )

        fun unpackQuickModel(context: Context): File = synchronized(modelFiles) {
            val dir = File(context.noBackupFilesDir, "toz-q8-6476d3e")
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Model klasörü oluşturulamadı.")
            for ((name, size) in modelFiles) {
                val target = File(dir, name)
                if (target.isFile && target.length() == size) continue
                val temporary = File.createTempFile("toz-", ".part", dir)
                try {
                    context.assets.open("toz/$name").use { input ->
                        temporary.outputStream().use { output -> input.copyTo(output, 128 * 1024) }
                    }
                    if (temporary.length() != size || !temporary.renameTo(target)) {
                        throw IOException("Model dosyası hazırlanamadı: $name")
                    }
                } finally {
                    temporary.delete()
                }
            }
            dir
        }
    }
}
