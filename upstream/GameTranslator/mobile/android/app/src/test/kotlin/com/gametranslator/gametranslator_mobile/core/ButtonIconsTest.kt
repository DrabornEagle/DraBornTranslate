// SPDX-License-Identifier: AGPL-3.0-only
package com.gametranslator.gametranslator_mobile.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** Kumanda simgesi görüntüden tanınır (Windows: ButtonIconsTests, aynı yapay simgeler). */
class ButtonIconsTest {
    private val size = 96

    /** Koyu zemin üstünde beyaz (ya da renkli) daire; içine [mark] koyu çizilir (PlayStation simgeleri böyle). */
    private fun icon(disc: Triple<Int, Int, Int> = Triple(235, 235, 235), mark: (Double, Double) -> Boolean): PixelImage {
        val image = PixelImage(size * 2, size)
        for (y in 0 until size) for (x in 0 until size * 2) {
            val dx = (x - size).toDouble()
            val dy = y - size / 2.0
            val r = size * 0.32
            var color = Triple(20, 20, 24)
            if (dx * dx + dy * dy <= r * r) {
                color = disc
                // İşaret, dairenin içinde -1..1 koordinatıyla çizilir.
                if (mark(dx / r, dy / r)) color = Triple(30, 30, 30)
            }
            image.set(x, y, color.first, color.second, color.third)
        }
        return image
    }

    private val stroke = 0.13

    private fun cross(x: Double, y: Double) = abs(x) < 0.5 && abs(y) < 0.5 && (abs(x - y) < stroke * 1.4 || abs(x + y) < stroke * 1.4)

    private fun ring(x: Double, y: Double): Boolean {
        val d = sqrt(x * x + y * y)
        return d > 0.38 && d < 0.55
    }

    private fun square(x: Double, y: Double) = max(abs(x), abs(y)).let { it > 0.36 && it < 0.5 }

    private fun triangle(x: Double, y: Double): Boolean {
        // Tepe üstte (y = -0.45), taban altta (y = 0.4).
        if (y < -0.47 || y > 0.42) return false
        val half = (y + 0.45) / 0.85 * 0.5
        return (y > 0.28 && abs(x) <= half) || abs(abs(x) - half) < stroke * 1.3
    }

    @Test
    fun faceButtonsAreRecognizedFromTheirSymbol() {
        assertEquals("✕", ButtonIcons.classify(icon(mark = ::cross)))
        assertEquals("○", ButtonIcons.classify(icon(mark = ::ring)))
        assertEquals("□", ButtonIcons.classify(icon(mark = ::square)))
        assertEquals("△", ButtonIcons.classify(icon(mark = ::triangle)))
    }

    @Test
    fun colouredDiscIsAMapMarkerNotAButton() {
        val yellow = icon(Triple(230, 190, 20)) { _, _ -> false }
        assertEquals("◆", ButtonIcons.classify(yellow))
        // Sarı dairedeki A/B/X harfi PlayStation'da harita işaretidir (Ⓑ Xbox'ın kırmızı B tuşu olarak çiziliyor).
        assertEquals("◆", ButtonIcons.classify(yellow, { "B" }))
        assertEquals("Ⓚ", ButtonIcons.classify(yellow, { "K" }))
    }

    /** PlayStation'ın renkli yüz tuşları rengiyle tanınır (GTA 6, God of War): yeşil △, mavi ✕, pembe □, kırmızı ○. */
    @Test
    fun colouredFaceButtonsAreRecognizedByHue() {
        assertEquals("△", ButtonIcons.classify(icon(Triple(40, 200, 120)) { _, _ -> false }))
        assertEquals("✕", ButtonIcons.classify(icon(Triple(60, 120, 230)) { _, _ -> false }))
        assertEquals("□", ButtonIcons.classify(icon(Triple(230, 80, 200)) { _, _ -> false }))
        assertEquals("○", ButtonIcons.classify(icon(Triple(230, 40, 40)) { _, _ -> false }))
        assertNull(ButtonIcons.faceButtonByHue(50.0))
    }

    @Test
    fun shoulderButtonIsReadFromItsLabel() {
        // Yazılı kutu (L2): yazı okuyucusu "L2" okur; okunamazsa daire/kare sembol sanılmaz.
        val image = PixelImage(size * 2, size)
        for (y in 0 until size) for (x in 0 until size * 2) {
            val inside = abs(x - size) < 26 && abs(y - size / 2) < 16
            val v = if (inside) 230 else 20
            image.set(x, y, v, v, v)
        }
        assertEquals("L2", ButtonIcons.classify(image, { "L2" }))
        assertEquals("L1", ButtonIcons.classify(image, { "(LI)" }))
        assertNull(ButtonIcons.classify(image, { null }))
    }

    @Test
    fun plainBackgroundIsNotAnIcon() {
        val image = PixelImage(size * 2, size)
        image.pixels.fill(0xFF282828.toInt())
        assertNull(ButtonIcons.classify(image))
    }

    @Test
    fun labelsAreNormalized() {
        assertEquals("L2", ButtonIcons.label("L2"))
        assertEquals("R1", ButtonIcons.label("(RI)"))
        assertEquals("L2", ButtonIcons.label("DL2T"))
        assertEquals("OPTIONS", ButtonIcons.label("OPTIONS"))
        assertNull(ButtonIcons.label("Hello"))
        assertEquals("L", ButtonIcons.label("L", stick = true))
        assertNull(ButtonIcons.label("L"))
    }

    /** Bir karede sözcükler arasındaki simge, karedeki yerinden kesilip tanınır. */
    @Test
    fun iconIsFoundAtItsPlaceInTheFrame() {
        val frame = PixelImage(400, 200)
        frame.pixels.fill(0xFF141418.toInt())
        val disc = icon(mark = ::cross)
        // Simge karenin ortasına (100..292, 52..148) yapıştırılır.
        for (y in 0 until disc.height) for (x in 0 until disc.width) frame.pixels[(52 + y) * 400 + 100 + x] = disc.pixels[y * disc.width + x]
        val box = TextBox(170.0 / 400, 76.0 / 200, 230.0 / 400, 124.0 / 200)
        val crop = ButtonIcons.cropAround(frame, box)!!
        assertTrue(crop.width >= 60 && crop.height >= 48)
        assertEquals("✕", ButtonIcons.classify(frame, box))
    }

    @Test
    fun paddedImageIsTallEnoughToRead() {
        val small = PixelImage(20, 12)
        val padded = ButtonIcons.padded(small)
        // Zemin 4 kat geniş, 2 kat yüksek (80x24); 140 piksele varmak için 6 kat büyütülür.
        assertEquals(144, padded.height)
        assertEquals(480, padded.width)
    }
}
