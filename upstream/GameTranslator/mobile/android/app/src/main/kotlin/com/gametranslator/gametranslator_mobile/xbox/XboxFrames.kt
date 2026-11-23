// SPDX-License-Identifier: AGPL-3.0-only
package com.gametranslator.gametranslator_mobile.xbox

import java.nio.ByteBuffer

/**
 * WebRTC'nin verdiği I420 kareyi (Y düzlemi + yarı çözünürlükte U ve V) ARGB piksellere çevirir. PS5 oturumu okuma
 * hattına renkli kare verdiği için (yazı tanıma ve önizleme aynı görüntüyü kullanır) Xbox kareleri de aynı biçime gelir.
 */
object XboxFrames {
    /**
     * BT.601, sınırlı aralık (16–235). [out] en az [width] x [height] uzunluğunda olmalı; her piksel 0xAARRGGBB.
     * Arabelleklerin konumu değişmez.
     */
    fun i420ToArgb(
        y: ByteBuffer, u: ByteBuffer, v: ByteBuffer, strideY: Int, strideU: Int, strideV: Int,
        width: Int, height: Int, out: IntArray,
    ) {
        val chromaWidth = (width + 1) / 2
        val rowY = ByteArray(width)
        val rowU = ByteArray(chromaWidth)
        val rowV = ByteArray(chromaWidth)
        val sourceY = y.duplicate()
        val sourceU = u.duplicate()
        val sourceV = v.duplicate()
        val baseY = y.position()
        val baseU = u.position()
        val baseV = v.position()
        var index = 0
        for (row in 0 until height) {
            sourceY.position(baseY + row * strideY)
            sourceY.get(rowY, 0, width)
            if (row and 1 == 0) {
                sourceU.position(baseU + (row / 2) * strideU)
                sourceU.get(rowU, 0, chromaWidth)
                sourceV.position(baseV + (row / 2) * strideV)
                sourceV.get(rowV, 0, chromaWidth)
            }
            for (column in 0 until width) {
                val c = ((rowY[column].toInt() and 0xFF) - 16) * 298 + 128
                val d = (rowU[column shr 1].toInt() and 0xFF) - 128
                val e = (rowV[column shr 1].toInt() and 0xFF) - 128
                var r = (c + 409 * e) shr 8
                var g = (c - 100 * d - 208 * e) shr 8
                var b = (c + 516 * d) shr 8
                if (r < 0) r = 0 else if (r > 255) r = 255
                if (g < 0) g = 0 else if (g > 255) g = 255
                if (b < 0) b = 0 else if (b > 255) b = 255
                out[index++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
    }
}
