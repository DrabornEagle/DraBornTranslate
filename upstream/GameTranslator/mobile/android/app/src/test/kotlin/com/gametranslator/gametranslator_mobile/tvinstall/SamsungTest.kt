// SPDX-License-Identifier: AGPL-3.0-only
package com.gametranslator.gametranslator_mobile.tvinstall

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.util.Date
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread

/**
 * Samsung kurulumu. Beklenen dosyalar (test/resources/fixtures/samsung) Windows uygulamasının kullandığı TizenSdb.Core
 * 1.1.9 ve SamsungCertificates.cs ile aynı anahtar ve sertifikalardan üretildi: imza XML'leri ve sertifika istekleri
 * bayt bayt aynı olmalı (RSA PKCS#1 v1.5 imzası belirlenimcidir).
 */
class SamsungTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun fixture(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("fixtures/samsung/$name")!!.use { it.readBytes() }

    private val key: KeyPair by lazy {
        val factory = KeyFactory.getInstance("RSA")
        val private = factory.generatePrivate(PKCS8EncodedKeySpec(fixture("key.pk8"))) as RSAPrivateCrtKey
        KeyPair(factory.generatePublic(RSAPublicKeySpec(private.modulus, private.publicExponent)), private)
    }

    private val cas by lazy { SamsungCa.load { File("src/main/assets/$it").takeIf { f -> f.isFile }?.inputStream() } }

    private fun identity(cert: String): SamsungSigner.Identity {
        val leaf = SamsungCa.certificate(fixture(cert))
        return SamsungSigner.Identity(key.private, listOfNotNull(leaf, SamsungCa.intermediate(leaf, cas)))
    }

    @Test
    fun loadsBundledCas() {
        assertEquals(4, cas.size)
        val author = SamsungCa.certificate(fixture("author.crt"))
        assertTrue(SamsungCa.intermediate(author, cas)!!.subjectX500Principal.name.contains("Samsung VD Author CA"))
    }

    @Test
    fun authorSignatureMatchesTizenSdb() {
        val files = SamsungSigner.readEntries(fixture("input.wgt"))
        assertEquals(listOf("config.xml", "index.html", "img/icon.png", "js/app.js"), files.map { it.name })
        val xml = SamsungSigner.signatureXml(SamsungSigner.AUTHOR, files, identity("author.crt"))
        assertEquals(String(fixture("author-signature.xml")), String(xml))
        assertArrayEquals(fixture("author-signature.xml"), xml)
    }

    @Test
    fun distributorSignatureMatchesTizenSdb() {
        val files = SamsungSigner.readEntries(fixture("input.wgt"))
        val all = listOf(SamsungSigner.Entry("author-signature.xml", fixture("author-signature.xml"))) + files
        val xml = SamsungSigner.signatureXml(SamsungSigner.DISTRIBUTOR, all, identity("distributor.crt"))
        assertEquals(String(fixture("signature1.xml")), String(xml))
    }

    @Test
    fun signedPackageIsValidZipWithSignaturesFirst() {
        val signed = SamsungSigner.sign(fixture("input.wgt"), identity("author.crt"), identity("distributor.crt"))
        val names = ArrayList<String>()
        val contents = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(signed)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                names.add(entry.name)
                contents[entry.name] = zip.readBytes()
            }
        }
        assertEquals(listOf("author-signature.xml", "signature1.xml", "config.xml", "index.html", "img/icon.png", "js/app.js"), names)
        assertArrayEquals(fixture("author-signature.xml"), contents["author-signature.xml"])
        assertArrayEquals(fixture("signature1.xml"), contents["signature1.xml"])
        // Yeniden imzalamak eski imzaları atar, aynı sonucu verir.
        val again = SamsungSigner.sign(signed, identity("author.crt"), identity("distributor.crt"))
        assertEquals(SamsungSigner.readEntries(signed).map { it.name }, SamsungSigner.readEntries(again).map { it.name })
        // Yerel başlıklarda veri tanımlayıcısı yok (bayrak 3. bit).
        val flags = ByteBuffer.wrap(signed, 6, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
        assertEquals(0, flags and 0x08)
    }

    @Test
    fun signatureVerifiesOverCanonicalSignedInfo() {
        val xml = String(fixture("author-signature.xml"))
        val signedInfo = xml.substringAfter("<Signature Id=\"AuthorSignature\" xmlns=\"http://www.w3.org/2000/09/xmldsig#\">")
            .substringBefore("<SignatureValue>")
            .replace(Regex("<(\\w+) (\\w+)=\"([^\"]*)\" />"), "<$1 $2=\"$3\"></$1>")
            .replaceFirst("<SignedInfo>", "<SignedInfo xmlns=\"http://www.w3.org/2000/09/xmldsig#\">")
        val value = kotlin.io.encoding.Base64.Default.decode(xml.substringAfter("<SignatureValue>").substringBefore("</SignatureValue>"))
        val verifier = Signature.getInstance("SHA512withRSA")
        verifier.initVerify(SamsungCa.certificate(fixture("author.crt")).publicKey)
        verifier.update(signedInfo.toByteArray())
        assertTrue(verifier.verify(value))
    }

    @Test
    fun certificateRequestsMatchDotNet() {
        assertEquals(String(fixture("author.csr")), String(SamsungCsr.author(key)))
        assertEquals(String(fixture("distributor.csr")), String(SamsungCsr.distributor(key, "TESTDUID0123456789", "user@example.com")))
    }

    @Test
    fun derEncodesLengthsAndOids() {
        assertArrayEquals(byteArrayOf(0x06, 0x03, 0x55, 0x1D, 0x11), Der.oid("2.5.29.17"))
        assertArrayEquals(byteArrayOf(0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x09, 0x0E), Der.oid("1.2.840.113549.1.9.14"))
        assertEquals(0x81, Der.tlv(0x04, ByteArray(200))[1].toInt() and 0xFF)
        assertEquals(0x82, Der.tlv(0x04, ByteArray(300))[1].toInt() and 0xFF)
    }

    @Test
    fun parsesSignInCallback() {
        val json = """{"access_token":"tok+en","userId":"u1","inputEmailID":"a@b.c"}"""
        val body = "code=" + java.net.URLEncoder.encode(json, "UTF-8").replace("+", "%20") + "&state=accountcheckdogeneratedstatetext"
        val account = SamsungLogin.parseCallback(body)!!
        assertEquals("tok+en", account.accessToken)
        assertEquals("u1", account.userId)
        assertEquals("a@b.c", account.email)
        assertNull(SamsungLogin.parseCallback(body.replace("accountcheck", "other")))
        assertNull(SamsungLogin.parseCallback("code=%7B%7D&state=accountcheckdogeneratedstatetext"))
        assertNull(SamsungLogin.parseCallback("code=bozuk&state=accountcheckdogeneratedstatetext"))
        assertTrue(SamsungLogin.authorizeUrl.contains("redirect_uri=http%3A%2F%2Flocalhost%3A4794%2Fsignin%2Fcallback"))
        assertEquals("a+b ü%", SamsungLogin.percentDecode("a+b%20%C3%BC%"))
    }

    @Test
    fun listenerReceivesCallbackOnLocalhost() {
        val listener = try {
            SamsungLogin.Listener().start()
        } catch (error: TvInstallException) {
            return // port bu makinede dolu
        }
        listener.use {
            Socket("127.0.0.1", SamsungLogin.CALLBACK_PORT).use { socket ->
                socket.getOutputStream().write("GET /favicon.ico HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
                assertTrue(String(socket.getInputStream().readBytes()).startsWith("HTTP/1.1 404"))
            }
            val body = "code=%7B%22access_token%22%3A%22t%22%2C%22userId%22%3A%22u%22%7D&state=accountcheckdogeneratedstatetext"
            Socket("127.0.0.1", SamsungLogin.CALLBACK_PORT).use { socket ->
                socket.getOutputStream().write(
                    ("POST /signin/callback HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/x-www-form-urlencoded\r\n" +
                        "Content-Length: ${body.length}\r\n\r\n$body").toByteArray(),
                )
                assertTrue(String(socket.getInputStream().readBytes()).startsWith("HTTP/1.1 200"))
            }
            assertEquals("t", listener.await(5000, Cancel()).accessToken)
        }
        val aborted = SamsungLogin.Listener()
        aborted.abort("kapatıldı")
        try {
            aborted.await(1000, Cancel())
            fail()
        } catch (error: TvInstallException) {
            assertEquals(TvError.SIGN_IN_FAILED, error.error)
        }
    }

    @Test
    fun parsesTvInfoAndDiagnoses() {
        val json = """{"device":{"OS":"Tizen","developerIP":"192.168.1.20","developerMode":"1","model":"20_PONTUSM_FHD",
            "modelName":"F27T850","name":"[Monitor] Samsung"},"name":"x"}"""
        val info = SamsungTvInfo.parse(json)!!
        assertEquals(true, info.developerMode)
        assertEquals("192.168.1.20", info.developerIp)
        assertEquals(2020, info.year)
        assertEquals("F27T850", info.modelName)
        assertNull(SamsungTvInfo.diagnose(info, listOf("192.168.1.20")))
        assertEquals(TvError.WRONG_HOST_IP, SamsungTvInfo.diagnose(info, listOf("192.168.1.30"))!!.error)
        val off = SamsungTvInfo.parse("""{"device":{"developerMode":"0","developerIP":"0.0.0.0"}}""")!!
        assertEquals(false, off.developerMode)
        assertNull(off.year)
        assertEquals(TvError.UNREACHABLE, SamsungTvInfo.diagnose(off, listOf("192.168.1.30"))!!.error)
        assertNull(SamsungTvInfo.parse("bozuk"))
        assertNull(SamsungTvInfo.diagnose(null, listOf("192.168.1.30")))
    }

    @Test
    fun parsesCapabilityAndDuid() {
        val caps = SamsungInstaller.parseCapability("\u0000\u0001secure_protocol:enabled\nplatform_version:6.0\nsdk_toolpath:/opt/usr/apps/tmp\nempty:\nx:y:z\n".toByteArray())
        assertEquals("6.0", caps["platform_version"])
        assertEquals("/opt/usr/apps/tmp", caps["sdk_toolpath"])
        assertFalse("empty" in caps)
        assertFalse("x" in caps)
        assertTrue(SamsungInstaller.parseCapability(byteArrayOf(1)).isEmpty())
        assertTrue(SamsungInstaller.isDuid("MWCQ3SZJ5NYUQ"))
        assertFalse(SamsungInstaller.isDuid("closed"))
        assertFalse(SamsungInstaller.isDuid("error: x y"))
        assertTrue(SamsungInstaller.installFailed("spend time for pkgcmd is [100]ms\ninstall failed[118, -12], reason: Author certificate not match"))
        assertFalse(SamsungInstaller.installFailed("start process (install)\nprocessing result : Ok [0]\nspend time"))
        assertEquals("MWCQ3SZJ5NYUQ_a_b", SamsungProfile.folderName("MWCQ3SZJ5NYUQ/a+b"))
    }

    @Test
    fun profileValidity() {
        val profile = SamsungProfile(temp.newFolder("p"))
        assertFalse(profile.isValid())
        profile.save(key, SamsungCa.certificate(fixture("author.crt")), SamsungCa.certificate(fixture("distributor.crt")), ByteArray(0), "DUID1234", "a@b.c")
        assertTrue(profile.isValid(Date()))
        assertEquals("a@b.c", profile.email)
        assertFalse(profile.isValid(Date(2_300_000_000_000L))) // 2042: süresi dolmuş
        val (author, distributor) = profile.identities(cas)
        assertEquals(2, author.chain.size)
        assertEquals(2, distributor.chain.size)
    }

    @Test
    fun multipartBodyHasFieldsAndFile() {
        val body = String(SamsungCertificates.multipartBody("B", listOf("access_token" to "t", "platform" to "VD"), "CSR".toByteArray(), "author.csr"))
        assertTrue(body.startsWith("--B\r\n"))
        assertTrue(body.contains("name=\"access_token\"\r\n\r\nt\r\n"))
        assertTrue(body.contains("name=\"csr\"; filename=\"author.csr\""))
        assertTrue(body.endsWith("CSR\r\n--B--\r\n"))
    }

    @Test
    fun certificatesAreRequestedForDuidAndSaved() {
        val calls = ArrayList<Pair<String, List<Pair<String, String>>>>()
        val certificates = SamsungCertificates { url, fields, csr, fileName, _ ->
            calls.add(url to fields)
            assertTrue(String(csr).startsWith("-----BEGIN CERTIFICATE REQUEST-----"))
            when (url) {
                SamsungCertificates.AUTHOR_URL -> fixture("author.crt")
                SamsungCertificates.DISTRIBUTOR_PROFILE_URL -> "<profile/>".toByteArray()
                else -> {
                    assertEquals("distributor.csr", fileName)
                    fixture("distributor.crt")
                }
            }
        }
        val profile = certificates.create("DUID1234", SamsungAccount("tok", "uid", "a@b.c"), SamsungProfile(temp.newFolder("q")), Cancel())
        assertTrue(profile.exists)
        assertEquals(3, calls.size)
        assertTrue(("user_id" to "uid") in calls[0].second)
        assertTrue(("developer_type" to "Individual") in calls[2].second)
        assertFalse(calls[0].second.any { it.first == "privilege_level" })
        assertEquals("<profile/>", File(profile.dir, "device-profile.xml").readText())
    }

    @Test
    fun certificateErrorIsReported() {
        val certificates = SamsungCertificates { _, _, _, _, _ -> "{\"error\":\"x\"}".toByteArray() }
        try {
            certificates.create("DUID1234", SamsungAccount("t", "u", ""), SamsungProfile(temp.newFolder("r")), Cancel())
            fail()
        } catch (error: TvInstallException) {
            assertEquals(TvError.CERTIFICATE_FAILED, error.error)
        }
    }

    /** Sahte Samsung TV: sdb karşılaşması, capability, getduid, sync ile dosya, kurulum ve açma komutları. */
    private class FakeTv(
        private val platform: String = "6.0",
        private val installReplies: MutableList<String> = mutableListOf("processing result : Ok [0]"),
    ) {
        val toDevice = PipedOutputStream()
        private val deviceIn = PipedInputStream(toDevice, 1 shl 20)
        private val fromDevice = PipedOutputStream()
        val clientIn = PipedInputStream(fromDevice, 1 shl 20)
        val commands = ArrayList<String>()
        val pushed = ArrayList<Pair<String, ByteArray>>()
        private var nextRemote = 500

        private fun send(command: Int, a0: Int, a1: Int, data: ByteArray = ByteArray(0)) {
            fromDevice.write(Adb.encode(command, a0, a1, data))
            fromDevice.flush()
        }

        private fun reply(local: Int, remote: Int, text: ByteArray) {
            send(Adb.WRTE, remote, local, text)
            while (true) if (Adb.read(deviceIn).command == Adb.OKAY) break
            send(Adb.CLSE, remote, local)
        }

        val thread = thread(isDaemon = true) {
            try {
                val cnxn = Adb.read(deviceIn)
                check(String(cnxn.data) == "host::sdb-net-client")
                send(Adb.CNXN, 0x01000000, 4096, "device::".toByteArray())
                while (true) {
                    val open = Adb.read(deviceIn)
                    if (open.command == Adb.CLSE || open.command == Adb.OKAY) continue
                    if (open.command == Adb.WRTE) { // sync'in QUIT'i
                        send(Adb.OKAY, open.arg1, open.arg0)
                        continue
                    }
                    check(open.command == Adb.OPEN)
                    val service = String(open.data).trimEnd('\u0000')
                    val local = open.arg0
                    val remote = nextRemote++
                    send(Adb.OKAY, remote, local)
                    commands.add(service)
                    when {
                        service == "capability:" -> reply(local, remote, "\u0000\u0000platform_version:$platform\nsdk_toolpath:/tmp/sdk\n".toByteArray())
                        service == "shell:0 getduid" -> reply(local, remote, "DUIDTEST1234\n".toByteArray())
                        service.startsWith("shell:0 vd_appinstall") -> reply(local, remote, (installReplies.removeFirstOrNull() ?: "ok").toByteArray())
                        service.startsWith("shell:") -> reply(local, remote, "ok\n".toByteArray())
                        service == "sync:" -> {
                            val data = ByteArrayOutputStream()
                            while (true) {
                                val packet = Adb.read(deviceIn)
                                if (packet.command != Adb.WRTE) continue
                                data.write(packet.data)
                                send(Adb.OKAY, remote, local)
                                val bytes = data.toByteArray()
                                if (bytes.size >= 16 && String(bytes, bytes.size - 8, 4) == "DONE") break
                            }
                            val bytes = data.toByteArray()
                            val pathLength = ByteBuffer.wrap(bytes, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                            val path = String(bytes, 8, pathLength)
                            val body = ByteArrayOutputStream()
                            var offset = 8 + pathLength
                            while (offset < bytes.size - 8) {
                                val length = ByteBuffer.wrap(bytes, offset + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                                body.write(bytes, offset + 8, length)
                                offset += 8 + length
                            }
                            pushed.add(path to body.toByteArray())
                            send(Adb.WRTE, remote, local, AdbClient.syncHeader("OKAY", 0))
                            // QUIT ve kapanış
                        }
                    }
                }
            } catch (error: Exception) {
                // istemci kapattı
            }
        }

        fun client() = AdbClient(clientIn, toDevice, AdbOptions.SDB)
    }

    private fun installer(tv: FakeTv, profiles: File, certificates: SamsungCertificates = SamsungCertificates { _, _, _, _, _ -> fail(); ByteArray(0) }) =
        SamsungInstaller("tv", profiles, cas, certificates, Cancel(), connector = {
            tv.client().apply { handshake(KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()) }
        })

    @Test
    fun installsWithStoredProfile() {
        val profiles = temp.newFolder("profiles")
        SamsungProfile.of(profiles, "DUIDTEST1234").save(
            key, SamsungCa.certificate(fixture("author.crt")), SamsungCa.certificate(fixture("distributor.crt")), ByteArray(0), "DUIDTEST1234", "",
        )
        val tv = FakeTv()
        val stages = ArrayList<String>()
        val installer = installer(tv, profiles)
        installer.onProgress = { stages.add(it) }
        assertTrue(installer.install(fixture("input.wgt")))
        assertEquals(1, tv.pushed.size)
        val (path, data) = tv.pushed[0]
        assertTrue(path.startsWith("/tmp/sdk/gametranslator-") && path.endsWith(".wgt"))
        assertEquals("author-signature.xml", ZipInputStream(ByteArrayInputStream(data)).nextEntry!!.name)
        assertTrue(tv.commands.contains("shell:0 vd_appinstall GameTransT.GameTranslator $path"))
        assertEquals("shell:0 execute GameTransT.GameTranslator", tv.commands.last())
        assertTrue("signing" in stages && "installing" in stages && "uploading:100" in stages)
        assertFalse("certificate" in stages)
    }

    @Test
    fun reinstallsAfterUninstallWhenCertificateDiffers() {
        val profiles = temp.newFolder("profiles")
        SamsungProfile.of(profiles, "DUIDTEST1234").save(
            key, SamsungCa.certificate(fixture("author.crt")), SamsungCa.certificate(fixture("distributor.crt")), ByteArray(0), "DUIDTEST1234", "",
        )
        val tv = FakeTv(installReplies = mutableListOf("install failed[118, -12], reason: Author certificate not match", "processing result : Ok [0]"))
        assertTrue(installer(tv, profiles).install(fixture("input.wgt")))
        assertTrue(tv.commands.contains("shell:0 vd_appuninstall GameTransT.GameTranslator"))
        assertTrue(tv.commands.contains("shell:0 vd_appuninstall GameTransT"))
        assertEquals(2, tv.pushed.size)
    }

    @Test
    fun reportsInstallFailureAndTooOld() {
        val profiles = temp.newFolder("profiles")
        SamsungProfile.of(profiles, "DUIDTEST1234").save(
            key, SamsungCa.certificate(fixture("author.crt")), SamsungCa.certificate(fixture("distributor.crt")), ByteArray(0), "DUIDTEST1234", "",
        )
        try {
            installer(FakeTv(installReplies = mutableListOf("install failed[1]", "install failed[2]")), profiles).install(fixture("input.wgt"))
            fail()
        } catch (error: TvInstallException) {
            assertEquals(TvError.INSTALL_FAILED, error.error)
            assertTrue(error.detail!!.contains("install failed[2]"))
        }
        try {
            installer(FakeTv(platform = "2.4"), profiles).install(fixture("input.wgt"))
            fail()
        } catch (error: TvInstallException) {
            assertEquals(TvError.TOO_OLD, error.error)
        }
    }

    @Test
    fun asksForSignInThenRequestsCertificate() {
        val profiles = temp.newFolder("profiles")
        try {
            installer(FakeTv(), profiles).install(fixture("input.wgt"))
            fail()
        } catch (error: TvInstallException) {
            assertEquals(TvError.SIGN_IN_NEEDED, error.error)
        }
        val certificates = SamsungCertificates { url, _, csr, _, _ ->
            if (url == SamsungCertificates.DISTRIBUTOR_URL) assertTrue(String(csr).isNotEmpty())
            when (url) {
                SamsungCertificates.AUTHOR_URL -> fixture("author.crt")
                SamsungCertificates.DISTRIBUTOR_URL -> fixture("distributor.crt")
                else -> ByteArray(0)
            }
        }
        val installer = installer(FakeTv(), profiles, certificates)
        installer.account = { SamsungAccount("t", "u", "a@b.c") }
        // Sertifikalar başka anahtarla imzalı (deneme), yine de imzalanıp kurulur; profil kaydedilir.
        assertTrue(installer.install(fixture("input.wgt")))
        assertNotNull(SamsungProfile.of(profiles, "DUIDTEST1234").key())
        assertTrue(installer(FakeTv(), profiles).status().second)
    }

    @Test
    fun unreachableUsesDiagnosis() {
        val installer = SamsungInstaller("tv", temp.newFolder("x"), cas, SamsungCertificates(), Cancel(),
            connector = { throw TvInstallException(TvError.UNREACHABLE, "kapalı") },
            diagnose = { TvInstallException(TvError.WRONG_HOST_IP, "192.168.1.20") })
        try {
            installer.install(ByteArray(0))
            fail()
        } catch (error: TvInstallException) {
            assertEquals(TvError.WRONG_HOST_IP, error.error)
        }
    }
}
