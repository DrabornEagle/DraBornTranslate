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
    val textLines = joinSubtitleFragments(lines.map { it.copy(text = cleanOcrLine(it.text)) }
        .filter { it.height > 0 && it.text.any(Char::isLetter) })
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

/** The recognizer can return a speaker name and its sentence as separate boxes on one row. */
private fun joinSubtitleFragments(lines: List<RecognizedLine>): List<RecognizedLine> {
    val joined = mutableListOf<RecognizedLine>()
    for (line in lines.sortedBy(RecognizedLine::left)) {
        val sameRow = joined.indexOfLast { earlier ->
            val smaller = minOf(earlier.height, line.height)
            val overlap = minOf(earlier.bottom, line.bottom) - maxOf(earlier.top, line.top)
            overlap >= smaller * 0.7f && maxOf(earlier.height, line.height) <= smaller * 1.5f &&
                line.left - earlier.right in 0..smaller
        }
        if (sameRow < 0) {
            joined.add(line)
        } else {
            val previous = joined[sameRow]
            val confidence = listOf(previous.confidence, line.confidence).filter { it > 0f }.minOrNull() ?: 0f
            joined[sameRow] = previous.copy(
                text = "${previous.text} ${line.text}",
                top = minOf(previous.top, line.top),
                right = line.right,
                bottom = maxOf(previous.bottom, line.bottom),
                confidence = confidence,
            )
        }
    }
    return joined
}
