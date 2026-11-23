// SPDX-License-Identifier: AGPL-3.0-only
// Xbox jeton biçimleri. Alan adları ve süre hesabı xal-node'daki gibidir (MIT, unknownskl; bkz. THIRD-PARTY-NOTICES.md):
// Windows köprüsünün sakladığı userToken JSON'u telefonda da aynı biçimdedir.
package com.gametranslator.gametranslator_mobile.xbox

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Xbox hatasının kullanıcıya anlatılabilecek türü. */
enum class XboxError {
    /** Microsoft girişi yok ya da yenileme jetonu geçersiz: yeniden giriş gerekir. */
    SIGN_IN_REQUIRED,
    /** Cihaz kodunun süresi doldu (15 dakika). */
    CODE_EXPIRED,
    /** Kullanıcı Microsoft sayfasında girişi reddetti. */
    SIGN_IN_DECLINED,
    /** Hesabın Xbox profili yok ya da çocuk hesabı izni gerekiyor (XSTS 401). */
    NO_XBOX_ACCOUNT,
    /** Konsol kapalı, uzaktan oynatma kapalı ya da konsol yayını açamadı. */
    CONSOLE_UNAVAILABLE,
    /** Konsol başka bir yayında. */
    BUSY,
    /** Yanıt beklenirken süre doldu. */
    TIMEOUT,
    /** İnternet ya da Xbox hizmeti erişilemiyor. */
    NETWORK,
    /** Xbox hizmeti beklenmedik yanıt verdi. */
    SERVICE,
}

class XboxException(val kind: XboxError, message: String, cause: Throwable? = null) : Exception(message, cause)

/** Microsoft kullanıcı jetonu: kalıcı saklanan tek jeton (Android Keystore, iPhone Anahtar Zinciri). */
class XboxUserToken(val json: JSONObject) {
    val accessToken: String get() = json.optString("access_token")
    val refreshToken: String get() = json.optString("refresh_token")
    /** xal-node `expires_on` (ISO 8601); yoksa süresi dolmuş sayılır. */
    val expiresOnMs: Long get() = parseInstant(json.optString("expires_on")) ?: 0L

    fun secondsValid(nowMs: Long): Long = Math.floorDiv(expiresOnMs - nowMs, 1000L)

    /** Saklanacak metin (Windows'taki userToken JSON'u). */
    fun serialize(): String = json.toString()

    companion object {
        /**
         * Microsoft jeton yanıtından: xal-node `TokenStore.setUserToken` gibi `expires_on = şimdi + expires_in`. Yenileme
         * yanıtında yenileme jetonu yoksa öncekisi korunur.
         */
        fun fromResponse(body: JSONObject, nowMs: Long, previous: XboxUserToken? = null): XboxUserToken {
            val access = body.optString("access_token")
            if (access.isEmpty()) throw XboxException(XboxError.SERVICE, "Microsoft jeton yanıtında access_token yok")
            val copy = JSONObject(body.toString())
            if (copy.optString("refresh_token").isEmpty()) previous?.refreshToken?.takeIf { it.isNotEmpty() }?.let { copy.put("refresh_token", it) }
            copy.put("expires_on", formatInstant(nowMs + body.optLong("expires_in", 0) * 1000))
            return XboxUserToken(copy)
        }

        /** Saklanan metinden; bozuksa ya da yenileme jetonu yoksa null (yeniden giriş). */
        fun parse(text: String?): XboxUserToken? {
            if (text.isNullOrBlank()) return null
            return try {
                XboxUserToken(JSONObject(text)).takeIf { it.refreshToken.isNotEmpty() }
            } catch (error: Exception) {
                null
            }
        }
    }
}

/** Xbox Live kullanıcı jetonu ya da XSTS jetonu (`Token`, `NotAfter`, `DisplayClaims.xui[0].uhs`). */
class XboxXstsToken(val token: String, val userHash: String, val notAfterMs: Long) {
    fun secondsValid(nowMs: Long): Long = Math.floorDiv(notAfterMs - nowMs, 1000L)

    companion object {
        fun parse(body: JSONObject): XboxXstsToken {
            val token = body.optString("Token")
            if (token.isEmpty()) throw XboxException(XboxError.SERVICE, "Xbox Live yanıtında Token yok")
            val uhs = body.optJSONObject("DisplayClaims")?.optJSONArray("xui")?.optJSONObject(0)?.optString("uhs").orEmpty()
            return XboxXstsToken(token, uhs, parseInstant(body.optString("NotAfter")) ?: 0L)
        }
    }
}

/** Ev yayını adresi ve jetonu (gssv `/v2/login/user` yanıtının varsayılan bölgesi). */
class XboxHomeEndpoint(val baseUrl: String, val token: String, val expiresAtMs: Long) {
    companion object {
        private const val DEFAULT_LIFETIME_MS = 30 * 60_000L

        fun parse(body: JSONObject, nowMs: Long): XboxHomeEndpoint {
            val regions = body.optJSONObject("offeringSettings")?.optJSONArray("regions")
            var chosen: JSONObject? = null
            if (regions != null) for (i in 0 until regions.length()) {
                val region = regions.optJSONObject(i) ?: continue
                if (region.optBoolean("isDefault")) { chosen = region; break }
            }
            if (chosen == null) chosen = regions?.optJSONObject(0)
            val base = chosen?.optString("baseUri").orEmpty().trimEnd('/')
            if (base.isEmpty()) throw XboxException(XboxError.SERVICE, "Xbox ev yayını bölgesi gelmedi")
            val token = body.optString("gsToken")
            if (token.isEmpty()) throw XboxException(XboxError.SERVICE, "Xbox ev yayını jetonu gelmedi")
            val seconds = body.optLong("durationInSeconds", 0)
            return XboxHomeEndpoint(base, token, nowMs + if (seconds > 0) seconds * 1000 else DEFAULT_LIFETIME_MS)
        }
    }
}

/** Microsoft cihaz kodu: kullanıcı [verificationUri]'de [userCode]'u yazar. */
class XboxDeviceCode(val userCode: String, val deviceCode: String, val verificationUri: String, val expiresInSeconds: Int, val intervalSeconds: Int) {
    companion object {
        fun parse(body: JSONObject): XboxDeviceCode {
            val user = body.optString("user_code")
            val device = body.optString("device_code")
            if (user.isEmpty() || device.isEmpty()) throw XboxException(XboxError.SERVICE, "Microsoft cihaz kodu gelmedi")
            return XboxDeviceCode(user, device, body.optString("verification_uri", "https://www.microsoft.com/link"),
                body.optInt("expires_in", 900), body.optInt("interval", 5).coerceAtLeast(1))
        }
    }
}

/** Ana hesaptaki bir Xbox (smartglass `/lists/devices`). */
data class XboxConsole(val id: String, val name: String, val model: String, val streamingEnabled: Boolean, val powerState: String = "") {
    fun toJson(): String = JSONObject().put("id", id).put("name", name).put("model", model)
        .put("streamingEnabled", streamingEnabled).put("powerState", powerState).toString()

    companion object {
        fun fromJson(text: String?): XboxConsole? {
            if (text.isNullOrBlank()) return null
            return try {
                val o = JSONObject(text)
                XboxConsole(o.getString("id"), o.optString("name"), o.optString("model"), o.optBoolean("streamingEnabled"), o.optString("powerState"))
            } catch (error: Exception) {
                null
            }
        }

        /** Windows köprüsündeki gibi: yayın için hem `consoleStreamingEnabled` hem `remoteManagementEnabled` açık olmalı. */
        fun parseList(body: JSONObject): List<XboxConsole> {
            val result = body.optJSONArray("result") ?: return emptyList()
            return (0 until result.length()).mapNotNull { i ->
                val c = result.optJSONObject(i) ?: return@mapNotNull null
                val id = c.optString("id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                XboxConsole(id, c.optString("name"), c.optString("consoleType"),
                    c.optBoolean("consoleStreamingEnabled") && c.optBoolean("remoteManagementEnabled"), c.optString("powerState"))
            }
        }
    }
}

// java.time (Instant) Android 8'den önce yok; uygulama Android 7'de de çalıştığı için ISO 8601 burada okunup yazılır.
private val ISO_INSTANT = Regex("""(\d{4})-(\d{2})-(\d{2})[Tt](\d{2}):(\d{2}):(\d{2})(?:[.,](\d{1,9}))?([Zz]|[+-]\d{2}:\d{2})""")

/** ISO 8601 anı (ör. "2026-11-16T10:16:00.1234567Z") → epoch ms; okunamıyorsa null. */
internal fun parseInstant(text: String?): Long? {
    if (text.isNullOrBlank()) return null
    val g = ISO_INSTANT.matchEntire(text.trim())?.groupValues ?: return null
    var year = g[1].toLong()
    val month = g[2].toInt()
    val day = g[3].toInt()
    val hour = g[4].toInt()
    val minute = g[5].toInt()
    val second = g[6].toInt()
    if (month !in 1..12 || day !in 1..31 || hour > 23 || minute > 59 || second > 59) return null
    val millis = if (g[7].isEmpty()) 0 else g[7].padEnd(3, '0').take(3).toInt()
    val zone = g[8]
    val offsetMinutes = if (zone.length == 1) 0
        else (zone.substring(1, 3).toInt() * 60 + zone.substring(4, 6).toInt()) * (if (zone[0] == '-') -1 else 1)
    // Takvim gününden epoch gününe (Gregoryen; H. Hinnant'ın days_from_civil hesabı).
    if (month <= 2) year -= 1
    val era = Math.floorDiv(year, 400L)
    val yearOfEra = year - era * 400
    val dayOfYear = (153 * (month + (if (month > 2) -3 else 9)) + 2) / 5 + day - 1
    val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
    val days = era * 146097 + dayOfEra - 719468
    return ((days * 24 + hour) * 60 + minute - offsetMinutes) * 60_000L + second * 1000L + millis
}

/** Epoch ms → ISO 8601 (UTC, "Z"); tam saniyede kesir yazılmaz (java.time `Instant.toString` gibi). */
internal fun formatInstant(ms: Long): String {
    val pattern = if (Math.floorMod(ms, 1000L) == 0L) "yyyy-MM-dd'T'HH:mm:ss'Z'" else "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
    return SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))
}
