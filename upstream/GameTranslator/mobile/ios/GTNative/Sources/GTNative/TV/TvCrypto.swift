// SPDX-License-Identifier: AGPL-3.0-only

import CryptoKit
import Foundation
import Security

/// En küçük DER okuyucusu: anahtar ve sertifika ayrıştırmak için.
struct DerNode {
    let tag: UInt8
    /// Öğenin tamamı (etiket ve uzunluk dahil).
    let whole: Data
    let content: Data

    /// `data`'nın başındaki öğe ve ardından kalan.
    static func read(_ data: Data) throws -> (DerNode, Data) {
        let bytes = [UInt8](data)
        guard bytes.count >= 2 else { throw TvIOError.failed("DER kısa") }
        var index = 1
        let first = Int(bytes[index]); index += 1
        var length = 0
        if first < 0x80 {
            length = first
        } else {
            let count = first & 0x7F
            guard count > 0, count <= 4, bytes.count >= index + count else { throw TvIOError.failed("DER uzunluğu bozuk") }
            for _ in 0..<count {
                length = (length << 8) | Int(bytes[index]); index += 1
            }
        }
        guard bytes.count >= index + length else { throw TvIOError.failed("DER kesik") }
        let start = data.startIndex
        let node = DerNode(tag: bytes[0], whole: Data(data[start..<(start + index + length)]),
                           content: Data(data[(start + index)..<(start + index + length)]))
        return (node, Data(data[(start + index + length)...]))
    }

    /// İçindeki öğeler (SEQUENCE/SET).
    func children() throws -> [DerNode] {
        var rest = content
        var out: [DerNode] = []
        while !rest.isEmpty {
            let (node, next) = try DerNode.read(rest)
            out.append(node)
            rest = next
        }
        return out
    }

    /// INTEGER'ın işaretsiz baytları (baştaki sıfırlar atılır).
    var unsignedInteger: [UInt8] {
        var bytes = [UInt8](content)
        while bytes.count > 1 && bytes[0] == 0 { bytes.removeFirst() }
        return bytes
    }

    /// OID'nin noktalı yazımı.
    var oidText: String {
        let bytes = [UInt8](content)
        guard let first = bytes.first else { return "" }
        var parts = [Int(first) / 40, Int(first) % 40]
        var value = 0
        for byte in bytes.dropFirst() {
            value = (value << 7) | Int(byte & 0x7F)
            if byte & 0x80 == 0 {
                parts.append(value)
                value = 0
            }
        }
        return parts.map(String.init).joined(separator: ".")
    }
}

/// En küçük DER yazıcısı (Android: Der): sertifika isteği (PKCS#10) ve anahtar biçimleri için.
enum Der {
    static func tlv(_ tag: UInt8, _ content: Data) -> Data {
        var out = Data([tag])
        let n = content.count
        if n < 0x80 {
            out.append(UInt8(n))
        } else if n < 0x100 {
            out.append(contentsOf: [0x81, UInt8(n)])
        } else if n < 0x10000 {
            out.append(contentsOf: [0x82, UInt8(n >> 8), UInt8(n & 0xFF)])
        } else {
            out.append(contentsOf: [0x83, UInt8(n >> 16), UInt8((n >> 8) & 0xFF), UInt8(n & 0xFF)])
        }
        out.append(content)
        return out
    }

    static func seq(_ items: Data...) -> Data { tlv(0x30, items.reduce(Data(), +)) }
    static func seq(_ items: [Data]) -> Data { tlv(0x30, items.reduce(Data(), +)) }
    static func set(_ items: Data...) -> Data { tlv(0x31, items.reduce(Data(), +)) }
    static func int(_ value: UInt8) -> Data { tlv(0x02, Data([value])) }
    static func nul() -> Data { Data([0x05, 0x00]) }
    static func utf8(_ text: String) -> Data { tlv(0x0C, Data(text.utf8)) }
    static func printable(_ text: String) -> Data { tlv(0x13, Data(text.utf8)) }
    static func ia5(_ text: String, tag: UInt8 = 0x16) -> Data { tlv(tag, Data(text.utf8)) }
    static func octets(_ data: Data) -> Data { tlv(0x04, data) }
    static func bits(_ data: Data) -> Data { tlv(0x03, Data([0]) + data) }

    /// İşaretsiz büyük sayıyı INTEGER olarak yazar (gerekirse başa sıfır).
    static func unsignedInt(_ bytes: [UInt8]) -> Data {
        var value = bytes
        while value.count > 1 && value[0] == 0 { value.removeFirst() }
        if let first = value.first, first & 0x80 != 0 { value.insert(0, at: 0) }
        return tlv(0x02, Data(value))
    }

    static func oid(_ text: String) -> Data {
        let parts = text.split(separator: ".").compactMap { UInt64($0) }
        var out = Data([UInt8(parts[0] * 40 + parts[1])])
        for part in parts.dropFirst(2) {
            var groups: [UInt8] = []
            var v = part
            repeat {
                groups.append(UInt8(v & 0x7F))
                v >>= 7
            } while v > 0
            for i in stride(from: groups.count - 1, through: 0, by: -1) { out.append(groups[i] | (i > 0 ? 0x80 : 0)) }
        }
        return tlv(0x06, out)
    }
}

/// PEM yardımcıları.
enum Pem {
    /// İlk PEM bloğunun gövdesi; PEM değilse nil.
    static func decode(_ text: String) -> Data? {
        guard let begin = text.range(of: "-----BEGIN "),
              let headerEnd = text.range(of: "-----", range: begin.upperBound..<text.endIndex),
              let end = text.range(of: "-----END", range: headerEnd.upperBound..<text.endIndex) else { return nil }
        let body = text[headerEnd.upperBound..<end.lowerBound].filter { !$0.isWhitespace }
        return Data(base64Encoded: String(body))
    }

    static func encode(_ der: Data, _ label: String, width: Int = 64) -> String {
        let base64 = der.base64EncodedString()
        var out = "-----BEGIN \(label)-----\n"
        var index = base64.startIndex
        while index < base64.endIndex {
            let next = base64.index(index, offsetBy: width, limitedBy: base64.endIndex) ?? base64.endIndex
            out += base64[index..<next] + "\n"
            index = next
        }
        return out + "-----END \(label)-----\n"
    }
}

/// Özet işlevleri.
enum Digest {
    static func md5(_ data: Data) -> Data { Data(Insecure.MD5.hash(data: data)) }
    static func sha1(_ data: Data) -> Data { Data(Insecure.SHA1.hash(data: data)) }
    static func sha512(_ data: Data) -> Data { Data(SHA512.hash(data: data)) }
}

/// RSA anahtar çifti (Security.framework). Saklama biçimi PKCS#1 RSAPrivateKey DER'dir; Android'in PKCS#8 dosyaları
/// da okunur.
final class RsaKey: @unchecked Sendable {
    let privateKey: SecKey
    let publicKey: SecKey
    /// Açık anahtarın PKCS#1 RSAPublicKey DER'i.
    let publicPkcs1: Data
    let modulus: [UInt8]
    let exponent: [UInt8]

    init(privateKey: SecKey) throws {
        guard let publicKey = SecKeyCopyPublicKey(privateKey),
              let external = SecKeyCopyExternalRepresentation(publicKey, nil) as Data? else {
            throw TvIOError.failed("açık anahtar alınamadı")
        }
        self.privateKey = privateKey
        self.publicKey = publicKey
        publicPkcs1 = external
        let (sequence, _) = try DerNode.read(external)
        let parts = try sequence.children()
        guard parts.count >= 2 else { throw TvIOError.failed("RSA açık anahtarı bozuk") }
        modulus = parts[0].unsignedInteger
        exponent = parts[1].unsignedInteger
    }

    /// Yeni 2048 bitlik anahtar.
    static func generate(bits: Int = 2048) throws -> RsaKey {
        let attributes: [String: Any] = [kSecAttrKeyType as String: kSecAttrKeyTypeRSA, kSecAttrKeySizeInBits as String: bits]
        var error: Unmanaged<CFError>?
        guard let key = SecKeyCreateRandomKey(attributes as CFDictionary, &error) else {
            throw TvIOError.failed("RSA anahtarı üretilemedi: \(error?.takeRetainedValue().localizedDescription ?? "?")")
        }
        return try RsaKey(privateKey: key)
    }

    /// PKCS#1 RSAPrivateKey ya da PKCS#8 PrivateKeyInfo DER'inden.
    static func load(der: Data) throws -> RsaKey {
        let pkcs1 = try unwrapPkcs8(der) ?? der
        let attributes: [String: Any] = [kSecAttrKeyType as String: kSecAttrKeyTypeRSA, kSecAttrKeyClass as String: kSecAttrKeyClassPrivate]
        var error: Unmanaged<CFError>?
        guard let key = SecKeyCreateWithData(pkcs1 as CFData, attributes as CFDictionary, &error) else {
            throw TvIOError.failed("RSA anahtarı okunamadı: \(error?.takeRetainedValue().localizedDescription ?? "?")")
        }
        return try RsaKey(privateKey: key)
    }

    /// PKCS#8 sarmalının içindeki PKCS#1 anahtar; PKCS#8 değilse nil.
    static func unwrapPkcs8(_ der: Data) throws -> Data? {
        let (outer, _) = try DerNode.read(der)
        let parts = try outer.children()
        guard parts.count >= 3, parts[0].tag == 0x02, parts[1].tag == 0x30, parts[2].tag == 0x04 else { return nil }
        return parts[2].content
    }

    /// Özel anahtarın PKCS#1 DER'i (saklamak için).
    func privatePkcs1() throws -> Data {
        var error: Unmanaged<CFError>?
        guard let data = SecKeyCopyExternalRepresentation(privateKey, &error) as Data? else {
            throw TvIOError.failed("anahtar dışa aktarılamadı: \(error?.takeRetainedValue().localizedDescription ?? "?")")
        }
        return data
    }

    /// X.509 SubjectPublicKeyInfo (Java'daki `publicKey.encoded`).
    var subjectPublicKeyInfo: Data {
        Der.seq(Der.seq(Der.oid("1.2.840.113549.1.1.1"), Der.nul()), Der.bits(publicPkcs1))
    }

    var bitLength: Int {
        guard let first = modulus.first else { return 0 }
        return (modulus.count - 1) * 8 + (8 - first.leadingZeroBitCount)
    }

    /// PKCS#1 v1.5 imzası.
    func sign(_ data: Data, _ algorithm: SecKeyAlgorithm) throws -> Data {
        var error: Unmanaged<CFError>?
        guard let signature = SecKeyCreateSignature(privateKey, algorithm, data as CFData, &error) as Data? else {
            throw TvIOError.failed("imzalanamadı: \(error?.takeRetainedValue().localizedDescription ?? "?")")
        }
        return signature
    }
}

/// X.509 sertifikası: Samsung zinciri ve geçerlilik süresi için gereken alanlar.
struct X509Cert {
    let der: Data
    let tbs: Data
    let issuer: Data
    let subject: Data
    let notBefore: Date
    let notAfter: Date
    let signatureOid: String
    let signature: Data

    /// PEM ya da DER sertifika.
    init(_ data: Data) throws {
        let text = String(decoding: data.prefix(64), as: UTF8.self)
        let der = text.contains("-----BEGIN") ? (Pem.decode(String(decoding: data, as: UTF8.self)) ?? Data()) : data
        let (certificate, _) = try DerNode.read(der)
        let parts = try certificate.children()
        guard certificate.tag == 0x30, parts.count == 3 else { throw TvIOError.failed("sertifika değil") }
        var fields = try parts[0].children()
        if fields.first?.tag == 0xA0 { fields.removeFirst() }
        // serialNumber, signature, issuer, validity, subject, subjectPublicKeyInfo, ...
        guard fields.count >= 6 else { throw TvIOError.failed("sertifika alanları eksik") }
        let validity = try fields[3].children()
        guard validity.count == 2, let start = Self.time(validity[0]), let end = Self.time(validity[1]) else {
            throw TvIOError.failed("sertifika tarihi okunamadı")
        }
        let algorithm = try parts[1].children()
        guard let oid = algorithm.first, oid.tag == 0x06, parts[2].tag == 0x03, !parts[2].content.isEmpty else {
            throw TvIOError.failed("sertifika imzası okunamadı")
        }
        self.der = certificate.whole
        tbs = parts[0].whole
        issuer = fields[2].whole
        subject = fields[4].whole
        notBefore = start
        notAfter = end
        signatureOid = oid.oidText
        signature = Data(parts[2].content.dropFirst())
    }

    var isSelfSigned: Bool { issuer == subject }

    /// Konu adının okunur özeti (Security.framework).
    var subjectSummary: String {
        SecCertificateCreateWithData(nil, der as CFData).flatMap { SecCertificateCopySubjectSummary($0) as String? } ?? ""
    }

    var publicKey: SecKey? { SecCertificateCreateWithData(nil, der as CFData).flatMap { SecCertificateCopyKey($0) } }

    /// Bu sertifika `issuerKey` ile mi imzalanmış?
    func isSigned(by issuerKey: SecKey) -> Bool {
        let algorithm: SecKeyAlgorithm
        switch signatureOid {
        case "1.2.840.113549.1.1.5": algorithm = .rsaSignatureMessagePKCS1v15SHA1
        case "1.2.840.113549.1.1.11": algorithm = .rsaSignatureMessagePKCS1v15SHA256
        case "1.2.840.113549.1.1.12": algorithm = .rsaSignatureMessagePKCS1v15SHA384
        case "1.2.840.113549.1.1.13": algorithm = .rsaSignatureMessagePKCS1v15SHA512
        default: return false
        }
        return SecKeyVerifySignature(issuerKey, algorithm, tbs as CFData, signature as CFData, nil)
    }

    /// UTCTime (YYMMDDHHMMSSZ) ya da GeneralizedTime (YYYYMMDDHHMMSSZ).
    private static func time(_ node: DerNode) -> Date? {
        let text = String(decoding: node.content, as: UTF8.self)
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(identifier: "UTC")
        switch node.tag {
        case 0x17:
            formatter.dateFormat = "yyMMddHHmmss'Z'"
            // X.509: YY 50 ve üstü 19YY, altı 20YY.
            formatter.twoDigitStartDate = Calendar(identifier: .gregorian).date(from: DateComponents(timeZone: TimeZone(identifier: "UTC"), year: 1950))
            return formatter.date(from: text)
        case 0x18:
            formatter.dateFormat = "yyyyMMddHHmmss'Z'"
            return formatter.date(from: text)
        default:
            return nil
        }
    }
}
