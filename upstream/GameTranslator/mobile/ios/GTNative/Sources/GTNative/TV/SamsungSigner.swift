// SPDX-License-Identifier: AGPL-3.0-only
// İmza dosyalarının biçimi TizenSdb.Core'un TizenResigner'ından (MIT, Patrick Stel) birebir alındı:
// https://github.com/PatrickSt1991/tizen-sdb (Copyright (c) PatrickSt1991, MIT License)

import Compression
import Foundation

/// Tizen .wgt paketini imzalar (Android: SamsungSigner.kt): yazar imzası (author-signature.xml) ve dağıtıcı imzası
/// (signature1.xml), W3C Widget Digital Signature biçiminde XML-DSig (RSA-SHA512). TizenSdb'nin .NET XML kitaplığıyla
/// ürettiği dosyaların bayt bayt aynısı elle yazılır: imzalanan SignedInfo exclusive C14N, #prop nesnesi C14N 1.1 ile
/// kanonikleştirilir; ikisi de ad alanı önekleri ve boşluk olmadan üretildiği için kanonik biçim doğrudan yazılabilir.
enum SamsungSigner {
    /// Paketteki bir dosya; `name` zip içindeki tam yol.
    struct Entry {
        let name: String
        let data: Data
    }

    /// İmzalayan: özel anahtar ve sertifika zinciri (ilk eleman yaprak sertifika, sonra Samsung ara sertifikası).
    struct Identity {
        let key: RsaKey
        let chain: [X509Cert]
    }

    static let author = "AuthorSignature"
    static let distributor = "DistributorSignature"

    private static let dsig = "http://www.w3.org/2000/09/xmldsig#"
    private static let dsp = "http://www.w3.org/2009/xmldsig-properties"
    private static let rsaSha512 = "http://www.w3.org/2001/04/xmldsig-more#rsa-sha512"
    private static let sha512 = "http://www.w3.org/2001/04/xmlenc#sha512"
    private static let excC14n = "http://www.w3.org/2001/10/xml-exc-c14n#"
    private static let c14n11 = "http://www.w3.org/2006/12/xml-c14n11"

    /// Paketi yeniden imzalar: eski imza dosyaları atılır, yenileri en başa yazılır.
    static func sign(_ wgt: Data, author authorIdentity: Identity, distributor distributorIdentity: Identity) throws -> Data {
        let files = try readEntries(wgt)
        let authorFile = Entry(name: "author-signature.xml", data: try signatureXml(author, files, authorIdentity))
        let distributorFile = Entry(name: "signature1.xml", data: try signatureXml(distributor, [authorFile] + files, distributorIdentity))
        return Zip.write([authorFile, distributorFile] + files)
    }

    /// Paketteki dosyalar sırasıyla; klasör girdileri ve eski imzalar hariç.
    static func readEntries(_ wgt: Data) throws -> [Entry] {
        try Zip.read(wgt).filter { entry in
            let lower = entry.name.lowercased()
            return !entry.name.hasSuffix("/") && !lower.hasSuffix("author-signature.xml") && !lower.hasSuffix("signature1.xml")
        }
    }

    /// Bir imza dosyası (TizenResigner.BuildSignatureFileAsync).
    static func signatureXml(_ id: String, _ files: [Entry], _ identity: Identity) throws -> Data {
        let prop = propObject(id)
        let propDigest = Digest.sha512(Data(prop.utf8)).base64EncodedString()

        // SignedInfo iki kez yazılır: imzalanan kanonik biçim (boş öğeler açık-kapalı, kökte ad alanı) ve dosyadaki
        // biçim (.NET XmlWriter: boş öğeler "<X a=\"…\" />", ad alanı Signature öğesinde).
        var canonical = "<SignedInfo xmlns=\"\(dsig)\">"
        var written = "<SignedInfo>"
        func empty(_ name: String, _ attribute: String, _ value: String) {
            canonical += "<\(name) \(attribute)=\"\(c14nAttr(value))\"></\(name)>"
            written += "<\(name) \(attribute)=\"\(xmlAttr(value))\" />"
        }
        func raw(_ text: String) {
            canonical += text
            written += text
        }
        func reference(_ uri: String, _ digest: String, _ transform: String?) {
            canonical += "<Reference URI=\"\(c14nAttr(uri))\">"
            written += "<Reference URI=\"\(xmlAttr(uri))\">"
            if let transform {
                raw("<Transforms>")
                empty("Transform", "Algorithm", transform)
                raw("</Transforms>")
            }
            empty("DigestMethod", "Algorithm", sha512)
            raw("<DigestValue>\(digest)</DigestValue></Reference>")
        }
        empty("CanonicalizationMethod", "Algorithm", excC14n)
        empty("SignatureMethod", "Algorithm", rsaSha512)
        for file in files { reference(file.name, Digest.sha512(file.data).base64EncodedString(), nil) }
        reference("#prop", propDigest, c14n11)
        raw("</SignedInfo>")

        let signature = try identity.key.sign(Data(canonical.utf8), .rsaSignatureMessagePKCS1v15SHA512)
        var xml = "<Signature Id=\"\(xmlAttr(id))\" xmlns=\"\(dsig)\">"
        xml += written
        xml += "<SignatureValue>" + signature.base64EncodedString() + "</SignatureValue>"
        xml += "<KeyInfo><X509Data>"
        for certificate in identity.chain {
            xml += "<X509Certificate>\n" + wrap76(certificate.der.base64EncodedString()) + "\n</X509Certificate>"
        }
        xml += "</X509Data></KeyInfo>"
        // Nesne dosyada ad alanı bildirimi olmadan durur (Signature'dan alır); kanonik biçimde kökte bildirilir.
        xml += prop.replacingOccurrences(of: "<Object xmlns=\"\(dsig)\" Id=", with: "<Object Id=", options: [.anchored])
        xml += "</Signature>"
        return Data(xml.utf8)
    }

    /// #prop nesnesinin C14N 1.1 biçimi (dosyadaki biçimiyle de aynı, kökteki ad alanı dışında).
    static func propObject(_ id: String) -> String {
        let role = id == author ? "author" : "distributor"
        let target = c14nAttr("#\(id)")
        func property(_ pid: String, _ child: String, _ uri: String?) -> String {
            let attr = uri.map { " URI=\"\(c14nAttr($0))\"" } ?? ""
            return "<SignatureProperty Id=\"\(pid)\" Target=\"\(target)\"><dsp:\(child)\(attr)></dsp:\(child)></SignatureProperty>"
        }
        return "<Object xmlns=\"\(dsig)\" Id=\"prop\"><SignatureProperties xmlns:dsp=\"\(dsp)\">" +
            property("profile", "Profile", "http://www.w3.org/ns/widgets-digsig#profile") +
            property("role", "Role", "http://www.w3.org/ns/widgets-digsig#role-\(role)") +
            property("identifier", "Identifier", nil) +
            "</SignatureProperties></Object>"
    }

    /// C14N öznitelik kaçışları.
    private static func c14nAttr(_ value: String) -> String {
        var out = ""
        for c in value.unicodeScalars {
            switch c {
            case "&": out += "&amp;"
            case "<": out += "&lt;"
            case "\"": out += "&quot;"
            case "\t": out += "&#x9;"
            case "\n": out += "&#xA;"
            case "\r": out += "&#xD;"
            default: out.unicodeScalars.append(c)
            }
        }
        return out
    }

    /// .NET XmlWriter'ın öznitelik kaçışları (NewLineHandling.None).
    private static func xmlAttr(_ value: String) -> String {
        var out = ""
        for c in value.unicodeScalars {
            switch c {
            case "&": out += "&amp;"
            case "<": out += "&lt;"
            case ">": out += "&gt;"
            case "\"": out += "&quot;"
            default: out.unicodeScalars.append(c)
            }
        }
        return out
    }

    private static func wrap76(_ text: String) -> String {
        var lines: [Substring] = []
        var index = text.startIndex
        while index < text.endIndex {
            let next = text.index(index, offsetBy: 76, limitedBy: text.endIndex) ?? text.endIndex
            lines.append(text[index..<next])
            index = next
        }
        return lines.joined(separator: "\n")
    }
}

/// Veri tanımlayıcısız (data descriptor) düz zip yazıcısı ve okuyucusu (Android: Zip). Her girdinin boyu ve CRC'si yerel
/// başlıkta yazılır, .NET'in ZipArchive'ının ürettiği gibi; TV'nin paket açıcısını şaşırtmamak için.
enum Zip {
    static func write(_ entries: [SamsungSigner.Entry], time: Date = Date()) -> Data {
        var out = Data()
        var central = Data()
        let parts = Calendar.current.dateComponents([.year, .month, .day, .hour, .minute, .second], from: time)
        let dosTime = ((parts.hour ?? 0) << 11) | ((parts.minute ?? 0) << 5) | ((parts.second ?? 0) / 2)
        let dosDate = (max(0, (parts.year ?? 1980) - 1980) << 9) | ((parts.month ?? 1) << 5) | (parts.day ?? 1)
        for entry in entries {
            let name = Data(entry.name.utf8)
            let flags = entry.name.unicodeScalars.contains { $0.value > 0x7F } ? 0x0800 : 0
            let crc = crc32(entry.data)
            let deflated = deflate(entry.data)
            let stored = deflated == nil || deflated!.count >= entry.data.count
            let body = stored ? entry.data : deflated!
            let method = stored ? 0 : 8
            let offset = out.count
            out.u32(0x0403_4b50); out.u16(20); out.u16(flags); out.u16(method); out.u16(dosTime); out.u16(dosDate)
            out.u32(crc); out.u32(UInt32(body.count)); out.u32(UInt32(entry.data.count)); out.u16(name.count); out.u16(0)
            out.append(name)
            out.append(body)
            central.u32(0x0201_4b50); central.u16(20); central.u16(20); central.u16(flags); central.u16(method)
            central.u16(dosTime); central.u16(dosDate); central.u32(crc); central.u32(UInt32(body.count))
            central.u32(UInt32(entry.data.count)); central.u16(name.count); central.u16(0); central.u16(0)
            central.u16(0); central.u16(0); central.u32(0); central.u32(UInt32(offset))
            central.append(name)
        }
        let centralOffset = out.count
        out.append(central)
        out.u32(0x0605_4b50); out.u16(0); out.u16(0); out.u16(entries.count); out.u16(entries.count)
        out.u32(UInt32(central.count)); out.u32(UInt32(centralOffset)); out.u16(0)
        return out
    }

    /// Zip'teki girdiler dosyadaki sırasıyla (merkez dizinden; sıkıştırılmamış ya da deflate).
    static func read(_ zip: Data) throws -> [SamsungSigner.Entry] {
        let bytes = [UInt8](zip)
        func u16(_ i: Int) -> Int { Int(bytes[i]) | (Int(bytes[i + 1]) << 8) }
        func u32(_ i: Int) -> Int { u16(i) | (u16(i + 2) << 16) }
        guard bytes.count >= 22 else { throw TvInstallError(.INSTALL_FAILED, "paket zip değil") }
        var end = -1
        for i in stride(from: bytes.count - 22, through: max(0, bytes.count - 22 - 65535), by: -1) where u32(i) == 0x0605_4b50 {
            end = i
            break
        }
        guard end >= 0 else { throw TvInstallError(.INSTALL_FAILED, "paket zip değil") }
        let count = u16(end + 10)
        var position = u32(end + 16)
        var found: [(offset: Int, entry: SamsungSigner.Entry)] = []
        for _ in 0..<count {
            guard position + 46 <= bytes.count, u32(position) == 0x0201_4b50 else { throw TvInstallError(.INSTALL_FAILED, "zip dizini bozuk") }
            let method = u16(position + 10)
            let compressed = u32(position + 20)
            let size = u32(position + 24)
            let nameLength = u16(position + 28)
            let extraLength = u16(position + 30)
            let commentLength = u16(position + 32)
            let local = u32(position + 42)
            guard position + 46 + nameLength <= bytes.count else { throw TvInstallError(.INSTALL_FAILED, "zip dizini bozuk") }
            let name = String(decoding: bytes[(position + 46)..<(position + 46 + nameLength)], as: UTF8.self)
            position += 46 + nameLength + extraLength + commentLength
            guard local + 30 <= bytes.count, u32(local) == 0x0403_4b50 else { throw TvInstallError(.INSTALL_FAILED, "zip girdisi bozuk") }
            let start = local + 30 + u16(local + 26) + u16(local + 28)
            guard start + compressed <= bytes.count else { throw TvInstallError(.INSTALL_FAILED, "zip girdisi kesik") }
            let raw = Data(bytes[start..<(start + compressed)])
            let data: Data
            switch method {
            case 0: data = raw
            case 8: data = try inflate(raw, size)
            default: throw TvInstallError(.INSTALL_FAILED, "desteklenmeyen sıkıştırma: \(method)")
            }
            found.append((local, SamsungSigner.Entry(name: name, data: data)))
        }
        return found.sorted { $0.offset < $1.offset }.map(\.entry)
    }

    /// Ham deflate (RFC 1951); sığmazsa nil.
    static func deflate(_ data: Data) -> Data? {
        guard !data.isEmpty else { return nil }
        let capacity = data.count + 1024
        var out = [UInt8](repeating: 0, count: capacity)
        let written = data.withUnsafeBytes { input in
            compression_encode_buffer(&out, capacity, input.bindMemory(to: UInt8.self).baseAddress!, data.count, nil, COMPRESSION_ZLIB)
        }
        return written == 0 ? nil : Data(out[0..<written])
    }

    static func inflate(_ data: Data, _ size: Int) throws -> Data {
        if size == 0 { return Data() }
        var out = [UInt8](repeating: 0, count: size)
        let written = data.withUnsafeBytes { input -> Int in
            guard let base = input.bindMemory(to: UInt8.self).baseAddress else { return 0 }
            return compression_decode_buffer(&out, size, base, data.count, nil, COMPRESSION_ZLIB)
        }
        guard written == size else { throw TvInstallError(.INSTALL_FAILED, "zip girdisi açılamadı") }
        return Data(out)
    }

    private static let crcTable: [UInt32] = (0..<256).map { n -> UInt32 in
        var c = UInt32(n)
        for _ in 0..<8 { c = c & 1 != 0 ? 0xEDB8_8320 ^ (c >> 1) : c >> 1 }
        return c
    }

    static func crc32(_ data: Data) -> UInt32 {
        var crc: UInt32 = 0xFFFF_FFFF
        for byte in data { crc = crcTable[Int((crc ^ UInt32(byte)) & 0xFF)] ^ (crc >> 8) }
        return crc ^ 0xFFFF_FFFF
    }
}

private extension Data {
    mutating func u16(_ value: Int) {
        append(UInt8(value & 0xFF))
        append(UInt8((value >> 8) & 0xFF))
    }

    mutating func u32(_ value: UInt32) {
        for shift in stride(from: 0, to: 32, by: 8) { append(UInt8((value >> UInt32(shift)) & 0xFF)) }
    }
}
