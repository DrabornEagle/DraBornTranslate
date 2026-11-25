// SPDX-License-Identifier: AGPL-3.0-only
// İmza dosyalarının biçimi TizenSdb.Core'un TizenResigner'ından (MIT, Patrick Stel) birebir alındı:
// https://github.com/PatrickSt1991/tizen-sdb (Copyright (c) PatrickSt1991, MIT License)
package com.gametranslator.gametranslator_mobile.tvinstall

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.X509Certificate
import java.util.Calendar
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipInputStream

/**
 * Tizen .wgt paketini imzalar: yazar imzası (author-signature.xml) ve dağıtıcı imzası (signature1.xml), W3C Widget
 * Digital Signature biçiminde XML-DSig (RSA-SHA512). TizenSdb'nin .NET XML kitaplığıyla ürettiği dosyaların bayt bayt
 * aynısı burada elle yazılır: imzalanan SignedInfo exclusive C14N, #prop nesnesi C14N 1.1 ile kanonikleştirilir; ikisi
 * de ad alanı önekleri ve boşluk olmadan üretildiği için kanonik biçim doğrudan yazılabilir.
 */
object SamsungSigner {
    /** Paketteki bir dosya; [name] zip içindeki tam yol. */
    class Entry(val name: String, val data: ByteArray)

    /** İmzalayan: özel anahtar ve sertifika zinciri (ilk eleman yaprak sertifika, sonra Samsung ara sertifikası). */
    class Identity(val key: PrivateKey, val chain: List<X509Certificate>)

    const val AUTHOR = "AuthorSignature"
    const val DISTRIBUTOR = "DistributorSignature"

    private const val DSIG = "http://www.w3.org/2000/09/xmldsig#"
    private const val DSP = "http://www.w3.org/2009/xmldsig-properties"
    private const val RSA_SHA512 = "http://www.w3.org/2001/04/xmldsig-more#rsa-sha512"
    private const val SHA512 = "http://www.w3.org/2001/04/xmlenc#sha512"
    private const val EXC_C14N = "http://www.w3.org/2001/10/xml-exc-c14n#"
    private const val C14N11 = "http://www.w3.org/2006/12/xml-c14n11"

    /** Paketi yeniden imzalar: eski imza dosyaları atılır, yenileri en başa yazılır. */
    fun sign(wgt: ByteArray, author: Identity, distributor: Identity): ByteArray {
        val files = readEntries(wgt)
        val authorFile = Entry("author-signature.xml", signatureXml(AUTHOR, files, author))
        val distributorFile = Entry("signature1.xml", signatureXml(DISTRIBUTOR, listOf(authorFile) + files, distributor))
        return Zip.write(listOf(authorFile, distributorFile) + files)
    }

    /** Paketteki dosyalar sırasıyla; klasör girdileri ve eski imzalar hariç. */
    fun readEntries(wgt: ByteArray): List<Entry> {
        val out = ArrayList<Entry>()
        ZipInputStream(ByteArrayInputStream(wgt)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                val lower = name.lowercase()
                if (name.endsWith("/") || lower.endsWith("author-signature.xml") || lower.endsWith("signature1.xml")) continue
                out.add(Entry(name, zip.readBytes()))
            }
        }
        return out
    }

    /** Bir imza dosyası (TizenResigner.BuildSignatureFileAsync). */
    fun signatureXml(id: String, files: List<Entry>, identity: Identity): ByteArray {
        val prop = propObject(id)
        val propDigest = base64(sha512(prop.toByteArray(Charsets.UTF_8)))

        // SignedInfo iki kez yazılır: imzalanan kanonik biçim (boş öğeler açık-kapalı, kökte ad alanı) ve dosyadaki
        // biçim (.NET XmlWriter: boş öğeler "<X a=\"…\" />", ad alanı Signature öğesinde).
        val canonical = StringBuilder("<SignedInfo xmlns=\"$DSIG\">")
        val written = StringBuilder("<SignedInfo>")
        fun empty(name: String, attribute: String, value: String) {
            canonical.append("<$name $attribute=\"${c14nAttr(value)}\"></$name>")
            written.append("<$name $attribute=\"${xmlAttr(value)}\" />")
        }
        fun raw(text: String) {
            canonical.append(text)
            written.append(text)
        }
        fun reference(uri: String, digest: String, transform: String?) {
            canonical.append("<Reference URI=\"${c14nAttr(uri)}\">")
            written.append("<Reference URI=\"${xmlAttr(uri)}\">")
            if (transform != null) {
                raw("<Transforms>")
                empty("Transform", "Algorithm", transform)
                raw("</Transforms>")
            }
            empty("DigestMethod", "Algorithm", SHA512)
            raw("<DigestValue>$digest</DigestValue></Reference>")
        }
        empty("CanonicalizationMethod", "Algorithm", EXC_C14N)
        empty("SignatureMethod", "Algorithm", RSA_SHA512)
        for (file in files) reference(file.name, base64(sha512(file.data)), null)
        reference("#prop", propDigest, C14N11)
        raw("</SignedInfo>")

        val signature = Signature.getInstance("SHA512withRSA").run {
            initSign(identity.key)
            update(canonical.toString().toByteArray(Charsets.UTF_8))
            sign()
        }
        val xml = StringBuilder()
        xml.append("<Signature Id=\"${xmlAttr(id)}\" xmlns=\"$DSIG\">")
        xml.append(written)
        xml.append("<SignatureValue>").append(base64(signature)).append("</SignatureValue>")
        xml.append("<KeyInfo><X509Data>")
        for (certificate in identity.chain) {
            xml.append("<X509Certificate>\n").append(wrap76(base64(certificate.encoded))).append("\n</X509Certificate>")
        }
        xml.append("</X509Data></KeyInfo>")
        // Nesne dosyada ad alanı bildirimi olmadan durur (Signature'dan alır); kanonik biçimde kökte bildirilir.
        xml.append(prop.replaceFirst("<Object xmlns=\"$DSIG\" Id=", "<Object Id="))
        xml.append("</Signature>")
        return xml.toString().toByteArray(Charsets.UTF_8)
    }

    /** #prop nesnesinin C14N 1.1 biçimi (dosyadaki biçimiyle de aynı, kökteki ad alanı dışında). */
    fun propObject(id: String): String {
        val role = if (id == AUTHOR) "author" else "distributor"
        val target = c14nAttr("#$id")
        fun property(pid: String, child: String, uri: String?): String {
            val attr = if (uri == null) "" else " URI=\"${c14nAttr(uri)}\""
            return "<SignatureProperty Id=\"$pid\" Target=\"$target\"><dsp:$child$attr></dsp:$child></SignatureProperty>"
        }
        return "<Object xmlns=\"$DSIG\" Id=\"prop\"><SignatureProperties xmlns:dsp=\"$DSP\">" +
            property("profile", "Profile", "http://www.w3.org/ns/widgets-digsig#profile") +
            property("role", "Role", "http://www.w3.org/ns/widgets-digsig#role-$role") +
            property("identifier", "Identifier", null) +
            "</SignatureProperties></Object>"
    }

    /** C14N öznitelik kaçışları. */
    private fun c14nAttr(value: String): String = buildString {
        for (c in value) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '"' -> append("&quot;")
            '\t' -> append("&#x9;")
            '\n' -> append("&#xA;")
            '\r' -> append("&#xD;")
            else -> append(c)
        }
    }

    /** .NET XmlWriter'ın öznitelik kaçışları (NewLineHandling.None). */
    private fun xmlAttr(value: String): String = buildString {
        for (c in value) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            else -> append(c)
        }
    }

    private fun sha512(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-512").digest(data)

    private fun base64(data: ByteArray): String = kotlin.io.encoding.Base64.Default.encode(data)

    private fun wrap76(text: String): String = text.chunked(76).joinToString("\n")
}

/**
 * Veri tanımlayıcısız (data descriptor) düz zip yazıcısı: her girdinin boyu ve CRC'si yerel başlıkta yazılır, .NET'in
 * ZipArchive'ının ürettiği gibi. java.util.zip.ZipOutputStream sıkıştırılmış girdilerde veri tanımlayıcısı kullanır;
 * TV'nin paket açıcısını şaşırtmamak için kullanılmadı.
 */
object Zip {
    fun write(entries: List<SamsungSigner.Entry>, time: Calendar = Calendar.getInstance()): ByteArray {
        val out = ByteArrayOutputStream()
        val central = ByteArrayOutputStream()
        val dosTime = ((time.get(Calendar.HOUR_OF_DAY) shl 11) or (time.get(Calendar.MINUTE) shl 5) or (time.get(Calendar.SECOND) / 2))
        val dosDate = (((time.get(Calendar.YEAR) - 1980).coerceAtLeast(0) shl 9) or ((time.get(Calendar.MONTH) + 1) shl 5) or time.get(Calendar.DAY_OF_MONTH))
        for (entry in entries) {
            val name = entry.name.toByteArray(Charsets.UTF_8)
            val flags = if (entry.name.any { it.code > 0x7F }) 0x0800 else 0
            val crc = CRC32().apply { update(entry.data) }.value
            val deflated = deflate(entry.data)
            val stored = deflated.size >= entry.data.size
            val body = if (stored) entry.data else deflated
            val method = if (stored) 0 else 8
            val offset = out.size()
            out.u32(0x04034b50).u16(20).u16(flags).u16(method).u16(dosTime).u16(dosDate)
                .u32(crc).u32(body.size.toLong()).u32(entry.data.size.toLong()).u16(name.size).u16(0)
            out.write(name)
            out.write(body)
            central.u32(0x02014b50).u16(20).u16(20).u16(flags).u16(method).u16(dosTime).u16(dosDate)
                .u32(crc).u32(body.size.toLong()).u32(entry.data.size.toLong()).u16(name.size).u16(0).u16(0)
                .u16(0).u16(0).u32(0).u32(offset.toLong())
            central.write(name)
        }
        val centralOffset = out.size()
        out.write(central.toByteArray())
        out.u32(0x06054b50).u16(0).u16(0).u16(entries.size).u16(entries.size)
            .u32(central.size().toLong()).u32(centralOffset.toLong()).u16(0)
        return out.toByteArray()
    }

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        try {
            deflater.setInput(data)
            deflater.finish()
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16384)
            while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun ByteArrayOutputStream.u16(value: Int): ByteArrayOutputStream {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
        return this
    }

    private fun ByteArrayOutputStream.u32(value: Long): ByteArrayOutputStream {
        for (shift in 0 until 32 step 8) write(((value ushr shift) and 0xFF).toInt())
        return this
    }

    private fun ByteArrayOutputStream.u32(value: Int): ByteArrayOutputStream = u32(value.toLong() and 0xFFFFFFFFL)
}
