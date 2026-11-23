// SPDX-License-Identifier: AGPL-3.0-only

import CommonCrypto
import Foundation

/// LG Key Server'ın verdiği parolalı anahtarı (eski PEM biçimi: "Proc-Type: 4,ENCRYPTED") çözer ve parolasız PEM döner
/// (Android: LegacyPemKey.kt). Parola, TV ekranındaki 6 karakterli "Passphrase"tir.
enum LegacyPemKey {
    static func isLegacyEncrypted(_ pem: String) -> Bool { pem.contains("Proc-Type: 4,ENCRYPTED") }

    /// Parola yanlışsa `WRONG_PASSPHRASE`.
    static func decrypt(_ pem: String, _ passphrase: String) throws -> String {
        guard let header = pem.firstMatch(of: #/-----BEGIN ([A-Z ]+?) PRIVATE KEY-----/#),
              let info = pem.firstMatch(of: #/DEK-Info:\s*([A-Za-z0-9-]+),([0-9A-Fa-f]+)\s*/#) else {
            throw TvInstallError(.INSTALL_FAILED, "PEM başlığı okunamadı")
        }
        let kind = String(header.1)
        let cipherName = info.1.uppercased()
        let iv = hex(String(info.2))
        let body = pem[info.range.upperBound...]
        guard let end = body.range(of: "-----END") else { throw TvInstallError(.INSTALL_FAILED, "PEM sonu bulunamadı") }
        guard let encrypted = Data(base64Encoded: String(body[..<end.lowerBound].filter { !$0.isWhitespace })) else {
            throw TvInstallError(.INSTALL_FAILED, "PEM gövdesi bozuk")
        }
        let algorithm: CCAlgorithm
        let keyLength: Int
        let blockSize: Int
        switch cipherName {
        case "DES-EDE3-CBC": (algorithm, keyLength, blockSize) = (CCAlgorithm(kCCAlgorithm3DES), 24, kCCBlockSize3DES)
        case "AES-128-CBC": (algorithm, keyLength, blockSize) = (CCAlgorithm(kCCAlgorithmAES), 16, kCCBlockSizeAES128)
        case "AES-192-CBC": (algorithm, keyLength, blockSize) = (CCAlgorithm(kCCAlgorithmAES), 24, kCCBlockSizeAES128)
        case "AES-256-CBC": (algorithm, keyLength, blockSize) = (CCAlgorithm(kCCAlgorithmAES), 32, kCCBlockSizeAES128)
        default: throw TvInstallError(.INSTALL_FAILED, "Desteklenmeyen şifre: \(cipherName)")
        }
        guard iv.count == blockSize else { throw TvInstallError(.INSTALL_FAILED, "PEM IV uzunluğu yanlış") }
        let key = deriveKey(Data(passphrase.utf8), Data(iv.prefix(8)), keyLength)
        // Yanlış parolada dolgu (padding) hemen hemen her zaman bozuk çıkar.
        guard let plain = crypt(algorithm, key, iv, encrypted), looksLikeDer(plain) else {
            // Dolgu tesadüfen tutsa bile çözülen veri bir ASN.1 dizisi (0x30) olmalı ve uzunluğu tutmalı.
            throw TvInstallError(.WRONG_PASSPHRASE)
        }
        return Pem.encode(plain, "\(kind) PRIVATE KEY")
    }

    /// TV parolayı büyük harfle gösterir; küçük harfle yazılmışsa büyük harfli hâli de denenir.
    static func decryptWithCaseFallback(_ pem: String, _ passphrase: String) throws -> String {
        let typed = passphrase.trimmingCharacters(in: .whitespacesAndNewlines)
        do {
            return try decrypt(pem, typed)
        } catch let error as TvInstallError where error.error == .WRONG_PASSPHRASE && typed != typed.uppercased() {
            return try decrypt(pem, typed.uppercased())
        }
    }

    /// OpenSSL'in EVP_BytesToKey'i (MD5, tek tur): eski PEM anahtarlarının parola türetmesi.
    static func deriveKey(_ password: Data, _ salt: Data, _ length: Int) -> Data {
        var key = Data()
        var previous = Data()
        while key.count < length {
            previous = Digest.md5(previous + password + salt)
            key.append(previous)
        }
        return Data(key.prefix(length))
    }

    static func looksLikeDer(_ data: Data) -> Bool {
        let bytes = [UInt8](data)
        guard bytes.count >= 4, bytes[0] == 0x30 else { return false }
        let first = Int(bytes[1])
        let length: Int
        let offset: Int
        if first < 0x80 {
            length = first
            offset = 2
        } else {
            let count = first & 0x7F
            guard count > 0, count <= 3, bytes.count >= 2 + count else { return false }
            var value = 0
            for i in 0..<count { value = (value << 8) | Int(bytes[2 + i]) }
            length = value
            offset = 2 + count
        }
        return offset + length == bytes.count
    }

    /// CBC + PKCS#7 dolgusuyla çözer; dolgu bozuksa nil.
    private static func crypt(_ algorithm: CCAlgorithm, _ key: Data, _ iv: [UInt8], _ input: Data) -> Data? {
        var output = Data(count: input.count + kCCBlockSizeAES128)
        let capacity = output.count
        var moved = 0
        let status = output.withUnsafeMutableBytes { out in
            input.withUnsafeBytes { inp in
                key.withUnsafeBytes { k in
                    CCCrypt(CCOperation(kCCDecrypt), algorithm, CCOptions(kCCOptionPKCS7Padding), k.baseAddress, key.count, iv,
                            inp.baseAddress, input.count, out.baseAddress, capacity, &moved)
                }
            }
        }
        guard status == kCCSuccess else { return nil }
        return Data(output.prefix(moved))
    }

    private static func hex(_ text: String) -> [UInt8] {
        var out: [UInt8] = []
        var index = text.startIndex
        while let next = text.index(index, offsetBy: 2, limitedBy: text.endIndex), index < text.endIndex {
            out.append(UInt8(text[index..<next], radix: 16) ?? 0)
            index = next
        }
        return out
    }

    /// Parolasız PKCS#1 PEM'i OpenSSH özel anahtar biçimine çevirir (Citadel yalnız bu biçimi okur). PKCS#1 dışındaki
    /// biçimler (ör. zaten OpenSSH) olduğu gibi döner.
    static func openSSH(fromPem pem: String) throws -> String {
        if pem.contains("BEGIN OPENSSH PRIVATE KEY") { return pem }
        guard let der = Pem.decode(pem) else { throw TvInstallError(.KEY_REFUSED, "anahtar okunamadı") }
        let pkcs1 = (try? RsaKey.unwrapPkcs8(der)).flatMap { $0 } ?? der
        let (sequence, _) = try DerNode.read(pkcs1)
        let parts = try sequence.children()
        // RSAPrivateKey: version, n, e, d, p, q, dP, dQ, qInv
        guard parts.count >= 9 else { throw TvInstallError(.KEY_REFUSED, "RSA anahtarı değil") }
        let n = parts[1].unsignedInteger, e = parts[2].unsignedInteger, d = parts[3].unsignedInteger
        let p = parts[4].unsignedInteger, q = parts[5].unsignedInteger, qInv = parts[8].unsignedInteger
        func u32(_ value: Int) -> Data { Data([UInt8(value >> 24 & 0xFF), UInt8(value >> 16 & 0xFF), UInt8(value >> 8 & 0xFF), UInt8(value & 0xFF)]) }
        func string(_ data: Data) -> Data { u32(data.count) + data }
        func mpint(_ bytes: [UInt8]) -> Data {
            var value = bytes
            while value.count > 1 && value[0] == 0 { value.removeFirst() }
            if let first = value.first, first & 0x80 != 0 { value.insert(0, at: 0) }
            return string(Data(value))
        }
        let type = string(Data("ssh-rsa".utf8))
        let publicBlob = type + mpint(e) + mpint(n)
        var secret = u32(0x4754_5631) + u32(0x4754_5631) + type + mpint(n) + mpint(e) + mpint(d) + mpint(qInv) + mpint(p) + mpint(q)
        secret += string(Data("GameTranslator".utf8))
        var pad: UInt8 = 1
        while secret.count % 8 != 0 {
            secret.append(pad)
            pad += 1
        }
        var blob = Data("openssh-key-v1".utf8) + Data([0])
        blob += string(Data("none".utf8)) + string(Data("none".utf8)) + string(Data()) + u32(1)
        blob += string(publicBlob) + string(secret)
        return Pem.encode(blob, "OPENSSH PRIVATE KEY", width: 70)
    }
}
