// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import com.gametranslator.gametranslator_mobile.core.TextMatch
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.TimeUnit

internal object MissionClipReader {
    const val SEGMENT_MS = 120_000L
    private const val STEP_MS = 650L

    fun scan(
        context: Context,
        capture: SavedCapture,
        area: FocusRegion,
        start: Long,
        end: Long,
        scene: String?,
        cancelled: () -> Boolean,
        progress: (Long) -> Unit,
    ): List<JournalLine> {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            return MediaMetadataRetriever().use { video ->
                video.setDataSource(context, capture.uri)
                val width = video.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1920
                val height = video.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1080
                val ratio = 1f.coerceAtMost(1_280f / maxOf(width, height).coerceAtLeast(1))
                val frameWidth = (width * ratio).toInt().coerceAtLeast(1)
                val frameHeight = (height * ratio).toInt().coerceAtLeast(1)
                val readAt = System.currentTimeMillis()
                val result = mutableListOf<JournalLine>()
                var position = start
                while (position < end && !cancelled()) {
                    val frame = video.getScaledFrameAtTime(position * 1_000,
                        MediaMetadataRetriever.OPTION_CLOSEST, frameWidth, frameHeight)
                    if (frame != null) {
                        val text = readFrame(frame, area) { cropped ->
                            Tasks.await(recognizer.process(InputImage.fromBitmap(cropped, 0)),
                                10, TimeUnit.SECONDS).text
                        }
                        if (text != null) {
                            val last = result.lastOrNull()
                            val contiguous = last?.endMs?.let { position - it <= STEP_MS / 2 } == true
                            val sameText = last?.english?.let {
                                TextMatch.isSimilar(TextMatch.normalize(it), TextMatch.normalize(text))
                            } == true
                            val until = minOf(position + STEP_MS, end)
                            if (last != null && contiguous && sameText) {
                                val preferred = maxOf(last.english.orEmpty(), text, compareBy(String::length))
                                result[result.lastIndex] = last.copy(english = preferred, endMs = until)
                            } else result.add(JournalLine(text, null, MissionLineSource.CLIP, scene,
                                capturedAt = readAt, startMs = position, endMs = until))
                        }
                    }
                    position += STEP_MS
                    progress(position.coerceAtMost(end))
                }
                result
            }
        } finally {
            recognizer.close()
        }
    }

    private fun readFrame(frame: Bitmap, area: FocusRegion, read: (Bitmap) -> String): String? {
        val box = area.pixels(frame.width, frame.height)
        val crop = Bitmap.createBitmap(frame, box.left, box.top, box.width(), box.height())
        try {
            return read(crop).trim().take(1_000).takeIf { it.count(Char::isLetter) >= 3 }
        } finally {
            if (crop !== frame) crop.recycle()
            frame.recycle()
        }
    }
}
