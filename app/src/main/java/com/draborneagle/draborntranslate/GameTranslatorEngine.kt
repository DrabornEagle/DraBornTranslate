// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.gametranslator.gametranslator_mobile.core.PackTranslation
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

internal class GameTranslatorEngine(context: Context) : CaptionTranslator {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val modelInitialization = Executors.newSingleThreadExecutor()
    private var model: TozTranslationModel? = null
    @Volatile private var localTranslator: MlKitLocalTranslator? = null
    private val packs = ImportedGamePack.library(app)
    private val corrections = LocalTranslationCorrections(app)
    private val pipeline = GameTranslationPipeline(
        // Human-approved lines override the external pack. For all other hits,
        // reject only malformed/obviously mismatched text and keep pack-first speed.
        packLookup = { text ->
            corrections.find(text) ?: PackQualityGate.select(text, PackTranslation.match(packs, text))
        },
        model = { text -> translateLocally(text) },
    )
    @Volatile private var closed = false

    override fun prepare(done: (Boolean) -> Unit) {
        if (closed) return
        executor.execute {
            if (closed) return@execute
            // Load game packs before any neural model: a known game line must not
            // wait for ML Kit's optional model download or ONNX initialization.
            val hasPacks = runCatching {
                packs.warmUp()
                packs.infos.isNotEmpty()
            }.onFailure { Log.w(TAG, "Oyun paketi açılamadı; çeviri motoruna geçiliyor", it) }
                .getOrDefault(false)
            if (hasPacks) {
                main.post { if (!closed) done(true) }
                // Download ML Kit's language model separately so pack hits remain fast.
                modelInitialization.execute { prepareMlKit() }
            } else {
                val mlKitReady = prepareMlKit()
                val ready = mlKitReady || runCatching {
                    if (model == null) model = TozTranslationModel(unpackModel(app))
                    true
                }.onFailure { Log.w(TAG, "Yedek Töz modeli açılamadı", it) }.getOrDefault(false)
                main.post { if (!closed) done(ready) }
            }
        }
    }

    override fun translate(input: TranslationInput, done: (TranslationReply) -> Unit) {
        if (closed) return
        executor.execute {
            if (closed) return@execute
            val reply = runCatching { pipeline.translate(input) }
                .onFailure { Log.w(TAG, "Altyazı çevrilemedi", it) }
                .getOrDefault(TranslationReply())
            val result = if (reply.text != null) reply
            else TranslationReply(issue = "Bu satır çevrilemedi. Sonraki replikte tekrar denenecek.")
            main.post { if (!closed) done(result) }
        }
    }

    private fun prepareMlKit(): Boolean {
        val candidate = MlKitLocalTranslator()
        val ready = runCatching { candidate.prepare() }
            .onFailure { Log.w(TAG, "ML Kit modeli hazır değil; Töz yedeği kullanılabilir", it) }
            .getOrDefault(false)
        if (ready && !closed) localTranslator = candidate else candidate.close()
        return ready
    }

    private fun translateLocally(text: String): String? {
        val fast = runCatching { localTranslator?.translate(text) }
            .onFailure { Log.w(TAG, "ML Kit çeviri hatası; yerel yedeğe geçiliyor", it) }
            .getOrNull()
        if (!fast.isNullOrBlank()) return fast
        return runCatching {
            if (model == null) model = TozTranslationModel(unpackModel(app))
            model?.translate(text)
        }.onFailure { Log.w(TAG, "Yerel yedek çeviri hatası", it) }.getOrNull()
    }

    override fun close() {
        if (closed) return
        closed = true
        executor.execute {
            runCatching { model?.close() }
            runCatching { localTranslator?.close() }
        }
        executor.shutdown()
        modelInitialization.shutdown()
    }

    companion object {
        private const val TAG = "DraBornTranslate"
        private val modelFiles = mapOf(
            "encoder_q8.onnx" to 52_533_066L,
            "decoder_q8.onnx" to 55_974_004L,
            "tokenizer.model" to 830_090L,
        )

        private fun unpackModel(context: Context): File = synchronized(modelFiles) {
            val dir = File(context.noBackupFilesDir, "toz-q8-6476d3e")
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Model klasörü oluşturulamadı.")
            for ((name, size) in modelFiles) {
                val target = File(dir, name)
                if (target.length() == size) continue
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
