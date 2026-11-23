// SPDX-License-Identifier: AGPL-3.0-only
// Samsung'un sertifika uç noktaları ve istek biçimleri Apps2Samsung'dan (MIT, Patrick Stel) alındı:
// https://github.com/Jellyfin2Samsung/Samsung-Jellyfin-Installer — Windows'taki SamsungCertificates.cs'nin karşılığı.
package com.gametranslator.gametranslator_mobile.tvinstall

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.util.Date
import java.util.UUID

/** Samsung hesabıyla girişten dönen bilgiler; sertifika istemek için gerekir. */
class SamsungAccount(val accessToken: String, val userId: String, val email: String)

/** En küçük DER yazıcısı: sertifika isteği (PKCS#10) için gerekenler. */
object Der {
    fun tlv(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val n = content.size
        when {
            n < 0x80 -> out.write(n)
            n < 0x100 -> { out.write(0x81); out.write(n) }
            n < 0x10000 -> { out.write(0x82); out.write(n ushr 8); out.write(n and 0xFF) }
            else -> { out.write(0x83); out.write(n ushr 16); out.write((n ushr 8) and 0xFF); out.write(n and 0xFF) }
        }
        out.write(content)
        return out.toByteArray()
    }

    fun seq(vararg items: ByteArray) = tlv(0x30, concat(items))
    fun set(vararg items: ByteArray) = tlv(0x31, concat(items))
    fun int(value: Int) = tlv(0x02, byteArrayOf(value.toByte()))
    fun nul() = byteArrayOf(0x05, 0x00)
    fun utf8(text: String) = tlv(0x0C, text.toByteArray(Charsets.UTF_8))
    fun printable(text: String) = tlv(0x13, text.toByteArray(Charsets.US_ASCII))
    fun ia5(text: String, tag: Int = 0x16) = tlv(tag, text.toByteArray(Charsets.US_ASCII))
    fun octets(data: ByteArray) = tlv(0x04, data)
    fun bits(data: ByteArray) = tlv(0x03, byteArrayOf(0) + data)

    fun oid(text: String): ByteArray {
        val parts = text.split('.').map { it.toLong() }
        val out = ByteArrayOutputStream()
        out.write((parts[0] * 40 + parts[1]).toInt())
        for (part in parts.drop(2)) {
            val groups = ArrayList<Int>()
            var v = part
            do {
                groups.add((v and 0x7F).toInt())
                v = v ushr 7
            } while (v > 0)
            for (i in groups.indices.reversed()) out.write(groups[i] or (if (i > 0) 0x80 else 0))
        }
        return tlv(0x06, out.toByteArray())
    }

    private fun concat(items: Array<out ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        for (item in items) out.write(item)
        return out.toByteArray()
    }
}

/**
 * Sertifika istekleri (PKCS#10, PEM). .NET'in CertificateRequest'inin ürettiğiyle bayt bayt aynıdır: yazar isteğinde
 * CN PrintableString; dağıtıcı isteğinde önce e-posta (IA5String) sonra CN (UTF8String) ve TV'nin DUID'ini taşıyan
 * subjectAltName (URI adları büyük harfle, elle yazılır).
 */
object SamsungCsr {
    private const val CN = "2.5.4.3"
    private const val EMAIL = "1.2.840.113549.1.9.1"
    private const val EXTENSION_REQUEST = "1.2.840.113549.1.9.14"
    private const val SUBJECT_ALT_NAME = "2.5.29.17"
    private const val SHA256_RSA = "1.2.840.113549.1.1.11"

    fun author(key: KeyPair): ByteArray {
        val subject = Der.seq(Der.set(Der.seq(Der.oid(CN), Der.printable("GameTranslator"))))
        return pem(key, subject, Der.tlv(0xA0, ByteArray(0)))
    }

    fun distributor(key: KeyPair, duid: String, email: String): ByteArray {
        val names = ArrayList<ByteArray>()
        if (email.isNotBlank()) names.add(Der.set(Der.seq(Der.oid(EMAIL), Der.ia5(email))))
        names.add(Der.set(Der.seq(Der.oid(CN), Der.utf8("TizenSDK"))))
        val subject = Der.seq(*names.toTypedArray())
        val altNames = Der.seq(Der.ia5("URN:tizen:packageid=", 0x86), Der.ia5("URN:tizen:deviceid=$duid", 0x86))
        val extension = Der.seq(Der.oid(SUBJECT_ALT_NAME), Der.octets(altNames))
        val attributes = Der.tlv(0xA0, Der.seq(Der.oid(EXTENSION_REQUEST), Der.set(Der.seq(extension))))
        return pem(key, subject, attributes)
    }

    private fun pem(key: KeyPair, subject: ByteArray, attributes: ByteArray): ByteArray {
        val info = Der.seq(Der.int(0), subject, key.public.encoded, attributes)
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(key.private)
            update(info)
            sign()
        }
        val der = Der.seq(info, Der.seq(Der.oid(SHA256_RSA), Der.nul()), Der.bits(signature))
        val body = kotlin.io.encoding.Base64.Default.encode(der).chunked(64).joinToString("\n")
        return "-----BEGIN CERTIFICATE REQUEST-----\n$body\n-----END CERTIFICATE REQUEST-----".toByteArray(Charsets.US_ASCII)
    }
}

/** Samsung'un geliştirici sertifikalarını veren ara sertifikalar (Tizen Studio'dan, değiştirilmeden; assets/samsung-ca). */
object SamsungCa {
    val NAMES = listOf("vd_tizen_dev_author_ca", "author_ca", "vd_tizen_dev_public2", "public2")

    fun load(open: (String) -> InputStream?): List<X509Certificate> = NAMES.mapNotNull { name ->
        runCatching { open("samsung-ca/$name.pem")?.use { certificate(it.readBytes()) } }.getOrNull()
    }

    /** PEM ya da DER sertifika. */
    fun certificate(data: ByteArray): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(data)) as X509Certificate

    /**
     * Yaprağı veren ara sertifika. Windows sürümü adı eşleşen ilkini alır; burada aynı adlı iki sertifikadan yaprağın
     * imzasını doğrulayan seçilir, doğrulayan yoksa adı eşleşen ilki.
     */
    fun intermediate(leaf: X509Certificate, cas: List<X509Certificate>): X509Certificate? {
        val matches = cas.filter { it.subjectX500Principal == leaf.issuerX500Principal && it.subjectX500Principal != it.issuerX500Principal }
        return matches.firstOrNull { ca -> runCatching { leaf.verify(ca.publicKey) }.isSuccess } ?: matches.firstOrNull()
    }
}

/**
 * Bir TV için imza dosyaları: anahtar (PKCS#8), yazar ve dağıtıcı sertifikası. Klasör TV'nin DUID'ine göredir;
 * dağıtıcı sertifikası o TV'ye, yazar sertifikası Samsung hesabına bağlıdır. Sonraki kurulum ve güncellemeler giriş
 * istemez.
 */
class SamsungProfile(val dir: File) {
    val keyFile = File(dir, "key.pk8")
    val authorFile = File(dir, "author.crt")
    val distributorFile = File(dir, "distributor.crt")

    val exists: Boolean get() = keyFile.isFile && authorFile.isFile && distributorFile.isFile

    val email: String get() = runCatching { File(dir, "email.txt").readText().trim() }.getOrDefault("")

    /** Sertifikalar hâlâ geçerli mi (bir gün pay bırakılarak)? */
    fun isValid(now: Date = Date()): Boolean {
        if (!exists) return false
        return try {
            val margin = Date(now.time + 24L * 3600 * 1000)
            listOf(authorFile, distributorFile).all {
                val certificate = SamsungCa.certificate(it.readBytes())
                !certificate.notBefore.after(now) && !certificate.notAfter.before(margin)
            } && key() != null
        } catch (error: Exception) {
            false
        }
    }

    fun key(): KeyPair? = runCatching {
        val factory = KeyFactory.getInstance("RSA")
        val private = factory.generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes())) as RSAPrivateCrtKey
        KeyPair(factory.generatePublic(RSAPublicKeySpec(private.modulus, private.publicExponent)), private)
    }.getOrNull()

    /** İmzalayanlar: yaprak sertifika ve onu veren Samsung ara sertifikası (TizenResigner.GetKeyInfoChain). */
    fun identities(cas: List<X509Certificate>): Pair<SamsungSigner.Identity, SamsungSigner.Identity> {
        val key = key() ?: throw TvInstallException(TvError.CERTIFICATE_FAILED, "imza anahtarı okunamadı")
        fun identity(file: File): SamsungSigner.Identity {
            val leaf = SamsungCa.certificate(file.readBytes())
            return SamsungSigner.Identity(key.private, listOfNotNull(leaf, SamsungCa.intermediate(leaf, cas)))
        }
        return identity(authorFile) to identity(distributorFile)
    }

    fun save(key: KeyPair, author: X509Certificate, distributor: X509Certificate, deviceProfile: ByteArray, duid: String, email: String) {
        dir.mkdirs()
        writeAtomic(File(dir, "device-profile.xml"), deviceProfile)
        writeAtomic(File(dir, "duid.txt"), duid.toByteArray())
        writeAtomic(File(dir, "email.txt"), email.toByteArray())
        writeAtomic(authorFile, author.encoded)
        writeAtomic(distributorFile, distributor.encoded)
        writeAtomic(keyFile, key.private.encoded)
    }

    private fun writeAtomic(file: File, data: ByteArray) {
        val temp = File(file.path + ".tmp")
        temp.writeBytes(data)
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
    }

    companion object {
        /** Profil klasörünün adı: DUID'deki dosya adına uymayan karakterler "_" olur. */
        fun folderName(duid: String): String = duid.replace(Regex("[^A-Za-z0-9_\\-]"), "_")

        fun of(root: File, duid: String) = SamsungProfile(File(root, folderName(duid)))
    }
}

/**
 * Samsung'dan TV'ye özel imza sertifikası alır (Tizen Studio'nun Certificate Manager'ının yaptığı). [post] testlerde
 * değiştirilir: (adres, alanlar, CSR, dosya adı) → yanıt gövdesi.
 */
class SamsungCertificates(
    private val post: (String, List<Pair<String, String>>, ByteArray, String, Cancel) -> ByteArray = ::multipartPost,
) {
    fun create(duid: String, account: SamsungAccount, profile: SamsungProfile, cancel: Cancel): SamsungProfile {
        val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val authorCsr = SamsungCsr.author(key)
        val distributorCsr = SamsungCsr.distributor(key, duid, account.email)
        val base = listOf("access_token" to account.accessToken, "user_id" to account.userId, "platform" to "VD")
        val distributorFields = base + listOf("privilege_level" to "public", "developer_type" to "Individual")

        val author = post(AUTHOR_URL, base, authorCsr, "author.csr", cancel)
        // Eski TV'ler (Tizen 4 ve öncesi) için cihaz profili; yenilerde kullanılmaz ama saklanır.
        val deviceProfile = post(DISTRIBUTOR_PROFILE_URL, distributorFields, distributorCsr, "distributor.csr", cancel)
        val distributor = post(DISTRIBUTOR_URL, distributorFields, distributorCsr, "distributor.csr", cancel)
        val authorCertificate = parse(author, "yazar")
        val distributorCertificate = parse(distributor, "dağıtıcı")
        profile.save(key, authorCertificate, distributorCertificate, deviceProfile, duid, account.email)
        return profile
    }

    private fun parse(data: ByteArray, what: String): X509Certificate = try {
        SamsungCa.certificate(data)
    } catch (error: Exception) {
        throw TvInstallException(TvError.CERTIFICATE_FAILED, "$what sertifikası okunamadı: ${String(data, 0, minOf(data.size, 200), Charsets.UTF_8)}", error)
    }

    companion object {
        const val AUTHOR_URL = "https://svdca.samsungqbe.com/apis/v3/authors"
        const val DISTRIBUTOR_PROFILE_URL = "https://svdca.samsungqbe.com/apis/v1/distributors"
        const val DISTRIBUTOR_URL = "https://svdca.samsungqbe.com/apis/v3/distributors"

        /** multipart/form-data gövdesi (.NET MultipartFormDataContent'in gönderdiği alanlar). */
        fun multipartBody(boundary: String, fields: List<Pair<String, String>>, file: ByteArray, fileName: String): ByteArray {
            val out = ByteArrayOutputStream()
            fun line(text: String) = out.write((text + "\r\n").toByteArray(Charsets.UTF_8))
            for ((name, value) in fields) {
                line("--$boundary")
                line("Content-Type: text/plain; charset=utf-8")
                line("Content-Disposition: form-data; name=\"$name\"")
                line("")
                line(value)
            }
            line("--$boundary")
            line("Content-Disposition: form-data; name=\"csr\"; filename=\"$fileName\"")
            line("Content-Type: application/octet-stream")
            line("")
            out.write(file)
            line("")
            line("--$boundary--")
            return out.toByteArray()
        }

        fun multipartPost(url: String, fields: List<Pair<String, String>>, file: ByteArray, fileName: String, cancel: Cancel): ByteArray {
            cancel.check()
            val boundary = UUID.randomUUID().toString()
            val body = multipartBody(boundary, fields, file, fileName)
            val connection = URL(url).openConnection() as HttpURLConnection
            val closer = Closeable { connection.disconnect() }
            cancel.track(closer)
            try {
                return cancel.guard {
                    connection.connectTimeout = 20_000
                    connection.readTimeout = 60_000
                    connection.requestMethod = "POST"
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=\"$boundary\"")
                    connection.setFixedLengthStreamingMode(body.size)
                    val status = try {
                        connection.outputStream.use { it.write(body) }
                        connection.responseCode
                    } catch (error: java.io.IOException) {
                        throw TvInstallException(TvError.CERTIFICATE_FAILED, "Samsung'a ulaşılamadı: ${error.message}", error)
                    }
                    val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                    val reply = stream?.use { it.readBytes() } ?: ByteArray(0)
                    if (status !in 200..299) {
                        throw TvInstallException(TvError.CERTIFICATE_FAILED, "$status ${String(reply, 0, minOf(reply.size, 300), Charsets.UTF_8)}")
                    }
                    reply
                }
            } finally {
                cancel.untrack(closer)
                connection.disconnect()
            }
        }
    }
}
