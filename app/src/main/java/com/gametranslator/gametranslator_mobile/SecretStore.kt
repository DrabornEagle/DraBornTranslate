// SPDX-License-Identifier: AGPL-3.0-only
package com.gametranslator.gametranslator_mobile

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Gizli değerler (Microsoft giriş jetonu) Android Keystore'daki (donanım destekli, dışarı çıkarılamayan) AES-GCM
 * anahtarıyla şifrelenip saklanır. Windows'taki DPAPI karşılığı. Uygulama silinince gider. CouchSubs'ın Android
 * uygulamasındaki SecretStore'dan taşındı.
 */
class SecretStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("gametranslator-secrets", Context.MODE_PRIVATE)
    private val alias = "gametranslator-secrets"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    /** [value] null ya da boşsa kayıt silinir. */
    fun put(name: String, value: String?) {
        if (value.isNullOrEmpty()) {
            prefs.edit().remove(name).apply()
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(name, Base64.encodeToString(sealed, Base64.NO_WRAP)).apply()
    }

    fun get(name: String): String? {
        val stored = prefs.getString(name, null) ?: return null
        return try {
            val sealed = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, 12))
            String(cipher.doFinal(sealed, 12, sealed.size - 12), Charsets.UTF_8)
        } catch (error: Exception) {
            // Anahtar silinmiş (ör. yedekten geri yükleme): değer okunamaz, yeniden giriş yapılır.
            null
        }
    }
}
