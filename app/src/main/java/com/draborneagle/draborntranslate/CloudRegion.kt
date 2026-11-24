// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

/** Independent normalized region, shared by the view and view-coordinate OCR. */
internal data class CloudRegion(val left: Int, val top: Int, val width: Int, val height: Int) {
    fun normalized(): CloudRegion {
        val l = left.coerceIn(0, 95)
        val t = top.coerceIn(0, 95)
        return CloudRegion(l, t, width.coerceIn(5, 100-l), height.coerceIn(5, 100-t))
    }
    fun move(dx: Int, dy: Int): CloudRegion {
        val r = normalized()
        return r.copy(left = (r.left+dx).coerceIn(0, 100-r.width), top = (r.top+dy).coerceIn(0, 100-r.height))
    }
    fun pixels(frame: Pixels): Pixels {
        val r = normalized()
        return percentRect(frame, r.left, r.top, r.width, r.height)
    }
}
