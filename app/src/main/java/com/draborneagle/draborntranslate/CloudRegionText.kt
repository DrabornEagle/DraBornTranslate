// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

/** CameraX ML Kit text coordinates normalized to a user-selected 2D region. */
internal object CloudRegionText {
    fun lines(frame: Pixels, region: CloudRegion, text: List<CloudTextBlock>): List<String> {
        val area = region.normalized().pixel(frame)
        return text.asSequence()
            .filter { CloudTextLayout.containsCenter(area, it) }
            .map { it.copy(text = it.text.trim().replace(Regex("\\s+"), " ")) }
            .filter { it.text.count(Char::isLetter) >= 2 }
            .sortedWith(compareBy({ it.top }, { it.left }))
            .map { it.text.take(180) }
            .distinct()
            .take(4)
            .toList()
    }

    fun blocks(frame: Pixels, region: CloudRegion, text: List<CloudTextBlock>): List<CloudTextBlock> {
        val area = region.normalized().pixel(frame)
        return text.asSequence()
            .filter { CloudTextLayout.containsCenter(area, it) && it.right > it.left && it.bottom > it.top }
            .map { it.copy(text = it.text.trim().replace(Regex("\\s+"), " ").take(360)) }
            .filter { it.text.count(Char::isLetter) >= 3 }
            .sortedWith(compareBy({ it.top }, { it.left }))
            .distinctBy { it.text.lowercase() }
            .take(10)
            .toList()
    }
}
