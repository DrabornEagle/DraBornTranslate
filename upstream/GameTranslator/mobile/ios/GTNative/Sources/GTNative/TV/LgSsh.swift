// SPDX-License-Identifier: AGPL-3.0-only

import Citadel
import CryptoKit
import NIOCore
import Foundation

/// LG geliştirici modunun SSH sunucusuna (9922, kullanıcı prisoner) Citadel ile bağlanır (Android: JschShell). TV'nin
/// anahtarı her fabrika ayarında değişir; anahtar denetlenmez (ssh'deki StrictHostKeyChecking=no gibi). webOS'un
/// dropbear'ı eski algoritmalar (ssh-rsa, SHA-1, diffie-hellman-group14-sha1, aes128-ctr) kullanır; Citadel'in
/// `SSHAlgorithms.all`'ı bunları ekler.
final class LgSsh: RemoteShell, @unchecked Sendable {
    private let host: String
    private let key: LgKey
    private let cancel: Cancel
    private let port: Int
    private var client: SSHClient?
    private var tracked: Int?

    init(host: String, key: LgKey, cancel: Cancel, port: Int = LgInstaller.sshPort) {
        self.host = host
        self.key = key
        self.cancel = cancel
        self.port = port
    }

    /// Key Server'dan gelen PKCS#1 dışı anahtarı kabul eder: parolasızsa olduğu gibi, parolalıysa TV ekranındaki
    /// parolayla (yazıldığı gibi ve büyük harfle) çözülebildiği sürece.
    static func acceptKey(_ pem: String, _ passphrase: String) throws -> LgKey {
        if !pem.contains("BEGIN OPENSSH PRIVATE KEY") {
            if pem.contains("PRIVATE KEY-----") { return LgKey(text: pem, passphrase: nil) }
            throw TvInstallError(.INSTALL_FAILED, "anahtar okunamadı")
        }
        if (try? Insecure.RSA.PrivateKey(sshRsa: pem)) != nil { return LgKey(text: pem, passphrase: nil) }
        let typed = passphrase.trimmingCharacters(in: .whitespacesAndNewlines)
        for candidate in Array(Set([typed, typed.uppercased()])) where !candidate.isEmpty {
            if (try? Insecure.RSA.PrivateKey(sshRsa: pem, decryptionKey: Data(candidate.utf8))) != nil {
                return LgKey(text: pem, passphrase: candidate)
            }
        }
        throw TvInstallError(.WRONG_PASSPHRASE)
    }

    private static func privateKey(_ key: LgKey) throws -> Insecure.RSA.PrivateKey {
        do {
            let text = try LegacyPemKey.openSSH(fromPem: key.text)
            return try Insecure.RSA.PrivateKey(sshRsa: text, decryptionKey: key.passphrase.map { Data($0.utf8) })
        } catch {
            throw TvInstallError(.KEY_REFUSED, "anahtar okunamadı: \(error)")
        }
    }

    private func privateKey() throws -> Insecure.RSA.PrivateKey { try Self.privateKey(key) }

    /// Anahtarın Citadel'deki ssh-rsa imzası (denemeler: dönüştürülen anahtarın doğru olduğunu gösterir).
    static func sshSignature(_ key: LgKey, _ data: Data) throws -> Data {
        (try privateKey(key).signature(for: data) as Insecure.RSA.Signature).rawRepresentation
    }

    private func session() async throws -> SSHClient {
        if let client, client.isConnected { return client }
        try cancel.check()
        let rsa = try privateKey()
        let connected: SSHClient
        do {
            connected = try await SSHClient.connect(
                host: host, port: port,
                authenticationMethod: .rsa(username: "prisoner", privateKey: rsa),
                hostKeyValidator: .acceptAnything(), reconnect: .never,
                algorithms: .all, connectTimeout: .seconds(8))
        } catch {
            try cancel.check()
            let refused = error is SSHClientError || error is AuthenticationFailed || "\(error)".localizedCaseInsensitiveContains("auth")
            throw TvInstallError(refused ? .KEY_REFUSED : .UNREACHABLE, "\(error)")
        }
        client = connected
        tracked = try cancel.track { Task { try? await connected.close() } }
        return connected
    }

    func run(_ command: String) async throws -> CommandResult {
        let client = try await session()
        return try await cancel.guardedAsync {
            var stdout = Data()
            var stderr = Data()
            var status = 0
            do {
                let stream = try await client.executeCommandStream(command)
                for try await chunk in stream {
                    switch chunk {
                    case .stdout(let buffer): stdout.append(contentsOf: buffer.readableBytesView)
                    case .stderr(let buffer): stderr.append(contentsOf: buffer.readableBytesView)
                    }
                }
            } catch let failed as SSHClient.CommandFailed {
                status = failed.exitCode
            } catch {
                throw TvInstallError(.CONNECTION_LOST, "\(error)")
            }
            return CommandResult(status: status, output: String(decoding: stdout, as: UTF8.self) + String(decoding: stderr, as: UTF8.self))
        }
    }

    /// Dosyayı SFTP ile yazar (ares-install gibi). TV'de SFTP yoksa kabuğun `printf` komutuyla parça parça yazar.
    func upload(_ data: Data, to path: String) async throws -> CommandResult {
        let client = try await session()
        return try await cancel.guardedAsync {
            do {
                let sftp = try await client.openSFTP()
                do {
                    let file = try await sftp.openFile(filePath: path, flags: [.write, .create, .truncate])
                    var offset = 0
                    while offset < data.count {
                        try cancel.check()
                        let length = min(32 * 1024, data.count - offset)
                        try await file.write(ByteBuffer(bytes: data.subdata(in: offset..<(offset + length))), at: UInt64(offset))
                        offset += length
                    }
                    try await file.close()
                    try? await sftp.close()
                    return CommandResult(status: 0, output: "")
                } catch {
                    try? await sftp.close()
                    throw error
                }
            } catch let error as TvInstallError {
                throw error
            } catch {
                if !client.isConnected { throw TvInstallError(.CONNECTION_LOST, "\(error)") }
                return try await uploadWithPrintf(data, to: path)
            }
        }
    }

    /// SFTP'siz yedek yol: her parça sekizli kaçışlarla `printf` ile dosyanın sonuna eklenir (kabuğun kendi komutu).
    private func uploadWithPrintf(_ data: Data, to path: String) async throws -> CommandResult {
        let first = try await run("rm -f '\(path)' && : > '\(path)'")
        if first.status != 0 { return first }
        var offset = 0
        while offset < data.count {
            try cancel.check()
            let length = min(16 * 1024, data.count - offset)
            var escaped = ""
            escaped.reserveCapacity(length * 4)
            for byte in data[data.startIndex + offset ..< data.startIndex + offset + length] {
                escaped += "\\" + String(byte, radix: 8)
            }
            let result = try await run("printf '\(escaped)' >> '\(path)'")
            if result.status != 0 { return result }
            offset += length
        }
        return CommandResult(status: 0, output: "")
    }

    func close() async {
        if let tracked { cancel.untrack(tracked) }
        tracked = nil
        let client = self.client
        self.client = nil
        try? await client?.close()
    }
}
