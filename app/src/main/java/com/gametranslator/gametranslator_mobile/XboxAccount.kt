// SPDX-License-Identifier: AGPL-3.0-only
package com.gametranslator.gametranslator_mobile

import android.content.Context
import com.gametranslator.gametranslator_mobile.core.UiText
import com.gametranslator.gametranslator_mobile.xbox.XboxAuth
import com.gametranslator.gametranslator_mobile.xbox.XboxConsole
import com.gametranslator.gametranslator_mobile.xbox.XboxDeviceCode
import com.gametranslator.gametranslator_mobile.xbox.XboxError
import com.gametranslator.gametranslator_mobile.xbox.XboxException
import com.gametranslator.gametranslator_mobile.xbox.XboxHome
import com.gametranslator.gametranslator_mobile.xbox.XboxUserToken
import java.util.concurrent.atomic.AtomicInteger

/**
 * Xbox (Beta) için Microsoft girişi ve konsol seçimi: arayüzün (Flutter) köprüden çağırdığı işler. Giriş cihaz
 * koduyla yapılır; parola Microsoft'un sayfasına yazılır, uygulama onu görmez. Kalıcı olan yalnız Microsoft kullanıcı
 * jetonudur ve [SecretStore]'da (Android Keystore) şifreli durur; başka hiçbir yere gönderilmez.
 */
object XboxAccount {
    private const val TOKEN = "xboxUserToken"

    @Volatile private var instance: XboxAuth? = null
    @Volatile private var pending: XboxDeviceCode? = null
    /** Her yeni giriş denemesi ve her iptal bunu artırır; eski bekleme kendini iptal edilmiş sayar. */
    private val attempt = AtomicInteger()

    /** Süreçte tek örnek: kullanıcı jetonu her yenilendiğinde Keystore'a yazılır, çıkışta silinir. */
    fun auth(context: Context): XboxAuth = instance ?: synchronized(this) {
        instance ?: run {
            val secrets = SecretStore(context.applicationContext)
            XboxAuth(userToken = XboxUserToken.parse(secrets.get(TOKEN)), onUserToken = { secrets.put(TOKEN, it?.serialize()) })
                .also { instance = it }
        }
    }

    /** Girişi başlatır (bloklar): kullanıcıya gösterilecek kod ve açılacak adres. */
    fun startSignIn(context: Context): Map<String, Any?> {
        val mine = attempt.incrementAndGet()
        pending = null
        return try {
            val code = auth(context).startDeviceCode()
            if (attempt.get() == mine) pending = code
            mapOf("ok" to true, "userCode" to code.userCode, "verificationUri" to code.verificationUri, "expiresIn" to code.expiresInSeconds)
        } catch (error: XboxException) {
            mapOf("ok" to false, "error" to describeSetup(error))
        } catch (error: Exception) {
            mapOf("ok" to false, "error" to (error.message ?: error.javaClass.simpleName))
        }
    }

    /** Kullanıcı kodu Microsoft'un sayfasında onaylayana kadar bekler (bloklar; en çok kodun süresi kadar). */
    fun awaitSignIn(context: Context): Map<String, Any?> {
        val code = pending ?: return mapOf("ok" to false, "error" to UiText.code("xs_start_first"))
        val mine = attempt.get()
        return try {
            auth(context).pollDeviceCode(code) { attempt.get() != mine }
            pending = null
            mapOf("ok" to true)
        } catch (error: InterruptedException) {
            mapOf("ok" to false, "cancelled" to true)
        } catch (error: XboxException) {
            pending = null
            mapOf("ok" to false, "error" to describeSetup(error))
        } catch (error: Exception) {
            pending = null
            mapOf("ok" to false, "error" to (error.message ?: error.javaClass.simpleName))
        }
    }

    fun cancelSignIn() {
        attempt.incrementAndGet()
        pending = null
    }

    /** Hesaptaki Xbox'lar (bloklar). */
    fun consoles(context: Context): Map<String, Any?> = try {
        val list = XboxHome(auth(context)).consoles()
        mapOf("ok" to true, "consoles" to list.map {
            mapOf("id" to it.id, "name" to it.name, "model" to it.model, "streamingEnabled" to it.streamingEnabled, "powerState" to it.powerState)
        })
    } catch (error: XboxException) {
        mapOf("ok" to false, "error" to describeSetup(error), "needsSignIn" to (error.kind == XboxError.SIGN_IN_REQUIRED))
    } catch (error: Exception) {
        mapOf("ok" to false, "error" to (error.message ?: error.javaClass.simpleName), "needsSignIn" to false)
    }

    fun selectConsole(context: Context, console: XboxConsole) {
        Store(context.applicationContext).xboxConsole = console
    }

    /** Çıkış: Microsoft jetonu Keystore'dan, seçili Xbox kayıttan silinir. */
    fun signOut(context: Context) {
        cancelSignIn()
        auth(context).signOut()
        Store(context.applicationContext).xboxConsole = null
    }

    /**
     * Kurulum ekranlarında gösterilecek nedenin kodu (metni Flutter seçer: lib/l10n/, `n.xs_*`); beklenmedik yanıtta
     * teknik ayrıntı parantez içinde gösterilir.
     */
    private fun describeSetup(error: XboxException): String = when (error.kind) {
        XboxError.SIGN_IN_REQUIRED, XboxError.SIGN_IN_DECLINED -> UiText.code("xs_signin_incomplete")
        XboxError.CODE_EXPIRED -> UiText.code("xs_code_expired")
        XboxError.NO_XBOX_ACCOUNT -> UiText.code("x_no_profile")
        XboxError.NETWORK -> UiText.code("xs_network")
        else -> UiText.code("xs_service", error.message ?: error.kind.name)
    }

    /** Çeviri sırasında ana ekranda gösterilecek nedenin kodu (`n.xr_*`). */
    fun describeStream(kind: XboxError): String = when (kind) {
        XboxError.SIGN_IN_REQUIRED, XboxError.CODE_EXPIRED, XboxError.SIGN_IN_DECLINED -> UiText.code("xr_signin_expired")
        XboxError.NO_XBOX_ACCOUNT -> UiText.code("x_no_profile")
        XboxError.CONSOLE_UNAVAILABLE -> UiText.code("xr_unavailable")
        XboxError.BUSY -> UiText.code("xr_busy")
        XboxError.TIMEOUT -> UiText.code("xr_timeout")
        XboxError.NETWORK -> UiText.code("xr_network")
        XboxError.SERVICE -> UiText.code("xr_service")
    }
}
