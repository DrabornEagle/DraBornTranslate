// SPDX-License-Identifier: AGPL-3.0-only
package com.gametranslator.gametranslator_mobile.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tuş çubuğu komutları sözlükten çevrilir, motorun düşürdüğü tuş simgeleri geri konur (Windows: UiPromptsTests);
 * oyunun renkli yazdığı sözcük çeviride aynı renkte gösterilir, küçük yazıda "m" okuma hatası düzeltilir
 * (Windows: ColoredWordsTests).
 */
class UiPromptsTest {
    @Test
    fun buttonBarCommandsAreTranslatedFromTheList() {
        assertEquals("Aç ✕", UiPrompts.translate("OPEN ✕"))
        assertEquals("Bırak ○", UiPrompts.translate("PUT DOWN ○"))
        assertEquals("Hedef belirle ✕", UiPrompts.translate("SET DESTINATION ✕"))
        assertEquals("□ İç", UiPrompts.translate("□ DRINK"))
        assertEquals("Yakıt al", UiPrompts.translate("REFUEL"))
        assertNull(UiPrompts.translate("Open the door"))
        assertNull(UiPrompts.translate("SHOOT THE MOON ✕"))
        assertNull(UiPrompts.translate("✕ ○"))
    }

    @Test
    fun droppedIconsComeBack() {
        assertEquals("Metni göster △", UiPrompts.keepIcons("SHOW TEXT △", "Metni göster"))
        assertEquals("Kapat L1", UiPrompts.keepIcons("CLOSE L1", "Kapat l1"))
        assertEquals("□ Al", UiPrompts.keepIcons("□ Pick up", "Al"))
        assertEquals("Tırmanmak için ✕ basılı tut", UiPrompts.keepIcons("Hold ✕ to climb", "Tırmanmak için ✕ basılı tut"))
        assertEquals("Tırmanmak için basılı tut ✕", UiPrompts.keepIcons("Hold ✕ to climb", "Tırmanmak için basılı tut"))
    }

    @Test
    fun iconsAndButtonNamesAreKnown() {
        for (token in listOf("✕", "(○)", "R2", "l1", "Ⓑ", "O", "X", "OPTIONS")) assertTrue(token, ButtonPrompts.isIcon(token))
        for (token in listOf("Open", "o", "LZ", "1")) assertFalse(token, ButtonPrompts.isIcon(token))
    }

    @Test
    fun translatedPhraseIsColoredInTheSentence() {
        fun mark(translation: String, phrase: String) = ColoredWords.mark(translation, listOf(phrase to "#ff8dab"))
        assertEquals("⁅#ff8dab|Uçağa⁆ bin", mark("Uçağa bin", "uçak"))
        assertEquals("⁅#ff8dab|Teknenin⁆ yakınına in", mark("Teknenin yakınına in", "tekne"))
        assertEquals("⁅#ff8dab|Brian'ı⁆ takip et", mark("Brian'ı takip et", "Brian"))
        assertEquals("⁅#ff8dab|Deniz uçağına⁆ bin", mark("Deniz uçağına bin", "deniz uçağı"))
        assertEquals("Arabaya bin", mark("Arabaya bin", "uçak"))
    }

    @Test
    fun plainRemovesTheMarks() {
        assertEquals("Deniz uçağına bin", ColoredWords.plain("⁅#ff8dab|Deniz uçağına⁆ bin"))
        assertEquals("Düz metin", ColoredWords.plain("Düz metin"))
    }

    @Test
    fun colouredWordIsFoundAndWhiteWordIsNot() {
        // Koyu zemin üstünde iki sözcük: soldaki beyaz, sağdaki pembe yazı.
        val image = PixelImage(200, 40)
        for (y in 0 until 40) for (x in 0 until 200) {
            val stroke = y in 13..27 && x % 6 < 3
            when {
                !stroke -> image.set(x, y, 20, 20, 24)
                x < 100 -> image.set(x, y, 240, 240, 240)
                else -> image.set(x, y, 255, 140, 170)
            }
        }
        val phrases = ColoredWords.phrases(listOf(OcrWord("Get", TextBox(0.05, 0.2, 0.45, 0.8)), OcrWord("plane", TextBox(0.55, 0.2, 0.95, 0.8))), image)
        assertEquals(1, phrases.size)
        assertEquals("plane", phrases[0].text)
        assertTrue(phrases[0].color, phrases[0].color.startsWith("#ff"))
    }

    @Test
    fun misreadLetterMIsRepaired() {
        val words = setOf("collect", "cash", "from", "completed", "jobs", "your", "mailbox", "pick", "up", "a", "longarm",
            "working", "for", "brian", "will", "require", "some", "gear", "turn", "on", "the", "corner")
        val check: (String) -> Boolean = { it in words }
        assertEquals("Collect cash from completed jobs", LetterM.fix("Collect cash fronn conopleted jobs", check))
        assertEquals("from your mailbox", LetterM.fix("frorn your noailbox", check))
        assertEquals("Pick up a longarm", LetterM.fix("Pick up a longarrn", check))
        assertEquals("Working for Brian will require some gear", LetterM.fix("Working for Brian will require sonne gear", check))
        assertEquals("Turn on the corner", LetterM.fix("Turn on the corner", check))
        assertEquals("frorn", LetterM.fix("frorn", null))
    }
}
