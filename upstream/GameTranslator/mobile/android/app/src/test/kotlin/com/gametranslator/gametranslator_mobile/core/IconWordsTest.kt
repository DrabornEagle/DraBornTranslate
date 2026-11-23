// SPDX-License-Identifier: AGPL-3.0-only
package com.gametranslator.gametranslator_mobile.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Satırın içindeki simge boşluğu ve yanlış okunan simge görüntüden tanınan düğmeyle değiştirilir (Windows: IconWordsTests). */
class IconWordsTest {
    private val width = 1920.0
    private val height = 1080.0
    private val top = 0.80
    private val bottom = 0.83

    private fun words(vararg words: Triple<String, Double, Double>) = words.map { OcrWord(it.first, TextBox(it.second, top, it.third, bottom)) }
    private fun w(text: String, left: Double, right: Double) = Triple(text, left, right)
    private val never: (TextBox) -> String? = { throw IllegalStateException("sorulmamalıydı") }

    @Test
    fun iconGapBetweenWordsGetsTheButton() {
        // "Hold" ile "to" arasında simge genişliğinde boşluk (satır ~32 px, boşluk ~58 px).
        val asked = mutableListOf<TextBox>()
        val text = IconWords.withIcons("Hold to climb", words(w("Hold", 0.40, 0.43), w("to", 0.46, 0.47), w("climb", 0.475, 0.50)), width, height) {
            asked.add(it)
            "✕"
        }
        assertEquals("Hold ✕ to climb", text)
        assertEquals(1, asked.size)
        assertEquals(0.43, asked[0].left, 0.001)
        assertEquals(0.46, asked[0].right, 0.001)
    }

    @Test
    fun ordinaryWordSpacingIsLeftAlone() {
        val words = words(w("Get", 0.40, 0.42), w("in", 0.425, 0.435), w("the", 0.44, 0.455), w("car.", 0.46, 0.48))
        assertEquals("Get in the car.", IconWords.withIcons("Get in the car.", words, width, height, never))
    }

    @Test
    fun misreadIconIsReplacedAndUnknownPictureIsDropped() {
        val words = words(w("Press", 0.40, 0.43), w("•", 0.432, 0.44), w("to", 0.442, 0.45), w("jump", 0.452, 0.47))
        assertEquals("Press □ to jump", IconWords.withIcons("Press • to jump", words, width, height) { "□" })
        assertEquals("Press to jump", IconWords.withIcons("Press • to jump", words, width, height) { null })
    }

    @Test
    fun letterAfterVerbIsCheckedButKeptWhenNoIconIsThere() {
        val words = words(w("Hold", 0.40, 0.43), w("A", 0.432, 0.44), w("to", 0.442, 0.45), w("aim", 0.452, 0.47))
        assertEquals("Hold △ to aim", IconWords.withIcons("Hold A to aim", words, width, height) { "△" })
        assertEquals("Hold A to aim", IconWords.withIcons("Hold A to aim", words, width, height) { null })
    }

    @Test
    fun edgeIconIsAddedOnceAndDuplicatesCollapse() {
        val line = TextBox(0.45, top, 0.55, bottom)
        assertEquals("□ Pick up", IconWords.withEdgeIcons("Pick up", line, width, height) { if (it.right <= line.left) "□" else null })
        assertEquals("✕ Open", IconWords.withEdgeIcons("✕ Open", line, width, height) { if (it.right <= line.left) "✕" else null })
        assertEquals("Hold ✕ to climb", IconWords.normalizedIcons("Hold ✕ ✕ to climb"))
        assertEquals("L2 Aim", IconWords.normalizedIcons("(l2) Aim"))
        assertEquals("R1 Pull", IconWords.normalizedIcons("(R1) Pull"))
    }

    /** Cümlenin içindeki harita işareti ("your stash ◆ or by…") tuş sayılmaz; fiilden sonraki simge tuştur. */
    @Test
    fun mapMarkerInsideASentenceIsNotAButton() {
        val words = words(w("your", 0.40, 0.42), w("stash", 0.425, 0.44), w("or", 0.47, 0.48), w("by", 0.485, 0.49))
        assertEquals("your stash or by", IconWords.withIcons("your stash or by", words, width, height) { "△" })
        val press = words(w("Press", 0.40, 0.43), w("to", 0.46, 0.47), w("jump", 0.475, 0.49))
        assertEquals("Press ✕ to jump", IconWords.withIcons("Press to jump", press, width, height) { "✕" })
    }

    /** Renkli adın önündeki bozuk parçalar birbirini tuş diye onaylamaz ("Talk to □ ○ Brian"). */
    @Test
    fun junkBeforeAColouredNameIsNotAChainOfButtons() {
        val words = words(w("Talk", 0.40, 0.42), w("to", 0.425, 0.435), w("•", 0.44, 0.445), w("O", 0.45, 0.455), w("Brian", 0.46, 0.48))
        assertEquals("Talk to Brian", IconWords.withIcons("Talk to • O Brian", words, width, height) { "□" })
    }

    @Test
    fun rightEdgeIconIsOnlyLookedForAfterAVerbOrInAButtonBar() {
        val line = TextBox(0.45, top, 0.50, bottom)
        assertEquals("Find", IconWords.withEdgeIcons("Find", line, width, height) { if (it.left >= line.right) "□" else null })
        assertEquals("SELECT ✕", IconWords.withEdgeIcons("SELECT", line, width, height) { if (it.left >= line.right) "✕" else null })
        assertEquals("Press ✕", IconWords.withEdgeIcons("Press", line, width, height) { if (it.left >= line.right) "✕" else null })
    }

    @Test
    fun junkAtTheEndOfASentenceIsNotAButton() {
        assertEquals("Follow", IconWords.withIcons("Follow •", words(w("Follow", 0.40, 0.43), w("•", 0.46, 0.47)), width, height) { "□" })
        assertEquals("Press □", IconWords.withIcons("Press •", words(w("Press", 0.40, 0.43), w("•", 0.46, 0.47)), width, height) { "□" })
    }

    @Test
    fun lowercaseLetterInsideAnUppercaseBarIsChecked() {
        val bar = words(w("OFFER", 0.40, 0.43), w("RIDE", 0.432, 0.45), w("e", 0.452, 0.455), w("LUCIA", 0.457, 0.48))
        assertEquals("OFFER RIDE ✕ LUCIA", IconWords.withIcons("OFFER RIDE e LUCIA", bar, width, height) { "✕" })
        val sentence = words(w("Get", 0.40, 0.42), w("in", 0.425, 0.43), w("the", 0.435, 0.44), w("e", 0.445, 0.45), w("car", 0.455, 0.47))
        assertEquals("Get in the e car", IconWords.withIcons("Get in the e car", sentence, width, height, never))
    }

    @Test
    fun suspiciousTokens() {
        for (word in listOf("•", "(x)", "LZ", "B")) assertTrue(word, IconWords.mayBeIcon(word))
        for (word in listOf("A", "I", "car", "&", "+")) assertFalse(word, IconWords.mayBeIcon(word))
    }
}
