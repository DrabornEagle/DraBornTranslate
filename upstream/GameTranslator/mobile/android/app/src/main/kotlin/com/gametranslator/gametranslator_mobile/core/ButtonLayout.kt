// SPDX-License-Identifier: AGPL-3.0-only
package com.gametranslator.gametranslator_mobile.core

import kotlin.math.abs

/** Ayardaki kumanda tuşları seçimi: Otomatik, PlayStation ya da Xbox (Windows: ButtonLayout.cs). */
enum class ButtonLayout {
    /** Görüntü kaynağından (Xbox / PS5) ya da ekrandaki simgelerin biçiminden anlaşılır. */
    AUTO,
    PLAYSTATION,
    XBOX;

    companion object {
        /** Kayıttaki ad ("auto", "playstation", "xbox"); bilinmiyorsa Otomatik. */
        fun parse(value: String?): ButtonLayout = when (value?.lowercase()) {
            "playstation" -> PLAYSTATION
            "xbox" -> XBOX
            else -> AUTO
        }
    }

    /** Kayıttaki adı. */
    val key: String get() = name.lowercase()
}

/** Ekrandaki tuş simgelerinin ailesi. */
enum class ButtonFamily { PLAYSTATION, XBOX }

/**
 * Simgeler hangi kumandanın: ayarda seçildiyse o, Otomatik'te kaynağa göre (Xbox kaynağı Xbox, PS5 Remote Play
 * PlayStation); başka kaynakta karelerdeki simgelerin biçiminden (dairede harf mi, PlayStation sembolü mü) karar verilir.
 * Karar yapışkandır: tek bir yanlış tanınan simge aileyi değiştirmez, birkaç tutarlı simgeden sonra değişir. Bilinmeden
 * önce PlayStation sayılır (Windows: ButtonLayoutVote).
 */
class ButtonLayoutVote(setting: ButtonLayout = ButtonLayout.AUTO) {
    companion object {
        /** Kararın değişmesi için gereken üst üste ağır basan simge sayısı. */
        const val SWITCH = 3
        private const val LIMIT = 6

        /**
         * Otomatik seçim kaynağa göre çözülür: [xboxSource] true ise Xbox, false ise PlayStation (PS5 Remote Play), null
         * ise görüntüden karar verilir.
         */
        fun resolve(setting: ButtonLayout, xboxSource: Boolean?): ButtonLayout = if (setting != ButtonLayout.AUTO) setting
        else when (xboxSource) {
            true -> ButtonLayout.XBOX
            false -> ButtonLayout.PLAYSTATION
            null -> ButtonLayout.AUTO
        }
    }

    private var score = 0
    private var sticky = ButtonFamily.PLAYSTATION

    /** Geçerli seçim (kaynağa göre çözülmüş). Değişince Otomatik karar sıfırlanır. */
    @Volatile var setting: ButtonLayout = setting
        set(value) {
            if (field == value) return
            field = value
            reset()
        }

    /** Şu an beklenen aile. */
    val current: ButtonFamily
        get() = when (setting) {
            ButtonLayout.PLAYSTATION -> ButtonFamily.PLAYSTATION
            ButtonLayout.XBOX -> ButtonFamily.XBOX
            ButtonLayout.AUTO -> sticky
        }

    /** Otomatik ve henüz karar verilmemiş mi. */
    val undecided: Boolean get() = setting == ButtonLayout.AUTO && abs(score) < SWITCH

    /** Karar Xbox'a yatıyor mu (seçilmiş ya da Otomatik'te Xbox simgesi daha çok görülmüş). */
    val leansXbox: Boolean get() = setting == ButtonLayout.XBOX || (setting == ButtonLayout.AUTO && score > 0)

    /** Kesin biçimli bir simge görüldü (dairede yeşil "A" Xbox'tır, üçgen PlayStation'dır). */
    @Synchronized
    fun add(seen: ButtonFamily) {
        if (setting != ButtonLayout.AUTO) return
        score = (score + if (seen == ButtonFamily.XBOX) 1 else -1).coerceIn(-LIMIT, LIMIT)
        if (score >= SWITCH) sticky = ButtonFamily.XBOX
        else if (score <= -SWITCH) sticky = ButtonFamily.PLAYSTATION
    }

    @Synchronized
    fun reset() {
        score = 0
        sticky = ButtonFamily.PLAYSTATION
    }
}

/**
 * Bütün platformların ortak tuş işaretleri. PlayStation: ✕ ○ △ □, L1–R3, OPTIONS, TOUCHPAD (▦). Xbox: Ⓐ Ⓑ Ⓧ Ⓨ,
 * LB RB LT RT LS RS, View ⧉ ve Menu ☰. Yön tuşu ikisinde de ↑ ↓ ← → ✜ (Windows: ButtonTokens).
 */
object ButtonTokens {
    const val A = "Ⓐ"
    const val B = "Ⓑ"
    const val X = "Ⓧ"
    const val Y = "Ⓨ"
    const val VIEW = "⧉"
    const val MENU = "☰"

    /** Xbox'ın omuz, tetik ve çubuk tuşları. */
    val xboxNames = setOf("LB", "RB", "LT", "RT", "LS", "RS")

    /** Xbox yüz tuşunun harfi ("A") → işareti ("Ⓐ"); yüz tuşu harfi değilse null. */
    fun xboxFace(letter: Char): String? = when (letter.uppercaseChar()) {
        'A' -> A
        'B' -> B
        'X' -> X
        'Y' -> Y
        else -> null
    }

    /** İşaret hangi ailenin: yön tuşu ve harita işareti ikisinde de olabilir (null). */
    fun familyOf(token: String): ButtonFamily? = when (token) {
        A, B, X, Y, VIEW, MENU -> ButtonFamily.XBOX
        in xboxNames -> ButtonFamily.XBOX
        "✕", "○", "△", "□", "▦", "OPTIONS", "TOUCHPAD", "L1", "L2", "L3", "R1", "R2", "R3" -> ButtonFamily.PLAYSTATION
        else -> null
    }

    /** Xbox yüz tuşunun rengine göre işareti (ton 0–360): yeşil Ⓐ, kırmızı Ⓑ, mavi Ⓧ, sarı Ⓨ. */
    internal fun xboxFaceByHue(hue: Double): String? = when {
        hue >= 75 && hue < 170 -> A
        hue >= 170 && hue < 265 -> X
        hue >= 38 && hue < 75 -> Y
        hue >= 330 || hue < 22 -> B
        else -> null
    }
}
