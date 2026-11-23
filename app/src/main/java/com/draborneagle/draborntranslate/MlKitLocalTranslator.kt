// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.gametranslator.gametranslator_mobile.core.TextTranslator
import java.util.concurrent.TimeUnit

/**
 * On-device EN -> TR translator. No paid translation service and no user API key.
 * Google ML Kit downloads its language model once; future inference is local.
 * Only call on the single translation worker, never on the UI thread.
 */
internal class MlKitLocalTranslator : TextTranslator, AutoCloseable {
    private val translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.TURKISH)
            .build(),
    )
    private var ready = false

    fun prepare(): Boolean {
        Tasks.await(
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build()),
            55,
            TimeUnit.SECONDS,
        )
        ready = true
        return true
    }

    override fun translate(text: String): String? {
        if (!ready || text.isBlank()) return null
        val translated = Tasks.await(translator.translate(text), 15, TimeUnit.SECONDS)
        return translated.trim().takeIf(String::isNotEmpty)
    }

    override fun close() {
        ready = false
        translator.close()
    }
}
