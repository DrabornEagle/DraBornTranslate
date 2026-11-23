// SPDX-License-Identifier: AGPL-3.0-only
// Kurulum adımları Windows'taki SamsungTvInstaller.cs'den; sdb komutları ve paket kurulumu TizenSdb.Core'un
// SdbTcpDevice ve TizenInstaller'ından (MIT, Patrick Stel; https://github.com/PatrickSt1991/tizen-sdb) uyarlandı.
package com.gametranslator.gametranslator_mobile.tvinstall

import org.json.JSONObject
import java.io.File
import java.security.KeyPair
import java.security.cert.X509Certificate
import java.util.UUID

/** Samsung TV'nin http://TV:8001/api/v2/ yanıtından kurulum için gerekenler. */
class SamsungTvInfo(
    val name: String?,
    val modelName: String?,
    val os: String?,
    /** Geliştirici modu açık mı; TV söylemiyorsa null. */
    val developerMode: Boolean?,
    /** Geliştirici modunda girilen "Host PC IP"; yalnız bu adresten gelen sdb bağlantısı kabul edilir. */
    val developerIp: String?,
    /** Model kodunun başındaki yıl (ör. "20_PONTUSM_FHD" → 2020); bilinmiyorsa null. */
    val year: Int?,
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "name" to name, "model" to modelName, "os" to os, "developerMode" to developerMode, "developerIp" to developerIp, "year" to year,
    )

    companion object {
        fun parse(json: String): SamsungTvInfo? = runCatching {
            val device = JSONObject(json).optJSONObject("device") ?: return null
            fun text(name: String) = device.optString(name, "").trim().ifEmpty { null }
            val mode = text("developerMode")
            val year = text("model")?.let { Regex("^(\\d{2})_").find(it)?.groupValues?.get(1)?.toIntOrNull() }?.let { 2000 + it }
            SamsungTvInfo(
                name = text("name")?.replace("&quot;", "\""),
                modelName = text("modelName"),
                os = text("OS"),
                developerMode = when (mode) {
                    "1", "true" -> true
                    "0", "false" -> false
                    else -> null
                },
                developerIp = text("developerIP"),
                year = year,
            )
        }.getOrNull()

        fun fetch(host: String, timeoutMs: Int = 2000, cancel: Cancel = Cancel.NONE): SamsungTvInfo? =
            runCatching { parse(LanHttp.get(host, 8001, "/api/v2/", timeoutMs, cancel).text) }.getOrNull()

        /**
         * sdb bağlantısı kurulamadığında nedeni: geliştirici modu kapalı ya da "Host PC IP" bu telefonun adresi değil.
         * TV bilgi vermiyorsa null (genel "TV yanıt vermedi").
         */
        fun diagnose(info: SamsungTvInfo?, phoneAddresses: List<String>): TvInstallException? {
            if (info == null) return null
            if (info.developerMode == false) return TvInstallException(TvError.UNREACHABLE, "TV'de Developer mode kapalı")
            val ip = info.developerIp
            if (ip != null && phoneAddresses.isNotEmpty() && ip !in phoneAddresses) {
                return TvInstallException(TvError.WRONG_HOST_IP, "TV'deki Host PC IP $ip, telefonun adresi ${phoneAddresses.first()}")
            }
            return null
        }
    }
}

/**
 * GameTranslator'ın Samsung TV uygulamasını geliştirici modu açık TV'ye kurar: sdb ile bağlanır, TV'nin DUID'ini ve
 * Tizen sürümünü okur, gerekirse Samsung hesabıyla TV'ye özel sertifika alır, paketi imzalar, gönderir, kurar ve
 * açar. Samsung yalnız geliştirici modunda girilen IP'den gelen sdb bağlantısını kabul eder. Bütün çağrılar bloklar.
 */
class SamsungInstaller(
    private val host: String,
    private val profiles: File,
    private val cas: List<X509Certificate>,
    private val certificates: SamsungCertificates,
    private val cancel: Cancel,
    /** TV'ye bağlanıp karşılaşmayı yapar; testlerde sahte TV. */
    private val connector: () -> AdbClient,
    /** Bağlantı kurulamazsa nedeni bulur (REST bilgisi ve telefonun adresi). */
    private val diagnose: () -> TvInstallException? = { null },
) {
    var onProgress: (String) -> Unit = {}

    /** Sertifika gerekince Samsung hesabı; yoksa null (kurulum [TvError.SIGN_IN_NEEDED] ile durur). */
    var account: () -> SamsungAccount? = { null }

    /** TV'nin DUID'i ve bu TV için geçerli sertifika kayıtlı mı (giriş gerekmeden kurulabilir mi)? */
    fun status(): Pair<String, Boolean> = connect(attempts = 1).use { client ->
        val duid = readDuid(client)
        duid to SamsungProfile.of(profiles, duid).isValid()
    }

    /** Paketi imzalayıp kurar ve uygulamayı açar. [wgt]: imzasız ya da imzalı paket. Uygulama açıldıysa true. */
    fun install(wgt: ByteArray): Boolean = connect(attempts = 3).use { client ->
        onProgress("reading")
        val capabilities = parseCapability(cancel.guard { client.runRaw("capability:") })
        val platform = capabilities["platform_version"]?.trim().orEmpty()
        if (platform.isNotEmpty() && Versions.isNewer("3.0", platform)) throw TvInstallException(TvError.TOO_OLD, "Tizen $platform")
        val toolPath = capabilities["sdk_toolpath"]?.trim()?.ifEmpty { null } ?: "/opt/usr/apps/tmp"

        val duid = readDuid(client)
        var profile = SamsungProfile.of(profiles, duid)
        if (!profile.isValid()) {
            val signedIn = account() ?: throw TvInstallException(TvError.SIGN_IN_NEEDED, duid)
            onProgress("certificate")
            profile = certificates.create(duid, signedIn, profile, cancel)
        }

        onProgress("signing")
        val (author, distributor) = profile.identities(cas)
        val signed = SamsungSigner.sign(wgt, author, distributor)
        val remote = "$toolPath/gametranslator-${UUID.randomUUID().toString().replace("-", "")}.wgt"
        var log = attempt(client, signed, remote)
        if (installFailed(log)) {
            // Başka sertifikayla kurulmuş eski kopya varsa ("Author certificate not match") kaldırıp bir kez daha
            // dene. TV sürümüne göre kaldırma uygulama kimliğini ya da paket kimliğini bekliyor; ikisi de denenir.
            onProgress("uninstalling")
            val removal = StringBuilder()
            for (id in listOf(APP_ID, PACKAGE_ID)) {
                try {
                    removal.append(cancel.guard { client.run("shell:0 vd_appuninstall $id") }).append('\n')
                } catch (error: TvInstallException) {
                    if (error.error == TvError.CANCELLED) throw error
                    removal.append(error.message).append('\n')
                } catch (error: Exception) {
                    removal.append(error.message).append('\n')
                }
            }
            log = attempt(client, signed, remote)
            if (installFailed(log)) {
                val error = if (log.contains("not enough space", ignoreCase = true)) TvError.NOT_ENOUGH_SPACE else TvError.INSTALL_FAILED
                throw TvInstallException(error, tail(log) + "\nuninstall: " + tail(removal.toString()))
            }
        }
        onProgress("launching")
        // Açmak isteğe bağlı: açılmazsa kullanıcı uygulamalar listesinden açar.
        runCatching { cancel.guard { client.run("shell:0 execute $APP_ID") } }.isSuccess
    }

    /** Paketi gönderip kurar; TV kurulum hatasını çıktıda söyler, bağlantı hatası da çıktıya yazılır (C#'taki CaptureAsync). */
    private fun attempt(client: AdbClient, signed: ByteArray, remote: String): String = try {
        cancel.guard {
            var last = -1
            client.timeout(60_000)
            client.push(signed, remote) { sent ->
                val percent = (sent * 100L / signed.size).toInt()
                if (percent != last) {
                    last = percent
                    onProgress("uploading:$percent")
                }
            }
            onProgress("installing")
            client.timeout(300_000)
            client.run("shell:0 vd_appinstall $APP_ID $remote")
        }
    } catch (error: Exception) {
        if (cancel.cancelled) throw TvInstallException(TvError.CANCELLED)
        "install failed: ${error.message}"
    }

    private fun connect(attempts: Int): AdbClient {
        var last: Exception? = null
        for (attempt in 1..attempts) {
            cancel.check()
            try {
                return connector()
            } catch (error: Exception) {
                if (cancel.cancelled) throw TvInstallException(TvError.CANCELLED)
                last = error
                if (attempt < attempts) cancel.sleep(400)
            }
        }
        diagnose()?.let { throw it }
        throw last as? TvInstallException ?: TvInstallException(TvError.UNREACHABLE, last?.message, last)
    }

    private fun readDuid(client: AdbClient): String {
        // Yavaş TV'lerde ilk yanıt boş gelebiliyor; birkaç kez dene.
        repeat(3) {
            val duid = cancel.guard { client.run("shell:0 getduid") }.split('\n')[0].trim()
            if (isDuid(duid)) return duid
            cancel.sleep(500)
        }
        throw TvInstallException(TvError.UNREACHABLE, "DUID okunamadı")
    }

    companion object {
        const val SDB_PORT = 26101
        const val APP_ID = "GameTransT.GameTranslator"
        const val PACKAGE_ID = "GameTransT"

        private val DUID = Regex("^[A-Za-z0-9_\\-+=/]{8,}$")

        fun isDuid(text: String): Boolean = DUID.matches(text)

        /**
         * sdb "capability:" yanıtı: iki baytlık önek, sonra `ad:değer` satırları (TizenSdb.CapabilityAsync). İki
         * nokta içeren ya da değeri boş satırlar atlanır.
         */
        fun parseCapability(data: ByteArray): Map<String, String> {
            if (data.size < 2) return emptyMap()
            val out = LinkedHashMap<String, String>()
            for (line in String(data, 2, data.size - 2, Charsets.UTF_8).split('\n')) {
                val parts = line.split(':').filter { it.isNotEmpty() }
                if (parts.size == 2 && parts[0] !in out) out[parts[0]] = parts[1]
            }
            return out
        }

        /**
         * TV, kurulum başarısız olsa da komutu başarılı bitirir; hata çıktıdadır (ör. `install failed[118]`). İşaretler
         * Apps2Samsung'un TizenInstallDiagnostics'inden.
         */
        fun installFailed(log: String): Boolean =
            log.contains("install failed", ignoreCase = true) ||
                log.contains("val[fail]", ignoreCase = true) ||
                log.contains("not enough space", ignoreCase = true)

        fun tail(log: String): String = (if (log.length <= 400) log else log.substring(log.length - 400)).trim()

        /** Gerçek TV bağlantısı: sdb karşılaşması (TizenSdb'deki gibi "host::sdb-net-client", SHA-1 imzalı jeton). */
        fun connector(host: String, key: KeyPair, cancel: Cancel, connectTimeoutMs: Int = 5_000): () -> AdbClient = {
            AdbClient.connect(host, SDB_PORT, key, AdbOptions.SDB, cancel, approvalTimeoutMs = 15_000, connectTimeoutMs = connectTimeoutMs)
        }
    }
}
