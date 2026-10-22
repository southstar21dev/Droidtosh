import CryptoKit
import NIOSSL
import XCTest
@testable import DroidsyncTransport

final class NIOSSLIdentityTests: XCTestCase {
    func testBuildsServerContextFromGeneratedIdentity() throws {
        let identity = try TLSIdentityGenerator.generate()
        let context = try identity.nioContext()
        XCTAssertEqual(context.configuration.applicationProtocols, [])
        XCTAssertEqual(context.configuration.minimumTLSVersion, .tlsv13)
    }

    func testPKCS8DerMatchesCryptoKit() throws {
        let identity = try TLSIdentityGenerator.generate()
        var secKey: SecKey?
        XCTAssertEqual(SecIdentityCopyPrivateKey(identity.identity, &secKey), errSecSuccess)
        let key = try XCTUnwrap(secKey)
        let der = try ECPrivateKeyPKCS8.der(from: key)
        XCTAssertNoThrow(try NIOSSLPrivateKey(bytes: Array(der), format: .der))

        let raw = try XCTUnwrap(SecKeyCopyExternalRepresentation(key, nil) as Data?)
        let scalar = try Self.scalar(from: raw)
        let converted = try P256.KeyAgreement.PrivateKey(derRepresentation: der)
        XCTAssertEqual(converted.rawRepresentation, scalar)
    }

    func testExportedScalarMatchesCertificatePublicKey() throws {
        let identity = try TLSIdentityGenerator.generate()
        var secKey: SecKey?
        XCTAssertEqual(SecIdentityCopyPrivateKey(identity.identity, &secKey), errSecSuccess)
        let key = try XCTUnwrap(secKey)
        let raw = try XCTUnwrap(SecKeyCopyExternalRepresentation(key, nil) as Data?)
        let scalar = try Self.scalar(from: raw)
        let privateKey = try P256.KeyAgreement.PrivateKey(rawRepresentation: scalar)

        var certificate: SecCertificate?
        XCTAssertEqual(SecIdentityCopyCertificate(identity.identity, &certificate), errSecSuccess)
        let certificateKey = try XCTUnwrap(SecCertificateCopyKey(try XCTUnwrap(certificate)))
        let certificatePublic = try XCTUnwrap(SecKeyCopyExternalRepresentation(certificateKey, nil) as Data?)
        XCTAssertEqual(privateKey.publicKey.x963Representation, certificatePublic)
    }

    private static func scalar(from raw: Data) throws -> Data {
        if raw.count == 32 {
            return raw
        } else if raw.count == 97, raw.first == 0x04 {
            return Data(raw.suffix(32))
        }
        throw NIOSSLIdentityError.unsupportedKey
    }
}
