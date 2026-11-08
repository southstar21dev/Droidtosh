import XCTest
@testable import DroidsyncTransport

final class SecretStoreTests: XCTestCase {
    private var root: URL!
    private var store: SecretStore!

    override func setUp() {
        root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        store = SecretStore(directory: root.appendingPathComponent("Secrets"))
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: root)
    }

    func testSaveLoadDelete() throws {
        XCTAssertNil(try store.load("secret"))
        try store.save(Data([1, 2, 3]), as: "secret")
        XCTAssertEqual(try store.load("secret"), Data([1, 2, 3]))
        try store.save(Data([4]), as: "secret")
        XCTAssertEqual(try store.load("secret"), Data([4]))
        store.delete("secret")
        XCTAssertNil(try store.load("secret"))
    }

    func testOnlyTheUserCanReadTheSecrets() throws {
        try store.save(Data("x".utf8), as: "secret")
        let manager = FileManager.default
        let folder = try manager.attributesOfItem(atPath: store.directory.path)[.posixPermissions] as? Int
        let file = try manager.attributesOfItem(atPath: store.directory.appendingPathComponent("secret").path)[.posixPermissions] as? Int
        XCTAssertEqual(folder, 0o700)
        XCTAssertEqual(file, 0o600)
        // No temporary files are left behind.
        XCTAssertEqual(try manager.contentsOfDirectory(atPath: store.directory.path), ["secret"])
    }

    func testSaveFailsWhenTheFolderIsReadOnly() throws {
        let manager = FileManager.default
        try manager.createDirectory(at: store.directory, withIntermediateDirectories: true)
        try manager.setAttributes([.immutable: true], ofItemAtPath: store.directory.path)
        defer { try? manager.setAttributes([.immutable: false], ofItemAtPath: store.directory.path) }
        XCTAssertThrowsError(try store.save(Data("secret".utf8), as: "secret"))
        XCTAssertNil(try store.load("secret"))
    }

    func testReturnsNilWhenNothingIsStored() throws {
        XCTAssertNil(try store.load("secret"))
    }

    func testAnUnreadableSecretThrowsInsteadOfLookingMissing() throws {
        // A plain file where the folder should be: the read fails with "not a directory".
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        try Data().write(to: store.directory)
        XCTAssertThrowsError(try store.load("secret"))
    }
}
