// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import com.gametranslator.gametranslator_mobile.core.ColoredPhrase
import com.gametranslator.gametranslator_mobile.core.ExtraAreaPreset
import com.gametranslator.gametranslator_mobile.core.OcrNoise
import com.gametranslator.gametranslator_mobile.core.TextBox
import com.gametranslator.gametranslator_mobile.core.TextMatch
import com.gametranslator.gametranslator_mobile.core.UiPrompts
import java.util.Locale
import kotlin.math.abs

internal enum class GameReadingProfile(val label: String) {
    GENERAL("Genel"), GTA_6("GTA 6");

    companion object {
        fun from(value: String?) = entries.firstOrNull { it.name == value } ?: GENERAL
    }
}

internal enum class HudKind(val label: String) {
    OBJECTIVE("Görev"), HELP("İpucu"), MESSAGE("Mesaj"), PHONE("Telefon"), CONTROLS("Tuşlar"),
    PAUSE_MENU("Duraklatma menüsü"),
}

internal enum class HudLineStyle { TEXT, INCOMING, OUTGOING, REPLY, NAME }

internal data class HudLine(
    val text: String,
    val style: HudLineStyle = HudLineStyle.TEXT,
    val prompt: Boolean = false,
    val colors: List<ColoredPhrase> = emptyList(),
)

internal data class HudBlock(
    val kind: HudKind,
    val box: TextBox,
    val lines: List<HudLine>,
    val title: String? = null,
) {
    fun contentKey() = listOf(title.orEmpty()) + lines.map { "${it.style}:${it.text}" }
}

internal data class ProfileLine(
    val text: String,
    val box: TextBox,
    val colors: List<ColoredPhrase> = emptyList(),
    val speakerColor: String? = null,
)

internal data class ProfileReading(
    val subtitles: List<ProfileLine>,
    val hud: List<HudBlock>,
    val allowFallback: Boolean,
) {
    fun captionPosition(): CaptionPosition? {
        if (subtitles.isEmpty()) return null
        val boxes = subtitles.map { it.box }
        val box = TextBox(boxes.minOf { it.left }, boxes.minOf { it.top }, boxes.maxOf { it.right }, boxes.maxOf { it.bottom })
        return CaptionPosition(box, subtitles.firstNotNullOfOrNull { it.speakerColor })
    }
}

/** GTA's HUD geometry is defined in normalized game-video coordinates. */
internal object Gta6Profile {
    private val objective = ExtraAreaPreset.BOTTOM_OBJECTIVE.region
    private val help = ExtraAreaPreset.HELP_BOX.region
    private val message = ExtraAreaPreset.PHONE_MESSAGE.region
    private val subtitles = TextBox(0.17, 0.73, 0.83, objective.top)
    private val controls = TextBox(0.53, 0.91, 1.0, 1.0)
    private val phoneRegion = TextBox(0.40, 0.12, 0.995, 0.93)
    private val pauseRegion = TextBox(0.0, 0.085, 0.31, 0.92)
    private val pauseItems = mapOf(
        "game" to "Oyun", "map" to "Harita", "brief" to "Özet", "progress" to "İlerleme",
        "stats" to "İstatistikler", "settings" to "Ayarlar", "store" to "Mağaza",
        "help" to "Yardım", "online" to "Çevrimiçi",
    )
    private val phoneTitles = setOf("messages", "new messages", "new message", "text messages", "group chat")
    private val phoneCommands = setOf("scroll", "send", "exit", "back", "reply", "select", "reminders")
    private val commandPattern = Regex("""\b(?:PHOTO STUDIO|SET DESTINATION|SCROLL|SEND|EXIT|BACK|REPLY|SELECT|REMINDERS|OPEN|CLOSE)\b""", RegexOption.IGNORE_CASE)
    private val name = Regex("""[\p{L}][\p{L}\p{M} .&'’\-]{0,35}""")
    private val speaker = Regex("""^([\p{L}][\p{L}\p{M} .&'’\-]{0,35}):\s*""")
    private val roleNames = mapOf(
        "local" to "Yerli", "locals" to "Yerliler", "cop" to "Polis", "cops" to "Polisler",
        "police officer" to "Polis", "officer" to "Memur", "guard" to "Muhafız",
        "dispatcher" to "Operatör", "pedestrian" to "Yaya", "bystander" to "Çevredeki kişi",
    )

    fun speakerName(text: String): String? = speaker.find(text)?.groupValues?.get(1)

    fun localizeSpeaker(text: String): String {
        val match = speaker.find(text) ?: return text
        val english = match.groupValues[1]
        val role = roleNames[english.lowercase(Locale.ROOT)] ?: return text
        return role + text.substring(english.length)
    }

    fun read(normal: List<ProfileLine>, contrasted: List<ProfileLine>): ProfileReading {
        // Prefer the view with more readable English while keeping the original view's colors and coordinates.
        val base = normal.sortedWith(screenOrder)
        val contrast = contrasted.sortedWith(screenOrder)
        val merged = merge(base, contrast)
        pauseMenu(merged)?.let { return ProfileReading(emptyList(), listOf(it), allowFallback = false) }
        val basePhone = phone(base)
        val contrastPhone = phone(contrast)
        val phone = phone(merged) ?: basePhone ?: contrastPhone
        val first = subtitleLines(base, phone?.box)
        val second = subtitleLines(contrast, phone?.box)
        val firstText = first.joinToString(" ") { it.text }
        val secondText = second.joinToString(" ") { it.text }
        val disagreement = first.isNotEmpty() && second.isNotEmpty() &&
            (!TextMatch.isSimilar(TextMatch.normalize(firstText), TextMatch.normalize(secondText), 0.87) ||
                TextMatch.endsQuestion(firstText) != TextMatch.endsQuestion(secondText) ||
                TextMatch.hasMeaningfulChange(TextMatch.normalize(secondText), TextMatch.normalize(firstText)))
        val result = if (disagreement) {
            if (OcrNoise.score(secondText) > OcrNoise.score(firstText)) second else first
        } else subtitleLines(merged, phone?.box)
        val blocks = buildList {
            paragraph(HudKind.OBJECTIVE, merged.filter { objective.contains(it.box) && !isControl(it.text) }, objective)?.let(::add)
            paragraph(HudKind.HELP, merged.filter { help.contains(it.box) }, help)?.let(::add)
            if (phone == null) notification(merged.filter { message.contains(it.box) && !isControl(it.text) })?.let(::add)
            phone?.let(::add)
            controller(merged.filter { controls.contains(it.box) })?.let(::add)
        }
        return ProfileReading(result, blocks, !disagreement && !OcrNoise.looksGarbled(result.joinToString(" ") { it.text }))
    }

    private fun pauseMenu(lines: List<ProfileLine>): HudBlock? {
        val recognised = lines.filter { pauseRegion.contains(it.box) }.mapNotNull { line ->
            val label = pauseItems[TextMatch.normalize(line.text)] ?: return@mapNotNull null
            line to label
        }
        val anchor = recognised.maxByOrNull { (line, _) ->
            recognised.count { abs(it.first.box.left - line.box.left) < 0.055 }
        }?.first ?: return null
        val menu = recognised.filter { abs(it.first.box.left - anchor.box.left) < 0.055 }.distinctBy { it.second }
        val steps = menu.zipWithNext().count { (first, second) ->
            second.first.box.top - first.first.box.top in 0.028..0.23
        }
        if (steps < 2) return null
        val box = union(menu.map { it.first }, pauseRegion)
        return HudBlock(HudKind.PAUSE_MENU, box, menu.map { HudLine(it.second, prompt = true) })
    }

    private fun merge(normal: List<ProfileLine>, contrasted: List<ProfileLine>): List<ProfileLine> {
        val remaining = contrasted.toMutableList()
        val result = normal.map { line ->
            val second = remaining.firstOrNull { overlapsRow(line.box, it.box) } ?: return@map line
            remaining.remove(second)
            if (OcrNoise.score(second.text) > OcrNoise.score(line.text) + 1) line.copy(text = second.text) else line
        }
        return (result + remaining).sortedWith(screenOrder)
    }

    private fun subtitleLines(lines: List<ProfileLine>, phone: TextBox?): List<ProfileLine> {
        val candidates = lines.filter { line ->
            val width = line.box.right - line.box.left
            val center = (line.box.right + line.box.left) / 2
            subtitles.contains(line.box) && phone?.contains(line.box) != true &&
                !help.contains(line.box) && !message.contains(line.box) &&
                (!isControl(line.text) || line.text.lastOrNull() in listOf('.', '!', '?', '…')) &&
                abs(center - 0.5) < if (width >= 0.28) 0.14 else 0.095
        }.filter { line ->
            val words = OcrNoise.tokens(line.text)
            val boxHeight = line.box.bottom - line.box.top
            // Lower-center short labels and large text on scenery can otherwise win over the actual dialogue.
            boxHeight in 0.008..0.082 &&
                (words.size >= 3 || speakerName(line.text) != null || line.text.lastOrNull() in listOf('.', '!', '?', '…'))
        }
        val anchor = candidates.minByOrNull { abs((it.box.left + it.box.right) / 2 - 0.5) }
            ?: return emptyList()
        return candidates.filter {
            abs(it.box.top - anchor.box.top) < 0.085 &&
                abs(it.box.bottom - anchor.box.bottom) < 0.095
        }.take(3)
    }

    private fun paragraph(kind: HudKind, source: List<ProfileLine>, region: TextBox): HudBlock? {
        val lines = source.filter { speakerName(it.text) == null && OcrNoise.tokens(it.text).any { word -> word.length >= 2 } }
        if (lines.isEmpty() || lines.all { it.text.length < 4 }) return null
        val text = lines.take(5).joinToString(" ") { it.text }
        return HudBlock(kind, union(lines, region), listOf(HudLine(text, colors = lines.flatMap { it.colors })))
    }

    private fun notification(lines: List<ProfileLine>): HudBlock? {
        if (lines.isEmpty()) return null
        val first = lines.first().text
        val sender = speakerName(first) ?: first.takeIf { lines.size > 1 && isName(it) }
        if (sender == null) return null
        val body = listOf(first.removePrefix(sender).trimStart(':', ' ')) + lines.drop(1).map { it.text }
        val text = body.filter { it.isNotBlank() }.take(5).joinToString(" ")
        if (text.isBlank()) return null
        return HudBlock(HudKind.MESSAGE, union(lines, message), listOf(HudLine(text, HudLineStyle.INCOMING)), sender)
    }

    /** The phone is identified by its message heading or a set of lower-right phone controls. */
    private fun phone(lines: List<ProfileLine>): HudBlock? {
        if (lines.any { it.box.top < 0.12 && (TextMatch.normalize(it.text) in setOf("brief", "progress") ||
                TextMatch.normalize(it.text).contains("jason lucia map")) }) return null
        val commands = lines.filter { controls.contains(it.box) }.flatMap { commandPattern.findAll(it.text).map(MatchResult::value) }
            .map { it.lowercase(Locale.ROOT) }.toSet()
        val hasControls = ("scroll" in commands || "send" in commands || "reply" in commands) &&
            ("exit" in commands || "back" in commands)
        val phoneLines = lines.filter { phoneRegion.contains(it.box) && !isControl(it.text) }
        val heading = phoneLines.firstOrNull { TextMatch.normalize(it.text) in phoneTitles && it.box.top < 0.60 }
        val separator = phoneLines.firstOrNull {
            TextMatch.normalize(it.text) in setOf("new message", "new messages")
        }
        if (heading == null && separator == null && !hasControls) return null
        val reference = heading ?: separator
        val header = heading ?: phoneLines.firstOrNull { line ->
            line.box.top < 0.49 && isName(line.text) && (reference == null ||
                abs((line.box.left + line.box.right - reference.box.left - reference.box.right) / 2) < 0.16)
        }
        if (header == null) return null
        val center = (header.box.left + header.box.right) / 2
        val list = TextMatch.normalize(header.text) in phoneTitles
        val messages = phoneLines.filter {
            it !== header && it.box.top >= header.box.bottom - 0.008 && it.box.top < 0.88 &&
                it.box.left >= center - 0.20 && it.box.right <= center + 0.22 &&
                TextMatch.normalize(it.text) !in phoneTitles &&
                it.text.count(Char::isLetter) >= 2
        }.take(24)
        if (messages.isEmpty()) return null
        val baseline = messages.minOf { it.box.left }
        val groups = mutableListOf<Pair<ProfileLine, HudLine>>()
        for (line in messages) {
            val text = line.text.trim()
            val reply = text.endsWith('?') && text.count(Char::isLowerCase) == 0
            val sender = speakerName(text)
            val person = sender ?: text.takeIf { list && isName(it) }
            if (person != null) groups.add(line to HudLine(person, HudLineStyle.NAME))
            val body = if (person == null) text else text.removePrefix(person).trimStart(':', ' ')
            if (body.isBlank()) continue
            val style = when {
                reply -> HudLineStyle.REPLY
                line.box.left - baseline > 0.047 && !list -> HudLineStyle.OUTGOING
                else -> HudLineStyle.INCOMING
            }
            val previous = groups.lastOrNull()
            if (previous != null && previous.second.style == style && !reply && person == null &&
                line.box.top - previous.first.box.bottom in -0.012..0.011 && abs(line.box.left - previous.first.box.left) < 0.017) {
                groups[groups.lastIndex] = line to previous.second.copy(text = "${previous.second.text} $body")
            } else groups.add(line to HudLine(body, style))
        }
        val title = if (list) "Mesajlar" else header.text
        val content = groups.map { it.second }
        if (content.isEmpty()) return null
        val detected = union(listOf(header) + messages, phoneRegion)
        val box = TextBox((detected.left - 0.035).coerceAtLeast(0.0), detected.top, (detected.right + 0.035).coerceAtMost(1.0), detected.bottom)
        return HudBlock(HudKind.PHONE, box, content, title)
    }

    private fun controller(lines: List<ProfileLine>): HudBlock? {
        val source = lines.filter { isControl(it.text) }
        if (source.isEmpty()) return null
        val translated = source.flatMap { line ->
            val whole = UiPrompts.translate(line.text.uppercase(Locale.ROOT))
            if (whole != null) listOf(HudLine(whole, prompt = true))
            else {
                val commands = commandPattern.findAll(line.text).toList()
                commands.mapIndexedNotNull { index, command ->
                    val before = if (index == 0) line.text.take(command.range.first).trim() else ""
                    val after = line.text.substring(command.range.last + 1, commands.getOrNull(index + 1)?.range?.first ?: line.text.length).trim()
                    val input = listOf(before, command.value, after).filter(String::isNotBlank).joinToString(" ")
                    (UiPrompts.translate(input.uppercase(Locale.ROOT)) ?: UiPrompts.translate(command.value.uppercase(Locale.ROOT)))
                        ?.let { HudLine(UiPrompts.keepIcons(input, it), prompt = true) }
                }
            }
        }.distinct()
        return translated.takeIf { it.isNotEmpty() }?.let { HudBlock(HudKind.CONTROLS, union(source, controls), it) }
    }

    private fun isControl(text: String): Boolean = TextMatch.normalize(text) in phoneCommands ||
        (text.any { it in "✕×○△□◯⨯" } && commandPattern.containsMatchIn(text)) ||
        UiPrompts.translate(text.uppercase(Locale.ROOT)) != null ||
        (text == text.uppercase(Locale.ROOT) && commandPattern.findAll(text).count() >= 2)

    private fun isName(text: String): Boolean = text.length in 2..36 && name.matches(text) &&
        text.first().isUpperCase() && text.split(' ').size <= 4

    private val screenOrder = compareBy<ProfileLine>({ (it.box.top * 125).toInt() }, { it.box.left })

    private fun union(lines: List<ProfileLine>, empty: TextBox): TextBox {
        val boxes = lines.map { it.box }
        if (boxes.isEmpty()) return empty
        return TextBox(boxes.minOf { it.left }, boxes.minOf { it.top }, boxes.maxOf { it.right }, boxes.maxOf { it.bottom })
    }

    private fun TextBox.contains(box: TextBox): Boolean =
        (box.left + box.right) / 2 in left..right && (box.top + box.bottom) / 2 in top..bottom

    private fun overlapsRow(a: TextBox, b: TextBox): Boolean {
        val overlap = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val narrower = minOf(a.right - a.left, b.right - b.left)
        return overlap > narrower * 0.55 &&
            abs(a.bottom + a.top - b.bottom - b.top) < maxOf(a.bottom - a.top, b.bottom - b.top)
    }
}
