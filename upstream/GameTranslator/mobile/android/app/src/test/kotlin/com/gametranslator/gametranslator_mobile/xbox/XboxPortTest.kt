// SPDX-License-Identifier: AGPL-3.0-only
package com.gametranslator.gametranslator_mobile.xbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.time.Instant

/** Bu depoya özgü parçalar: java.time kullanmayan ISO 8601 okuma/yazma ve I420 → ARGB çevirisi. */
class XboxInstantTest {
    @Test fun parsesLikeJavaTime() {
        for (text in listOf(
            "2026-11-16T10:00:00Z", "2026-11-16T10:30:00.123Z", "2026-11-16T10:16:00.1234567Z", "2024-02-29T23:59:59Z",
            "1970-01-01T00:00:00Z", "2000-03-01T00:00:00.5Z", "2099-12-31T23:59:59.999999999Z",
        )) assertEquals(text, Instant.parse(text).toEpochMilli(), parseInstant(text))
        assertEquals(Instant.parse("2026-11-16T07:00:00Z").toEpochMilli(), parseInstant("2026-11-16T10:00:00+03:00"))
    }

    @Test fun rejectsInvalid() {
        assertNull(parseInstant(null))
        assertNull(parseInstant(""))
        assertNull(parseInstant("bozuk"))
        assertNull(parseInstant("2026-13-01T00:00:00Z"))
        assertNull(parseInstant("2026-11-16 10:00:00"))
    }

    @Test fun formatsLikeJavaTime() {
        for (ms in listOf(0L, 1_794_823_200_000L, 1_794_823_200_123L, 951_782_400_000L))
            assertEquals(Instant.ofEpochMilli(ms).toString(), formatInstant(ms))
    }
}

class XboxFramesTest {
    private fun convert(width: Int, height: Int, strideY: Int, strideC: Int, y: ByteArray, u: ByteArray, v: ByteArray): IntArray {
        val out = IntArray(width * height)
        XboxFrames.i420ToArgb(ByteBuffer.wrap(y), ByteBuffer.wrap(u), ByteBuffer.wrap(v), strideY, strideC, strideC, width, height, out)
        return out
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test fun blackWhiteAndGrey() {
        // 2x2, satır aralığı 4 (dolgu baytları 0xFF: okunmamalı).
        val y = bytes(16, 235, 0xFF, 0xFF, 126, 16, 0xFF, 0xFF)
        val out = convert(2, 2, 4, 2, y, bytes(128, 0xFF), bytes(128, 0xFF))
        assertEquals(0xFF000000.toInt(), out[0])
        assertEquals(0xFFFFFFFF.toInt(), out[1])
        assertEquals(0xFF808080.toInt(), out[2])
        assertEquals(0xFF000000.toInt(), out[3])
    }

    @Test fun primaryColours() {
        // BT.601 sınırlı aralık: kırmızı (81, 90, 240), yeşil (145, 54, 34), mavi (41, 240, 110).
        fun pixel(y: Int, u: Int, v: Int) = convert(1, 1, 1, 1, bytes(y), bytes(u), bytes(v))[0]
        fun near(expected: Int, actual: Int) {
            for (shift in listOf(16, 8, 0))
                assertTrue("%08x ~ %08x".format(expected, actual), Math.abs(((expected shr shift) and 0xFF) - ((actual shr shift) and 0xFF)) <= 2)
            assertEquals(0xFF, actual ushr 24)
        }
        near(0xFFFF0000.toInt(), pixel(81, 90, 240))
        near(0xFF00FF00.toInt(), pixel(145, 54, 34))
        near(0xFF0000FF.toInt(), pixel(41, 240, 110))
    }

    @Test fun oddSizeAndBufferPosition() {
        // 3x3: renk düzlemi 2x2. Arabellek konumu ilerletilmiş olsa da kare oradan okunur ve konum değişmez.
        val y = ByteBuffer.wrap(bytes(0, 0, 235, 235, 235, 16, 16, 16, 235, 235, 235))
        y.position(2)
        val u = ByteBuffer.wrap(bytes(128, 128, 128, 128))
        val v = ByteBuffer.wrap(bytes(128, 128, 128, 128))
        val out = IntArray(9)
        XboxFrames.i420ToArgb(y, u, v, 3, 2, 2, 3, 3, out)
        assertEquals(2, y.position())
        assertEquals(listOf(-1, -1, -1), out.slice(0..2))
        assertEquals(List(3) { 0xFF000000.toInt() }, out.slice(3..5))
        assertEquals(listOf(-1, -1, -1), out.slice(6..8))
    }
}
