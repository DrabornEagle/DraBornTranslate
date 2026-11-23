// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import android.content.Context
import com.gametranslator.gametranslator_mobile.core.PackKey
import com.gametranslator.gametranslator_mobile.core.PackMatch

/**
 * Human-reviewed corrections are private, offline and take precedence over
 * community packs. No English source lines or corrected dialogues are uploaded.
 */
internal class LocalTranslationCorrections(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "draborn_user_translation_corrections_v1", Context.MODE_PRIVATE
    )

    fun find(source: String): PackMatch? {
        val key = key(source) ?: return null
        return preferences.getString(key, null)
            ?.takeIf { it.isNotBlank() }
            ?.let { PackMatch(it, "draborn-user-correction") }
    }

    fun save(source: String, turkish: String): Boolean {
        val key = key(source) ?: return false
        val normalized = turkish.trim()
        if (source.length !in 1..1500 || normalized.length !in 1..2400) return false
        return preferences.edit().putString(key, normalized).commit()
    }

    fun delete(source: String): Boolean {
        val key = key(source) ?: return false
        return preferences.edit().remove(key).commit()
    }

    private fun key(source: String): String? {
        if (source.length !in 1..1500) return null
        return PackKey.key(source)?.let { "line_$it" }
    }
}
