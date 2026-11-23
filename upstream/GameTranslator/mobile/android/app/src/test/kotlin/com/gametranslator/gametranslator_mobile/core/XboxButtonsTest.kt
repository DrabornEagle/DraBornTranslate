// SPDX-License-Identifier: AGPL-3.0-only
package com.gametranslator.gametranslator_mobile.core

import com.gametranslator.gametranslator_mobile.Settings
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Xbox tuşları: işaretler, görüntüden tanıma, harf olarak okunan tuşlar ve Otomatik karar (Windows: XboxButtonsTests). */
class XboxButtonsTest {
    private val size = 96

    private fun icon(disc: Triple<Int, Int, Int>, mark: (Double, Double) -> Boolean = { _, _ -> false }): PixelImage {
        val image = PixelImage(size * 2, size)
        for (y in 0 until size) for (x in 0 until size * 2) {
            val dx = (x - size).toDouble()
            val dy = y - size / 2.0
            val r = size * 0.32
            var color = Triple(20, 20, 24)
            if (dx * dx + dy * dy <= r * r) {
                color = disc
                if (mark(dx / r, dy / r)) color = Triple(30, 30, 30)
            }
            image.set(x, y, color.first, color.second, color.third)
        }
        return image
    }

    private val green = Triple(127, 186, 0)
    private val red = Triple(232, 17, 35)
    private val blue = Triple(0, 120, 215)
    private val yellow = Triple(255, 185, 0)
    private val white = Triple(235, 235, 235)

    private fun bars(x: Double, y: Double) = abs(x) < 0.42 && (abs(y + 0.3) < 0.08 || abs(y) < 0.08 || abs(y - 0.3) < 0.08)

    private fun cross(x: Double, y: Double) = abs(x) < 0.5 && abs(y) < 0.5 && (abs(x - y) < 0.18 || abs(x + y) < 0.18)

    @Test
    fun xboxFaceButtonsAreReadFromTheirColour() {
        val xbox = ButtonLayoutVote(ButtonLayout.XBOX)
        assertEquals("Ⓐ", ButtonIcons.classify(icon(green), layout = xbox))
        assertEquals("Ⓑ", ButtonIcons.classify(icon(red), layout = xbox))
        assertEquals("Ⓧ", ButtonIcons.classify(icon(blue), layout = xbox))
        assertEquals("Ⓨ", ButtonIcons.classify(icon(yellow), layout = xbox))
    }

    @Test
    fun playStationColoursStayPlayStation() {
        val ps = ButtonLayoutVote(ButtonLayout.PLAYSTATION)
        assertEquals("✕", ButtonIcons.classify(icon(blue), layout = ps))
        assertEquals("◆", ButtonIcons.classify(icon(yellow), layout = ps))
        assertEquals("◆", ButtonIcons.classify(icon(yellow), { "Y" }, layout = ps))
    }

    @Test
    fun yellowCircleWithYIsTheXboxYButtonInAuto() {
        val vote = ButtonLayoutVote()
        assertEquals("Ⓨ", ButtonIcons.classify(icon(yellow), { "Y" }, layout = vote))
        assertTrue(vote.leansXbox)
    }

    @Test
    fun letterReadAtTheIconBecomesTheXboxButton() {
        val crossed = icon(white, ::cross)
        assertEquals("Ⓧ", ButtonIcons.classify(crossed, readLine = { _, _, _, _ -> "x" }, layout = ButtonLayoutVote(ButtonLayout.XBOX)))
        assertEquals("✕", ButtonIcons.classify(crossed, readLine = { _, _, _, _ -> "x" }, layout = ButtonLayoutVote(ButtonLayout.PLAYSTATION)))
        assertEquals("Ⓐ", ButtonIcons.classify(icon(green), readLine = { _, _, _, _ -> "A" }))
    }

    @Test
    fun shoulderLabelsFollowTheFamily() {
        assertEquals("RT", ButtonIcons.label("RT"))
        assertEquals("LS", ButtonIcons.label("(LS)"))
        assertEquals("R1", ButtonIcons.label("RI"))
        assertEquals("RT", ButtonIcons.label("RI", family = ButtonFamily.XBOX))
        assertEquals("L3", ButtonIcons.label("LB"))
        assertEquals("LB", ButtonIcons.label("LB", family = ButtonFamily.XBOX))
        assertEquals("LB", ButtonIcons.label("L8", family = ButtonFamily.XBOX))
        assertEquals("RB", ButtonIcons.label("RB", leanXbox = true))
        assertEquals("L", ButtonIcons.label("L", stick = true))
        assertNull(ButtonIcons.label("L", stick = true, family = ButtonFamily.XBOX))
        assertEquals("⧉", ButtonIcons.label("View", stick = true, family = ButtonFamily.XBOX))
    }

    @Test
    fun threeBarsAreTheMenuButton() {
        assertEquals("☰", ButtonIcons.classify(icon(white, ::bars), layout = ButtonLayoutVote(ButtonLayout.XBOX)))
        assertNull(ButtonIcons.classify(icon(white, ::bars), layout = ButtonLayoutVote(ButtonLayout.PLAYSTATION)))
    }

    @Test
    fun voteIsStickyAndForcedSettingWins() {
        val vote = ButtonLayoutVote()
        assertEquals(ButtonFamily.PLAYSTATION, vote.current)
        vote.add(ButtonFamily.XBOX)
        vote.add(ButtonFamily.XBOX)
        assertEquals(ButtonFamily.PLAYSTATION, vote.current)
        vote.add(ButtonFamily.XBOX)
        assertEquals(ButtonFamily.XBOX, vote.current)
        vote.add(ButtonFamily.PLAYSTATION)
        assertEquals(ButtonFamily.XBOX, vote.current)
        vote.reset()
        assertEquals(ButtonFamily.PLAYSTATION, vote.current)
        val forced = ButtonLayoutVote(ButtonLayout.XBOX)
        repeat(6) { forced.add(ButtonFamily.PLAYSTATION) }
        assertEquals(ButtonFamily.XBOX, forced.current)
        assertEquals(ButtonLayout.XBOX, ButtonLayoutVote.resolve(ButtonLayout.AUTO, true))
        assertEquals(ButtonLayout.PLAYSTATION, ButtonLayoutVote.resolve(ButtonLayout.AUTO, false))
        assertEquals(ButtonLayout.AUTO, ButtonLayoutVote.resolve(ButtonLayout.AUTO, null))
        assertEquals(ButtonLayout.PLAYSTATION, ButtonLayoutVote.resolve(ButtonLayout.PLAYSTATION, true))
    }

    @Test
    fun xboxLettersAreTurnedIntoButtonsOnlyWhereAButtonBelongs() {
        val cases = mapOf(
            "Press A to jump" to "Press Ⓐ to jump",
            "Hold [X]" to "Hold Ⓧ",
            "(B) Back" to "Ⓑ Back",
            "A Talk" to "Ⓐ Talk",
            "TALK A" to "TALK Ⓐ",
            "Press A or B to dodge" to "Press Ⓐ or Ⓑ to dodge",
            "PRESS Y TO CONTINUE" to "PRESS Ⓨ TO CONTINUE",
            "Use A Medkit to heal." to "Use A Medkit to heal.",
            "A new day begins." to "A new day begins.",
            "Plan B is ready." to "Plan B is ready.",
            "Hold (LT) to aim" to "Hold LT to aim",
        )
        for ((line, expected) in cases) assertEquals(line, expected, IconWords.withXboxLetters(line, ButtonFamily.XBOX))
        assertEquals("Press A to jump", IconWords.withXboxLetters("Press A to jump", ButtonFamily.PLAYSTATION))
    }

    @Test
    fun plainButtonNamesFeedTheAutoVote() {
        val vote = ButtonLayoutVote()
        repeat(3) { IconWords.observe("Hold LT to aim", vote) }
        assertEquals(ButtonFamily.XBOX, vote.current)
        val other = ButtonLayoutVote()
        repeat(3) { IconWords.observe("We lost the LT signal, Captain.", other) }
        assertEquals(ButtonFamily.PLAYSTATION, other.current)
    }

    @Test
    fun xboxTokensArePromptsAndKeptInTranslation() {
        assertTrue(ButtonPrompts.isPrompt("Hold LT to aim"))
        assertTrue(ButtonPrompts.isPrompt("Ⓐ Talk"))
        assertTrue(ButtonPrompts.isPrompt("RB Next"))
        for (token in listOf("Ⓐ", "Ⓨ", "LB", "RS", "⧉", "☰")) assertTrue(token, ButtonPrompts.isIcon(token))
        assertEquals("Nişan almak için LT basılı tut", UiPrompts.keepIcons("Hold LT to aim", "Nişan almak için lt basılı tut"))
        assertEquals("Ⓐ Konuş", UiPrompts.keepIcons("Ⓐ Talk", "Konuş"))
        assertEquals("Ⓐ Konuş", UiPrompts.translate("Ⓐ TALK"))
        assertEquals(ButtonFamily.XBOX, ButtonTokens.familyOf("RT"))
        assertEquals(ButtonFamily.PLAYSTATION, ButtonTokens.familyOf("△"))
        assertNull(ButtonTokens.familyOf("↑"))
        assertEquals("LT Aim", IconWords.normalizedIcons("[LT] Aim"))
        assertEquals("Hold Ⓐ to climb", IconWords.normalizedIcons("Hold Ⓐ Ⓐ to climb"))
    }

    @Test
    fun buttonLayoutSettingRoundTrips() {
        assertEquals("auto", Settings.fromJson(JSONObject("{}")).buttonLayout)
        val saved = Settings().copy(buttonLayout = "xbox").toJson()
        assertEquals("xbox", saved.getString("buttonLayout"))
        assertEquals("xbox", Settings.fromJson(JSONObject(saved.toString())).buttonLayout)
        assertEquals("auto", Settings.fromJson(JSONObject("{\"buttonLayout\":\"nintendo\"}")).buttonLayout)
        assertEquals(ButtonLayout.PLAYSTATION, ButtonLayout.parse("playstation"))
    }
}
