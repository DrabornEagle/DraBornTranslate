// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import kotlin.math.abs

internal data class RecognizedLine(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val confidence: Float,
) {
    val height: Int get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
}

internal fun selectSubtitleLines(lines: List<RecognizedLine>, width: Int, height: Int): List<String> {
    val textLines = lines.map { it.copy(text = cleanOcrLine(it.text)) }
        .filter { it.text.any(Char::isLetter) }
    val anchor = textLines.filter { !looksDamaged(it.text) }.minByOrNull { line ->
        // A confidence of zero can also mean that the recognizer did not provide a score.
        val confidence = line.confidence.takeIf { it > 0f } ?: 0.75f
        abs(line.centerX / width - 0.5f) +
            abs(line.centerY / height - 0.5f) * 0.35f + (1f - confidence) * 0.45f
    } ?: return emptyList()
    if (anchor.confidence > 0f && anchor.confidence < 0.35f) return emptyList()

    val neighbors = textLines.filter { line ->
        val heightRatio = line.height.toFloat() / anchor.height
        val rowDistance = abs(line.centerY - anchor.centerY) / anchor.height
        val aligned = abs(line.centerX - anchor.centerX) <= width * 0.12f ||
            abs(line.left - anchor.left) <= anchor.height
        aligned && heightRatio in 0.67f..1.5f && rowDistance in 0.7f..1.95f
    }
    val above = neighbors.filter { it.centerY < anchor.centerY }.maxByOrNull(RecognizedLine::centerY)
    val below = neighbors.filter { it.centerY > anchor.centerY }.minByOrNull(RecognizedLine::centerY)
    val group = listOfNotNull(above, anchor, below)
    // Wait for a clearer frame if a wrapped line has damaged words.
    if (group.any { looksDamaged(it.text) || it.confidence > 0f && it.confidence < 0.35f }) return emptyList()
    return group.map(RecognizedLine::text)
}
