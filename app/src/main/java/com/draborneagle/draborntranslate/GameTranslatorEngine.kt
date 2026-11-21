// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.gametranslator.gametranslator_mobile.core.PackTranslation
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class GameTranslatorEngine(context: Context) : CaptionTranslator {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val packs = ImportedGamePack.library(app)
    private val known = if (ImportedGamePack.hasPack(app)) null else GameTranslationPipeline(packLookup = { null })
    private val current = AtomicReference<AtomicBoolean>()
    private var model: LocalTranslationModel? = null
    @Volatile private var closed = false

    fun translateKnown(input: TranslationInput): String? = known?.translate(input.copy(allowPartial = false))?.text

    override fun prepare(done: (Boolean) -> Unit) {
        if (closed) return
        executor.execute {
            if (closed) return@execute
            val ready = runCatching {
                if (model == null) {
                    model = LocalTranslationModel(
                        checkNotNull(OfflineModelStore.installedFile(app)),
                        app.applicationInfo.nativeLibraryDir,
                    )
                }
            }.onFailure { Log.w(TAG, "Yerel çeviri modeli açılamadı", it) }.isSuccess
            main.post { if (!closed) done(ready) }
        }
    }

    override fun translate(input: TranslationInput, done: (TranslationReply) -> Unit) {
        if (closed) return
        val cancellation = AtomicBoolean()
        current.getAndSet(cancellation)?.set(true)
        executor.execute {
            if (closed) return@execute
            val pipeline = GameTranslationPipeline(
                packLookup = { text -> PackTranslation.match(text, packs::findWithPack) },
                translateModel = { text -> model?.translate(text, cancellation) },
                canceled = cancellation::get,
            )
            val reply = runCatching { pipeline.translate(input) }
                .onFailure { Log.w(TAG, "Altyazı çevrilemedi", it) }
                .getOrDefault(TranslationReply())
            current.compareAndSet(cancellation, null)
            val result = if (reply.text != null) reply
            else TranslationReply(issue = "Bu replik için okunabilir bir Türkçe karşılık çıkmadı.")
            main.post { if (!closed) done(result) }
        }
    }

    override fun cancelCurrent() {
        current.get()?.set(true)
    }

    override fun close() {
        if (closed) return
        closed = true
        cancelCurrent()
        executor.execute { model?.close() }
        executor.shutdown()
    }

    private companion object {
        const val TAG = "DraBornTranslate"
    }
}
