// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

/**
 * Coordinate math for an optical camera looking at the physical PS Portal.
 * It does not claim to extract or alter the Portal's protected cloud stream.
 * Each frame is examined locally and never transmitted or written to storage.
 */
internal data class CloudTextBlock(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

internal object CloudTextLayout {
    fun containsCenter(region: Pixels, line: CloudTextBlock): Boolean {
        val centerX = line.left + (line.right - line.left) / 2
        val centerY = line.top + (line.bottom - line.top) / 2
        return centerX >= region.x && centerX < region.x + region.width &&
            centerY >= region.y && centerY < region.y + region.height
    }

    fun subtitles(
        area: Pixels,
        bandTop: Int,
        lines: List<CloudTextBlock>,
    ): List<String> {
        val strip = subtitleBand(area, bandTop)
        return lines.asSequence()
            .filter { containsCenter(strip, it) && it.text.count(Char::isLetter) >= 2 }
            .sortedWith(compareBy({ it.top }, { it.left }))
            .map { it.text.trim().replace(Regex("\\s+"), " ").take(150) }
            .filter(String::isNotEmpty)
            .distinct()
            .take(3)
            .toList()
    }

    fun messages(
        area: Pixels,
        settings: CaptureSettings,
        blocks: List<CloudTextBlock>,
    ): List<CloudTextBlock> {
        val lens = settings.lensArea(area)
        return blocks.asSequence()
            .filter { containsCenter(lens, it) }
            .map { it.copy(text = it.text.trim().replace(Regex("\\s+"), " ").take(360)) }
            .filter { it.text.count(Char::isLetter) >= 3 && it.right > it.left && it.bottom > it.top }
            .sortedWith(compareBy({ it.top }, { it.left }))
            .distinctBy { it.text.lowercase() }
            .take(10)
            .toList()
    }
}
