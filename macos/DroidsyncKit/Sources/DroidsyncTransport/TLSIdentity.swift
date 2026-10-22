import Foundation
import Security

public struct TLSIdentity {
    static let pkcs12Passphrase = "droidsync"

    public let identity: SecIdentity
    public let certificateDER: Data

    public var fingerprint: String { Fingerprint.hexSHA256(certificateDER) }

    public init(identity: SecIdentity, certificateDER: Data) {
        self.identity = identity
        self.certificateDER = certificateDER
    }

    public init(pkcs12: Data) throws {
        var items: CFArray?
        let options: [String: Any] = [
            kSecImportExportPassphrase as String: Self.pkcs12Passphrase,
            kSecImportToMemoryOnly as String: true,
        ]
        let status = SecPKCS12Import(pkcs12 as CFData, options as CFDictionary, &items)
        guard status == errSecSuccess,
              let array = items as? [[String: Any]],
              let first = array.first,
              let identityValue = first[kSecImportItemIdentity as String],
              CFGetTypeID(identityValue as CFTypeRef) == SecIdentityGetTypeID(),
              let chain = first[kSecImportItemCertChain as String] as? [SecCertificate],
              let leaf = chain.first else {
            throw TLSIdentityError.importFailed(status)
        }
        self.identity = identityValue as! SecIdentity
        self.certificateDER = SecCertificateCopyData(leaf) as Data
    }
}

public enum TLSIdentityError: Error, Equatable {
    case opensslFailed(String)
    case importFailed(OSStatus)
}

public enum TLSIdentityGenerator {
    public static func generate() throws -> TLSIdentity {
        try TLSIdentity(pkcs12: try generatePKCS12())
    }

    /// A new PKCS#12 identity that is known to import. The system's LibreSSL drops the leading zero
    /// byte of a P-256 private key that starts with one (about 1 key in 256), and Security can't
    /// pair such a key with its certificate: the import reports success but holds no identity. A
    /// key like that is simply generated again.
    public static func generatePKCS12() throws -> Data {
        var lastError: Error = TLSIdentityError.importFailed(errSecSuccess)
        for _ in 0..<maxAttempts {
            let pkcs12 = try generateCandidatePKCS12()
            do {
                _ = try TLSIdentity(pkcs12: pkcs12)
                return pkcs12
            } catch {
                lastError = error
            }
        }
        throw lastError
    }

    static let maxAttempts = 8

    private static func generateCandidatePKCS12() throws -> Data {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }

        let keyURL = directory.appendingPathComponent("key.pem")
        let certificateURL = directory.appendingPathComponent("cert.pem")
        let pkcs12URL = directory.appendingPathComponent("identity.p12")

        try run("/usr/bin/openssl", [
            "ecparam", "-name", "prime256v1", "-genkey", "-noout",
            "-out", keyURL.path,
        ])
        try run("/usr/bin/openssl", [
            "req", "-new", "-x509", "-key", keyURL.path, "-out", certificateURL.path,
            "-days", "3650", "-subj", "/CN=Droidsync",
        ])
        try run("/usr/bin/openssl", [
            "pkcs12", "-export", "-out", pkcs12URL.path,
            "-inkey", keyURL.path, "-in", certificateURL.path,
            "-passout", "pass:\(TLSIdentity.pkcs12Passphrase)",
        ])
        return try Data(contentsOf: pkcs12URL)
    }

    private static func run(_ executable: String, _ arguments: [String]) throws {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: executable)
        process.arguments = arguments
        let standardError = Pipe()
        process.standardError = standardError
        process.standardOutput = Pipe()
        try process.run()
        process.waitUntilExit()
        guard process.terminationStatus == 0 else {
            let message = String(
                data: standardError.fileHandleForReading.readDataToEndOfFile(),
                encoding: .utf8
            ) ?? "unknown openssl error"
            throw TLSIdentityError.opensslFailed(message)
        }
    }
}

public enum TLSIdentityStore {
    /// The Mac TLS identity (a PKCS#12 blob of key and certificate), whose fingerprint the paired
    /// phone pins, in the `SecretStore`.
    public static let fileName = "tls-identity.p12"

    public static func loadOrCreate(store: SecretStore = .default) throws -> TLSIdentity {
        if let data = try store.load(fileName) {
            return try TLSIdentity(pkcs12: data)
        }
        let pkcs12 = try TLSIdentityGenerator.generatePKCS12()
        try store.save(pkcs12, as: fileName)
        return try TLSIdentity(pkcs12: pkcs12)
    }
}
