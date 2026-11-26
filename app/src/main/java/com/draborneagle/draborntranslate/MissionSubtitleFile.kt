// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import java.io.IOException

internal data class MissionCue(val startMs: Long, val endMs: Long, val text: String, val speaker: String?)

internal object MissionSubtitleFile {
    const val MAX_BYTES = 2 * 1024 * 1024
    private val blocks = Regex("\\n[ \\t]*\\n")
    private val time = Regex("(?:(\\d{1,3}):)?([0-5]\\d):([0-5]\\d)[.,](\\d{3})")
    private val cueTime = Regex("^([^ \\t]+)[ \\t]+-->[ \\t]+([^ \\t]+)(?:[ \\t].*)?$")
    private val voice = Regex("^<v(?:\\.[^ >]+)?[ \\t]+([^>]{1,80})>", RegexOption.IGNORE_CASE)
    private val tags = Regex("</?(?:i|b|u|s|font|c|v|lang|ruby|rt)(?:[. \\t][^>]*)?>", RegexOption.IGNORE_CASE)

    fun parse(input: String): List<MissionCue> {
        val normalized = input.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n').trim()
        val cues = normalized.split(blocks).mapNotNull { block ->
            val lines = block.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
            val first = lines.firstOrNull() ?: return@mapNotNull null
            if (first.startsWith("WEBVTT") || first.startsWith("NOTE ") || first in listOf("NOTE", "STYLE", "REGION")) {
                return@mapNotNull null
            }
            val timeIndex = if ("-->" in first) 0 else 1
            val match = lines.getOrNull(timeIndex)?.let(cueTime::matchEntire) ?: invalid()
            val start = milliseconds(match.groupValues[1])
            val end = milliseconds(match.groupValues[2])
            val raw = lines.drop(timeIndex + 1).joinToString("\n")
            val speaker = voice.find(raw)?.groupValues?.get(1)?.trim()?.takeIf(String::isNotEmpty)
            val text = tags.replace(raw, "").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&nbsp;", " ").replace("&amp;", "&").trim()
            if (end < start || text.isEmpty() || text.length > 4_000) invalid()
            MissionCue(start, end, text, speaker)
        }
        if (cues.isEmpty() || cues.size > 5_000) invalid()
        return cues.sortedBy { it.startMs }
    }

    private fun milliseconds(value: String): Long {
        val match = time.matchEntire(value) ?: invalid()
        val (hours, minutes, seconds, millis) = match.destructured
        return (((hours.toLongOrNull() ?: 0) * 60 + minutes.toLong()) * 60 + seconds.toLong()) * 1000 + millis.toLong()
    }

    private fun invalid(): Nothing = throw IOException("SRT veya WebVTT dosyasındaki altyazı ve zaman bilgileri okunamadı.")
}
