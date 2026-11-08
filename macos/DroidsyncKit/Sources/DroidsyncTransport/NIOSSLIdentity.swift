import CryptoKit
import Foundation
import NIOSSL
import Security

public enum NIOSSLIdentityError: Error, Equatable {
    case unsupportedKey
}

public enum ECPrivateKeyPKCS8 {
    public static func der(from key: SecKey) throws -> Data {
        guard let raw = SecKeyCopyExternalRepresentation(key, nil) as Data? else {
            throw NIOSSLIdentityError.unsupportedKey
        }
        let scalar: Data
        if raw.count == 32 {
            scalar = raw
        } else if raw.count == 97, raw.first == 0x04 {
            scalar = Data(raw.suffix(32))
        } else {
            throw NIOSSLIdentityError.unsupportedKey
        }
        let privateKey = try P256.KeyAgreement.PrivateKey(rawRepresentation: scalar)
        return privateKey.derRepresentation
    }
}

extension TLSIdentity {
    public func nioContext() throws -> NIOSSLContext {
        let certificate = try NIOSSLCertificate(bytes: Array(certificateDER), format: .der)
        var key: SecKey?
        let status = SecIdentityCopyPrivateKey(identity, &key)
        guard status == errSecSuccess, let key else {
            throw NIOSSLIdentityError.unsupportedKey
        }
        let keyDER = try ECPrivateKeyPKCS8.der(from: key)
        let privateKey = try NIOSSLPrivateKey(bytes: Array(keyDER), format: .der)
        var configuration = TLSConfiguration.makeServerConfiguration(
            certificateChain: [.certificate(certificate)],
            privateKey: .privateKey(privateKey)
        )
        configuration.minimumTLSVersion = .tlsv13
        return try NIOSSLContext(configuration: configuration)
    }
}
