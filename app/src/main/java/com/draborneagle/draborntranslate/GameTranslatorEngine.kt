// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.gametranslator.gametranslator_mobile.core.PackLookup
import com.gametranslator.gametranslator_mobile.core.PackTranslation
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask

internal class GameTranslatorEngine(context: Context) : CaptionTranslator {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var closed = false

    override fun prepare(done: (Boolean) -> Unit) {
        if (closed) return
        packExecutor.execute {
            val ready = runCatching { ImportedGamePack.library(app).warmUp() }
                .onFailure { Log.w(TAG, "Oyun paketi açılamadı", it) }.isSuccess
            if (!closed) main.post { if (!closed) done(ready) }
        }
        submit(priority = 2) {
            runCatching { model(app) }.onFailure { Log.w(TAG, "Türkçe model açılamadı", it) }
        }
    }

    fun lookupPack(input: TranslationInput, done: (TranslationReply?) -> Unit) {
        if (closed) return
        packExecutor.execute {
            if (closed) return@execute
            val result = runCatching {
                val library = ImportedGamePack.library(app)
                val exact = PackTranslation.match(input.text) { line ->
                    PackLookup.match(line, find = library::find)
                }
                val match = exact ?: PackTranslation.match(library, input.text)
                    ?.takeIf { candidate ->
                        com.gametranslator.gametranslator_mobile.core.TextMatch.endsQuestion(input.text) ==
                            com.gametranslator.gametranslator_mobile.core.TextMatch.endsQuestion(candidate.text)
                    }
                match?.text?.trim()?.takeIf(String::isNotEmpty)?.let { TranslationReply(it) }
            }.getOrNull()
            main.post { if (!closed) done(result) }
        }
    }

    override fun translate(input: TranslationInput, done: (TranslationReply) -> Unit) {
        lookupPack(input) { known ->
            if (known != null) done(known) else translateFallback(input, done)
        }
    }

    fun translateFallback(input: TranslationInput, done: (TranslationReply) -> Unit) {
        if (closed) return
        submit(priority = if (input.readingArea == ReadingArea.SUBTITLES) 0 else 1) {
            if (!closed) {
                val pipeline = GameTranslationPipeline(
                    packLookup = { text -> PackTranslation.match(ImportedGamePack.library(app), text) },
                    model = { text -> model(app).translate(text) },
                )
                val reply = runCatching { pipeline.translate(input) }
                    .onFailure { Log.w(TAG, "Yazı çevrilemedi", it) }.getOrDefault(TranslationReply())
                main.post {
                    if (!closed) done(if (reply.text != null) reply else TranslationReply(issue = "Metin çevrilemedi."))
                }
            }
        }
    }

    override fun close() {
        closed = true
    }

    private class TranslationJob(val priority: Int, val order: Long, val runJob: () -> Unit) :
        Runnable, Comparable<TranslationJob> {
        override fun run() = runJob()
        override fun compareTo(other: TranslationJob): Int =
            compareValuesBy(this, other, TranslationJob::priority, TranslationJob::order)
    }

    companion object {
        private val packExecutor = Executors.newSingleThreadExecutor()
        private val sequence = AtomicLong()
        private val inference = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, PriorityBlockingQueue())
        private var sharedModel: TozTranslationModel? = null

        private fun submit(priority: Int, task: () -> Unit) {
            inference.execute(TranslationJob(priority, sequence.getAndIncrement(), task))
        }

        internal fun consoleTranslation(context: Context, text: String): String? {
            val work = FutureTask(Callable { model(context.applicationContext).translate(text) })
            submit(0) { work.run() }
            return runCatching { work.get(15, TimeUnit.SECONDS) }.getOrElse {
                work.cancel(false)
                null
            }
        }

        // The ONNX session is shared by live captions, Odak and the local capture inbox.
        private fun model(context: Context): TozTranslationModel =
            sharedModel ?: TozTranslationModel(unpackModel(context)).also { sharedModel = it }

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
