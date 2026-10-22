import XCTest
@testable import DroidsyncTransport

final class TLSIdentityTests: XCTestCase {
    func testGeneratePKCS12ProducesUsableIdentity() throws {
        let identity = try TLSIdentityGenerator.generate()
        XCTAssertEqual(identity.fingerprint.count, 64)
        XCTAssertFalse(identity.certificateDER.isEmpty)
    }

    private func temporaryStore() -> SecretStore {
        SecretStore(directory: FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString))
    }

    func testStoreIsStableAcrossLoads() throws {
        let store = temporaryStore()
        defer { try? FileManager.default.removeItem(at: store.directory) }
        let first = try TLSIdentityStore.loadOrCreate(store: store)
        let second = try TLSIdentityStore.loadOrCreate(store: store)
        XCTAssertEqual(first.fingerprint, second.fingerprint)
        XCTAssertNotNil(try store.load(TLSIdentityStore.fileName))
    }
}
