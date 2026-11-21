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

internal data class TextReading(val lines: List<String>, val confident: Boolean = false)

internal fun selectSubtitleLines(lines: List<RecognizedLine>, width: Int, height: Int): TextReading {
    val textLines = joinSubtitleFragments(lines.map { it.copy(text = cleanOcrLine(it.text)) }
        .filter { it.height > 0 && it.text.any(Char::isLetter) })
    fun readable(line: RecognizedLine): Boolean =
        !looksDamaged(line.text) && (line.confidence == 0f || line.confidence >= 0.35f)
    val anchor = textLines.filter(::readable).minByOrNull { line ->
        // A confidence of zero can also mean that the recognizer did not provide a score.
        val confidence = line.confidence.takeIf { it > 0f } ?: 0.75f
        abs(line.centerX / width - 0.5f) +
            abs(line.centerY / height - 0.5f) * 0.35f + (1f - confidence) * 0.45f
    } ?: return TextReading(emptyList())

    val aligned = textLines.filter { line ->
        val ratio = line.height.toFloat() / anchor.height
        ratio in 0.67f..1.5f && (abs(line.centerX - anchor.centerX) <= width * 0.12f ||
            abs(line.left - anchor.left) <= anchor.height)
    }.sortedBy(RecognizedLine::centerY)
    val middle = aligned.indexOf(anchor)
    fun nearby(rows: List<RecognizedLine>): List<RecognizedLine> = buildList {
        var prior = anchor
        for (line in rows) {
            val distance = abs(line.centerY - prior.centerY) / anchor.height
            if (distance > 1.95f) break
            if (distance < 0.7f) continue
            add(line)
            prior = line
        }
    }
    val above = nearby(aligned.subList(0, middle).asReversed()).asReversed()
    val below = nearby(aligned.subList(middle + 1, aligned.size))
    val group = above + anchor + below
    val clearRows = group.filter(::readable)
    return TextReading(clearRows.map(RecognizedLine::text),
        group.size == clearRows.size && clearRows.all { it.confidence >= 0.84f })
}

/** The recognizer can return a speaker name and its sentence as separate boxes on one row. */
internal fun joinSubtitleFragments(lines: List<RecognizedLine>): List<RecognizedLine> {
    val usable = lines.filter { it.height > 0 && !looksDamaged(it.text) &&
        (it.confidence == 0f || it.confidence >= 0.35f) }
    val joined = mutableListOf<RecognizedLine>()
    for (line in usable.sortedBy(RecognizedLine::left)) {
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
            joined[sameRow] = previous.copy(
                text = "${previous.text} ${line.text}",
                top = minOf(previous.top, line.top),
                right = line.right,
                bottom = maxOf(previous.bottom, line.bottom),
                confidence = minOf(previous.confidence, line.confidence),
            )
        }
    }
    // Non-readable boxes are kept separate so they cannot poison a clean row.
    return joined + lines.filterNot(usable::contains)
}
