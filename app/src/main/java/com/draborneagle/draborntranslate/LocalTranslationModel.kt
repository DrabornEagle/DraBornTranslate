// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import com.gametranslator.gametranslator_mobile.core.TextMatch
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/** Owned by one translation worker. The cancellation flag can be changed by the capture thread. */
internal class LocalTranslationModel(file: File, nativeLibraryDir: String? = null) : AutoCloseable {
    private var handle = nativeOpen(
        file.absolutePath, nativeLibraryDir, min(4, Runtime.getRuntime().availableProcessors()),
    )
        .takeIf { it != 0L } ?: throw IOException("MiLMMT modeli açılamadı.")

    fun translate(input: TranslationInput, canceled: AtomicBoolean): String? {
        val source = TextMatch.literal(input.text)
        if (source.isEmpty() || handle == 0L || canceled.get()) return null
        val task = if (input.readingArea == ReadingArea.SUBTITLES && !input.preservePlaceholders) {
            "Translate this English dialogue to natural spoken Turkish:"
        } else {
            "Translate this from English to Turkish:"
        }
        val prompt = "$task\nEnglish: $source\nTurkish:"
        val reply = nativeComplete(handle, prompt.encodeToByteArray(), 192, canceled) ?: return null
        val translation = reply.decodeToString().trim()
            .removePrefix("Turkish:").trimStart()
            .substringBefore("\nEnglish:")
            .substringBefore("\nTurkish:")
            .substringBefore("\nTranslate this")
            .trim()
        return translation.takeUnless { it.isBlank() || '\uFFFD' in it }
    }

    override fun close() {
        if (handle != 0L) nativeClose(handle)
        handle = 0L
    }

    private external fun nativeOpen(path: String, nativeLibraryDir: String?, threads: Int): Long
    private external fun nativeComplete(handle: Long, prompt: ByteArray, maxTokens: Int, canceled: AtomicBoolean): ByteArray?
    private external fun nativeClose(handle: Long)

    companion object {
        init {
            System.loadLibrary("draborn_local")
        }
    }
}
