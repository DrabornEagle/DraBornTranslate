// SPDX-License-Identifier: AGPL-3.0-only
package com.gametranslator.gametranslator_mobile.core

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/** Karenin bir bölgesini kırpıp verir (yoksa null); ek alanların okuyucusu her turda alanını böyle alır. */
fun interface RegionFrame {
    /** [region]: karenin kenarlarına oranla (0–1). */
    fun area(region: TextBox): PixelImage?
}

/**
 * Android'den bağımsız ARGB görüntü (piksel başına bir Int: 0xAARRGGBB, Bitmap.getPixels ile aynı biçim). Windows'taki
 * Frame'in karşılığı; kumanda simgesi ve renkli sözcük tanıma bunun üstünde çalışır, JVM testlerinde de kullanılır.
 */
class PixelImage(val width: Int, val height: Int, val pixels: IntArray = IntArray(width * height)) : RegionFrame {
    init {
        require(pixels.size >= width * height) { "piksel dizisi küçük" }
    }

    fun red(x: Int, y: Int): Int = (pixels[y * width + x] shr 16) and 0xFF
    fun green(x: Int, y: Int): Int = (pixels[y * width + x] shr 8) and 0xFF
    fun blue(x: Int, y: Int): Int = pixels[y * width + x] and 0xFF

    fun set(x: Int, y: Int, r: Int, g: Int, b: Int) {
        pixels[y * width + x] = (0xFF shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)
    }

    /** Parlaklık (0–1; Windows'taki ağırlıklar). */
    fun luma(x: Int, y: Int): Double = (0.114 * blue(x, y) + 0.587 * green(x, y) + 0.299 * red(x, y)) / 255

    /** Kesit; kenarlar görüntünün içine kırpılır. */
    fun crop(x: Int, y: Int, w: Int, h: Int): PixelImage {
        val cx = x.coerceIn(0, width)
        val cy = y.coerceIn(0, height)
        val cw = w.coerceIn(0, width - cx)
        val ch = h.coerceIn(0, height - cy)
        val result = PixelImage(cw, ch)
        for (row in 0 until ch) System.arraycopy(pixels, (cy + row) * width + cx, result.pixels, row * cw, cw)
        return result
    }

    /** Kenarları dışa yuvarlanmış bölge (Windows: RegionCropper.Crop). */
    override fun area(region: TextBox): PixelImage {
        val left = floor(width * region.left).toInt()
        val top = floor(height * region.top).toInt()
        val right = ceil(width * region.right).toInt()
        val bottom = ceil(height * region.bottom).toInt()
        return crop(left, top, right - left, bottom - top)
    }

    /** İki doğrusal (bilinear) büyütme (Windows: Frame.Scaled). */
    fun scaled(factor: Int): PixelImage {
        val result = PixelImage(width * factor, height * factor)
        for (ty in 0 until result.height) {
            var sy = min((ty + 0.5) / factor - 0.5, height - 1.0)
            if (sy < 0) sy = 0.0
            val y0 = sy.toInt()
            val y1 = min(y0 + 1, height - 1)
            val fy = sy - y0
            for (tx in 0 until result.width) {
                var sx = min((tx + 0.5) / factor - 0.5, width - 1.0)
                if (sx < 0) sx = 0.0
                val x0 = sx.toInt()
                val x1 = min(x0 + 1, width - 1)
                val fx = sx - x0
                var out = 0
                for (shift in intArrayOf(24, 16, 8, 0)) {
                    fun p(x: Int, y: Int) = ((pixels[y * width + x] shr shift) and 0xFF).toDouble()
                    val top = p(x0, y0) + (p(x1, y0) - p(x0, y0)) * fx
                    val bottom = p(x0, y1) + (p(x1, y1) - p(x0, y1)) * fx
                    out = out or ((Math.rint(top + (bottom - top) * fy).roundToInt() and 0xFF) shl shift)
                }
                result.pixels[ty * result.width + tx] = out
            }
        }
        return result
    }

    companion object {
        /** Bölge izinin boyutu (Windows: ReadScheduler.SignatureWidth/Height). */
        const val SIGNATURE_WIDTH = 64
        const val SIGNATURE_HEIGHT = 12
        /** İzdeki bir örnek bu kadar değişirse bölge değişmiş sayılır (Windows: ReadScheduler.ChangeThreshold). */
        const val CHANGE_THRESHOLD = 16

        /** Bölgenin küçültülmüş gri kopyası: iki kareyi yazı tanımaya gerek kalmadan karşılaştırmaya yeter. */
        fun signature(image: PixelImage): ByteArray {
            val samples = ByteArray(SIGNATURE_WIDTH * SIGNATURE_HEIGHT)
            if (image.width == 0 || image.height == 0) return samples
            for (y in 0 until SIGNATURE_HEIGHT) {
                val sy = y * image.height / SIGNATURE_HEIGHT
                for (x in 0 until SIGNATURE_WIDTH) {
                    val sx = x * image.width / SIGNATURE_WIDTH
                    samples[y * SIGNATURE_WIDTH + x] = ((image.blue(sx, sy) + image.green(sx, sy) * 2 + image.red(sx, sy)) / 4).toByte()
                }
            }
            return samples
        }

        /** İki iz farklı mı: bir örnek [threshold]'dan çok değiştiyse. */
        fun changed(a: ByteArray, b: ByteArray, threshold: Int = CHANGE_THRESHOLD): Boolean {
            if (a.size != b.size) return true
            for (i in a.indices) if (kotlin.math.abs((a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)) > threshold) return true
            return false
        }
    }
}
