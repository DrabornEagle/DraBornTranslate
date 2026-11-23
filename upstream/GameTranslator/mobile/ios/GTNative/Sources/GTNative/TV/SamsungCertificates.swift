// SPDX-License-Identifier: AGPL-3.0-only
// Samsung'un sertifika uç noktaları ve istek biçimleri Apps2Samsung'dan (MIT, Patrick Stel) alındı:
// https://github.com/Jellyfin2Samsung/Samsung-Jellyfin-Installer — Android'deki SamsungCertificates.kt'nin karşılığı.

import Foundation

/// Samsung hesabıyla girişten dönen bilgiler; sertifika istemek için gerekir.
struct SamsungAccount: Equatable {
    let accessToken: String
    let userId: String
    let email: String
}

/// Sertifika istekleri (PKCS#10, PEM). .NET'in CertificateRequest'inin ürettiğiyle bayt bayt aynıdır: yazar isteğinde CN
/// PrintableString; dağıtıcı isteğinde önce e-posta (IA5String) sonra CN (UTF8String) ve TV'nin DUID'ini taşıyan
/// subjectAltName (URI adları büyük harfle, elle yazılır).
enum SamsungCsr {
    private static let cn = "2.5.4.3"
    private static let email = "1.2.840.113549.1.9.1"
    private static let extensionRequest = "1.2.840.113549.1.9.14"
    private static let subjectAltName = "2.5.29.17"
    private static let sha256Rsa = "1.2.840.113549.1.1.11"

    static func author(_ key: RsaKey) throws -> Data {
        let subject = Der.seq(Der.set(Der.seq(Der.oid(cn), Der.printable("GameTranslator"))))
        return try pem(key, subject, Der.tlv(0xA0, Data()))
    }

    static func distributor(_ key: RsaKey, duid: String, email address: String) throws -> Data {
        var names: [Data] = []
        if !address.trimmingCharacters(in: .whitespaces).isEmpty { names.append(Der.set(Der.seq(Der.oid(email), Der.ia5(address)))) }
        names.append(Der.set(Der.seq(Der.oid(cn), Der.utf8("TizenSDK"))))
        let subject = Der.seq(names)
        let altNames = Der.seq(Der.ia5("URN:tizen:packageid=", tag: 0x86), Der.ia5("URN:tizen:deviceid=\(duid)", tag: 0x86))
        let ext = Der.seq(Der.oid(subjectAltName), Der.octets(altNames))
        let attributes = Der.tlv(0xA0, Der.seq(Der.oid(extensionRequest), Der.set(Der.seq(ext))))
        return try pem(key, subject, attributes)
    }

    private static func pem(_ key: RsaKey, _ subject: Data, _ attributes: Data) throws -> Data {
        let info = Der.seq(Der.int(0), subject, key.subjectPublicKeyInfo, attributes)
        let signature = try key.sign(info, .rsaSignatureMessagePKCS1v15SHA256)
        let der = Der.seq(info, Der.seq(Der.oid(sha256Rsa), Der.nul()), Der.bits(signature))
        let body = Pem.encode(der, "CERTIFICATE REQUEST").trimmingCharacters(in: .newlines)
        return Data(body.utf8)
    }
}

/// Samsung'un geliştirici sertifikalarını veren ara sertifikalar (Tizen Studio'dan, değiştirilmeden). Uygulamayla gelen
/// klasör (Runner: samsung-ca/, Android'deki assets/samsung-ca) varsa oradan, yoksa gömülü kopyadan (SamsungCaData).
enum SamsungCa {
    static let names = ["vd_tizen_dev_author_ca", "author_ca", "vd_tizen_dev_public2", "public2"]

    static func load(from directory: URL? = nil) -> [X509Cert] {
        if let directory {
            let loaded = names.compactMap { name in
                (try? Data(contentsOf: directory.appendingPathComponent("\(name).pem"))).flatMap { try? X509Cert($0) }
            }
            if loaded.count == names.count { return loaded }
        }
        return SamsungCaData.pems.compactMap { try? X509Cert(Data($0.pem.utf8)) }
    }

    /// Yaprağı veren ara sertifika: aynı adlı iki sertifikadan yaprağın imzasını doğrulayan, doğrulayan yoksa adı eşleşen
    /// ilki.
    static func intermediate(_ leaf: X509Cert, _ cas: [X509Cert]) -> X509Cert? {
        let matches = cas.filter { $0.subject == leaf.issuer && !$0.isSelfSigned }
        return matches.first { ca in ca.publicKey.map { leaf.isSigned(by: $0) } ?? false } ?? matches.first
    }
}

/// Bir TV için imza bilgileri: anahtar (Anahtar Zinciri), yazar ve dağıtıcı sertifikası (UserDefaults). Kayıt TV'nin
/// DUID'ine göredir; dağıtıcı sertifikası o TV'ye, yazar sertifikası Samsung hesabına bağlıdır. Sonraki kurulum ve
/// güncellemeler giriş istemez.
final class SamsungProfile {
    let folder: String
    private let store: TvStore

    init(store: TvStore, duid: String) {
        self.store = store
        folder = Self.folderName(duid)
    }

    /// Profil adı: DUID'deki dosya adına uymayan karakterler "_" olur (Android'deki klasör adıyla aynı).
    static func folderName(_ duid: String) -> String {
        String(duid.unicodeScalars.map { scalar -> Character in
            let c = Character(scalar)
            return (("A"..."Z").contains(c) || ("a"..."z").contains(c) || ("0"..."9").contains(c) || c == "_" || c == "-") ? c : "_"
        })
    }

    private var record: [String: Any] { store.samsungRecord(folder) ?? [:] }

    var authorData: Data? { record["author"] as? Data }
    var distributorData: Data? { record["distributor"] as? Data }
    var deviceProfile: Data? { record["deviceProfile"] as? Data }
    var email: String { (record["email"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines) }

    var exists: Bool { authorData != nil && distributorData != nil && store.samsungKey(folder) != nil }

    /// Sertifikalar hâlâ geçerli mi (bir gün pay bırakılarak)?
    func isValid(_ now: Date = Date()) -> Bool {
        guard exists, let author = authorData, let distributor = distributorData else { return false }
        let margin = now.addingTimeInterval(24 * 3600)
        for data in [author, distributor] {
            guard let certificate = try? X509Cert(data), certificate.notBefore <= now, certificate.notAfter >= margin else { return false }
        }
        return key() != nil
    }

    func key() -> RsaKey? { store.samsungKey(folder).flatMap { try? RsaKey.load(der: $0) } }

    /// İmzalayanlar: yaprak sertifika ve onu veren Samsung ara sertifikası (TizenResigner.GetKeyInfoChain).
    func identities(_ cas: [X509Cert]) throws -> (SamsungSigner.Identity, SamsungSigner.Identity) {
        guard let key = key(), let author = authorData, let distributor = distributorData else {
            throw TvInstallError(.CERTIFICATE_FAILED, "imza anahtarı okunamadı")
        }
        func identity(_ data: Data) throws -> SamsungSigner.Identity {
            let leaf: X509Cert
            do { leaf = try X509Cert(data) } catch { throw TvInstallError(.CERTIFICATE_FAILED, "sertifika okunamadı") }
            return SamsungSigner.Identity(key: key, chain: [leaf] + (SamsungCa.intermediate(leaf, cas).map { [$0] } ?? []))
        }
        return (try identity(author), try identity(distributor))
    }

    func save(key: RsaKey, author: X509Cert, distributor: X509Cert, deviceProfile: Data, duid: String, email: String) throws {
        store.setSamsungKey(folder, try key.privatePkcs1())
        store.setSamsungRecord(folder, ["author": author.der, "distributor": distributor.der, "deviceProfile": deviceProfile,
                                        "duid": duid, "email": email])
    }
}

/// Samsung'dan TV'ye özel imza sertifikası alır (Tizen Studio'nun Certificate Manager'ının yaptığı). `post` testlerde
/// değiştirilir: (adres, alanlar, CSR, dosya adı) → yanıt gövdesi.
final class SamsungCertificates {
    typealias Post = (_ url: String, _ fields: [(String, String)], _ csr: Data, _ fileName: String, _ cancel: Cancel) throws -> Data

    static let authorUrl = "https://svdca.samsungqbe.com/apis/v3/authors"
    static let distributorProfileUrl = "https://svdca.samsungqbe.com/apis/v1/distributors"
    static let distributorUrl = "https://svdca.samsungqbe.com/apis/v3/distributors"

    private let post: Post

    init(post: @escaping Post = SamsungCertificates.multipartPost) { self.post = post }

    func create(duid: String, account: SamsungAccount, profile: SamsungProfile, cancel: Cancel) throws -> SamsungProfile {
        let key = try RsaKey.generate()
        let authorCsr = try SamsungCsr.author(key)
        let distributorCsr = try SamsungCsr.distributor(key, duid: duid, email: account.email)
        let base = [("access_token", account.accessToken), ("user_id", account.userId), ("platform", "VD")]
        let distributorFields = base + [("privilege_level", "public"), ("developer_type", "Individual")]

        let author = try post(Self.authorUrl, base, authorCsr, "author.csr", cancel)
        // Eski TV'ler (Tizen 4 ve öncesi) için cihaz profili; yenilerde kullanılmaz ama saklanır.
        let deviceProfile = try post(Self.distributorProfileUrl, distributorFields, distributorCsr, "distributor.csr", cancel)
        let distributor = try post(Self.distributorUrl, distributorFields, distributorCsr, "distributor.csr", cancel)
        let authorCertificate = try parse(author, "yazar")
        let distributorCertificate = try parse(distributor, "dağıtıcı")
        try profile.save(key: key, author: authorCertificate, distributor: distributorCertificate, deviceProfile: deviceProfile,
                         duid: duid, email: account.email)
        return profile
    }

    private func parse(_ data: Data, _ what: String) throws -> X509Cert {
        do {
            return try X509Cert(data)
        } catch {
            throw TvInstallError(.CERTIFICATE_FAILED, "\(what) sertifikası okunamadı: \(String(decoding: data.prefix(200), as: UTF8.self))")
        }
    }

    /// multipart/form-data gövdesi (.NET MultipartFormDataContent'in gönderdiği alanlar).
    static func multipartBody(boundary: String, fields: [(String, String)], file: Data, fileName: String) -> Data {
        var out = Data()
        func line(_ text: String) { out.append(Data((text + "\r\n").utf8)) }
        for (name, value) in fields {
            line("--\(boundary)")
            line("Content-Type: text/plain; charset=utf-8")
            line("Content-Disposition: form-data; name=\"\(name)\"")
            line("")
            line(value)
        }
        line("--\(boundary)")
        line("Content-Disposition: form-data; name=\"csr\"; filename=\"\(fileName)\"")
        line("Content-Type: application/octet-stream")
        line("")
        out.append(file)
        line("")
        line("--\(boundary)--")
        return out
    }

    /// Gerçek istek (URLSession; bloklar, arka plan iş parçacığında çağrılır).
    static func multipartPost(url: String, fields: [(String, String)], file: Data, fileName: String, cancel: Cancel) throws -> Data {
        try cancel.check()
        let boundary = UUID().uuidString.lowercased()
        var request = URLRequest(url: URL(string: url)!)
        request.httpMethod = "POST"
        request.timeoutInterval = 60
        request.setValue("multipart/form-data; boundary=\"\(boundary)\"", forHTTPHeaderField: "Content-Type")
        request.httpBody = multipartBody(boundary: boundary, fields: fields, file: file, fileName: fileName)
        let done = DispatchSemaphore(value: 0)
        let result = TvLocked<(Data?, URLResponse?, Error?)>((nil, nil, nil))
        let task = URLSession.shared.dataTask(with: request) { data, response, error in
            result.set((data, response, error))
            done.signal()
        }
        let tracked = try cancel.track { task.cancel() }
        defer { cancel.untrack(tracked) }
        task.resume()
        done.wait()
        try cancel.check()
        let (data, response, error) = result.get
        if let error { throw TvInstallError(.CERTIFICATE_FAILED, "Samsung'a ulaşılamadı: \(error.localizedDescription)") }
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        let reply = data ?? Data()
        guard (200...299).contains(status) else {
            throw TvInstallError(.CERTIFICATE_FAILED, "\(status) \(String(decoding: reply.prefix(300), as: UTF8.self))")
        }
        return reply
    }
}
